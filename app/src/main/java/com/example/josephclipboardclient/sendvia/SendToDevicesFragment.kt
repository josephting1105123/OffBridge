package com.EntropicSoftwareLab.ClipboardShare.sendvia

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.os.bundleOf
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.EntropicSoftwareLab.ClipboardShare.DiscoveredDevice
import com.EntropicSoftwareLab.ClipboardShare.Ports
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.SecurityVault
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import com.EntropicSoftwareLab.ClipboardShare.TransferHistoryManager
import com.EntropicSoftwareLab.ClipboardShare.TransferItem
import com.EntropicSoftwareLab.ClipboardShare.TransferRecord
import com.EntropicSoftwareLab.ClipboardShare.TransferSession
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.FilePreviewKind
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.FilePreviewUi
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.SendDeviceUi
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.SendToDevicesScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Socket

/**
 * Packs the user's file selection into a Parcelable-free Bundle
 * (URI strings + parallel relativePath/isFolder arrays) and back.
 */
object TransferItemArgs {
    const val ARG_URIS = "uris"
    const val ARG_PATHS = "relativePaths"
    const val ARG_FOLDERS = "isFolders"

    fun toBundle(items: List<TransferItem>): Bundle = bundleOf(
        ARG_URIS to ArrayList(items.map { it.uri.toString() }),
        ARG_PATHS to ArrayList(items.map { it.relativePath ?: "" }),
        ARG_FOLDERS to BooleanArray(items.size) { items[it].isFolder }
    )

    fun fromBundle(args: Bundle): List<TransferItem> {
        val uris = args.getStringArrayList(ARG_URIS) ?: return emptyList()
        val paths = args.getStringArrayList(ARG_PATHS) ?: ArrayList()
        val folders = args.getBooleanArray(ARG_FOLDERS) ?: BooleanArray(uris.size)
        return uris.indices.map { i ->
            TransferItem(
                uri = Uri.parse(uris[i]),
                relativePath = paths.getOrNull(i)?.takeIf { it.isNotEmpty() },
                isFolder = folders.getOrNull(i) ?: false
            )
        }
    }
}

/** Shared file-metadata helpers used by the LAN and off-grid send flows. */
object TransferItemExpander {

    fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            } finally { cursor?.close() }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "Unknown File"
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        var size = 0L
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        } finally { cursor?.close() }
        return size
    }

    private fun traverseFolder(doc: DocumentFile, currentPath: String, output: MutableList<TransferItem>) {
        for (file in doc.listFiles()) {
            val path = if (currentPath.isEmpty()) file.name ?: "Unknown" else "$currentPath/${file.name ?: "Unknown"}"
            if (file.isDirectory) {
                traverseFolder(file, path, output)
            } else {
                output.add(TransferItem(file.uri, path))
            }
        }
    }

    /** Expands folder items into their contained files (DFS), leaving plain files untouched. */
    fun expand(context: Context, items: List<TransferItem>): List<TransferItem> {
        val expanded = mutableListOf<TransferItem>()
        for (item in items) {
            if (item.isFolder) {
                val docFile = try { DocumentFile.fromTreeUri(context, item.uri) } catch (_: Exception) { null }
                if (docFile != null) traverseFolder(docFile, docFile.name ?: "Folder", expanded)
            } else {
                expanded.add(item)
            }
        }
        return expanded
    }

    /** Builds the (safeName, size) manifest used by the OFFER/MANIFEST protocol. */
    fun buildManifest(context: Context, expandedItems: List<TransferItem>): List<Pair<String, Long>> {
        return expandedItems.map { item ->
            val rawName = item.relativePath ?: getFileName(context, item.uri)
            val safeName = rawName.replace("|", "_").replace(":", "_")
            Pair(safeName, getFileSize(context, item.uri))
        }
    }
}

/**
 * Second screen of the "Connected Devices" send flow: read-only preview of
 * the chosen files, the paired-device list (relocated verbatim from
 * FileTransferFragment) and the bottom-pinned Send button.
 */
class SendToDevicesFragment : Fragment() {

    companion object {
        /** Fragment-manager tag for the modal sheet. */
        const val TAG = "send_to_devices"
        const val RESULT_KEY = "send_to_devices_result"
        const val RESULT_SENT = "sent"
        /** First outgoing sessionId of the batch — the listener opens its history detail. */
        const val RESULT_SESSION_ID = "session_id"

        fun newInstance(items: List<TransferItem>): SendToDevicesFragment {
            return SendToDevicesFragment().apply {
                arguments = TransferItemArgs.toBundle(items)
            }
        }
    }

    /** One queued offer of [executeMultiDeviceTransfer]: its session plus the record identifiers. */
    private data class PendingOffer(
        val sessionId: String,
        val targetIp: String,
        val targetName: String,
        val session: TransferSession,
    )

    private var items: List<TransferItem> = emptyList()
    private val selectedTargetIps = mutableSetOf<String>()
    private var firstPollDone = false
    private var pollTicks = 0

    // Compose state surfaced to SendToDevicesScreen (logic stays here; the screen is pure UI).
    private val previewsState = mutableStateOf<List<FilePreviewUi>>(emptyList())
    private val devicesState = mutableStateOf<List<SendDeviceUi>>(emptyList())
    private val selectedIpsState = mutableStateOf<Set<String>>(emptySet())
    private val loadingState = mutableStateOf(true)
    private val sendingState = mutableStateOf(false)

    private val fragmentJob = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + fragmentJob)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        items = arguments?.let { TransferItemArgs.fromBundle(it) } ?: emptyList()

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    SendToDevicesScreen(
                        previews = previewsState.value,
                        devices = devicesState.value,
                        selectedIps = selectedIpsState.value,
                        loading = loadingState.value,
                        sending = sendingState.value,
                        onBack = { parentFragmentManager.popBackStack() },
                        onToggleDevice = ::onToggleDevice,
                        onToggleSelectAll = ::onToggleSelectAll,
                        onSend = ::executeMultiDeviceTransfer,
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadPreviews()
        startDevicePolling()
    }

    /** Builds read-only preview tiles (image thumbnails decoded off the main thread). */
    private fun loadPreviews() {
        // Placeholder tiles immediately so the strip isn't empty while bitmaps decode.
        val ctx = requireContext()
        previewsState.value = items.map { item -> FilePreviewUi(name = previewName(ctx, item), kind = previewKind(ctx, item)) }
        uiScope.launch {
            val appContext = ctx.applicationContext
            val built = withContext(Dispatchers.IO) {
                items.map { item ->
                    val kind = previewKind(appContext, item)
                    val bitmap = if (kind == FilePreviewKind.IMAGE) decodeThumbnail(appContext, item.uri) else null
                    FilePreviewUi(name = previewName(appContext, item), kind = kind, bitmap = bitmap)
                }
            }
            if (isAdded) previewsState.value = built
        }
    }

    // Take an explicit Context (the app context off-thread): these run inside the IO block, so
    // requireContext() here would crash if the fragment detaches mid-decode (back out / tab switch).
    private fun previewKind(context: Context, item: TransferItem): FilePreviewKind {
        if (item.isFolder) return FilePreviewKind.FOLDER
        val mime = try {
            context.contentResolver.getType(item.uri)
        } catch (_: Exception) { null } ?: "application/octet-stream"
        if (mime.startsWith("image/")) return FilePreviewKind.IMAGE
        val displayPath = item.relativePath ?: TransferItemExpander.getFileName(context, item.uri)
        if (displayPath.matches(Regex("Text \\d+"))) return FilePreviewKind.TEXT
        return FilePreviewKind.OTHER
    }

    private fun previewName(context: Context, item: TransferItem): String {
        if (item.isFolder) {
            val docFile = try { DocumentFile.fromTreeUri(context, item.uri) } catch (_: Exception) { null }
            return docFile?.name ?: "Folder"
        }
        val displayPath = item.relativePath ?: TransferItemExpander.getFileName(context, item.uri)
        val parts = displayPath.split("/")
        return if (parts.size > 1) "${parts[0]}/.../${parts.last()}" else parts.last()
    }

    private fun decodeThumbnail(context: Context, uri: Uri): androidx.compose.ui.graphics.ImageBitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val maxPx = 240
                    val w = info.size.width
                    val h = info.size.height
                    val largest = maxOf(w, h)
                    if (largest > maxPx) {
                        val scale = maxPx.toFloat() / largest
                        decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)?.asImageBitmap()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun onToggleDevice(ip: String) {
        if (selectedTargetIps.contains(ip)) selectedTargetIps.remove(ip) else selectedTargetIps.add(ip)
        selectedIpsState.value = selectedTargetIps.toSet()
    }

    private fun onToggleSelectAll(selectAll: Boolean) {
        if (selectAll) {
            selectedTargetIps.addAll(devicesState.value.map { it.ip })
        } else {
            selectedTargetIps.clear()
        }
        selectedIpsState.value = selectedTargetIps.toSet()
    }

    private fun startDevicePolling() {
        uiScope.launch {
            while (isActive) {
                refreshDeviceListUI()
                delay(1000)
            }
        }
    }

    private fun refreshDeviceListUI() {
        if (!isAdded) return
        val allNodes = TransferDaemon.discoveredNodes.values.toList()
        val (paired, _) = allNodes.partition { SecurityVault.getKeyForNode(requireContext(), it.ip) != null }
        val sortedPaired = paired.sortedBy { it.name }

        pollTicks++
        // First tick that finds a device — or three empty ticks — ends the skeleton phase.
        if (!firstPollDone && (sortedPaired.isNotEmpty() || pollTicks >= 3)) {
            firstPollDone = true
            loadingState.value = false
        }
        if (!firstPollDone) return

        devicesState.value = sortedPaired.map { it.toUi() }

        // Prune disconnected devices from the selection so Send can't target a vanished node.
        val validIps = sortedPaired.map { it.ip }.toSet()
        if (selectedTargetIps.retainAll(validIps)) {
            selectedIpsState.value = selectedTargetIps.toSet()
        }
    }

    private fun DiscoveredDevice.toUi(): SendDeviceUi =
        SendDeviceUi(ip = ip, name = name, isWindows = os.equals("Windows", ignoreCase = true))

    private fun executeMultiDeviceTransfer() {
        val sendItems = items.toList()
        val ips = selectedTargetIps.toList()
        if (sendItems.isEmpty() || ips.isEmpty() || sendingState.value) return

        // Drives the bottom button to "Sending..." / disabled (mirrors the old btnSend tweaks).
        sendingState.value = true

        val appContext = requireContext().applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            val expandedItems = TransferItemExpander.expand(appContext, sendItems)
            val manifestList = TransferItemExpander.buildManifest(appContext, expandedItems)
            val fileUriStrings = expandedItems.map { it.uri.toString() }

            // Register every target's session + pending record BEFORE any handshake so the
            // history detail we navigate to below exists immediately; the offers themselves
            // keep running in this detached scope after the screen closes.
            val offers = ips.map { targetIp ->
                val sessionId = java.util.UUID.randomUUID().toString()
                val session = TransferSession(
                    sessionId = sessionId,
                    senderName = TransferDaemon.customDeviceName,
                    totalFiles = expandedItems.size,
                    manifest = manifestList,
                    fileUris = fileUriStrings,
                    targetIp = targetIp,
                    isIncoming = false,
                    isPaused = false
                )
                TransferDaemon.putActiveSession(sessionId, session)

                val targetName = TransferDaemon.discoveredNodes[targetIp]?.name ?: targetIp
                TransferHistoryManager.addRecord(appContext, TransferRecord(
                    sessionId = sessionId,
                    deviceName = targetName,
                    direction = "outgoing",
                    fileNames = manifestList.map { it.first.substringAfterLast('/') },
                    totalBytes = manifestList.sumOf { it.second },
                    timestamp = System.currentTimeMillis(),
                    status = "pending",
                    filesCompleted = 0,
                    totalFiles = expandedItems.size,
                    fileSizes = manifestList.map { it.second },
                    currentFileOffset = 0L,
                    transport = "lan"
                ))
                PendingOffer(sessionId, targetIp, targetName, session)
            }

            // Close this screen and jump to the (first) outgoing record's history detail right
            // away — the sender watches progress there while the offers continue below.
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    parentFragmentManager.setFragmentResult(
                        RESULT_KEY,
                        bundleOf(RESULT_SENT to true, RESULT_SESSION_ID to offers.first().sessionId)
                    )
                    parentFragmentManager.popBackStack()
                }
            }

            var hasFailures = false
            for ((sessionId, targetIp, targetName, session) in offers) {

                try {
                    Socket(targetIp, Ports.FILE_TRANSFER).use { fileSocket ->
                        val outStream = fileSocket.getOutputStream()
                        val offerHeader = "OFFER|$sessionId|${TransferDaemon.customDeviceName}|${expandedItems.size}\n"
                        var manifestStr = "MANIFEST|"
                        for (manifestItem in manifestList) {
                            manifestStr += "${manifestItem.first}:${manifestItem.second}|"
                        }
                        manifestStr += "\n"

                        outStream.write(offerHeader.toByteArray(Charsets.UTF_8))
                        outStream.write(manifestStr.toByteArray(Charsets.UTF_8))
                        outStream.flush()

                        // Wait for the receiver's next line.
                        fileSocket.soTimeout = 10000
                        val inStream = fileSocket.getInputStream()
                        fun readLine(): String {
                            val baos = java.io.ByteArrayOutputStream()
                            var c = inStream.read()
                            while (c != -1 && c != '\n'.code) {
                                baos.write(c)
                                c = inStream.read()
                            }
                            return baos.toString("UTF-8")
                        }
                        var response = readLine()

                        // AUTH2: a new receiver may challenge us to prove we hold the shared pairing key
                        // before it shows its incoming-file popup. Answer with HMAC-SHA256(pairingKey,
                        // nonce), then read the real OFFER_RECEIVED. An old receiver sends OFFER_RECEIVED
                        // directly and we fall straight through — so we tolerate BOTH sequences without
                        // depending on the peer's advertised caps.
                        if (response.startsWith("OFFER_CHALLENGE|")) {
                            val nonce = android.util.Base64.decode(response.removePrefix("OFFER_CHALLENGE|"), android.util.Base64.NO_WRAP)
                            val pairingKey = SecurityVault.getKeyForNode(appContext, targetIp)
                                ?: throw Exception("No pairing key to answer OFFER_CHALLENGE for $targetIp")
                            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                            mac.init(javax.crypto.spec.SecretKeySpec(pairingKey, "HmacSHA256"))
                            val proof = mac.doFinal(nonce)
                            outStream.write("OFFER_PROOF|${android.util.Base64.encodeToString(proof, android.util.Base64.NO_WRAP)}\n".toByteArray(Charsets.UTF_8))
                            outStream.flush()
                            response = readLine()
                        }

                        if (response != "OFFER_RECEIVED") {
                            throw Exception("Receiver did not acknowledge offer")
                        }

                        // Stop here. Receiver will connect back with ACCEPT_OFFER or DECLINE_OFFER.
                    }

                    // Keep session paused so the loop doesn't kill it and it waits for Accept
                    session.isPaused = true

                } catch (e: Exception) {
                    e.printStackTrace()
                    hasFailures = true
                    // Keep the session alive and mark as paused so receiver can reconnect
                    session.isPaused = true
                    session.lastUpdated = System.currentTimeMillis()
                    TransferHistoryManager.addRecord(appContext, TransferRecord(
                        sessionId = sessionId,
                        deviceName = targetName,
                        direction = "outgoing",
                        fileNames = manifestList.map { it.first.substringAfterLast('/') },
                        totalBytes = manifestList.sumOf { it.second },
                        timestamp = System.currentTimeMillis(),
                        status = "paused",
                        filesCompleted = session.currentFileIndex,
                        totalFiles = expandedItems.size,
                        fileSizes = manifestList.map { it.second },
                        currentFileOffset = session.currentOffset,
                        transport = "lan"
                    ))
                }
            }

            // The fragment already popped when the offers were queued; toast via app context.
            withContext(Dispatchers.Main) {
                val message = if (hasFailures) appContext.getString(R.string.toast_transfer_interrupted) else appContext.getString(R.string.toast_batch_transfer_complete)
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fragmentJob.cancel()
    }
}

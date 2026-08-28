package com.EntropicSoftwareLab.ClipboardShare.sendvia

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.EntropicSoftwareLab.ClipboardShare.ImageThumbnails
import com.EntropicSoftwareLab.ClipboardShare.MainActivity
import com.EntropicSoftwareLab.ClipboardShare.PairingActivity
import com.EntropicSoftwareLab.ClipboardShare.Ports
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.SecurityVault
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import com.EntropicSoftwareLab.ClipboardShare.TransferHistoryManager
import com.EntropicSoftwareLab.ClipboardShare.TransferItem
import com.EntropicSoftwareLab.ClipboardShare.TransferRecord
import com.EntropicSoftwareLab.ClipboardShare.TransferSession
import com.EntropicSoftwareLab.ClipboardShare.TransferUtils
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
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.fragment.app.FragmentManager
import com.EntropicSoftwareLab.ClipboardShare.FILE_BRIDGE_PREFS
import com.EntropicSoftwareLab.ClipboardShare.isLiteMode

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
        // -1 = "provider didn't say", distinct from a genuinely 0-byte file. Some cloud/SAF
        // providers return a NULL SIZE column, which getLong reads as 0 — the sender then streamed
        // 0 bytes and both sides marked the transfer completed: a silently empty received file.
        var size = -1L
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        } finally { cursor?.close() }
        if (size < 0) {
            // Fallback for providers that omit SIZE: the open file's actual length.
            size = try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            } catch (_: Exception) { -1L }
        }
        return if (size >= 0) size else 0L
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
            Pair(TransferUtils.sanitizeOutgoingManifestName(rawName), getFileSize(context, item.uri))
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

        /**
         * First target of the offer batch currently in flight, or null when idle. Process-level
         * rather than per-instance because the offer loop runs in a detached scope that outlives
         * this Fragment: now that the screen no longer pops the moment Send is tapped, a rotation
         * mid-send would otherwise rebuild it with a fresh, re-enabled Send button and let the user
         * fire a duplicate OFFER at a device already receiving one.
         */
        @Volatile
        private var activeSendTargetIp: String? = null

        fun newInstance(items: List<TransferItem>, isMultiple: Boolean = true): SendToDevicesFragment {
            return SendToDevicesFragment().apply {
                arguments = TransferItemArgs.toBundle(items).apply {
                    putBoolean("is_multiple", isMultiple)
                }
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
    // Paired devices only — feeds Pro's multi-select screen AND Lite's Multiple-device mode, both of
    // which must only ever let you select/send to an already-paired device.
    private val devicesState = mutableStateOf<List<SendDeviceUi>>(emptyList())
    // Paired + unpaired — feeds ONLY Lite's One-Device tri-state card, which needs to show an
    // unpaired device with a "Pair" action instead of just hiding it.
    private val allDevicesState = mutableStateOf<List<SendDeviceUi>>(emptyList())
    private val selectedIpsState = mutableStateOf<Set<String>>(emptySet())
    private val loadingState = mutableStateOf(true)
    private val sendingState = mutableStateOf(false)
    // The device Lite's per-device card marks as in flight (Pro's batch reports its first target and
    // doesn't read this). Held separately from selectedTargetIps: the 1 Hz refreshDeviceListUI
    // prunes that set to whatever is still discovered + paired, so deriving the "Sending" label
    // from it flipped the row back to "Send" the moment the target blinked out of discovery —
    // while the transfer was still running. Mirrors [activeSendTargetIp] for Compose to observe.
    private val sendingIpState = mutableStateOf<String?>(null)
    private var isLite = false

    private val fragmentJob = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + fragmentJob)

    /** Lite ends the send flow back at the hub (clears the whole child back stack); Pro pops one
     *  level back to the Send tab, where the history detail then opens. */
    private fun endSendFlow() {
        if (isLite) {
            parentFragmentManager.popBackStack(
                null, FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
        } else {
            parentFragmentManager.popBackStack()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        items = arguments?.let { TransferItemArgs.fromBundle(it) } ?: emptyList()
        val isMultiple = arguments?.getBoolean("is_multiple", true) ?: true

        val prefs = requireContext().getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
        isLite = isLiteMode(requireContext())

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    if (isLite) {
                        com.EntropicSoftwareLab.ClipboardShare.ui.screens.LiteSendToScreen(
                            deviceName = TransferDaemon.customDeviceName,
                            // Editable here (same as System Status): trim and persist only when
                            // non-empty, mirroring SystemStatusFragment.onDeviceNameChange — otherwise
                            // clearing the field would advertise/pair the device under a blank name.
                            onDeviceNameChange = { newName ->
                                val trimmed = newName.trim()
                                if (trimmed.isNotEmpty()) {
                                    TransferDaemon.customDeviceName = trimmed
                                    prefs.edit().putString("device_name", trimmed).apply()
                                }
                            },
                            // Lite Send-To is always the per-device tri-state card now (One/Multiple was
                            // removed), so it needs the paired+unpaired list to offer "Pair" on unpaired
                            // rows — see allDevicesState's doc comment above.
                            devices = allDevicesState.value,
                            sendingIp = sendingIpState.value,
                            onSendToDevice = { ip ->
                                // sendingState is a single fragment-wide flag (one transfer at a
                                // time). Without this guard, tapping a second device's "Send" while
                                // the first is still in flight would overwrite selectedTargetIps —
                                // executeMultiDeviceTransfer's own re-entrancy check would then
                                // silently drop the second tap, but the "Sending…" label (driven by
                                // selectedTargetIps.first()) would have already jumped to the wrong
                                // device. Ignoring taps while a send is in flight keeps both correct.
                                if (!sendingState.value) {
                                    selectedTargetIps.clear()
                                    selectedTargetIps.add(ip)
                                    selectedIpsState.value = selectedTargetIps.toSet()
                                    executeMultiDeviceTransfer()
                                }
                            },
                            // No tab shell in Lite (openPairingTab is a no-op there) — launch pairing
                            // for this specific device directly, same as HistoryFragment.onPair.
                            onPairDevice = { ip, name ->
                                startActivity(
                                    Intent(requireContext(), PairingActivity::class.java).apply {
                                        putExtra("TARGET_NAME", name)
                                        putExtra("TARGET_IP", ip)
                                    }
                                )
                            },
                            onDone = { endSendFlow() }
                        )
                    } else {
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
                            // Tab switch only: this screen stays (hidden) on the back stack with the
                            // selection intact, so the user pairs a device and returns to send.
                            onGoToPairing = { (activity as? MainActivity)?.openPairingTab() },
                        )
                    }
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

    /** Bounded decode shared with the send-queue grid and the fullscreen viewer — see
     *  [ImageThumbnails] for why a preview must never decode at full resolution. The old pre-API-28
     *  branch here decoded whole (MediaStore.Images.Media.getBitmap) and was dead code anyway:
     *  minSdk is 29. */
    private fun decodeThumbnail(context: Context, uri: Uri): ImageBitmap? =
        ImageThumbnails.decode(context, uri, ImageThumbnails.GRID_MAX_PX)?.asImageBitmap()

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
        // Re-adopt the process-level send lock every tick, so an instance rebuilt by a config change
        // mid-send comes back showing "Sending" and a disabled button rather than an idle one.
        val inFlight = activeSendTargetIp
        if (sendingIpState.value != inFlight) sendingIpState.value = inFlight
        if (sendingState.value != (inFlight != null)) sendingState.value = inFlight != null

        val allNodes = TransferDaemon.discoveredNodes.values.toList()
        val sortedNodes = allNodes.sortedBy { it.name }

        pollTicks++
        // First tick that finds a device — or three empty ticks — ends the skeleton phase.
        if (!firstPollDone && (sortedNodes.isNotEmpty() || pollTicks >= 3)) {
            firstPollDone = true
            loadingState.value = false
        }
        if (!firstPollDone) return

        // Build the UI model once (toUi resolves isPaired via a single getKeyForNode per device),
        // then derive both lists from it: allDevices feeds Lite's One-Device tri-state card, which
        // needs unpaired rows to offer "Pair"; paired-only feeds Pro's multi-select and Lite's
        // Multiple mode. (Mapping once avoids decrypting each node's key 2–3× per poll tick.)
        val allUi = sortedNodes.map { it.toUi() }
        allDevicesState.value = allUi
        val pairedUi = allUi.filter { it.isPaired }
        devicesState.value = pairedUi

        // Prune the multi-select set to devices that are still discovered AND still paired, so a node
        // that goes offline — or loses its pairing mid-session while staying online — can't linger in
        // the selection and be sent to. validIps must be the paired set, not all discovered nodes.
        val validIps = pairedUi.map { it.ip }.toSet()
        if (selectedTargetIps.retainAll(validIps)) {
            selectedIpsState.value = selectedTargetIps.toSet()
        }
    }

    private fun DiscoveredDevice.toUi(): SendDeviceUi =
        SendDeviceUi(
            ip = ip, 
            name = name, 
            isWindows = os.equals("Windows", ignoreCase = true),
            isPaired = SecurityVault.getKeyForNode(requireContext(), ip) != null
        )

    private fun executeMultiDeviceTransfer() {
        val sendItems = items.toList()
        val ips = selectedTargetIps.toList()
        // activeSendTargetIp, not sendingState: the lock has to survive a config change (see its doc).
        if (sendItems.isEmpty() || ips.isEmpty() || activeSendTargetIp != null) return

        // Drives the bottom button (Pro) and the tapped device's capsule (Lite) to "Sending..." /
        // disabled. The screen deliberately STAYS on the device list for the whole send — it used to
        // pop the moment the offers were queued, which in Lite (endSendFlow clears the entire child
        // back stack) threw the user out to the hub the instant they tapped a device's Send.
        activeSendTargetIp = ips.first()
        sendingState.value = true
        sendingIpState.value = ips.first()

        val appContext = requireContext().applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            val expandedItems = TransferItemExpander.expand(appContext, sendItems)
            val manifestList = TransferItemExpander.buildManifest(appContext, expandedItems)
            val fileUriStrings = expandedItems.map { it.uri.toString() }

            // Register every target's session + pending record BEFORE any handshake so the history
            // detail Pro opens on completion already exists; the offers themselves keep running in
            // this detached scope even if the user leaves the screen first.
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
                    fileNames = manifestList.map { TransferUtils.manifestDisplayLeaf(it.first) },
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

            var hasFailures = false
            for ((sessionId, targetIp, targetName, session) in offers) {

                try {
                    // Bounded connect (mirrors DownloadEngine's resume path): the 2-arg Socket
                    // ctor blocks on the OS default SYN timeout — a vanished peer stalled the
                    // whole multi-device offer loop for tens of seconds (AND-24). connect runs
                    // INSIDE use so a timeout still closes the socket instead of leaking the fd.
                    Socket().use { fileSocket ->
                        fileSocket.connect(java.net.InetSocketAddress(targetIp, Ports.FILE_TRANSFER), 8000)
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

                        // Wait for the receiver's next line (shared size-capped reader — the old
                        // hand-rolled loop had no byte cap, AND-24).
                        fileSocket.soTimeout = 10000
                        val inStream = fileSocket.getInputStream()
                        var response = com.EntropicSoftwareLab.ClipboardShare.TransferUtils.readUntilNewline(inStream)

                        // AUTH2: a new receiver may challenge us to prove we hold the shared pairing key
                        // before it shows its incoming-file popup. Answer with HMAC-SHA256(pairingKey,
                        // nonce), then read the real OFFER_RECEIVED. An old receiver sends OFFER_RECEIVED
                        // directly and we fall straight through — so we tolerate BOTH sequences without
                        // depending on the peer's advertised caps.
                        if (response.startsWith("OFFER_CHALLENGE|")) {
                            val nonce = Base64.decode(response.removePrefix("OFFER_CHALLENGE|"), Base64.NO_WRAP)
                            val pairingKey = SecurityVault.getKeyForNode(appContext, targetIp)
                                ?: throw Exception("No pairing key to answer OFFER_CHALLENGE for $targetIp")
                            val proof = com.EntropicSoftwareLab.ClipboardShare.transport.SessionCrypto.hmacSha256(pairingKey, nonce)
                            outStream.write("OFFER_PROOF|${android.util.Base64.encodeToString(proof, android.util.Base64.NO_WRAP)}\n".toByteArray(Charsets.UTF_8))
                            outStream.flush()
                            response = com.EntropicSoftwareLab.ClipboardShare.TransferUtils.readUntilNewline(inStream)
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
                    // Surface the precise reason to logcat (Android's real diagnostics surface) — the
                    // generic "interrupted" toast + "paused" history below otherwise hides WHY the send
                    // failed. Key case for the phone->laptop "no popup" bug: "No pairing key to answer
                    // OFFER_CHALLENGE for <ip>" means the phone can't answer the receiver's AUTH2
                    // challenge (missing key for the targeted IP) vs a plain connect timeout.
                    Log.w("OffBridgeXfer", "OFFER to $targetName ($targetIp) failed: ${e.message}", e)
                    hasFailures = true
                    // Keep the session alive and mark as paused so receiver can reconnect
                    session.isPaused = true
                    session.lastUpdated = System.currentTimeMillis()
                    TransferHistoryManager.addRecord(appContext, TransferRecord(
                        sessionId = sessionId,
                        deviceName = targetName,
                        direction = "outgoing",
                        fileNames = manifestList.map { TransferUtils.manifestDisplayLeaf(it.first) },
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

            // Every offer is out. Release the in-flight lock so the list is usable again, then toast
            // via the app context (the user may have left in the meantime).
            withContext(Dispatchers.Main) {
                // Release the process-level lock first: a rebuilt instance re-reads it on its next
                // poll tick, so it must be clear before this instance's own state is.
                activeSendTargetIp = null
                sendingState.value = false
                sendingIpState.value = null
                val message = if (hasFailures) appContext.getString(R.string.toast_transfer_interrupted) else appContext.getString(R.string.toast_batch_transfer_complete)
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()

                // Pro's completion path: report the batch so the Send tab opens the outgoing
                // record's history detail, then close. Lite has no such listener (endSendFlow would
                // clear the whole stack), so it stays on the device list and the user leaves via
                // Done — which is the point of the fix above.
                if (isAdded && !isLite) {
                    parentFragmentManager.setFragmentResult(
                        RESULT_KEY,
                        bundleOf(RESULT_SENT to true, RESULT_SESSION_ID to offers.first().sessionId)
                    )
                    endSendFlow()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fragmentJob.cancel()
    }
}

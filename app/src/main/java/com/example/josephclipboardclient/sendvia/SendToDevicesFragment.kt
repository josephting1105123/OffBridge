package com.EntropicSoftwareLab.ClipboardShare.sendvia

import android.content.Context
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
import com.EntropicSoftwareLab.ClipboardShare.ImageThumbnails
import com.EntropicSoftwareLab.ClipboardShare.MainActivity
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import com.EntropicSoftwareLab.ClipboardShare.TransferItem
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
import androidx.compose.ui.graphics.ImageBitmap

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

        fun newInstance(items: List<TransferItem>, isMultiple: Boolean = true): SendToDevicesFragment {
            return SendToDevicesFragment().apply {
                arguments = TransferItemArgs.toBundle(items).apply {
                    putBoolean("is_multiple", isMultiple)
                }
            }
        }
    }

    private var items: List<TransferItem> = emptyList()
    private val selectedTargetIps = mutableSetOf<String>()
    private var firstPollDone = false
    private var pollTicks = 0

    // Compose state surfaced to SendToDevicesScreen (logic stays here; the screen is pure UI).
    private val previewsState = mutableStateOf<List<FilePreviewUi>>(emptyList())
    // Paired devices only — feeds Pro's multi-select screen, which must only ever let you
    // select/send to an already-paired device.
    private val devicesState = mutableStateOf<List<SendDeviceUi>>(emptyList())
    private val selectedIpsState = mutableStateOf<Set<String>>(emptySet())
    private val loadingState = mutableStateOf(true)
    private val sendingState = mutableStateOf(false)

    private val fragmentJob = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + fragmentJob)

    /** Pops this overlay back to the Send tab beneath. */
    private fun endSendFlow() {
        parentFragmentManager.popBackStack()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        items = arguments?.let { TransferItemArgs.fromBundle(it) } ?: emptyList()
        val isMultiple = arguments?.getBoolean("is_multiple", true) ?: true

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
                        // Tab switch only: this screen stays (hidden) on the back stack with the
                        // selection intact, so the user pairs a device and returns to send.
                        onGoToPairing = { (activity as? MainActivity)?.openPairingTab() },
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
        val inFlight = LanOfferSender.activeTargetIp
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

        // Resolve isPaired once per device (toSendDeviceUi calls getKeyForNode) and keep paired only.
        val pairedUi = sortedNodes.map { it.toSendDeviceUi(requireContext()) }.filter { it.isPaired }
        devicesState.value = pairedUi

        // Prune the multi-select set to devices that are still discovered AND still paired, so a node
        // that goes offline — or loses its pairing mid-session while staying online — can't linger in
        // the selection and be sent to. validIps must be the paired set, not all discovered nodes.
        val validIps = pairedUi.map { it.ip }.toSet()
        if (selectedTargetIps.retainAll(validIps)) {
            selectedIpsState.value = selectedTargetIps.toSet()
        }
    }

    private fun executeMultiDeviceTransfer() {
        val sendItems = items.toList()
        val ips = selectedTargetIps.toList()
        // LanOfferSender.activeTargetIp, not sendingState: the lock has to survive a config change (see its doc).
        if (sendItems.isEmpty() || ips.isEmpty() || LanOfferSender.activeTargetIp != null) return

        // Drives the bottom Send button to "Sending..." / disabled while the offers are in flight.
        LanOfferSender.activeTargetIp = ips.first()
        sendingState.value = true

        val appContext = requireContext().applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            val result = LanOfferSender.sendOffers(appContext, sendItems, ips)
            val hasFailures = result.hasFailures

            // Every offer is out. Release the in-flight lock so the list is usable again, then toast
            // via the app context (the user may have left in the meantime).
            withContext(Dispatchers.Main) {
                // Release the process-level lock first: a rebuilt instance re-reads it on its next
                // poll tick, so it must be clear before this instance's own state is.
                LanOfferSender.activeTargetIp = null
                sendingState.value = false
                val message = if (hasFailures) appContext.getString(R.string.toast_transfer_interrupted) else appContext.getString(R.string.toast_batch_transfer_complete)
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()

                // Report the batch + session id so the Send tab clears its selection and opens
                // the outgoing record's history detail, then pop back to the Send tab.
                if (isAdded) {
                    parentFragmentManager.setFragmentResult(
                        RESULT_KEY,
                        bundleOf(RESULT_SENT to true, RESULT_SESSION_ID to result.firstSessionId)
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

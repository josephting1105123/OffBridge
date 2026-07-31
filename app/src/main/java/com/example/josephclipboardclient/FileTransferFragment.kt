package com.EntropicSoftwareLab.ClipboardShare

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.fragment.app.Fragment
import com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridSendActivity
import com.EntropicSoftwareLab.ClipboardShare.sendvia.SendToDevicesFragment
import com.EntropicSoftwareLab.ClipboardShare.sendvia.SendViaChoice
import com.EntropicSoftwareLab.ClipboardShare.sendvia.TransferItemArgs
import com.EntropicSoftwareLab.ClipboardShare.sendvia.TransferItemExpander
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.FileTransferScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.EntropicSoftwareLab.ClipboardShare.ui.components.circularReveal
import android.graphics.Rect
import android.widget.EditText
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier

data class TransferItem(val uri: Uri, val relativePath: String?, val isFolder: Boolean = false)

class FileTransferFragment : Fragment() {

    // Source-of-truth selection list (read directly by the send flow, clearSelectedFiles, etc.).
    private var selectedFiles: MutableList<TransferItem> = mutableListOf()
    private var textCounter = 1

    // Lite's screen calls onSendViaChoose directly (no SendViaOverlay, so showSendViaState never
    // flips true); the double-fire guard below must not swallow those calls. Set in onCreateView.
    private var isLiteMode = false

    // Compose-observable mirrors of the selection state.
    private val selectedFilesState = mutableStateOf<List<TransferItem>>(emptyList())
    private val sendEnabledState = mutableStateOf(false)
    // Total bytes queued (folders DFS-expanded); recomputed off the main thread per selection change.
    private val totalSizeBytesState = mutableStateOf(0L)
    private var totalSizeJob: Job? = null
    // Multi-select (delete) mode for the queued-files grid. Entered via the "Select" capsule; long-press
    // stays drag-reorder. Indices are valid only while in selection mode (no add/reorder happens then).
    private val selectionModeState = mutableStateOf(false)
    private val selectedIndicesState = mutableStateOf<Set<Int>>(emptySet())

    /** Drives the Compose Send-Files tutorial overlay (item 1: migrated off the legacy View overlay). */
    private val showSendTutorialState = mutableStateOf(false)

    /** Window bounds of the header "How to use" chip — the forced tour's closer step spotlights it. */
    var howToUseChipBounds: Rect? = null
        private set

    /** Drives the in-screen Send-Via chooser overlay (slides up from behind the bottom nav bar). */
    private val showSendViaState = mutableStateOf(false)

    // M-11: this Job is cancelled in onDestroyView; if the view is re-created the old
    // scope would stay cancelled and silently no-op launches. Recreated in onViewCreated.
    private var fragmentJob = Job()
    private var uiScope = CoroutineScope(Dispatchers.Main + fragmentJob)

    /** Carries the QR-vs-NFC presentation across the runtime-permission round-trip. */
    private var pendingPresentation = OffGridSendActivity.PRESENTATION_QR

    private val requestOffGridPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            launchOffGridSend(pendingPresentation)
        } else {
            Toast.makeText(requireContext(), getString(R.string.offgrid_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private val selectMultipleFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val newItems = uris.map { TransferItem(it, null) }
            val combined = selectedFiles.toMutableList()
            combined.addAll(newItems)
            handleMultipleFilesSelection(combined)
        }
    }

    private val selectMultipleMediaLauncher = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            val newItems = uris.map { TransferItem(it, null) }
            val combined = selectedFiles.toMutableList()
            combined.addAll(newItems)
            handleMultipleFilesSelection(combined)
        }
    }

    private val selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            // Take persistable permission so the URI stays valid
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }

            val combined = selectedFiles.toMutableList()
            combined.add(TransferItem(treeUri, null, true))
            handleMultipleFilesSelection(combined)
        }
    }

    private val selectAppLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = result.data?.getParcelableArrayListExtra<Uri>("app_uris")
            val names = result.data?.getStringArrayListExtra("app_names")
            if (uris != null && names != null) {
                val newItems = uris.indices.map { TransferItem(uris[it], names[it]) }
                val combined = selectedFiles.toMutableList()
                combined.addAll(newItems)
                handleMultipleFilesSelection(combined)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // A present-but-disabled adapter stays enabled; the downstream NFC flow guides turning it on.
        val nfcUnavailable = NfcAdapter.getDefaultAdapter(requireContext()) == null
        val prefs = requireContext().getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
        val isLite = isLiteMode(requireContext())
        isLiteMode = isLite
        
        val revealX = arguments?.getFloat("reveal_x", -1f) ?: -1f
        val revealY = arguments?.getFloat("reveal_y", -1f) ?: -1f

        return ComposeView(requireContext()).apply{
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // Lite adds this over the hub — consume touches so empty areas don't fall through to the
            // hub tiles beneath.
            isClickable = true
            setContent {
                AppTheme {
                    if (isLite) {
                        Box(
                            modifier = Modifier.circularReveal(revealX, revealY)
                        ) {
                            com.EntropicSoftwareLab.ClipboardShare.ui.screens.LiteSendScreen(
                                selectedFiles = selectedFilesState.value,
                                totalSizeBytes = totalSizeBytesState.value,
                                onBack = { parentFragmentManager.popBackStack() },
                                onSelectFile = ::onSelectFile,
                                onSelectMedia = ::onSelectMedia,
                                onSelectText = ::onSelectText,
                                onRemoveFile = ::onRemoveFile,
                            onMoveFile = ::onMoveFile,
                            onPreviewFile = ::previewFile,
                            selectionMode = selectionModeState.value,
                            selectedIndices = selectedIndicesState.value,
                            onToggleSelectMode = ::setSelectionMode,
                            onToggleSelection = ::toggleSelection,
                            onSelectAll = ::toggleSelectAll,
                            onDeleteSelected = ::deleteSelectedFiles,
                            onSendViaChoose = ::onSendViaChoose
                            )
                        }
                    } else {
                    FileTransferScreen(
                        selectedFiles = selectedFilesState.value,
                        sendEnabled = sendEnabledState.value,
                        totalSizeBytes = totalSizeBytesState.value,
                        onSelectFile = ::onSelectFile,
                        onSelectMedia = ::onSelectMedia,
                        onSelectFolder = ::onSelectFolder,
                        onSelectText = ::onSelectText,
                        onSelectApp = ::onSelectApp,
                        onRemoveFile = ::onRemoveFile,
                        onMoveFile = ::onMoveFile,
                        onPreviewFile = ::previewFile,
                        selectionMode = selectionModeState.value,
                        selectedIndices = selectedIndicesState.value,
                        onToggleSelectMode = ::setSelectionMode,
                        onToggleSelection = ::toggleSelection,
                        onSelectAll = ::toggleSelectAll,
                        onDeleteSelected = ::deleteSelectedFiles,
                        onSend = ::showSendViaChooser,
                        onHowToUse = { showSendTutorialState.value = true },
                        onHowToUseChipBounds = { r ->
                            howToUseChipBounds = Rect(
                                r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt(),
                            )
                        },
                        onTransferHistory = ::openTransferHistory,
                        showTutorial = showSendTutorialState.value,
                        onTutorialDismiss = { showSendTutorialState.value = false },
                        onTutorialComplete = ::onSendTutorialComplete,
                        showSendVia = showSendViaState.value,
                        nfcUnavailable = nfcUnavailable,
                        onSendViaChoose = ::onSendViaChoose,
                        onSendViaDismiss = { showSendViaState.value = false },
                    )
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // M-11: onDestroyView cancels fragmentJob; recreate it (and its scope) so a
        // re-created view gets a live scope instead of a permanently cancelled one.
        if (!fragmentJob.isActive) {
            fragmentJob = Job()
            uiScope = CoroutineScope(Dispatchers.Main + fragmentJob)
        }

        // Clear the selection once the devices screen reports a queued send, then jump to the
        // outgoing record's history detail so the sender watches its progress there.
        parentFragmentManager.setFragmentResultListener(
            SendToDevicesFragment.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(SendToDevicesFragment.RESULT_SENT)) {
                selectedFiles.clear()
                handleMultipleFilesSelection(selectedFiles)
                bundle.getString(SendToDevicesFragment.RESULT_SESSION_ID)?.let(::openTransferHistoryDetail)
            }
        }

        syncSelectionState()
    }

    // --- File-type selector handlers ------------------------------------------------------------

    private fun onSelectFile() {
        try { selectMultipleFilesLauncher.launch(arrayOf("*/*")) } catch (_: Exception) { }
    }

    private fun onSelectMedia() {
        try {
            selectMultipleMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        } catch (_: Exception) { }
    }

    private fun onSelectFolder() {
        try { selectFolderLauncher.launch(null) } catch (_: Exception) { }
    }

    private fun onSelectApp() {
        selectAppLauncher.launch(Intent(requireContext(), AppSelectionActivity::class.java))
    }

    private fun onSelectText() {
        val input = EditText(requireContext())
        input.hint = getString(R.string.transfer_add_text_hint)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        // Bound the box so long text scrolls inside it instead of growing the dialog off-screen:
        // open at a single row, grow with the text to 7, then scroll internally. maxLines caps the
        // WRAP_CONTENT height, so no fixed pixel height is needed.
        input.gravity = Gravity.TOP or Gravity.START
        input.minLines = 1
        input.maxLines = 7
        input.setHorizontallyScrolling(false)
        input.isVerticalScrollBarEnabled = true

        val container = FrameLayout(requireContext())
        val padding = (24 * requireContext().resources.displayMetrics.density).toInt()
        val paddingTop = (12 * requireContext().resources.displayMetrics.density).toInt()
        container.setPadding(padding, paddingTop, padding, 0)

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        input.layoutParams = lp
        container.addView(input)

        val addTextDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.transfer_add_text_title))
            .setView(container)
            .setPositiveButton(getString(R.string.transfer_add_text_positive)) { dialog, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) {
                    val displayName = getString(R.string.transfer_text_item_name, textCounter++)
                    val tempFile = File(requireContext().cacheDir, "${displayName.replace(" ", "_")}_${System.currentTimeMillis()}.txt")
                    tempFile.writeText(text)
                    val uri = appFileProviderUri(requireContext(), tempFile)
                    selectedFiles.add(TransferItem(uri, displayName))
                    syncSelectionState()
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.action_cancel)) { dialog, _ -> dialog.dismiss() }
            .create()
        addTextDialog.setOnShowListener { addTextDialog.spaceButtons() }
        addTextDialog.applyModalPolish()
        addTextDialog.show()
    }

    // --- Grid item actions (remove / drag-reorder) ----------------------------------------------

    private fun onRemoveFile(index: Int) {
        if (index !in selectedFiles.indices) return
        selectedFiles.removeAt(index)
        syncSelectionState()
    }

    private fun onMoveFile(from: Int, to: Int) {
        // M-10: guard against out-of-range indices from a drag that races a list mutation.
        if (from !in selectedFiles.indices || to !in selectedFiles.indices) return
        // Move (insert/shift), not swap: a cross-row drag on the multi-column grid jumps the hovered
        // index by a whole column, so swapping endpoints would mis-order the items passed over.
        selectedFiles.add(to, selectedFiles.removeAt(from))
        syncSelectionState()
    }

    // ---- Multi-select (delete) mode --------------------------------------------------------------

    private fun setSelectionMode(enabled: Boolean) {
        selectionModeState.value = enabled
        selectedIndicesState.value = emptySet()
    }

    private fun toggleSelection(index: Int) {
        val cur = selectedIndicesState.value.toMutableSet()
        if (!cur.add(index)) cur.remove(index)
        selectedIndicesState.value = cur
    }

    private fun toggleSelectAll() {
        val all = selectedFiles.indices.toSet()
        selectedIndicesState.value = if (selectedIndicesState.value.size == all.size) emptySet() else all
    }

    private fun deleteSelectedFiles() {
        // Remove high indices first so earlier removals don't shift the ones still to delete.
        selectedIndicesState.value.sortedDescending().forEach { i ->
            if (i in selectedFiles.indices) selectedFiles.removeAt(i)
        }
        setSelectionMode(false)
        syncSelectionState()
    }

    /** Tap a queued file (normal mode): image -> fullscreen viewer, other -> open in an external app. */
    private fun previewFile(item: TransferItem) {
        val mime = if (item.isFolder) "inode/directory"
        else runCatching { requireContext().contentResolver.getType(item.uri) }.getOrNull() ?: "application/octet-stream"
        if (mime.startsWith("image/")) showFullScreenImageDialog(item.uri)
        else if (!item.isFolder) openFilePreview(item.uri, mime)
    }

    private fun showFullScreenImageDialog(uri: Uri) {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val imageView = ImageView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#D9000000"))
            setOnClickListener { dialog.dismiss() }
        }
        try { imageView.setImageURI(uri) } catch (_: Exception) { dialog.dismiss(); return }
        dialog.setContentView(imageView)
        dialog.show()
    }

    private fun openFilePreview(uri: Uri, mimeType: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) { /* no app can handle this type */ }
    }

    private fun openTransferHistory() {
        if (parentFragmentManager.findFragmentByTag(TransferHistoryBottomSheet.TAG) == null) {
            TransferHistoryBottomSheet.newInstance().show(parentFragmentManager, TransferHistoryBottomSheet.TAG)
        }
    }

    /** Open the history sheet at [sessionId]'s detail, reusing an already-open sheet like MainActivity does. */
    private fun openTransferHistoryDetail(sessionId: String) {
        val existing = parentFragmentManager.findFragmentByTag(TransferHistoryBottomSheet.TAG) as? TransferHistoryBottomSheet
        if (existing != null && existing.isAdded) {
            existing.openSession(sessionId)
            return
        }
        TransferHistoryBottomSheet.newInstance(sessionId).show(parentFragmentManager, TransferHistoryBottomSheet.TAG)
    }

    private fun showSendViaChooser() {
        if (selectedFiles.isEmpty()) return
        showSendViaState.value = true
    }

    /** Routes the Send-Via overlay's choice to the chosen transport. */
    private fun onSendViaChoose(choice: String) {
        // The double-fire guard only applies to the Pro overlay (SendViaOverlay/SendViaSheet), which
        // sets showSendViaState true before it can call back. Lite's screens call this directly with
        // no overlay involved, so showSendViaState never flips true there — skip the guard entirely.
        if (!isLiteMode && !showSendViaState.value) return
        showSendViaState.value = false
        when {
            choice == SendViaChoice.DEVICES -> openSendToDevices(isMultiple = true)
            choice == SendViaChoice.QR -> gateAndLaunchOffGridSend(OffGridSendActivity.PRESENTATION_QR)
            choice == SendViaChoice.NFC -> gateAndLaunchOffGridSend(OffGridSendActivity.PRESENTATION_NFC)
            choice.startsWith("devices_lite_") -> {
                openSendToDevices(isMultiple = choice.endsWith("1"))
            }
        }
    }

    private fun openSendToDevices(isMultiple: Boolean = true) {
        if (selectedFiles.isEmpty()) return
        // Lite skips the overlay's double-fire guard, so a fast double-tap on the "Same network" card
        // would stack two SendToDevices fragments; dedupe by tag (self-resets once it's popped).
        if (parentFragmentManager.findFragmentByTag(SendToDevicesFragment.TAG) != null) return
        // Full-screen content fragment over the tab — covers the page, keeps the bottom nav visible.
        parentFragmentManager.beginTransaction()
            .add(R.id.nav_host_fragment, SendToDevicesFragment.newInstance(selectedFiles, isMultiple), SendToDevicesFragment.TAG)
            .addToBackStack(SendToDevicesFragment.TAG)
            .commit()
    }

    /**
     * Pre-flight permission gate on the "NFC / QR" path (edge case 3):
     * Wi-Fi P2P group creation needs FINE_LOCATION on API ≤ 32 and
     * NEARBY_WIFI_DEVICES on API ≥ 33.
     */
    private fun gateAndLaunchOffGridSend(presentation: String) {
        if (selectedFiles.isEmpty()) return
        pendingPresentation = presentation
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            launchOffGridSend(presentation)
        } else {
            requestOffGridPermission.launch(permission)
        }
    }

    private fun launchOffGridSend(presentation: String) {
        val intent = Intent(requireContext(), OffGridSendActivity::class.java).apply {
            putExtra(OffGridSendActivity.EXTRA_MODE, OffGridSendActivity.MODE_SEND)
            putExtra(OffGridSendActivity.EXTRA_PRESENTATION, presentation)
            putExtras(TransferItemArgs.toBundle(selectedFiles))
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            // Tabs are hide()/show()n, so the hidden composition stays alive: close the Send-Via
            // overlay or its still-enabled BackHandler would eat back presses on other tabs.
            showSendViaState.value = false
        }
    }

    /**
     * The Compose Send-Files tutorial finished its 8 steps → chain into the Transfer History sheet
     * tutorial (steps 9-13 of the shared "x / 13" counter). Skip just hides the overlay (no chain).
     */
    private fun onSendTutorialComplete() {
        showSendTutorialState.value = false
        TransferHistoryBottomSheet.newInstance(tutorialMode = true, forcedTour = false)
            .show(parentFragmentManager, "transfer_history_tutorial")
    }

    /**
     * Clears the picked-files list and refreshes the UI. Called by MainActivity
     * after a successful send (off-grid) so the next send starts with nothing
     * selected. Safe to call before the view exists. */
    fun clearSelectedFiles() {
        if (selectedFiles.isEmpty()) return
        selectedFiles.clear()
        syncSelectionState()
    }

    /**
     * Ingests files shared into the app via the system share-sheet (ACTION_SEND[_MULTIPLE]).
     * Appends [uris] to the current selection and refreshes the UI. Called by MainActivity from
     * onCreate/onNewIntent. Reliable regardless of whether the view already exists — unlike the
     * old `setArguments`-after-commitNow path, which ran too late (the view was already created)
     * so shared files silently vanished (AND-28). Safe to call before the view exists: the data
     * lives in [selectedFiles] and onViewCreated re-syncs it into the Compose state. */
    fun ingestSharedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val combined = selectedFiles.toMutableList()
        combined.addAll(uris.map { TransferItem(it, null) })
        handleMultipleFilesSelection(combined)
    }

    private fun handleMultipleFilesSelection(items: MutableList<TransferItem>) {
        if (selectedFiles !== items) {
            selectedFiles.clear()
            selectedFiles.addAll(items)
        }
        // Adding files shifts indices, so drop any in-progress selection.
        if (selectionModeState.value) setSelectionMode(false)
        syncSelectionState()
    }

    /** Pushes the current selection into the Compose-observable state. */
    private fun syncSelectionState() {
        selectedFilesState.value = selectedFiles.toList()
        sendEnabledState.value = selectedFiles.isNotEmpty()
        recomputeTotalSize()
    }

    /**
     * Recomputes the queued total off the main thread (folder items are DFS-expanded, so a large
     * tree can take a moment); a newer selection change supersedes any in-flight pass.
     */
    private fun recomputeTotalSize() {
        totalSizeJob?.cancel()
        val items = selectedFiles.toList()
        if (items.isEmpty()) {
            totalSizeBytesState.value = 0L
            return
        }
        val appContext = context?.applicationContext ?: return
        totalSizeJob = uiScope.launch {
            val total = withContext(Dispatchers.IO) {
                TransferItemExpander.expand(appContext, items)
                    .sumOf { TransferItemExpander.getFileSize(appContext, it.uri) }
            }
            totalSizeBytesState.value = total
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") { val cursor = requireContext().contentResolver.query(uri, null, null, null, null); try { if (cursor != null && cursor.moveToFirst()) { val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (index != -1) result = cursor.getString(index) } } finally { cursor?.close() } }
        if (result == null) { result = uri.path; val cut = result?.lastIndexOf('/') ?: -1; if (cut != -1) result = result?.substring(cut + 1) }
        return result ?: getString(R.string.transfer_unknown_file)
    }

    private fun reportTransferToAndroidOS(targetName: String, targetIp: String) {
        val shortcut = ShortcutInfoCompat.Builder(requireContext(), "node_$targetIp").setShortLabel(getString(R.string.transfer_shortcut_short_label, targetName)).setLongLabel(getString(R.string.transfer_shortcut_long_label)).setIcon(IconCompat.createWithResource(requireContext(), R.mipmap.ic_launcher)).setIntent(Intent(requireContext(), MainActivity::class.java).apply { action = Intent.ACTION_SEND; putExtra("TARGET_IP", targetIp) }).setCategories(setOf("android.intent.category.DEFAULT")).build()
        ShortcutManagerCompat.pushDynamicShortcut(requireContext(), shortcut)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fragmentJob.cancel()
    }
}

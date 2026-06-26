package com.EntropicSoftwareLab.ClipboardShare

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
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
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.fragment.app.Fragment
import com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridSendActivity
import com.EntropicSoftwareLab.ClipboardShare.sendvia.SendToDevicesFragment
import com.EntropicSoftwareLab.ClipboardShare.sendvia.SendViaBottomSheetFragment
import com.EntropicSoftwareLab.ClipboardShare.sendvia.TransferItemArgs
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.FileTransferScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.File

data class TransferItem(val uri: Uri, val relativePath: String?, val isFolder: Boolean = false)

class FileTransferFragment : Fragment() {

    // Source-of-truth selection list (read directly by the send flow, clearSelectedFiles, etc.).
    private var selectedFiles: MutableList<TransferItem> = mutableListOf()
    private var textCounter = 1

    // Compose-observable mirrors of the selection state.
    private val selectedFilesState = mutableStateOf<List<TransferItem>>(emptyList())
    private val sendEnabledState = mutableStateOf(false)
    // Multi-select (delete) mode for the queued-files grid. Entered via the "Select" capsule; long-press
    // stays drag-reorder. Indices are valid only while in selection mode (no add/reorder happens then).
    private val selectionModeState = mutableStateOf(false)
    private val selectedIndicesState = mutableStateOf<Set<Int>>(emptySet())

    /** Drives the Compose Send-Files tutorial overlay (item 1: migrated off the legacy View overlay). */
    private val showSendTutorialState = mutableStateOf(false)

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
        return ComposeView(requireContext()).apply{
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    FileTransferScreen(
                        selectedFiles = selectedFilesState.value,
                        sendEnabled = sendEnabledState.value,
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
                        onTransferHistory = ::openTransferHistory,
                        showTutorial = showSendTutorialState.value,
                        onTutorialDismiss = { showSendTutorialState.value = false },
                        onTutorialComplete = ::onSendTutorialComplete,
                    )
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

        // "Send Via" chooser result → route to the chosen transport
        parentFragmentManager.setFragmentResultListener(
            SendViaBottomSheetFragment.REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            when (bundle.getString(SendViaBottomSheetFragment.RESULT_CHOICE)) {
                SendViaBottomSheetFragment.CHOICE_DEVICES -> openSendToDevices()
                SendViaBottomSheetFragment.CHOICE_QR ->
                    gateAndLaunchOffGridSend(OffGridSendActivity.PRESENTATION_QR)
                SendViaBottomSheetFragment.CHOICE_NFC ->
                    gateAndLaunchOffGridSend(OffGridSendActivity.PRESENTATION_NFC)
            }
        }

        // Clear the selection once the devices screen reports a completed send
        parentFragmentManager.setFragmentResultListener(
            SendToDevicesFragment.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(SendToDevicesFragment.RESULT_SENT)) {
                selectedFiles.clear()
                handleMultipleFilesSelection(selectedFiles)
            }
        }

        arguments?.let {
            val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableArrayList("uris", Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableArrayList("uris")
            }
            if (uris != null) {
                val newItems = uris.map { uri -> TransferItem(uri, null) }
                val combined = selectedFiles.toMutableList()
                combined.addAll(newItems)
                handleMultipleFilesSelection(combined)
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
                androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
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
        val input = android.widget.EditText(requireContext())
        input.hint = getString(R.string.transfer_add_text_hint)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

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
                    val uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.provider",
                        tempFile
                    )
                    selectedFiles.add(TransferItem(uri, displayName))
                    syncSelectionState()
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.action_cancel)) { dialog, _ -> dialog.dismiss() }
            .create()
        addTextDialog.setOnShowListener { addTextDialog.spaceButtons() }
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

    private fun showSendViaChooser() {
        if (selectedFiles.isEmpty()) return
        SendViaBottomSheetFragment().show(parentFragmentManager, "send_via")
    }

    private fun openSendToDevices() {
        if (selectedFiles.isEmpty()) return
        // Full-screen content fragment over the tab — covers the page, keeps the bottom nav visible.
        parentFragmentManager.beginTransaction()
            .add(R.id.nav_host_fragment, SendToDevicesFragment.newInstance(selectedFiles), SendToDevicesFragment.TAG)
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
        if (!isHidden) maybeAutoShowTutorial()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) maybeAutoShowTutorial()
    }

    /** One-shot: the full send tutorial fires the first time this tab is opened after install. */
    private fun maybeAutoShowTutorial() {
        if (!isAdded || isHidden) return
        // The forced first-launch tour drives this segment itself; never auto-fire underneath it.
        if (TutorialTour.isForced()) return
        val prefs = requireContext().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("send_tutorial_shown", false)) return
        prefs.edit().putBoolean("send_tutorial_shown", true).apply()
        showSendTutorialState.value = true
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

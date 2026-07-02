package com.EntropicSoftwareLab.ClipboardShare

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.DetailFileRow
import com.EntropicSoftwareLab.ClipboardShare.ui.screens.TransferHistoryScreen
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/**
 * Transfer history sheet. The UI is now Compose ([TransferHistoryScreen], hosted via [ComposeView]);
 * this fragment keeps ALL networking / file / SAF / manager / polling logic and feeds the screen
 * through observable state + callbacks. The list-vs-detail ViewFlipper is modelled as a Compose
 * boolean ([showDetailState]); live transfer progress is driven by a 500 ms polling coroutine that
 * bumps [progressTickState] to force a recompute of the detail file rows.
 */
class TransferHistoryBottomSheet : BottomSheetDialogFragment(), TransferHistoryManager.HistoryUpdateListener {

    // ---- Observable Compose state (read by the screen) ----
    private val recordsState = mutableStateOf<List<TransferRecord>>(emptyList())
    private val currentFilterState = mutableStateOf("All")
    private val isSelectModeState = mutableStateOf(false)
    private val selectedSessionIds: SnapshotStateList<String> = emptyList<String>().toMutableStateList()
    private val showDetailState = mutableStateOf(false)
    private val detailRecordState = mutableStateOf<TransferRecord?>(null)
    // Bumped every 500 ms while a transfer is live so the detail file rows recompute from RAM.
    private val progressTickState = mutableStateOf(0)

    private var currentDetailRecordId: String? = null

    // When non-null, the screen renders THESE records instead of the live history (tutorial only).
    private val tutorialRecordsState = mutableStateOf<List<TransferRecord>?>(null)
    // True only while the tutorial runs — gates the per-frame onGloballyPositioned anchor reporting
    // (otherwise normal list scrolling / filter taps lag from the constant bounds callbacks).
    private val tutorialActiveState = mutableStateOf(false)

    private val filterOptions = listOf("All", "Sent", "Received", "Completed", "Pending", "Cancelled")

    // Tutorial spotlight anchors: zero-size Views positioned from the Compose layout so each step can
    // highlight its real region. See onAnchorBounds / startHistoryTutorial.
    private var anchorOverlay: FrameLayout? = null
    private val anchorViews = mutableMapOf<String, View>()
    private val lastAnchorBounds = mutableMapOf<String, IntArray>()

    companion object {
        /** Single canonical tag so only one detail sheet is ever shown (prevents stacking). */
        const val TAG = "transfer_history_detail"

        fun newInstance(
            targetSessionId: String? = null,
            tutorialMode: Boolean = false,
            forcedTour: Boolean = false
        ): TransferHistoryBottomSheet {
            val fragment = TransferHistoryBottomSheet()
            val args = Bundle()
            args.putString("TARGET_SESSION_ID", targetSessionId)
            args.putBoolean("TUTORIAL_MODE", tutorialMode)
            args.putBoolean("FORCED_TOUR", forcedTour)
            fragment.arguments = args
            return fragment
        }
    }

    /**
     * Reuse an already-open sheet: jump straight to [sessionId]'s detail instead of opening a second
     * sheet on top. Called by [MainActivity] when a transfer/receive fires while the sheet is showing.
     */
    fun openSession(sessionId: String?) {
        if (!isAdded || sessionId == null) return
        val record = TransferHistoryManager.getSortedRecords(requireContext())
            .find { it.sessionId == sessionId } ?: return
        openDetail(record)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.TransparentBottomSheetDialogTheme)
        TransferHistoryManager.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        TransferHistoryManager.removeListener(this)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        // Backdrop only — sheets keep their native slide-up entrance.
        dialog.window?.applyModalBackdrop()
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = false
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        currentFilterState.value = "All"
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    // progressTickState read here so a tick recomposes the detail rows.
                    val tick = progressTickState.value
                    TransferHistoryScreen(
                        records = tutorialRecordsState.value ?: recordsState.value,
                        filterOptions = filterOptions,
                        currentFilter = currentFilterState.value,
                        onFilterSelected = ::onFilterSelected,
                        filterLabel = ::filterLabel,
                        isSelectMode = isSelectModeState.value,
                        onSelectModeChange = ::onSelectModeChange,
                        selectedSessionIds = selectedSessionIds.toSet(),
                        onToggleSelection = ::onToggleSelection,
                        onDeleteSelected = ::onDeleteSelected,
                        showDetail = showDetailState.value,
                        detailRecord = detailRecordState.value,
                        onRecordClick = ::onRecordClick,
                        onBackToList = ::onBackToList,
                        rowLabel = ::rowLabel,
                        rowFileSummary = ::rowFileSummary,
                        rowTimestamp = ::rowTimestamp,
                        detailSubtitle = ::detailSubtitle,
                        buildDetailFiles = { record -> tick.let { buildDetailFiles(record) } },
                        onDetailAction = ::onDetailAction,
                        onDeleteTransaction = ::onDeleteTransaction,
                        onOpenFile = ::openFile,
                        onAnchorBounds = ::onAnchorBounds,
                        reportAnchors = tutorialActiveState.value,
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        refreshData()

        val targetSessionId = arguments?.getString("TARGET_SESSION_ID")
        if (targetSessionId != null) {
            val targetRecord = recordsState.value.find { it.sessionId == targetSessionId }
            if (targetRecord != null) openDetail(targetRecord)
        }

        lifecycleScope.launch {
            while (isActive) {
                delay(500)
                if (showDetailState.value && currentDetailRecordId != null) {
                    val record = TransferHistoryManager.getSortedRecords(requireContext())
                        .find { it.sessionId == currentDetailRecordId }
                    if (record?.status == "transferring") {
                        // Bump the tick so the detail file rows recompute live progress from RAM.
                        progressTickState.value++
                    }
                }
            }
        }

        val isTutorialMode = arguments?.getBoolean("TUTORIAL_MODE", false) ?: false
        if (isTutorialMode) {
            // ComposeView hosts the screen, so spotlights anchor to it (see RISK in the report).
            startHistoryTutorial(view)
        }
    }

    /**
     * The forced/standalone history tutorial. The body is now a Compose tree, so every step's
     * spotlight anchors to the whole ComposeView ([composeRoot]) rather than to the individual
     * sub-views the XML layout used to expose. Fake records are injected via [tutorialRecordsState]
     * and the detail step drives the Compose list/detail state directly. The step numbering, forced
     * tour offset, the [TutorialTour.RESULT_HISTORY_DONE] handoff and the isCancelable lock are all
     * preserved exactly as before.
     */
    private fun startHistoryTutorial(composeRoot: View) {
        val forced = arguments?.getBoolean("FORCED_TOUR", false) == true
        val dialogView = dialog?.window?.decorView ?: return

        // Transparent layer of zero-size anchor Views, positioned from the Compose layout via
        // onAnchorBounds, so each step spotlights its real region (list/filter/select/record/open)
        // instead of the whole sheet. Added under the TutorialOverlayView.
        val anchorLayer = FrameLayout(requireContext()).apply { isClickable = false; isFocusable = false }
        anchorOverlay = anchorLayer
        tutorialActiveState.value = true   // turn the per-frame anchor reporting ON for the tutorial
        listOf("list", "filter", "select", "record", "recordlast", "listregion", "open").forEach { key ->
            val anchor = View(requireContext()).apply { visibility = View.INVISIBLE }
            anchorViews[key] = anchor
            anchorLayer.addView(anchor, FrameLayout.LayoutParams(0, 0))
        }
        (dialogView as? ViewGroup)?.addView(anchorLayer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val overlay = TutorialOverlayView(requireContext())
        (dialogView as? ViewGroup)?.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        isCancelable = false
        dialog?.setCanceledOnTouchOutside(false)
        overlay.onDismissed = {
            isCancelable = true
            dialog?.setCanceledOnTouchOutside(true)
            tutorialRecordsState.value = null
            tutorialActiveState.value = false
            (dialogView as? ViewGroup)?.removeView(anchorLayer)
            anchorOverlay = null
            anchorViews.clear()
            lastAnchorBounds.clear()
        }

        val fakeToRecord = TransferRecord(
            sessionId = "tutorial_to",
            deviceName = "Android Phone",
            direction = "outgoing",
            fileNames = listOf("file 1.mp4", "file 2.pdf", "file 3.jpg"),
            totalBytes = 15L * 1024 * 1024,
            timestamp = System.currentTimeMillis(),
            status = "paused",
            filesCompleted = 0,
            totalFiles = 3,
            fileSizes = listOf(8L * 1024 * 1024, 5L * 1024 * 1024, 2L * 1024 * 1024),
            currentFileOffset = 0L
        )
        val fakeFromRecord = TransferRecord(
            sessionId = "tutorial_from",
            deviceName = "Android Phone",
            direction = "incoming",
            fileNames = listOf("file 1.mp4", "file 2.pdf", "file 3.jpg"),
            totalBytes = 15L * 1024 * 1024,
            timestamp = System.currentTimeMillis(),
            status = "completed",
            filesCompleted = 3,
            totalFiles = 3,
            fileSizes = listOf(8L * 1024 * 1024, 5L * 1024 * 1024, 2L * 1024 * 1024),
            currentFileOffset = 0L
        )

        // Replace real history with fake tutorial records and return to the list view.
        onBackToList()
        tutorialRecordsState.value = listOf(fakeToRecord, fakeFromRecord)

        val steps = listOf(
            // Step 9: spotlight the header + filter bar + cards (the "listregion"); tooltip below the cards.
            TutorialStep(anchorViews.getValue("listregion"), getString(R.string.history_tutorial_list), tooltipBelowViewProvider = { anchorViews.getValue("recordlast") }),
            TutorialStep(anchorViews.getValue("filter"), getString(R.string.history_tutorial_filter), forceTooltipAbove = true, tooltipAboveSpotlight = true),
            // Step 11: snug spotlight fitting just the Select control (trim most of the 12dp pad).
            TutorialStep(anchorViews.getValue("select"), getString(R.string.history_tutorial_select), spotlightHorizontalInsetDp = 8, spotlightVerticalInsetDp = 10, forceTooltipAbove = true, tooltipAboveSpotlight = true),
            // Step 12: finger-tap the SAME card step 13 opens (the From: record, which has the Open
            // button); tooltip below the cards. The tap hint leads into the step-13 detail.
            TutorialStep(anchorViews.getValue("recordlast"), getString(R.string.history_tutorial_tap_record), pointerTapSpotlightCenter = true, tooltipBelowViewProvider = { anchorViews.getValue("recordlast") }),
            // Open button: navigate to the From: detail in Compose, then spotlight the Open button.
            TutorialStep(
                targetView = anchorViews.getValue("open"),
                text = getString(R.string.history_tutorial_open),
                forceTooltipAbove = true,
                tooltipAboveSpotlight = true,
                onShow = { openDetail(fakeFromRecord) },
                delayMs = 200L,
                hideSkipButton = true,
                buttonLabel = if (forced) getString(R.string.history_tutorial_next) else null
            )
        )
        val tourSteps = if (forced) steps.map { it.copy(hideSkipButton = true) } else steps
        // Delay so the Compose list lays out and reports anchor bounds before step 1 is spotlighted.
        composeRoot.postDelayed({
            if (!isAdded) return@postDelayed
            overlay.start(
                tourSteps,
                stepIndexOffset = if (forced) TutorialTour.OFFSET_HISTORY else 9,
                totalStepsOverride = if (forced) TutorialTour.TOTAL_STEPS else 14,
                onComplete = {
                    if (forced) {
                        parentFragmentManager.setFragmentResult(TutorialTour.RESULT_HISTORY_DONE, Bundle())
                    }
                    dismiss()
                }
            )
        }, 150L)
    }

    // ---- Filter ----

    /** Localized display label for an internal filter value (logic still uses the raw value). */
    private fun filterLabel(option: String): String = when (option) {
        "All" -> getString(R.string.history_filter_all)
        "Sent" -> getString(R.string.history_filter_sent)
        "Received" -> getString(R.string.history_filter_received)
        "Completed" -> getString(R.string.history_filter_completed)
        "Pending" -> getString(R.string.history_filter_pending)
        "Cancelled" -> getString(R.string.history_filter_cancelled)
        else -> option
    }

    private fun onFilterSelected(option: String) {
        if (currentFilterState.value != option) {
            currentFilterState.value = option
            refreshData()
        }
    }

    // ---- Selection mode ----

    private fun onSelectModeChange(enabled: Boolean) {
        isSelectModeState.value = enabled
        selectedSessionIds.clear()
    }

    private fun onToggleSelection(sessionId: String) {
        if (sessionId in selectedSessionIds) selectedSessionIds.remove(sessionId)
        else selectedSessionIds.add(sessionId)
    }

    private fun onDeleteSelected() {
        if (selectedSessionIds.isNotEmpty()) {
            TransferHistoryManager.deleteRecords(requireContext(), selectedSessionIds.toSet())
            selectedSessionIds.clear()
            isSelectModeState.value = false
        }
    }

    // ---- List <-> detail ----

    private fun onRecordClick(record: TransferRecord) {
        if (isSelectModeState.value) onToggleSelection(record.sessionId)
        else openDetail(record)
    }

    private fun openDetail(record: TransferRecord) {
        currentDetailRecordId = record.sessionId
        detailRecordState.value = record
        showDetailState.value = true
    }

    private fun onBackToList() {
        currentDetailRecordId = null
        detailRecordState.value = null
        showDetailState.value = false
    }

    /** Positions a tutorial anchor View from the latest Compose layout bounds (dedup'd to avoid a relayout storm). */
    private fun onAnchorBounds(key: String, bounds: IntArray) {
        if (lastAnchorBounds[key]?.contentEquals(bounds) == true) return
        lastAnchorBounds[key] = bounds.copyOf()
        positionAnchor(key, bounds)
        // "listregion" is synthetic: it spans the sheet top down to the bottom of the last card.
        if (key == "list" || key == "recordlast") updateListRegionAnchor()
    }

    /** Lay out the named anchor View at [bounds] (window coords), translated into the anchor layer. */
    private fun positionAnchor(key: String, bounds: IntArray) {
        val anchor = anchorViews[key] ?: return
        val overlay = anchorOverlay ?: return
        val overlayLoc = IntArray(2)
        overlay.getLocationInWindow(overlayLoc)
        val lp = (anchor.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(0, 0)
        lp.leftMargin = bounds[0] - overlayLoc[0]
        lp.topMargin = bounds[1] - overlayLoc[1]
        lp.width = bounds[2]
        lp.height = bounds[3]
        anchor.layoutParams = lp
    }

    /** Combine the sheet ("list") top/width with the last card's bottom into the "listregion" anchor. */
    private fun updateListRegionAnchor() {
        val list = lastAnchorBounds["list"] ?: return
        val last = lastAnchorBounds["recordlast"] ?: return
        val top = list[1]
        val bottom = last[1] + last[3]
        positionAnchor("listregion", intArrayOf(list[0], top, list[2], (bottom - top).coerceAtLeast(0)))
    }

    override fun onHistoryChanged() {
        if (isAdded) refreshData()
    }

    private fun refreshData() {
        val ctx = context ?: return
        var records = TransferHistoryManager.getSortedRecords(ctx)

        records = when (currentFilterState.value) {
            "Sent" -> records.filter { it.direction == "outgoing" }
            "Received" -> records.filter { it.direction == "incoming" }
            "Completed" -> records.filter { it.status == "completed" }
            "Pending" -> records.filter { it.status == "pending" || it.status == "transferring" || it.status == "paused" }
            "Cancelled" -> records.filter { it.status == "failed" || it.status == "declined" || it.status == "cancelled" }
            else -> records
        }

        recordsState.value = records

        if (records.isEmpty() && isSelectModeState.value) {
            isSelectModeState.value = false
            selectedSessionIds.clear()
        }

        if (currentDetailRecordId != null) {
            val updatedRecord = records.find { it.sessionId == currentDetailRecordId }
            if (updatedRecord != null) detailRecordState.value = updatedRecord
        }
    }

    // ---- List row formatting (mirrors TransferHistoryAdapter) ----

    private fun rowLabel(record: TransferRecord): String =
        if (record.direction == "incoming") getString(R.string.format_from_device, record.deviceName)
        else getString(R.string.format_to_device, record.deviceName)

    private fun rowFileSummary(record: TransferRecord): String {
        val totalMb = record.totalBytes / 1048576.0
        val fileCount = record.totalFiles
        val fileNames = record.fileNames.take(2).joinToString(", ")
        val suffix = if (fileCount > 2) getString(R.string.history_more_files_suffix, fileCount - 2) else ""
        return getString(R.string.history_file_summary, fileNames, suffix, String.format("%.1f", totalMb))
    }

    private fun rowTimestamp(record: TransferRecord): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault())
        return sdf.format(Date(record.timestamp))
    }

    // ---- Detail header (mirrors showDetailView) ----

    private fun detailSubtitle(record: TransferRecord): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault())
        val timestamp = sdf.format(Date(record.timestamp))
        val totalMb = record.totalBytes / 1048576.0
        return getString(
            R.string.history_detail_subtitle,
            timestamp,
            record.totalFiles,
            String.format("%.1f", totalMb)
        )
    }

    /**
     * Compute the per-file detail rows for [record], reading live progress from RAM when the record
     * is transferring. Faithfully reproduces [item_history_file] + the old DetailFilesAdapter logic
     * (completed bytes, percent, prefix, tint, Open-button visibility). Called on every progress tick.
     */
    private fun buildDetailFiles(record: TransferRecord): List<DetailFileRow> {
        val count = minOf(record.fileNames.size, record.fileSizes.size)
        val rows = ArrayList<DetailFileRow>(count)
        for (position in 0 until count) {
            val name = record.fileNames.getOrNull(position) ?: ""
            val sizeBytes = record.fileSizes.getOrNull(position) ?: 0L
            val sizeMb = sizeBytes / 1048576.0

            var completedBytes = 0L
            var filesCompleted = record.filesCompleted

            if (record.status == "transferring") {
                val liveSession = TransferDaemon.activeSessions[record.sessionId]
                if (liveSession != null) {
                    filesCompleted = liveSession.currentFileIndex
                    if (position == filesCompleted) completedBytes = liveSession.currentOffset
                } else if (position == filesCompleted) {
                    completedBytes = record.currentFileOffset
                }
            } else {
                if (position == filesCompleted) completedBytes = record.currentFileOffset
            }

            if (position < filesCompleted) {
                completedBytes = sizeBytes
            } else if (position == filesCompleted && record.status == "completed") {
                completedBytes = sizeBytes
            }

            val pct = if (sizeBytes > 0) ((completedBytes.toDouble() / sizeBytes) * 100).toInt()
            else if (position <= record.filesCompleted) 100 else 0
            val completedMb = completedBytes / 1048576.0

            val isDone = (pct == 100 && position < filesCompleted) ||
                (position == filesCompleted && record.status == "completed")
            val isActive = position == filesCompleted && record.status != "completed"
            val activePrefix = if (isActive) {
                if (record.status == "transferring") getString(R.string.status_transferring) else getString(R.string.status_paused)
            } else ""

            val progressText: String
            val tint: Int
            val showOpen: Boolean

            if (isDone) {
                progressText = getString(R.string.history_file_progress, pct, String.format("%.1f", completedMb), String.format("%.1f", sizeMb))
                tint = Color.parseColor("#34D399")
                showOpen = record.direction == "incoming"
            } else if (isActive) {
                val progressStr = getString(R.string.history_file_progress, pct, String.format("%.1f", completedMb), String.format("%.1f", sizeMb))
                progressText = getString(R.string.history_file_progress_prefixed, activePrefix, progressStr)
                tint = Color.parseColor("#FFA500")
                showOpen = false
            } else {
                progressText = getString(R.string.history_waiting_zero)
                tint = Color.parseColor("#AAAAAA")
                showOpen = false
            }

            rows.add(
                DetailFileRow(
                    position = position,
                    fileName = name,
                    progress = pct.coerceIn(0, 100),
                    progressText = progressText,
                    progressTint = tint,
                    showOpen = showOpen,
                    completedBytes = completedBytes,
                    sizeBytes = sizeBytes,
                    isActive = isActive,
                    activePrefix = activePrefix,
                )
            )
        }
        return rows
    }

    /** The bottom action button label/colour is derived from record status, mirroring showDetailView. */
    private fun onDetailAction(record: TransferRecord) {
        when (record.status) {
            "completed", "declined", "cancelled" -> onBackToList()
            "transferring" -> if (record.direction == "incoming") cancelTransfer(record) else { /* outgoing: disabled */ }
            "pending" -> { /* disabled */ }
            else -> resumeTransfer(record) // paused / failed
        }
    }

    private fun onDeleteTransaction(record: TransferRecord) {
        TransferHistoryManager.deleteRecords(requireContext(), setOf(record.sessionId))
        onBackToList()
    }

    // ---- Record actions (unchanged logic) ----

    private fun cancelTransfer(record: TransferRecord) {
        val session = TransferDaemon.activeSessions.remove(record.sessionId)
        if (session != null) {
            session.isCancelled = true
            TransferHistoryManager.updateStatus(requireContext(), record.sessionId, "cancelled", session.currentFileIndex, session.currentOffset)

            // Notify the remote peer
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val targetIp = session.targetIp.ifEmpty { TransferDaemon.discoveredNodes.entries.find { it.value.name == record.deviceName }?.key }
                    if (targetIp != null) {
                        // This path only fires for a live ("transferring") session, so send
                        // CANCEL_TRANSFER regardless of direction: it makes the sender abort the
                        // stream and close its socket. DECLINE_OFFER is for refusing a
                        // not-yet-accepted offer and doesn't force-close the sender's stream.
                        val cmd = "CANCEL_TRANSFER|${record.sessionId}\n"
                        Net.sendBytes(targetIp, Ports.FILE_TRANSFER, cmd.toByteArray(Charsets.UTF_8), 5000)
                    }
                } catch (e: Exception) {}
            }
        } else {
            TransferHistoryManager.updateStatus(requireContext(), record.sessionId, "cancelled")
        }
        // updateStatus fires onHistoryChanged → refreshData → detail re-renders with "cancelled" status.
    }

    private fun resumeTransfer(record: TransferRecord) {
        if (record.direction == "incoming") {
            val session = TransferDaemon.activeSessions[record.sessionId]
            if (session != null && session.isPaused) {
                TransferDaemon.resumeIncomingTransfer(requireContext(), record.sessionId)
            } else {
                android.widget.Toast.makeText(requireContext(), getString(R.string.history_session_unavailable), android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            // Outgoing resume: tell the receiver to resume
            val session = TransferDaemon.activeSessions[record.sessionId]
            val targetIp = session?.targetIp ?: TransferDaemon.discoveredNodes.entries.find { it.value.name == record.deviceName }?.key

            if (targetIp != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    val msg = "FILEBRIDGE_RESUME_REQUEST|${record.sessionId}".toByteArray(Charsets.UTF_8)
                    val sent = Net.sendUdp(targetIp, Ports.DISCOVERY, msg)
                    withContext(Dispatchers.Main) {
                        if (sent) {
                            android.widget.Toast.makeText(requireContext(), getString(R.string.history_resume_request_sent, record.deviceName), android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(requireContext(), getString(R.string.history_resume_request_failed), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                android.widget.Toast.makeText(requireContext(), getString(R.string.history_resume_no_device_ip), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Open a received file (mirrors the old DetailFilesAdapter open-button click). */
    private fun openFile(name: String) {
        try {
            val prefs = requireContext().getSharedPreferences("FileBridgePrefs", android.content.Context.MODE_PRIVATE)
            val saveLocationUriString = prefs.getString("save_location_uri", null)

            var fileUri: android.net.Uri? = null
            var folderUri: android.net.Uri? = null
            var fileExists = false

            if (saveLocationUriString != null) {
                val saveDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), android.net.Uri.parse(saveLocationUriString))
                val targetFile = saveDir?.findFile(name)
                if (targetFile != null && targetFile.exists()) {
                    fileUri = targetFile.uri
                    folderUri = saveDir.uri
                    fileExists = true
                }
            } else {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val file = java.io.File(downloadsDir, name)
                if (file.exists()) {
                    fileUri = androidx.core.content.FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
                    folderUri = android.net.Uri.fromFile(downloadsDir)
                    fileExists = true
                }
            }

            if (fileExists && fileUri != null) {
                val ext = name.substringAfterLast('.', "").lowercase()
                val isImage = ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
                val isVideo = ext in listOf("mp4", "mkv", "avi", "mov", "webm", "ts")
                val isApk = ext == "apk"
                val isPdf = ext == "pdf"

                if (isImage || isVideo || isApk || isPdf) {
                    val mimeType = if (isImage) "image/*" else if (isVideo) "video/*" else if (isApk) "application/vnd.android.package-archive" else "application/pdf"
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        startActivity(if (isApk) intent else Intent.createChooser(intent, getString(R.string.history_chooser_open_media)))
                    } catch (e: android.content.ActivityNotFoundException) {
                        if (isApk) {
                            try {
                                val folderIntent = Intent(Intent.ACTION_VIEW)
                                folderIntent.setDataAndType(folderUri, "resource/folder")
                                folderIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(Intent.createChooser(folderIntent, getString(R.string.history_chooser_open_storage)))
                            } catch (e1: Exception) {
                                val fallback = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(fileUri, "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(Intent.createChooser(fallback, getString(R.string.history_chooser_open_file)))
                            }
                        } else {
                            val fallback = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(fileUri, "*/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(Intent.createChooser(fallback, getString(R.string.history_chooser_open_file)))
                        }
                    }
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.setDataAndType(folderUri, "resource/folder")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(Intent.createChooser(intent, getString(R.string.history_chooser_open_storage)))
                    } catch (e1: android.content.ActivityNotFoundException) {
                        try {
                            val fallback = Intent(Intent.ACTION_VIEW)
                            fallback.setDataAndType(folderUri, "vnd.android.document/directory")
                            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(Intent.createChooser(fallback, getString(R.string.history_chooser_open_storage)))
                        } catch (e2: android.content.ActivityNotFoundException) {
                            try {
                                val dlFallback = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                                dlFallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(dlFallback)
                            } catch (e3: android.content.ActivityNotFoundException) {
                                val ultimate = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(fileUri, "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(Intent.createChooser(ultimate, getString(R.string.history_chooser_open_file)))
                            }
                        }
                    }
                }
            } else {
                android.widget.Toast.makeText(requireContext(), getString(R.string.history_file_not_found), android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(requireContext(), getString(R.string.history_cannot_open_file), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

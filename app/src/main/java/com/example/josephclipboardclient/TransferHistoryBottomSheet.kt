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
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTER, currentFilterState.value)
        if (showDetailState.value) outState.putString(STATE_DETAIL_ID, currentDetailRecordId)
    }

    companion object {
        /** Single canonical tag so only one detail sheet is ever shown (prevents stacking). */
        const val TAG = "transfer_history_detail"
        private const val STATE_FILTER = "state_filter"
        private const val STATE_DETAIL_ID = "state_detail_id"

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
        // Restore the filter across recreation — the unconditional "All" reset dropped the
        // user's selection on every rotation (AND-41).
        currentFilterState.value = savedInstanceState?.getString(STATE_FILTER) ?: "All"
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

        if (savedInstanceState != null) {
            // Recreation: reopen the detail page the user was on (or stay on the list if they
            // had navigated back — the arguments-driven jump below must not re-fire, AND-41).
            savedInstanceState.getString(STATE_DETAIL_ID)?.let { id ->
                recordsState.value.find { it.sessionId == id }?.let(::openDetail)
            }
        } else {
            val targetSessionId = arguments?.getString("TARGET_SESSION_ID")
            if (targetSessionId != null) {
                val targetRecord = recordsState.value.find { it.sessionId == targetSessionId }
                if (targetRecord != null) openDetail(targetRecord)
            }
        }

        // View-scoped so it dies with the view (the old fragment-scoped loop could
        // requireContext() after detach). Liveness comes from the in-RAM session map — an
        // O(1) read — instead of re-parsing the full JSON history every tick (AND-43): a
        // present, non-paused, non-cancelled session IS the "transferring" state.
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(500)
                if (showDetailState.value) {
                    val sid = currentDetailRecordId ?: continue
                    val live = TransferDaemon.activeSessions[sid]
                    if (live != null && !live.isPaused && !live.isCancelled) {
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

            // Zero-byte fallback mirrors the isDone rule below, off the LIVE filesCompleted —
            // the record's persisted count lags the in-RAM session while transferring (AND-45).
            val pct = if (sizeBytes > 0) ((completedBytes.toDouble() / sizeBytes) * 100).toInt()
            else if (position < filesCompleted || (position == filesCompleted && record.status == "completed")) 100 else 0
            val completedMb = completedBytes / 1048576.0

            val isDone = (pct == 100 && position < filesCompleted) ||
                (position == filesCompleted && record.status == "completed")
            val isActive = position == filesCompleted && record.status != "completed"
            val activePrefix = if (isActive) {
                // Label the row by its real status — the old transferring-else-Paused binary
                // stamped failed/cancelled/pending rows all as "Paused" (AND-42).
                when (record.status) {
                    "transferring" -> getString(R.string.status_transferring)
                    "failed" -> getString(R.string.status_failed)
                    "cancelled" -> getString(R.string.status_cancelled)
                    "declined" -> getString(R.string.status_declined)
                    "pending" -> getString(R.string.status_pending)
                    else -> getString(R.string.status_paused)
                }
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
        val session = TransferDaemon.removeActiveSession(record.sessionId)
        if (session != null) {
            session.isCancelled = true
            TransferHistoryManager.updateStatus(requireContext(), record.sessionId, "cancelled", session.currentFileIndex, session.currentOffset)

            // Notify the remote peer. Deliberately NOT lifecycle-scoped (unlike resumeTransfer):
            // the cancel notice must reach the peer even if the sheet is dismissed mid-send, and
            // the block captures no fragment state — only the session and the record's ids.
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
                } catch (e: Exception) {
                    Log.w("TransferHistory", "cancel notify to peer failed: ${e.message}")
                }
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
                com.EntropicSoftwareLab.ClipboardShare.DownloadEngine.resumeIncomingTransfer(requireContext(), record.sessionId)
            } else {
                Toast.makeText(requireContext(), getString(R.string.history_session_unavailable), Toast.LENGTH_SHORT).show()
            }
        } else {
            // Outgoing resume: tell the receiver to resume
            val session = TransferDaemon.activeSessions[record.sessionId]
            val targetIp = session?.targetIp ?: TransferDaemon.discoveredNodes.entries.find { it.value.name == record.deviceName }?.key

            if (targetIp != null) {
                // Lifecycle-scoped so dismissing the sheet cancels the in-flight send, and the
                // Main-thread block is isAdded-guarded: the old unscoped CoroutineScope(Dispatchers.IO)
                // called requireContext() after the async send with no guard, throwing an uncaught
                // IllegalStateException (fragment detached) if the sheet was dismissed mid-flight.
                val appCtx = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val msg = "FILEBRIDGE_RESUME_REQUEST|${record.sessionId}".toByteArray(Charsets.UTF_8)
                    val sent = Net.sendUdp(targetIp, Ports.DISCOVERY, msg)
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        if (sent) {
                            Toast.makeText(appCtx, getString(R.string.history_resume_request_sent, record.deviceName), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(appCtx, getString(R.string.history_resume_request_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.history_resume_no_device_ip), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Open a received file (mirrors the old DetailFilesAdapter open-button click). */
    private fun openFile(name: String) {
        try {
            val prefs = requireContext().getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
            val saveLocationUriString = prefs.getString("save_location_uri", null)

            var fileUri: Uri? = null
            var folderUri: Uri? = null
            var fileExists = false
            val category = TransferUtils.mediaCategory(name)

            if (saveLocationUriString != null) {
                val saveDir = DocumentFile.fromTreeUri(requireContext(), Uri.parse(saveLocationUriString))
                val targetFile = saveDir?.findFile(name)
                if (targetFile != null && targetFile.exists()) {
                    fileUri = targetFile.uri
                    folderUri = saveDir.uri
                    fileExists = true
                }
            }
            // Fall back to the gallery/Download locations whenever the SAF lookup missed — not only
            // when no custom folder is set. Under "Save media to: Gallery" (the default), received
            // photos/videos bypass the SAF tree entirely: they are published into
            // Pictures|Movies/"Clipboard Share" and staged in Download. Without this fallback a
            // custom-folder user would get "file not found" for every received photo. Older receives
            // and any publish fallback that stayed put are still in Download.
            if (!fileExists) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val mediaFile = TransferUtils.publicMediaDir(category)?.let { java.io.File(it, name) }?.takeIf { it.exists() }
                val file = mediaFile ?: java.io.File(downloadsDir, name)
                if (file.exists()) {
                    fileUri = appFileProviderUri(requireContext(), file)
                    folderUri = Uri.fromFile(file.parentFile ?: downloadsDir)
                    fileExists = true
                }
            }
            // Published-row fallback, needed on API 29: non-SAF receives land there through pending
            // MediaStore rows, and scoped storage denies the raw probes above even for our own files
            // (the owner FUSE passthrough that makes java.io reads work is Android 11+) — so every
            // received file looked missing and history "open" always toasted "File not found".
            // Resolve the row's content URI by the history name instead. History holds the
            // POST-dedupe leaf (the receive path repoints it on promote/publish renames), so a
            // receive stored as "name (1).ext" beside an existing file is looked up under the name
            // actually on disk, never the original.
            if (!fileExists) {
                val publishedUri = TransferUtils.findPublishedFileUri(requireContext(), name)
                if (publishedUri != null) {
                    fileUri = publishedUri
                    // Physical collection folder, used only by the reveal-folder fallback chain.
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    folderUri = Uri.fromFile(TransferUtils.publicMediaDir(category) ?: downloadsDir)
                    fileExists = true
                }
            }
            // API-29 receives whose MediaStore publish failed stay in the app-private staging dir
            // (see TransferUtils.receiveStagingDir) — still openable from there via FileProvider
            // (provider_paths root-path covers it). On API 30+ the staging dir IS public Download,
            // already probed above.
            if (!fileExists && TransferUtils.isLegacyScopedStorage()) {
                val stagingDir = TransferUtils.receiveStagingDir(requireContext())
                val staged = java.io.File(stagingDir, name)
                if (staged.exists()) {
                    fileUri = appFileProviderUri(requireContext(), staged)
                    folderUri = Uri.fromFile(stagingDir)
                    fileExists = true
                }
            }

            if (fileExists && fileUri != null) {
                val ext = name.substringAfterLast('.', "").lowercase()
                val isImage = category == TransferUtils.MediaCategory.IMAGE
                val isVideo = category == TransferUtils.MediaCategory.VIDEO
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
                    } catch (e: ActivityNotFoundException) {
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
                    // Open the received file itself (e.g. a text viewer for a .txt). Previously this
                    // branch only revealed the containing folder, so tapping a received .txt — or any
                    // non image/video/apk/pdf file — never opened it. Resolve a MIME from the
                    // extension; unknown types fall back to */*. A direct ACTION_VIEW throws
                    // ActivityNotFoundException when nothing on the device can handle it, so we only
                    // drop to the old open-the-folder chain when the file genuinely can't be viewed.
                    val fileMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                    val fileIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, fileMime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        startActivity(fileIntent)
                    } catch (eFile: ActivityNotFoundException) {
                        // No app declared an ACTION_VIEW handler for the resolved type — common for
                        // text/plain, since many phones ship no text viewer. Before falling back to
                        // merely revealing the containing folder (which never shows the text), let the
                        // user pick ANY app to open the file itself: a */* chooser surfaces editors and
                        // file apps that register for */* rather than text/plain. For text files, if even
                        // that finds nothing, guarantee readability with an in-app preview. Only a
                        // genuinely un-viewable binary drops through to the old folder chain.
                        // Direct start (NOT createChooser): the system chooser always resolves, so it
                        // would never throw even with zero targets and we'd dead-end on an empty "no apps"
                        // screen. A plain ACTION_VIEW throws ActivityNotFoundException when nothing matches,
                        // letting us fall through to the in-app preview / folder chain. If several apps
                        // match, Android shows its own "Open with" disambiguation.
                        val anyAppShown = try {
                            val anyApp = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(fileUri, "*/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(anyApp)
                            true
                        } catch (eAny: Exception) {
                            false
                        }
                        if (!anyAppShown && !(fileMime.startsWith("text/") && showTextPreview(fileUri, name))) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.setDataAndType(folderUri, "resource/folder")
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(Intent.createChooser(intent, getString(R.string.history_chooser_open_storage)))
                            } catch (e1: ActivityNotFoundException) {
                                try {
                                    val fallback = Intent(Intent.ACTION_VIEW)
                                    fallback.setDataAndType(folderUri, "vnd.android.document/directory")
                                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    startActivity(Intent.createChooser(fallback, getString(R.string.history_chooser_open_storage)))
                                } catch (e2: ActivityNotFoundException) {
                                    try {
                                        val dlFallback = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                                        dlFallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(dlFallback)
                                    } catch (e3: ActivityNotFoundException) {
                                        Toast.makeText(requireContext(), getString(R.string.history_cannot_open_file), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.history_file_not_found), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), getString(R.string.history_cannot_open_file), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Last-resort in-app viewer for a received text file: when nothing on the device can open it, read
     * the bytes and show them in a scrollable, selectable dialog with a Copy action, so received text is
     * always readable. Returns true if the preview was shown; returns false (letting the caller fall
     * back to the folder chain) for files over ~1 MB or on any read error.
     */
    private fun showTextPreview(uri: Uri, name: String): Boolean {
        if (!isAdded) return false
        val content = try {
            val cap = 1024 * 1024  // 1 MB — inline preview is for short snippets, not large files
            // Chunked read into a growable buffer: allocation stays proportional to the actual file
            // (a fixed cap-sized array charged 1 MB to preview a 10-byte note).
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(32 * 1024)
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                while (out.size() <= cap) {
                    val r = input.read(chunk)
                    if (r == -1) break
                    out.write(chunk, 0, r)
                }
            } ?: return false
            if (out.size() > cap) return false  // too large to show inline; use the folder fallback instead
            out.toString("UTF-8")
        } catch (e: Exception) {
            return false
        }
        showSelectableTextDialog(
            requireContext(),
            title = name,
            text = content,
            copyButtonLabel = android.R.string.copy,
            copiedToast = R.string.history_toast_copied,
        )
        return true
    }
}

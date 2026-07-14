package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context

/**
 * Shared state + numbering for the forced first-launch tutorial tour.
 *
 * The tour walks all four tabs in bottom-nav order with ONE global "x / 22"
 * counter. This object holds only process-level state and the step offsets so
 * that [MainActivity] (the only class that can switch tabs and reach the
 * fragments) and the cross-activity scanner step agree on numbering and on
 * "is the forced tour running". It deliberately holds no Context/View.
 */
object TutorialTour {

    enum class Segment { SEND_FILES, HISTORY_SHEET, SYSTEM_STATUS, CLIPBOARD, RECEIVE, SCANNER, DONE }

    const val TOTAL_STEPS = 22

    // stepIndexOffset for each segment (bottom-nav order).
    const val OFFSET_SEND = 0       // steps 1-8  (FileTransferFragment)
    const val OFFSET_HISTORY = 8    // steps 9-13 (TransferHistoryBottomSheet)
    const val OFFSET_SYSTEM = 13    // steps 14-17 (SystemStatusFragment)
    const val OFFSET_CLIPBOARD = 17 // steps 18-19 (HistoryFragment)
    const val OFFSET_RECEIVE = 19   // steps 20-21 (ReceiveFragment)
    const val OFFSET_SCANNER = 21   // step 22 (QrScannerFragment)

    /** New conceptual first-launch tour: 7 steps: one per main interface, a how-to-use closer, and a
     *  Lite/Pro mode-toggle step. */
    const val TOUR_STEPS = 7

    const val PREFS = FILE_BRIDGE_PREFS
    const val KEY_COMPLETED = "forced_tour_completed_v1"        // legacy 22-step tour; grandfathered
    const val KEY_COMPLETED_V2 = "forced_tour_completed_v2"     // new 7-step conceptual tour
    const val KEY_IN_PROGRESS = "forced_tour_in_progress"

    /** Result key the forced history-sheet segment posts when it finishes step 13. */
    const val RESULT_HISTORY_DONE = "forced_tour_history_done"

    @Volatile
    var active = false
        private set

    @Volatile
    var currentSegment: Segment = Segment.DONE
        private set

    fun isForced(): Boolean = active

    fun begin() {
        active = true
        currentSegment = Segment.SEND_FILES
    }

    fun advanceTo(seg: Segment) {
        currentSegment = seg
    }

    fun finish() {
        active = false
        currentSegment = Segment.DONE
    }

    /**
     * Durably mark the whole tour complete (every per-tab flag too) so nothing re-fires,
     * and end the in-memory tour. Uses commit() because it's a once-per-install flow that
     * must survive an immediate process death (e.g. right after the scanner's final step).
     */
    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_COMPLETED_V2, true)
            .putBoolean(KEY_IN_PROGRESS, false)
            .putBoolean("send_tutorial_shown", true)
            .putBoolean("system_tutorial_shown", true)
            .putBoolean("clipboard_tutorial_shown", true)
            .putBoolean("receive_tutorial_shown", true)
            .putBoolean("scanner_tutorial_shown", true)
            .commit()
        finish()
    }
}

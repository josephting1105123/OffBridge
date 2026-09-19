package com.joseph.clipboard.windows.ui

import com.joseph.clipboard.windows.Screen
import com.joseph.clipboard.windows.storage.TranslationManager

/**
 * The forced first-launch tour: one conceptual step per main interface, then a closer pointing at
 * the "How to use" chip. Six steps, mirroring the Android client's seven (`MainActivity.tourSteps`)
 * minus its Lite/Pro mode-toggle step — Windows has no Lite mode.
 *
 * This replaces the old first-launch behaviour, which chained the *full* per-screen tutorials
 * (Send + its nested transfer-history tour, then System Status, then Clipboard) — around eighteen
 * steps of detail before the user had seen the app do anything. Those tutorials are unchanged and
 * still reachable: every screen's "How to use" chip opens its own, exactly as before. Android made
 * the same split (see TutorialTour.KEY_COMPLETED_V2); this is the Windows half.
 *
 * The step order is Android's, so the two clients tell the same story: pair first, then what pairing
 * buys you (clipboard), then the two ways to send, then how receiving works, then where the detail
 * lives.
 */
object FirstRunTour {

    /** Total shown in the tooltip counter ("3 / 6"). */
    const val TOTAL_STEPS = 6

    /** Bounds keys the host publishes for the nav-rail items this tour spotlights. */
    const val TARGET_NAV_SEND = "tourNavSend"
    const val TARGET_NAV_PAIRING = "tourNavPairing"
    const val TARGET_NAV_CLIPBOARD = "tourNavClipboard"

    /** Bounds key for the Send screen's "How to use" chip (the closer's target). */
    const val TARGET_HOW_TO_USE_CHIP = "tourHowToUseChip"

    /**
     * Compose's Crossfade between screens runs 220ms; the chip only reports its bounds once the Send
     * screen has been laid out again. Waiting covers that, and mirrors the 350ms Android puts on its
     * own final step for the same reason.
     */
    const val SCREEN_SETTLE_MS = 350L

    /** One tour step before it becomes a [TutorialStep]: which screen to show, and what to say. */
    data class Step(
        val screen: Screen,
        val targetKey: String,
        val translationKey: String,
        val fallback: String,
    )

    /**
     * The steps, in narrative order. Copy is the Android client's, verbatim where the feature is
     * identical; [Step.fallback] is the English source of truth for anything the translation file
     * can't resolve.
     */
    val steps: List<Step> = listOf(
        Step(
            screen = Screen.SYSTEM_STATUS,
            targetKey = TARGET_NAV_PAIRING,
            translationKey = "tour_pairing",
            fallback = "Start by pairing your devices. Pairing unlocks a seamless workflow — clipboard sync and direct file transfer between them.",
        ),
        Step(
            screen = Screen.HISTORY,
            targetKey = TARGET_NAV_CLIPBOARD,
            translationKey = "tour_clipboard",
            fallback = "Once your devices are paired, your clipboard syncs automatically — copy on one device, paste on another.",
        ),
        Step(
            screen = Screen.SEND_FILES,
            targetKey = TARGET_NAV_SEND,
            translationKey = "tour_send_lan",
            fallback = "Send files with no data charges and no quality loss. On the same Wi-Fi or hotspot, pick a Connected Device — fastest, no internet needed.",
        ),
        // Android's tour_send_qr also offers NFC; a desktop has no NFC radio, and this app's QR flow
        // is one-directional (this PC shows the code, the phone scans it), so the copy says that
        // instead of promising a tap that can never happen here.
        Step(
            screen = Screen.SEND_FILES,
            targetKey = TARGET_NAV_SEND,
            translationKey = "tour_send_qr",
            fallback = "On different networks? Choose QR when you send — a nearby Android phone scans the code and receives over your local network, no shared Wi-Fi required.",
        ),
        // Android anchors its receive step on a Receive tab. Windows has no such tab: an incoming
        // transfer arrives as a popup wherever you are, so the step explains that and points at the
        // Send screen, which is where the transfer history that tracks it lives.
        Step(
            screen = Screen.SEND_FILES,
            targetKey = TARGET_NAV_SEND,
            translationKey = "tour_receive",
            fallback = "Receiving needs nothing set up — a file sent to this PC pops up here for you to accept, and lands in your save folder. The history button above tracks it.",
        ),
        Step(
            screen = Screen.SEND_FILES,
            targetKey = TARGET_HOW_TO_USE_CHIP,
            translationKey = "tour_how_to_use",
            fallback = "Want the details? Every screen has a \"How to use\" button (top-right) with a full step-by-step guide.",
        ),
    )

    /** Localized text for [step], falling back to its English source when the key is missing. */
    fun textOf(step: Step): String =
        TranslationManager.getString(step.translationKey).takeIf { it != step.translationKey }
            ?: step.fallback
}

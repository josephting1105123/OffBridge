package com.joseph.clipboard.windows.network

/**
 * Whether an incoming offer may be accepted and opened without asking the user.
 *
 * This is the security boundary of the screenshot feature, and it is deliberately a separate,
 * pure, tested decision rather than a few conditions inlined at the call site.
 *
 * The risk is specific. Normally an offer raises a prompt and a person decides. For a screenshot the
 * laptop accepts silently and then hands the result to the Windows shell — so if the matching were
 * loose, a paired-but-hostile phone could announce `holiday.png`, wait for the fetch, and stream
 * something else entirely into a file that is then opened. Every condition below exists to close one
 * version of that: the transfer has to be the one we asked for, just now, and it has to be the exact
 * file that was announced.
 *
 * Failing any condition is not an error — it falls back to the ordinary accept prompt, which is
 * exactly what would have happened before this feature existed.
 */
object ScreenshotAdmission {

    /** How long a fetch stays auto-acceptable. A screenshot offer comes back in about a second. */
    const val FETCH_DEADLINE_MS = 20_000L

    /** What the phone said it was going to send, recorded when the user clicked Open. */
    data class Expected(
        val sessionId: String,
        val name: String,
        val size: Long,
        val requestedAtMs: Long,
    )

    /**
     * True only when every condition holds:
     *
     *  - the offer answers a fetch this laptop sent (session id matched by the caller);
     *  - it arrived inside [FETCH_DEADLINE_MS] of that fetch;
     *  - it carries exactly one file, so a screenshot request can't deliver a payload alongside it;
     *  - that file's name and size are the ones announced;
     *  - and the name is one of the image types this feature is allowed to open unattended.
     */
    fun mayAutoAccept(expected: Expected?, manifest: List<Pair<String, Long>>, nowMs: Long): Boolean {
        if (expected == null) return false
        val age = nowMs - expected.requestedAtMs
        if (age < 0 || age > FETCH_DEADLINE_MS) return false
        if (manifest.size != 1) return false
        val (name, size) = manifest.first()
        if (name != expected.name) return false
        if (size != expected.size) return false
        return ScreenshotWire.isOpenableImage(name)
    }

    /**
     * Whether a finished file may be handed to the shell.
     *
     * Checked again at open time rather than trusted from accept time. The two are far apart — a
     * whole transfer, a name-collision rename and a disk write in between — and the cost of asking
     * twice is a string comparison.
     */
    fun mayOpen(fileName: String): Boolean = ScreenshotWire.isOpenableImage(fileName)
}

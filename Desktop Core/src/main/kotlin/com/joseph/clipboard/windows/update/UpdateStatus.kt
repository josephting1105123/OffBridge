package com.joseph.clipboard.windows.update

/** State of the in-app update checker — backs both the sidebar badge and the Updates page. */
sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    object Downloading : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class UpdateAvailable(
        val version: String,
        val notes: String,
        val downloadUrl: String,
    ) : UpdateStatus()

    /**
     * @param reason the raw diagnostic (exception message, `HTTP 503`, …). Not localized — it is for
     *   the log and the small detail line, never the headline.
     * @param kind what the user can actually do about it. The Updates page used to render one
     *   generic "Couldn't check for updates" for every failure, so "your network is down",
     *   "GitHub is throttling you" and "the release is malformed" were indistinguishable — and the
     *   Try-again button was the wrong advice for two of the three.
     */
    data class CheckFailed(
        val reason: String,
        val kind: FailureKind = FailureKind.BAD_RESPONSE,
    ) : UpdateStatus()

    /** Failure classes the Updates page gives distinct copy to. */
    enum class FailureKind {
        /** No answer at all: offline, DNS failure, connect or response timeout. Retrying may work. */
        UNREACHABLE,

        /** GitHub is throttling this address (403/429 with the rate-limit budget spent). Retrying
         *  now only makes it worse — the useful advice is to wait. */
        RATE_LIMITED,

        /** The server answered with a non-success status. Nothing the user can fix. */
        HTTP_ERROR,

        /** Reached and answered, but the payload wasn't a usable release (no tag, no installer). */
        BAD_RESPONSE,
    }
}

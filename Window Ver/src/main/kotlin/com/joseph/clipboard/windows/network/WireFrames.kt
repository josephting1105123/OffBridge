package com.joseph.clipboard.windows.network

/**
 * Builders for the outbound control lines this client composes by hand. These strings ARE the
 * cross-platform wire format (Android parses them positionally), so each builder reproduces its
 * historical literal exactly — including whether the terminator is part of the returned string.
 *
 * Convention: `*Line` functions include the trailing `\n` and are written to a raw OutputStream.
 * The unpair frames return bare lines because their call sites use `PrintWriter.println`, which
 * appends the platform terminator itself (historical behavior both peers tolerate — keep it).
 */
object WireFrames {
    /** `OFFER|sessionId|deviceName|count\n` — file-offer header (LAN send and QR-host handshake). */
    fun offerLine(sessionId: String, deviceName: String, fileCount: Int): String =
        "OFFER|$sessionId|$deviceName|$fileCount\n"

    /** `OFFER_PROOF|b64\n` — AUTH2 answer to a receiver's OFFER_CHALLENGE. */
    fun offerProofLine(proofB64: String): String = "OFFER_PROOF|$proofB64\n"

    /** `UNPAIR_REQ|` — bare line for println. */
    fun unpairReq(): String = "UNPAIR_REQ|"

    /** `UNPAIR_PROOF|b64` — bare line for println; proves key possession for authenticated unpair. */
    fun unpairProof(proofB64: String): String = "UNPAIR_PROOF|$proofB64"
}

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

    // ---- Pairing handshake --------------------------------------------------------------------
    // Both frames end in an unordered token tail. `pv2` (PIN v2) has ridden there since 3.5.0 and
    // both platforms already read it with `contains`, so appending `did:<32 hex>` is invisible to
    // older builds — they keep their positional fields and ignore the extra token. A peer that
    // sends no did simply doesn't get stale-record eviction.
    /** Prefix marking the stable device-id token in both the pairing tail and the discovery frame. */
    const val DEVICE_ID_TOKEN = "did:"

    /** `PAIR_REQ|name|pubkeyB64|pv2|did:<hex>` — bare line for println. */
    fun pairReq(deviceName: String, pubKeyB64: String, deviceId: String): String =
        "PAIR_REQ|$deviceName|$pubKeyB64|pv2|$DEVICE_ID_TOKEN$deviceId"

    /** `PAIR_ACK|pubkeyB64|pv2|did:<hex>` — bare line for println. */
    fun pairAck(pubKeyB64: String, deviceId: String): String =
        "PAIR_ACK|$pubKeyB64|pv2|$DEVICE_ID_TOKEN$deviceId"

    /**
     * Peer device id from a frame's token tail (the fields after the positional ones), or null when
     * absent or malformed. Only 32 lowercase hex chars are accepted — the value is persisted and
     * compared, so a peer must not be able to smuggle a delimiter or an arbitrary-length blob in.
     */
    fun parseDeviceId(tokens: List<String>): String? = tokens
        .firstOrNull { it.startsWith(DEVICE_ID_TOKEN) }
        ?.removePrefix(DEVICE_ID_TOKEN)
        ?.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    /** `UNPAIR_PROOF|b64` — bare line for println; proves key possession for authenticated unpair. */
    fun unpairProof(proofB64: String): String = "UNPAIR_PROOF|$proofB64"
}

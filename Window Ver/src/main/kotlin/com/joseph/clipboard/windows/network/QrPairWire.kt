package com.joseph.clipboard.windows.network

import java.util.Base64

/**
 * Builders and parsers for the three QR-pairing frames. These strings ARE the cross-platform wire
 * format — Android `pairing/QrPairWire.kt` holds the byte-identical counterpart and
 * `QrPairWireTest` pins both shapes.
 *
 * They ride the same LF-terminated, `|`-separated pairing socket as `PAIR_REQ`/`PAIR_ACK`
 * ([com.joseph.clipboard.windows.network.WireFrames]), so a daemon tells them apart by the
 * leading verb alone and an older build simply ignores a verb it never learned.
 *
 *   scanner -> host   PAIR_QR|<nameB64>|<pubB64>|<tokB64>|did:<hex>
 *   host -> scanner   PAIR_QR_ACK|did:<hex>|<sealB64>      (host has NOT stored trust yet)
 *   scanner -> host   PAIR_QR_FIN|<sealB64>                (scanner has)
 *   host -> scanner   PAIR_QR_DENY|<reason>
 *
 * The scanner's name is base64 here even though the PIN frame sends it raw: a peer chooses that
 * string, and a `|` inside it would shift every field after it.
 */
object QrPairWire {

    const val VERB_REQ = "PAIR_QR"
    const val VERB_ACK = "PAIR_QR_ACK"
    const val VERB_FIN = "PAIR_QR_FIN"
    const val VERB_DENY = "PAIR_QR_DENY"

    /** No open window matched the token — expired, already claimed, or never existed. One reason
     *  for all three so a prober can't tell which. */
    const val DENY_NO_SESSION = "no_session"

    /** The frame didn't parse, or its confirmation failed to open under the derived key. */
    const val DENY_BAD_REQUEST = "bad_request"

    /**
     * [deviceName] is truncated to the length [parseReq] accepts. Without that, a user whose device
     * name happens to be longer than the limit would have every pairing rejected by the host as a
     * malformed frame, with nothing on either screen to explain why.
     */
    fun req(deviceName: String, pubKey: ByteArray, token: ByteArray, deviceId: String): String =
        "$VERB_REQ|${b64(deviceName.take(PairQrPayload.MAX_NAME_LEN).toByteArray(Charsets.UTF_8))}|" +
            "${b64(pubKey)}|${b64(token)}|${DEVICE_ID_TOKEN}$deviceId"

    fun ack(deviceId: String, seal: ByteArray): String =
        "$VERB_ACK|${DEVICE_ID_TOKEN}$deviceId|${b64(seal)}"

    fun fin(seal: ByteArray): String = "$VERB_FIN|${b64(seal)}"

    fun deny(reason: String): String = "$VERB_DENY|$reason"

    /** Scanner's half of the handshake, as the host reads it. */
    data class Req(
        val deviceName: String,
        val publicKey: ByteArray,
        val token: ByteArray,
        val deviceId: String?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Req) return false
            return deviceName == other.deviceName && publicKey.contentEquals(other.publicKey) &&
                token.contentEquals(other.token) && deviceId == other.deviceId
        }

        override fun hashCode(): Int = token.contentHashCode()
    }

    /** Host's half, as the scanner reads it. */
    data class Ack(val deviceId: String?, val seal: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ack) return false
            return deviceId == other.deviceId && seal.contentEquals(other.seal)
        }

        override fun hashCode(): Int = seal.contentHashCode()
    }

    /** Null on anything malformed — the caller denies rather than guessing at a partial frame. */
    fun parseReq(line: String): Req? {
        val parts = line.split("|")
        if (parts.size < 4 || parts[0] != VERB_REQ) return null
        val name = unb64(parts[1])?.toString(Charsets.UTF_8) ?: return null
        if (name.length > PairQrPayload.MAX_NAME_LEN) return null
        val pub = unb64(parts[2]) ?: return null
        val token = unb64(parts[3]) ?: return null
        if (token.size != PairQrPayload.TOKEN_SIZE_BYTES) return null
        return Req(name, pub, token, parseDeviceId(parts.drop(4)))
    }

    fun parseAck(line: String): Ack? {
        val parts = line.split("|")
        if (parts.size < 3 || parts[0] != VERB_ACK) return null
        val seal = unb64(parts[2]) ?: return null
        return Ack(parseDeviceId(listOf(parts[1])), seal)
    }

    fun parseFin(line: String): ByteArray? {
        val parts = line.split("|")
        if (parts.size < 2 || parts[0] != VERB_FIN) return null
        return unb64(parts[1])
    }

    /** Reason from a denial, or null when [line] isn't one. */
    fun parseDeny(line: String): String? {
        val parts = line.split("|")
        if (parts.size < 2 || parts[0] != VERB_DENY) return null
        return parts[1]
    }

    /**
     * Peer device id from a token tail. Same 32-lowercase-hex rule the PIN frames use — the value is
     * persisted and compared, so a peer must not be able to smuggle a delimiter or a long blob in.
     */
    fun parseDeviceId(tokens: List<String>): String? = tokens
        .firstOrNull { it.startsWith(DEVICE_ID_TOKEN) }
        ?.removePrefix(DEVICE_ID_TOKEN)
        ?.takeIf { PairQrPayload.isDeviceId(it) }

    private const val DEVICE_ID_TOKEN = "did:"

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(value: String): ByteArray? =
        try { Base64.getDecoder().decode(value) } catch (_: IllegalArgumentException) { null }
}

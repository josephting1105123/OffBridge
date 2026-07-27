package com.EntropicSoftwareLab.ClipboardShare.pairing

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The QR-pairing credential — what a device puts on screen so another device can pair by scanning
 * it, instead of comparing a 6-digit PIN.
 *
 * Deliberately NOT the JSON shape [com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridPayload]
 * uses. A `|`-delimited line with a leading magic keeps this pure JVM (no org.json, no
 * android.util.Base64), so the codec is unit-testable on the host and provably byte-identical to
 * the Windows port; the magic also lets one scanner tell a pairing code from a file-transfer code
 * before parsing either. Free-text fields ride base64 so a device name containing `|` or a newline
 * cannot desync the positional parse.
 *
 *   CSPAIR1|<ip>|<port>|<nameB64>|<did>|<pkB64>|<tokB64>|<expMs>[|tail...]
 *
 * Tail tokens are unordered and optional; unknown ones are ignored, which is the version seam for
 * later additions. Known: `ssid:<b64>` and `psk:<b64>` (the host's off-grid AP, present only when
 * it raised one) and `lan:<ipv4>` (its address on a shared network, when it has both).
 *
 * Byte-identical counterpart: Windows `network/PairQrPayload.kt`.
 */
data class PairQrPayload(
    val version: Int,
    /** Address the scanner dials — the AP gateway off-grid, else the host's LAN address. */
    val ip: String,
    val port: Int,
    /** Host's display name. Untrusted, shown only after a successful pair. */
    val name: String,
    /** Host's stable device id, 32 lowercase hex — same token the PIN handshake carries. */
    val deviceId: String,
    /** Host's ephemeral ECDH public key for this QR window, X.509 DER. */
    val publicKey: ByteArray,
    /** 16-byte one-shot admission token; possession proves the scanner saw this screen. */
    val token: ByteArray,
    /** Absolute expiry, epoch millis. */
    val expiresAtMs: Long,
    /** Off-grid AP credentials the scanner should join when it can't reach [ip] directly. */
    val ssid: String? = null,
    val psk: String? = null,
    /** Host's address on a shared network, when it advertises both an AP and a LAN route. */
    val lanIp: String? = null,
) {

    fun encode(): String {
        val head = listOf(
            MAGIC,
            ip,
            port.toString(),
            b64(name.toByteArray(Charsets.UTF_8)),
            deviceId,
            b64(publicKey),
            b64(token),
            expiresAtMs.toString(),
        )
        val tail = buildList {
            ssid?.let { add(SSID_TOKEN + b64(it.toByteArray(Charsets.UTF_8))) }
            psk?.let { add(PSK_TOKEN + b64(it.toByteArray(Charsets.UTF_8))) }
            lanIp?.let { add(LAN_TOKEN + it) }
        }
        return (head + tail).joinToString("|")
    }

    /** True when this code advertises an AP the scanner can join with no shared network. */
    fun hasApCredentials(): Boolean = !ssid.isNullOrEmpty() && !psk.isNullOrEmpty()

    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs

    /** Hex form of [token] — the value both confirmation blobs bind themselves to. */
    fun tokenHex(): String = token.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairQrPayload) return false
        return version == other.version && ip == other.ip && port == other.port &&
            name == other.name && deviceId == other.deviceId &&
            publicKey.contentEquals(other.publicKey) && token.contentEquals(other.token) &&
            expiresAtMs == other.expiresAtMs && ssid == other.ssid && psk == other.psk &&
            lanIp == other.lanIp
    }

    override fun hashCode(): Int = token.contentHashCode()

    sealed class ParseResult {
        data class Valid(val payload: PairQrPayload) : ParseResult()

        /** A pairing code from a newer app generation — "update the app", not "bad code". */
        object UnsupportedVersion : ParseResult()

        /** Not a pairing code at all, or malformed. Rejected before any network action. */
        object Invalid : ParseResult()

        /** Well-formed but past its expiry — the UI tells the user to refresh the code. */
        object Expired : ParseResult()
    }

    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * Highest generation this build parses. Emitters still write [SCHEMA_VERSION]; accepting a
         * range only stops a later additive revision from being rejected outright.
         */
        const val SCHEMA_VERSION_MAX = 2

        /** Wire magic. The trailing digit IS the schema version — bump both together. */
        const val MAGIC = "CSPAIR1"

        const val TOKEN_SIZE_BYTES = 16
        const val DEVICE_ID_LEN = 32
        const val MAX_NAME_LEN = 32

        /** How long a displayed code stays valid. Long enough to find the other device, short
         *  enough that a photographed screen goes stale on its own. */
        const val VALIDITY_MS = 180_000L

        private const val SSID_TOKEN = "ssid:"
        private const val PSK_TOKEN = "psk:"
        private const val LAN_TOKEN = "lan:"

        /** Guards against a QR blown up to a size that would make the scanner allocate wildly. */
        private const val MAX_ENCODED_LEN = 1024

        private val IPV4_REGEX = Regex(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
        )

        fun newToken(): ByteArray = ByteArray(TOKEN_SIZE_BYTES).also { SecureRandom().nextBytes(it) }

        /**
         * Builds the payload a host displays. [name] is truncated rather than rejected — it is the
         * local user's own device name and must never be able to block their own pairing.
         */
        fun build(
            ip: String,
            port: Int,
            name: String,
            deviceId: String,
            publicKey: ByteArray,
            token: ByteArray,
            nowMs: Long,
            ssid: String? = null,
            psk: String? = null,
            lanIp: String? = null,
            validityMs: Long = VALIDITY_MS,
        ): PairQrPayload = PairQrPayload(
            version = SCHEMA_VERSION,
            ip = ip,
            port = port,
            name = name.take(MAX_NAME_LEN),
            deviceId = deviceId,
            publicKey = publicKey,
            token = token,
            expiresAtMs = nowMs + validityMs,
            ssid = ssid?.takeIf { it.isNotEmpty() },
            psk = psk?.takeIf { it.isNotEmpty() },
            // Only advertise a real routable address, and never the Wi-Fi Direct subnet — that one
            // is reachable only from inside the group the scanner hasn't joined yet.
            lanIp = lanIp?.takeIf { IPV4_REGEX.matches(it) && !it.startsWith("192.168.49.") },
        )

        /**
         * Strict validation: an unknown magic, bad base64, a non-P-256 key, a wrong-size token and
         * out-of-range numbers are all rejected here, before the scanner touches the network.
         * [nowMs] is injected so tests (and a caller with a better clock) control expiry.
         */
        fun parse(raw: String, nowMs: Long): ParseResult {
            if (raw.length > MAX_ENCODED_LEN) return ParseResult.Invalid
            val parts = raw.trim().split("|")
            if (parts.size < 8) return ParseResult.Invalid
            if (!parts[0].startsWith("CSPAIR")) return ParseResult.Invalid

            val version = parts[0].removePrefix("CSPAIR").toIntOrNull() ?: return ParseResult.Invalid
            if (version < 1) return ParseResult.Invalid
            if (version > SCHEMA_VERSION_MAX) return ParseResult.UnsupportedVersion

            val ip = parts[1]
            if (!IPV4_REGEX.matches(ip)) return ParseResult.Invalid

            val port = parts[2].toIntOrNull() ?: return ParseResult.Invalid
            if (port < 1 || port > 65535) return ParseResult.Invalid

            val name = String(unb64(parts[3]) ?: return ParseResult.Invalid, Charsets.UTF_8)
            if (name.length > MAX_NAME_LEN) return ParseResult.Invalid

            val deviceId = parts[4]
            if (!isDeviceId(deviceId)) return ParseResult.Invalid

            val publicKey = unb64(parts[5]) ?: return ParseResult.Invalid
            if (!isValidP256Key(publicKey)) return ParseResult.Invalid

            val token = unb64(parts[6]) ?: return ParseResult.Invalid
            if (token.size != TOKEN_SIZE_BYTES) return ParseResult.Invalid

            val expiresAtMs = parts[7].toLongOrNull() ?: return ParseResult.Invalid

            val tail = parts.drop(8)
            // A malformed optional field drops to null rather than failing the whole code: these are
            // connectivity hints, never security inputs. ssid/psk are the exception — a half-decoded
            // credential pair would send the scanner at an AP it can't authenticate to, so both must
            // survive or neither is used.
            val ssid = tailValue(tail, SSID_TOKEN)?.let { unb64(it) }?.toString(Charsets.UTF_8)
            val psk = tailValue(tail, PSK_TOKEN)?.let { unb64(it) }?.toString(Charsets.UTF_8)
            val apUsable = !ssid.isNullOrEmpty() && !psk.isNullOrEmpty() &&
                ssid.toByteArray(Charsets.UTF_8).size <= 32 && psk.length in 8..63
            val lanIp = tailValue(tail, LAN_TOKEN)
                ?.takeIf { IPV4_REGEX.matches(it) && !it.startsWith("192.168.49.") }

            val payload = PairQrPayload(
                version = version,
                ip = ip,
                port = port,
                name = name,
                deviceId = deviceId,
                publicKey = publicKey,
                token = token,
                expiresAtMs = expiresAtMs,
                ssid = if (apUsable) ssid else null,
                psk = if (apUsable) psk else null,
                lanIp = lanIp,
            )
            // Expiry is checked last so a stale-but-valid code reports "refresh it" instead of the
            // generic "wrong code" a user can do nothing with.
            return if (payload.isExpired(nowMs)) ParseResult.Expired else ParseResult.Valid(payload)
        }

        /** True when [raw] looks like a pairing code, so a shared scanner can route it here. */
        fun looksLikePairCode(raw: String): Boolean = raw.trimStart().startsWith("CSPAIR")

        fun isDeviceId(value: String): Boolean =
            value.length == DEVICE_ID_LEN && value.all { it in '0'..'9' || it in 'a'..'f' }

        /**
         * True when [encoded] is a well-formed P-256 public key.
         *
         * Exposed so the pairing daemon can screen a peer's key before ECDH, not just when parsing a
         * scanned code. Modern JDKs reject off-curve points inside key agreement, but an
         * invalid-curve attack is precisely the class of bug that has broken ECDH implementations
         * before, and this check costs one parse on a path that runs at most a few times a day.
         */
        fun isP256PublicKey(encoded: ByteArray): Boolean = isValidP256Key(encoded)

        private fun tailValue(tail: List<String>, prefix: String): String? =
            tail.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotEmpty() }

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

        private fun unb64(value: String): ByteArray? =
            try { Base64.getDecoder().decode(value) } catch (_: IllegalArgumentException) { null }

        private fun isValidP256Key(encoded: ByteArray): Boolean = try {
            val key = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(encoded)) as? ECPublicKey
            key != null && key.params.curve.field.fieldSize == 256
        } catch (_: Exception) {
            false
        }
    }
}

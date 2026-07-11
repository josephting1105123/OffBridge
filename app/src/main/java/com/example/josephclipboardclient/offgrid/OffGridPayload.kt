package com.EntropicSoftwareLab.ClipboardShare.offgrid

import android.util.Base64
import com.EntropicSoftwareLab.ClipboardShare.Ports
import org.json.JSONObject
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * QR / NFC credential payload (schema v1, §3.1 of the V3.0 plan).
 *
 * One JSON object with short keys, carried identically by QR code and the
 * NDEF record served by [NdefHceService] — one payload, two carriers.
 */
data class OffGridPayload(
    val version: Int,
    val ssid: String,
    val psk: String,
    val ip: String,
    val port: Int,
    /** Sender's ephemeral ECDH public key, X.509 DER (fresh per session). */
    val senderPublicKey: ByteArray,
    /** 16-byte session admission token. */
    val token: ByteArray,
    /** Sender device name — display only, untrusted. */
    val senderName: String,
    val fileCount: Int,
    val totalBytes: Long,
    /**
     * NEW (optional): the group-owner's Wi-Fi Direct deviceAddress (MAC). Lets the
     * receiver bypass the WifiNetworkSpecifier picker with a real P2P client join
     * (root cause #3). Null on v1 senders or when the OS hid the MAC.
     */
    val goDeviceAddress: String? = null,
    /** NEW (optional): the group's operating frequency in MHz — diagnostics + the band-warning chip. */
    val frequencyMhz: Int? = null,
    /**
     * V4 §5 (optional): sender capability bitmap — bit0 BLE-advertise,
     * bit1 BLE-scan (role swap), bit2 L2CAP, bit3 LOHS, bit4 Aware.
     * 0 on pre-V4 senders → the receiver skips the control channel.
     */
    val cap: Int = 0,
    /**
     * V4 §5 (optional): sender long-term identity public key, P-256 X.509 DER.
     * Drives peer recognition + adaptive transport ordering. Never a security
     * input for this session (the ephemeral key in `pk` is).
     */
    val senderIdentityKey: ByteArray? = null,
    /**
     * V4.1 (optional): the sender's Wi-Fi station (LAN) IPv4. When both devices
     * are on the same network the receiver connects here directly — a plain LAN
     * socket, no Wi-Fi Direct and no channel conflict. Null when the sender isn't
     * on a normal Wi-Fi network (only the GO/p2p interface).
     */
    val lanIp: String? = null,
    /**
     * V4 (optional): true when [ssid]/[psk] describe the sender's mobile HOTSPOT (a SoftAP), not a
     * Wi-Fi Direct group. The receiver must then JOIN it via [android.net.wifi.WifiNetworkSpecifier]
     * (SOFTAP_JOIN), never a WifiP2pConfig credential join. Carried in the QR so the receiver can do
     * the right join even when the BLE control channel never links (which otherwise carries this as
     * the `nk` HELLO field). Serialized as `nk`=1.
     */
    val softApBridge: Boolean = false
) {

    fun toJson(): String {
        return JSONObject().apply {
            put("v", version)
            put("t", TYPE)
            put("ssid", ssid)
            put("psk", psk)
            put("ip", ip)
            put("port", port)
            put("pk", Base64.encodeToString(senderPublicKey, Base64.NO_WRAP))
            put("tok", Base64.encodeToString(token, Base64.NO_WRAP))
            put("name", senderName)
            put("n", fileCount)
            put("b", totalBytes)
            // Emitted only when known — old receivers ignore unknown keys.
            goDeviceAddress?.let { put("mac", it) }
            frequencyMhz?.let { put("freq", it) }
            if (cap != 0) put("cap", cap)
            senderIdentityKey?.let { put("idk", Base64.encodeToString(it, Base64.NO_WRAP)) }
            lanIp?.let { put("lan", it) }
            if (softApBridge) put("nk", 1)
        }.toString()
    }

    /**
     * A LAN-only payload carries NO Wi-Fi AP credentials (ssid/psk empty) — the sender is already
     * reachable on a shared network or its own hotspot, so the receiver (already on that network)
     * connects straight to [lanIp]/[ip] instead of joining a Wi-Fi Direct group.
     */
    fun isLanOnly(): Boolean = ssid.isEmpty() && psk.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OffGridPayload) return false
        return version == other.version && ssid == other.ssid && psk == other.psk &&
                ip == other.ip && port == other.port &&
                senderPublicKey.contentEquals(other.senderPublicKey) &&
                token.contentEquals(other.token) && senderName == other.senderName &&
                fileCount == other.fileCount && totalBytes == other.totalBytes &&
                goDeviceAddress == other.goDeviceAddress && frequencyMhz == other.frequencyMhz &&
                cap == other.cap && lanIp == other.lanIp && softApBridge == other.softApBridge &&
                ((senderIdentityKey == null && other.senderIdentityKey == null) ||
                        (senderIdentityKey != null && other.senderIdentityKey != null &&
                                senderIdentityKey.contentEquals(other.senderIdentityKey)))
    }

    override fun hashCode(): Int = token.contentHashCode()

    sealed class ParseResult {
        data class Valid(val payload: OffGridPayload) : ParseResult()
        /** Schema version newer than this app understands — "update the app". */
        object UnsupportedVersion : ParseResult()
        /** Wrong-app QR / malformed payload — rejected before any network action. */
        object Invalid : ParseResult()
    }

    companion object {
        const val SCHEMA_VERSION = 1
        /**
         * Highest wire version this generation parses. Senders still emit
         * [SCHEMA_VERSION] (1) — bumping the emitted version would brick
         * new-sender → old-receiver for no benefit, since the new keys are
         * additive and old receivers ignore them. Accepting 1..MAX here only
         * future-proofs against a later breaking version being rejected outright.
         */
        const val SCHEMA_VERSION_MAX = 2
        const val TYPE = "p2p"

        /** Legacy Wi-Fi Direct group-owner address — fallback only; newer Android builds can randomize the GO IP. */
        const val GROUP_OWNER_IP = "192.168.49.1"
        const val TRANSFER_PORT = Ports.FILE_TRANSFER  // the off-grid data port is the LAN file-transfer port
        const val TOKEN_SIZE_BYTES = 16

        const val NDEF_MIME_TYPE = "application/vnd.clipboardshare.p2p"

        private val IPV4_REGEX = Regex(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
        )

        private val MAC_REGEX = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

        /** P2P group operating frequencies span 2.4/5/6 GHz — accept a generous MHz window. */
        private const val FREQ_MIN_MHZ = 2000
        private const val FREQ_MAX_MHZ = 7200

        /** The "anonymized"/zero MAC the OS hands back without nearby/location grants — never a usable target. */
        private fun isPlaceholderMac(mac: String): Boolean {
            val lower = mac.lowercase()
            return lower == "02:00:00:00:00:00" || lower == "00:00:00:00:00:00"
        }

        fun newToken(): ByteArray {
            val token = ByteArray(TOKEN_SIZE_BYTES)
            SecureRandom().nextBytes(token)
            return token
        }

        fun build(
            ssid: String,
            psk: String,
            senderPublicKey: ByteArray,
            token: ByteArray,
            senderName: String,
            fileCount: Int,
            totalBytes: Long,
            ip: String = GROUP_OWNER_IP,
            goDeviceAddress: String? = null,
            frequencyMhz: Int? = null,
            cap: Int = 0,
            senderIdentityKey: ByteArray? = null,
            lanIp: String? = null,
            softApBridge: Boolean = false
        ): OffGridPayload {
            return OffGridPayload(
                version = SCHEMA_VERSION,
                ssid = ssid,
                psk = psk,
                ip = ip,
                port = TRANSFER_PORT,
                senderPublicKey = senderPublicKey,
                token = token,
                senderName = senderName.take(32),
                fileCount = fileCount,
                totalBytes = totalBytes,
                // Drop a placeholder/zero MAC here so we never advertise an unusable hint.
                goDeviceAddress = goDeviceAddress
                    ?.takeIf { MAC_REGEX.matches(it) && !isPlaceholderMac(it) },
                frequencyMhz = frequencyMhz?.takeIf { it in FREQ_MIN_MHZ..FREQ_MAX_MHZ },
                cap = cap,
                senderIdentityKey = senderIdentityKey,
                // Only advertise a real, routable LAN IPv4 (not the GO subnet).
                lanIp = lanIp?.takeIf { IPV4_REGEX.matches(it) && !it.startsWith("192.168.49.") },
                softApBridge = softApBridge
            )
        }

        /**
         * Strict schema validation per §3.1: unknown type, bad base64,
         * non-P-256 key and out-of-range values are all rejected here,
         * before any network action.
         */
        fun parse(raw: String): ParseResult {
            val json = try { JSONObject(raw) } catch (_: Exception) { return ParseResult.Invalid }

            val type = json.optString("t")
            if (type != TYPE) return ParseResult.Invalid

            val version = json.optInt("v", -1)
            if (version < 1) return ParseResult.Invalid
            if (version > SCHEMA_VERSION_MAX) return ParseResult.UnsupportedVersion

            val lanIp = json.optString("lan").takeIf { it.isNotEmpty() }
                ?.takeIf { IPV4_REGEX.matches(it) && !it.startsWith("192.168.49.") }

            val ssid = json.optString("ssid")
            val psk = json.optString("psk")
            // LAN-only payload (sender already on a shared network / its own hotspot): no AP
            // credentials, just an IP the receiver — already on that network — connects to.
            if (ssid.isEmpty() && psk.isEmpty()) {
                if (lanIp == null) return ParseResult.Invalid
            } else {
                if (ssid.isEmpty() || ssid.toByteArray(Charsets.UTF_8).size > 32) return ParseResult.Invalid
                if (psk.length < 8 || psk.length > 63) return ParseResult.Invalid
            }

            val ip = json.optString("ip")
            if (!IPV4_REGEX.matches(ip)) return ParseResult.Invalid

            val port = json.optInt("port", -1)
            if (port < 1 || port > 65535) return ParseResult.Invalid

            val publicKey = try {
                Base64.decode(json.optString("pk"), Base64.NO_WRAP)
            } catch (_: Exception) { return ParseResult.Invalid }
            if (!isValidP256Key(publicKey)) return ParseResult.Invalid

            val token = try {
                Base64.decode(json.optString("tok"), Base64.NO_WRAP)
            } catch (_: Exception) { return ParseResult.Invalid }
            if (token.size != TOKEN_SIZE_BYTES) return ParseResult.Invalid

            val name = json.optString("name")
            if (name.length > 32) return ParseResult.Invalid

            val fileCount = json.optInt("n", -1)
            if (fileCount < 1) return ParseResult.Invalid

            val totalBytes = json.optLong("b", -1L)
            if (totalBytes < 0L) return ParseResult.Invalid

            // Optional fallback hints — they are never security inputs, so a malformed
            // value is silently dropped to null rather than rejecting the whole payload.
            val goDeviceAddress = json.optString("mac").takeIf { it.isNotEmpty() }
                ?.takeIf { MAC_REGEX.matches(it) && !isPlaceholderMac(it) }
            val frequencyMhz = json.optInt("freq", -1).takeIf { it in FREQ_MIN_MHZ..FREQ_MAX_MHZ }
            val cap = json.optInt("cap", 0).coerceIn(0, 0xFF)
            val senderIdentityKey = json.optString("idk").takeIf { it.isNotEmpty() }?.let { idk ->
                try {
                    Base64.decode(idk, Base64.NO_WRAP).takeIf { isValidP256Key(it) }
                } catch (_: Exception) { null }
            }
            val softApBridge = json.optInt("nk", 0) == 1
            return ParseResult.Valid(
                OffGridPayload(
                    version, ssid, psk, ip, port, publicKey, token, name, fileCount, totalBytes,
                    goDeviceAddress, frequencyMhz, cap, senderIdentityKey, lanIp, softApBridge
                )
            )
        }

        private fun isValidP256Key(encoded: ByteArray): Boolean {
            return try {
                val kf = KeyFactory.getInstance("EC")
                val key = kf.generatePublic(X509EncodedKeySpec(encoded)) as? ECPublicKey ?: return false
                key.params.curve.field.fieldSize == 256
            } catch (_: Exception) {
                false
            }
        }
    }
}

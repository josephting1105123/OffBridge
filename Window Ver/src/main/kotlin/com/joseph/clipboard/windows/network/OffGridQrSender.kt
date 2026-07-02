package com.joseph.clipboard.windows.network

import com.google.gson.GsonBuilder
import com.joseph.clipboard.windows.storage.CryptoEngine
import com.joseph.clipboard.windows.storage.EcdhSession
import java.io.File
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Windows-side "send via QR" off-grid host.
 *
 * Windows is always the SENDER: it shows a QR encoding an [com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridPayload]
 * (schema v1), then an Android phone scans it and dials back in to the file-transfer port. Two carriers:
 *  - **LAN** (shared network): the QR carries the PC's LAN IPv4 in `lan`; the phone connects directly.
 *  - **Off-grid hotspot** (no shared network): the PC stands up a Wi-Fi Direct GO (see [HotspotController]);
 *    the QR carries its real ssid/psk + `nk=1`, the phone JOINs it (`SOFTAP_JOIN`) and dials the GO IP.
 * In hotspot mode the PC also advertises a BLE control channel (see
 * [com.joseph.clipboard.windows.network.control.PcQrSenderController]) when the adapter supports the
 * peripheral role — signalled via [CAP_BLE_ADVERTISE] in `cap`. NFC and the Wi-Fi-Direct-credential
 * transport tiers remain Android-only and out of scope here.
 *
 * Wire protocol (one duplex socket, LF-terminated UTF-8 frames, `|`-separated):
 *   1. receiver → P2P_HELLO|tokenB64|receiverPubB64|name|HMAC(K,token)B64
 *   2. sender   → P2P_WELCOME|sessionId|HMAC(K, token‖"ack")B64
 *   3. sender   → OFFER|sessionId|name|count  then  MANIFEST|name:size|...|
 *   4. receiver → ACCEPT_OFFER|sessionId
 *   5. sender   → raw file bytes (manifest order, no framing), then close.
 *
 * Crypto matches Android exactly: ECDH(secp256r1, X.509 pubkeys) →
 * K = SHA-256(sharedSecret); K is used ONLY as an HMAC-SHA256 key for the two
 * key-confirmation values. No HKDF, no AES — the file stream is plaintext
 * (admission is authenticated by token + HMAC).
 */
object OffGridQrSender {

    /** Highest wire version we emit (Android `OffGridPayload.SCHEMA_VERSION`). */
    private const val SCHEMA_VERSION = 1
    private const val TYPE = "p2p"
    const val TRANSFER_PORT = Ports.FILE_TRANSFER // 65433, == Android OffGridPayload.TRANSFER_PORT
    private const val TOKEN_SIZE_BYTES = 16

    /** Wi-Fi Direct GO gateway — matches Android `OffGridPayload.GROUP_OWNER_IP`; also a schema-valid `ip` placeholder. */
    private const val GROUP_OWNER_IP = "192.168.49.1"

    /**
     * `cap` bit advertising that we wait for an end-to-end TRANSFER_COMPLETE ack before
     * declaring the transfer done — identical to Android `TransportCaps.COMPLETION_ACK`.
     * Lets the phone resume to a still-live session after a late link drop instead of
     * hitting a sender that already finished on its own TCP write.
     */
    const val CAP_COMPLETION_ACK = 1 shl 5

    /**
     * `cap` bit advertising that we run a BLE control channel under the token-derived session
     * UUID — identical to Android `TransportCaps.BLE_ADVERTISE`. Set ONLY after the GATT
     * advertisement demonstrably started (fail-soft: without it the phone never scans and the
     * flow is byte-identical to the pre-BLE QR).
     */
    const val CAP_BLE_ADVERTISE = 1 shl 0

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /** One active QR advertising window: ephemeral key + admission token + the files to serve. */
    data class HostSession(
        val sessionId: String,
        val ecdh: EcdhSession,
        val token: ByteArray,
        val files: List<File>,
        val deviceName: String
    )

    fun newToken(): ByteArray = CryptoEngine.generateRandomBytes(TOKEN_SIZE_BYTES)

    /**
     * Builds the raw JSON string to render as the QR (no prefix, no base64 of the whole — the Android
     * scanner feeds the rawValue straight into `OffGridPayload.parse`).
     *
     * Two modes, by whether a Wi-Fi Direct AP is up ([hotspot] non-null):
     *  - **Off-grid hotspot:** advertise the real AP `ssid`/`psk`, the GO gateway as `ip`, and `nk=1`
     *    so the receiver does a `SOFTAP_JOIN` (join the AP via `WifiNetworkSpecifier`) rather than a
     *    Wi-Fi Direct credential join. `lan` is added only when a shared network also exists.
     *  - **LAN-only fallback** (no AP, PC on a shared network): emit EMPTY `ssid`/`psk` so the
     *    receiver's `isLanOnly()` short-circuits straight to the LAN socket — and on failure shows a
     *    clean "can't reach" message instead of running a doomed Wi-Fi Direct ladder. `ip` keeps a
     *    schema-valid placeholder ([GROUP_OWNER_IP]); the real target is the `lan` field.
     */
    fun buildPayloadJson(
        session: HostSession,
        hotspot: HotspotController.HotspotInfo?,
        lanIp: String?,
        cap: Int = CAP_COMPLETION_ACK
    ): String {
        val b64 = Base64.getEncoder()
        val totalBytes = session.files.sumOf { it.length() }
        // LinkedHashMap → compact, ordered JSON via Gson (HTML-escaping disabled so
        // base64 '+'/'/'/'=' and any unicode stay verbatim, matching org.json output).
        val map = linkedMapOf<String, Any>(
            "v" to SCHEMA_VERSION,
            "t" to TYPE,
            // Real AP creds when bridging a hotspot; EMPTY for LAN-only so isLanOnly() is true.
            "ssid" to (hotspot?.ssid ?: ""),
            "psk" to (hotspot?.passphrase ?: ""),
            "ip" to (hotspot?.gatewayIp ?: GROUP_OWNER_IP),
            "port" to TRANSFER_PORT,
            "pk" to b64.encodeToString(session.ecdh.getPublicKey()),
            "tok" to b64.encodeToString(session.token),
            "name" to session.deviceName.take(32),
            "n" to session.files.size,
            "b" to totalBytes,
            // Delivery-ack + (when advertising) BLE-control bits; old phones ignore unknown bits.
            "cap" to cap
        )
        // Same-network shortcut: a phone already on this LAN connects directly (fast). Omitted off-grid
        // so the phone doesn't waste 2.5s probing an unreachable LAN IP before joining the hotspot.
        if (lanIp != null) map["lan"] = lanIp
        // nk=1 ⇒ ssid/psk are a SoftAP to JOIN (SOFTAP_JOIN), not a Wi-Fi Direct group.
        if (hotspot != null) map["nk"] = 1
        return gson.toJson(map)
    }

    /**
     * Outcome of [runHandshake]: whether the receiver accepted, and whether it
     * appended "|ack" to ACCEPT_OFFER (i.e. it will send TRANSFER_COMPLETE and we
     * must wait for it before declaring the transfer done).
     */
    data class HandshakeResult(val accepted: Boolean, val ackExpected: Boolean) {
        companion object { val REJECTED = HandshakeResult(false, false) }
    }

    /**
     * Runs the inbound handshake on [socket] after the daemon has read the
     * P2P_HELLO [header]. On success the WELCOME/OFFER/MANIFEST have been sent
     * and the receiver's ACCEPT_OFFER has been read — the caller then streams
     * the file bytes on the same socket. Returns [HandshakeResult.REJECTED]
     * (and leaves the socket for the caller to close) on any validation failure.
     */
    fun runHandshake(socket: Socket, header: String, session: HostSession): HandshakeResult {
        // parts[3] (the receiver name) is untrusted and may contain '|', so index positionally.
        val parts = header.split("|")
        if (parts.size < 5) return HandshakeResult.REJECTED

        val decoder = Base64.getDecoder()
        val token: ByteArray
        val receiverPub: ByteArray
        val helloHmac: ByteArray
        try {
            token = decoder.decode(parts[1])
            receiverPub = decoder.decode(parts[2])
            helloHmac = decoder.decode(parts[4])
        } catch (e: Exception) {
            return HandshakeResult.REJECTED
        }

        // Constant-time token match against the one we advertised in the QR.
        if (!MessageDigest.isEqual(token, session.token)) return HandshakeResult.REJECTED

        // K = SHA-256(ECDH(myPriv, receiverPub)); deriveSecrets returns it as `.first`.
        val sharedKey = try {
            session.ecdh.deriveSecrets(receiverPub).first
        } catch (e: Exception) {
            return HandshakeResult.REJECTED
        }

        if (!MessageDigest.isEqual(helloHmac, hmacSha256(sharedKey, token))) return HandshakeResult.REJECTED

        val out = socket.getOutputStream()

        // P2P_WELCOME is the gate: the receiver aborts unless HMAC(K, token‖"ack") verifies.
        val ack = hmacSha256(sharedKey, token + "ack".toByteArray(Charsets.UTF_8))
        out.write("P2P_WELCOME|${session.sessionId}|${Base64.getEncoder().encodeToString(ack)}\n".toByteArray(Charsets.UTF_8))

        // Existing single-socket sequence: OFFER then MANIFEST (basenames; trailing '|').
        val offer = "OFFER|${session.sessionId}|${session.deviceName}|${session.files.size}\n"
        val manifest = StringBuilder("MANIFEST|")
        for (f in session.files) manifest.append("${f.name}:${f.length()}|")
        manifest.append("\n")
        out.write(offer.toByteArray(Charsets.UTF_8))
        out.write(manifest.toString().toByteArray(Charsets.UTF_8))
        out.flush()

        // Receiver confirms on the same socket before any bytes flow. A COMPLETION_ACK-capable
        // receiver appends "|ack"; index positionally so the optional suffix is tolerated.
        val reply = Net.readLineUtf8(socket.getInputStream())
        val replyParts = reply.split("|")
        val accepted = replyParts.getOrNull(0) == "ACCEPT_OFFER" && replyParts.getOrNull(1) == session.sessionId
        return HandshakeResult(accepted, accepted && replyParts.getOrNull(2) == "ack")
    }

    /** Reject an inbound off-grid dial when no QR is being shown (or one is in flight). */
    fun writeBusy(socket: Socket) {
        try {
            socket.getOutputStream().run {
                write("P2P_BUSY\n".toByteArray(Charsets.UTF_8))
                flush()
            }
        } catch (e: Exception) {
            // best-effort
        }
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }
}

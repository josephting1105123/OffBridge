package com.joseph.clipboard.windows.network

import com.google.gson.GsonBuilder
import com.joseph.clipboard.windows.network.control.SessionCrypto
import com.joseph.clipboard.windows.storage.CryptoEngine
import com.joseph.clipboard.windows.storage.DiagLog
import com.joseph.clipboard.windows.storage.EcdhSession
import com.joseph.clipboard.windows.storage.FileSeal
import java.io.File
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

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

    /**
     * `cap` bit advertising that we will FileSeal (AES-256-GCM) the off-grid file stream when the
     * receiver asks for it — identical to Android `TransportCaps.SEALED_STREAM`. Bit 6 is the first
     * free one (0..5 are BLE_ADVERTISE, BLE_SCAN, BT_L2CAP, LOHS, AWARE, COMPLETION_ACK).
     *
     * Historically the off-grid stream was PLAINTEXT: the ECDH secret authenticated admission (the
     * two HMAC key-confirmation values) but never protected the bytes, unlike the LAN path which has
     * been FileSeal-sealed since 3.4.0. This closes that gap without inventing any crypto: the
     * already-agreed, already-mutually-confirmed ECDH secret is fed to the same [FileSeal] v2 path
     * the LAN transfers use, and HKDF's distinct info label domain-separates the AEAD sub-key from
     * that secret's other use as the raw HMAC key for the two confirmation values.
     *
     * Negotiation is two-sided so nothing already installed breaks: an old sender never sets this
     * bit, so a new receiver never asks; an old receiver ignores an unknown bit, so it never sends
     * the `seal` flag and we stream plaintext exactly as before. Only new↔new seals.
     */
    const val CAP_SEALED_STREAM = 1 shl 6

    /**
     * `cap` bit: BLE control frames use SessionCrypto v2 (frame header bound as GCM AAD, own HKDF
     * label) — identical to Android `TransportCaps.CTRL_AAD`. Set together with
     * [CAP_BLE_ADVERTISE]; the phone then always uses v2, and [PcQrSenderController] uses v2 iff the
     * phone's HELLO_RX carries the bit too. A stripped HELLO bit leaves the two ends on different
     * keys, so the session fails closed rather than downgrading.
     */
    const val CAP_CTRL_AAD = 1 shl 7

    /** Flag a sealing-capable receiver appends to ACCEPT_OFFER, mirroring the LAN path's "fgcm2". */
    const val ACCEPT_FLAG_SEAL = "seal"

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
        cap: Int = CAP_COMPLETION_ACK or CAP_SEALED_STREAM
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
    data class HandshakeResult(
        val accepted: Boolean,
        val ackExpected: Boolean,
        /** Non-null iff the receiver asked for [ACCEPT_FLAG_SEAL]: the RAW ECDH secret, to be handed
         *  to the byte-pump as its `fileKey` with `useV2 = true`. Raw and not pre-derived on purpose
         *  — FileStreamer already applies [FileSeal.deriveKeyV2] itself, exactly as it does to the
         *  stored pairing key on the LAN path, so pre-deriving here would derive twice and the two
         *  ends would never agree. Null means a legacy receiver that can't unseal, and the caller
         *  streams plaintext as before. */
        val sealKey: ByteArray? = null,
        /** The RAW ECDH secret, sealed or not. A later off-grid RESUME_REQUEST must prove it
         *  ([OffGridResumeAuth]), since the phone comes back from an IP nothing has paired. */
        val authKey: ByteArray? = null,
    ) {
        companion object { val REJECTED = HandshakeResult(false, false) }
    }

    /**
     * Runs the inbound handshake on [socket] after the daemon has read the
     * P2P_HELLO [header]. On success the WELCOME/OFFER/MANIFEST have been sent
     * and the receiver's ACCEPT_OFFER has been read — the caller then streams
     * the file bytes on the same socket. Returns [HandshakeResult.REJECTED]
     * (and leaves the socket for the caller to close) on any validation failure.
     *
     * [manifestEntries] is the caller's name→size snapshot (from its OutgoingFileSession): the
     * MANIFEST must commit the receiver to exactly the byte counts the streamer will send.
     * Re-reading file.length() here (the old behavior) let a file change size between this frame
     * and the stream, silently desyncing the positional read on the receiver (XP-DEF-04). The
     * pairs travel together so a name can't be zipped against the wrong size.
     */
    fun runHandshake(socket: Socket, header: String, session: HostSession, manifestEntries: List<Pair<String, Long>>): HandshakeResult {
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

        if (!MessageDigest.isEqual(helloHmac, SessionCrypto.hmacSha256(sharedKey, token))) return HandshakeResult.REJECTED

        val out = socket.getOutputStream()

        // P2P_WELCOME is the gate: the receiver aborts unless HMAC(K, token‖"ack") verifies.
        val ack = SessionCrypto.hmacSha256(sharedKey, token + "ack".toByteArray(Charsets.UTF_8))
        out.write("P2P_WELCOME|${session.sessionId}|${Base64.getEncoder().encodeToString(ack)}\n".toByteArray(Charsets.UTF_8))

        // Existing single-socket sequence: OFFER then MANIFEST (basenames; trailing '|').
        val offer = WireFrames.offerLine(session.sessionId, session.deviceName, manifestEntries.size)
        val manifest = ManifestCodec.encode(manifestEntries)
        out.write(offer.toByteArray(Charsets.UTF_8))
        out.write(manifest.toByteArray(Charsets.UTF_8))
        out.flush()

        // Receiver confirms on the same socket before any bytes flow. Trailing flags after the
        // sessionId are order-independent (a COMPLETION_ACK-capable receiver appends "ack"; a
        // sealing-capable one appends "seal"), so match by membership rather than by position —
        // positional indexing broke the moment a second optional flag existed.
        val reply = Net.readLineUtf8(socket.getInputStream())
        val replyParts = reply.split("|")
        val accepted = replyParts.getOrNull(0) == "ACCEPT_OFFER" && replyParts.getOrNull(1) == session.sessionId
        if (!accepted) return HandshakeResult.REJECTED
        val flags = replyParts.drop(2)
        // Seal only when the receiver actually asked. It only asks when it saw CAP_SEALED_STREAM,
        // so an old receiver silently keeps the pre-3.7.7 plaintext behavior.
        val sealKey = if (flags.contains(ACCEPT_FLAG_SEAL)) sharedKey else null
        // Log the negotiated OUTCOME. A plaintext fallback is externally indistinguishable from a
        // sealed transfer — the files still arrive and nothing errors — so without this line there
        // is no way to tell the two apart on a machine or in a diagnostics.log a tester sends back.
        DiagLog.w(
            if (sealKey != null) "QR send: off-grid stream SEALED (AES-256-GCM, FileSeal v2 off the ECDH secret)"
            else "QR send: off-grid stream PLAINTEXT — the phone did not request the seal; update the phone to encrypt off-grid transfers"
        )
        return HandshakeResult(true, flags.contains("ack"), sealKey, authKey = sharedKey)
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

}

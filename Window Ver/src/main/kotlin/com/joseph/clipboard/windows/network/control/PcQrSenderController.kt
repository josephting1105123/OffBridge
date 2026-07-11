package com.joseph.clipboard.windows.network.control

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.joseph.clipboard.windows.network.Ports
import com.joseph.clipboard.windows.storage.EcdhSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What the QR-session control plane is doing — drives the ShowQrDialog status line. */
sealed class QrControlState {
    /** BLE is advertising; no phone has linked yet (also the pre-BLE/none state). */
    object Advertising : QrControlState()

    /** A phone subscribed to the GATT service; HELLO expected within the watchdog. */
    object CentralConnected : QrControlState()

    /** HELLO validated — the encrypted control channel is up. [phoneName] is display-only, untrusted. */
    data class Linked(val phoneName: String) : QrControlState()

    /** TRY_TRANSPORT(SOFTAP_JOIN) sent; the phone is joining [ssid] (attempt [attempt] of 3). */
    data class Joining(val attempt: Int, val ssid: String) : QrControlState()

    /** The phone reported the Wi-Fi join OK — its TCP dial to the transfer port is next. */
    object AwaitingDial : QrControlState()

    /** The TCP handshake was accepted; the data plane owns the session from here. */
    object Transferring : QrControlState()

    /**
     * The coordinated ladder gave up. [lastFailureCode] is an Android `FailureCode` id;
     * [firewallSuspected] marks the joined-but-TCP-dial-failed case (HANDSHAKE_FAILED).
     */
    data class Failed(val lastFailureCode: Int, val firewallSuspected: Boolean) : QrControlState()

    /** The BLE link died before a data route was up (phone left, helper crashed). The
     *  QR + hotspot remain valid — the phone can still degrade to the direct join. */
    object ControlLost : QrControlState()
}

/**
 * Minimal sender-side control-plane coordinator for the PC's off-grid QR session — the
 * subset of the phone's `SenderSessionController` a PC needs. The plan is only ever
 * `[SOFTAP_JOIN]` (the PC hosts the Wi-Fi Direct legacy GO and serves TCP at its gateway),
 * so instead of a multi-tier ladder this runs a bounded retry ladder over that one tier:
 *
 *   attempt 1 (QR creds) → attempt 2 (re-push, same creds) → regenerate AP → attempt 3
 *   (fresh creds) → ABORT("GENERIC") + Failed.
 *
 * Frames spoken (wire-compatible with the phone's `ReceiverSessionController`; layouts
 * pinned by ControlPlaneVectorTest): HELLO_RX in / HELLO_ACK out (plaintext), then sealed
 * DATA_READY, TRY_TRANSPORT{t=7,ssid,psk,nk=1}, TRANSPORT_RESULT{t,ok,code} in,
 * ABORT{reason} out; PING/PONG ride inside [ControlSession]. REQ_TRANSPORT / HOST_READY
 * are never valid against a PC (it can't join anything) and are ignored.
 *
 * All state is confined to the single-threaded [executor]; public entry points hop onto it.
 */
class PcQrSenderController(
    private val channel: ControlChannel,
    private val token: ByteArray,
    private val ecdh: EcdhSession,
    private val gatewayIp: String,
    initialSsid: String,
    initialPsk: String,
    /**
     * Tears the AP down and stands up a fresh one (HotspotController.restart()), returning the
     * new (ssid, psk) or null. Invoked on a throwaway thread — its ~9 s spin-up must not stall
     * the executor, or queued PONG replies would push the phone toward its 3-missed-pings limit.
     */
    private val regenerateCreds: (() -> Pair<String, String>?)? = null,
    private val helloWatchdogMs: Long = HELLO_WATCHDOG_MS,
    private val resultTimeoutMs: Long = RESULT_TIMEOUT_MS,
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "pc-qr-control").apply { isDaemon = true }
        }
) {

    private val _state = MutableStateFlow<QrControlState>(QrControlState.Advertising)
    val state: StateFlow<QrControlState> = _state

    /** Live AP creds — updated after a regeneration so the dialog can re-render the QR. */
    private val _creds = MutableStateFlow(initialSsid to initialPsk)
    val creds: StateFlow<Pair<String, String>> = _creds

    private var session: ControlSession? = null
    private var sharedKey: ByteArray? = null
    private var attempt = 0
    private var joinedOnce = false
    private var transferring = false
    private var finished = false
    private var helloWatchdog: ScheduledFuture<*>? = null
    private var resultWatchdog: ScheduledFuture<*>? = null

    /** Wire the channel callbacks. Call once, after the peripheral's advertising verdict is in. */
    fun begin() {
        channel.onMessage = { raw -> executor.execute { handlePreSessionFrame(raw) } }
        channel.onClosed = { executor.execute { handleControlLost() } }
    }

    /** A phone subscribed to the TX characteristic (BleControlPeripheral.onSubscribed). */
    fun onCentralSubscribed() {
        executor.execute {
            if (finished || transferring || session != null) return@execute
            _state.value = QrControlState.CentralConnected
            helloWatchdog?.cancel(false)
            helloWatchdog = executor.schedule({
                if (session == null && !finished && !transferring) {
                    // Central linked but HELLO never completed — same verdict as the phone's
                    // HELLO_WATCHDOG: the control channel failed; the QR/direct join stays valid.
                    _state.value = QrControlState.Advertising
                }
            }, helloWatchdogMs, TimeUnit.MILLISECONDS)
        }
    }

    /** The TCP P2P_HELLO handshake was accepted — the data plane owns the session now. */
    fun notifyTransferStarted() {
        executor.execute {
            transferring = true
            helloWatchdog?.cancel(false)
            resultWatchdog?.cancel(false)
            _state.value = QrControlState.Transferring
        }
    }

    /**
     * Best-effort sealed ABORT (so a linked phone fails fast instead of joining a dying AP),
     * then kills the channel. Never blocks the caller; the 200 ms grace lets the last GATT
     * notify flush before the helper dies.
     */
    fun closeWithAbort() {
        try {
            executor.execute {
                finished = true
                session?.send(ControlProtocol.ABORT, JsonObject().apply { addProperty("reason", ABORT_GENERIC) })
            }
            executor.schedule({
                session?.close()
                channel.close()
            }, ABORT_FLUSH_MS, TimeUnit.MILLISECONDS)
            executor.shutdown()
        } catch (_: Exception) {
        }
    }

    // ----------------------------------------------------------- HELLO phase

    private fun handlePreSessionFrame(raw: ByteArray) {
        if (finished || session != null) return // one session per QR window; later HELLOs ignored
        val frame = ControlProtocol.parse(raw) ?: return
        if (frame.type != ControlProtocol.HELLO_RX) return
        val body = try {
            JsonParser.parseString(String(frame.body, Charsets.UTF_8)).asJsonObject
        } catch (_: Exception) {
            return
        }

        val decoder = Base64.getDecoder()
        val receiverPub = try { decoder.decode(body.get("pk")?.asString ?: return) } catch (_: Exception) { return }
        val helloHmac = try { decoder.decode(body.get("hm")?.asString ?: return) } catch (_: Exception) { return }

        val key = try { ecdh.deriveSecrets(receiverPub).first } catch (_: Exception) { return }
        if (!MessageDigest.isEqual(helloHmac, SessionCrypto.hmacSha256(key, token))) return
        sharedKey = key
        helloWatchdog?.cancel(false)

        val phoneName = (body.get("name")?.asString ?: "").take(32).ifEmpty { "Unknown Device" }

        // HELLO_ACK travels plaintext (it completes the bootstrap), then the session seals.
        val ack = SessionCrypto.hmacSha256(key, token + "ack".toByteArray(Charsets.UTF_8))
        val ackBody = JsonObject().apply {
            addProperty("ack", Base64.getEncoder().encodeToString(ack))
            addProperty("cap", CAP_BITS)
            addProperty("gof", -1) // a PC never hosts a phone-side Wi-Fi Direct group election
            add("plan", JsonArray().apply { add(SOFTAP_JOIN_ID) })
        }
        channel.send(ControlProtocol.plainFrame(ControlProtocol.HELLO_ACK, ackBody))

        val s = ControlSession(channel, SessionCrypto(key, isSender = true), executor)
        session = s
        s.onFrame = { type, payload -> handleFrame(type, payload) }
        s.onDead = { handleControlLost() }
        s.start()

        _state.value = QrControlState.Linked(phoneName)

        // Exact-IP delivery: the phone dials this instead of guessing gateway candidates.
        s.send(ControlProtocol.DATA_READY, JsonObject().apply {
            addProperty("ip", gatewayIp)
            addProperty("port", TRANSFER_PORT)
        })
        startAttempt(1)
    }

    // ----------------------------------------------------------- retry ladder

    private fun startAttempt(n: Int) {
        if (finished || transferring) return
        attempt = n
        val (ssid, psk) = _creds.value
        _state.value = QrControlState.Joining(n, ssid)
        session?.send(ControlProtocol.TRY_TRANSPORT, JsonObject().apply {
            addProperty("t", SOFTAP_JOIN_ID)
            addProperty("ssid", ssid)
            addProperty("psk", psk)
            addProperty("nk", 1) // NetKind.SOFTAP — join via WifiNetworkSpecifier
        })
        resultWatchdog?.cancel(false)
        resultWatchdog = executor.schedule({
            // No TRANSPORT_RESULT inside the tier budget — treat like CONNECT_TIMEOUT.
            onAttemptFailed(FAILURE_CONNECT_TIMEOUT)
        }, resultTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun handleFrame(type: Int, payload: JsonObject) {
        if (finished || transferring) return
        when (type) {
            ControlProtocol.TRANSPORT_RESULT -> {
                if (payload.get("t")?.asInt != SOFTAP_JOIN_ID) return
                resultWatchdog?.cancel(false)
                val ok = payload.get("ok")?.asBoolean ?: false
                val code = payload.get("code")?.asInt ?: 0
                when {
                    ok -> {
                        joinedOnce = true
                        _state.value = QrControlState.AwaitingDial
                    }
                    code == FAILURE_HANDSHAKE_FAILED && joinedOnce -> {
                        // The phone joined the AP but its TCP dial failed — regenerating the AP
                        // can't fix that; the firewall almost certainly can. Invisible today.
                        finish(QrControlState.Failed(code, firewallSuspected = true))
                    }
                    else -> onAttemptFailed(code)
                }
            }
            // A PC cannot join a phone-hosted network — these tiers are never ordered, so any
            // HOST_READY / REQ_TRANSPORT from the phone is a protocol mismatch; drop silently.
            ControlProtocol.HOST_READY, ControlProtocol.REQ_TRANSPORT -> Unit
        }
    }

    private fun onAttemptFailed(code: Int) {
        if (finished || transferring) return
        when (attempt) {
            1 -> startAttempt(2) // re-push the same creds; SpecifierJoiner self-tears-down first
            2 -> regenerateAndRetry(code)
            else -> finish(QrControlState.Failed(code, firewallSuspected = false))
        }
    }

    private fun regenerateAndRetry(lastCode: Int) {
        val regen = regenerateCreds
        if (regen == null) {
            finish(QrControlState.Failed(lastCode, firewallSuspected = false))
            return
        }
        // Off-executor: restart() blocks ~9 s and the executor must keep answering PINGs.
        Thread({
            val fresh = try { regen() } catch (_: Exception) { null }
            executor.execute {
                if (finished || transferring) return@execute
                if (fresh == null) {
                    finish(QrControlState.Failed(lastCode, firewallSuspected = false))
                } else {
                    _creds.value = fresh // dialog re-renders the QR for not-yet-linked phones
                    startAttempt(3)
                }
            }
        }, "pc-qr-regen").apply { isDaemon = true }.start()
    }

    private fun finish(terminal: QrControlState) {
        finished = true
        resultWatchdog?.cancel(false)
        helloWatchdog?.cancel(false)
        session?.send(ControlProtocol.ABORT, JsonObject().apply { addProperty("reason", ABORT_GENERIC) })
        _state.value = terminal
    }

    private fun handleControlLost() {
        if (finished || transferring) return
        // The QR + AP stay up: a degraded phone can still do the direct SOFTAP_JOIN and dial in
        // (notifyTransferStarted flips the state when it does).
        resultWatchdog?.cancel(false)
        helloWatchdog?.cancel(false)
        session = null
        _state.value = QrControlState.ControlLost
    }

    companion object {
        /** Android `TransportDescriptor.SOFTAP_JOIN.id` — the only tier a PC can offer. */
        const val SOFTAP_JOIN_ID = 7

        /** Android `TransportCaps.BLE_ADVERTISE or COMPLETION_ACK` (1 | 32). */
        const val CAP_BITS = 33

        const val TRANSFER_PORT = Ports.FILE_TRANSFER

        /** Android `FailureCode.CONNECT_TIMEOUT` / `FailureCode.HANDSHAKE_FAILED`. */
        const val FAILURE_CONNECT_TIMEOUT = 7
        const val FAILURE_HANDSHAKE_FAILED = 8

        /** Android `LadderExhaustReason.GENERIC` — the only reason a PC reports. */
        const val ABORT_GENERIC = "GENERIC"

        /** Mirror of the phone sender's HELLO_WATCHDOG_MS. */
        const val HELLO_WATCHDOG_MS = 8_000L

        /** SOFTAP_JOIN budget (25 s) + specifier watchdog grace + margin. */
        const val RESULT_TIMEOUT_MS = 35_000L

        const val ABORT_FLUSH_MS = 200L
    }
}

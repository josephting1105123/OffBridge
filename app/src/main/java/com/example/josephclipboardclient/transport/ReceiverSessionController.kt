package com.EntropicSoftwareLab.ClipboardShare.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.EntropicSoftwareLab.ClipboardShare.EcdhSession
import com.EntropicSoftwareLab.ClipboardShare.Net
import com.EntropicSoftwareLab.ClipboardShare.SecurityVault
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import com.EntropicSoftwareLab.ClipboardShare.data.db.IdentityKeyStore
import com.EntropicSoftwareLab.ClipboardShare.data.db.OffGridRepository
import com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridPayload
import com.EntropicSoftwareLab.ClipboardShare.transport.control.BleControlClient
import com.EntropicSoftwareLab.ClipboardShare.transport.control.ControlProtocol
import com.EntropicSoftwareLab.ClipboardShare.transport.control.ControlSession
import com.EntropicSoftwareLab.ClipboardShare.transport.wifidirect.P2pGroupManager
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Receiver-side session coordinator (§3 of the V4 plan).
 *
 * With a BLE-capable pair: connects the control channel, sends HELLO_RX
 * (which is the moment the sender learns the receiver exists — "the sending
 * side initiates" ~1–3 s after the scan), then executes TRY_TRANSPORT frames
 * and reports TRANSPORT_RESULT. Without BLE: degrades to the local Phase 1
 * ladder. Either way the fragment receives the same callbacks, so the UI has
 * exactly one code path.
 */
class ReceiverSessionController(
    context: Context,
    private val payload: OffGridPayload,
    private val hasNearbyPermission: Boolean,
    private val listener: Listener
) {

    interface Listener {
        /** A tier attempt began ([ordinal]/[total] for "trying method k of n"). */
        fun onTierStarted(tier: TransportDescriptor, ordinal: Int, total: Int)
        /** A route is live. [exactIp]/[exactPort] from DATA_READY override candidate guessing. */
        fun onRouteReady(tier: TransportDescriptor, route: TransportRoute, exactIp: String?, exactPort: Int?)
        /** [controlFailed] = the BLE coordination link was the likely cause (sender reported
         *  CONTROL_FAILED, or this side degraded off a dead control channel) — lets the UI name it. */
        fun onAllFailed(controlFailed: Boolean)
        /** A live specifier network dropped mid-use. */
        fun onRouteLost()
    }

    /** One ephemeral key for both planes: control HELLOs and the TCP P2P_HELLO. */
    val ecdh = EcdhSession()

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ladder = TransportLadder(appContext, payload)

    /** This device's GO-fitness — computed once, advertised in HELLO_RX so the sender can elect. */
    private val myGof: Int by lazy { GoFitness.score(appContext) }

    /** This device's rendezvous capabilities (hotspot/STA+AP/creds), advertised in HELLO_RX. */
    private val myCaps: DeviceCaps by lazy { Capabilities.compute(appContext) }

    private var bleClient: BleControlClient? = null
    private var session: ControlSession? = null
    private var sharedKey: ByteArray? = null

    // RECEIVER_AS_GO: when WE are the elected group owner, we host the group and the sender joins.
    private var groupManager: P2pGroupManager? = null
    private var goSelfHostIp: String? = null  // our GO-side p2p IPv4, for binding the outgoing socket

    private var exactIp: String? = null
    private var exactPort: Int? = null
    private var currentTier: TransportDescriptor? = null
    private var tierOrdinal = 0
    private var planSize = 0
    private var senderCaps = 0

    private var localLadderStarted = false

    @Volatile private var routeDelivered = false
    @Volatile private var torndown = false

    /** Set when we drop to the Wi-Fi-only ladder because the BLE control channel failed; if that
     *  ladder then exhausts, the failure is reported as control-caused (see [degradeToLocalLadder]). */
    @Volatile private var controlDegraded = false

    companion object {
        private const val TAG = "OffGridCtl"
        private const val HELLO_ACK_TIMEOUT_MS = 5_000L

        /**
         * When to re-send HELLO_RX if no ACK has come back, inside the same 5 s budget.
         *
         * The handshake used to be a single shot: one frame, then a five-second wait, then degrade.
         * Anything that ate that one frame — a chunk lost in the peer's GATT server, or a peripheral
         * that negotiates a 247-byte MTU and then quietly swallows writes near the limit — cost the
         * whole control channel, which is the "HELLO_RX sent" / "HELLO never completed" stand-off in
         * the HONOR traces. The retries go out with ATT-minimum chunks, so the second attempt does
         * not repeat whatever the first one tripped over.
         */
        private val HELLO_RETRY_DELAYS_MS = longArrayOf(1_400L, 2_800L)
    }

    private val helloTimeoutToken = Any()

    // ----------------------------------------------------------------- start

    fun start() {
        torndown = false
        // Scope the diagnostics screen to THIS session even if it records no attempt rows.
        OffGridRepository.markActiveLadder(appContext, ladder.ladderId)
        val senderAdvertises = (payload.cap and TransportCaps.BLE_ADVERTISE) != 0
        val canScan = BleControlClient.canScan(appContext)
        TransportDiag.log(
            "RX: start nearbyPerm=$hasNearbyPermission senderBLE=$senderAdvertises canScan=$canScan " +
                "senderCap=0x%02x".format(payload.cap)
        )
        if (senderAdvertises && canScan) {
            TransportDiag.log("BLE: scanning for sender (${BleControlClient.SCAN_BUDGET_MS / 1000}s)…")
            val client = BleControlClient(appContext)
            bleClient = client
            client.start(ControlProtocol.sessionServiceUuid(payload.token)) { device ->
                if (torndown) return@start
                if (device == null) {
                    // No BLE control channel → drop to the Wi-Fi-only local ladder.
                    val matched = bleClient?.device
                    TransportDiag.log(
                        if (matched != null) "BLE: device matched but control session FAILED → Wi-Fi-only degrade"
                        else "BLE: NO device match (sender not advertising, or scan blocked) → Wi-Fi-only"
                    )
                    degradeToLocalLadder()
                } else {
                    TransportDiag.log("BLE: matched sender ✓ → linking control channel")
                    sendHelloRx(client)
                }
            }
        } else {
            TransportDiag.log("BLE unavailable here → Wi-Fi-only local ladder (no BT fallback possible)")
            startLocalLadder()
        }
    }

    // ----------------------------------------------------------- HELLO phase

    @SuppressLint("MissingPermission")
    private fun sendHelloRx(client: BleControlClient) {
        val key = try { ecdh.deriveSecrets(payload.senderPublicKey).first } catch (_: Exception) {
            degradeToLocalLadder(); return
        }
        sharedKey = key

        client.onMessage = { raw -> mainHandler.post { handlePreSessionFrame(raw) } }
        client.onClosed = { mainHandler.post { handleControlLost() } }

        // Best-effort own-MAC fetch for the sender's T2 dial; placeholder MACs dropped (§9 #9).
        fetchOwnP2pMac { mac ->
            if (torndown) return@fetchOwnP2pMac
            val hello = JSONObject().apply {
                put("hm", Base64.encodeToString(SessionCrypto.hmacSha256(key, payload.token), Base64.NO_WRAP))
                put("pk", Base64.encodeToString(ecdh.getPublicKey(), Base64.NO_WRAP))
                put("name", TransferDaemon.customDeviceName.take(32))
                put("cap", localCaps())
                put("perm", hasNearbyPermission)
                put("gof", myGof)
                // Rendezvous capabilities so the sender's buildPlan can pick a SoftAP route.
                put("cja", myCaps.canJoinAp)
                put("cha", myCaps.canHostAp)
                put("hsa", myCaps.hotspotActive)
                put("sac", myCaps.staApConcurrency)
                put("hcr", myCaps.hasCreds)
                put("sta", myCaps.staConnected) // am I already on a Wi-Fi network (not free to host)?
                mac?.let { put("mac", it) }
                IdentityKeyStore.publicKeyDer(appContext)?.let {
                    put("idk", Base64.encodeToString(it, Base64.NO_WRAP))
                }
            }
            val frame = ControlProtocol.plainFrame(ControlProtocol.HELLO_RX, hello)
            val attempts = HELLO_RETRY_DELAYS_MS.size + 1
            TransportDiag.log(
                "control: HELLO_RX sent (1/$attempts), awaiting ACK (${HELLO_ACK_TIMEOUT_MS / 1000}s)…"
            )
            client.send(frame)
            // Every retry shares helloTimeoutToken with the deadline below, so the ACK handler's
            // single removeCallbacksAndMessages(helloTimeoutToken) cancels the whole schedule.
            HELLO_RETRY_DELAYS_MS.forEachIndexed { i, delay ->
                mainHandler.postDelayed({
                    if (torndown || session != null) return@postDelayed
                    TransportDiag.log("control: no HELLO_ACK yet — re-sending HELLO_RX (${i + 2}/$attempts, small chunks)")
                    client.resendSmall(frame)
                }, helloTimeoutToken, delay)
            }
            mainHandler.postDelayed({
                if (!torndown && session == null) {
                    Log.i(TAG, "HELLO_ACK timeout — degraded local ladder")
                    TransportDiag.log("control: HELLO_ACK TIMEOUT (sender GATT not responding) → Wi-Fi-only degrade")
                    degradeToLocalLadder()
                }
            }, helloTimeoutToken, HELLO_ACK_TIMEOUT_MS)
        }
    }

    private fun handlePreSessionFrame(raw: ByteArray) {
        if (torndown) return
        // The sender seals DATA_READY and TRY_TRANSPORT straight after HELLO_ACK. When their chunks
        // are reassembled before this handler has run for the ACK, they are queued here too — and
        // were discarded once the ACK built the session, leaving both sides waiting on each other.
        session?.let { it.deliver(raw); return }
        val frame = ControlProtocol.parse(raw) ?: return
        if (frame.type != ControlProtocol.HELLO_ACK) return
        val key = sharedKey ?: return
        val body = try { JSONObject(String(frame.body, Charsets.UTF_8)) } catch (_: Exception) { return }

        val ack = try { Base64.decode(body.optString("ack"), Base64.NO_WRAP) } catch (_: Exception) { return }
        val expected = SessionCrypto.hmacSha256(key, payload.token + "ack".toByteArray(Charsets.UTF_8))
        if (!MessageDigest.isEqual(ack, expected)) return

        mainHandler.removeCallbacksAndMessages(helloTimeoutToken)
        senderCaps = body.optInt("cap", 0)
        planSize = body.optJSONArray("plan")?.length() ?: 0
        val senderGof = body.optInt("gof", -1)
        TransportDiag.log(
            "GO fitness: self=$myGof sender=$senderGof → I host=${GoFitness.electReceiverAsGo(senderGof, myGof)}"
        )

        val channel = bleClient ?: return
        val s = ControlSession(channel, SessionCrypto(key, isSender = false))
        session = s
        s.onFrame = { type, payload -> handleFrame(type, payload) }
        s.onDead = { handleControlLost() }
        s.start()

        TransportDiag.log(
            "control: linked ✓ (senderCaps=0x%02x, %d tiers) — sender now drives".format(senderCaps, planSize)
        )
        recordSenderPeer()
    }

    private fun recordSenderPeer() {
        val idk = payload.senderIdentityKey ?: return
        val peerId = MessageDigest.getInstance("SHA-256").digest(idk)
            .joinToString("") { "%02x".format(it) }
        OffGridRepository.recordPeerSeen(appContext, peerId, payload.senderName)
    }

    // ------------------------------------------------------------- inbound

    private fun handleFrame(type: Int, body: JSONObject) {
        if (torndown) return
        when (type) {
            ControlProtocol.DATA_READY -> {
                exactIp = body.optString("ip").takeIf { it.isNotEmpty() }
                exactPort = body.optInt("port", -1).takeIf { it in 1..65535 }
                // We host the rendezvous (RECEIVER_AS_GO = our Wi-Fi Direct group, or SENDER_JOINS_AP
                // = our mobile hotspot); the sender just told us its joined-network IP. Connect OUT to
                // it — the file-sender is still the TCP server. P2pRoute binds our host-side address
                // and connects to the target (works for the p2p GO IP and the hotspot AP IP alike).
                val hostTier = currentTier
                if ((hostTier == TransportDescriptor.RECEIVER_AS_GO ||
                        hostTier == TransportDescriptor.SENDER_JOINS_AP) && !routeDelivered) {
                    val target = exactIp?.let { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
                    if (target != null) {
                        val local = goSelfHostIp?.let { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
                        TransportDiag.log("RX: as host, connecting out to sender $exactIp")
                        // Deliver the route and DIAL — but DON'T report CONNECTED yet. We report this tier
                        // ok only after the TCP handshake actually lands ([reportHandshakeSucceeded]).
                        // Reporting ok here, before the dial, falsely told the sender it was connected: it
                        // set dataConnected and stopped advancing while we were still dialing a possibly-dead
                        // IP (the MIUI role-flip's stale 192.168.43.1), desyncing the ladders so the LOHS
                        // fallback was never reached. On a dial failure [reportHandshakeFailed] now drives
                        // the advance instead.
                        deliverRoute(hostTier, P2pRoute(target, local))
                    }
                }
            }

            ControlProtocol.TRY_TRANSPORT -> {
                if (routeDelivered) return  // a route already won — ignore further tiers
                val tier = TransportDescriptor.fromId(body.optInt("t", -1)) ?: return
                tierOrdinal++
                currentTier = tier
                // Moving off RECEIVER_AS_GO → stop hosting (a stale group conflicts with the next tier).
                if (tier != TransportDescriptor.RECEIVER_AS_GO) {
                    groupManager?.teardown()
                    groupManager = null
                }
                TransportDiag.log("sender→ try ${tier.wireName} ($tierOrdinal/$planSize)")
                listener.onTierStarted(tier, tierOrdinal, planSize.coerceAtLeast(tierOrdinal))
                executeTier(tier, body)
            }

            ControlProtocol.ABORT -> {
                // Wi-Fi ladder exhausted. Bluetooth is no longer a data fallback, so this is terminal.
                val controlFailed = body.optString("reason") == LadderExhaustReason.CONTROL_FAILED.name
                TransportDiag.log("sender: ladder ABORT (Wi-Fi exhausted, controlFailed=$controlFailed)")
                listener.onAllFailed(controlFailed)
            }
        }
    }

    private fun executeTier(tier: TransportDescriptor, params: JSONObject) {
        when (tier) {
            TransportDescriptor.RECEIVER_AS_GO -> {
                // GO election: WE host the Wi-Fi Direct group; the sender joins as client and serves
                // TCP. Stop any lingering Wi-Fi joiner from a prior tier (it would conflict with
                // creating a group). On ready, hand the sender our creds via HOST_READY; the sender
                // joins and replies with DATA_READY(its client IP), which we connect out to (above).
                ladder.teardown()
                val mgr = P2pGroupManager(appContext).also {
                    groupManager?.teardown()
                    groupManager = it
                }
                TransportDiag.log("RX: creating group as elected GO…")
                mgr.createGroup(object : P2pGroupManager.Listener {
                    override fun onGroupReady(
                        ssid: String, passphrase: String, groupOwnerIp: String?,
                        goDeviceAddress: String?, frequencyMhz: Int?, usedBandPin: Boolean
                    ) {
                        if (torndown || routeDelivered) return
                        goSelfHostIp = groupOwnerIp
                        TransportDiag.log("RX: group up (ssid=$ssid) — HOST_READY sent, awaiting sender join")
                        session?.send(ControlProtocol.HOST_READY, JSONObject().apply {
                            put("ssid", ssid)
                            put("psk", passphrase)
                            groupOwnerIp?.let { put("goIp", it) }
                            goDeviceAddress?.let { put("goMac", it) }
                        })
                    }

                    override fun onGroupError(message: String, retryable: Boolean) {
                        if (torndown || routeDelivered) return
                        TransportDiag.log("RX: group create failed ($message)")
                        report(TransportDescriptor.RECEIVER_AS_GO, ok = false, FailureCode.GO_BUSY)
                    }

                    override fun onGroupLost() {
                        // The framework auto-recreates; if it ultimately can't, onGroupError fires.
                        if (torndown || routeDelivered) return
                        TransportDiag.log("RX: hosted group dropped (auto-recreating)")
                    }
                })
            }

            TransportDescriptor.SENDER_JOINS_AP -> {
                // SoftAP role flip: WE already host a mobile hotspot. Relay its saved creds so the
                // sender joins it (WifiNetworkSpecifier) and serves TCP; we then connect OUT to the
                // sender's joined-network IP (DATA_READY handler above). The app can't read a manual
                // hotspot's creds, so this only runs when the user saved them once.
                val creds = SecurityVault.getHotspotCredentials(appContext)
                val apIp = Net.detectHotspotIp(appContext)
                if (creds == null || apIp == null) {
                    TransportDiag.log("RX: SENDER_JOINS_AP unavailable (creds=${creds != null}, hotspotIp=$apIp)")
                    report(tier, ok = false, FailureCode.UNSUPPORTED)
                    return
                }
                goSelfHostIp = apIp
                TransportDiag.log("RX: sharing my hotspot creds (ssid=${creds.first}) — awaiting sender join")
                session?.send(ControlProtocol.HOST_READY, JSONObject().apply {
                    put("ssid", creds.first)
                    put("psk", creds.second)
                    put("nk", NetKind.SOFTAP.id)
                    put("goIp", apIp)
                })
            }

            else -> {
                val ssid = params.optString("ssid").ifEmpty { payload.ssid }
                val psk = params.optString("psk").ifEmpty { payload.psk }
                val mac = params.optString("mac").takeIf { it.isNotEmpty() } ?: payload.goDeviceAddress
                ladder.executeTier(tier, ssid, psk, mac, onLost = { listener.onRouteLost() }) { route, code ->
                    if (torndown) return@executeTier
                    if (route != null) {
                        report(tier, ok = true, FailureCode.NONE)
                        deliverRoute(tier, route)
                    } else {
                        report(tier, ok = false, code)
                    }
                }
            }
        }
    }

    private fun report(tier: TransportDescriptor, ok: Boolean, code: FailureCode) {
        TransportDiag.log("tier ${tier.wireName}: ${if (ok) "CONNECTED ✓" else "failed ($code)"}")
        // Self-GO learning: RECEIVER_AS_GO is THIS device hosting the group, so its join result is
        // a GO-reliability signal. Skip HANDSHAKE_FAILED — that's a post-join data-plane issue, not
        // a GO-hosting failure — so we record exactly one outcome per hosting attempt.
        if (tier == TransportDescriptor.RECEIVER_AS_GO && code != FailureCode.HANDSHAKE_FAILED) {
            OffGridRepository.recordSelfGoOutcome(appContext, ok)
        }
        session?.send(
            ControlProtocol.TRANSPORT_RESULT,
            JSONObject().put("t", tier.id).put("ok", ok).put("code", code.id)
        )
    }

    private fun deliverRoute(tier: TransportDescriptor, route: TransportRoute) {
        if (routeDelivered) return
        routeDelivered = true
        TransportDiag.log("ROUTE READY via ${tier.wireName} → transfer handshake")
        listener.onRouteReady(tier, route, exactIp, exactPort)
    }

    /**
     * The TCP handshake on a delivered route failed terminally (route up,
     * sender unreachable — e.g. the sender lost the T2 GO negotiation, §9).
     * Reports the failure so the sender advances the ladder.
     */
    fun reportHandshakeFailed() {
        val tier = currentTier ?: return
        routeDelivered = false
        report(tier, ok = false, FailureCode.HANDSHAKE_FAILED)
    }

    /**
     * The TCP handshake on a delivered route SUCCEEDED (data now streaming). Only the host tiers
     * (RECEIVER_AS_GO / SENDER_JOINS_AP) defer their success report to here — the join tiers already
     * reported ok at route-up, so reporting again would be redundant. This is the other half of the
     * [reportHandshakeFailed] pair: together they make the host-tier ok/fail verdict reflect the real
     * TCP outcome instead of the mere arrival of DATA_READY (which desynced the ladder).
     */
    fun reportHandshakeSucceeded() {
        val tier = currentTier ?: return
        if (tier == TransportDescriptor.RECEIVER_AS_GO || tier == TransportDescriptor.SENDER_JOINS_AP) {
            report(tier, ok = true, FailureCode.NONE)
        }
    }

    /**
     * The TCP connected but the sender REJECTED the app handshake (BUSY / token EXPIRED) — not a
     * transport failure, but the sender must still be told. Only the host tiers need this: their
     * success report is deferred to [reportHandshakeSucceeded], so without a failure report here the
     * sender would wait for a TRANSPORT_RESULT that never comes (freed only by the BLE keepalive ~15 s
     * later). Join tiers already reported ok at route-up, so they stay silent and keep today's behavior
     * (the user retries from the error card).
     */
    fun reportHostHandshakeRejected() {
        val tier = currentTier ?: return
        if (tier == TransportDescriptor.RECEIVER_AS_GO || tier == TransportDescriptor.SENDER_JOINS_AP) {
            reportHandshakeFailed()
        }
    }

    private fun handleControlLost() {
        if (torndown) return
        session?.close()
        session = null
        // §9 #11: data plane independent; degrade only when no route is up yet.
        if (!routeDelivered) {
            degradeToLocalLadder()
        }
    }

    // -------------------------------------------------------- degraded mode

    /**
     * Drop to the Wi-Fi local ladder when the BLE control channel can't be used. Bluetooth is no
     * longer a data fallback, so this is Wi-Fi-only (autonomous P2P/specifier tiers).
     */
    private fun degradeToLocalLadder() {
        if (torndown) return
        // Drop any pending HELLO retransmit/deadline: the control channel is being abandoned, so a
        // later retry would only log a re-send against a client we are about to close.
        mainHandler.removeCallbacksAndMessages(helloTimeoutToken)
        // We fell back to a Wi-Fi-only ladder because the BLE control channel couldn't be used; if
        // that ladder then exhausts, the control failure is the likely root cause to surface.
        controlDegraded = true
        bleClient?.close()
        bleClient = null
        startLocalLadder()
    }

    private fun startLocalLadder() {
        if (torndown || localLadderStarted) return
        localLadderStarted = true
        // controlDegraded is passed through so the ladder can drop the tiers that only work by
        // relaying credentials over the control channel that just died, instead of attempting them.
        ladder.start(hasNearbyPermission, preferredOrder = localPreferredOrder(),
            controlDegraded = controlDegraded,
            listener = object : TransportLadder.Listener {
                override fun onTierStarted(tier: TransportDescriptor, ordinal: Int, total: Int) {
                    currentTier = tier
                    TransportDiag.log("Wi-Fi tier $ordinal/$total: ${tier.wireName}…")
                    listener.onTierStarted(tier, ordinal, total)
                }

                override fun onTierFailed(tier: TransportDescriptor, code: FailureCode) {
                    // Ladder auto-advances; UI already shows the next tier via onTierStarted.
                    TransportDiag.log("Wi-Fi tier ${tier.wireName} FAILED ($code)")
                }

                override fun onConnected(tier: TransportDescriptor, route: TransportRoute) {
                    TransportDiag.log("Wi-Fi tier ${tier.wireName} CONNECTED ✓")
                    deliverRoute(tier, route)
                }

                override fun onAllTiersFailed() {
                    if (routeDelivered) return
                    TransportDiag.log("all Wi-Fi tiers exhausted (controlDegraded=$controlDegraded)")
                    listener.onAllFailed(controlDegraded)
                }

                override fun onRouteLost() {
                    listener.onRouteLost()
                }
            })
    }

    private fun localPreferredOrder(): List<Int>? {
        val idk = payload.senderIdentityKey ?: return null
        val peerId = MessageDigest.getInstance("SHA-256").digest(idk)
            .joinToString("") { "%02x".format(it) }
        return OffGridRepository.preferredTransportOrder(appContext, peerId)
    }

    // -------------------------------------------------------------- helpers

    private fun localCaps(): Int {
        // Bluetooth is no longer a data path — advertise only the BLE-scan capability. (Don't OR in
        // BT_L2CAP: a legacy sender that still saw that bit would wait for a BT data link we never open.)
        var cap = 0
        if (BleControlClient.canScan(appContext)) cap = cap or TransportCaps.BLE_SCAN
        return cap
    }

    /**
     * Own P2P device address for the sender's T2 dial. requestDeviceInfo is
     * API 29+ (= minSdk); the placeholder/zero MAC the OS returns without the
     * nearby/location grant is dropped (§9 #9). One-shot, 1.5 s cap.
     */
    @SuppressLint("MissingPermission")
    private fun fetchOwnP2pMac(done: (String?) -> Unit) {
        if (!hasNearbyPermission) { done(null); return }
        val mgr = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) { done(null); return }
        var finished = false
        val finishOnce: (String?) -> Unit = { mac ->
            if (!finished) { finished = true; done(mac) }
        }
        try {
            val ch = mgr.initialize(appContext, Looper.getMainLooper(), null)
            mainHandler.postDelayed({ finishOnce(null) }, 1_500L)
            mgr.requestDeviceInfo(ch) { device ->
                val mac = device?.deviceAddress?.lowercase()
                val usable = mac != null && mac != "02:00:00:00:00:00" && mac != "00:00:00:00:00:00"
                finishOnce(if (usable) mac else null)
            }
        } catch (_: Exception) {
            finishOnce(null)
        }
    }

    fun teardown() {
        torndown = true
        mainHandler.removeCallbacksAndMessages(helloTimeoutToken)
        session?.close()
        session = null
        bleClient?.close()
        bleClient = null
        groupManager?.teardown()
        groupManager = null
        ladder.teardown()
    }
}

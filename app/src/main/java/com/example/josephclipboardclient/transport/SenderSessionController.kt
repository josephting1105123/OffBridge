package com.EntropicSoftwareLab.ClipboardShare.transport

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.EntropicSoftwareLab.ClipboardShare.EcdhSession
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import com.EntropicSoftwareLab.ClipboardShare.data.db.OffGridRepository
import com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridPayload
import com.EntropicSoftwareLab.ClipboardShare.transport.control.BleControlServer
import com.EntropicSoftwareLab.ClipboardShare.transport.control.ControlProtocol
import com.EntropicSoftwareLab.ClipboardShare.transport.control.ControlSession
import com.EntropicSoftwareLab.ClipboardShare.transport.hotspot.LocalOnlyHotspotHost
import com.EntropicSoftwareLab.ClipboardShare.transport.hotspot.SpecifierJoiner
import com.EntropicSoftwareLab.ClipboardShare.transport.wifidirect.P2pCredentialJoiner
import com.EntropicSoftwareLab.ClipboardShare.transport.wifidirect.P2pNegotiatedConnector
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Why the ladder gave up — drives the actionable guidance the sender screen shows when a hotspot is
 * on but no Wi-Fi bridge could be formed (Bluetooth is no longer a fallback).
 */
enum class LadderExhaustReason {
    /** Generic: no hotspot involved, Wi-Fi Direct / LAN simply didn't connect. */
    GENERIC,
    /** This device hosts a hotspot but hasn't saved its password, so the receiver can't auto-join. */
    SAVE_HOTSPOT_CREDS,
    /** A hotspot is on that can't be bridged (e.g. the receiver hosts one, or both do without
     *  STA+AP concurrency) — turning one off lets Wi-Fi Direct take over. */
    TURN_OFF_HOTSPOT,
    /** No hotspot in play, but the BLE control channel also failed this session — the likely cause
     *  is the Bluetooth coordination link (keep both phones unlocked and close, then retry). */
    CONTROL_FAILED,
}

/**
 * Sender-side session coordinator (§3 of the V4 plan). The sender owns the
 * radios being advertised, so it drives the ladder: it sends TRY_TRANSPORT,
 * the receiver executes and reports TRANSPORT_RESULT. This kills the
 * split-brain failure mode where both sides fall back at different times.
 *
 * Lifecycle: one controller per advertising window (fresh token), created by
 * QrDisplayFragment.enterAdvertising. Without a BLE-capable pair the
 * controller stays dormant and the session behaves exactly like pre-V4
 * (QR-credentials-only).
 */
class SenderSessionController(
    context: Context,
    private val token: ByteArray,
    private val ecdh: EcdhSession,
    private val groupSsid: String,
    private val groupPsk: String,
    private val groupOwnerIp: String?,
    private val goMac: String?,
    private val listener: Listener,
    /**
     * What [groupSsid]/[groupPsk] describe: a Wi-Fi Direct GO ([NetKind.P2P_GROUP], the default) or
     * this device's mobile hotspot ([NetKind.SOFTAP]). A SoftAP must be joined via WifiNetworkSpecifier
     * (SOFTAP_JOIN), never P2P_CRED_JOIN — that was the bug where hotspot creds drove a WifiP2pConfig join.
     */
    private val netKind: NetKind = NetKind.P2P_GROUP
) {

    interface Listener {
        /** Control channel authenticated; [receiverName] is display-only, untrusted. */
        fun onControlLinked(receiverName: String)
        /** A new tier is being coordinated — UI shows "trying method k/n". */
        fun onTierChanged(tier: TransportDescriptor, ordinal: Int, total: Int)
        /**
         * Tier T2/T3/T6 require the autonomous GO to be gone. Called on the main thread, once.
         * The implementer MUST invoke [onDone] when the teardown has settled (removeGroup resolved),
         * so the caller can re-use the radio (e.g. join another P2P group) without a dual-GO conflict.
         */
        fun onRequireGroupTeardown(onDone: () -> Unit)
        /** Every coordinated tier failed; the receiver was told to give up. [reason] lets the UI
         *  offer an actionable next step (save hotspot creds / turn a hotspot off) when a hotspot
         *  is on but unbridgeable, instead of a generic failure. */
        fun onLadderExhausted(reason: LadderExhaustReason)
        /** The control link died before a data route was up. Fragment resets to plain advertising. */
        fun onControlLost()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bleServer: BleControlServer? = null
    private var session: ControlSession? = null
    private var negotiated: P2pNegotiatedConnector? = null
    private var lohs: LocalOnlyHotspotHost? = null
    private var credentialJoiner: P2pCredentialJoiner? = null  // RECEIVER_AS_GO: we join the receiver's group
    private var specifierJoiner: SpecifierJoiner? = null       // SENDER_JOINS_AP: we join the receiver's hotspot

    private var plan: List<TransportDescriptor> = emptyList()
    private var planIndex = -1
    private var currentAttempt: TransportAttemptLog.Attempt? = null
    private var receiverMac: String? = null
    private var receiverPeerId: String? = null
    private var receiverGof: Int = -1
    private var groupTornDown = false

    /** This device's rendezvous capabilities (hotspot/STA+AP/creds), advertised in HELLO_ACK. */
    private val myCaps: DeviceCaps by lazy { Capabilities.compute(appContext) }
    /** The receiver's capabilities, learned from HELLO_RX (legacy peers → conservative default). */
    private var receiverDeviceCaps: DeviceCaps = DeviceCaps.legacyDefault()

    /** This device's GO-fitness — computed once, advertised in HELLO_ACK and used for the election. */
    private val senderGof: Int by lazy { GoFitness.score(appContext) }

    @Volatile private var dataConnected = false
    @Volatile private var torndown = false

    /** Fires if a BLE central connects but never completes HELLO — records the control failure. */
    private val helloWatchdogToken = Any()

    /** Why an empty/exhausted plan happened, for actionable UI guidance. Set by [buildPlan]. */
    private var exhaustReason = LadderExhaustReason.GENERIC

    /**
     * True once the BLE control channel demonstrably failed this session (advertising never started,
     * or a central connected but HELLO never completed). When the Wi-Fi ladder then exhausts with a
     * generic reason, this lets the UI name the Bluetooth coordination link as the likely cause
     * instead of showing the blank "all methods failed" message.
     */
    @Volatile private var controlFailed = false

    private val ladderId: String =
        MessageDigest.getInstance("SHA-256").digest(token)
            .joinToString("") { "%02x".format(it) }.substring(0, 10)

    companion object {
        private const val TAG = "OffGridCtl"
        /** A GATT central that connects but doesn't finish HELLO within this is treated as control-failed. */
        private const val HELLO_WATCHDOG_MS = 8_000L
    }

    /** Capability bitmap advertised in the QR `cap` field (§5). */
    fun capabilityBits(): Int {
        var cap = 0
        if (BleControlServer.canAdvertise(appContext)) cap = cap or TransportCaps.BLE_ADVERTISE
        // Bluetooth is no longer a DATA path (too slow at any PHY) — never advertise BT_L2CAP.
        cap = cap or TransportCaps.LOHS // declared; runtime denial is a ladder outcome, not a cap
        cap = cap or TransportCaps.COMPLETION_ACK // build-level: this generation waits for the delivery ack
        return cap
    }

    fun start() {
        torndown = false
        // Mark THIS ladder as the active off-grid session so the diagnostics screen scopes to it (and
        // doesn't surface a stale prior session's rows when this one's control channel never forms).
        OffGridRepository.markActiveLadder(appContext, ladderId)
        val canAdvertise = BleControlServer.canAdvertise(appContext)
        TransportDiag.log("TX: control start — BLE advertise=$canAdvertise")
        if (!canAdvertise) {
            // No BLE advertising → the receiver cannot discover us over BLE, so there is no control
            // channel. Only same-network LAN / Wi-Fi Direct (autonomous) can work.
            TransportDiag.log("TX: BLE advertise UNAVAILABLE — receiver can't discover us (no control channel)")
            return
        }
        val server = BleControlServer(appContext)
        bleServer = server
        server.onMessage = { raw -> mainHandler.post { handlePreSessionFrame(raw) } }
        server.onClosed = { mainHandler.post { handleControlLost() } }
        server.onClientConnected = { mainHandler.post { onBleClientConnected() } }
        if (!server.start(ControlProtocol.sessionServiceUuid(token))) {
            controlFailed = true
            TransportDiag.log("TX: BLE advertise FAILED to start — no control channel/BT")
            server.close()
            bleServer = null
        } else {
            TransportDiag.log("TX: BLE advertising ✓ — waiting for receiver to connect")
        }
    }

    /**
     * A GATT central connected. The control handshake (HELLO_RX → HELLO_ACK) should complete within a
     * second; if it doesn't, the BLE control channel failed (a common MIUI/HONOR GATT write/notify
     * issue) and we record that for this session's trace. We do NOT tear the group/advertising down: a
     * receiver that degraded can still join our live group via P2P_CRED_JOIN with no control channel.
     */
    private fun onBleClientConnected() {
        if (torndown || session != null) return
        TransportDiag.log("TX: receiver connected over BLE — awaiting HELLO")
        mainHandler.removeCallbacksAndMessages(helloWatchdogToken)
        mainHandler.postDelayed({
            if (!torndown && session == null) {
                controlFailed = true
                TransportDiag.log("TX: receiver linked over BLE but HELLO never completed — control failed")
            }
        }, helloWatchdogToken, HELLO_WATCHDOG_MS)
    }

    // ----------------------------------------------------------- HELLO phase

    private fun handlePreSessionFrame(raw: ByteArray) {
        if (torndown || session != null) return
        val frame = ControlProtocol.parse(raw) ?: return
        if (frame.type != ControlProtocol.HELLO_RX) return
        val body = try { JSONObject(String(frame.body, Charsets.UTF_8)) } catch (_: Exception) { return }

        val receiverPub = try {
            Base64.decode(body.optString("pk"), Base64.NO_WRAP)
        } catch (_: Exception) { return }
        val helloHmac = try {
            Base64.decode(body.optString("hm"), Base64.NO_WRAP)
        } catch (_: Exception) { return }

        val sharedKey = try { ecdh.deriveSecrets(receiverPub).first } catch (_: Exception) { return }
        val expected = SessionCrypto.hmacSha256(sharedKey, token)
        if (!MessageDigest.isEqual(helloHmac, expected)) return
        // Valid HELLO arrived — the control channel is forming, so cancel the no-HELLO watchdog.
        mainHandler.removeCallbacksAndMessages(helloWatchdogToken)

        val receiverName = body.optString("name").take(32).ifEmpty { "Unknown Device" }
        val receiverCaps = body.optInt("cap", 0)
        val receiverPerm = body.optBoolean("perm", false)
        receiverMac = body.optString("mac").takeIf { it.isNotEmpty() }
        receiverGof = body.optInt("gof", -1)
        receiverPeerId = body.optString("idk").takeIf { it.isNotEmpty() }?.let { idkB64 ->
            try {
                val der = Base64.decode(idkB64, Base64.NO_WRAP)
                MessageDigest.getInstance("SHA-256").digest(der)
                    .joinToString("") { "%02x".format(it) }
            } catch (_: Exception) { null }
        }
        receiverPeerId?.let { OffGridRepository.recordPeerSeen(appContext, it, receiverName) }

        // Receiver rendezvous capabilities. A legacy peer omits all of these; optBoolean defaults
        // every flag to false, which yields the conservative DeviceCaps (canJoin=true, canHost=false)
        // — so parse unconditionally rather than gating on one field's presence.
        receiverDeviceCaps = DeviceCaps.fromPeer(
            hotspotActive = body.optBoolean("hsa", false),
            staApConcurrency = body.optBoolean("sac", false),
            hasCreds = body.optBoolean("hcr", false),
            staConnected = body.optBoolean("sta", false)
        )

        plan = buildPlan(receiverPerm)
        TransportDiag.log(
            "TX: receiver '$receiverName' linked (perm=$receiverPerm cap=0x${Integer.toHexString(receiverCaps)}" +
                " gof self=$senderGof peer=$receiverGof; me[hs=${myCaps.hotspotActive} sta=${myCaps.staConnected} join=${myCaps.canJoinAp} host=${myCaps.canHostAp}]" +
                " rx[hs=${receiverDeviceCaps.hotspotActive} sta=${receiverDeviceCaps.staConnected} join=${receiverDeviceCaps.canJoinAp} host=${receiverDeviceCaps.canHostAp}])" +
                " — plan=${plan.joinToString(",") { it.wireName }}"
        )

        // HELLO_ACK travels plaintext (it completes the bootstrap), then the session seals.
        val ack = SessionCrypto.hmacSha256(sharedKey, token + "ack".toByteArray(Charsets.UTF_8))
        val ackBody = JSONObject().apply {
            put("ack", Base64.encodeToString(ack, Base64.NO_WRAP))
            put("cap", capabilityBits())
            put("gof", senderGof)
            put("plan", JSONArray(plan.map { it.id }))
            // Rendezvous capabilities (so a future receiver-driven decision can see them too).
            put("cja", myCaps.canJoinAp)
            put("cha", myCaps.canHostAp)
            put("hsa", myCaps.hotspotActive)
            put("sac", myCaps.staApConcurrency)
            put("hcr", myCaps.hasCreds)
            put("sta", myCaps.staConnected)
        }
        val channel = bleServer ?: return
        channel.send(ControlProtocol.plainFrame(ControlProtocol.HELLO_ACK, ackBody))

        val s = ControlSession(channel, SessionCrypto(sharedKey, isSender = true))
        session = s
        s.onFrame = { type, payload -> handleFrame(type, payload) }
        s.onDead = { handleControlLost() }
        s.start()

        listener.onControlLinked(receiverName)

        // The GO is already up: exact-IP delivery kills the candidate guessing (§9 #8).
        sendDataReady(groupOwnerIp ?: OffGridPayload.GROUP_OWNER_IP)
        advance(0)
    }

    /** Capability-driven tier sequence — the hotspot-first decision tree (plan v2). The sender always
     *  serves the TCP transfer, so a hotspot is JOINED only when the SENDER owns it (sender serves at
     *  the gateway, receiver dials in = SOFTAP_JOIN); otherwise the non-hotspot/free device hosts a
     *  Wi-Fi Direct group ("whoever is free hosts"; the [DeviceCaps.staConnected] flag only breaks the
     *  both-hotspot tie — with no hotspot, the sender just hosts). Unlike before, the Wi-Fi Direct
     *  tiers + LOHS stay as the fallback even when a hotspot is up on the OTHER phone, so no case
     *  dead-ends on guidance. The per-peer learner then reorders by history. */
    private fun buildPlan(receiverPerm: Boolean): List<TransportDescriptor> {
        val haveGroupCreds = groupSsid.isNotEmpty() && groupPsk.isNotEmpty()
        val wifiTiers = mutableListOf<TransportDescriptor>()

        // AUTHORITATIVE "we are bridging over our own hotspot" signal: the fragment set netKind=SOFTAP
        // when it advertised saved hotspot creds. Do NOT gate on the live detectHotspotIp()
        // (myCaps.hotspotActive) — on real OEMs it intermittently reports "off" while a hotspot is up
        // (the device traces showed me[hs=false] yet Wi-Fi Direct failed "hotspot busy"), which made
        // buildPlan fall through to the Wi-Fi Direct tiers that can't run on the busy single radio.
        val weBridgeSoftAp = netKind == NetKind.SOFTAP

        // Hotspot-first decision tree (whoever is free hosts; sta only breaks the both-hotspot tie).
        // The SENDER always serves the TCP transfer, so a hotspot can be JOINED only when the sender
        // owns it (sender serves at the gateway, receiver dials in = SOFTAP_JOIN); a receiver-owned
        // hotspot is never joinable — the joiner-serves direction (SENDER_JOINS_AP/T8) is unsound:
        // Network.bindSocket is outbound-only, so the joiner can't accept the inbound TCP. Everything
        // else is "the non-hotspot device hosts a Wi-Fi Direct group", which still works with a hotspot
        // up on the OTHER phone — so, unlike before, we DON'T suppress the Wi-Fi Direct tiers when a
        // hotspot is in play; they (+ LOHS) are the required fallback so no case dead-ends.
        // netKind is the AUTHORITATIVE hotspot signal. The fragment sets SOFTAP only when bridging a
        // real saved-creds hotspot, and it refuses to create a Wi-Fi Direct group while a hotspot is up
        // — so in P2P_GROUP mode there is no real hotspot. A live detectHotspotIp() here
        // (myCaps.hotspotActive) is then our OWN Wi-Fi Direct GO interface misread as a SoftAP (some
        // MIUI GOs land on a non-192.168.49.x subnet). Trusting it would wrongly set senderHasHotspot
        // and flip the host election (the spurious RECEIVER_AS_GO lead) — so gate on netKind alone.
        val senderHasHotspot = weBridgeSoftAp
        val receiverHasHotspot = receiverDeviceCaps.hotspotActive
        val senderFree = !myCaps.staConnected
        val receiverFree = !receiverDeviceCaps.staConnected
        val receiverCanHost = receiverGof >= 0
        var receiverLeads = false

        // (1) JOIN the sender's hotspot — sound only when the sender owns the AP (with creds) AND the
        // receiver's STA is free to join (not already pinned to another Wi-Fi without STA+STA
        // concurrency). When the receiver is stuck on another network we skip this and let the Wi-Fi
        // Direct fallback below carry the transfer, instead of leading with a doomed join.
        if (weBridgeSoftAp && haveGroupCreds &&
            receiverDeviceCaps.canJoinAp && receiverFree
        ) {
            wifiTiers.add(TransportDescriptor.SOFTAP_JOIN)
        }
        // (2) Wi-Fi Direct host election — the non-hotspot/free device is the GO. Leading with
        // P2P_CRED_JOIN reuses our already-live autonomous group (its creds are in the QR); leading
        // with RECEIVER_AS_GO tears that group down first, so we only do it when the receiver should
        // host (sender busy with a hotspot, or the both-hotspot sta tiebreak picks the receiver). The
        // per-peer learner below can still reorder these by history (e.g. the HONOR-tablet case).
        if (receiverPerm && haveGroupCreds) {
            receiverLeads = GoFitness.electHost(
                senderHasHotspot = senderHasHotspot,
                receiverHasHotspot = receiverHasHotspot,
                senderFree = senderFree,
                receiverFree = receiverFree,
                receiverCanHost = receiverCanHost
            )
            if (receiverLeads) {
                // Receiver should host (sender busy with a hotspot, or the both-hotspot sta tiebreak picked
                // the receiver) → role-flip leads, then our group; LOHS stays the final fallback.
                wifiTiers.add(TransportDescriptor.RECEIVER_AS_GO)
                wifiTiers.add(TransportDescriptor.P2P_CRED_JOIN)
                if (receiverMac != null) wifiTiers.add(TransportDescriptor.P2P_NEGOTIATED)
                wifiTiers.add(TransportDescriptor.LOHS)
            } else {
                // Sender hosts → our already-live group first ("P2P instantly"), then the RELIABLE SoftAP
                // (LOHS) BEFORE the fragile role-flip. When Wi-Fi Direct group-join is broken in BOTH
                // directions (the MIUI↔MIUI wall) a real SoftAP is the only Wi-Fi transport that connects,
                // so we reach it after ONE failed Wi-Fi Direct attempt instead of burning a second doomed
                // one (RECEIVER_AS_GO). The role-flip stays as the last-resort for asymmetric pairs (a
                // sender that's a bad GO while the receiver hosts fine); the per-peer learner reorders by
                // history. P2P_CRED_JOIN stays FIRST so we never tear our live group down before reusing it.
                wifiTiers.add(TransportDescriptor.P2P_CRED_JOIN)
                wifiTiers.add(TransportDescriptor.LOHS)
                if (receiverCanHost) wifiTiers.add(TransportDescriptor.RECEIVER_AS_GO)
                if (receiverMac != null) wifiTiers.add(TransportDescriptor.P2P_NEGOTIATED)
            }
        } else if (haveGroupCreds) {
            // No nearby permission (degraded) → the specifier-based SoftAP paths still work without
            // NEARBY_WIFI_DEVICES: SOFTAP_JOIN (added above if the sender hosts a hotspot) and the LOHS
            // auto-hotspot fallback.
            wifiTiers.add(TransportDescriptor.LOHS)
        }
        // Phase 3 adaptive ordering reorders the Wi-Fi tiers by per-peer success — but it must NEVER
        // promote Bluetooth. BT is the slow, radio-independent LAST resort (§3.2 T4): always tried
        // after every Wi-Fi route, only once they've all failed. (Letting the learner rank BT first
        // meant fast Wi-Fi Direct never even got a turn — the "why is it using Bluetooth?" bug.)
        val stats = receiverPeerId?.let { OffGridRepository.preferredTransportOrder(appContext, it) }
        if (!stats.isNullOrEmpty()) {
            val defaultOrder = wifiTiers.toList()
            wifiTiers.sortBy { t ->
                val i = stats.indexOf(t.id)
                if (i >= 0) i else stats.size + defaultOrder.indexOf(t)
            }
        }
        // Topology guard on the learner: it ranks by past per-peer success but is blind to THIS
        // session's network state. It must never promote the role-flip (RECEIVER_AS_GO) ahead of
        // P2P_CRED_JOIN when the election chose the SENDER to host — leading RECEIVER_AS_GO there asks
        // the receiver to host a group it shouldn't, and if the control channel then fails the receiver
        // degrades to P2P_CRED_JOIN (join OUR live group), producing the sender-hosts-vs-receiver-joins
        // desync (the 30s CANCELLED trace). Re-pin P2P_CRED_JOIN ahead of the role-flip; the learner's
        // ordering of every other tier is preserved.
        if (!receiverLeads) {
            val pj = wifiTiers.indexOf(TransportDescriptor.P2P_CRED_JOIN)
            val rg = wifiTiers.indexOf(TransportDescriptor.RECEIVER_AS_GO)
            if (rg in 0 until pj) {
                wifiTiers.add(rg, wifiTiers.removeAt(pj))
            }
        }
        // Bluetooth is retired as a data path (too slow at any PHY) — never appended. exhaustReason is
        // surfaced only AFTER the whole ladder has failed, so the hotspot/creds guidance below is set
        // even when a (fragile) plan WAS built — with the unified tree LOHS is almost always present so
        // wifiTiers is rarely empty, yet a live hotspot on either side is still the usual reason the
        // single radio couldn't be shared, and "turn one off" remains the actionable fix. (Order matters:
        // the specific hints must precede the GENERIC fallthrough, NOT a wifiTiers.isNotEmpty() short-circuit.)
        exhaustReason = when {
            // Our hotspot is up but the receiver can't join it — it likely hosts its own hotspot without
            // STA+AP concurrency → ask to turn one off so Wi-Fi Direct (or a single hotspot) can work.
            // Gate on netKind (weBridgeSoftAp), NOT the auto-detected myCaps.hotspotActive, which in
            // P2P_GROUP mode is our own GO interface misread as a SoftAP.
            weBridgeSoftAp && !receiverDeviceCaps.canJoinAp ->
                LadderExhaustReason.TURN_OFF_HOTSPOT
            // The receiver hosts a hotspot we couldn't bridge (both-hotspot, or receiver-as-AP serving is
            // unsupported) → turning it off lets Wi-Fi Direct take over.
            receiverDeviceCaps.hotspotActive -> LadderExhaustReason.TURN_OFF_HOTSPOT
            // We have a hotspot/shared network but no saved creds (LAN-only mode: empty group creds and
            // not SoftAP-advertised) and the receiver could join → guide the user to save creds so the
            // SOFTAP_JOIN auto-join becomes possible next time.
            !weBridgeSoftAp && !haveGroupCreds && receiverDeviceCaps.canJoinAp ->
                LadderExhaustReason.SAVE_HOTSPOT_CREDS
            else -> LadderExhaustReason.GENERIC
        }
        return wifiTiers.toList()
    }

    // ------------------------------------------------------------ the ladder

    private fun advance(index: Int) {
        if (torndown || dataConnected) return
        currentAttempt?.let {
            // Close a dangling attempt as failed before moving on.
            if (it.outcome == AttemptOutcome.PENDING) {
                TransportAttemptLog.end(appContext, it, AttemptOutcome.FAILED, FailureCode.CONNECT_TIMEOUT)
            }
        }
        if (index >= plan.size) {
            // controlFailed is only known after the ladder has actually run, so refine the generic
            // reason here (not in buildPlan): a generic Wi-Fi exhaustion that was preceded by a BLE
            // control failure is reported as CONTROL_FAILED so the UI can name the real cause.
            val finalReason =
                if (exhaustReason == LadderExhaustReason.GENERIC && controlFailed)
                    LadderExhaustReason.CONTROL_FAILED
                else exhaustReason
            TransportDiag.log("TX: ladder exhausted (reason=$finalReason) — told receiver to give up")
            session?.send(ControlProtocol.ABORT, JSONObject().put("reason", finalReason.name))
            listener.onLadderExhausted(finalReason)
            return
        }
        planIndex = index
        val tier = plan[index]
        TransportDiag.log("TX: tier ${index + 1}/${plan.size} → ${tier.wireName}")
        listener.onTierChanged(tier, index + 1, plan.size)
        currentAttempt = TransportAttemptLog.begin(appContext, ladderId, tier)

        when (tier) {
            TransportDescriptor.P2P_CRED_JOIN, TransportDescriptor.SPECIFIER -> {
                if (groupTornDown) {
                    // The autonomous GO no longer exists (we are past T2) — skip.
                    skipCurrent()
                    return
                }
                sendTry(tier, JSONObject().put("ssid", groupSsid).put("psk", groupPsk))
            }

            TransportDescriptor.P2P_NEGOTIATED -> {
                val rxMac = receiverMac
                if (rxMac == null) { skipCurrent(); return }
                teardownGroupOnce()
                // Receiver starts its half first, then we dial: simultaneous
                // connect() intents suppress both consent dialogs.
                sendTry(tier, JSONObject().apply { goMac?.let { put("mac", it) } })
                val connector = P2pNegotiatedConnector(appContext).also {
                    negotiated?.teardown()
                    negotiated = it
                }
                connector.connect(rxMac, goIntent = 15, budgetMs = tier.budgetMs,
                    listener = object : P2pNegotiatedConnector.Listener {
                        override fun onGroupFormed(
                            isGroupOwner: Boolean,
                            groupOwnerAddress: InetAddress?,
                            localP2pAddress: InetAddress?
                        ) {
                            if (torndown) return
                            if (isGroupOwner) {
                                localP2pAddress?.hostAddress?.let { sendDataReady(it) }
                            }
                            // A sender that lost the GO negotiation cannot serve TCP;
                            // the receiver's handshake failure report advances the ladder.
                        }

                        override fun onFailed() {
                            // Wait for the receiver's TRANSPORT_RESULT — it owns the verdict.
                        }
                    })
            }

            TransportDescriptor.LOHS -> {
                teardownGroupOnce()
                negotiated?.teardown()
                val host = LocalOnlyHotspotHost(appContext).also {
                    lohs?.stop()
                    lohs = it
                }
                host.start(tier.budgetMs, object : LocalOnlyHotspotHost.Listener {
                    override fun onStarted(ssid: String, psk: String, hostIp: String) {
                        if (torndown) return
                        sendDataReady(hostIp)
                        sendTry(tier, JSONObject().put("ssid", ssid).put("psk", psk))
                    }

                    override fun onFailed(code: FailureCode) {
                        if (torndown) return
                        Log.i(TAG, "LOHS denied: $code")
                        TransportDiag.log("TX: LOHS denied ($code) — likely hotspot/tethering active or no STA+AP")
                        currentAttempt?.let {
                            TransportAttemptLog.end(appContext, it, AttemptOutcome.FAILED, code)
                            currentAttempt = null
                        }
                        advance(planIndex + 1)
                    }
                })
            }

            TransportDescriptor.RECEIVER_AS_GO -> {
                // Role flip (GO election fallback): ask the receiver to host the group and wait for its
                // HOST_READY creds (handled below). We DON'T tear our own autonomous GO down yet — only
                // once the receiver confirms its group is up (in the HOST_READY handler, right before we
                // join). That way a receiver that can't host (onGroupError) leaves our group intact for
                // the remaining tiers, instead of the old "teardown-then-fail" that disabled P2P_CRED_JOIN.
                negotiated?.teardown()
                TransportDiag.log("TX: RECEIVER_AS_GO — asking receiver to host the group")
                sendTry(tier, JSONObject())
            }

            TransportDescriptor.SOFTAP_JOIN -> {
                // We are hosting our own mobile hotspot (creds embedded). The receiver joins it via
                // WifiNetworkSpecifier; we already serve TCP on the hotspot IP (DATA_READY sent at
                // link time). No P2P group to tear down — the OS owns the SoftAP.
                sendTry(tier, JSONObject()
                    .put("ssid", groupSsid)
                    .put("psk", groupPsk)
                    .put("nk", NetKind.SOFTAP.id))
            }

            TransportDescriptor.SENDER_JOINS_AP -> {
                // SoftAP role flip: the receiver hosts its mobile hotspot and relays its creds via
                // HOST_READY{nk=SOFTAP}; we then join via WifiNetworkSpecifier and serve TCP on our
                // STA-side IP (handled in the HOST_READY branch below).
                TransportDiag.log("TX: SENDER_JOINS_AP — asking receiver to share its hotspot creds")
                sendTry(tier, JSONObject())
            }

            // Retired/unsupported in any plan — defensive skip if they ever appear.
            TransportDescriptor.AWARE, TransportDescriptor.BT_L2CAP -> skipCurrent()
        }
    }

    private fun skipCurrent() {
        currentAttempt?.let {
            TransportAttemptLog.end(appContext, it, AttemptOutcome.SKIPPED, FailureCode.UNSUPPORTED)
            currentAttempt = null
        }
        advance(planIndex + 1)
    }

    private fun sendTry(tier: TransportDescriptor, params: JSONObject) {
        session?.send(ControlProtocol.TRY_TRANSPORT, params.put("t", tier.id))
    }

    private fun sendDataReady(ip: String) {
        session?.send(
            ControlProtocol.DATA_READY,
            JSONObject().put("ip", ip).put("port", OffGridPayload.TRANSFER_PORT)
        )
    }

    /** Our live IPv4 on the p2p (Wi-Fi Direct client) interface, or null until DHCP assigns one. */
    private fun findLocalP2pIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.name?.startsWith("p2p") == true && it.isUp }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    /** Our IPv4 on a joined [android.net.Network] (the hotspot we joined via WifiNetworkSpecifier). */
    private fun localIpv4OnNetwork(network: android.net.Network): String? = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------- inbound

    private fun handleFrame(type: Int, payload: JSONObject) {
        if (torndown) return
        when (type) {
            ControlProtocol.TRANSPORT_RESULT -> {
                val tier = TransportDescriptor.fromId(payload.optInt("t", -1)) ?: return
                if (planIndex !in plan.indices || plan[planIndex] != tier) return
                val ok = payload.optBoolean("ok", false)
                val code = FailureCode.fromId(payload.optInt("code", 0))
                currentAttempt?.let {
                    TransportAttemptLog.end(
                        appContext, it,
                        if (ok) AttemptOutcome.CONNECTED else AttemptOutcome.FAILED, code
                    )
                    currentAttempt = null
                }
                receiverPeerId?.let {
                    OffGridRepository.recordTransportOutcome(appContext, it, tier, ok)
                }
                // Self-GO learning: P2P_CRED_JOIN is the sender hosting its own Wi-Fi Direct group,
                // so its outcome reflects this device's GO reliability. Skip HANDSHAKE_FAILED (a
                // post-join data-plane issue, not a GO failure) → one outcome per hosting attempt.
                // (RECEIVER_AS_GO = receiver hosted → the receiver records its own self-GO.)
                if (tier == TransportDescriptor.P2P_CRED_JOIN && code != FailureCode.HANDSHAKE_FAILED) {
                    OffGridRepository.recordSelfGoOutcome(appContext, ok)
                    TransportDiag.log("TX: self-GO outcome=${if (ok) "ok" else "fail"}")
                }
                TransportDiag.log("TX: receiver result ${tier.wireName} = ${if (ok) "OK ✓" else "fail ($code)"}")
                if (ok) {
                    dataConnected = true
                    // The TCP P2P_HELLO arrives next; TransferDaemon takes over.
                } else {
                    // HANDSHAKE_FAILED means a route reported UP (so dataConnected was set on that
                    // earlier OK) but its data handshake then failed — the route is dead. Clear the
                    // flag so the ladder can resume; otherwise advance() no-ops on dataConnected and
                    // the session deadlocks at the current tier (the later RECEIVER_AS_GO/LOHS tiers
                    // never run — the exact phone→tablet hang in the traces).
                    if (code == FailureCode.HANDSHAKE_FAILED) dataConnected = false
                    advance(planIndex + 1)
                }
            }

            ControlProtocol.HOST_READY -> {
                // The receiver is hosting the rendezvous and handed us its creds. We join, then serve
                // TCP on our joined-network IP; the receiver (host) connects out to it. Two flavours:
                // a Wi-Fi Direct GO (RECEIVER_AS_GO → P2pCredentialJoiner) or a mobile hotspot
                // (SENDER_JOINS_AP, nk=SOFTAP → SpecifierJoiner / WifiNetworkSpecifier).
                val curTier = plan.getOrNull(planIndex)
                if (curTier != TransportDescriptor.RECEIVER_AS_GO && curTier != TransportDescriptor.SENDER_JOINS_AP) return
                if (dataConnected) return
                val ssid = payload.optString("ssid")
                val psk = payload.optString("psk")
                if (ssid.isEmpty() || psk.isEmpty()) { advance(planIndex + 1); return }
                val nk = NetKind.fromId(payload.optInt("nk", NetKind.P2P_GROUP.id))

                if (nk == NetKind.SOFTAP || curTier == TransportDescriptor.SENDER_JOINS_AP) {
                    TransportDiag.log("TX: joining receiver's hotspot (ssid=$ssid)")
                    val joiner = SpecifierJoiner(appContext).also {
                        specifierJoiner?.teardown()
                        specifierJoiner = it
                    }
                    joiner.connect(ssid, psk, TransportDescriptor.SENDER_JOINS_AP.budgetMs.toInt(),
                        object : SpecifierJoiner.Listener {
                            override fun onNetworkAvailable(network: android.net.Network) {
                                if (torndown) return
                                TransferDaemon.start(appContext) // server on 0.0.0.0 accepts on the joined iface
                                val ip = localIpv4OnNetwork(network)
                                if (ip == null) {
                                    TransportDiag.log("TX: joined hotspot but no STA IP — failing tier")
                                    advance(planIndex + 1)
                                    return
                                }
                                TransportDiag.log("TX: joined receiver hotspot; serving on $ip; DATA_READY sent")
                                sendDataReady(ip)
                            }

                            override fun onNetworkUnavailable() {
                                if (torndown) return
                                TransportDiag.log("TX: failed to join receiver's hotspot")
                                advance(planIndex + 1)
                            }

                            override fun onNetworkLost() {
                                if (torndown || dataConnected) return
                                advance(planIndex + 1)
                            }
                        })
                    return
                }

                val joiner = P2pCredentialJoiner(appContext).also {
                    credentialJoiner?.teardown()
                    credentialJoiner = it
                }
                val joinListener = object : P2pCredentialJoiner.Listener {
                    override fun onJoined(groupOwnerAddress: InetAddress, localAddress: InetAddress?) {
                        if (torndown || dataConnected) return
                        TransferDaemon.start(appContext)  // bind the TCP server on the p2p iface
                        val clientIp = localAddress?.hostAddress ?: findLocalP2pIp()
                        if (clientIp == null) {
                            TransportDiag.log("TX: joined but no client p2p IP — failing tier")
                            advance(planIndex + 1)
                            return
                        }
                        // The receiver (GO) connects OUT to this IP, so it MUST be on the GO's /24. A stale
                        // interface read (e.g. a 192.168.43.x hotspot remnant) is unreachable and would push
                        // the receiver into a self-connection fallback — fail fast instead of advertising it.
                        val goHost = groupOwnerAddress.hostAddress
                        if (goHost != null &&
                            clientIp.substringBeforeLast('.') != goHost.substringBeforeLast('.')) {
                            TransportDiag.log(
                                "TX: client IP $clientIp not on GO subnet ${goHost.substringBeforeLast('.')}.x — failing tier"
                            )
                            advance(planIndex + 1)
                            return
                        }
                        TransportDiag.log("TX: joined receiver group; serving on $clientIp; DATA_READY sent")
                        sendDataReady(clientIp)
                        // The receiver (GO) connects to clientIp and reports TRANSPORT_RESULT.
                    }

                    override fun onFailed() {
                        if (torndown) return
                        TransportDiag.log("TX: failed to join receiver's group")
                        advance(planIndex + 1)
                    }
                }
                // Tear OUR autonomous GO down and let the radio settle BEFORE joining the receiver's group:
                // a device can't be a Group Owner and join another P2P group on the same chip at once (the
                // MIUI "failed to join receiver's group" 20s timeout). teardownGroupOnce fires its callback
                // once removeGroup resolves (bounded fallback inside P2pGroupManager.teardownAndSettle).
                TransportDiag.log("TX: tearing down our group, then joining receiver's (ssid=$ssid)")
                teardownGroupOnce {
                    if (torndown || dataConnected) return@teardownGroupOnce
                    joiner.join(ssid, psk, TransportDescriptor.RECEIVER_AS_GO.budgetMs, joinListener)
                }
            }

            ControlProtocol.ABORT -> listener.onLadderExhausted(LadderExhaustReason.GENERIC)
        }
    }

    private fun handleControlLost() {
        if (torndown) return
        session?.close()
        session = null
        if (!dataConnected) {
            // §9 #11: revert to autonomous behavior; the fragment recreates the
            // group if T2/T3 destroyed it.
            listener.onControlLost()
        }
        // Mid-transfer the data plane is independent (§9 #12) — nothing to do.
    }

    /** True once a coordinated tier produced a live data connection. */
    fun isDataConnected(): Boolean = dataConnected

    fun isGroupTornDown(): Boolean = groupTornDown

    /**
     * Tear down our autonomous Wi-Fi Direct GO, once. [onDone] fires (on the main thread) when the
     * teardown has settled — used by RECEIVER_AS_GO so the join doesn't start while the chip is still
     * a GO. Tiers that don't need to wait (P2P_NEGOTIATED/LOHS) call it with the default no-op.
     */
    private fun teardownGroupOnce(onDone: () -> Unit = {}) {
        if (groupTornDown) { mainHandler.post(onDone); return }
        groupTornDown = true
        listener.onRequireGroupTeardown(onDone)
    }

    fun teardown() {
        torndown = true
        mainHandler.removeCallbacksAndMessages(helloWatchdogToken)
        currentAttempt?.let {
            if (it.outcome == AttemptOutcome.PENDING) {
                TransportAttemptLog.end(appContext, it, AttemptOutcome.FAILED, FailureCode.CANCELLED)
            }
        }
        currentAttempt = null
        session?.close()
        session = null
        bleServer?.close()
        bleServer = null
        negotiated?.teardown()
        negotiated = null
        credentialJoiner?.teardown()
        credentialJoiner = null
        specifierJoiner?.teardown()
        specifierJoiner = null
        lohs?.stop()
        lohs = null
    }
}

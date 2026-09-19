package com.joseph.clipboard.windows.network

import com.joseph.clipboard.desktop.platform.Platform

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import com.joseph.clipboard.windows.storage.DiagLog
import com.joseph.clipboard.windows.storage.SecurityVault

data class DiscoveredDevice(
    val name: String,
    val ip: String,
    val os: String,
    var lastSeen: Long,
    /** Peer advertised [TransferDaemon.CLIPBOARD_GCM_CAP] → can open authenticated (AES-GCM)
     * clipboard payloads. Every send is GCM-sealed regardless (CBC retired in 3.5.0); this flag
     * drives the TOFU pin, and a pinned peer that stops advertising it is skipped as a downgrade. */
    var supportsAuthClipboard: Boolean = false,
    /** Peer advertised [TransferDaemon.FILE_GCM_CAP] → can seal/open authenticated (AES-GCM) bulk
     * file streams via FileSeal. Defaults false so legacy peers stay plaintext. */
    var supportsAuthFile: Boolean = false,
    /** Peer advertised [TransferDaemon.FILE_GCM_CAP_V2] → supports the FileSeal v2 AEAD (per-session
     * key + session/file-bound AAD). Orthogonal to [supportsAuthFile]: v2 is used only when BOTH peers
     * advertise it, otherwise the negotiation falls back to v1. Defaults false. */
    var supportsAuthFileV2: Boolean = false,
    /** Peer advertised [TransferDaemon.OFFER_AUTH_CAP] → it answers an OFFER_CHALLENGE with an HMAC
     * proof of the shared pairing key before we show its incoming-file popup. The receiver only
     * challenges senders with this flag; senders that omit it keep today's direct OFFER_RECEIVED
     * handshake. Defaults false so un-updated peers stay byte-identical. */
    var supportsAuth2: Boolean = false,
    /** Peer's stable install id from the discovery `did:` token; null for peers that predate it. Lets
     * clipboard auto-push follow this device to a new DHCP address (see [ClipboardTargeting]). */
    var deviceId: String? = null,
    /** Peer advertised [TransferDaemon.ACCEPT_AUTH_CAP] → it will answer an ACCEPT_CHALLENGE with an
     * HMAC proof of the shared pairing key before it (as sender) starts streaming bytes in response
     * to our ACCEPT_OFFER/RESUME_REQUEST — the accept/resume-direction mirror of [supportsAuth2].
     * Defaults false so un-updated peers are byte-identical. */
    var supportsAuth2Accept: Boolean = false,
)

class TransferDaemon(
    private val deviceNameProvider: () -> String,
    // Fired when a peer's FILEBRIDGE_RESUME_REQUEST nudge arrives on the discovery port — Android's
    // outgoing-transfer "Resume" sends this to prompt US (the receiver) to reconnect. Previously
    // unhandled: Windows silently dropped the nudge, so an Android→Windows outgoing resume looked
    // like it "sent" (Android's local send() succeeded) but the transfer never actually resumed.
    private val onResumeRequested: (sessionId: String, fromIp: String) -> Unit = { _, _ -> },
) {
    private val DISCOVERY_PORT = Ports.DISCOVERY
    // Match Android's DISCOVERY_NODE_TIMEOUT_MS (20s). Android backs its broadcaster off toward 12s
    // on a stable network, so an 8s window flickered a quiet Android peer out of the list every cycle
    // (spurious drop + downgrade-BLOCK churn). 20s keeps ~8s of margin over that max interval.
    private val EXPIRY_TIME_MS = 20000L
    private val BROADCAST_INTERVAL_MS = 3000L // Broadcast every 3 seconds

    companion object {
        /** Optional 5th discovery field; presence means the peer can decrypt AES-GCM clipboard
         * payloads ([com.joseph.clipboard.windows.storage.ClipboardSeal]). Mirrors Android's token. */
        const val CLIPBOARD_GCM_CAP = "GCM1"

        /** File-stream AEAD token (FileSeal). Advertised comma-joined with [CLIPBOARD_GCM_CAP] in the
         * optional 5th field (`...|Windows|GCM1,FGCM1`). Mirrors Android's FILE_GCM_CAP. */
        const val FILE_GCM_CAP = "FGCM1"

        /** FileSeal v2 AEAD token. Advertised comma-joined after [FILE_GCM_CAP] (`...|Windows|
         * GCM1,FGCM1,FGCM2`). Mirrors Android's FILE_GCM_CAP_V2. Orthogonal to [FILE_GCM_CAP]: both
         * are authenticated; v2 (per-session key + session/file-bound AAD) is chosen only when BOTH
         * peers advertise FGCM2, else the transfer falls back to the v1 FileSeal path. */
        const val FILE_GCM_CAP_V2 = "FGCM2"

        /** OFFER admission-proof token. Advertised comma-joined after [FILE_GCM_CAP_V2] (`...|Windows|
         * GCM1,FGCM1,FGCM2,AUTH2`). Mirrors Android's OFFER_AUTH_CAP. Presence means the peer answers an
         * OFFER_CHALLENGE with an HMAC proof of the shared pairing key before the receiver shows the
         * incoming-file popup. A receiver only challenges senders with this flag; a sender always
         * tolerates both the challenge and a direct OFFER_RECEIVED, so old↔new stays byte-identical. */
        const val OFFER_AUTH_CAP = "AUTH2"

        /** ACCEPT/RESUME admission-proof token, advertised comma-joined after [OFFER_AUTH_CAP]
         * (`...|Windows|GCM1,FGCM1,FGCM2,AUTH2,AUTH2R`). The accept/resume-direction mirror of
         * [OFFER_AUTH_CAP]: presence means the peer will answer an ACCEPT_CHALLENGE with an HMAC
         * proof of the shared pairing key before it (as sender) streams bytes in response to our
         * ACCEPT_OFFER/RESUME_REQUEST. Deliberately a SEPARATE token from OFFER_AUTH_CAP — already-
         * shipped 3.7.1/3.7.4 builds advertise AUTH2 but do not implement this direction's challenge,
         * so reusing AUTH2 would silently break them. Mirrors Android's TransferDaemon.ACCEPT_AUTH_CAP. */
        const val ACCEPT_AUTH_CAP = "AUTH2R"

        /** Capability list advertised in discovery — comma-joined, parsed by `contains`. */
        private val DISCOVERY_CAPS = "$CLIPBOARD_GCM_CAP,$FILE_GCM_CAP,$FILE_GCM_CAP_V2,$OFFER_AUTH_CAP,$ACCEPT_AUTH_CAP"
    }

    private val discoveredNodes = ConcurrentHashMap<String, DiscoveredDevice>()
    // @Volatile: written by stop()/start() on the caller's thread, read by three worker loops.
    @Volatile private var isRunning = false
    @Volatile private var socket: DatagramSocket? = null
    // The workers this run owns. stop() interrupts and joins them, so a restart can't leave the old
    // broadcaster/pruner asleep in their 3s/2s waits only to wake up into the NEXT run's socket.
    private val workers = mutableListOf<Thread>()

    /** Last bind failure, or null when the socket is up. Discovery dying is otherwise invisible in a
     *  windowed (javaw) launch, which is exactly how a failed restart used to go unnoticed. */
    @Volatile var lastBindError: String? = null
        private set

    /** True while the discovery socket is bound and the workers are live. */
    val isAlive: Boolean get() = isRunning

    @Synchronized
    fun start() {
        if (isRunning) return

        // Bind BEFORE arming isRunning: a failed bind used to leave the flag set, so every later
        // start() (notably Restart Services) returned immediately and discovery stayed dead forever.
        val bound = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                broadcast = true
            }
        } catch (e: Exception) {
            lastBindError = e.message ?: e.toString()
            System.err.println("TransferDaemon failed to bind discovery port $DISCOVERY_PORT: ${e.message}")
            DiagLog.w("discovery: bind of UDP $DISCOVERY_PORT failed (${e.message}) — this device will neither see nor be seen. Another instance may still hold the port.")
            return
        }
        socket = bound
        lastBindError = null
        isRunning = true

        // Every worker closes over the socket IT was started with rather than reading the shared
        // field, so a late-exiting worker can never touch a socket a later start() published.

        // Broadcaster Thread
        workers += thread(isDaemon = true, name = "Discovery-Broadcaster") {
            while (isRunning && !bound.isClosed) {
                if (SecurityVault.isDiscoverable()) {
                    broadcastPresence(bound)
                } else {
                    // Hidden: never announce to the whole subnet, but a device we are already paired
                    // with must still be able to find us, so probe each trusted node directly. Same
                    // frame, same fields — only the destination differs from the broadcast above.
                    unicastPresenceToTrustedNodes(bound)
                }
                try {
                    Thread.sleep(BROADCAST_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break // stop() is joining us
                }
            }
        }

        // Listener Thread
        workers += thread(isDaemon = true, name = "Discovery-Listener") {
            val buffer = ByteArray(1024)
            try {
                while (isRunning && !bound.isClosed) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        bound.receive(packet)
                        val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        handleIncomingPacket(data, packet.address, bound)
                    } catch (e: Exception) {
                        if (isRunning && !bound.isClosed) {
                            e.printStackTrace()
                        }
                    }
                }
            } finally {
                // The listener is the only worker whose death takes discovery down with it. If it
                // leaves while still flagged running (socket closed under it by something other than
                // stop(), or an Error escaping the inner catch), clear the flag so a later start()
                // rebinds instead of no-opping at its guard. A normal stop() nulls the field before
                // joining, so the identity check keeps this off the ordinary path — no lock needed,
                // and no risk of deadlocking against stop()'s own monitor while it joins us.
                if (socket === bound && isRunning) {
                    isRunning = false
                    socket = null
                    lastBindError = "discovery listener exited unexpectedly"
                    DiagLog.w("discovery: listener thread exited unexpectedly — this device will neither see nor be seen until the next start")
                }
            }
        }

        // Expiry Pruner Thread
        workers += thread(isDaemon = true, name = "Discovery-Pruner") {
            while (isRunning && !bound.isClosed) {
                pruneExpiredNodes()
                try {
                    Thread.sleep(2000L) // check every 2s
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try { socket?.close() } catch (e: Exception) { /* already closed */ }
        socket = null
        // Interrupt the sleepers, then wait for all three to actually leave their loops. Without the
        // join, stop()->delay(500)->start() returned while the old workers were still sleeping.
        val leaving = workers.toList()
        workers.clear()
        leaving.forEach { it.interrupt() }
        leaving.forEach { runCatching { it.join(WORKER_JOIN_TIMEOUT_MS) } }
        discoveredNodes.clear()
    }

    fun getDiscoveredNodes(): List<DiscoveredDevice> {
        return discoveredNodes.values.toList()
    }

    fun clearCache() {
        discoveredNodes.clear()
    }

    /**
     * The presence frame this daemon puts on the wire, for [kind] = `FILEBRIDGE_DISCOVERY` (a probe)
     * or `FILEBRIDGE_REPLY` (a directed answer). Trailing caps field (|GCM1,FGCM1…) advertises
     * clipboard + file AEAD support; the `did:` token after it carries this install's stable id so a
     * peer can follow us to a new DHCP address for auto-push. Older peers parse caps at index 4 and
     * ignore anything beyond, so this stays wire-compatible.
     *
     * Field 3 is the OS token, previously the literal "Windows" because there was only one desktop
     * client. It now comes from the platform seam, so a Linux build advertises "Linux". Android
     * reads this field to pick the peer's icon and labels — see PlatformId.wireToken, and treat any
     * change to those strings as a wire change needing a matching Android-side update.
     */
    private fun presenceFrame(kind: String): ByteArray =
        "$kind|${deviceNameProvider()}|${getLocalIpAddress()}|${Platform.current.id.wireToken}|$DISCOVERY_CAPS|${WireFrames.DEVICE_ID_TOKEN}${SecurityVault.localDeviceId()}"
            .toByteArray(Charsets.UTF_8)

    private fun broadcastPresence(via: DatagramSocket) {
        try {
            val data = presenceFrame("FILEBRIDGE_DISCOVERY")
            val packet = DatagramPacket(
                data,
                data.size,
                InetAddress.getByName("255.255.255.255"),
                DISCOVERY_PORT
            )
            via.send(packet)
        } catch (e: Exception) {
            // Ignore broadcast errors
        }
    }

    /**
     * The not-discoverable substitute for [broadcastPresence]: the identical DISCOVERY frame, sent
     * to each paired peer's last-known address instead of the subnet broadcast. Without it, two
     * devices that both have "Discoverable to other devices" off could never find each other — each
     * would be waiting for a probe neither is allowed to broadcast. Unreachable/stale addresses just
     * fail silently, the same way a broadcast to an empty subnet does.
     */
    private fun unicastPresenceToTrustedNodes(via: DatagramSocket) {
        val trusted = SecurityVault.getAllTrustedNodes().keys
        if (trusted.isEmpty()) return
        val data = try {
            presenceFrame("FILEBRIDGE_DISCOVERY")
        } catch (e: Exception) {
            return
        }
        for (ip in trusted) {
            try {
                via.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), DISCOVERY_PORT))
            } catch (e: Exception) {
                // Per-peer best effort: one unresolvable address must not stop the rest.
            }
        }
    }

    private fun handleIncomingPacket(data: String, sender: InetAddress, via: DatagramSocket) {
        val parts = data.split("|")
        if (parts.getOrNull(0) == "FILEBRIDGE_RESUME_REQUEST") {
            val sessionId = parts.getOrNull(1)
            val fromIp = sender.hostAddress
            if (sessionId != null && fromIp != null) onResumeRequested(sessionId, fromIp)
            return
        }
        // Accept the unicast FILEBRIDGE_REPLY as well as the broadcast FILEBRIDGE_DISCOVERY; Android
        // emits a directed REPLY so peers can find each other under AP client-isolation (which drops
        // broadcasts). Both carry the same name|ip|os|caps fields and are processed identically.
        val isReply = parts.getOrNull(0) == "FILEBRIDGE_REPLY"
        val isDiscovery = parts.getOrNull(0) == "FILEBRIDGE_DISCOVERY"
        if (parts.size >= 4 && (isDiscovery || isReply)) {
            // Inbound presence is ALWAYS processed, in either discoverable state. "Discoverable to
            // other devices" only governs what this device sends: dropping inbound probes as well
            // used to blind the user to every peer, including their own paired devices, which is not
            // what the setting says it does. See [DiscoveryPolicy] for who gets answered.

            val deviceName = parts[1]
            val os = parts[3]
            // Optional trailing capability field; absent for older Android + Windows builds.
            val caps = (parts.getOrNull(4) ?: "").split(",")
            val supportsAuth = caps.contains(CLIPBOARD_GCM_CAP)
            val supportsFile = caps.contains(FILE_GCM_CAP)
            val supportsFileV2 = caps.contains(FILE_GCM_CAP_V2)
            val supportsAuth2 = caps.contains(OFFER_AUTH_CAP)
            val supportsAuth2Accept = caps.contains(ACCEPT_AUTH_CAP)
            // Stable device id from the `did:` token anywhere in the tail after the positional fields
            // (absent on peers that predate it). Lets clipboard auto-push follow this peer to a new
            // address; see ClipboardTargeting.
            val peerDeviceId = WireFrames.parseDeviceId(parts.drop(4))

            // Always trust the packet's source address over the payload-declared IP (parts[2]);
            // Android was hardened the same way so a peer behind NAT/misconfig can't have us store an
            // unreachable or spoofed IP. The declared IP is left unused for tracking.
            val ipToTrack = sender.hostAddress ?: return

            // Ignore our own broadcast. Compare against ALL of this host's addresses, not just the
            // single localLanIpv4() we advertise: a PC with >1 active adapter (Wi-Fi + Ethernet, or a
            // Hyper-V / WSL / VPN virtual adapter) can receive its own broadcast with a source IP that
            // egressed a different adapter, which a single-IP compare misses — so the PC lists itself.
            // Mirrors Android's Net.isMyOwnIp self-filter in LanDiscovery.
            if (Net.isMyOwnIp(ipToTrack)) return

            val now = System.currentTimeMillis()
            val existing = discoveredNodes[ipToTrack]
            if (existing != null) {
                existing.lastSeen = now
                existing.supportsAuthClipboard = supportsAuth
                existing.supportsAuthFile = supportsFile
                existing.supportsAuthFileV2 = supportsFileV2
                existing.supportsAuth2 = supportsAuth2
                existing.supportsAuth2Accept = supportsAuth2Accept
                // Keep the last id we saw: a did-aware peer sends it on every frame, and we never want
                // a stray malformed packet to null out a good id mid-session.
                if (peerDeviceId != null) existing.deviceId = peerDeviceId
            } else {
                discoveredNodes[ipToTrack] = DiscoveredDevice(deviceName, ipToTrack, os, now, supportsAuth, supportsFile, supportsFileV2, supportsAuth2, peerDeviceId, supportsAuth2Accept)
            }

            // Answer a DISCOVERY with a unicast REPLY (bypasses AP broadcast restrictions), mirroring
            // Android. Field order matches this daemon's own broadcast: name|ip|Windows|caps. Not sent
            // for a REPLY, so two peers don't ping-pong replies forever. While discoverable the answer
            // goes to everyone exactly as before; while hidden only a paired peer is answered, so this
            // device stays invisible to strangers without going blind — see [DiscoveryPolicy].
            if (isDiscovery && DiscoveryPolicy.shouldReplyToProbe(
                    discoverable = SecurityVault.isDiscoverable(),
                    probe = data,
                    senderIp = ipToTrack,
                    trustedIps = SecurityVault.getAllTrustedNodes().keys,
                    pairedDeviceIdFor = SecurityVault::getPairedDeviceId,
                )
            ) {
                sendDiscoveryReply(sender, via)
            }
        }
    }

    private fun sendDiscoveryReply(target: InetAddress, via: DatagramSocket) {
        try {
            val data = presenceFrame("FILEBRIDGE_REPLY")
            val packet = DatagramPacket(data, data.size, target, DISCOVERY_PORT)
            via.send(packet)
        } catch (e: Exception) {
            // Best-effort unicast reply
        }
    }

    private fun pruneExpiredNodes() {
        val now = System.currentTimeMillis()
        val iterator = discoveredNodes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeen > EXPIRY_TIME_MS) {
                iterator.remove()
            }
        }
    }

    private fun getLocalIpAddress(): String {
        // The IP we advertise as our reachable endpoint in DISCOVERY/REPLY payloads.
        // InetAddress.getLocalHost() often returns 127.0.0.1 or a virtual-adapter IP on Windows, so
        // peers would store an unreachable address. Prefer Net.localLanIpv4() (resolves the real LAN
        // adapter); fall back to getLocalHost() only when no LAN IPv4 can be determined. The self-
        // broadcast filter no longer relies on this single value — see Net.isMyOwnIp above.
        Net.localLanIpv4()?.let { return it }
        return try {
            InetAddress.getLocalHost().hostAddress
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}

package com.joseph.clipboard.windows.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
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
    private var isRunning = false
    private var socket: DatagramSocket? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                broadcast = true
            }
        } catch (e: SocketException) {
            e.printStackTrace()
            // Port already bound (likely another instance) — discovery stays offline this run.
            return
        }

        // Broadcaster Thread
        thread(isDaemon = true, name = "Discovery-Broadcaster") {
            while (isRunning) {
                // Skip broadcasting while not discoverable, mirroring Android (which gates its send loop
                // on SecurityVault.isDiscoverable). Keeps the daemon running so it can still receive.
                if (SecurityVault.isDiscoverable()) {
                    broadcastPresence()
                }
                Thread.sleep(BROADCAST_INTERVAL_MS)
            }
        }

        // Listener Thread
        thread(isDaemon = true, name = "Discovery-Listener") {
            val buffer = ByteArray(1024)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    handleIncomingPacket(data, packet.address)
                } catch (e: Exception) {
                    if (isRunning) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // Expiry Pruner Thread
        thread(isDaemon = true, name = "Discovery-Pruner") {
            while (isRunning) {
                pruneExpiredNodes()
                Thread.sleep(2000L) // check every 2s
            }
        }
    }

    fun stop() {
        isRunning = false
        socket?.close()
        socket = null
        discoveredNodes.clear()
    }

    fun getDiscoveredNodes(): List<DiscoveredDevice> {
        return discoveredNodes.values.toList()
    }

    fun clearCache() {
        discoveredNodes.clear()
    }

    private fun broadcastPresence() {
        try {
            val localIp = getLocalIpAddress()
            // Trailing caps field (|GCM1,FGCM1) advertises clipboard + file AEAD support; the `did:`
            // token after it carries this install's stable id so a peer can follow us to a new DHCP
            // address for auto-push. Older peers parse caps at index 4 and ignore anything beyond, so
            // this stays wire-compatible.
            val message = "FILEBRIDGE_DISCOVERY|${deviceNameProvider()}|$localIp|Windows|$DISCOVERY_CAPS|${WireFrames.DEVICE_ID_TOKEN}${SecurityVault.localDeviceId()}"
            val data = message.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                data,
                data.size,
                InetAddress.getByName("255.255.255.255"),
                DISCOVERY_PORT
            )
            socket?.send(packet)
        } catch (e: Exception) {
            // Ignore broadcast errors
        }
    }

    private fun handleIncomingPacket(data: String, sender: InetAddress) {
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
            // A DISCOVERY probe is inbound presence; ignore it while not discoverable, mirroring
            // Android. A REPLY is a directed answer to our own probe, so we always process it.
            if (isDiscovery && !SecurityVault.isDiscoverable()) return

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

            // Answer every DISCOVERY with a unicast REPLY (bypasses AP broadcast restrictions), mirroring
            // Android. Field order matches this daemon's own broadcast: name|ip|Windows|caps. Not sent
            // for a REPLY, so two peers don't ping-pong replies forever.
            if (isDiscovery) {
                sendDiscoveryReply(sender)
            }
        }
    }

    private fun sendDiscoveryReply(target: InetAddress) {
        try {
            val localIp = getLocalIpAddress()
            val message = "FILEBRIDGE_REPLY|${deviceNameProvider()}|$localIp|Windows|$DISCOVERY_CAPS|${WireFrames.DEVICE_ID_TOKEN}${SecurityVault.localDeviceId()}"
            val data = message.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(data, data.size, target, DISCOVERY_PORT)
            socket?.send(packet)
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

package com.EntropicSoftwareLab.ClipboardShare

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Who is allowed to learn this device exists while "Discoverable to other devices" is OFF.
 *
 * OFF used to mean "no presence broadcast AND drop every inbound probe", which blinded this device
 * as well as hiding it — the user could neither be seen nor see anyone. The rule is now narrower:
 * stay silent to the subnet, but keep answering the peers that already hold a pairing with us.
 *
 * Pure so the Windows daemon can implement the identical decision against the same test matrix; no
 * new wire fields are involved, only whether an otherwise-normal FILEBRIDGE_REPLY is sent.
 */
object DiscoveryPolicy {

    /**
     * Should an inbound FILEBRIDGE_DISCOVERY probe get the usual unicast FILEBRIDGE_REPLY?
     *
     * @param discoverable      the user's "Discoverable to other devices" setting.
     * @param probeDeviceId     the probe's `did:` value, or null/blank when it carries none (peers
     *                          older than the token omit it).
     * @param senderIp          the datagram's source address — never the payload-declared IP, so a
     *                          peer cannot claim a paired address it isn't calling from.
     * @param trustedIps        `SecurityVault.getTrustedNodes(context)`.
     * @param pairedDeviceIdFor `SecurityVault.getPairedDeviceId(context, ip)` for a trusted IP, null
     *                          for records written before device ids existed.
     */
    fun shouldReplyToProbe(
        discoverable: Boolean,
        probeDeviceId: String?,
        senderIp: String,
        trustedIps: Set<String>,
        pairedDeviceIdFor: (String) -> String?
    ): Boolean {
        if (discoverable) return true
        val probed = probeDeviceId?.takeIf { it.isNotBlank() }
        if (probed != null) {
            // Matched against EVERY pairing record, not just the one filed under senderIp: the whole
            // point of the id is to survive the peer moving to a new DHCP address, which is exactly
            // when its old record is filed under an address it no longer calls from. A did matching
            // no pairing does NOT fall through to the address check — the peer told us which device
            // it is, and it isn't one of ours.
            //
            // Residual exposure, accepted: a `did:` travels in clear in every broadcast a visible
            // peer sends, so a LAN attacker who harvested one can replay it to confirm a hidden
            // device exists. The reply discloses only what that device broadcasts when visible, and
            // the setting's promise is "don't appear in strangers' device lists", not resistance to
            // an active on-link attacker.
            return trustedIps.any { ip -> pairedDeviceIdFor(ip)?.takeIf { it.isNotBlank() } == probed }
        }
        // No usable id (peer predates the token, or sent one that failed validation): the source
        // address is all there is to go on.
        return senderIp in trustedIps
    }
}

object LanDiscovery {
    @Volatile private var discoveryWakeUntilMs: Long = 0L

    fun wakeDiscovery() {
        discoveryWakeUntilMs = System.currentTimeMillis() + TransferDaemon.DISCOVERY_WAKE_WINDOW_MS
    }

    /**
     * [DiscoveryPolicy.shouldReplyToProbe] against the live vault, short-circuiting on the
     * discoverable case so the common path never touches encrypted prefs on the per-datagram
     * receive loop.
     */
    private fun mayAnswerProbe(context: Context, senderIp: String, probeDeviceId: String?): Boolean {
        // Short-circuit before touching the vault at all: the discoverable case is the common one and
        // getTrustedNodes decrypts the whole pairing store, on a path that runs per inbound datagram.
        if (SecurityVault.isDiscoverable(context)) return true
        return DiscoveryPolicy.shouldReplyToProbe(
            discoverable = false,
            probeDeviceId = probeDeviceId,
            senderIp = senderIp,
            trustedIps = SecurityVault.getTrustedNodes(context).toSet(),
            pairedDeviceIdFor = { ip -> SecurityVault.getPairedDeviceId(context, ip) }
        )
    }

    fun start(context: Context, daemonScope: CoroutineScope) {
        // Independent pruning timer: the receive loop only prunes stale nodes as a side effect of
        // an incoming packet, so on a near-silent LAN (the exact moment auto-push/auto-resume needs
        // fresh discovery data to fail over) a dead entry can sit unpruned indefinitely. Windows runs
        // an equivalent dedicated poller (TransferDaemon.kt "Discovery-Pruner", every 2s); this
        // matches that cadence so the two platforms fail over on comparable timelines.
        daemonScope.launch {
            while (isActive) {
                delay(2000L)
                val now = System.currentTimeMillis()
                TransferDaemon.removeDiscoveredNodesIf { now - it.value.lastSeen > TransferDaemon.DISCOVERY_NODE_TIMEOUT_MS }
            }
        }
        daemonScope.launch {
            var udpSocket: DatagramSocket? = null
            try {
                val sock = DatagramSocket()
                udpSocket = sock
                sock.broadcast = true
                var interval = TransferDaemon.DISCOVERY_INTERVAL_MIN_MS
                var lastNodeCount = -1
                while (isActive) {
                    val targets: List<InetAddress> = if (SecurityVault.isDiscoverable(context)) {
                        Net.getAllBroadcastAddresses(context)
                    } else {
                        // Not discoverable: never announce to the whole subnet. Paired peers must
                        // still find us — and two devices that BOTH have discoverable off would
                        // otherwise never meet — so probe each trusted node's last-known address
                        // directly with the very same frame. A peer on an older build parses a
                        // unicast DISCOVERY exactly as it parses a broadcast one (its socket is
                        // bound to 0.0.0.0 and never inspects the destination), so nothing on the
                        // wire changed; only the addressee did.
                        SecurityVault.getTrustedNodes(context)
                            .filter { Net.isIpv4Literal(it) && !Net.isMyOwnIp(it) }
                            .mapNotNull { ip -> try { InetAddress.getByName(ip) } catch (_: Exception) { null } }
                    }
                    if (targets.isNotEmpty()) {
                        val myIp = Net.getMyIpAddress()
                        // The `did:` token after caps carries this install's stable id so a peer can
                        // follow us to a new DHCP address for auto-push. Older peers read caps at index
                        // 4 and ignore anything beyond, so this stays wire-compatible.
                        val message = "FILEBRIDGE_DISCOVERY|${TransferDaemon.customDeviceName}|$myIp|Android|${TransferDaemon.DISCOVERY_CAPS}|${PairingWire.DEVICE_ID_TOKEN}${SecurityVault.localDeviceId(context)}".toByteArray(Charsets.UTF_8)
                        for (address in targets) {
                            try {
                                val packet = DatagramPacket(message, message.size, InetSocketAddress(address, Ports.DISCOVERY))
                                sock.send(packet)
                            } catch (e: Exception) {
                                // Ignore send errors for individual interfaces
                            }
                        }
                    }

                    val nodeCount = TransferDaemon.discoveredNodes.size
                    interval = if (System.currentTimeMillis() < discoveryWakeUntilMs || nodeCount != lastNodeCount) {
                        TransferDaemon.DISCOVERY_INTERVAL_MIN_MS
                    } else {
                        (interval * 2).coerceAtMost(TransferDaemon.DISCOVERY_INTERVAL_MAX_MS)
                    }
                    lastNodeCount = nodeCount
                    delay(interval)
                }
            } catch (e: Exception) { e.printStackTrace() } finally {
                try { udpSocket?.close() } catch (_: Exception) {}
            }
        }

        daemonScope.launch {
            var udpSocket: DatagramSocket? = null
            try {
                val sock = DatagramSocket(null)
                udpSocket = sock
                sock.reuseAddress = true
                sock.bind(InetSocketAddress("0.0.0.0", Ports.DISCOVERY))
                val buffer = ByteArray(1024)

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        sock.receive(packet)
                        val senderIp = packet.address.hostAddress ?: continue
                        val msg = String(packet.data, 0, packet.length).trim()

                        val now = System.currentTimeMillis()

                        if (msg.startsWith("FILEBRIDGE_DISCOVERY|") || msg.startsWith("FILEBRIDGE_REPLY|")) {
                            val isReply = msg.startsWith("FILEBRIDGE_REPLY|")

                            // Inbound probes are ALWAYS processed, whatever "Discoverable to other
                            // devices" says: that setting controls whether others can find us, not
                            // whether we can find them. Dropping them here used to blind this device
                            // to the whole LAN as a side effect of hiding it. Discoverability is
                            // enforced on the two outbound paths instead — the send loop above and
                            // the reply below.
                            val parts = msg.split("|")
                            if (parts.size >= 4) {
                                val remoteName = parts[1]
                                val remoteOs = parts[3]
                                val caps = (parts.getOrNull(4) ?: "").split(",")
                                val supportsAuth = caps.contains(TransferDaemon.CLIPBOARD_GCM_CAP)
                                val supportsFile = caps.contains(TransferDaemon.FILE_GCM_CAP)
                                val supportsFileV2 = caps.contains(TransferDaemon.FILE_GCM_CAP_V2)
                                val supportsAuth2 = caps.contains(TransferDaemon.OFFER_AUTH_CAP)
                                val supportsAuth2Accept = caps.contains(TransferDaemon.ACCEPT_AUTH_CAP)
                                // Stable device id from the `did:` token anywhere in the tail after the
                                // positional fields (absent on peers that predate it). Lets clipboard
                                // auto-push follow this peer to a new address; see ClipboardTargeting.
                                val peerDeviceId = PairingWire.parseDeviceId(parts.drop(4))
                                val actualIp = senderIp

                                if (!Net.isMyOwnIp(actualIp)) {
                                    val isNewNode = !TransferDaemon.discoveredNodes.containsKey(actualIp)
                                    // Keep the last id we saw: a did-aware peer sends it on every frame, so
                                    // a stray packet without one must not null out a good id mid-session
                                    // (mirrors the Windows daemon).
                                    val effectiveDeviceId = peerDeviceId ?: TransferDaemon.discoveredNodes[actualIp]?.deviceId
                                    TransferDaemon.putDiscoveredNode(
                                        actualIp,
                                        DiscoveredDevice(actualIp, remoteName, remoteOs, now, supportsAuth, supportsFile, supportsFileV2, supportsAuth2, effectiveDeviceId, supportsAuth2Accept)
                                    )
                                    if (isNewNode) wakeDiscovery()

                                    if (!isReply && mayAnswerProbe(context, senderIp, peerDeviceId)) {
                                        daemonScope.launch {
                                            try {
                                                DatagramSocket().use { replySocket ->
                                                    val myIp = Net.getMyIpAddress()
                                                    val replyMsg = "FILEBRIDGE_REPLY|${TransferDaemon.customDeviceName}|$myIp|Android|${TransferDaemon.DISCOVERY_CAPS}|${PairingWire.DEVICE_ID_TOKEN}${SecurityVault.localDeviceId(context)}".toByteArray(Charsets.UTF_8)
                                                    val replyPacket = DatagramPacket(replyMsg, replyMsg.size, InetSocketAddress(senderIp, Ports.DISCOVERY))
                                                    replySocket.send(replyPacket)
                                                }
                                            } catch (e: Exception) {
                                                Log.w("LanDiscovery", "Discovery reply to $senderIp failed: ${e.message}")
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (msg.startsWith("FILEBRIDGE_RESUME_REQUEST|")) {
                            val parts = msg.split("|")
                            if (parts.size >= 2) {
                                val targetSessionId = parts[1]
                                val resumeIntent = Intent(context, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    putExtra("SHOW_HISTORY", true)
                                    putExtra("HISTORY_SESSION_ID", targetSessionId)
                                }
                                val pendingIntent = PendingIntent.getActivity(context, targetSessionId.hashCode(), resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                val notification = NotificationCompat.Builder(context, NotificationChannels.TRANSFER_REQUEST)
                                    .setSmallIcon(R.mipmap.ic_launcher)
                                    .setContentTitle(context.getString(R.string.transfer_resume_request_title))
                                    .setContentText(context.getString(R.string.transfer_resume_request_text))
                                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                                    .setContentIntent(pendingIntent)
                                    .setAutoCancel(true)
                                    .build()
                                notificationManager.notify(65437, notification)
                            }
                        }

                        TransferDaemon.removeDiscoveredNodesIf { now - it.value.lastSeen > TransferDaemon.DISCOVERY_NODE_TIMEOUT_MS }
                    } catch (e: Exception) {
                        if (!isActive || sock.isClosed) break
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() } finally {
                try { udpSocket?.close() } catch (_: Exception) {}
            }
        }
    }
}

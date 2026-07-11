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
import java.net.InetSocketAddress

object LanDiscovery {
    @Volatile private var discoveryWakeUntilMs: Long = 0L

    fun wakeDiscovery() {
        discoveryWakeUntilMs = System.currentTimeMillis() + TransferDaemon.DISCOVERY_WAKE_WINDOW_MS
    }

    fun start(context: Context, daemonScope: CoroutineScope) {
        daemonScope.launch {
            var udpSocket: DatagramSocket? = null
            try {
                val sock = DatagramSocket()
                udpSocket = sock
                sock.broadcast = true
                var interval = TransferDaemon.DISCOVERY_INTERVAL_MIN_MS
                var lastNodeCount = -1
                while (isActive) {
                    if (SecurityVault.isDiscoverable(context)) {
                        val myIp = Net.getMyIpAddress()
                        val message = "FILEBRIDGE_DISCOVERY|${TransferDaemon.customDeviceName}|$myIp|Android|${TransferDaemon.DISCOVERY_CAPS}".toByteArray(Charsets.UTF_8)

                        val broadcastAddresses = Net.getAllBroadcastAddresses(context)
                        for (address in broadcastAddresses) {
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
                            
                            if (!isReply && !SecurityVault.isDiscoverable(context)) {
                                continue
                            }
                            
                            val parts = msg.split("|")
                            if (parts.size >= 4) {
                                val remoteName = parts[1]
                                val remoteOs = parts[3]
                                val caps = (parts.getOrNull(4) ?: "").split(",")
                                val supportsAuth = caps.contains(TransferDaemon.CLIPBOARD_GCM_CAP)
                                val supportsFile = caps.contains(TransferDaemon.FILE_GCM_CAP)
                                val supportsFileV2 = caps.contains(TransferDaemon.FILE_GCM_CAP_V2)
                                val supportsAuth2 = caps.contains(TransferDaemon.OFFER_AUTH_CAP)
                                val actualIp = senderIp

                                if (!Net.isMyOwnIp(actualIp)) {
                                    val isNewNode = !TransferDaemon.discoveredNodes.containsKey(actualIp)
                                    TransferDaemon.putDiscoveredNode(
                                        actualIp,
                                        DiscoveredDevice(actualIp, remoteName, remoteOs, now, supportsAuth, supportsFile, supportsFileV2, supportsAuth2)
                                    )
                                    if (isNewNode) wakeDiscovery()

                                    if (!isReply) {
                                        daemonScope.launch {
                                            try {
                                                DatagramSocket().use { replySocket ->
                                                    val myIp = Net.getMyIpAddress()
                                                    val replyMsg = "FILEBRIDGE_REPLY|${TransferDaemon.customDeviceName}|$myIp|Android|${TransferDaemon.DISCOVERY_CAPS}".toByteArray(Charsets.UTF_8)
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

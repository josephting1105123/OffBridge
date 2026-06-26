package com.joseph.clipboard.windows.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class DiscoveredDevice(
    val name: String,
    val ip: String,
    val os: String,
    var lastSeen: Long,
    /** Peer advertised [TransferDaemon.CLIPBOARD_GCM_CAP] → can decrypt authenticated (AES-GCM)
     * clipboard payloads. Defaults false so legacy Android + older Windows builds stay on CBC. */
    var supportsAuthClipboard: Boolean = false
)

class TransferDaemon(
    private val deviceNameProvider: () -> String
) {
    private val DISCOVERY_PORT = 50001
    // Item 2: Android's node timeout is 20s and it can broadcast as infrequently as ~every 12s, so an
    // 8s window pruned quiet Android peers between broadcasts (they flickered out of the list). Match
    // Android's 20s for a comfortable margin over its 12s max interval. Broadcast cadence is unchanged.
    private val EXPIRY_TIME_MS = 20000L // 20 seconds expiry (matches Android)
    private val BROADCAST_INTERVAL_MS = 3000L // Broadcast every 3 seconds

    companion object {
        /** Optional 5th discovery field; presence means the peer can decrypt AES-GCM clipboard
         * payloads ([com.joseph.clipboard.windows.storage.ClipboardSeal]). Mirrors Android's token. */
        const val CLIPBOARD_GCM_CAP = "GCM1"
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
            // In a real app, handle port already in use or bind errors
            return
        }

        // Broadcaster Thread
        thread(isDaemon = true, name = "Discovery-Broadcaster") {
            while (isRunning) {
                broadcastPresence()
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
                    handleIncomingPacket(data, packet.address.hostAddress)
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
            // Trailing |GCM1 advertises authenticated-clipboard support; older peers parse with
            // parts.size >= 4 and ignore the extra field, so this stays wire-compatible.
            val message = "FILEBRIDGE_DISCOVERY|${deviceNameProvider()}|$localIp|Windows|$CLIPBOARD_GCM_CAP"
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

    private fun handleIncomingPacket(data: String, senderIp: String) {
        val parts = data.split("|")
        if (parts.size >= 4 && parts[0] == "FILEBRIDGE_DISCOVERY") {
            val deviceName = parts[1]
            val declaredIp = parts[2] // Can use senderIp if declared is empty/invalid
            val os = parts[3]
            // Optional trailing capability field; absent for older Android + Windows builds.
            val supportsAuth = (parts.getOrNull(4) ?: "").split(",").contains(CLIPBOARD_GCM_CAP)

            val ipToTrack = if (declaredIp.isNotBlank()) declaredIp else senderIp

            // Ignore our own broadcast
            if (ipToTrack == getLocalIpAddress()) return

            val now = System.currentTimeMillis()
            val existing = discoveredNodes[ipToTrack]
            if (existing != null) {
                existing.lastSeen = now
                existing.supportsAuthClipboard = supportsAuth
            } else {
                discoveredNodes[ipToTrack] = DiscoveredDevice(deviceName, ipToTrack, os, now, supportsAuth)
            }
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
        // M-17: InetAddress.getLocalHost() often returns 127.0.0.1 or a virtual-adapter
        // IP on Windows, so peers would store an unreachable IP and the PC could list
        // itself as discovered. Prefer Net.localLanIpv4() (resolves the real LAN adapter)
        // for both the advertised broadcast IP and the self-broadcast filter; only fall
        // back to the old getLocalHost() value when no LAN IPv4 can be determined.
        Net.localLanIpv4()?.let { return it }
        return try {
            InetAddress.getLocalHost().hostAddress
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}

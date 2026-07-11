package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings

object Ports {
    const val DISCOVERY = 50001      // UDP device discovery
    const val CLIPBOARD = 65432      // TCP clipboard sync
    const val FILE_TRANSFER = 65433  // TCP file transfer / control
    const val PAIRING = 65435        // TCP pairing
}

/** App-level read/write chunk for bulk file streaming (was a flat 8 KiB everywhere). */
const val TRANSFER_BUFFER_SIZE = 64 * 1024

/** SO_SNDBUF / SO_RCVBUF hint for data-plane sockets — a larger TCP window lifts throughput. */
const val SOCKET_BUFFER_BYTES = 512 * 1024

/**
 * Tune a data-plane TCP [Socket] for bulk throughput. Buffer sizes and the
 * performance-preference hint only take effect BEFORE the socket connects, so
 * pass [beforeConnect] = true at client connect sites; `tcpNoDelay` applies any
 * time. All calls are best-effort — a carrier that rejects an option is a no-op.
 */
fun Socket.tuneForBulkTransfer(beforeConnect: Boolean = false) {
    if (beforeConnect) runCatching { setPerformancePreferences(0, 0, 1) } // bandwidth > latency > conn-time
    runCatching { receiveBufferSize = SOCKET_BUFFER_BYTES }
    runCatching { sendBufferSize = SOCKET_BUFFER_BYTES }
    runCatching { tcpNoDelay = true }
}

object Net {
    /** Mobile-data / WWAN interface name prefixes — their IPs (often CGNAT) are never peer-reachable. */
    private val CELLULAR_IFACE_PREFIXES =
        listOf("rmnet", "ccmni", "pdp", "ppp", "clat", "rev_rmnet", "v4-rmnet", "wwan", "rmnet_data")

    private fun isCellularIface(name: String): Boolean {
        val n = name.lowercase()
        return CELLULAR_IFACE_PREFIXES.any { n.startsWith(it) } || n.contains("rmnet")
    }

    /** 100.64.0.0/10 — carrier-grade NAT; a peer on a hotspot/LAN can never reach it. */
    private fun isCgnatIp(ip: String): Boolean {
        if (!ip.startsWith("100.")) return false
        val second = ip.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 64..127
    }

    /** Closes sockets whose write outlived its deadline — java.net has no write timeout. */
    private val sendWatchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "net-send-watchdog").apply { isDaemon = true }
    }

    /** Connect, write payload, flush, close. Returns true on success. Never throws. */
    fun sendBytes(ip: String, port: Int, payload: ByteArray, connectTimeoutMs: Int = 2000, writeTimeoutMs: Long = 8000): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), connectTimeoutMs)
                // A peer that ACKs the connect but dies with a full TCP window parks write()
                // forever, and callers loop over peers sequentially — one dead peer used to
                // stall the whole clipboard broadcast (AND-07). The watchdog closes the
                // socket at the deadline so the parked write throws instead.
                val closer = sendWatchdog.schedule(
                    { runCatching { socket.close() } },
                    writeTimeoutMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                try {
                    socket.getOutputStream().run {
                        write(payload)
                        flush()
                    }
                } finally {
                    closer.cancel(false)
                }
            }
            true
        } catch (e: Exception) {
            Log.w("Net", "sendBytes to $ip:$port failed: ${e.message}")
            false
        }
    }

    /**
     * The device's Wi-Fi station / LAN IPv4, or null when it isn't on a normal
     * network. Skips the Wi-Fi Direct GO interface (p2p*, 192.168.49.x) and
     * link-local/loopback so we only ever advertise an address a peer on the
     * same LAN can actually reach.
     */
    fun localLanIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                // Skip the Wi-Fi Direct GO iface (p2p*) AND cellular ifaces — a peer can't reach our
                // carrier (CGNAT) address, so it must never be advertised as a LAN/hotspot endpoint.
                .filter { iface ->
                    val n = iface.name?.lowercase()
                    n == null || (!n.startsWith("p2p") && !isCellularIface(n))
                }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                .mapNotNull { it.hostAddress }
                .firstOrNull { !it.startsWith("192.168.49.") && !isCgnatIp(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The IPv4 of THIS device's live mobile-hotspot (SoftAP) interface, or null if no hotspot is
     * up. Two signals: (1) a clearly-named AP interface (ap*, *swlan*, *softap*, wlan1, *_ap) —
     * unambiguous regardless of STA state (STA+AP concurrency); (2) when the Wi-Fi STA is OFF, a
     * private IPv4 on a Wi-Fi-family interface (incl. wlan0) can only be a SoftAP. Excludes the
     * Wi-Fi Direct GO subnet (192.168.49.x). This same IP is the address the host serves TCP on.
     */
    fun detectHotspotIp(context: Context): String? {
        return try {
            val staEnabled = try {
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .isWifiEnabled
            } catch (_: Exception) { false }
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList()?.filter {
                it.isUp && !it.isLoopback
            } ?: return null
            fun firstIpv4(iface: NetworkInterface): String? =
                iface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                    ?.hostAddress
            fun isPrivate(ip: String): Boolean {
                if (ip.startsWith("192.168.") || ip.startsWith("10.")) return true
                if (ip.startsWith("172.")) {
                    val second = ip.split('.').getOrNull(1)?.toIntOrNull() ?: return false
                    return second in 16..31
                }
                return false
            }
            for (iface in ifaces) {
                val name = iface.name?.lowercase() ?: continue
                if (isCellularIface(name)) continue
                if (name.startsWith("ap") || name.contains("swlan") || name.contains("softap") ||
                    name.startsWith("wlan1") || name.endsWith("_ap") || name.contains("_ap_")
                ) {
                    firstIpv4(iface)?.let { if (!it.startsWith("192.168.49.") && !isCgnatIp(it)) return it }
                }
            }
            // A private IPv4 on a Wi-Fi-family iface can only be a SoftAP when the Wi-Fi STA is OFF, OR
            // when the active default network is CELLULAR — i.e. we're sharing mobile data via the
            // hotspot, so a private wifi-side IP is our AP gateway, not a client link. The cellular
            // signal is what catches MIUI/Poco, which keep isWifiEnabled=true while hosting on wlan0
            // (the device that advertised its 100.x mobile-data IP instead of 192.168.x.1).
            val defaultIsCellular = try {
                val cm = context.applicationContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true
            } catch (_: Exception) { false }
            if (!staEnabled || defaultIsCellular) {
                for (iface in ifaces) {
                    val name = iface.name?.lowercase() ?: continue
                    if (isCellularIface(name) || name.startsWith("p2p")) continue
                    val wifiFamily = name.startsWith("wlan") || name.startsWith("ap") ||
                        name.contains("swlan") || name.contains("softap")
                    if (!wifiFamily) continue
                    val ip = firstIpv4(iface) ?: continue
                    if (isPrivate(ip) && !ip.startsWith("192.168.49.") && !isCgnatIp(ip)) return ip
                }
            }
            // STA is connected to a real Wi-Fi (the paths above didn't fire): on STA+AP-concurrent
            // hardware the hotspot runs on a SEPARATE wifi-family netdev (ap0/wlan1/…) whose IP is NOT
            // our STA's own connected IP. This is the only way to see the hotspot while Wi-Fi is on —
            // which is exactly when the receive-screen warning/block needs it. Guarded on knowing the STA
            // IP (from the active default network) so we never mistake the STA's own iface for a hotspot.
            if (staEnabled && !defaultIsCellular) {
                val staIp = activeWifiIpv4(context)
                if (staIp != null) {
                    for (iface in ifaces) {
                        val name = iface.name?.lowercase() ?: continue
                        if (isCellularIface(name) || name.startsWith("p2p")) continue
                        val wifiFamily = name.startsWith("wlan") || name.startsWith("ap") ||
                            name.contains("swlan") || name.contains("softap")
                        if (!wifiFamily) continue
                        val ip = firstIpv4(iface) ?: continue
                        if (ip != staIp && isPrivate(ip) && !ip.startsWith("192.168.49.") && !isCgnatIp(ip)) return ip
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** IPv4 of the active default network when it is Wi-Fi (i.e. the STA's connected address), else null. */
    private fun activeWifiIpv4(context: Context): String? = try {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            null
        } else {
            cm.getLinkProperties(active)?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                ?.hostAddress
        }
    } catch (_: Exception) {
        null
    }

    /**
     * True when this device's Wi-Fi STA is already occupied by a real AP/router (a home/office/guest
     * network), i.e. NOT free to join a peer's hotspot. Used to guide the user when a SoftAP bridge
     * (SOFTAP_JOIN) can't proceed: on a single-radio device the OS won't switch off an active Wi-Fi
     * for a per-app WifiNetworkSpecifier, so the user must disconnect first. Reads only the active
     * transport + interface IPs (no SSID), so it needs no location/NEARBY permission
     * (ACCESS_NETWORK_STATE for getActiveNetwork is a normal, install-time permission). Best-effort.
     */
    fun isConnectedToOtherWifi(context: Context): Boolean = try {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        // Active default network is Wi-Fi AND we have a real STA IPv4 (not the p2p/49.x GO subnet).
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && localLanIpv4() != null
    } catch (_: Exception) {
        false
    }

    /** True if this device can run Wi-Fi STA (client) and AP (hotspot) at the same time. */
    fun staApConcurrencySupported(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .isStaApConcurrencySupported
        } else false
    } catch (_: Exception) {
        false
    }

    /**
     * Open the system hotspot/tethering page so the user can toggle their mobile hotspot OFF.
     * Best-effort chain: the direct Tether settings screen (the hotspot toggle lives there on most
     * OEMs) → wireless settings → the Wi-Fi panel. Each guarded by resolveActivity; no-op if none
     * resolve. Shared by [com.EntropicSoftwareLab.ClipboardShare.offgrid.HotspotCredentialsDialog] and
     * the receive-screen hotspot warning so the chain stays in one place.
     */
    fun openHotspotSettings(context: Context) {
        val intents = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.Panel.ACTION_WIFI)
        )
        for (intent in intents) {
            if (intent.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(intent) }.onSuccess { return }
            }
        }
    }

    /**
     * Open Wi-Fi settings so the user can disconnect from / switch their current network. Prefers the
     * in-context slide-up Wi-Fi panel, falling back to the full wireless-settings screen. No-op if
     * neither resolves. (App can't change the active Wi-Fi connection itself since API 29.)
     */
    fun openWifiSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.Panel.ACTION_WIFI),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in intents) {
            if (intent.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(intent) }.onSuccess { return }
            }
        }
    }

    /**
     * Every IPv4 currently assigned to this device across all up interfaces — INCLUDING the Wi-Fi
     * Direct GO interface (p2p*, 192.168.49.x). Used to drop self-addresses from a dial candidate list
     * so a device hosting as group owner never connects to its own TCP server (the receiver-as-GO
     * self-connection: candidate fallbacks resolve to our own 192.168.49.1 and we'd dial ourselves).
     */
    fun localIpv4Addresses(): Set<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                .mapNotNull { it.hostAddress }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Fire-and-forget UDP datagram. Returns true on success. Never throws. */
    /** First non-loopback IPv4 on any interface, or 127.0.0.1. (Moved from TransferDaemon.) */
    fun getMyIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in java.util.Collections.list(interfaces)) {
                val addrs = intf.inetAddresses
                for (addr in java.util.Collections.list(addrs)) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        // Parenthesize: `?:` binds looser than `<`, so the elvis must be grouped
                        // before the comparison or it parses as `sAddr ?: (-1 < 0)`.
                        val isIPv4 = (sAddr?.indexOf(':') ?: -1) < 0
                        if (isIPv4) return sAddr ?: "127.0.0.1"
                    }
                }
            }
        } catch (ex: Exception) {
            // Ignore
        }
        return "127.0.0.1"
    }

    /** True if [ip] is loopback, unspecified, or bound to any local interface. (Moved from TransferDaemon.) */
    fun isMyOwnIp(ip: String?): Boolean {
        if (ip == null || ip == "127.0.0.1" || ip == "0.0.0.0") return true
        return ownAddresses().contains(ip)
    }

    // Cached snapshot of this host's own addresses, rebuilt at most once per TTL. isMyOwnIp runs on
    // the discovery receive path per datagram, and getNetworkInterfaces() is a heavyweight syscall;
    // the local address set changes on the order of minutes, not packets. On enumeration failure we
    // keep the last good snapshot rather than returning empty — failing open would re-list this
    // device as its own peer. Mirrors the Windows Net.ownAddresses cache.
    @Volatile private var ownIpCache: Set<String> = emptySet()
    @Volatile private var ownIpCacheAtMs: Long = 0L
    private const val OWN_IP_TTL_MS = 5_000L

    private fun ownAddresses(): Set<String> {
        val now = System.currentTimeMillis()
        val cached = ownIpCache
        if (cached.isNotEmpty() && now - ownIpCacheAtMs < OWN_IP_TTL_MS) return cached
        return try {
            val set = HashSet<String>()
            for (intf in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (addr in java.util.Collections.list(intf.inetAddresses)) {
                    addr.hostAddress?.let { set.add(it) }
                }
            }
            ownIpCache = set
            ownIpCacheAtMs = now
            set
        } catch (ex: Exception) {
            cached
        }
    }

    /** DHCP-derived + per-interface broadcast addresses, with 255.255.255.255 fallback. (Moved from TransferDaemon.) */
    fun getAllBroadcastAddresses(context: Context): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifiManager.dhcpInfo
            if (dhcp != null && dhcp.ipAddress != 0) {
                val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                val quads = ByteArray(4)
                for (k in 0..3) quads[k] = ((broadcast shr k * 8) and 0xFF).toByte()
                addresses.add(InetAddress.getByAddress(quads))
            }
        } catch (e: Exception) {}

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in java.util.Collections.list(interfaces)) {
                if (intf.isLoopback || !intf.isUp) continue
                for (address in intf.interfaceAddresses) {
                    val broadcast = address.broadcast
                    if (broadcast != null && !addresses.contains(broadcast)) {
                        addresses.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {}

        // Add a global broadcast fallback just in case
        try {
            val globalBroadcast = InetAddress.getByName("255.255.255.255")
            if (!addresses.contains(globalBroadcast)) {
                addresses.add(globalBroadcast)
            }
        } catch (e: Exception) {}

        if (addresses.isEmpty()) {
            try { addresses.add(InetAddress.getByName("255.255.255.255")) } catch (e: Exception) {}
        }

        return addresses
    }

    fun sendUdp(ip: String, port: Int, payload: ByteArray): Boolean {
        return try {
            DatagramSocket().use { socket ->
                val packet = DatagramPacket(payload, payload.size, InetAddress.getByName(ip), port)
                socket.send(packet)
            }
            true
        } catch (e: Exception) {
            Log.w("Net", "sendUdp to $ip:$port failed: ${e.message}")
            false
        }
    }
}

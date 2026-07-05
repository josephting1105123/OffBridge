package com.joseph.clipboard.windows.network

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object Ports {
    const val DISCOVERY = 50001
    const val CLIPBOARD = 65432
    const val FILE_TRANSFER = 65433
    const val PAIRING = 65435
}

object Net {
    /** Connect, send UTF-8 payload (caller includes trailing \n), flush, close. Returns true on success. */
    fun sendString(ip: String, port: Int, payload: String, connectTimeoutMs: Int = 3000): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), connectTimeoutMs)
                // Bound the exchange so a peer that accepts then stops reading (TCP zero
                // window) can't block the write forever and burn a Dispatchers.IO thread.
                socket.soTimeout = connectTimeoutMs
                socket.getOutputStream().run {
                    write(payload.toByteArray(Charsets.UTF_8))
                    flush()
                }
            }
            true
        } catch (e: Exception) {
            System.err.println("Net.sendString to $ip:$port failed: ${e.message}")
            false
        }
    }

    /**
     * Best site-local IPv4 of a real (up, non-loopback, non-virtual) network
     * interface, for the off-grid QR `lan` field. Excludes the Wi-Fi Direct GO
     * subnet (192.168.49.x). Returns null when the machine has no usable LAN IPv4.
     *
     * [InetAddress.getLocalHost] is unreliable on Windows (loopback / wrong
     * adapter), so the off-grid path must resolve the station IP explicitly.
     */
    fun localLanIpv4(): String? {
        // Prefer the adapter backing the default route — the network the scanning phone
        // most likely shares. A UDP "connect" makes the OS pick the outbound source IP
        // without sending any packets, sidestepping VPN/virtual adapters that the
        // NetworkInterface scan can't reliably tell apart (isVirtual under-reports on Windows).
        routeLocalIpv4()?.let { return it }
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter { !it.startsWith("127.") && !it.startsWith("192.168.49.") }
                .toList()
            candidates.firstOrNull { isSiteLocalIpv4(it) } ?: candidates.firstOrNull()
        } catch (e: Exception) {
            System.err.println("Net.localLanIpv4 failed: ${e.message}")
            null
        }
    }

    /** OS-chosen outbound source IPv4 for the default route (no packets are actually sent). */
    private fun routeLocalIpv4(): String? {
        return try {
            java.net.DatagramSocket().use { sock ->
                sock.connect(InetSocketAddress("8.8.8.8", 53))
                (sock.localAddress as? Inet4Address)?.hostAddress
                    ?.takeIf { !it.startsWith("127.") && !it.startsWith("192.168.49.") && isSiteLocalIpv4(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isSiteLocalIpv4(ip: String): Boolean {
        return ip.startsWith("10.") ||
                ip.startsWith("192.168.") ||
                (ip.startsWith("172.") && (ip.substringAfter('.').substringBefore('.').toIntOrNull() ?: 0) in 16..31)
    }

    /**
     * True when [ip] is one of THIS host's own addresses — any address on any interface, plus the
     * loopback/wildcard literals. Used to drop our own looped-back discovery broadcast.
     *
     * A single "primary" IP ([localLanIpv4]) is not enough: a PC with more than one active adapter
     * (Wi-Fi + Ethernet, or a Hyper-V / WSL / VMware / VPN virtual adapter holding a site-local IP)
     * can receive its own 255.255.255.255 broadcast with a source IP that egressed a *different*
     * adapter than the one we advertise, so a single-IP compare misses and the PC lists itself.
     * Enumerating every local address closes that gap. Mirrors Android's Net.isMyOwnIp.
     */
    fun isMyOwnIp(ip: String?): Boolean {
        if (ip == null || ip == "127.0.0.1" || ip == "0.0.0.0") return true
        return ownAddresses().contains(ip)
    }

    // Cached snapshot of this host's own addresses. getNetworkInterfaces() is a heavyweight native
    // call (tens of ms on adapter-heavy Windows boxes — Hyper-V/WSL/VPN) and isMyOwnIp runs on the
    // discovery receive path for every incoming datagram, so enumerate at most once per TTL. The
    // local address set changes on the order of minutes, not packets.
    @Volatile private var ownIpCache: Set<String> = emptySet()
    @Volatile private var ownIpCacheAtMs: Long = 0L
    private const val OWN_IP_TTL_MS = 5_000L

    private fun ownAddresses(): Set<String> {
        val now = System.currentTimeMillis()
        val cached = ownIpCache
        if (cached.isNotEmpty() && now - ownIpCacheAtMs < OWN_IP_TTL_MS) return cached
        return try {
            val set = NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .mapNotNull { it.hostAddress }
                .toSet()
            ownIpCache = set
            ownIpCacheAtMs = now
            set
        } catch (e: Exception) {
            // Keep the last good snapshot rather than failing open: returning an empty set here would
            // let our own looped-back broadcast through and re-list this PC as its own peer during
            // adapter churn (VPN connect, Wi-Fi roam) — the exact bug this filter exists to prevent.
            cached
        }
    }

    /** Reads bytes until '\n' or EOF; returns UTF-8 string without the newline.
     *  1 MiB cap (~20k-file manifests) so large multi-file/folder OFFER manifests aren't truncated. */
    fun readLineUtf8(input: InputStream, maxBytes: Int = 1024 * 1024): String {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            baos.write(b)
            // Header/manifest lines are small; cap so a peer streaming bytes without a
            // newline can't grow this buffer until OutOfMemoryError.
            if (baos.size() > maxBytes) throw java.io.IOException("Header line exceeded $maxBytes bytes")
        }
        return String(baos.toByteArray(), Charsets.UTF_8)
    }
}

package com.joseph.clipboard.windows.network.quickshare

import com.joseph.clipboard.windows.storage.DiagLog
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Publishes the Quick Share mDNS service on every real IPv4 network, following changes of network
 * and device name. One-shot: [start] once, [stop] once. All JmDNS calls stay on its own thread
 * because registering and closing each take seconds.
 */
class QuickShareAdvertiser(private val port: Int, private val deviceName: () -> String) {
    private val wake = LinkedBlockingQueue<Unit>()
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "quickshare-mdns").apply {
            isDaemon = true
            start()
        }
    }

    /** Re-reads the device name and the network list now instead of at the next interval. */
    fun refresh() {
        wake.offer(Unit)
    }

    /** Returns quickly; the thread finishes sending the mDNS goodbye on its own. */
    fun stop(joinMs: Long) {
        running = false
        wake.offer(Unit)
        runCatching { thread?.join(joinMs) }
        thread = null
    }

    private fun loop() {
        val published = HashMap<InetAddress, JmDNS>()
        val failed = HashSet<InetAddress>() // so a persistent failure is logged once
        var publishedName: String? = null
        var endpointId = QuickShareEndpoint.randomEndpointId()
        try {
            while (running) {
                try {
                    val name = deviceName().ifBlank { FALLBACK_NAME }
                    if (name != publishedName) {
                        published.values.forEach(::close)
                        published.clear()
                        // A new id, so no phone keeps showing the old name from its cache.
                        endpointId = QuickShareEndpoint.randomEndpointId()
                        publishedName = name
                    }
                    val current = lanAddresses()
                    val gone = published.keys - current
                    gone.forEach { address -> published.remove(address)?.let(::close) }
                    failed.retainAll(current)
                    for (address in current - published.keys) {
                        if (!running) break
                        try {
                            published[address] = publish(address, endpointId, name)
                            failed.remove(address)
                        } catch (e: Exception) {
                            if (failed.add(address)) {
                                DiagLog.w("QuickShare: mDNS advert failed on ${address.hostAddress}: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    DiagLog.w("QuickShare: mDNS update failed: ${e.message}")
                }
                val waitedFrom = System.currentTimeMillis()
                wake.poll(RESCAN_SECONDS, TimeUnit.SECONDS)
                wake.clear()
                // Far longer than asked for means the PC slept. The address may be unchanged while
                // the multicast socket behind it is not usable any more, so publish again.
                if (System.currentTimeMillis() - waitedFrom > SLEEP_GAP_MS) publishedName = null
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            published.values.forEach(::close)
        }
    }

    private fun publish(address: InetAddress, endpointId: ByteArray, name: String): JmDNS {
        // A private host name keeps clear of the one Windows' own mDNS responder answers for.
        val hostName = "offbridge-" + String(endpointId, Charsets.US_ASCII)
        val jmdns = JmDNS.create(address, hostName)
        try {
            val record = QuickShareEndpoint.base64Url(QuickShareEndpoint.endpointInfo(name))
            jmdns.registerService(
                ServiceInfo.create(
                    QuickShareEndpoint.SERVICE_TYPE,
                    QuickShareEndpoint.instanceName(endpointId),
                    port,
                    0,
                    0,
                    mapOf("n" to record),
                )
            )
        } catch (e: Exception) {
            close(jmdns)
            throw e
        }
        return jmdns
    }

    private fun close(jmdns: JmDNS) {
        runCatching { jmdns.unregisterAllServices() }
        runCatching { jmdns.close() }
    }

    private fun lanAddresses(): Set<InetAddress> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter {
                runCatching {
                    it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint && it.supportsMulticast()
                }.getOrDefault(false)
            }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLinkLocalAddress && !it.isLoopbackAddress }
            .toSet()

    private companion object {
        const val RESCAN_SECONDS = 10L
        const val SLEEP_GAP_MS = RESCAN_SECONDS * 3 * 1000
        const val FALLBACK_NAME = "OffBridge PC"
    }
}

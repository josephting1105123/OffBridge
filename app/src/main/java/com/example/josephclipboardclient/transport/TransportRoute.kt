package com.EntropicSoftwareLab.ClipboardShare.transport

import android.content.Context
import android.net.Network
import android.os.Build
import com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridPayload
import com.EntropicSoftwareLab.ClipboardShare.tuneForBulkTransfer
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transport abstraction so the handshake does not depend on a [Network]
 * (moved out of QrScannerFragment, §2 of the V4 plan). Implementations:
 * the WifiNetworkSpecifier network (T0/T3), a true Wi-Fi P2P join (T1/T2)
 * and a pre-connected Bluetooth link (T4).
 */
interface TransportRoute {
    /** Opens a connected duplex stream to [ip]:[port], routed over this transport. */
    @Throws(IOException::class)
    fun openConnection(ip: String, port: Int): TransportConnection

    /** Ordered sender-address candidates to try for this transport. */
    fun senderCandidates(payload: OffGridPayload): List<String>
}

/**
 * WifiNetworkSpecifier path (T0 and T3): every socket must come from the
 * joined network's factory — the OS does not route to a specifier network
 * by default.
 */
class SpecifierRoute(
    private val appContext: Context,
    private val network: Network
) : TransportRoute {

    override fun openConnection(ip: String, port: Int): TransportConnection {
        val socket = network.socketFactory.createSocket()
        try {
            socket.tuneForBulkTransfer(beforeConnect = true)
            socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw IOException("specifier connect to $ip:$port failed", e)
        }
        return SocketConnection(socket)
    }

    /**
     * The QR carries the legacy GO address (192.168.49.1), but newer Android
     * builds can randomize the group-owner IP. The network we joined knows the
     * real one — try its DHCP server, the route gateway and the .1 of our own
     * subnet before the payload address.
     */
    override fun senderCandidates(payload: OffGridPayload): List<String> {
        val candidates = linkedSetOf<String>()
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val lp = cm.getLinkProperties(network)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp?.dhcpServerAddress?.hostAddress?.let { candidates.add(it) }
            }
            lp?.routes?.forEach { route ->
                val gw = route.gateway
                if (gw is java.net.Inet4Address && !gw.isAnyLocalAddress) {
                    gw.hostAddress?.let { candidates.add(it) }
                }
            }
            lp?.linkAddresses?.forEach { la ->
                val addr = la.address
                if (addr is java.net.Inet4Address) {
                    addr.hostAddress?.let { own ->
                        candidates.add(own.substringBeforeLast('.') + ".1")
                    }
                }
            }
        } catch (_: Exception) {}
        candidates.add(payload.ip)
        return candidates.toList()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 7000
    }
}

/**
 * True Wi-Fi P2P join (T1, T2): there is no [Network] object, so bind the
 * socket's source address to the p2p interface — that selects the p2p route
 * on a multi-homed phone. Falls back to an unbound connect when no local
 * address is known. Target priority: group-owner address → payload.ip →
 * <p2p subnet>.1.
 */
class P2pRoute(
    private val groupOwnerAddress: InetAddress,
    private val localAddress: InetAddress?
) : TransportRoute {

    override fun openConnection(ip: String, port: Int): TransportConnection {
        // Re-resolve the p2p interface address on every attempt: right after the group forms the
        // client's p2p interface often has no IP/route yet (DHCP still running), so the address
        // captured at join time can be null or stale. Binding to the live p2p address is what gives
        // the socket a route to the group owner on a multi-homed phone (p2p + home Wi-Fi).
        val local = currentP2pLocalAddress() ?: localAddress
        if (local != null) {
            var bound: Socket? = null
            try {
                bound = Socket()
                bound.tuneForBulkTransfer(beforeConnect = true)
                bound.bind(InetSocketAddress(local, 0))
                bound.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                return SocketConnection(bound)
            } catch (_: Exception) {
                try { bound?.close() } catch (_: Exception) {}
                // Fall through to an unbound connect as a last resort.
            }
        }
        val socket = Socket()
        try {
            socket.tuneForBulkTransfer(beforeConnect = true)
            socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw IOException("p2p connect to $ip:$port failed", e)
        }
        return SocketConnection(socket)
    }

    override fun senderCandidates(payload: OffGridPayload): List<String> {
        val candidates = linkedSetOf<String>()
        groupOwnerAddress.hostAddress?.let { candidates.add(it) }
        candidates.add(payload.ip)
        (currentP2pLocalAddress() ?: localAddress)?.hostAddress?.let { local ->
            candidates.add(local.substringBeforeLast('.') + ".1")
        }
        return candidates.toList()
    }

    /** The live IPv4 on this device's p2p interface, or null until DHCP assigns one. */
    private fun currentP2pLocalAddress(): InetAddress? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.name?.startsWith("p2p") == true && it.isUp }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 7000
    }
}

/**
 * Bluetooth path (T4): the link is already connected when the route is built
 * (the receiver dialed the PSM/UUID during the tier attempt), so "opening" it
 * is a one-shot hand-over. IP candidates are meaningless on this carrier.
 */
class BtRoute(private val connection: TransportConnection) : TransportRoute {

    private val consumed = AtomicBoolean(false)

    override fun openConnection(ip: String, port: Int): TransportConnection {
        if (!consumed.compareAndSet(false, true)) {
            throw IOException("Bluetooth link already consumed; re-run the tier to reconnect")
        }
        return connection
    }

    override fun senderCandidates(payload: OffGridPayload): List<String> = listOf(BT_PSEUDO_ADDRESS)

    companion object {
        /** Pseudo-address: BtRoute ignores it, but the shared dial loop needs one entry. */
        const val BT_PSEUDO_ADDRESS = "bt:0"
    }
}

/**
 * Same-network LAN route: a plain TCP socket to the sender's LAN IP over the
 * receiver's default network (no Wi-Fi Direct, no Network binding). The socket
 * is already connected when this wraps it — reachability over the default
 * network is itself the "same network" check.
 */
class LanRoute(private val connection: TransportConnection) : TransportRoute {

    private val consumed = AtomicBoolean(false)

    override fun openConnection(ip: String, port: Int): TransportConnection {
        if (!consumed.compareAndSet(false, true)) {
            throw IOException("LAN link already consumed; re-run to reconnect")
        }
        return connection
    }

    override fun senderCandidates(payload: OffGridPayload): List<String> =
        listOfNotNull(payload.lanIp)
}

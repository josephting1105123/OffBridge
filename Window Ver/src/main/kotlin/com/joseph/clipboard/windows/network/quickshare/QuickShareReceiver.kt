package com.joseph.clipboard.windows.network.quickshare

import com.joseph.clipboard.windows.network.Ports
import com.joseph.clipboard.windows.network.WORKER_JOIN_TIMEOUT_MS
import com.joseph.clipboard.windows.storage.DiagLog
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Makes this PC a Quick Share target: a TCP server on a fixed port (so the installer's firewall
 * rule can name it) plus the mDNS advert that points phones at it. Receive only.
 */
class QuickShareReceiver(
    private val listener: QuickShareListener,
    private val deviceName: () -> String,
    private val saveDir: () -> File,
    private val reservedNames: () -> Set<String> = { emptySet() },
    private val requestedPort: Int = Ports.QUICK_SHARE,
    private val advertise: Boolean = true,
) {
    /** A live connection and the socket under it, which the owner may close to cancel. */
    private class Active(val connection: QuickShareConnection, val socket: Socket) {
        @Volatile var reader: Thread? = null
    }

    private val active = ConcurrentHashMap<String, Active>()
    // When each host last raised prompts that were never accepted. Survives stop/start.
    private val unaccepted = ConcurrentHashMap<InetAddress, List<Long>>()
    @Volatile private var serverSocket: ServerSocket? = null
    private var advertiser: QuickShareAdvertiser? = null
    private var watchdog: ScheduledExecutorService? = null
    private var acceptThread: Thread? = null

    @Volatile var lastBindError: String? = null
        private set

    val isAlive: Boolean get() = serverSocket?.isClosed == false

    /** The bound TCP port, or null while stopped. */
    val port: Int? get() = serverSocket?.takeUnless { it.isClosed }?.localPort

    fun socketFor(sessionId: String): Socket? = active[sessionId]?.socket

    fun connection(sessionId: String): QuickShareConnection? = active[sessionId]?.connection

    @Synchronized
    fun start() {
        if (isAlive) return
        stop() // clears out whatever a dead accept loop left behind
        val server = try {
            // No reuseAddress: on Windows it would let two processes share the port.
            ServerSocket().apply { bind(InetSocketAddress(requestedPort)) }
        } catch (e: Exception) {
            lastBindError = e.message ?: e.toString()
            DiagLog.w("QuickShare: bind of TCP $requestedPort failed (${e.message}); this PC is not visible to Quick Share")
            return
        }
        lastBindError = null
        serverSocket = server
        watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "quickshare-watchdog").apply { isDaemon = true }
        }.also {
            // Only ever closes sockets, so one stuck connection can't delay the others.
            it.scheduleWithFixedDelay(::closeOverdue, 1, 1, TimeUnit.SECONDS)
        }
        acceptThread = thread(isDaemon = true, name = "quickshare-accept") { acceptLoop(server) }
        if (advertise) {
            advertiser = QuickShareAdvertiser(server.localPort, deviceName).also { it.start() }
        }
    }

    @Synchronized
    fun stop() {
        val server = serverSocket
        serverSocket = null
        runCatching { server?.close() }
        advertiser?.stop(WORKER_JOIN_TIMEOUT_MS)
        advertiser = null
        watchdog?.shutdownNow()
        watchdog = null
        val open = active.values.toList()
        open.forEach {
            // Close only: telling the sender could block the caller (often the UI thread).
            it.connection.abort(QuickShareOutcome.Failed("Quick Share was turned off"))
        }
        // The readers delete their part files on the way out. They are daemon threads, so on app
        // exit they only get to if we wait. One deadline for all of them.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WORKER_JOIN_TIMEOUT_MS)
        open.forEach {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left > 0) runCatching { it.reader?.join(left) }
        }
        runCatching { acceptThread?.join(WORKER_JOIN_TIMEOUT_MS) }
        acceptThread = null
    }

    /** Publishes a changed device name. */
    @Synchronized
    fun refreshAdvertisement() {
        advertiser?.refresh()
    }

    private fun acceptLoop(server: ServerSocket) {
        try {
            while (!server.isClosed) {
                admit(server.accept())
            }
        } catch (e: Exception) {
            if (serverSocket === server) DiagLog.w("QuickShare: accept loop stopped: ${e.message}")
        } finally {
            runCatching { server.close() }
        }
    }

    private fun admit(socket: Socket) {
        val address: InetAddress = socket.inetAddress
        // Nothing is authenticated at this point, so limit how much one host, or all of them, can hold open.
        // A stranger who keeps raising prompts nobody accepts stops being heard for a while.
        if (recentUnaccepted(address).size >= MAX_UNACCEPTED_OFFERS || active.size >= MAX_CONNECTIONS ||
            active.values.count { it.socket.inetAddress == address } >= MAX_CONNECTIONS_PER_HOST
        ) {
            runCatching { socket.close() }
            return
        }
        val connection = try {
            socket.tcpNoDelay = true
            QuickShareConnection(
                input = BufferedInputStream(socket.getInputStream(), 64 * 1024),
                output = socket.getOutputStream(),
                closeTransport = { runCatching { socket.close() } },
                listener = countingListener(address),
                saveDirProvider = saveDir,
                reservedNames = reservedNames,
            )
        } catch (e: Exception) {
            runCatching { socket.close() }
            return
        }
        val entry = Active(connection, socket)
        active[connection.sessionId] = entry
        // A separate ticker, because a keep-alive write can block if the sender stops reading.
        val ticker = thread(isDaemon = true, name = "quickshare-keepalive") {
            try {
                while (!socket.isClosed) {
                    Thread.sleep(TICK_MS)
                    connection.tick()
                }
            } catch (_: InterruptedException) {
            }
        }
        val reader = thread(start = false, isDaemon = true, name = "quickshare-connection") {
            try {
                connection.run()
            } finally {
                ticker.interrupt()
                active.remove(connection.sessionId)
            }
        }
        entry.reader = reader
        reader.start()
    }

    private fun recentUnaccepted(host: InetAddress): List<Long> {
        val now = System.nanoTime()
        return unaccepted[host].orEmpty().filter { now - it < UNACCEPTED_WINDOW_NANOS }
    }

    /** [listener], plus a note of each offer from [host] that ended without being accepted. */
    private fun countingListener(host: InetAddress) = object : QuickShareListener {
        @Volatile private var offered = false

        override fun onOffer(connection: QuickShareConnection, offer: QuickShareOffer) {
            offered = true
            listener.onOffer(connection, offer)
        }

        override fun onProgress(connection: QuickShareConnection, progress: QuickShareProgress) =
            listener.onProgress(connection, progress)

        override fun onFinished(connection: QuickShareConnection, outcome: QuickShareOutcome) {
            if (connection.wasAccepted) {
                unaccepted.remove(host)
            } else if (offered && serverSocket != null) { // not when we are the ones shutting down
                unaccepted[host] = recentUnaccepted(host) + System.nanoTime()
            }
            listener.onFinished(connection, outcome)
        }
    }

    private fun closeOverdue() {
        active.values.forEach {
            // abort() records the outcome before closing the socket, so the reader thread can't
            // report the resulting I/O error first.
            if (it.connection.isOverdue()) runCatching { it.connection.abort(QuickShareOutcome.TimedOut) }
        }
    }

    private companion object {
        const val MAX_CONNECTIONS = 4
        const val MAX_CONNECTIONS_PER_HOST = 2
        const val TICK_MS = 1_000L
        const val MAX_UNACCEPTED_OFFERS = 3
        val UNACCEPTED_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(10)
    }
}

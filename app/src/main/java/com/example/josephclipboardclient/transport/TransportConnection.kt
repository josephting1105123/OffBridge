package com.EntropicSoftwareLab.ClipboardShare.transport

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Carrier-neutral duplex byte stream (§2 of the V4 plan): the download/upload
 * engine speaks this instead of a concrete socket so a future non-TCP carrier
 * can be dropped in without touching the protocol code.
 */
interface TransportConnection : Closeable {
    val input: InputStream
    val output: OutputStream

    /** Remote endpoint for logging/session bookkeeping (an IPv4 string). */
    val remoteIdentifier: String

    /** Best-effort read timeout; a carrier without timeout support may no-op. */
    fun setReadTimeout(ms: Int)
}

/** TCP carrier — the only carrier in use. */
class SocketConnection(private val socket: Socket) : TransportConnection {
    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()
    override val remoteIdentifier: String get() = socket.inetAddress?.hostAddress ?: ""

    override fun setReadTimeout(ms: Int) {
        try { socket.soTimeout = ms } catch (_: Exception) {}
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}

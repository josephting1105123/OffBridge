package com.EntropicSoftwareLab.ClipboardShare.transport

import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Carrier-neutral duplex byte stream (§2 of the V4 plan). The transfer
 * protocol bytes are identical over TCP and Bluetooth; only the carrier
 * differs. `BluetoothSocket` does not extend `java.net.Socket`, so the
 * engine speaks this interface instead.
 */
interface TransportConnection : Closeable {
    val input: InputStream
    val output: OutputStream

    /** Remote endpoint for logging/session bookkeeping: an IPv4 string or a BT MAC. */
    val remoteIdentifier: String

    /** Best-effort read timeout; carriers without timeout support no-op (BT). */
    fun setReadTimeout(ms: Int)
}

/** TCP carrier. */
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

/** Bluetooth carrier (L2CAP CoC or RFCOMM — both surface as BluetoothSocket). */
class BluetoothSocketConnection(private val socket: BluetoothSocket) : TransportConnection {
    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream
    override val remoteIdentifier: String get() = socket.remoteDevice?.address ?: "bt"

    /** BluetoothSocket exposes no read timeout; the engine's own watchdogs apply. */
    override fun setReadTimeout(ms: Int) {}

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}

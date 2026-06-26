package com.EntropicSoftwareLab.ClipboardShare.transport.control

/**
 * Raw, reliable, message-oriented control link (§4 of the V4 plan). The BLE
 * server and client implement this after GATT chunking/reassembly; the
 * [ControlSession] layered on top adds crypto, sequencing and liveness.
 *
 * Implementations deliver [onMessage]/[onClosed] on the main thread and
 * accept [send] from any thread.
 */
interface ControlChannel {
    /** Queues one complete frame for delivery. Silently drops after close. */
    fun send(frame: ByteArray)

    /** One complete reassembled frame from the peer. */
    var onMessage: ((ByteArray) -> Unit)?

    /** The underlying link died (disconnect, advertise failure, teardown). Fires once. */
    var onClosed: (() -> Unit)?

    fun close()
}

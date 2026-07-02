package com.joseph.clipboard.windows.network.control

import java.io.ByteArrayOutputStream

/**
 * Frame ↔ GATT-write chunking — port of the Android `transport.control.GattChunker`
 * (chunk layout pinned by ControlPlaneVectorTest on both platforms): each chunk is
 * `[idx: 1 byte rolling][flags: 1 byte, bit0 = END]` + at most MTU−3−2 bytes
 * of frame data. The ATT link is reliable and ordered, so reassembly is a
 * simple accumulate-until-END — O(L) per frame, no timers.
 */
internal object GattChunker {

    private const val HEADER = 2
    private const val FLAG_END = 0x01

    fun split(frame: ByteArray, mtu: Int): List<ByteArray> {
        val maxData = (mtu - 3 - HEADER).coerceAtLeast(1)
        val chunks = ArrayList<ByteArray>((frame.size / maxData) + 1)
        var offset = 0
        var idx = 0
        while (offset < frame.size || chunks.isEmpty()) {
            val n = minOf(maxData, frame.size - offset)
            val last = offset + n >= frame.size
            val chunk = ByteArray(HEADER + n)
            chunk[0] = (idx and 0xFF).toByte()
            chunk[1] = if (last) FLAG_END.toByte() else 0
            System.arraycopy(frame, offset, chunk, HEADER, n)
            chunks.add(chunk)
            offset += n
            idx++
        }
        return chunks
    }
}

/** Per-link reassembly state. Not thread-safe — confine to one callback thread. */
internal class GattReassembler(private val onFrame: (ByteArray) -> Unit) {

    private val buffer = ByteArrayOutputStream()
    // Sticky overflow flag. Once a frame overflows we must discard every
    // remaining chunk of it; otherwise post-reset chunks re-accumulate and the
    // END would emit a truncated/corrupt frame that passes the size cap.
    private var overflowed = false

    fun accept(chunk: ByteArray) {
        if (chunk.size < 2) return
        val isEnd = chunk[1].toInt() and 0x01 != 0
        if (overflowed) {
            // Drop chunks belonging to the overflowed frame; clear (without
            // emitting) only when its END arrives so the next frame starts clean.
            if (isEnd) overflowed = false
            return
        }
        buffer.write(chunk, 2, chunk.size - 2)
        if (isEnd) {
            val frame = buffer.toByteArray()
            buffer.reset()
            // Defensive cap: a malformed peer cannot balloon memory.
            if (frame.size <= MAX_FRAME_BYTES) onFrame(frame)
        } else if (buffer.size() > MAX_FRAME_BYTES) {
            buffer.reset()
            overflowed = true
        }
    }

    private companion object {
        const val MAX_FRAME_BYTES = 8 * 1024
    }
}

package com.joseph.clipboard.windows.network.control

import java.io.ByteArrayOutputStream

/**
 * Frame ↔ GATT-write chunking — port of the Android `transport.control.GattChunker`
 * (chunk layout pinned by ControlPlaneVectorTest on both platforms): each chunk is
 * `[idx: 1 byte rolling][flags: 1 byte, bit0 = END, bit1 = START]` + at most
 * MTU−3−2 bytes of frame data. The ATT link is reliable and ordered, so reassembly
 * is a simple accumulate-until-END — O(L) per frame, no timers.
 */
internal object GattChunker {

    private const val HEADER = 2
    private const val FLAG_END = 0x01

    /**
     * bit1 — the FIRST chunk of a frame. Additive on the wire: builds that predate it mask bit0
     * only, so they ignore it, and a peer that never sets it leaves reassembly behaving as before.
     * Index 0 cannot serve as the marker because the index wraps every 256 chunks.
     */
    private const val FLAG_START = 0x02

    /** Data bytes that fit the ATT minimum (MTU 23) — what the phone's retransmit path falls back to. */
    const val CONSERVATIVE_PAYLOAD = 18

    fun split(frame: ByteArray, mtu: Int, maxPayload: Int = Int.MAX_VALUE): List<ByteArray> {
        val maxData = (mtu - 3 - HEADER).coerceAtMost(maxPayload).coerceAtLeast(1)
        val chunks = ArrayList<ByteArray>((frame.size / maxData) + 1)
        var offset = 0
        var idx = 0
        while (offset < frame.size || chunks.isEmpty()) {
            val n = minOf(maxData, frame.size - offset)
            val last = offset + n >= frame.size
            val chunk = ByteArray(HEADER + n)
            chunk[0] = (idx and 0xFF).toByte()
            var flags = if (last) FLAG_END else 0
            if (idx == 0) flags = flags or FLAG_START
            chunk[1] = flags.toByte()
            System.arraycopy(frame, offset, chunk, HEADER, n)
            chunks.add(chunk)
            offset += n
            idx++
        }
        return chunks
    }
}

/**
 * Per-link reassembly state. Not thread-safe — confine to one callback thread.
 *
 * Mirrors the phone's reassembler, index validation included. That matters here because the phone
 * retransmits: its write watchdog re-sends a chunk whose completion callback never arrived, and its
 * HELLO_RX is re-sent whole if no ACK comes back. Blind concatenation turned either of those into a
 * corrupt frame that failed `ControlProtocol.parse` and vanished without a trace.
 *
 *  - an exact retransmit of the chunk just accepted is DROPPED;
 *  - a START chunk resyncs unconditionally, so a re-sent frame lands even when the previous attempt
 *    left this side mid-frame;
 *  - any other break in the sequence POISONS the frame — a hole is never mistaken for data.
 */
internal class GattReassembler(private val onFrame: (ByteArray) -> Unit) {

    private val buffer = ByteArrayOutputStream()
    // Sticky drop flag, set by an overflow OR a broken chunk sequence. Once a frame is unusable we
    // must discard every remaining chunk of it; otherwise post-reset chunks re-accumulate and the
    // END would emit a truncated/corrupt frame that passes the size cap.
    private var poisoned = false
    /** Index the next in-sequence chunk must carry (mod 256); 0 at every frame boundary. */
    private var expectedIdx = 0
    /** Index of the last chunk accepted into [buffer]; -1 before the first chunk of a frame. */
    private var lastIdx = -1

    fun accept(chunk: ByteArray) {
        if (chunk.size < 2) return
        val idx = chunk[0].toInt() and 0xFF
        val flags = chunk[1].toInt()
        val isEnd = flags and 0x01 != 0
        val isStart = flags and 0x02 != 0

        // The peer began a new frame: abandon whatever we were holding and resync here.
        if (isStart && (lastIdx != -1 || poisoned)) resetFrame()

        if (poisoned) {
            // Drop chunks belonging to the poisoned frame; clear (without emitting) only when its
            // END arrives so the next frame starts clean.
            if (isEnd) resetFrame()
            return
        }

        // Exact retransmit of the chunk we just took. Idempotent by construction: drop it.
        if (idx == lastIdx) return

        if (idx != expectedIdx) {
            // A hole, a stale chunk, or a reorder — the ATT link is ordered, so bytes were lost.
            buffer.reset()
            if (isEnd) resetFrame() else { poisoned = true; lastIdx = -1 }
            return
        }

        buffer.write(chunk, 2, chunk.size - 2)
        lastIdx = idx
        expectedIdx = (idx + 1) and 0xFF
        if (isEnd) {
            val frame = buffer.toByteArray()
            resetFrame()
            // Defensive cap: a malformed peer cannot balloon memory.
            if (frame.size <= MAX_FRAME_BYTES) onFrame(frame)
        } else if (buffer.size() > MAX_FRAME_BYTES) {
            buffer.reset()
            poisoned = true
            lastIdx = -1
        }
    }

    private fun resetFrame() {
        buffer.reset()
        poisoned = false
        expectedIdx = 0
        lastIdx = -1
    }

    private companion object {
        const val MAX_FRAME_BYTES = 8 * 1024
    }
}

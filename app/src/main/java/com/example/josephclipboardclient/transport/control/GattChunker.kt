package com.EntropicSoftwareLab.ClipboardShare.transport.control

import java.io.ByteArrayOutputStream

/**
 * Frame ↔ GATT-write chunking (§4): each chunk is
 * `[idx: 1 byte rolling][flags: 1 byte, bit0 = END, bit1 = START]` + at most
 * MTU−3−2 bytes of frame data. The ATT link is reliable and ordered, so
 * reassembly is a simple accumulate-until-END — O(L) per frame, no timers.
 */
internal object GattChunker {

    private const val HEADER = 2
    private const val FLAG_END = 0x01

    /**
     * bit1 — this is the FIRST chunk of a frame. Purely additive on the wire: every previous build
     * masks bit0 only, so an old peer ignores it and an old peer that never sets it leaves the new
     * reassembler behaving exactly as before. It exists because index 0 alone cannot mark a frame
     * start (the index wraps every 256 chunks), which made a retransmitted frame undeliverable
     * whenever the previous attempt left the peer mid-frame.
     */
    private const val FLAG_START = 0x02

    /**
     * The data bytes that fit in the ATT minimum (MTU 23 − 3 ATT header − 2 chunk header). Every
     * BLE stack must accept a write this size, so it's what a retransmit falls back to when a peer
     * appears to be swallowing MTU-sized writes.
     */
    const val CONSERVATIVE_PAYLOAD = 18

    /**
     * [maxPayload] caps the data bytes per chunk below what the MTU allows — used by the
     * conservative retransmit path. Leave it unset for the normal full-MTU split.
     */
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
 * The rolling index [GattChunker.split] writes into byte 0 is VALIDATED here. It used to be written
 * and never read, which made the transport silently lossy: a chunk that was dropped, duplicated or
 * reordered on the way in was concatenated straight into the byte stream, and the resulting frame
 * failed [ControlProtocol.parse]'s length check and was discarded without a trace — the sender then
 * sat in "awaiting HELLO" until its watchdog fired while the receiver waited for an ACK that could
 * never come. Reading the index turns all three into detectable events:
 *
 *  - an exact retransmit of the chunk just accepted is DROPPED, which is what makes the sender-side
 *    write watchdog's "a duplicate chunk is harmless" assumption actually true;
 *  - any other break in the sequence POISONS the frame, so a hole is never mistaken for data;
 *  - a poisoned frame is abandoned in full and reassembly resyncs at the next END boundary.
 */
internal class GattReassembler(
    /**
     * Called with a one-line reason whenever bytes are thrown away. Diagnostics only — the caller
     * logs it so a trace can tell "the peer's chunks never arrived" apart from "they arrived and
     * reassembly rejected them", which used to look identical from the outside.
     */
    private val onDropped: ((String) -> Unit)? = null,
    private val onFrame: (ByteArray) -> Unit
) {

    private val buffer = ByteArrayOutputStream()
    // Sticky drop flag, set by an overflow OR a broken chunk sequence. Once a frame is unusable we
    // must discard every remaining chunk of it; otherwise post-reset chunks re-accumulate and the
    // END would emit a truncated/corrupt frame that still passes the size cap.
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

        // A START chunk is an unconditional frame boundary: the peer has begun a new frame, so
        // whatever we were holding (a poisoned frame, or a half-delivered one whose sender gave up)
        // is abandoned and reassembly resyncs right here. Frame-level retransmission depends on
        // this — without it a retry that follows a truncated attempt is itself discarded.
        if (isStart && (lastIdx != -1 || poisoned)) {
            onDropped?.invoke("resynced on a new frame start, dropped ${buffer.size()} buffered byte(s)")
            resetFrame()
        }

        if (poisoned) {
            // Drop chunks belonging to the poisoned frame; clear (without emitting) only when its
            // END arrives so the next frame starts clean. Indices wrap every 256 chunks, so an
            // index of 0 is NOT a reliable frame start — only END is.
            if (isEnd) resetFrame()
            return
        }

        // Exact retransmit of the chunk we just took (the peer's write watchdog re-sent it because
        // no completion callback arrived). Idempotent by construction: drop it and keep going.
        if (idx == lastIdx) return

        if (idx != expectedIdx) {
            // A hole, a stale chunk, or a reorder. The frame cannot be recovered — the ATT link is
            // ordered, so this means bytes were genuinely lost, not merely delayed.
            onDropped?.invoke("chunk out of sequence (got idx=$idx, expected $expectedIdx) — frame discarded")
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
            else onDropped?.invoke("frame of ${frame.size} bytes exceeds the $MAX_FRAME_BYTES cap")
        } else if (buffer.size() > MAX_FRAME_BYTES) {
            onDropped?.invoke("frame exceeded the $MAX_FRAME_BYTES cap mid-stream")
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

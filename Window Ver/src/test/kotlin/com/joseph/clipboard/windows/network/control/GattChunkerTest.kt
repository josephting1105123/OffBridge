package com.joseph.clipboard.windows.network.control

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the phone's `transport.control.GattChunkerTest`.
 *
 * The chunk LAYOUT is pinned across platforms by [ControlPlaneVectorTest]; this covers the
 * reassembler's BEHAVIOUR, which that vector cannot reach — duplicate chunks, holes, and the
 * START-flag resync. This side needs it as much as the phone does: the phone retransmits (a write
 * whose completion callback never arrives is re-sent, and HELLO_RX is re-sent whole when no ACK
 * comes back), so every one of these sequences is something this reassembler will actually be fed.
 *
 * Until this file existed the Windows reassembler concatenated whatever arrived, which is how a
 * retransmitted chunk turned into a corrupt frame that failed ControlProtocol.parse and vanished
 * with nothing in the trace.
 */
class GattChunkerTest {

    private fun roundTrip(frame: ByteArray, mtu: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        GattChunker.split(frame, mtu).forEach { r.accept(it) }
        return out
    }

    @Test
    fun splitThenReassembleReturnsTheOriginalFrame() {
        val frame = ByteArray(500) { (it * 7).toByte() }
        val out = roundTrip(frame, mtu = 23)
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun firstChunkFlagsStartAndLastFlagsEnd() {
        val chunks = GattChunker.split(ByteArray(40) { it.toByte() }, mtu = 23)
        assertTrue("multi-chunk frame expected", chunks.size >= 3)
        assertEquals("first chunk must flag START", 0x02, chunks.first()[1].toInt() and 0x02)
        assertEquals("only the first chunk flags START", 0, chunks[1][1].toInt() and 0x02)
        assertTrue("last chunk must flag END", chunks.last()[1].toInt() and 0x01 != 0)

        val single = GattChunker.split("hi".toByteArray(), mtu = 23)
        assertEquals(1, single.size)
        assertEquals("a single-chunk frame is both START and END", 0x03, single[0][1].toInt() and 0x03)
    }

    @Test
    fun anExactRetransmitOfTheLastChunkIsIgnored() {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        val frame = ByteArray(60) { it.toByte() }
        val chunks = GattChunker.split(frame, mtu = 23)
        assertTrue(chunks.size >= 3)
        // The phone's write watchdog re-sends a chunk whose callback never arrived; the bytes are
        // already here, so the duplicate must not be appended a second time.
        r.accept(chunks[0])
        r.accept(chunks[0])
        chunks.drop(1).forEach { r.accept(it) }
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun aHoleInTheSequenceDiscardsTheFrameRatherThanEmittingCorruption() {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        val frame = ByteArray(80) { it.toByte() }
        val chunks = GattChunker.split(frame, mtu = 23)
        assertTrue(chunks.size >= 4)
        r.accept(chunks[0])
        // chunks[1] is lost; the rest arrive, END included.
        chunks.drop(2).forEach { r.accept(it) }
        assertTrue("a frame with a hole must never be emitted", out.isEmpty())
        // …and the reassembler resyncs for the next frame rather than staying poisoned.
        val next = ByteArray(30) { (it + 1).toByte() }
        GattChunker.split(next, mtu = 23).forEach { r.accept(it) }
        assertEquals(1, out.size)
        assertArrayEquals(next, out[0])
    }

    @Test
    fun retransmittedFrameLandsAfterATruncatedAttempt() {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        val frame = ByteArray(60) { it.toByte() }
        val chunks = GattChunker.split(frame, mtu = 23)
        assertTrue(chunks.size >= 3)
        // Attempt 1 dies part-way through — this side is left mid-frame with no END in sight.
        chunks.dropLast(2).forEach { r.accept(it) }
        assertTrue(out.isEmpty())
        // Attempt 2 re-sends the whole frame. Without START it would be discarded as out-of-sequence.
        chunks.forEach { r.accept(it) }
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun retransmitAtASmallerChunkSizeStillReassembles() {
        // The phone's retry path re-chunks to the ATT minimum, so the two attempts have different
        // chunk counts and boundaries; the second must not be read as a continuation of the first.
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        val frame = ByteArray(600) { (it * 3).toByte() }
        val big = GattChunker.split(frame, mtu = 247)
        assertTrue("need a multi-chunk first attempt", big.size >= 2)
        big.dropLast(1).forEach { r.accept(it) }
        assertTrue(out.isEmpty())
        val small = GattChunker.split(frame, mtu = 247, maxPayload = GattChunker.CONSERVATIVE_PAYLOAD)
        assertTrue("the conservative split must produce more chunks", small.size > 1)
        small.forEach { r.accept(it) }
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun conservativePayloadCapsChunksAtTheAttMinimum() {
        val chunks = GattChunker.split(
            ByteArray(100), mtu = 512, maxPayload = GattChunker.CONSERVATIVE_PAYLOAD
        )
        chunks.forEach {
            assertTrue("chunk of ${it.size} B exceeds a 20-byte ATT write", it.size <= 20)
        }
        // …and the cap never enlarges a chunk the MTU already limits below it.
        val tiny = GattChunker.split(ByteArray(100), mtu = 8, maxPayload = GattChunker.CONSERVATIVE_PAYLOAD)
        tiny.forEach { assertTrue(it.size <= 5) } // mtu 8 -> 3 data bytes + 2 header
    }

    @Test
    fun indexWrapsPastByteBoundaryWithoutBreakingReassembly() {
        // maxData = 1 at mtu 4, so a 600-byte frame is 600 chunks and the index wraps twice.
        val frame = ByteArray(600) { (it % 251).toByte() }
        val chunks = GattChunker.split(frame, mtu = 4)
        assertEquals(600, chunks.size)
        val out = roundTrip(frame, mtu = 4)
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun aRetransmittedSingleChunkFrameIsDeliveredOnce() {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        val frame = ByteArray(12) { (it + 1).toByte() }
        val chunks = GattChunker.split(frame, mtu = 247)
        assertEquals(1, chunks.size)
        r.accept(chunks[0])
        r.accept(chunks[0]) // the write watchdog re-sent it after a lost completion callback
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun aRetransmittedEndChunkOfAMultiChunkFrameIsDroppedAndTheNextFrameStillLands() {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        val first = ByteArray(60) { it.toByte() }
        val second = ByteArray(30) { (100 + it).toByte() }
        val chunks = GattChunker.split(first, mtu = 23)
        chunks.forEach { r.accept(it) }
        r.accept(chunks.last())
        GattChunker.split(second, mtu = 23).forEach { r.accept(it) }
        assertEquals(2, out.size)
        assertArrayEquals(first, out[0])
        assertArrayEquals(second, out[1])
    }

    @Test
    fun identicalFramesSeparatedByAnotherFrameAreBothDelivered() {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        val a = ByteArray(8) { 7 }
        val b = ByteArray(8) { 9 }
        listOf(a, b, a).forEach { f -> GattChunker.split(f, mtu = 247).forEach { r.accept(it) } }
        assertEquals(3, out.size)
        assertArrayEquals(a, out[2])
    }
}

package com.EntropicSoftwareLab.ClipboardShare.transport.control

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pure-JVM coverage for the BLE GATT frame chunking/reassembly (§4 control channel).
 * This is the "fuzz the framing for truncation / overflow / garbage" guard: split() and
 * GattReassembler must round-trip any frame and never crash or emit a corrupt frame on
 * malformed input. No Android dependencies, so it runs with `./gradlew testDebugUnitTest`.
 */
class GattChunkerTest {

    private val MAX_FRAME = 8 * 1024  // mirrors GattReassembler.MAX_FRAME_BYTES (private)

    private fun roundTrip(frame: ByteArray, mtu: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        GattChunker.split(frame, mtu).forEach { r.accept(it) }
        return out
    }

    @Test
    fun emptyFrameProducesOneEndChunkAndRoundTrips() {
        val chunks = GattChunker.split(ByteArray(0), mtu = 23)
        assertEquals(1, chunks.size)
        assertTrue("only chunk must carry END", chunks[0][1].toInt() and 0x01 != 0)
        val out = roundTrip(ByteArray(0), 23)
        assertEquals(1, out.size)
        assertArrayEquals(ByteArray(0), out[0])
    }

    @Test
    fun splitFlagsEndOnLastChunkOnly() {
        val frame = ByteArray(10) { it.toByte() }
        val chunks = GattChunker.split(frame, mtu = 8) // maxData = 8-3-2 = 3 -> 4 chunks (3,3,3,1)
        assertEquals(4, chunks.size)
        chunks.dropLast(1).forEach { assertEquals("non-final chunk must not flag END", 0, it[1].toInt() and 0x01) }
        assertTrue("final chunk must flag END", chunks.last()[1].toInt() and 0x01 != 0)
    }

    @Test
    fun roundTripsAcrossSizesAndMtus() {
        val sizes = intArrayOf(0, 1, 2, 3, 4, 5, 16, 17, 511, 512, 513, 4096, MAX_FRAME)
        val mtus = intArrayOf(3, 4, 5, 6, 23, 64, 185, 512) // includes sub-header MTUs (coerced to 1 data byte)
        for (size in sizes) for (mtu in mtus) {
            val frame = ByteArray(size) { (it * 31 + mtu).toByte() }
            val out = roundTrip(frame, mtu)
            assertEquals("size=$size mtu=$mtu should emit exactly one frame", 1, out.size)
            assertArrayEquals("size=$size mtu=$mtu must round-trip byte-for-byte", frame, out[0])
        }
    }

    @Test
    fun fuzzRandomFramesRoundTrip() {
        val rnd = Random(0xC0FFEE)
        repeat(500) {
            val frame = ByteArray(rnd.nextInt(0, MAX_FRAME + 1)).also { rnd.nextBytes(it) }
            val mtu = rnd.nextInt(3, 600)
            val out = roundTrip(frame, mtu)
            assertEquals(1, out.size)
            assertArrayEquals(frame, out[0])
        }
    }

    @Test
    fun oversizedFrameIsDroppedNotEmitted() {
        val tooBig = ByteArray(MAX_FRAME + 1) { 1 }
        val out = roundTrip(tooBig, mtu = 23)
        assertTrue("a frame over the cap must never be emitted", out.isEmpty())
    }

    @Test
    fun reassemblerRecoversAfterOverflow() {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        // Feed an over-cap frame (dropped), then a normal frame (must still emit cleanly).
        GattChunker.split(ByteArray(MAX_FRAME + 1) { 7 }, mtu = 23).forEach { r.accept(it) }
        assertTrue(out.isEmpty())
        val good = "recovered".toByteArray()
        GattChunker.split(good, mtu = 23).forEach { r.accept(it) }
        assertEquals(1, out.size)
        assertArrayEquals(good, out[0])
    }

    @Test
    fun shortChunksAreDroppedSafely() {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        r.accept(ByteArray(0))           // length 0
        r.accept(byteArrayOf(0x00))      // length 1 (no flags byte)
        assertTrue("sub-header chunks must be ignored, not crash", out.isEmpty())
    }

    @Test
    fun garbageNonTerminatedStreamNeverEmits() {
        val rnd = Random(42)
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        repeat(50) {
            val junk = ByteArray(rnd.nextInt(2, 40)).also { rnd.nextBytes(it) }
            junk[1] = 0 // force flags = not-END
            r.accept(junk)
        }
        assertTrue("a stream that never flags END must not emit", out.isEmpty())
    }

    @Test
    fun singleEndChunkEmitsItsPayload() {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        // Index 0 = the first chunk of a frame, END = also the last.
        r.accept(byteArrayOf(0x00, 0x01, 'h'.code.toByte(), 'i'.code.toByte()))
        assertEquals(1, out.size)
        assertArrayEquals(byteArrayOf('h'.code.toByte(), 'i'.code.toByte()), out[0])
    }

    // ---------------------------------------------------------------- index validation
    // The rolling index in byte 0 used to be written by split() and ignored by the reassembler, so a
    // chunk that was lost, duplicated or reordered on the GATT link was concatenated straight into
    // the frame. The corrupt result failed ControlProtocol.parse()'s length check and vanished
    // without a trace — the HELLO_RX/HELLO_ACK stand-off. These pin the index down.

    @Test
    fun loneEndChunkWithWrongIndexIsRejected() {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        // Index 5 with no preceding 0..4: a fragment of someone else's frame, not a frame.
        r.accept(byteArrayOf(0x05, 0x01, 'h'.code.toByte(), 'i'.code.toByte()))
        assertTrue("an out-of-sequence chunk must never be emitted as a frame", out.isEmpty())
    }

    @Test
    fun droppedChunkPoisonsTheFrameInsteadOfCorruptingIt() {
        val frame = ByteArray(60) { it.toByte() }
        val chunks = GattChunker.split(frame, mtu = 23) // maxData 18 -> 4 chunks
        assertTrue("need a multi-chunk frame for this test", chunks.size >= 3)
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        chunks.filterIndexed { i, _ -> i != 1 }.forEach { r.accept(it) }
        assertTrue("a frame missing a chunk must be dropped, not silently truncated", out.isEmpty())
    }

    @Test
    fun duplicatedChunkIsIgnoredAndFrameStillRoundTrips() {
        // This is the property the sender-side write watchdog depends on: when it re-sends a chunk
        // whose completion callback never arrived, the peer must absorb the retransmit.
        val frame = ByteArray(100) { (it * 7).toByte() }
        val chunks = GattChunker.split(frame, mtu = 23)
        assertTrue(chunks.size >= 3)
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        chunks.forEachIndexed { i, c ->
            r.accept(c)
            if (i == 1) r.accept(c)  // exact retransmit of the chunk just accepted
        }
        assertEquals(1, out.size)
        assertArrayEquals("a duplicate chunk must not shift the payload", frame, out[0])
    }

    @Test
    fun reorderedChunksAreRejected() {
        val frame = ByteArray(60) { it.toByte() }
        val chunks = GattChunker.split(frame, mtu = 23).toMutableList()
        assertTrue(chunks.size >= 3)
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        java.util.Collections.swap(chunks, 1, 2)
        chunks.forEach { r.accept(it) }
        assertTrue("out-of-order chunks must not be assembled into a frame", out.isEmpty())
    }

    @Test
    fun resyncsOnTheNextFrameAfterALostChunk() {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        val broken = GattChunker.split(ByteArray(60) { it.toByte() }, mtu = 23)
        broken.filterIndexed { i, _ -> i != 1 }.forEach { r.accept(it) }
        assertTrue(out.isEmpty())
        val good = "the next frame must still get through".toByteArray()
        GattChunker.split(good, mtu = 23).forEach { r.accept(it) }
        assertEquals(1, out.size)
        assertArrayEquals(good, out[0])
    }

    // ------------------------------------------------------- retransmission / START resync
    // The HELLO handshake re-sends its frame when no ACK comes back. A retry has to land even when
    // the abandoned attempt left the peer holding half a frame, which index validation alone can't
    // do: index 0 is not a frame start (it wraps every 256 chunks). Hence the START flag.

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
    fun retransmittedFrameLandsAfterATruncatedAttempt() {
        val out = ArrayList<ByteArray>()
        val r = GattReassembler { out.add(it) }
        val frame = ByteArray(60) { it.toByte() }
        val chunks = GattChunker.split(frame, mtu = 23)
        assertTrue(chunks.size >= 3)
        // Attempt 1 dies part-way through — the peer is left mid-frame with no END in sight.
        chunks.dropLast(2).forEach { r.accept(it) }
        assertTrue(out.isEmpty())
        // Attempt 2 re-sends the whole frame. Without START it would be discarded as out-of-sequence.
        chunks.forEach { r.accept(it) }
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun retransmitAtASmallerChunkSizeStillReassembles() {
        // The retry path re-chunks to the ATT minimum, so the two attempts have different chunk
        // counts and boundaries; the second must not be read as a continuation of the first.
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

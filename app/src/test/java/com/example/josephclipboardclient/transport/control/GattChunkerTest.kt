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
        r.accept(byteArrayOf(0x05, 0x01, 'h'.code.toByte(), 'i'.code.toByte()))
        assertEquals(1, out.size)
        assertArrayEquals(byteArrayOf('h'.code.toByte(), 'i'.code.toByte()), out[0])
    }
}

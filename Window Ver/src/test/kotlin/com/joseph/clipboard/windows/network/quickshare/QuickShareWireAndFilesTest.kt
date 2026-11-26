package com.joseph.clipboard.windows.network.quickshare

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.util.Base64

class QuickShareWireAndFilesTest {

    @get:Rule val folder = TemporaryFolder()

    // --- framing -----------------------------------------------------------------------------

    @Test
    fun framesRoundTripWithABigEndianLength() {
        val out = ByteArrayOutputStream()
        val big = ByteArray(70_000) { it.toByte() }
        QuickShareFraming.write(out, byteArrayOf(5, 6, 7))
        QuickShareFraming.write(out, big)
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 5, 6, 7), out.toByteArray().copyOf(7))
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals(byteArrayOf(5, 6, 7), QuickShareFraming.read(input, 100_000))
        assertArrayEquals(big, QuickShareFraming.read(input, 100_000))
        assertNull(QuickShareFraming.read(input, 100_000))
    }

    @Test
    fun rejectsTruncatedFrames() {
        listOf(byteArrayOf(0), byteArrayOf(0, 0, 0), byteArrayOf(0, 0, 0, 4, 1, 2)).forEach { data ->
            assertThrows(EOFException::class.java) { QuickShareFraming.read(ByteArrayInputStream(data), 100) }
        }
    }

    @Test
    fun rejectsBadLengthsBeforeAllocating() {
        listOf(
            byteArrayOf(0, 0, 0, 0), // empty
            byteArrayOf(0, 0, 0, 101), // over the cap
            byteArrayOf(0x7f, -1, -1, -1), // 2 GiB
            byteArrayOf(-1, -1, -1, -1), // negative
        ).forEach { header ->
            assertThrows(QuickShareProtocolException::class.java) {
                QuickShareFraming.read(ByteArrayInputStream(header), 100)
            }
        }
    }

    // --- advert and endpoint info ------------------------------------------------------------

    @Test
    fun instanceNameMatchesTheNearbyLayout() {
        assertEquals("I2FiY2T8n14AAA", QuickShareEndpoint.instanceName("abcd".toByteArray()))
        assertEquals(4, QuickShareEndpoint.randomEndpointId().size)
    }

    @Test
    fun endpointInfoCarriesAVisibleLaptopAndItsName() {
        val info = QuickShareEndpoint.endpointInfo("Joseph's PC – ✓")
        assertEquals(6, info[0].toInt()) // device type 3 (laptop) << 1, hidden bit clear
        assertEquals(info.size - 18, info[17].toInt())
        assertEquals("Joseph's PC – ✓", QuickShareEndpoint.parseDeviceName(info))
        // The whole base64 TXT entry "n=..." has to fit in 255 bytes.
        val longest = QuickShareEndpoint.endpointInfo("名".repeat(500))
        assertTrue(2 + Base64.getUrlEncoder().withoutPadding().encodeToString(longest).length <= 255)
    }

    @Test
    fun longNamesAreCutBetweenCharacters() {
        val name = "📱".repeat(100) // 4 bytes and 2 UTF-16 units each
        val parsed = QuickShareEndpoint.parseDeviceName(QuickShareEndpoint.endpointInfo(name))
        assertEquals("📱".repeat(QuickShareEndpoint.MAX_NAME_BYTES / 4), parsed)
    }

    @Test
    fun unreadableEndpointInfoGivesNoName() {
        assertNull(QuickShareEndpoint.parseDeviceName(ByteArray(0)))
        assertNull(QuickShareEndpoint.parseDeviceName(ByteArray(17)))
        val hidden = QuickShareEndpoint.endpointInfo("x").also { it[0] = (it[0].toInt() or 0x10).toByte() }
        assertNull(QuickShareEndpoint.parseDeviceName(hidden))
        val lying = QuickShareEndpoint.endpointInfo("abc").also { it[17] = 100 }
        assertNull(QuickShareEndpoint.parseDeviceName(lying))
    }

    @Test
    fun senderNamesAreMadeSafeToShow() {
        assertEquals("Pixelgnp.exe", QuickShareEndpoint.displayName("Pi‮xel\n\u0007gnp.exe⁦"))
        assertEquals("Quick Share", QuickShareEndpoint.displayName("  ​ "))
        assertEquals("Quick Share", QuickShareEndpoint.displayName(null))
        assertEquals(64, QuickShareEndpoint.displayName("a".repeat(500)).length)
    }

    // --- incoming file names -----------------------------------------------------------------

    @Test
    fun wireFileNamesAreSanitized() {
        assertEquals("evil.exe", QuickShareFiles.safeName("..\\..\\Startup\\evil.exe"))
        assertEquals("passwd", QuickShareFiles.safeName("/etc/passwd"))
        assertEquals("received_file", QuickShareFiles.safeName(".."))
        assertEquals("received_file", QuickShareFiles.safeName(""))
        assertEquals("_CON.txt", QuickShareFiles.safeName("CON.txt"))
        assertEquals("_nul.tar.gz", QuickShareFiles.safeName("nul.tar.gz"))
        assertEquals("a.exe", QuickShareFiles.safeName("a.exe. . ."))
        assertEquals("invoicefdp.exe", QuickShareFiles.safeName("invoice‮fdp.exe"))
        assertEquals("abc", QuickShareFiles.safeName("a:b<>|?*\"c\u0001"))
        val long = QuickShareFiles.safeName("é".repeat(400) + ".jpg")
        assertTrue(long.toByteArray().size <= 200 && long.endsWith(".jpg"))
    }

    @Test
    fun finalizingNeverReplacesAnExistingFile() {
        val dir = folder.newFolder()
        File(dir, "photo.jpg").writeText("mine")
        val saved = QuickShareFiles.finalizeFile(part(dir, "theirs"), dir, "photo.jpg")
        assertEquals("photo (1).jpg", saved.name)
        assertEquals("theirs", saved.readText())
        assertEquals("mine", File(dir, "photo.jpg").readText())
        assertEquals("photo (2).jpg", QuickShareFiles.finalizeFile(part(dir, "again"), dir, "photo.jpg").name)
    }

    @Test
    fun finalizingSkipsNamesOtherTransfersHold() {
        val dir = folder.newFolder()
        File(dir, "a.txt.part").writeText("a paired device is writing this")
        val saved = QuickShareFiles.finalizeFile(part(dir, "x"), dir, "a.txt", reserved = setOf("A (1).TXT"))
        assertEquals("a (2).txt", saved.name)
    }

    @Test
    fun suffixesKeepDotFilesAndExtensionlessNamesIntact() {
        val dir = folder.newFolder()
        File(dir, ".env").writeText("")
        File(dir, "README").writeText("")
        assertEquals(".env (1)", QuickShareFiles.finalizeFile(part(dir, ""), dir, ".env").name)
        assertEquals("README (1)", QuickShareFiles.finalizeFile(part(dir, ""), dir, "README").name)
    }

    @Test
    fun finalizingRefusesToLeaveTheSaveFolder() {
        val dir = folder.newFolder()
        assertThrows(java.io.IOException::class.java) {
            QuickShareFiles.finalizeFile(part(dir, "x"), dir, "..\\escaped.txt")
        }
        assertTrue(!File(dir.parentFile, "escaped.txt").exists())
    }

    private fun part(dir: File, content: String) = QuickShareFiles.createPartFile(dir).apply { writeText(content) }
}

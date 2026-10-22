package com.EntropicSoftwareLab.ClipboardShare

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * XP-DEF-03: the receiver reduces a manifest-supplied filename to a safe on-disk leaf before it ever
 * hits the filesystem. Pure host-JVM test (no Android API touched by the function). The expected
 * outputs match the Windows FileStreamer.sanitizeIncomingFileName so both receivers agree.
 */
class FileNameSanitizeTest {

    @Test
    fun stripsPathTraversalComponents() {
        assertEquals("evil.txt", TransferUtils.sanitizeIncomingFileName("../../etc/evil.txt"))
        assertEquals("baz.png", TransferUtils.sanitizeIncomingFileName("foo\\bar\\baz.png"))
        assertEquals("leaf", TransferUtils.sanitizeIncomingFileName("/nested/dir/leaf"))
    }

    @Test
    fun degenerateNamesFallBackToReceivedFile() {
        assertEquals("received_file", TransferUtils.sanitizeIncomingFileName(".."))
        assertEquals("received_file", TransferUtils.sanitizeIncomingFileName("."))
        assertEquals("received_file", TransferUtils.sanitizeIncomingFileName(""))
        assertEquals("received_file", TransferUtils.sanitizeIncomingFileName("   "))
    }

    @Test
    fun removesReservedAndControlCharacters() {
        // The reserved set and control chars (below 0x20) are stripped; an ordinary space is kept.
        assertEquals("abcd.txt", TransferUtils.sanitizeIncomingFileName("a<b>c:d.txt"))
        assertEquals("abc.txt", TransferUtils.sanitizeIncomingFileName("a\tbc.txt"))
        assertEquals("re port.pdf", TransferUtils.sanitizeIncomingFileName("re port.pdf"))
    }

    @Test
    fun prefixesWindowsReservedDeviceNames() {
        assertEquals("_CON.txt", TransferUtils.sanitizeIncomingFileName("CON.txt"))
        assertEquals("_nul", TransferUtils.sanitizeIncomingFileName("nul"))
        assertEquals("_COM1.log", TransferUtils.sanitizeIncomingFileName("COM1.log"))
    }

    @Test
    fun leavesOrdinaryNamesUnchanged() {
        assertEquals("photo.jpg", TransferUtils.sanitizeIncomingFileName("photo.jpg"))
        assertEquals("My Report (1).pdf", TransferUtils.sanitizeIncomingFileName("My Report (1).pdf"))
    }

    @Test
    fun reservedNameCheckUsesTextBeforeFirstDot() {
        assertEquals("_NUL.tar.gz", TransferUtils.sanitizeIncomingFileName("NUL.tar.gz"))
        assertEquals("_CON .txt", TransferUtils.sanitizeIncomingFileName("CON .txt"))
        assertEquals("CONSOLE.txt", TransferUtils.sanitizeIncomingFileName("CONSOLE.txt"))
    }

    @Test
    fun stripsTrailingDotsSpacesAndBidiControls() {
        assertEquals("evil.exe", TransferUtils.sanitizeIncomingFileName("evil.exe. . "))
        assertEquals("received_file", TransferUtils.sanitizeIncomingFileName("..."))
        assertEquals("invoicefdp.exe", TransferUtils.sanitizeIncomingFileName("invoice‮fdp.exe"))
    }

    @Test
    fun capsLongNamesAndKeepsExtension() {
        val out = TransferUtils.sanitizeIncomingFileName("a".repeat(400) + ".pdf")
        assertEquals(200, out.toByteArray(Charsets.UTF_8).size)
        assertEquals(true, out.endsWith(".pdf"))
        // 3-byte CJK chars must not be split mid-code-point.
        val cjk = TransferUtils.sanitizeIncomingFileName("文".repeat(100) + ".txt")
        assertEquals(true, cjk.toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals(true, cjk.endsWith(".txt") && cjk.removeSuffix(".txt").all { it == '文' })
    }
}

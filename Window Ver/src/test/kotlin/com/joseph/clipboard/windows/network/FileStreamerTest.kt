package com.joseph.clipboard.windows.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Characterization tests for the file-streaming byte pumps: the plaintext path, the helper
 * methods, and the FileSeal-keyed (AEAD v1/v2) pump end to end. Record-format golden vectors
 * stay in FileSealVectorTest; here the pump itself is exercised with a key, plus true
 * mid-file byte-offset resume.
 */
class FileStreamerTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    // ---------- sanitizeIncomingFileName ----------

    @Test
    fun sanitizeStripsDotDot() {
        assertEquals("received_file", FileStreamer.sanitizeIncomingFileName(".."))
    }

    @Test
    fun sanitizeSingleDot() {
        assertEquals("received_file", FileStreamer.sanitizeIncomingFileName("."))
    }

    @Test
    fun sanitizeEmpty() {
        assertEquals("received_file", FileStreamer.sanitizeIncomingFileName(""))
    }

    @Test
    fun sanitizeStripsPathSeparators() {
        assertEquals("file.txt", FileStreamer.sanitizeIncomingFileName("foo/bar/file.txt"))
    }

    @Test
    fun sanitizeStripsBackslash() {
        assertEquals("file.txt", FileStreamer.sanitizeIncomingFileName("C:\\Users\\evil\\file.txt"))
    }

    @Test
    fun sanitizeStripsDotDotSlash() {
        assertEquals("secret.txt", FileStreamer.sanitizeIncomingFileName("../../secret.txt"))
    }

    @Test
    fun sanitizeReservedDeviceName() {
        assertEquals("_CON.txt", FileStreamer.sanitizeIncomingFileName("CON.txt"))
    }

    @Test
    fun sanitizeReservedNul() {
        assertEquals("_NUL.exe", FileStreamer.sanitizeIncomingFileName("NUL.exe"))
    }

    @Test
    fun sanitizeReservedCom1() {
        assertEquals("_COM1.log", FileStreamer.sanitizeIncomingFileName("COM1.log"))
    }

    @Test
    fun sanitizeReservedLpt1CaseInsensitive() {
        assertEquals("_lpt1.dat", FileStreamer.sanitizeIncomingFileName("lpt1.dat"))
    }

    @Test
    fun sanitizeNonReservedPassesThrough() {
        assertEquals("hello.txt", FileStreamer.sanitizeIncomingFileName("hello.txt"))
    }

    @Test
    fun sanitizeStripsIllegalChars() {
        assertEquals("ab.txt", FileStreamer.sanitizeIncomingFileName("a<b>.txt"))
    }

    @Test
    fun sanitizeReservedNameUsesTextBeforeFirstDot() {
        assertEquals("_NUL.tar.gz", FileStreamer.sanitizeIncomingFileName("NUL.tar.gz"))
        assertEquals("_CON .txt", FileStreamer.sanitizeIncomingFileName("CON .txt"))
        assertEquals("CONSOLE.txt", FileStreamer.sanitizeIncomingFileName("CONSOLE.txt"))
    }

    @Test
    fun sanitizeStripsTrailingDotsSpacesAndBidiControls() {
        assertEquals("evil.exe", FileStreamer.sanitizeIncomingFileName("evil.exe. . "))
        assertEquals("received_file", FileStreamer.sanitizeIncomingFileName("..."))
        assertEquals("invoicefdp.exe", FileStreamer.sanitizeIncomingFileName("invoice‮fdp.exe"))
    }

    @Test
    fun sanitizeCapsLongNamesAndKeepsExtension() {
        val out = FileStreamer.sanitizeIncomingFileName("a".repeat(400) + ".pdf")
        assertEquals(200, out.toByteArray(Charsets.UTF_8).size)
        assertEquals(true, out.endsWith(".pdf"))
        val cjk = FileStreamer.sanitizeIncomingFileName("文".repeat(100) + ".txt")
        assertEquals(true, cjk.toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals(true, cjk.endsWith(".txt") && cjk.removeSuffix(".txt").all { it == '文' })
    }

    // ---------- awaitCompletionAck ----------

    @Test
    fun ackMatchesSessionId() {
        val server = ServerSocket(0)
        val port = server.localPort
        val clientThread = Thread {
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("TRANSFER_COMPLETE|sid-42\n".toByteArray(Charsets.UTF_8))
                s.getOutputStream().flush()
            }
        }
        clientThread.start()
        val accepted = server.accept()
        assertTrue(FileStreamer.awaitCompletionAck(accepted, "sid-42"))
        accepted.close()
        server.close()
        clientThread.join()
    }

    @Test
    fun ackRejectsMismatchedSessionId() {
        val server = ServerSocket(0)
        val port = server.localPort
        val clientThread = Thread {
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("TRANSFER_COMPLETE|wrong-id\n".toByteArray(Charsets.UTF_8))
                s.getOutputStream().flush()
            }
        }
        clientThread.start()
        val accepted = server.accept()
        assertFalse(FileStreamer.awaitCompletionAck(accepted, "sid-42"))
        accepted.close()
        server.close()
        clientThread.join()
    }

    // ---------- plaintext round-trip: send → receive ----------

    @Test
    fun plaintextRoundTrip() {
        val srcDir = tmpDir.newFolder("src")
        val dstDir = tmpDir.newFolder("dst")

        val fileA = File(srcDir, "a.txt").apply { writeText("hello") }
        val fileB = File(srcDir, "b.bin").apply { writeBytes(ByteArray(1024) { it.toByte() }) }

        val manifest = listOf("a.txt" to fileA.length(), "b.bin" to fileB.length())

        // Pipe via PipedStreams doesn't work well with large buffers; use a byte array buffer.
        val buf = ByteArrayOutputStream()
        val progress = mutableListOf<Pair<Int, Long>>()

        FileStreamer.sendFromFiles(
            output = buf,
            files = listOf(fileA, fileB),
            fileSizes = listOf(fileA.length(), fileB.length()),
            sessionId = "test-session",
            startFileIndex = 0,
            startOffset = 0L,
            fileKey = null,
            useV2 = false,
            listener = object : FileStreamer.Listener {
                override fun onProgress(fileIndex: Int, currentFileOffset: Long) {
                    progress.add(fileIndex to currentFileOffset)
                }
                override fun onFileComplete(fileIndex: Int) {}
            }
        )

        val completed = mutableListOf<Int>()
        FileStreamer.receiveToDir(
            input = ByteArrayInputStream(buf.toByteArray()),
            saveDir = dstDir,
            manifest = manifest,
            sessionId = "test-session",
            startFileIndex = 0,
            startOffset = 0L,
            fileKey = null,
            useV2 = false,
            listener = object : FileStreamer.Listener {
                override fun onProgress(fileIndex: Int, currentFileOffset: Long) {}
                override fun onFileComplete(fileIndex: Int) { completed.add(fileIndex) }
            }
        )

        assertEquals("hello", File(dstDir, "a.txt").readText())
        assertArrayEquals(ByteArray(1024) { it.toByte() }, File(dstDir, "b.bin").readBytes())
        assertEquals(listOf(0, 1), completed)
    }

    private val noopListener = object : FileStreamer.Listener {
        override fun onProgress(fileIndex: Int, currentFileOffset: Long) {}
        override fun onFileComplete(fileIndex: Int) {}
    }

    // ---------- FileSeal (AEAD) round-trip through the byte pump ----------
    // FileSealVectorTest pins the record format; these exercise the PUMP with a key —
    // multi-record files, a zero-byte file, and the v2 session binding end to end.

    @Test
    fun aeadRoundTripV1() {
        val srcDir = tmpDir.newFolder("aead-src")
        val dstDir = tmpDir.newFolder("aead-dst")
        val key = ByteArray(32) { it.toByte() }

        // big.bin spans multiple 64KiB FileSeal records; empty.bin exercises the zero-chunk path.
        val big = File(srcDir, "big.bin").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        val small = File(srcDir, "small.txt").apply { writeText("sealed") }
        val empty = File(srcDir, "empty.bin").apply { writeBytes(ByteArray(0)) }
        val files = listOf(big, small, empty)
        val sizes = files.map { it.length() }
        val manifest = files.mapIndexed { i, f -> f.name to sizes[i] }

        val buf = ByteArrayOutputStream()
        FileStreamer.sendFromFiles(
            output = buf, files = files, fileSizes = sizes, sessionId = "aead-sess",
            startFileIndex = 0, startOffset = 0L, fileKey = key, useV2 = false, listener = noopListener
        )
        assertTrue("sealed stream must be larger than the plaintext", buf.size() > sizes.sum())
        FileStreamer.receiveToDir(
            input = ByteArrayInputStream(buf.toByteArray()), saveDir = dstDir, manifest = manifest,
            sessionId = "aead-sess", startFileIndex = 0, startOffset = 0L, fileKey = key,
            useV2 = false, listener = noopListener
        )

        assertArrayEquals(big.readBytes(), File(dstDir, "big.bin").readBytes())
        assertEquals("sealed", File(dstDir, "small.txt").readText())
        assertEquals(0L, File(dstDir, "empty.bin").length())
    }

    @Test
    fun aeadRoundTripV2AndWrongSessionRejected() {
        val srcDir = tmpDir.newFolder("aeadv2-src")
        val dstDir = tmpDir.newFolder("aeadv2-dst")
        val key = ByteArray(32) { it.toByte() }

        val fileA = File(srcDir, "a.bin").apply { writeBytes(ByteArray(70_000) { (it % 127).toByte() }) }
        val files = listOf(fileA)
        val sizes = files.map { it.length() }
        val manifest = files.mapIndexed { i, f -> f.name to sizes[i] }

        val buf = ByteArrayOutputStream()
        FileStreamer.sendFromFiles(
            output = buf, files = files, fileSizes = sizes, sessionId = "v2-sess",
            startFileIndex = 0, startOffset = 0L, fileKey = key, useV2 = true, listener = noopListener
        )
        FileStreamer.receiveToDir(
            input = ByteArrayInputStream(buf.toByteArray()), saveDir = dstDir, manifest = manifest,
            sessionId = "v2-sess", startFileIndex = 0, startOffset = 0L, fileKey = key,
            useV2 = true, listener = noopListener
        )
        assertArrayEquals(fileA.readBytes(), File(dstDir, "a.bin").readBytes())

        // v2 binds the sessionId into key derivation + AAD: replaying the stream under a
        // different session must fail authentication, not decrypt.
        val wrongDir = tmpDir.newFolder("aeadv2-wrong")
        val replayed = runCatching {
            FileStreamer.receiveToDir(
                input = ByteArrayInputStream(buf.toByteArray()), saveDir = wrongDir, manifest = manifest,
                sessionId = "other-sess", startFileIndex = 0, startOffset = 0L, fileKey = key,
                useV2 = true, listener = noopListener
            )
        }
        assertTrue("v2 stream must not open under a different sessionId", replayed.isFailure)
    }

    // ---------- true mid-file byte-offset resume (plaintext) ----------

    @Test
    fun plaintextResumeMidFileOffset() {
        val srcDir = tmpDir.newFolder("src3")
        val dstDir = tmpDir.newFolder("dst3")

        val fileA = File(srcDir, "a.txt").apply { writeText("done-already") }
        val fileB = File(srcDir, "b.txt").apply { writeText("0123456789") }
        val fileC = File(srcDir, "c.txt").apply { writeText("after") }
        val files = listOf(fileA, fileB, fileC)
        val sizes = files.map { it.length() }
        val manifest = files.mapIndexed { i, f -> f.name to sizes[i] }

        // The interrupted first run already put b.txt's first 4 bytes on disk.
        File(dstDir, "b.txt.part").writeText("0123")

        val buf = ByteArrayOutputStream()
        FileStreamer.sendFromFiles(
            output = buf, files = files, fileSizes = sizes, sessionId = "mid-resume",
            startFileIndex = 1, startOffset = 4L, fileKey = null, useV2 = false, listener = noopListener
        )
        FileStreamer.receiveToDir(
            input = ByteArrayInputStream(buf.toByteArray()), saveDir = dstDir, manifest = manifest,
            sessionId = "mid-resume", startFileIndex = 1, startOffset = 4L, fileKey = null,
            useV2 = false, listener = noopListener
        )

        // Sender skipped 4 bytes; receiver appended the tail onto the .part and promoted it.
        assertEquals("0123456789", File(dstDir, "b.txt").readText())
        assertEquals("after", File(dstDir, "c.txt").readText())
        assertFalse(File(dstDir, "a.txt").exists())
    }

    @Test
    fun plaintextResumeFromSecondFile() {
        val srcDir = tmpDir.newFolder("src2")
        val dstDir = tmpDir.newFolder("dst2")

        val fileA = File(srcDir, "a.txt").apply { writeText("skip-me") }
        val fileB = File(srcDir, "b.txt").apply { writeText("resume-here") }

        // Send only from file index 1 (skip file 0 entirely).
        val buf = ByteArrayOutputStream()
        FileStreamer.sendFromFiles(
            output = buf,
            files = listOf(fileA, fileB),
            fileSizes = listOf(fileA.length(), fileB.length()),
            sessionId = "resume-sess",
            startFileIndex = 1,
            startOffset = 0L,
            fileKey = null,
            useV2 = false,
            listener = object : FileStreamer.Listener {
                override fun onProgress(fileIndex: Int, currentFileOffset: Long) {}
                override fun onFileComplete(fileIndex: Int) {}
            }
        )

        val manifest = listOf("a.txt" to fileA.length(), "b.txt" to fileB.length())
        FileStreamer.receiveToDir(
            input = ByteArrayInputStream(buf.toByteArray()),
            saveDir = dstDir,
            manifest = manifest,
            sessionId = "resume-sess",
            startFileIndex = 1,
            startOffset = 0L,
            fileKey = null,
            useV2 = false,
            listener = object : FileStreamer.Listener {
                override fun onProgress(fileIndex: Int, currentFileOffset: Long) {}
                override fun onFileComplete(fileIndex: Int) {}
            }
        )

        // a.txt should NOT exist in dst (skipped)
        assertFalse(File(dstDir, "a.txt").exists())
        assertEquals("resume-here", File(dstDir, "b.txt").readText())
    }
}

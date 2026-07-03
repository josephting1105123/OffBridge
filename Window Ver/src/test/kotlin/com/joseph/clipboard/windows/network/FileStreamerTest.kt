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
 * Characterization tests for the file-streaming byte pumps. These tests exercise the
 * plaintext path (no FileSeal) and the helper methods. FileSeal v1/v2 round-trip tests
 * live in FileSealVectorTest; exercising them here would duplicate that coverage and
 * couple this test to the crypto infra.
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

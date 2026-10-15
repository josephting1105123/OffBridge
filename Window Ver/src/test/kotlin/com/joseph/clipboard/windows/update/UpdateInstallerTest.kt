package com.joseph.clipboard.windows.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URI

class UpdateInstallerTest {
    @Test
    fun githubHostsOverHttpsAreTrusted() {
        UpdateInstaller.requireTrusted(URI("https://github.com/o/r/releases/download/v1/OffBridgeSetup.exe"))
        UpdateInstaller.requireTrusted(URI("https://objects.githubusercontent.com/x"))
        UpdateInstaller.requireTrusted(URI("https://release-assets.githubusercontent.com/x"))
    }

    @Test
    fun httpLookalikeAndForeignHostsAreRefused() {
        listOf(
            "http://github.com/x",
            "https://evilgithub.com/x",
            "https://github.com.evil.net/x",
            "https://example.com/x",
            "ftp://github.com/x",
        ).forEach { url ->
            assertThrows(url, IOException::class.java) { UpdateInstaller.requireTrusted(URI(url)) }
        }
    }

    @Test
    fun redirectLocationResolvesAgainstCurrentUrl() {
        val current = URI("https://github.com/o/r/releases/download/v1/a.exe")
        assertEquals(
            URI("https://objects.githubusercontent.com/b?sig=1"),
            UpdateInstaller.redirectTarget(current, "https://objects.githubusercontent.com/b?sig=1"),
        )
        assertEquals(URI("https://github.com/o/r/other"), UpdateInstaller.redirectTarget(current, "/o/r/other"))
    }

    @Test
    fun redirectToForeignHostIsCaughtPerHop() {
        val next = UpdateInstaller.redirectTarget(URI("https://github.com/a"), "https://example.com/payload.exe")
        assertThrows(IOException::class.java) { UpdateInstaller.requireTrusted(next) }
    }

    @Test
    fun missingLocationIsAnError() {
        assertThrows(IOException::class.java) { UpdateInstaller.redirectTarget(URI("https://github.com/a"), null) }
        assertThrows(IOException::class.java) { UpdateInstaller.redirectTarget(URI("https://github.com/a"), " ") }
    }

    @Test
    fun checksumUrlPointsAtTheWebsiteFileForThatVersion() {
        assertEquals(
            URI("https://offbridge.app/checksums/OffBridgeSetup-3.7.11.exe.sha256"),
            UpdateInstaller.checksumUrl("3.7.11"),
        )
    }

    @Test
    fun sha256sumStyleBodiesParse() {
        val hex = "f50b42e8c2a63e1d8ca56a879d2355736e096588b5ee6e26894009b64c2f2a85"
        val bytes = UpdateInstaller.parseChecksum("$hex  OffBridgeSetup.exe\r\n")!!
        assertEquals(32, bytes.size)
        assertEquals(0xf5.toByte(), bytes[0])
        assertEquals(0x85.toByte(), bytes[31])
        assertArrayEquals(bytes, UpdateInstaller.parseChecksum("\uFEFF" + hex.uppercase()))
        assertArrayEquals(bytes, UpdateInstaller.parseChecksum("  $hex\n"))
    }

    @Test
    fun anythingElseIsNotAChecksum() {
        listOf("", "   ", "<!doctype html><html>", "f50b42", "z".repeat(64),
            "f50b42e8c2a63e1d8ca56a879d2355736e096588b5ee6e26894009b64c2f2a8").forEach {
            assertNull(it, UpdateInstaller.parseChecksum(it))
        }
    }

    @Test
    fun fileHashMatchesKnownVector() {
        val f = File.createTempFile("sha", ".bin")
        try {
            f.writeText("abc")
            assertArrayEquals(
                UpdateInstaller.parseChecksum("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                UpdateInstaller.sha256Of(f),
            )
        } finally {
            f.delete()
        }
    }
}

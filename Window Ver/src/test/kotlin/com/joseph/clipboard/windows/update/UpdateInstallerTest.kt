package com.joseph.clipboard.windows.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
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
}

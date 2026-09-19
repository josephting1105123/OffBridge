package com.joseph.clipboard.linux.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * XDG path resolution, asserted without a Linux machine anywhere in sight — this runs on the
 * Windows dev box and on the Linux CI runner identically, because [XdgDirs.resolveConfigHome] and
 * [XdgDirs.parseUserDirs] take their environment as parameters instead of reading the real one.
 *
 * These are the highest-risk lines in the Linux backend: a wrong config dir silently strands the
 * user's pairings somewhere they will never look.
 */
class XdgDirsTest {

    @Test
    fun `config home uses XDG_CONFIG_HOME when set`() {
        assertEquals(
            "/home/joe/.custom-config",
            XdgDirs.resolveConfigHome(mapOf("XDG_CONFIG_HOME" to "/home/joe/.custom-config"), "/home/joe")
        )
    }

    @Test
    fun `config home falls back to HOME dot-config when unset`() {
        assertEquals("/home/joe/.config", XdgDirs.resolveConfigHome(emptyMap(), "/home/joe"))
    }

    @Test
    fun `config home falls back when XDG_CONFIG_HOME is blank`() {
        assertEquals(
            "/home/joe/.config",
            XdgDirs.resolveConfigHome(mapOf("XDG_CONFIG_HOME" to "   "), "/home/joe")
        )
    }

    /** The XDG spec says a relative value is invalid and must be ignored, not resolved against cwd. */
    @Test
    fun `config home ignores a relative XDG_CONFIG_HOME`() {
        assertEquals(
            "/home/joe/.config",
            XdgDirs.resolveConfigHome(mapOf("XDG_CONFIG_HOME" to "relative/path"), "/home/joe")
        )
    }

    @Test
    fun `user-dirs download entry expands HOME`() {
        val contents = """
            # This file is written by xdg-user-dirs-update
            XDG_DESKTOP_DIR="${'$'}HOME/Desktop"
            XDG_DOWNLOAD_DIR="${'$'}HOME/Downloads"
            XDG_MUSIC_DIR="${'$'}HOME/Music"
        """.trimIndent()
        assertEquals("/home/joe/Downloads", XdgDirs.parseUserDirs(contents, "/home/joe"))
    }

    @Test
    fun `user-dirs download entry accepts an absolute path`() {
        val contents = """XDG_DOWNLOAD_DIR="/mnt/bulk/incoming""""
        assertEquals("/mnt/bulk/incoming", XdgDirs.parseUserDirs(contents, "/home/joe"))
    }

    /** A localized install may point downloads at a translated folder name — must survive verbatim. */
    @Test
    fun `user-dirs download entry keeps a non-ascii folder name`() {
        val contents = """XDG_DOWNLOAD_DIR="${'$'}HOME/téléchargements""""
        assertEquals("/home/joe/téléchargements", XdgDirs.parseUserDirs(contents, "/home/joe"))
    }

    @Test
    fun `user-dirs returns null when the download key is absent`() {
        val contents = """
            XDG_DESKTOP_DIR="${'$'}HOME/Desktop"
            XDG_MUSIC_DIR="${'$'}HOME/Music"
        """.trimIndent()
        assertNull(XdgDirs.parseUserDirs(contents, "/home/joe"))
    }

    @Test
    fun `user-dirs ignores a commented-out download entry`() {
        val contents = """
            # XDG_DOWNLOAD_DIR="${'$'}HOME/Old"
            XDG_DESKTOP_DIR="${'$'}HOME/Desktop"
        """.trimIndent()
        assertNull(XdgDirs.parseUserDirs(contents, "/home/joe"))
    }

    /** xdg-user-dirs-update appends rather than rewrites, so the last entry is the live one. */
    @Test
    fun `user-dirs takes the last download entry when repeated`() {
        val contents = """
            XDG_DOWNLOAD_DIR="${'$'}HOME/Old"
            XDG_DOWNLOAD_DIR="${'$'}HOME/New"
        """.trimIndent()
        assertEquals("/home/joe/New", XdgDirs.parseUserDirs(contents, "/home/joe"))
    }

    @Test
    fun `user-dirs rejects a relative download path`() {
        assertNull(XdgDirs.parseUserDirs("""XDG_DOWNLOAD_DIR="Downloads"""", "/home/joe"))
    }

    @Test
    fun `user-dirs handles HOME itself as the download dir`() {
        assertEquals("/home/joe", XdgDirs.parseUserDirs("""XDG_DOWNLOAD_DIR="${'$'}HOME"""", "/home/joe"))
    }
}

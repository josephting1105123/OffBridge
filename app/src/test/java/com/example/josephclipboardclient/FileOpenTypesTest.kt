package com.EntropicSoftwareLab.ClipboardShare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Received text files used to be unopenable: MimeTypeMap has no entry for .md / .log / .kt on most
 * builds, so they resolved to the wildcard type, matched no app, and missed the history sheet's
 * text fallback (which tested the MIME for a `text/` prefix). These pin the extension table that
 * now decides both questions. MimeTypeMap is a throwing stub on the host JVM, which is exactly the
 * path [FileOpenTypes] must survive.
 */
class FileOpenTypesTest {

    @Test
    fun theExtensionsMimeTypeMapMissesAreStillText() {
        for (name in listOf("notes.md", "server.log", "Main.kt", "build.gradle", "app.properties",
                            "data.yml", "config.toml", "query.sql", "run.sh")) {
            assertTrue("$name must be previewable", FileOpenTypes.isTextLike(name))
        }
    }

    @Test
    fun plainTextAndDataFormatsAreText() {
        for (name in listOf("a.txt", "b.csv", "c.json", "d.xml", "e.html", "f.tsv")) {
            assertTrue("$name must be previewable", FileOpenTypes.isTextLike(name))
        }
        assertEquals("text/plain", FileOpenTypes.mimeForOpening("a.txt"))
        assertEquals("text/markdown", FileOpenTypes.mimeForOpening("notes.md"))
        assertEquals("text/csv", FileOpenTypes.mimeForOpening("b.csv"))
        // Deliberately not text/*: it is what editors register for. The preview still applies,
        // because isTextLike is decided by the extension, not by this value.
        assertEquals("application/json", FileOpenTypes.mimeForOpening("c.json"))
        assertTrue(FileOpenTypes.isTextLike("c.json"))
    }

    @Test
    fun binariesAreNotOfferedTheTextPreview() {
        for (name in listOf("photo.jpg", "clip.mp4", "app.apk", "archive.zip", "doc.pdf",
                            "noextension", "", "trailingdot.")) {
            assertFalse("$name must not be previewable as text", FileOpenTypes.isTextLike(name))
        }
    }

    @Test
    fun extensionsAreCaseInsensitive() {
        assertTrue(FileOpenTypes.isTextLike("NOTES.MD"))
        assertTrue(FileOpenTypes.isTextLike("Server.LOG"))
        assertEquals("text/plain", FileOpenTypes.mimeForOpening("READ.TXT"))
    }

    @Test
    fun aMimeIsAlwaysReturnedSoTheIntentCanMatchSomething() {
        for (name in listOf("mystery.qqq", "noextension", "", "archive.zip")) {
            val mime = FileOpenTypes.mimeForOpening(name)
            assertTrue("$name resolved to '$mime'", mime.isNotEmpty())
        }
        // Unknown to our table and to the (stubbed) platform map: the wildcard, never null.
        assertEquals(FileOpenTypes.ANY_TYPE, FileOpenTypes.mimeForOpening("mystery.qqq"))
        assertEquals(FileOpenTypes.ANY_TYPE, FileOpenTypes.mimeForOpening("noextension"))
    }

    @Test
    fun mediaFallsBackToTheSharedMediaTableWhenThePlatformMapIsUnavailable() {
        // TransferUtils owns the media extensions; FileOpenTypes must defer to it rather than
        // keeping a second, divergent copy.
        assertEquals(TransferUtils.mimeForMediaExt("jpg"), FileOpenTypes.mimeForOpening("photo.jpg"))
        assertEquals("image/heic", FileOpenTypes.mimeForOpening("shot.heic"))
    }
}

package com.EntropicSoftwareLab.ClipboardShare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Received images/videos are routed into the public media collections so galleries list them;
 * everything else stays in Download. These pure host-JVM tests pin the classifier, its MIME map
 * (MediaProvider rejects an insert whose MIME doesn't match the collection), and the shared
 * "name (N).ext" dedupe used both at receive time and at publish time.
 */
class MediaRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val imageExts = listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    private val videoExts = listOf("mp4", "mkv", "avi", "mov", "webm", "ts")

    @Test
    fun classifiesEveryKnownExtensionCaseInsensitively() {
        for (ext in imageExts) {
            assertEquals(TransferUtils.MediaCategory.IMAGE, TransferUtils.mediaCategory("photo.$ext"))
            assertEquals(TransferUtils.MediaCategory.IMAGE, TransferUtils.mediaCategory("PHOTO.${ext.uppercase()}"))
        }
        for (ext in videoExts) {
            assertEquals(TransferUtils.MediaCategory.VIDEO, TransferUtils.mediaCategory("clip.$ext"))
            assertEquals(TransferUtils.MediaCategory.VIDEO, TransferUtils.mediaCategory("CLIP.${ext.uppercase()}"))
        }
    }

    @Test
    fun nonMediaNamesAreOther() {
        assertEquals(TransferUtils.MediaCategory.OTHER, TransferUtils.mediaCategory("doc.pdf"))
        assertEquals(TransferUtils.MediaCategory.OTHER, TransferUtils.mediaCategory("archive.zip"))
        assertEquals(TransferUtils.MediaCategory.OTHER, TransferUtils.mediaCategory("noextension"))
        // The in-flight sidecar must never classify as media, or a publish could grab it mid-write.
        assertEquals(TransferUtils.MediaCategory.OTHER, TransferUtils.mediaCategory("photo.jpg.part"))
        assertEquals(TransferUtils.MediaCategory.OTHER, TransferUtils.mediaCategory(""))
    }

    @Test
    fun mimeMapCoversEveryMediaExtensionAndMatchesItsCollection() {
        for (ext in imageExts) {
            val mime = TransferUtils.mimeForMediaExt(ext)
            assertNotNull("missing MIME for .$ext", mime)
            assertTrue(".$ext must map to image/*", mime!!.startsWith("image/"))
            assertEquals(mime, TransferUtils.mimeForMediaExt(ext.uppercase()))
        }
        for (ext in videoExts) {
            val mime = TransferUtils.mimeForMediaExt(ext)
            assertNotNull("missing MIME for .$ext", mime)
            assertTrue(".$ext must map to video/*", mime!!.startsWith("video/"))
        }
        assertEquals(null, TransferUtils.mimeForMediaExt("pdf"))
    }

    @Test
    fun dedupeNameReturnsOriginalWhenFree() {
        assertEquals("a.jpg", TransferUtils.dedupeName(tmp.root, "a.jpg"))
    }

    @Test
    fun dedupeNameCountsUpPastExistingCopies() {
        tmp.newFile("a.jpg")
        assertEquals("a (1).jpg", TransferUtils.dedupeName(tmp.root, "a.jpg"))
        tmp.newFile("a (1).jpg")
        assertEquals("a (2).jpg", TransferUtils.dedupeName(tmp.root, "a.jpg"))
    }

    @Test
    fun dedupeNameHandlesDotlessNames() {
        tmp.newFile("README")
        assertEquals("README (1)", TransferUtils.dedupeName(tmp.root, "README"))
    }

    @Test
    fun gallerySettingRoutesOnlyMediaToGallery() {
        // "Save media to: Gallery" — images/videos publish to the gallery collections, everything else
        // stays with files.
        assertTrue(TransferUtils.routeMediaToGallery("photo.jpg", TransferUtils.SAVE_MEDIA_GALLERY))
        assertTrue(TransferUtils.routeMediaToGallery("CLIP.MP4", TransferUtils.SAVE_MEDIA_GALLERY))
        assertEquals(false, TransferUtils.routeMediaToGallery("doc.pdf", TransferUtils.SAVE_MEDIA_GALLERY))
        // The in-flight sidecar must never route to the gallery, or a publish could grab it mid-write.
        assertEquals(false, TransferUtils.routeMediaToGallery("photo.jpg.part", TransferUtils.SAVE_MEDIA_GALLERY))
    }

    @Test
    fun sameAsFilesSettingNeverRoutesToGallery() {
        // "Save media to: Same as files" — even media goes to the file save folder, so nothing routes.
        assertEquals(false, TransferUtils.routeMediaToGallery("photo.jpg", TransferUtils.SAVE_MEDIA_SAME_AS_FILES))
        assertEquals(false, TransferUtils.routeMediaToGallery("clip.mp4", TransferUtils.SAVE_MEDIA_SAME_AS_FILES))
        assertEquals(false, TransferUtils.routeMediaToGallery("doc.pdf", TransferUtils.SAVE_MEDIA_SAME_AS_FILES))
        // An unrecognised setting value is treated as "not gallery" (the pref read supplies the real default).
        assertEquals(false, TransferUtils.routeMediaToGallery("photo.jpg", "bogus"))
    }

    @Test
    fun dedupeNameAcrossDirectoriesIsFreeInEveryDir() {
        // Receive-time dedupe must clear both Download and the publish destination, or a
        // same-named earlier-published photo shadows the new file for history opens.
        val download = tmp.newFolder("download")
        val pictures = tmp.newFolder("pictures")
        java.io.File(download, "a.jpg").createNewFile()
        java.io.File(pictures, "a (1).jpg").createNewFile()
        assertEquals("a (2).jpg", TransferUtils.dedupeName(listOf(download, pictures), "a.jpg"))
    }
}

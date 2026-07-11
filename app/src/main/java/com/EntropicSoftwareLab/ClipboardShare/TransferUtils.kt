package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import android.content.ContentUris
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap

object TransferUtils {
    enum class MediaCategory { IMAGE, VIDEO, OTHER }

    /** Persisted values of the "save_media_to" preference (where received photos/videos land).
     *  GALLERY publishes into the Pictures|Movies gallery collections; SAME_AS_FILES keeps them in
     *  the same folder as ordinary files ("Save file to"). GALLERY is the default (see receive path). */
    const val SAVE_MEDIA_GALLERY = "gallery"
    const val SAVE_MEDIA_SAME_AS_FILES = "same_as_files"

    /** Whether a received file named [fileName] should be published into the gallery collections
     *  rather than written to the file save folder, given the "save_media_to" [saveMediaTo] setting.
     *  Only images/videos route to the gallery, and only when the setting is GALLERY; everything
     *  else (all non-media, and any media under SAME_AS_FILES) goes to the file folder. Pure so the
     *  routing decision is unit-testable without a running receive stream. */
    fun routeMediaToGallery(fileName: String, saveMediaTo: String): Boolean =
        saveMediaTo == SAVE_MEDIA_GALLERY && mediaCategory(fileName) != MediaCategory.OTHER

    /** Subfolder inside Pictures/ and Movies/ where received media is published. Not localized:
     *  it is an on-disk path that must stay stable across locale changes. */
    private const val MEDIA_SUBDIR = "Clipboard Share"

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "ts")

    // MediaProvider rejects an insert whose MIME doesn't match the collection, and
    // MimeTypeMap has per-OEM gaps (heic/ts/bmp) — so map our closed extension set explicitly.
    private val MEDIA_MIME = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "heic" to "image/heic",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "avi" to "video/avi",
        "mov" to "video/quicktime", "webm" to "video/webm", "ts" to "video/mp2t"
    )

    /** Classifies a file name by extension (case-insensitive). Single source of truth shared by
     *  the receive path and the history "open" UI, so routing and lookup can never disagree. */
    fun mediaCategory(fileName: String): MediaCategory {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext in IMAGE_EXTS -> MediaCategory.IMAGE
            ext in VIDEO_EXTS -> MediaCategory.VIDEO
            else -> MediaCategory.OTHER
        }
    }

    /** Platform mapping first (MediaProvider validates extension↔MIME with the same table, so
     *  agreeing with it maximises insert success), our closed map as the fallback for the
     *  OEM-gap extensions. The try covers host-JVM unit tests where MimeTypeMap is a stub. */
    fun mimeForMediaExt(ext: String): String? {
        val e = ext.lowercase()
        val platform = try {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(e)
        } catch (_: Throwable) { null }
        return platform ?: MEDIA_MIME[e]
    }

    /** Environment collection name (Pictures/Movies) for [category]; null for OTHER. The single
     *  place that names the collections — the publish fast path, the MediaStore RELATIVE_PATH and
     *  the history lookup must all agree or published files become unfindable. */
    private fun collectionFor(category: MediaCategory): String? = when (category) {
        MediaCategory.IMAGE -> Environment.DIRECTORY_PICTURES
        MediaCategory.VIDEO -> Environment.DIRECTORY_MOVIES
        MediaCategory.OTHER -> null
    }

    /** Public collection folder where [publishReceivedMedia] lands media of [category];
     *  null for OTHER (those stay in Download). */
    fun publicMediaDir(category: MediaCategory): java.io.File? {
        val collection = collectionFor(category) ?: return null
        return java.io.File(Environment.getExternalStoragePublicDirectory(collection), MEDIA_SUBDIR)
    }

    /** First name derived from [name] that doesn't exist in [dir] yet, using the same
     *  "name (N).ext" counter the receive path has always used for Download collisions. */
    fun dedupeName(dir: java.io.File, name: String): String = dedupeName(listOf(dir), name)

    /** Multi-directory variant: the returned name is free in EVERY listed directory, and not already
     *  claimed in [taken]. The receive path dedupes against Download AND the media publish
     *  destination — a name that is only unique in Download could be shadowed by a same-named,
     *  earlier-published photo when the history "open" action later probes the media folder first.
     *  [taken] guards against names that can't be probed as java.io.File paths: duplicate leaf
     *  names WITHIN one batch (e.g. a sent folder containing "a.txt" in two different subfolders,
     *  both flattened to the same leaf — without it the second file would overwrite the first on
     *  disk), and the receive path also passes the user's custom SAF folder listing through it so
     *  a received file never claims a name already in that folder.
     *  [reservePartSidecars]: treat "name.part" in [taken] as claiming "name" — session-start
     *  reservation uses this so a CONCURRENT session's in-flight sidecar blocks the name (two
     *  same-name receives used to stream into ONE shared .part and corrupt each other). Promote-time
     *  callers must leave it false: their own just-written sidecar always exists at that moment. */
    fun dedupeName(dirs: List<java.io.File>, name: String, taken: Set<String> = emptySet(), reservePartSidecars: Boolean = false): String {
        var candidate = name
        var counter = 1
        val nameWithoutExt = name.substringBeforeLast('.')
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        while (candidate in taken ||
            (reservePartSidecars && "$candidate.part" in taken) ||
            dirs.any { java.io.File(it, candidate).exists() }
        ) {
            candidate = "$nameWithoutExt ($counter)$ext"
            counter++
        }
        return candidate
    }

    /** API 29 (Android 10): raw java.io writes/reads into the public collections EACCES because the
     *  owner-passthrough FUSE bridge is Android 11+ and this app never opts into legacy storage — so
     *  receives stage app-privately and publish through MediaStore. Single predicate for the three
     *  receive/open sites that branch on it. */
    fun isLegacyScopedStorage(): Boolean = Build.VERSION.SDK_INT <= 29

    /** Directory where the no-SAF receive path stages ".part" files (and where non-media receives
     *  live once promoted, on API 30+). Public Download normally; on API 29, the app-private external
     *  files dir (see [isLegacyScopedStorage]), published out via MediaStore after promote
     *  ([publishReceivedMedia] / [publishToPublicDownloads]). */
    fun receiveStagingDir(context: Context): java.io.File =
        if (isLegacyScopedStorage())
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        else
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** MediaStore content URI of a published receive by its history leaf name — needed for history
     *  "open" on API 29, where raw File(...).exists() can't see even our own published rows (owner
     *  FUSE passthrough is Android 11+). Media is scoped to our RELATIVE_PATH (Pictures|Movies/"Clipboard
     *  Share") so a same-named camera photo elsewhere can't shadow it; non-media matches the Downloads
     *  collection by name. Newest row wins; null on miss/error. */
    fun findPublishedFileUri(context: Context, name: String): Uri? {
        val category = mediaCategory(name)
        val nameCol = MediaStore.MediaColumns.DISPLAY_NAME
        val pathCol = MediaStore.MediaColumns.RELATIVE_PATH
        val collection = collectionFor(category)
        if (collection != null) {
            val mediaUri = if (category == MediaCategory.IMAGE)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            queryNewestId(context, mediaUri, "$nameCol = ? AND $pathCol LIKE ?", arrayOf(name, "$collection/$MEDIA_SUBDIR%"))
                ?.let { return it }
        }
        // Non-media receives — and media the user routed "Same as files" with no custom folder,
        // which [publishToPublicDownloads] lands in the Downloads collection on API 29, NOT
        // Pictures|Movies — live in Download/. Media falls through here when the scoped query
        // above missed, so both routings stay findable.
        return queryNewestId(
            context,
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "$nameCol = ?", arrayOf(name)
        )
    }

    /** First (newest) row id of [contentUri] matching [selection], as a content URI; null on miss/error. */
    private fun queryNewestId(context: Context, contentUri: Uri, selection: String, selectionArgs: Array<String>): Uri? =
        try {
            context.contentResolver.query(
                contentUri,
                arrayOf(MediaStore.MediaColumns._ID),
                selection, selectionArgs,
                "${android.provider.MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(contentUri, c.getLong(0)) else null
            }
        } catch (_: Exception) { null }

    /**
     * Makes a fully received file visible where users expect it. Images/videos move out of
     * Download into Pictures|Movies/"Clipboard Share" — a bare Download scan never surfaces
     * them in a gallery's photo/video view — while every other type stays in Download and is
     * just handed to the media scanner. Returns the leaf name the file ends up with (deduped
     * when the destination already held one; never overwrites — a same-name file there may be
     * a user-kept photo). Never throws: any failure falls back to today's behaviour (file
     * stays in Download, scanned), because publishing must not be able to fail a transfer
     * that already delivered every byte.
     */
    fun publishReceivedMedia(context: Context, file: java.io.File): String {
        val category = mediaCategory(file.name)
        val destDir = publicMediaDir(category)
        if (destDir == null) {
            indexForGallery(context, file)
            return file.name
        }
        try {
            // Fast path: same-volume rename — instant even for multi-GB videos. FUSE (API 30+)
            // allows it because we own the source and the name is media-typed for the collection.
            if (destDir.isDirectory || destDir.mkdirs()) {
                val destName = dedupeName(destDir, file.name)
                val dest = java.io.File(destDir, destName)
                if (file.renameTo(dest)) {
                    indexForGallery(context, dest)
                    return destName
                }
            }
            // Fallback (API 29, or a FUSE rename denial): copy through a pending MediaStore row.
            val copiedName = copyIntoMediaStore(context, file, category)
            if (copiedName != null) {
                file.delete()
                return copiedName
            }
        } catch (_: Exception) { }
        indexForGallery(context, file)
        return file.name
    }

    /** Streams [file] into a pending MediaStore Images/Video row under RELATIVE_PATH
     *  Pictures|Movies/"Clipboard Share". Returns the row's actual display name (MediaProvider
     *  silently dedupes collisions), or null on failure — the pending row is deleted so no
     *  half-written ghost survives. */
    private fun copyIntoMediaStore(context: Context, file: java.io.File, category: MediaCategory): String? {
        val mime = mimeForMediaExt(file.name.substringAfterLast('.', "")) ?: return null
        val isImage = category == MediaCategory.IMAGE
        val contentUri = if (isImage)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = (collectionFor(category) ?: return null) + "/" + MEDIA_SUBDIR
        return copyIntoCollection(context, file, contentUri, relativePath, mime)
    }

    /** API-29 companion to [publishReceivedMedia] for non-media receives. The file was staged in
     *  the app-private dir (see [receiveStagingDir]) because raw public-Download writes EACCES
     *  under API 29 scoped storage, so stream it into a pending MediaStore Downloads row (which
     *  lands physically in Download/) and delete the private original. Returns the row's actual
     *  display name (MediaProvider silently dedupes collisions), or null on failure — the file
     *  then just stays in the app dir; publishing must never fail an already-delivered transfer. */
    fun publishToPublicDownloads(context: Context, file: java.io.File): String? {
        val mime = mimeForMediaExt(file.name.substringAfterLast('.', "")) ?: "application/octet-stream"
        val contentUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val copiedName = try {
            copyIntoCollection(context, file, contentUri, Environment.DIRECTORY_DOWNLOADS, mime)
        } catch (_: Exception) { null }
        if (copiedName != null) file.delete()
        return copiedName
    }

    /** Shared pending-row copy for [copyIntoMediaStore] and [publishToPublicDownloads]:
     *  insert IS_PENDING=1 → stream bytes → clear IS_PENDING → read back the display name. */
    private fun copyIntoCollection(context: Context, file: java.io.File, contentUri: Uri, relativePath: String, mime: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(contentUri, values) ?: return null
        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: throw java.io.IOException("openOutputStream returned null for $itemUri")
            val publish = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            // An un-updated row stays IS_PENDING=1 — invisible to every gallery — while the caller
            // would delete the Download original, leaving the media visible nowhere. Fail instead.
            if (resolver.update(itemUri, publish, null, null) == 0) {
                throw java.io.IOException("Failed to clear IS_PENDING for $itemUri")
            }
            var actualName = file.name
            resolver.query(itemUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { actualName = it }
            }
            actualName
        } catch (e: Exception) {
            try { resolver.delete(itemUri, null, null) } catch (_: Exception) { }
            null
        }
    }

    fun indexForGallery(context: Context, file: java.io.File) {
        try {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(file.absolutePath),
                null,
                null
            )
        } catch (_: Exception) { }
    }

    fun readUntilNewline(inputStream: InputStream, maxBytes: Int = 1024 * 1024): String {
        val baos = ByteArrayOutputStream()
        var b = inputStream.read()
        while (b != -1 && b != '\n'.code) {
            baos.write(b)
            // A header/manifest line is small; a peer streaming bytes without a newline
            // would otherwise grow this buffer until OutOfMemoryError. Cap and abort.
            if (baos.size() > maxBytes) throw IOException("Header line exceeded $maxBytes bytes")
            b = inputStream.read()
        }
        return baos.toString("UTF-8")
    }

    /**
     * Parses a LAN file-offer MANIFEST line: `MANIFEST|name:size|name:size|...`.
     *
     * Each entry is split on its LAST ':' so a filename containing ':' is preserved. Entries with
     * no ':', an empty name (`lastColonIndex <= 0`), or a non-numeric size are skipped. This is
     * byte-for-byte parity with the Windows client's `ManifestCodec.parse` and the Android off-grid
     * handshake — the sole authority for turning the wire manifest into (name, size) pairs, so all
     * three paths agree on which entries a hostile/degenerate manifest yields.
     */
    fun parseManifestEntries(manifestLine: String): List<Pair<String, Long>> {
        if (!manifestLine.startsWith("MANIFEST|")) return emptyList()
        val items = manifestLine.substringAfter("MANIFEST|").split('|').filter { it.isNotBlank() }
        val out = ArrayList<Pair<String, Long>>(items.size)
        for (item in items) {
            val lastColonIndex = item.lastIndexOf(':')
            if (lastColonIndex > 0) {
                val fname = item.substring(0, lastColonIndex)
                val fsize = item.substring(lastColonIndex + 1).toLongOrNull() ?: continue
                out.add(fname to fsize)
            }
        }
        return out
    }

    /**
     * Reduces a manifest-supplied filename to a safe on-disk leaf name (XP-DEF-03). Ported to stay
     * byte-for-byte with the Windows client's `FileStreamer.sanitizeIncomingFileName` so both
     * receivers turn a hostile name into the same file. Strips any directory component and drops
     * "."/".." (path-traversal), removes reserved/control characters, and prefixes Windows reserved
     * device names (CON, NUL, COM1-9, …) so they land on disk rather than a device — harmless on
     * Android but kept for cross-platform parity. Empty/degenerate names fall back to "received_file".
     */
    fun sanitizeIncomingFileName(rawName: String): String {
        val leaf = rawName.replace('\\', '/').substringAfterLast('/').trim()
        if (leaf.isEmpty() || leaf == "." || leaf == "..") return "received_file"
        val cleaned = leaf.filterNot { it in "<>:\"/\\|?*" || it.code < 0x20 }.ifBlank { "received_file" }
        val stem = cleaned.substringBeforeLast('.')
        val reserved = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", RegexOption.IGNORE_CASE)
        return if (reserved.matches(stem)) "_$cleaned" else cleaned
    }

    /**
     * Sanitizes an OUTGOING manifest name so it survives the pipe-delimited, last-colon-split MANIFEST
     * wire format: '|' (the entry delimiter) and ':' (the name:size separator) would corrupt framing,
     * so both map to '_'. Receive-side twin: [sanitizeIncomingFileName].
     */
    fun sanitizeOutgoingManifestName(rawName: String): String =
        rawName.replace("|", "_").replace(":", "_")

    /**
     * Display leaf for a manifest path (history rows, records): normalizes back-slashes first so a
     * Windows-style path flattens the same on every code path — some sites previously skipped the
     * normalize and stored the raw "dir\file" verbatim, so the SAME transfer displayed different
     * names depending on which path recorded it.
     */
    fun manifestDisplayLeaf(rawName: String): String = rawName.replace('\\', '/').substringAfterLast('/')
}

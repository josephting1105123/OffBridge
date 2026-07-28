package com.EntropicSoftwareLab.ClipboardShare

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * One-shot move of already-received photos and videos out of Pictures|Movies/"Clipboard Share" into
 * Pictures|Movies/"OffBridge" after the app rename, so a user who received files under the old name
 * doesn't end up with their media split across two gallery albums.
 *
 * Best-effort by design — two ways to move a file, tried in order:
 *  1. A MediaStore RELATIVE_PATH update, which makes MediaProvider perform the physical move while
 *     keeping the row id, so anything already pointing at it (a granted share URI, a cached gallery
 *     thumbnail) survives. Only works for rows this app owns; where MediaProvider recorded no owner
 *     (scanner-inserted, which happens on some OEMs) it throws a SecurityException instead.
 *  2. A raw [java.io.File.renameTo] plus a scan of both paths, which the API 30+ FUSE layer permits
 *     for files we own. On API 29 raw public-collection writes EACCES (see
 *     [TransferUtils.isLegacyScopedStorage]), so there the first route is the only one.
 *
 * Anything that fails both is left where it is: [TransferUtils.LEGACY_MEDIA_SUBDIR] keeps every
 * history lookup probing the old folder, so a stranded file stays openable from the app. Nothing
 * here may throw into the caller or prompt the user — it runs on a background thread during the
 * splash and must never block or crash launch.
 */
object MediaFolderMigration {
    private const val TAG = "MediaFolderMigration"

    /** Set once a full pass has run, whatever the per-file outcome was. A pass that stranded a few
     *  rows must not re-run every launch: the strays stay reachable through the legacy-folder
     *  probes, so retrying would re-scan both whole collections for nothing. */
    private const val PREF_DONE = "media_folder_migrated_offbridge"

    /** Runs the migration at most once per install, off the main thread. Cheap to call every
     *  launch — after the first pass it is a single SharedPreferences read. */
    fun runOnce(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_DONE, false)) return
        Thread({
            try {
                migrate(appContext)
                prefs.edit().putBoolean(PREF_DONE, true).apply()
            } catch (t: Throwable) {
                // Storage not mounted yet, provider not up — leave the flag clear and retry next launch.
                Log.w(TAG, "media folder migration deferred to next launch", t)
            }
        }, TAG).apply { isDaemon = true }.start()
    }

    private fun migrate(context: Context) {
        var moved = 0
        var stranded = 0
        for (category in listOf(TransferUtils.MediaCategory.IMAGE, TransferUtils.MediaCategory.VIDEO)) {
            val collection = TransferUtils.collectionFor(category) ?: continue
            val contentUri = TransferUtils.mediaContentUri(category) ?: continue
            val destDir = TransferUtils.publicMediaDir(category) ?: continue
            val legacyDir = TransferUtils.publicMediaDir(category, TransferUtils.LEGACY_MEDIA_SUBDIR) ?: continue
            val newRelativePath = "$collection/${TransferUtils.MEDIA_SUBDIR}/"

            // Indexed rows first, so the on-disk sweep below only ever sees what MediaStore missed —
            // moving a file the provider still has a stale row for would leave a broken gallery entry.
            val attempted = HashSet<String>()
            for ((id, name) in legacyRows(context, contentUri, collection)) {
                attempted.add(name)
                val rowUri = ContentUris.withAppendedId(contentUri, id)
                val ok = updateRelativePath(context, rowUri, newRelativePath) ||
                    renameOnDisk(context, java.io.File(legacyDir, name), destDir)
                if (ok) moved++ else stranded++
            }

            // Files the provider never indexed (a scan that never landed, a sideloaded copy).
            val leftovers = try { legacyDir.listFiles() } catch (_: Exception) { null }
            leftovers?.forEach { file ->
                if (!file.isFile || file.name in attempted) return@forEach
                if (renameOnDisk(context, file, destDir)) moved++ else stranded++
            }

            // Drops the empty album from the gallery. Fails harmlessly while files are still stranded.
            try { legacyDir.delete() } catch (_: Exception) { }
        }
        Log.i(TAG, "media folder migration finished: moved=$moved stranded=$stranded")
    }

    /** `(id, display name)` of every row of [contentUri] filed DIRECTLY in the pre-rename folder.
     *  The query is a bare prefix LIKE because MediaProvider is not consistent about the trailing
     *  slash on RELATIVE_PATH; each row's own path is then compared exactly, which drops both the
     *  near-miss neighbour ("Clipboard Shared/") and anything sitting in a nested subfolder. Nested
     *  files are deliberately left alone: flattening someone's subfolder into the new album would be
     *  surprising, and [java.io.File] `(legacyDir, name)` in the on-disk fallback would not name a
     *  real path for them anyway. */
    private fun legacyRows(context: Context, contentUri: Uri, collection: String): List<Pair<Long, String>> {
        val rows = ArrayList<Pair<Long, String>>()
        val expected = "$collection/${TransferUtils.LEGACY_MEDIA_SUBDIR}"
        try {
            context.contentResolver.query(
                contentUri,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$expected%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val path = c.getString(2)?.trimEnd('/') ?: continue
                    if (path != expected) continue
                    rows.add(c.getLong(0) to name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not list legacy rows in $collection", e)
        }
        return rows
    }

    /** Asks MediaProvider to refile [rowUri] under [newRelativePath], moving the bytes with it.
     *  False when we don't own the row, or when the destination already holds that display name —
     *  both fall through to [renameOnDisk], which dedupes. */
    private fun updateRelativePath(context: Context, rowUri: Uri, newRelativePath: String): Boolean =
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, newRelativePath)
            }
            context.contentResolver.update(rowUri, values, null, null) > 0
        } catch (_: Exception) {
            false
        }

    /** Same-volume move of [src] into [destDir] under a name free there, then a scan of both paths
     *  so the gallery drops the old entry and picks up the new one. False if the move didn't
     *  happen — the caller then leaves the file alone rather than losing it. */
    private fun renameOnDisk(context: Context, src: java.io.File, destDir: java.io.File): Boolean {
        try {
            if (!src.isFile) return false
            if (!destDir.isDirectory && !destDir.mkdirs()) return false
            val dest = java.io.File(destDir, TransferUtils.dedupeName(destDir, src.name))
            val oldPath = src.absolutePath
            if (!src.renameTo(dest)) return false
            MediaScannerConnection.scanFile(context, arrayOf(oldPath, dest.absolutePath), null, null)
            return true
        } catch (_: Exception) {
            return false
        }
    }
}

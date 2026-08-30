package com.EntropicSoftwareLab.ClipboardShare

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.EntropicSoftwareLab.ClipboardShare.transport.ClipboardSeal
import com.EntropicSoftwareLab.ClipboardShare.transport.ScreenshotRules
import com.EntropicSoftwareLab.ClipboardShare.transport.ScreenshotWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Tells paired laptops when this phone takes a screenshot, and serves the image if one asks for it.
 *
 * Nothing is sent but a sealed announcement: filename, size, timestamp and a small preview. The
 * bytes move only when the person at the laptop clicks Open, which turns into a `SCREENSHOT_FETCH`
 * and then an ordinary file transfer through [OfferHandshake] reusing the same session id. No new
 * transfer, crypto or resume code — FileSeal, AUTH2R and resume all come along because it is the
 * same path a manual send takes.
 *
 * Detection is a [ContentObserver] on the images collection. Android gives third-party apps no
 * "a screenshot happened" signal: `Activity.registerScreenCaptureCallback` (API 34) fires only for
 * screenshots of your OWN activity, which is the opposite of what is wanted here. Watching
 * MediaStore is what is actually available, at the cost of needing READ_MEDIA_IMAGES.
 *
 * Lifetime is the service's: started from [ClipboardService.onCreate], stopped in onDestroy.
 */
object ScreenshotWatcher {

    /** Pref key for the Settings toggle. Off by default — this reads the user's photo library. */
    const val PREF_ENABLED = "screenshot_notify_enabled"

    /**
     * MediaStore fires several times for one insert (the pending row, then the published one), so
     * the observer settles before querying rather than racing each notification.
     */
    private const val DEBOUNCE_MS = 400L

    /** Longest edge of the preview. Big enough to recognise the shot, small enough to be free. */
    private const val THUMBNAIL_MAX_EDGE = 256

    private const val THUMBNAIL_QUALITY = 60

    /** How long a fetch stays answerable, and how many shots are remembered at once. */
    private const val PENDING_TTL_MS = 30 * 60 * 1000L
    private const val MAX_PENDING = 10

    /** A screenshot that has been announced and might still be asked for. */
    private data class Pending(
        val uri: Uri,
        val name: String,
        val size: Long,
        val announcedAt: Long,
    )

    /**
     * Announced screenshots by session id, oldest first.
     *
     * Bounded and expiring because it holds content URIs for images the user may since have
     * deleted, and because an unbounded map fed by an automatic trigger is a leak waiting for a
     * long-running service to find. Guarded by its own lock: written from the observer thread, read
     * from the socket thread that handles a fetch.
     */
    private val pending = LinkedHashMap<String, Pending>()
    private val pendingLock = Any()

    /** Recently-seen MediaStore row ids, so one insert is never announced twice. */
    private val recentIds = LinkedHashSet<Long>()

    private var observer: ContentObserver? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Coalesces the burst of notifications MediaStore emits for a single new file.
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounced: Runnable? = null

    /** True when the feature is switched on AND the permission it needs is actually granted. */
    fun isActive(context: Context): Boolean = isEnabled(context) && hasPermission(context)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, enabled).apply()
        if (enabled) start(context) else stop(context)
    }

    /**
     * Whether full photo access is granted.
     *
     * On Android 14+ the user can grant READ_MEDIA_VISUAL_USER_SELECTED instead, which limits
     * MediaStore to the handful of images they picked — a set that will never contain a screenshot
     * taken thirty seconds from now. That partial grant has to read as "not available" rather than
     * as a working feature that silently never fires.
     */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission()) == PackageManager.PERMISSION_GRANTED

    /** The runtime permission to request when the user turns the feature on. */
    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    @Synchronized
    fun start(context: Context) {
        val ctx = context.applicationContext
        if (observer != null) return
        if (!isActive(ctx)) return

        val created = object : ContentObserver(debounceHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                debounced?.let { debounceHandler.removeCallbacks(it) }
                val runnable = Runnable { scope.launch { checkForNewScreenshot(ctx) } }
                debounced = runnable
                debounceHandler.postDelayed(runnable, DEBOUNCE_MS)
            }
        }
        try {
            ctx.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                // Screenshots land in a subtree of the collection, so descendant changes matter.
                true,
                created,
            )
            observer = created
            ServiceLifecycleLog.log(ctx, "ScreenshotWatcher: watching for screenshots")
        } catch (e: Exception) {
            Log.w(TAG, "could not register observer: " + e.message)
        }
    }

    @Synchronized
    fun stop(context: Context) {
        val current = observer ?: return
        observer = null
        debounced?.let { debounceHandler.removeCallbacks(it) }
        debounced = null
        try {
            context.applicationContext.contentResolver.unregisterContentObserver(current)
        } catch (e: Exception) {
            Log.w(TAG, "could not unregister observer: " + e.message)
        }
        synchronized(pendingLock) {
            pending.clear()
            recentIds.clear()
        }
    }

    /** Queries the newest image row and announces it if it is a screenshot not yet seen. */
    private fun checkForNewScreenshot(context: Context) {
        if (!isActive(context)) return
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                // IS_PENDING = 0 skips the half-written row MediaStore publishes first.
                MediaStore.Images.Media.IS_PENDING + " = 0",
                null,
                MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT 1",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    ?: return
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH))

                if (!ScreenshotRules.isFreshScreenshot(relativePath, dateAdded, System.currentTimeMillis())) return
                if (size <= 0L) return
                if (!ScreenshotWire.isOpenableImage(name)) return
                synchronized(pendingLock) {
                    if (!recentIds.add(id)) return
                    while (recentIds.size > MAX_PENDING) recentIds.remove(recentIds.first())
                }

                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(id.toString()).build()
                announce(context, uri, name, size, dateAdded * 1000L)
            }
        } catch (e: Exception) {
            // A permission revoked mid-session lands here. Not worth surfacing: the connection
            // health card already reports permission state, and a toast per screenshot is worse.
            Log.w(TAG, "MediaStore query failed: " + e.message)
        }
    }

    /** Seals the metadata and sends SCREENSHOT_AVAILABLE to every peer that can act on it. */
    private fun announce(context: Context, uri: Uri, name: String, size: Long, modifiedMillis: Long) {
        val targets = TransferDaemon.discoveredNodes.values.filter { device ->
            // Paired, present, understands the frame, and can decrypt the payload. The last of
            // those is not optional: there is no plaintext fallback for a picture of your screen.
            device.supportsScreenshotNotify &&
                device.supportsAuthClipboard &&
                SecurityVault.getKeyForNode(context, device.ip) != null
        }
        if (targets.isEmpty()) return

        val sessionId = UUID.randomUUID().toString().replace("-", "")
        val thumbnail = buildThumbnail(context, uri)
        val metadata = ScreenshotWire.Metadata(name, size, modifiedMillis, thumbnail)
        val plaintext = ScreenshotWire.encodeMetadata(metadata).toByteArray(Charsets.UTF_8)

        synchronized(pendingLock) {
            pending[sessionId] = Pending(uri, name, size, System.currentTimeMillis())
            prunePendingLocked()
        }

        val now = System.currentTimeMillis()
        for (device in targets) {
            scope.launch {
                try {
                    val key = SecurityVault.getKeyForNode(context, device.ip) ?: return@launch
                    val sealed = Base64.encodeToString(
                        ClipboardSeal.seal(key, plaintext, now), Base64.NO_WRAP
                    )
                    sendLine(device.ip, ScreenshotWire.availableLine(sessionId, sealed))
                } catch (e: Exception) {
                    Log.w(TAG, "announce to " + device.ip + " failed: " + e.message)
                }
            }
        }
    }

    /**
     * Answers a laptop's SCREENSHOT_FETCH by raising a normal offer for the stored image.
     *
     * The session id is reused from the announcement so the receiver can match this offer to the
     * fetch it just sent and accept it without asking. Called from [FileTransferServer] on the
     * socket thread, after that dispatcher has applied the pairing trust gate.
     */
    fun handleFetch(context: Context, sessionId: String, requesterIp: String) {
        val entry = synchronized(pendingLock) {
            prunePendingLocked()
            pending[sessionId]
        }
        if (entry == null) {
            Log.w(TAG, "fetch for unknown or expired session " + sessionId)
            return
        }
        // The dispatcher already checked this, but handleFetch is public and the cost of checking
        // again before serving a picture of the user's screen is one map lookup.
        if (SecurityVault.getKeyForNode(context, requesterIp) == null) return

        scope.launch {
            try {
                val manifest = listOf(entry.name to entry.size)
                val session = TransferSession(
                    sessionId = sessionId,
                    senderName = TransferDaemon.customDeviceName,
                    totalFiles = 1,
                    manifest = manifest,
                    fileUris = listOf(entry.uri.toString()),
                    targetIp = requesterIp,
                    isIncoming = false,
                    isPaused = false,
                )
                TransferDaemon.putActiveSession(sessionId, session)

                val targetName = TransferDaemon.discoveredNodes[requesterIp]?.name ?: requesterIp
                TransferHistoryManager.addRecord(
                    context,
                    TransferRecord(
                        sessionId = sessionId,
                        deviceName = targetName,
                        direction = "outgoing",
                        fileNames = listOf(entry.name),
                        totalBytes = entry.size,
                        timestamp = System.currentTimeMillis(),
                        status = "pending",
                        filesCompleted = 0,
                        totalFiles = 1,
                        fileSizes = listOf(entry.size),
                        currentFileOffset = 0L,
                        transport = "lan",
                    )
                )

                OfferHandshake.sendOffer(
                    context = context,
                    targetIp = requesterIp,
                    sessionId = sessionId,
                    deviceName = TransferDaemon.customDeviceName,
                    manifest = manifest,
                )
                session.isPaused = true
            } catch (e: Exception) {
                Log.w(TAG, "fetch offer to " + requesterIp + " failed: " + e.message)
            }
        }
    }

    /** Drops expired entries, then the oldest, until the map is within both bounds. */
    private fun prunePendingLocked() {
        val cutoff = System.currentTimeMillis() - PENDING_TTL_MS
        val expired = pending.entries.filter { it.value.announcedAt < cutoff }.map { it.key }
        expired.forEach { pending.remove(it) }
        while (pending.size > MAX_PENDING) {
            val oldest = pending.keys.firstOrNull() ?: break
            pending.remove(oldest)
        }
    }

    /** One line to a peer's file-transfer port. Fire-and-forget; a failure costs a notification. */
    private fun sendLine(ip: String, line: String) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, Ports.FILE_TRANSFER), 5000)
            val out = socket.getOutputStream()
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    /**
     * A small JPEG preview, base64-encoded, or "" when one cannot be made.
     *
     * Downsampled during decode rather than after: a full-resolution screenshot bitmap on a modern
     * phone is tens of megabytes, and this runs on every screenshot the user takes.
     */
    private fun buildThumbnail(context: Context, uri: Uri): String = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longestEdge / sample > THUMBNAIL_MAX_EDGE * 2) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }

        if (decoded == null) {
            ""
        } else {
            val scale = THUMBNAIL_MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                decoded
            }
            val buffer = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, buffer)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            val encoded = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
            // Better no preview than a rejected frame: the receiver drops metadata over the cap.
            if (encoded.length > ScreenshotWire.MAX_THUMBNAIL_B64) "" else encoded
        }
    } catch (e: Exception) {
        Log.w(TAG, "thumbnail failed: " + e.message)
        ""
    }

    private const val TAG = "ScreenshotWatcher"
}

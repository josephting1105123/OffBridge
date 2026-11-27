package com.EntropicSoftwareLab.ClipboardShare

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches MediaStore for new screenshots and posts a "Screenshot taken / Send this screenshot to
 * another device?" heads-up notification for each one, whatever devices are online. The Send action
 * opens [MainActivity] with the screenshot as an ordinary ACTION_SEND share; nothing is transferred
 * from here.
 */
class ScreenshotWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var observer: ContentObserver? = null
    private var drainJob: Job? = null
    private var pollJob: Job? = null
    @Volatile private var channel: Channel<Unit>? = null
    @Volatile private var startSec = 0L
    private var started = false

    // Both guarded by `handled`: scan() normally runs on the single drain coroutine, but a scan in
    // flight across a restart() can overlap the new one.
    private val announced = LinkedHashSet<Long>()
    private val handled = LinkedHashSet<Long>()

    fun start() {
        synchronized(lock) {
            if (started) return
            if (!isEnabled(context) || !hasReadPermission(context)) return
            startSec = System.currentTimeMillis() / 1000
            synchronized(handled) {
                announced.clear()
                handled.clear()
            }

            val ht = HandlerThread("screenshot-watch").also { it.start() }
            val ch = Channel<Unit>(Channel.CONFLATED)
            channel = ch
            val job = scope.launch(Dispatchers.IO) {
                for (ignored in ch) {
                    try {
                        scan()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Screenshot scan failed: ${e.message}")
                    }
                }
            }
            drainJob = job
            val power = context.getSystemService(PowerManager::class.java)
            pollJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val on = power?.isInteractive ?: true
                    if (on) requestScan()
                    delay(if (on) POLL_SCREEN_ON_MS else POLL_SCREEN_OFF_MS)
                }
            }
            val obs = object : ContentObserver(Handler(ht.looper)) {
                override fun onChange(selfChange: Boolean) {
                    requestScan()
                }
            }
            try {
                context.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not register screenshot observer: ${e.message}")
                pollJob?.cancel()
                pollJob = null
                job.cancel()
                ch.close()
                drainJob = null
                channel = null
                ht.quitSafely()
                return
            }
            thread = ht
            observer = obs
            started = true
            requestScan()
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            observer?.let {
                try { context.contentResolver.unregisterContentObserver(it) } catch (e: Exception) {}
            }
            observer = null
            thread?.quitSafely()
            thread = null
            pollJob?.cancel()
            pollJob = null
            drainJob?.cancel()
            drainJob = null
            channel?.close()
            channel = null
            started = false
        }
    }

    fun restart() {
        stop()
        start()
    }

    private fun requestScan() {
        channel?.trySend(Unit)
    }

    private fun scan() {
        if (!isEnabled(context)) return
        // Floor the window at 2 minutes back so a provider that ignores QUERY_ARG_LIMIT (API 29)
        // can't hand us a long tail of old screenshots to prompt about.
        val lowerBoundSec = maxOf(startSec - 10, System.currentTimeMillis() / 1000 - RECENT_WINDOW_SEC)
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(lowerBoundSec.toString(), "%Screenshots%")
            )
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, QUERY_LIMIT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.DATE_ADDED,
        )

        val rows = ArrayList<Pair<Long, Boolean>>()
        val seen = HashSet<Long>()
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, args, null
            ) ?: return
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val pendingCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val isPending = c.getInt(pendingCol) != 0
                    seen.add(id)
                    rows.add(id to isPending)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Screenshot scan lost read permission: ${e.message}")
            return
        }

        val toAnnounce = ArrayList<Long>()
        val toFinish = ArrayList<Long>()
        val toCancel = ArrayList<Long>()
        synchronized(handled) {
            for ((id, isPending) in rows) {
                if (id in handled) continue
                if (id !in announced) {
                    addCapped(announced, id)
                    toAnnounce.add(id)
                }
                if (!isPending) {
                    addCapped(handled, id)
                    toFinish.add(id)
                }
            }
            if (rows.size < QUERY_LIMIT) {
                val it = announced.iterator()
                while (it.hasNext()) {
                    val id = it.next()
                    if (id !in handled && id !in seen) {
                        it.remove()
                        toCancel.add(id)
                    }
                }
            }
        }

        for (id in toCancel) {
            context.getSystemService(NotificationManager::class.java)?.cancel(notificationIdFor(id))
        }
        for (id in toAnnounce) {
            post(id, thumbnail = null)
        }
        for (id in toFinish) {
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val thumbnail: Bitmap? = try {
                context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
            } catch (e: Exception) {
                null
            }
            val notifId = notificationIdFor(id)
            if (thumbnail != null && (id in toAnnounce || isPromptShowing(notifId))) {
                post(id, thumbnail)
            }
        }
    }

    /** Caller holds the `handled` monitor. */
    private fun addCapped(set: LinkedHashSet<Long>, id: Long) {
        set.add(id)
        while (set.size > HANDLED_CAP) {
            val it = set.iterator()
            it.next()
            it.remove()
        }
    }

    private fun isPromptShowing(notifId: Int): Boolean = try {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.activeNotifications?.any { it.id == notifId } == true
    } catch (e: Exception) {
        false
    }

    private fun post(id: Long, thumbnail: Bitmap?) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val notifId = notificationIdFor(id)

        val sendIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val sendPI = PendingIntent.getActivity(
            context, notifId, sendIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = Intent(context, ScreenshotPromptDismissReceiver::class.java)
            .setAction(ACTION_DISMISS)
            .putExtra(EXTRA_NOTIFICATION_ID, notifId)
        val dismissPI = PendingIntent.getBroadcast(
            context, notifId, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, NotificationChannels.SCREENSHOT_PROMPT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.screenshot_prompt_title))
            .setContentText(context.getString(R.string.screenshot_prompt_send))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .setContentIntent(sendPI)
            .addAction(0, context.getString(R.string.screenshot_prompt_action_send), sendPI)
            .addAction(0, context.getString(R.string.screenshot_prompt_action_dismiss), dismissPI)
        if (thumbnail != null) builder.setLargeIcon(thumbnail)

        // The user may have switched the feature off while the thumbnail loaded.
        if (!isEnabled(context)) return
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.notify(notifId, builder.build())
            Log.i(TAG, "Prompt posted for $id (thumbnail=${thumbnail != null})")
        } catch (e: SecurityException) {
            Log.w(TAG, "Screenshot prompt not posted: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ScreenshotWatcher"
        private const val POLL_SCREEN_ON_MS = 200L
        private const val POLL_SCREEN_OFF_MS = 1000L
        private const val RECENT_WINDOW_SEC = 120L
        private const val QUERY_LIMIT = 5
        private const val HANDLED_CAP = 64
        private const val NOTIFICATION_TIMEOUT_MS = 60_000L

        const val EXTRA_NOTIFICATION_ID = "com.EntropicSoftwareLab.ClipboardShare.SCREENSHOT_NOTIF_ID"
        const val ACTION_DISMISS = "com.EntropicSoftwareLab.ClipboardShare.SCREENSHOT_DISMISS"

        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(FILE_BRIDGE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_SCREENSHOT_PROMPT, false)

        /**
         * Full image read access. On API 34+ the partial "selected photos" grant does not count: it
         * can't see screenshots taken after the user's pick.
         */
        fun hasReadPermission(ctx: Context): Boolean {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        }

        fun permissionsToRequest(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        private const val ID_BASE = 0x5C0000
        private const val ID_SPAN = 100000

        fun notificationIdFor(mediaId: Long): Int = ID_BASE + (mediaId % ID_SPAN).toInt()

        /** True only for ids this class hands out, so intents from outside can't cancel other notifications. */
        fun isPromptId(id: Int): Boolean = id in ID_BASE until ID_BASE + ID_SPAN
    }
}

package com.EntropicSoftwareLab.ClipboardShare

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import com.EntropicSoftwareLab.ClipboardShare.transport.DowngradePolicy
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class ClipboardService : Service() {

    private val serviceJob = SupervisorJob()
    // SupervisorJob keeps one child's failure from cancelling its siblings (receiver, notifier,
    // senders); the handler catches anything that escapes a child so a stray throw can't crash the
    // whole service via the default uncaught-exception path.
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.w("ClipboardService", "Uncaught coroutine failure: ${throwable.message}")
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob + serviceExceptionHandler)

    private var serverSocket: ServerSocket? = null
    private lateinit var clipboardManager: ClipboardManager

    private var notificationStartTime = 0L
    private var chronometerBase = 0L

    private val CHANNEL_ID = "CLIPBOARD_SERVICE_CHANNEL_V2"
    private val PAIRING_CHANNEL_ID = "PAIRING_REQUESTS_CHANNEL_V2"

    private val pairingNotificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PairingDaemon.ACTION_TRIGGER_PAIRING_NOTIFICATION) {
                val senderName = intent.getStringExtra("SENDER_NAME") ?: "Unknown Device"
                val senderIp = intent.getStringExtra("SENDER_IP")
                val aesKey = intent.getByteArrayExtra("AES_KEY")
                val visualPin = intent.getStringExtra("VISUAL_PIN")
                val expiresAt = intent.getLongExtra(PairingDaemon.EXTRA_EXPIRES_AT, 0L)

                if (senderIp != null && aesKey != null) {
                    showPairingNotification(senderName, senderIp, aesKey, visualPin, expiresAt)
                }
            }
        }
    }

    companion object {
        const val ACTION_EXECUTE_NETWORK_PUSH = "com.example.josephclipboardclient.EXECUTE_PUSH"
        const val ACTION_EXECUTE_NETWORK_DELETE = "com.example.josephclipboardclient.EXECUTE_DELETE"
        const val ACTION_HISTORY_UPDATED = "com.example.josephclipboardclient.HISTORY_UPDATED"
        // Marks clips this app wrote itself (received sync / programmatic clear) so we can tell them
        // apart from user copies.
        const val CLIPBOARD_SOURCE_TAG = "com.example.josephclipboardclient.PROGRAMMATIC_COPY"
        // Toggled on the main thread (UI), read on IO coroutines (receive loop, notification loop);
        // @Volatile gives the cross-thread visibility a plain var lacks.
        @Volatile var isSyncEnabled = true
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val pairingFilter = IntentFilter(PairingDaemon.ACTION_TRIGGER_PAIRING_NOTIFICATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingNotificationReceiver, pairingFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pairingNotificationReceiver, pairingFilter)
        }

        // Track foreground/visible state so an incoming file OFFER can pop its accept screen
        // in-app when open, and only notify when backgrounded (see FileTransferServer). Registered
        // here because the daemon (started next) is what receives OFFERs — the tracker is live for
        // exactly that window. Idempotent across service restarts.
        AppForeground.register(application)

        TransferDaemon.start(applicationContext)
        PairingDaemon.start(applicationContext)

        startForegroundServiceNotification()
        startTcpReceiver()
        startNotificationUpdater()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXECUTE_NETWORK_PUSH -> {
                val payload = intent.getStringExtra("PAYLOAD")
                    ?: intent.getStringExtra("EXTRA_PAYLOAD")
                val targetIps = intent.getStringArrayExtra("TARGET_IPS")
                if (!payload.isNullOrEmpty()) {
                    executeNetworkPush(payload, targetIps)
                }
            }
            ACTION_EXECUTE_NETWORK_DELETE -> {
                val payload = intent.getStringExtra("PAYLOAD")
                if (!payload.isNullOrEmpty()) {
                    executeNetworkDelete(payload)
                    wipeFromOSClipboardIfMatches(payload)
                }
            }
        }
        return START_STICKY
    }

    /**
     * Defensive insurance against the Android 15+ foreground-service time limit.
     * connectedDevice has no timeout today, but if the type is ever changed back
     * to a time-limited one (or Google extends limits), the system calls this on
     * the timed-out FGS; stopping cleanly here avoids the RemoteServiceException
     * crash that an unhandled onTimeout produces. (API 35+.)
     */
    override fun onTimeout(startedId: Int, fgsType: Int) {
        stopSelf()
    }

    private fun notifyHistoryUpdated() {
        val intent = Intent(ACTION_HISTORY_UPDATED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun wipeFromOSClipboardIfMatches(toDelete: String) {
        serviceScope.launch(Dispatchers.Main) {
            try {
                val currentClip = clipboardManager.primaryClip
                if (currentClip != null && currentClip.itemCount > 0) {
                    val currentText = currentClip.getItemAt(0).text?.toString()
                    if (currentText == toDelete) {
                        val clipData = ClipData.newPlainText("", "")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val extras = PersistableBundle()
                            extras.putBoolean(CLIPBOARD_SOURCE_TAG, true)
                            clipData.description.extras = extras
                        }
                        clipboardManager.setPrimaryClip(clipData)
                    }
                }
            } catch (e: Exception) {
                Log.w("ClipboardService", "Failed to wipe clipboard match: ${e.message}")
            }
        }
    }

    private fun executeNetworkPush(text: String, targetIps: Array<String>? = null) {
        ClipboardHistoryManager.addEntry(applicationContext, text)
        notifyHistoryUpdated()
        broadcastClipboardText(text, true, targetIps)
    }

    /**
     * Seal a clipboard payload for one peer with anti-downgrade enforcement. We pin a peer the first
     * time it negotiates authenticated (AES-GCM) clipboard so a later GCM1-strip on the unauthenticated
     * UDP discovery can't push us back to malleable CBC. A pinned peer that no longer advertises GCM1
     * is a downgrade attempt → skip it (return null) rather than send the weaker mode. Fail closed.
     */
    private fun sealClipboardForPeer(ip: String, supportsAuthClipboard: Boolean, aesKey: ByteArray, plain: String): String? {
        return when (DowngradePolicy.decide(supportsAuthClipboard, SecurityVault.isClipboardGcmPinned(applicationContext, ip))) {
            DowngradePolicy.Decision.SEAL -> {
                SecurityVault.pinClipboardGcm(applicationContext, ip)
                CryptoEngine.encryptStringAuthenticated(plain, aesKey)
            }
            // CBC retired in 3.5.0: a peer not (yet) advertising GCM1 still gets an authenticated GCM
            // seal (it must update to open it) instead of the malleable unauthenticated CBC path.
            DowngradePolicy.Decision.PLAINTEXT -> CryptoEngine.encryptStringAuthenticated(plain, aesKey)
            DowngradePolicy.Decision.BLOCK -> {
                Log.w("ClipboardService", "Clipboard GCM downgrade blocked for $ip — skipping peer")
                null
            }
        }
    }

    private fun executeNetworkDelete(text: String) {
        serviceScope.launch(Dispatchers.IO) {
            val payload = "CMD_DELETE|$text"
            val pairedNodes = TransferDaemon.discoveredNodes.values.filter { SecurityVault.getKeyForNode(applicationContext, it.ip) != null }
            for (node in pairedNodes) {
                try {
                    val aesKey = SecurityVault.getKeyForNode(applicationContext, node.ip) ?: continue
                    val encryptedPayload = sealClipboardForPeer(node.ip, node.supportsAuthClipboard, aesKey, payload) ?: continue
                    val textBytes = encryptedPayload.toByteArray(Charsets.UTF_8)

                    Net.sendBytes(node.ip, Ports.CLIPBOARD, textBytes)
                } catch (e: Exception) {}
            }
        }
    }

    private fun isDarkMode(): Boolean {
        val prefs = getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
        return when (prefs.getString("theme_mode", "System Default")) {
            "Dark" -> true
            "Light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun getThemedIcon(): Int {
        return if (isDarkMode()) R.drawable.ic_clipboard_dark else R.drawable.ic_clipboard_light
    }

    private fun showSuccessNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val preview = if (text.length > 50) text.substring(0, 50) + "..." else text
        val notification = NotificationCompat.Builder(this, "CLIPBOARD_ALERT_CHANNEL_V2")
            .setContentTitle(applicationContext.getString(R.string.status_push_success_title))
            .setContentText("\"$preview\"")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2, notification)
        serviceScope.launch(Dispatchers.Main) { delay(3000); notificationManager.cancel(2) }
    }

    private fun showReceivedNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val preview = if (text.length > 50) text.substring(0, 50) + "..." else text
        val notification = NotificationCompat.Builder(this, "CLIPBOARD_ALERT_CHANNEL_V2")
            .setContentTitle(applicationContext.getString(R.string.status_clipboard_received_title))
            .setContentText("\"$preview\"")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(3, notification)
        serviceScope.launch(Dispatchers.Main) { delay(3000); notificationManager.cancel(3) }
    }

    private fun getCustomRemoteViews(statusText: String): RemoteViews {
        val views = RemoteViews(packageName, R.layout.notification_custom)
        val isNightMode = isDarkMode()

        val textColor = if (isNightMode) Color.parseColor("#FFFFFFFF") else Color.parseColor("#FF000000")
        val statusColor = if (isNightMode) Color.parseColor("#34D399") else Color.parseColor("#006400")
        val iconResId = if (isNightMode) R.drawable.ic_clipboard_dark else R.drawable.ic_clipboard_light

        views.setTextColor(R.id.notif_title, textColor)
        views.setTextColor(R.id.notif_chronometer, textColor)
        views.setTextColor(R.id.notif_status, statusColor)
        views.setImageViewResource(R.id.btn_push_text, iconResId)
        views.setInt(R.id.btn_transfer_file, "setColorFilter", textColor)

        views.setTextViewText(R.id.notif_status, statusText)
        views.setChronometer(R.id.notif_chronometer, chronometerBase, null, true)

        val pushIntent = Intent(this, ClipboardReaderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pushPending = PendingIntent.getActivity(this, 0, pushIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.btn_push_text, pushPending)

        val fileIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        val filePending = PendingIntent.getActivity(this, 1, fileIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.btn_transfer_file, filePending)

        return views
    }

    private fun startForegroundServiceNotification() {
        notificationStartTime = System.currentTimeMillis()
        chronometerBase = SystemClock.elapsedRealtime()

        val manager = getSystemService(NotificationManager::class.java)
        val serviceChannel = NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.status_channel_service), NotificationManager.IMPORTANCE_LOW)
        serviceChannel.setShowBadge(false)
        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(NotificationChannel("CLIPBOARD_ALERT_CHANNEL_V2", applicationContext.getString(R.string.status_channel_alerts), NotificationManager.IMPORTANCE_HIGH))
        val pairingChannel = NotificationChannel(PAIRING_CHANNEL_ID, applicationContext.getString(R.string.status_channel_pairing), NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(pairingChannel)
        val transferProgressChannel = NotificationChannel("FILE_TRANSFER_CHANNEL_V2", applicationContext.getString(R.string.status_channel_transfer_progress), NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(transferProgressChannel)
        val transferRequestChannel = NotificationChannel("FILE_TRANSFER_REQUEST_CHANNEL_V2", applicationContext.getString(R.string.status_channel_incoming_transfers), NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(transferRequestChannel)

        val remoteViews = getCustomRemoteViews(applicationContext.getString(R.string.status_mesh_active))
        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(this, 2, mainActivityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remoteViews)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()

        // On Android 12+/14 startForeground can throw ForegroundServiceStartNotAllowedException
        // (or SecurityException) when the app isn't allowed to start an FGS; an unhandled throw
        // crashes the service, so guard it and stop cleanly instead of crashing.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.w("ClipboardService", "startForeground failed, stopping service: ${e.message}")
            stopSelf()
        }
    }

    private fun showPairingNotification(senderName: String, senderIp: String, aesKey: ByteArray, visualPin: String?, expiresAt: Long) {
        val fullScreenIntent = Intent(this, PairingConfirmationActivity::class.java).apply {
            putExtra("SENDER_NAME", senderName)
            putExtra("SENDER_IP", senderIp)
            putExtra("AES_KEY", aesKey)
            putExtra("VISUAL_PIN", visualPin)
            putExtra(PairingDaemon.EXTRA_EXPIRES_AT, expiresAt)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 3, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notificationBuilder = NotificationCompat.Builder(this, PAIRING_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(applicationContext.getString(R.string.pairing_request_title))
            .setContentText(applicationContext.getString(R.string.pairing_request_from, senderName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(65435, notificationBuilder.build())
    }

    private fun startNotificationUpdater() {
        // Off Main (AND-06): each tick reads per-peer keys from EncryptedSharedPreferences and
        // builds RemoteViews — none of it needs the UI thread, and notify() is thread-safe.
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                // Per-iteration guard: a notify() throw (e.g. TransactionTooLargeException) must not
                // kill this loop, or the foreground notification would freeze on its last status.
                try {
                    val pairedCount = TransferDaemon.discoveredNodes.values.count { SecurityVault.getKeyForNode(applicationContext, it.ip) != null }
                    val statusText = if (!hasValidNetwork()) applicationContext.getString(R.string.status_network_disconnected)
                    else if (!isSyncEnabled) applicationContext.getString(R.string.status_receiving_paused)
                    else if (TransferDaemon.discoveredNodes.isEmpty()) applicationContext.getString(R.string.status_scanning_devices)
                    else applicationContext.getString(R.string.status_connected_devices, pairedCount)

                    val remoteViews = getCustomRemoteViews(statusText)
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val notification = NotificationCompat.Builder(this@ClipboardService, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                        .setCustomContentView(remoteViews)
                        .setOngoing(true)
                        .setSilent(true)
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .build()
                    notificationManager.notify(1, notification)
                } catch (e: Exception) {
                    Log.w("ClipboardService", "Notification update failed, retrying: ${e.message}")
                }
                delay(2000)
            }
        }
    }

    private fun hasValidNetwork(): Boolean {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) { return false }
    }

    private fun startTcpReceiver() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket()
                serverSocket?.reuseAddress = true
                serverSocket?.bind(InetSocketAddress("0.0.0.0", Ports.CLIPBOARD))
                while (isActive) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        handleIncomingClipboard(client)
                    } catch (e: Exception) {
                        // A transient accept() error must not kill the receiver for the
                        // whole process lifetime; only stop when the socket is closed.
                        if (!isActive || serverSocket?.isClosed != false) break
                        Log.w("ClipboardService", "accept() failed, continuing: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w("ClipboardService", "TCP receiver stopped: ${e.message}")
            }
        }
    }

    private fun handleIncomingClipboard(socket: Socket) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                socket.soTimeout = 10000
                if (!isSyncEnabled) {
                    socket.close()
                    return@launch
                }

                val senderIp = (socket.remoteSocketAddress as InetSocketAddress).address.hostAddress
                val aesKey = SecurityVault.getKeyForNode(applicationContext, senderIp)
                if (aesKey == null) {
                    socket.close()
                    return@launch
                }

                val inputStream: InputStream = socket.getInputStream()
                val buffer = ByteArray(8192)
                // Read the full payload until EOF instead of a single read(): a single read()
                // only returns the first TCP segment, truncating larger clipboards so decryption
                // fails. The sender closes the socket after writing (soTimeout guards stalls).
                val payloadStream = ByteArrayOutputStream()
                val maxPayloadBytes = 16 * 1024 * 1024 // 16 MiB cap to bound memory use
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    payloadStream.write(buffer, 0, bytesRead)
                    if (payloadStream.size() > maxPayloadBytes) {
                        throw IllegalStateException("Incoming clipboard exceeds $maxPayloadBytes bytes; aborting")
                    }
                }

                val payloadBytes = payloadStream.toByteArray()
                if (payloadBytes.isNotEmpty()) {
                    val encryptedText = String(payloadBytes, 0, payloadBytes.size, Charsets.UTF_8)

                    val text = try {
                        CryptoEngine.decryptString(encryptedText, aesKey)
                    } catch (e: Exception) {
                        Log.w("ClipboardService", "Failed to decrypt clipboard from $senderIp: ${e.message}")
                        null
                    }

                    if (!text.isNullOrEmpty()) {
                        if (text.startsWith("CMD_DELETE|")) {
                            val toDelete = text.substring("CMD_DELETE|".length)
                            ClipboardHistoryManager.removeEntry(applicationContext, toDelete)
                            notifyHistoryUpdated()
                            wipeFromOSClipboardIfMatches(toDelete)
                        } else {
                            ClipboardHistoryManager.addEntry(applicationContext, text)
                            notifyHistoryUpdated()
                            showReceivedNotification(text)

                            withContext(Dispatchers.Main) {
                                val clipData = ClipData.newPlainText("Clipboard Share", text)
                                val extras = PersistableBundle()
                                extras.putBoolean(CLIPBOARD_SOURCE_TAG, true)
                                clipData.description.extras = extras
                                clipboardManager.setPrimaryClip(clipData)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ClipboardService", "Incoming clipboard handling failed: ${e.message}")
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun broadcastClipboardText(text: String, isManualPush: Boolean = false, targetIps: Array<String>? = null) {
        serviceScope.launch(Dispatchers.IO) {
            var successCount = 0
            val allPairedNodes = TransferDaemon.discoveredNodes.values.filter { SecurityVault.getKeyForNode(applicationContext, it.ip) != null }
            val pairedNodes = if (targetIps != null) {
                allPairedNodes.filter { targetIps.contains(it.ip) }
            } else {
                allPairedNodes
            }

            for (node in pairedNodes) {
                try {
                    val aesKey = SecurityVault.getKeyForNode(applicationContext, node.ip) ?: continue
                    val encryptedPayload = sealClipboardForPeer(node.ip, node.supportsAuthClipboard, aesKey, text) ?: continue
                    val textBytes = encryptedPayload.toByteArray(Charsets.UTF_8)

                    if (Net.sendBytes(node.ip, Ports.CLIPBOARD, textBytes)) successCount++
                } catch (e: Exception) {}
            }

            if (isManualPush) {
                if (successCount > 0) showSuccessNotification(text)
                else withContext(Dispatchers.Main) { Toast.makeText(applicationContext, applicationContext.getString(R.string.toast_push_failed_no_devices), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        // Guard like the adjacent socket close: if registration never completed, unregisterReceiver
        // throws IllegalArgumentException ("Receiver not registered") and would crash teardown.
        try { unregisterReceiver(pairingNotificationReceiver) } catch (e: IllegalArgumentException) {}
        try { serverSocket?.close() } catch (e: Exception) {} // best-effort close on shutdown
        serviceJob.cancel()
    }
}
package com.EntropicSoftwareLab.ClipboardShare

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.EntropicSoftwareLab.ClipboardShare.transport.DowngradePolicy
import com.EntropicSoftwareLab.ClipboardShare.transport.FileSeal
import com.EntropicSoftwareLab.ClipboardShare.transport.SocketConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thrown when a peer that previously negotiated an authenticated (AES-GCM) channel is now offering
 * only the weaker path — i.e. a capability-strip downgrade attack on the unauthenticated discovery.
 * The transfer is aborted rather than continued in the clear. See [SecurityVault] anti-downgrade pins.
 */
class DowngradeBlockedException(message: String) : Exception(message)

data class DiscoveredDevice(
    val ip: String,
    val name: String,
    val os: String,
    val lastSeen: Long = 0,
    /** Peer advertised the [TransferDaemon.CLIPBOARD_GCM_CAP] token → it can decrypt authenticated
     * (AES-GCM) clipboard payloads. Defaults false so legacy Android + the Windows client stay on CBC. */
    val supportsAuthClipboard: Boolean = false,
    /** Peer advertised the [TransferDaemon.FILE_GCM_CAP] token → it can seal/open authenticated
     * (AES-GCM) bulk file streams via [FileSeal]. Defaults false so legacy peers stay plaintext. */
    val supportsAuthFile: Boolean = false
)

data class TransferSession(
    val sessionId: String,
    val senderName: String,
    val totalFiles: Int,
    val manifest: List<Pair<String, Long>>,
    val fileUris: List<String> = emptyList(),
    var targetIp: String = "",
    @Volatile var currentFileIndex: Int = 0,
    @Volatile var currentOffset: Long = 0L,
    var isIncoming: Boolean,
    @Volatile var isPaused: Boolean = false,
    @Volatile var isCancelled: Boolean = false,
    var lastUpdated: Long = System.currentTimeMillis(),
    val localFileNames: MutableList<String> = mutableListOf(),
    val transport: String = "lan",
    /**
     * Incoming off-grid sessions whose sender advertised [TransportCaps.COMPLETION_ACK]:
     * the receiver sends a TRANSFER_COMPLETE frame once every byte is on disk, and the
     * sender only declares "completed" after reading it. Stops the sender finishing on a
     * TCP write (buffer) while the receiver is still draining — the false-"done" + dead-resume bug.
     */
    @Volatile var completionAckRequired: Boolean = false,
    /**
     * Bounded auto-reconnect bookkeeping (incoming sessions). A mid-transfer TCP drop used to leave
     * the session paused until the user manually resumed; now [TransferDaemon.scheduleAutoResume]
     * re-dials automatically with backoff. [autoResumeAttempts] counts consecutive no-progress
     * retries (reset whenever [lastResumeOffset] advances), so a long transfer that blips repeatedly
     * but keeps moving never exhausts its budget while a genuinely-dead route gives up after a bound.
     */
    @Volatile var autoResumeAttempts: Int = 0,
    @Volatile var lastResumeOffset: Long = -1L
) {
    /**
     * Bumped when a new outgoing send attempt starts. A stale send coroutine — still blocked on a dead
     * socket until its ~20 s TCP write timeout — checks this in its terminal handler, so when it finally
     * fails it can't overwrite a fresh resume's "transferring" status back to "paused".
     */
    val sendGeneration = java.util.concurrent.atomic.AtomicInteger(0)
}

/**
 * Sender-side advertising context for an off-grid (Wi-Fi Direct) session.
 * Registered by the QR display screen while a group is advertising; the
 * P2P_HELLO dispatch in [TransferDaemon.handleIncomingConnection] is
 * unreachable without it — admission is by token + HMAC, not pairing keys.
 */
class P2pAdvertisingContext(
    /** 16 random bytes from SecureRandom; compared constant-time. */
    val token: ByteArray,
    /** Sender's ephemeral ECDH session (fresh per advertising window). */
    val ecdh: EcdhSession,
    val fileUris: List<String>,
    val manifest: List<Pair<String, Long>>,
    val totalFiles: Int,
    @Volatile var busy: Boolean = false,
    /** Receiver authenticated and handshake completed. */
    val onPeerConnected: (deviceName: String) -> Unit = {},
    val onTransferComplete: (sessionId: String) -> Unit = {},
    /** paused=true means the resume machinery still holds the session. */
    val onTransferError: (paused: Boolean) -> Unit = {}
)

object TransferDaemon {
    @Volatile var isRunning = false
    @Volatile var customDeviceName: String = run {
        val manufacturer = Build.MANUFACTURER?.trim() ?: ""
        val model = Build.MODEL?.trim() ?: ""
        if (model.startsWith(manufacturer, ignoreCase = true)) model
        else if (manufacturer.isNotEmpty()) "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        else model.ifEmpty { "Android Device" }
    }
    val myDeviceId = UUID.randomUUID().toString()

    val discoveredNodes = ConcurrentHashMap<String, DiscoveredDevice>()
    
    val activeSessions = ConcurrentHashMap<String, TransferSession>()

    var pendingSessionId: String = ""
    var pendingSocket: Socket? = null
    var pendingSenderName: String = ""
    var pendingTotalFiles: Int = 0
    var pendingManifest: List<Pair<String, Long>> = emptyList()

    /** Non-null only while an off-grid sender screen is advertising. */
    @Volatile
    var p2pContext: P2pAdvertisingContext? = null

    // var (not val) so [stop] can cancel the scope and a later [start] can rebuild it — a cancelled
    // CoroutineScope cannot relaunch, so the daemon needs a fresh Job/scope on restart.
    private var daemonJob = Job()
    private var daemonScope = CoroutineScope(Dispatchers.IO + daemonJob)

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Discovery stays on the fast cadence until this wall-clock deadline; bumped on daemon start
     * and whenever a new peer appears (see [wakeDiscovery]). Read by the adaptive broadcaster loop
     * so a stable network quiets down toward [DISCOVERY_INTERVAL_MAX_MS] instead of broadcasting
     * every 3s forever — the main lever for cutting constant foreground network usage.
     */
    @Volatile private var discoveryWakeUntilMs: Long = 0L

    fun start(context: Context) {
        if (isRunning) return
        isRunning = true
        // A prior stop() cancelled daemonScope; a cancelled scope can't relaunch, so rebuild the
        // Job + scope before starting fresh after a shutdown.
        if (!daemonScope.isActive) {
            daemonJob = Job()
            daemonScope = CoroutineScope(Dispatchers.IO + daemonJob)
        }
        wakeDiscovery()

        PairingDaemon.start(context)

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("JosephShare_UDP_Lock")
            multicastLock?.setReferenceCounted(true)
            multicastLock?.acquire()

            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JosephClipboardClient:TransferDaemonWakeLock")
            wakeLock?.acquire()

            wifiLock = wifiManager.createWifiLock(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "JosephClipboardClient:TransferDaemonWifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        daemonScope.launch {
            val udpSocket = DatagramSocket()
            try {
                udpSocket.broadcast = true
                var interval = DISCOVERY_INTERVAL_MIN_MS
                var lastNodeCount = -1
                while (isActive) {
                    if (com.EntropicSoftwareLab.ClipboardShare.SecurityVault.isDiscoverable(context)) {
                        val myIp = getMyIpAddress()
                        // Trailing |GCM1 advertises authenticated-clipboard support. Older Android and
                        // the Windows client parse with parts.size >= 4 and ignore the extra field, so
                        // this stays wire-compatible while letting two updated peers negotiate AES-GCM.
                        val message = "FILEBRIDGE_DISCOVERY|$customDeviceName|$myIp|Android|$DISCOVERY_CAPS".toByteArray(Charsets.UTF_8)

                        val broadcastAddresses = getAllBroadcastAddresses(context)
                        for (address in broadcastAddresses) {
                            try {
                                val packet = DatagramPacket(message, message.size, InetSocketAddress(address, Ports.DISCOVERY))
                                udpSocket.send(packet)
                            } catch (e: Exception) {
                                // Ignore send errors for individual interfaces
                            }
                        }
                    }

                    // Adaptive cadence: stay fast (MIN) right after a wake event (daemon start or a
                    // newly-seen peer) or while the discovered-device set is still changing; once the
                    // network is stable, back off geometrically toward MAX so we stop pinging every 3s.
                    // This is the primary lever that drops constant foreground Wi-Fi/data usage while
                    // keeping discovery responsive when devices actually appear or leave.
                    val nodeCount = discoveredNodes.size
                    interval = if (System.currentTimeMillis() < discoveryWakeUntilMs || nodeCount != lastNodeCount) {
                        DISCOVERY_INTERVAL_MIN_MS
                    } else {
                        (interval * 2).coerceAtMost(DISCOVERY_INTERVAL_MAX_MS)
                    }
                    lastNodeCount = nodeCount
                    delay(interval)
                }
            } catch (e: Exception) { e.printStackTrace() } finally {
                try { udpSocket.close() } catch (_: Exception) {}
            }
        }

        daemonScope.launch {
            val udpSocket = DatagramSocket(null)
            try {
                udpSocket.reuseAddress = true
                udpSocket.bind(InetSocketAddress("0.0.0.0", Ports.DISCOVERY))
                val buffer = ByteArray(1024)

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket.receive(packet)
                    // hostAddress can be null; skip rather than letting a null key poison discoveredNodes
                    val senderIp = packet.address.hostAddress ?: continue
                    val msg = String(packet.data, 0, packet.length).trim()

                    val now = System.currentTimeMillis()

                    if (msg.startsWith("FILEBRIDGE_DISCOVERY|") || msg.startsWith("FILEBRIDGE_REPLY|")) {
                        val isReply = msg.startsWith("FILEBRIDGE_REPLY|")
                        
                        if (!isReply && !com.EntropicSoftwareLab.ClipboardShare.SecurityVault.isDiscoverable(context)) {
                            continue
                        }
                        
                        val parts = msg.split("|")
                        if (parts.size >= 4) {
                            val remoteName = parts[1]
                            val remoteOs = parts[3]
                            // Optional trailing capability field (parts[4]); absent for legacy Android + Windows.
                            val caps = (parts.getOrNull(4) ?: "").split(",")
                            val supportsAuth = caps.contains(CLIPBOARD_GCM_CAP)
                            val supportsFile = caps.contains(FILE_GCM_CAP)
                            val actualIp = senderIp // Always trust the packet's source IP over the payload IP

                            if (!isMyOwnIp(actualIp)) {
                                // A peer we hadn't seen (or one that had expired and returned) snaps the
                                // broadcaster back to the fast cadence so mutual discovery completes quickly.
                                val isNewNode = !discoveredNodes.containsKey(actualIp)
                                discoveredNodes[actualIp] = DiscoveredDevice(actualIp, remoteName, remoteOs, now, supportsAuth, supportsFile)
                                if (isNewNode) wakeDiscovery()

                                // Reply to discovery packets via unicast to bypass hotspot broadcast restrictions
                                if (!isReply) {
                                    daemonScope.launch {
                                        try {
                                            val replySocket = DatagramSocket()
                                            val myIp = getMyIpAddress()
                                            val replyMsg = "FILEBRIDGE_REPLY|$customDeviceName|$myIp|Android|$DISCOVERY_CAPS".toByteArray(Charsets.UTF_8)
                                            val replyPacket = DatagramPacket(replyMsg, replyMsg.size, InetSocketAddress(senderIp, Ports.DISCOVERY))
                                            replySocket.send(replyPacket)
                                            replySocket.close()
                                        } catch (e: Exception) {} // best-effort unicast reply
                                    }
                                }
                            }
                        }
                    } else if (msg.startsWith("FILEBRIDGE_RESUME_REQUEST|")) {
                        val parts = msg.split("|")
                        if (parts.size >= 2) {
                            val targetSessionId = parts[1]
                            val resumeIntent = android.content.Intent(context, com.EntropicSoftwareLab.ClipboardShare.MainActivity::class.java).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra("SHOW_HISTORY", true)
                                putExtra("HISTORY_SESSION_ID", targetSessionId)
                            }
                            val pendingIntent = android.app.PendingIntent.getActivity(context, targetSessionId.hashCode(), resumeIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                            val notification = androidx.core.app.NotificationCompat.Builder(context, "FILE_TRANSFER_REQUEST_CHANNEL_V2")
                                .setSmallIcon(R.mipmap.ic_launcher)
                                .setContentTitle(context.getString(R.string.transfer_resume_request_title))
                                .setContentText(context.getString(R.string.transfer_resume_request_text))
                                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                                .setContentIntent(pendingIntent)
                                .setAutoCancel(true)
                                .build()
                            notificationManager.notify(65437, notification)
                        }
                    }

                    discoveredNodes.entries.removeIf { now - it.value.lastSeen > DISCOVERY_NODE_TIMEOUT_MS }
                }
            } catch (e: Exception) { e.printStackTrace() } finally {
                try { udpSocket.close() } catch (_: Exception) {}
            }
        }

        daemonScope.launch {
            val serverSocket = ServerSocket()
            try {
                serverSocket.reuseAddress = true
                // Advertise a large TCP window in the handshake so accepted sockets inherit it.
                runCatching { serverSocket.receiveBufferSize = SOCKET_BUFFER_BYTES }
                serverSocket.bind(InetSocketAddress("0.0.0.0", Ports.FILE_TRANSFER))

                while (isActive) {
                    try {
                        val clientSocket = serverSocket.accept()
                        clientSocket.tuneForBulkTransfer()
                        // Bound the connection so a peer that connects but never sends a
                        // newline can't pin a handler forever; 30s of total silence on a
                        // live transfer means a dead link. Handle off the accept loop so a
                        // slow/hostile client never blocks new connections.
                        clientSocket.soTimeout = 30000
                        daemonScope.launch { handleIncomingConnection(context, clientSocket) }
                    } catch (e: Exception) {
                        // A transient accept() error (e.g. EMFILE) must not permanently kill
                        // the listener; only stop when the socket itself is closed.
                        if (!isActive || serverSocket.isClosed) break
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() } finally {
                try { serverSocket.close() } catch (_: Exception) {}
            }
        }

        daemonScope.launch {
            val pendingReconnects = mutableMapOf<String, Long>()
            val alreadyNotified = mutableSetOf<String>()

            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                
                var newlyResumableCount = 0
                var totalResumableCount = 0

                for ((sessionId, session) in activeSessions) {
                    if (session.isPaused && session.isIncoming) {
                        val senderIp = discoveredNodes.entries.find { it.value.name == session.senderName }?.key
                        if (senderIp != null) {
                            session.targetIp = senderIp // Update IP in case it changed
                            val firstSeen = pendingReconnects.getOrPut(sessionId) { now }
                            if (now - firstSeen >= 5000) {
                                totalResumableCount++
                                if (!alreadyNotified.contains(sessionId)) {
                                    newlyResumableCount++
                                    alreadyNotified.add(sessionId)
                                }
                            }
                        } else {
                            pendingReconnects.remove(sessionId)
                            alreadyNotified.remove(sessionId)
                        }
                    } else {
                        pendingReconnects.remove(sessionId)
                        alreadyNotified.remove(sessionId)
                    }
                }

                if (newlyResumableCount > 0 && alreadyNotified.isNotEmpty()) {
                    val firstResumableSessionId = alreadyNotified.first()
                    val resumeIntent = Intent(context, com.EntropicSoftwareLab.ClipboardShare.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("SHOW_HISTORY", true)
                        putExtra("HISTORY_SESSION_ID", firstResumableSessionId)
                    }
                    val pendingIntent = PendingIntent.getActivity(context, 2, resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    
                    val title = if (totalResumableCount == 1) context.getString(R.string.transfer_resumable_one) else context.getString(R.string.transfer_resumable_many, totalResumableCount)
                    val text = context.getString(R.string.transfer_connection_restored)
                    
                    val notification = NotificationCompat.Builder(context, "FILE_TRANSFER_REQUEST_CHANNEL_V2")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setDefaults(NotificationCompat.DEFAULT_ALL)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .build()

                    notificationManager.notify(65436, notification)
                }
            }
        }
    }

    /**
     * Full shutdown — the counterpart [start] never had. Cancels the discovery / TCP-server /
     * resume coroutines and releases the three radio/CPU locks [start] acquires. Without this the
     * PARTIAL_WAKE_LOCK + Wi-Fi lock + multicast lock stayed held for the entire process lifetime
     * (pinning the CPU awake and the radio in high-power mode) with no way for the user to release
     * them short of force-stop. Idempotent; safe to call when not running. Call only on a genuine
     * app shutdown (Power Off) — the daemon is a shared singleton many screens [start]; a later
     * [start] rebuilds the scope.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        try { daemonScope.cancel() } catch (_: Exception) {}
        releaseLocks()
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
        multicastLock = null
    }

    fun executeDownloadStream(context: Context, socket: Socket, session: TransferSession, fileKey: ByteArray? = null) {
        executeDownloadStream(context, SocketConnection(socket), session, fileKey)
    }

    /**
     * V4 §2: the carrier-neutral download path. Protocol bytes are identical
     * over TCP and Bluetooth; only the duplex-stream carrier differs.
     * A non-null [fileKey] (the shared pairing key) means the bulk stream is FileSeal-sealed and is
     * decrypted here; null keeps the legacy plaintext read (off-grid + legacy peers).
     */
    fun executeDownloadStream(context: Context, connection: TransportConnection, session: TransferSession, fileKey: ByteArray? = null) {
        val manifest = session.manifest

        daemonScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
            val saveLocationUriString = prefs.getString("save_location_uri", null)
            val saveDir = if (saveLocationUriString != null) {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(saveLocationUriString))
            } else {
                null
            }

            try {
                connection.setReadTimeout(10000) // 10 second network timeout (no-op on BT)
                val inputStream = connection.input
                val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                val aeadKeySpec = fileKey?.let { FileSeal.keySpec(FileSeal.deriveKey(it)) }

                if (session.localFileNames.isEmpty()) {
                    for (m in manifest) {
                        var finalName = m.first.replace('\\', '/').substringAfterLast('/')
                        if (session.currentOffset == 0L) {
                            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                            var targetFile = java.io.File(downloadDir, finalName)
                            var counter = 1
                            val nameWithoutExt = finalName.substringBeforeLast('.')
                            val ext = if (finalName.contains('.')) "." + finalName.substringAfterLast('.') else ""
                            while (targetFile.exists()) {
                                finalName = "$nameWithoutExt ($counter)$ext"
                                targetFile = java.io.File(downloadDir, finalName)
                                counter++
                            }
                        }
                        session.localFileNames.add(finalName)
                    }
                    TransferHistoryManager.updateFileNames(context, session.sessionId, session.localFileNames)
                }

                for (i in session.currentFileIndex until manifest.size) {
                    val (fname, fsize) = manifest[i]
                    val isFolder = fname.endsWith("/")
                    
                    if (isFolder) continue

                    // Name list and manifest can drift (e.g. resumed session); guard the
                    // index so a mismatch skips the entry instead of throwing IndexOutOfBounds.
                    val finalName = session.localFileNames.getOrNull(i) ?: continue

                    // Stream into a ".part" sidecar; promote it to the real name only once every byte
                    // is on disk, so a mid-transfer drop leaves a .part instead of a truncated real file.
                    val partName = "$finalName.part"
                    val append = session.currentOffset > 0
                    val saveTree = saveDir
                    var partDoc: androidx.documentfile.provider.DocumentFile? = null
                    var partFile: java.io.File? = null
                    val outputStream = if (saveTree != null && saveTree.canWrite()) {
                        val doc = saveTree.findFile(partName) ?: saveTree.createFile("*/*", partName)
                        partDoc = doc
                        if (doc != null) {
                            context.contentResolver.openOutputStream(doc.uri, if (append) "wa" else "w")
                        } else null
                    } else {
                        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val pf = java.io.File(downloadDir, partName)
                        partFile = pf
                        java.io.FileOutputStream(pf, append)
                    }
                    
                    outputStream?.use { out ->
                        if (aeadKeySpec != null) {
                            // AEAD: read GCM records, decrypt, and append plaintext until we have the
                            // file's plaintext bytes. AEAD resume restarts the file (currentOffset==0).
                            var remaining = fsize - session.currentOffset
                            var chunkIndex = session.currentOffset / FileSeal.CHUNK_SIZE
                            while (remaining > 0) {
                                if (session.isCancelled) throw Exception("Transfer cancelled")
                                val pt = FileSeal.openChunk(aeadKeySpec, inputStream, chunkIndex, remaining)
                                out.write(pt)
                                session.currentOffset += pt.size
                                remaining -= pt.size
                                chunkIndex++
                            }
                        } else {
                            var remainingBytes = fsize - session.currentOffset
                            while (remainingBytes > 0) {
                                val toRead = if (remainingBytes > buffer.size) buffer.size else remainingBytes.toInt()
                                val read = inputStream.read(buffer, 0, toRead)
                                if (read == -1) throw java.io.IOException("Stream closed unexpectedly")
                                out.write(buffer, 0, read)
                                session.currentOffset += read
                                remainingBytes -= read
                            }
                        }
                    }

                    // Promote the completed .part to its final name (replacing any stale same-name file).
                    partDoc?.let { doc ->
                        saveTree?.findFile(finalName)?.delete()
                        doc.renameTo(finalName)
                    }
                    partFile?.let { pf ->
                        val finalFile = java.io.File(pf.parentFile, finalName)
                        if (finalFile.exists()) finalFile.delete()
                        if (pf.renameTo(finalFile)) {
                            // Raw File writes into Download never register the file in MediaStore,
                            // so images/videos land on disk but show up in no gallery. Poke the media
                            // scanner to create that entry now (best-effort).
                            indexForGallery(context, finalFile)
                        }
                    }
                    session.currentFileIndex = i + 1
                    session.currentOffset = 0
                }

                // End-to-end delivery confirmation: tell the sender every byte is on disk so it
                // declares "completed" on delivery, not on its own TCP write. Best-effort — an old
                // sender (no COMPLETION_ACK cap) never set this flag and isn't waiting for it.
                // Gate "completed" on the delivery ack actually reaching the sender. If the ack write
                // fails, the sender still sees the transfer as incomplete and will resume — so mark it
                // paused here too (keeping the session) rather than falsely declaring it completed.
                val ackDelivered = if (session.completionAckRequired) {
                    try {
                        connection.output.write("TRANSFER_COMPLETE|${session.sessionId}\n".toByteArray(Charsets.UTF_8))
                        connection.output.flush()
                        true
                    } catch (_: Exception) { false }
                } else true

                if (ackDelivered) {
                    activeSessions.remove(session.sessionId)
                    TransferHistoryManager.updateStatus(context, session.sessionId, "completed", manifest.size)
                } else {
                    session.isPaused = true
                    session.lastUpdated = System.currentTimeMillis()
                    TransferHistoryManager.updateStatus(context, session.sessionId, "paused", session.currentFileIndex, session.currentOffset)
                }

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                if (session.isCancelled) {
                    TransferHistoryManager.updateStatus(context, session.sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
                } else {
                    session.isPaused = true
                    session.lastUpdated = System.currentTimeMillis()
                    TransferHistoryManager.updateStatus(context, session.sessionId, "paused", session.currentFileIndex, session.currentOffset)
                    // Transient drop while the L2 route persists → re-dial automatically (bounded +
                    // backed off). A genuinely dead route makes every attempt fail with no progress
                    // and the session is left paused for a manual resume after the budget runs out.
                    scheduleAutoResume(context, session)
                }
            } finally {
                try { connection.close() } catch(e:Exception){}
                // If the receiver backgrounded this download (left the camera), release the Wi-Fi
                // Direct joiner it held now that the download reached a terminal state. No-op when the
                // screen is still resident (never handed off).
                com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridLiveTransfer.finishIfArmed(session.sessionId)
            }
        }
    }

    /**
     * Make a finished download visible to the gallery / Files app. The receiver writes to Download
     * via the raw File API, which never inserts a MediaStore row — so images and videos sit on disk
     * but appear in no gallery until some eventual system scan. Hand the file to the media scanner
     * explicitly to create that row now. It infers the MIME from the file (so non-media files are
     * harmless) and keys on the path (so re-scanning the same file is idempotent). No storage
     * permission is needed since we own the file we just wrote. Best-effort: indexing must never
     * fail the transfer.
     *
     * Only covers the default Download path. A user-chosen SAF location writes through the documents
     * provider and has no stable absolute path to scan, so it keeps its existing behaviour.
     */
    private fun indexForGallery(context: Context, file: java.io.File) {
        try {
            android.media.MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(file.absolutePath),
                null,
                null
            )
        } catch (_: Exception) { }
    }

    /**
     * Returns the shared pairing key for [ip] iff a FileSeal (AES-GCM) bulk-file stream should be
     * used: the peer advertised the FGCM1 capability AND we hold its pairing key. Null ⇒ fall back to
     * the plaintext stream. The receiver calls this to decide whether to request "fgcm" on
     * ACCEPT_OFFER / RESUME_REQUEST. (Off-grid never reaches here — it uses an ECDH token key, not a
     * stored pairing key, so it stays plaintext by construction.)
     */
    private fun fileKeyIfAead(context: Context, ip: String): ByteArray? {
        val key = SecurityVault.getKeyForNode(context, ip) ?: return null
        return when (DowngradePolicy.decide(
            peerOffersAead = discoveredNodes[ip]?.supportsAuthFile == true,
            pinned = SecurityVault.isFileGcmPinned(context, ip)
        )) {
            // TOFU: pin so a later FGCM1-strip on the unauthenticated discovery can't downgrade us.
            DowngradePolicy.Decision.SEAL -> { SecurityVault.pinFileGcm(context, ip); key }
            DowngradePolicy.Decision.PLAINTEXT -> null
            // Peer dropped FGCM1 after a prior sealed transfer ⇒ downgrade attempt. Fail closed.
            DowngradePolicy.Decision.BLOCK ->
                throw DowngradeBlockedException("FileSeal downgrade blocked for $ip (peer no longer advertises FGCM1)")
        }
    }

    /**
     * Sender-side counterpart to [fileKeyIfAead]: decides whether to seal the outgoing bulk stream.
     * [fgcmRequested] is true iff the receiver appended "fgcm" to ACCEPT_OFFER / RESUME_REQUEST.
     * Returns the pairing key to seal with, or null for a legitimate plaintext stream (the peer never
     * negotiated AEAD). Throws [DowngradeBlockedException] if the receiver dropped "fgcm" after a
     * prior sealed transfer — refusing to stream in the clear. The trust gate guarantees we hold the key.
     */
    private fun senderFileKey(context: Context, ip: String, fgcmRequested: Boolean): ByteArray? {
        return when (DowngradePolicy.decide(
            peerOffersAead = fgcmRequested,
            pinned = SecurityVault.isFileGcmPinned(context, ip)
        )) {
            DowngradePolicy.Decision.SEAL -> { SecurityVault.pinFileGcm(context, ip); SecurityVault.getKeyForNode(context, ip) }
            DowngradePolicy.Decision.PLAINTEXT -> null
            DowngradePolicy.Decision.BLOCK ->
                throw DowngradeBlockedException("FileSeal downgrade blocked (sender) for $ip (receiver dropped fgcm)")
        }
    }

    fun resumeIncomingTransfer(context: Context, sessionId: String) {
        val session = activeSessions[sessionId] ?: return
        if (!session.isIncoming || !session.isPaused) return
        
        session.isPaused = false
        TransferHistoryManager.updateStatus(context, sessionId, "transferring")
        
        daemonScope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                val fileKey = fileKeyIfAead(context, session.targetIp)
                socket = Socket(session.targetIp, Ports.FILE_TRANSFER)
                socket.soTimeout = 10000
                val outStream = socket.getOutputStream()
                var trueOffset = session.currentOffset
                if (fileKey != null) {
                    // FileSeal records are not byte-seekable, so an AEAD resume restarts the
                    // in-progress file from chunk 0 (already-completed files are promoted out of the
                    // .part and are kept). offset 0 makes executeDownloadStream reopen the .part in
                    // overwrite mode, discarding the partial plaintext.
                    trueOffset = 0L
                } else if (session.currentFileIndex < session.localFileNames.size) {
                    val finalName = session.localFileNames[session.currentFileIndex]
                    val prefs = context.getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)
                    val saveLocationUriString = prefs.getString("save_location_uri", null)
                    if (saveLocationUriString != null) {
                        val saveDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(saveLocationUriString))
                        val targetFile = saveDir?.findFile(finalName)
                        if (targetFile != null) {
                            trueOffset = targetFile.length()
                        }
                    } else {
                        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val targetFile = java.io.File(downloadDir, finalName)
                        if (targetFile.exists()) {
                            trueOffset = targetFile.length()
                        }
                    }
                }
                session.currentOffset = trueOffset

                val resumeCmd = if (fileKey != null)
                    "RESUME_REQUEST|${session.sessionId}|${session.currentFileIndex}|${session.currentOffset}|fgcm\n"
                else
                    "RESUME_REQUEST|${session.sessionId}|${session.currentFileIndex}|${session.currentOffset}\n"
                outStream.write(resumeCmd.toByteArray(Charsets.UTF_8))
                outStream.flush()

                executeDownloadStream(context, socket, session, fileKey)
            } catch (e: DowngradeBlockedException) {
                // The sender's FileSeal capability vanished after a prior sealed transfer — refuse the
                // bytes in the clear. Terminal (cancelled), not paused, so we don't auto-retry into it.
                TransportDiag.log("RX: $e")
                try { socket?.close() } catch (_: Exception) {}
                session.isPaused = false
                TransferHistoryManager.updateStatus(context, sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                try { socket?.close() } catch (_: Exception) {}
                session.isPaused = true
                TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                // A failed reconnect counts toward the bounded budget and backs off; only surface the
                // "couldn't reach the sender" toast once the budget is spent (or the user cancelled).
                if (!scheduleAutoResume(context, session)) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, context.getString(R.string.transfer_failed_connect_sender), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun acceptOffer(context: Context, sessionId: String) {
        val session = activeSessions[sessionId] ?: return
        if (!session.isIncoming) return
        
        session.isPaused = false
        TransferHistoryManager.addRecord(context, TransferRecord(
            sessionId = sessionId,
            deviceName = session.senderName,
            direction = "incoming",
            fileNames = session.manifest.map { it.first.replace('\\', '/').substringAfterLast('/') },
            totalBytes = session.manifest.sumOf { it.second },
            timestamp = System.currentTimeMillis(),
            status = "transferring",
            filesCompleted = 0,
            totalFiles = session.totalFiles,
            fileSizes = session.manifest.map { it.second },
            currentFileOffset = 0L
        ))
        
        daemonScope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                val fileKey = fileKeyIfAead(context, session.targetIp)
                socket = Socket(session.targetIp, Ports.FILE_TRANSFER)
                socket.soTimeout = 10000
                val outStream = socket.getOutputStream()
                // Request authenticated (FileSeal) file bytes when both peers support it; "fgcm" tells
                // the sender to seal. Legacy senders ignore the extra field and stream plaintext.
                val acceptCmd = if (fileKey != null) "ACCEPT_OFFER|${session.sessionId}|fgcm\n" else "ACCEPT_OFFER|${session.sessionId}\n"
                outStream.write(acceptCmd.toByteArray(Charsets.UTF_8))
                outStream.flush()

                executeDownloadStream(context, socket, session, fileKey)
            } catch (e: DowngradeBlockedException) {
                // The sender's FileSeal capability vanished after a prior sealed transfer — refuse the
                // bytes in the clear. Terminal (cancelled), not paused, so we don't auto-retry into it.
                TransportDiag.log("RX: $e")
                try { socket?.close() } catch (_: Exception) {}
                session.isPaused = false
                TransferHistoryManager.updateStatus(context, sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                try { socket?.close() } catch (_: Exception) {}
                session.isPaused = true
                TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                // A momentary failure on the initial accept-connect auto-retries (bounded + backed off)
                // before the transfer is left paused for a manual resume.
                scheduleAutoResume(context, session)
            }
        }
    }

    fun declineOffer(context: Context, sessionId: String) {
        val session = activeSessions.remove(sessionId) ?: return
        if (!session.isIncoming) return

        daemonScope.launch(Dispatchers.IO) {
            Net.sendBytes(session.targetIp, Ports.FILE_TRANSFER, "DECLINE_OFFER|${session.sessionId}\n".toByteArray(Charsets.UTF_8), 5000)
        }
    }

    private fun getMyIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in Collections.list(interfaces)) {
                val addrs = intf.inetAddresses
                for (addr in Collections.list(addrs)) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        // Parenthesize: `?:` binds looser than `<`, so the elvis must be grouped
                        // before the comparison or it parses as `sAddr ?: (-1 < 0)`.
                        val isIPv4 = (sAddr?.indexOf(':') ?: -1) < 0
                        if (isIPv4) return sAddr ?: "127.0.0.1"
                    }
                }
            }
        } catch (ex: Exception) {
            // Ignore
        }
        return "127.0.0.1"
    }

    private fun isMyOwnIp(ip: String?): Boolean {
        if (ip == null || ip == "127.0.0.1" || ip == "0.0.0.0") return true
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in Collections.list(interfaces)) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (addr.hostAddress == ip) return true
                }
            }
        } catch (ex: Exception) {
            // Ignore
        }
        return false
    }

    private fun getAllBroadcastAddresses(context: Context): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifiManager.dhcpInfo
            if (dhcp != null && dhcp.ipAddress != 0) {
                val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                val quads = ByteArray(4)
                for (k in 0..3) quads[k] = ((broadcast shr k * 8) and 0xFF).toByte()
                addresses.add(InetAddress.getByAddress(quads))
            }
        } catch (e: Exception) {}

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in Collections.list(interfaces)) {
                if (intf.isLoopback || !intf.isUp) continue
                for (address in intf.interfaceAddresses) {
                    val broadcast = address.broadcast
                    if (broadcast != null && !addresses.contains(broadcast)) {
                        addresses.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {}
        
        // Add a global broadcast fallback just in case
        try {
            val globalBroadcast = InetAddress.getByName("255.255.255.255")
            if (!addresses.contains(globalBroadcast)) {
                addresses.add(globalBroadcast)
            }
        } catch (e: Exception) {}

        if (addresses.isEmpty()) {
            try { addresses.add(InetAddress.getByName("255.255.255.255")) } catch (e: Exception) {}
        }
        
        return addresses
    }

    private fun handleIncomingConnection(context: Context, socket: Socket) {
        try {
            val inputStream = socket.getInputStream()
            val batchHeader = readUntilNewline(inputStream)

            // Off-grid handshake authenticates itself with the QR token + ECDH HMAC (inside
            // handleP2pHello, which is stronger than IP pairing) and arrives from an ephemeral
            // Wi-Fi Direct IP that is never a paired node. It MUST be dispatched BEFORE the LAN
            // trust gate below — this is the "separate P2P/QR-NFC path" the gate was always meant
            // to leave unaffected. Gating it ahead of this read closed the socket before the HELLO
            // was even parsed, which silently broke every off-grid Wi-Fi Direct transfer
            // (TCP connected, then HANDSHAKE_FAILED with no P2P_WELCOME).
            if (batchHeader.startsWith("P2P_HELLO|")) {
                handleP2pHello(context, SocketConnection(socket), batchHeader)
                return
            }

            // Trust gate: only paired devices may push files / drive session control over the LAN
            // — same check ClipboardService uses for clipboard pushes. Closes the "any host on the
            // network can push files" hole. (Off-grid P2P_HELLO is handled above, so it is exempt.)
            val senderIp = (socket.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress
            if (senderIp == null || SecurityVault.getKeyForNode(context, senderIp) == null) {
                // If the trace shows this during an off-grid send, the receiver's HELLO arrived
                // garbled/partial (didn't match "P2P_HELLO|") and got caught by the LAN trust gate.
                TransportDiag.log("TX: incoming '${batchHeader.take(16)}' from $senderIp blocked — not a paired LAN node")
                socket.close()
                return
            }

            if (batchHeader.startsWith("RESUME_REQUEST|")) {
                val parts = batchHeader.split('|')
                if (parts.size >= 4) {
                    val sessionId = parts[1]
                    // Network-supplied tokens: parse defensively so malformed input aborts
                    // this connection instead of throwing NumberFormatException.
                    val fileIndex = parts[2].toIntOrNull()
                    val offset = parts[3].toLongOrNull()
                    if (fileIndex == null || offset == null) { socket.close(); return }
                    // Receiver flags after the offset, order-independent: "ack" → wait for the delivery
                    // confirmation before declaring complete; "fgcm" → the bulk stream is FileSeal-sealed.
                    val flags = parts.drop(4)
                    val ackExpected = flags.contains("ack")
                    val resumeFileKey = try {
                        senderFileKey(context, senderIp, flags.contains("fgcm"))
                    } catch (e: DowngradeBlockedException) {
                        TransportDiag.log("TX: $e — refusing plaintext resume")
                        socket.close(); return
                    }

                    val session = activeSessions[sessionId]
                    if (session != null && !session.isIncoming) {
                        // Bounds-check the network-supplied resume position before trusting it, so a
                        // bad (fileIndex, offset) can't make the sender skip past or re-send file data.
                        if (fileIndex < 0 || fileIndex >= session.fileUris.size || offset < 0L) {
                            socket.close(); return
                        }
                        session.isPaused = false
                        session.currentFileIndex = fileIndex
                        session.currentOffset = offset
                        // Reflect the resume on the sender immediately (mirrors ACCEPT_OFFER) so the
                        // history detail flips Paused→Transferring now, not only on completion.
                        TransferHistoryManager.updateStatus(context, sessionId, "transferring")
                        val myGen = session.sendGeneration.incrementAndGet()

                        daemonScope.launch(Dispatchers.IO) {
                            try {
                                val outStream = socket.getOutputStream()
                                
                                val ctx = context
                                val senderIntent = Intent(ctx, MainActivity::class.java).apply {
                                    action = Intent.ACTION_SEND
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }
                                val pendingSenderIntent = PendingIntent.getActivity(ctx, 2, senderIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                val notifMgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                val senderNotif = NotificationCompat.Builder(ctx, "FILE_TRANSFER_REQUEST_CHANNEL_V2")
                                    .setSmallIcon(R.mipmap.ic_launcher)
                                    .setContentTitle(ctx.getString(R.string.transfer_sending_resumed_title))
                                    .setContentText(ctx.getString(R.string.transfer_sending_resumed_text))
                                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                                    .setContentIntent(pendingSenderIntent)
                                    .setAutoCancel(true)
                                    .build()
                                notifMgr.notify(65435, senderNotif)

                                streamSessionFiles(context, session, outStream, resumeFileKey)

                                if (ackExpected && !awaitCompletionAck(socket, sessionId)) {
                                    // Resent bytes never confirmed — pause and keep the session so
                                    // the receiver can resume again rather than hit a dead session.
                                    session.isPaused = true
                                    session.lastUpdated = System.currentTimeMillis()
                                    TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                                } else {
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.transfer_resumed_complete), android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    TransferHistoryManager.updateStatus(context, sessionId, "completed", session.fileUris.size)
                                    // Clean up sender session on success
                                    activeSessions.remove(sessionId)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                if (session.isCancelled) {
                                    TransferHistoryManager.updateStatus(context, sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
                                } else if (session.sendGeneration.get() == myGen) {
                                    // A newer resume may have superseded this stale send — only the
                                    // current generation may mark the session paused.
                                    session.isPaused = true
                                    session.lastUpdated = System.currentTimeMillis()
                                    TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                                }
                            } finally {
                                try { socket.close() } catch(e:Exception){}
                            }
                        }
                    }
                }
                return
            }

            if (batchHeader.startsWith("ACCEPT_OFFER|")) {
                val parts = batchHeader.split('|')
                if (parts.size >= 2) {
                    val sessionId = parts[1]
                    val session = activeSessions[sessionId]
                    if (session != null && !session.isIncoming) {
                        session.isPaused = false
                        // Receiver appended "fgcm" ⇒ seal the bulk stream with FileSeal under the shared
                        // pairing key (the trust gate above guarantees we hold it). Absent ⇒ plaintext,
                        // unless we'd previously sealed for this peer (pin) → refuse the downgrade.
                        val fileKey = try {
                            senderFileKey(context, senderIp, parts.drop(2).contains("fgcm"))
                        } catch (e: DowngradeBlockedException) {
                            TransportDiag.log("TX: $e — refusing plaintext stream")
                            socket.close(); return
                        }
                        val myGen = session.sendGeneration.incrementAndGet()
                        TransferHistoryManager.updateStatus(context, sessionId, "transferring")

                        daemonScope.launch(Dispatchers.IO) {
                            try {
                                val outStream = socket.getOutputStream()
                                streamSessionFiles(context, session, outStream, fileKey)
                                activeSessions.remove(sessionId)
                                TransferHistoryManager.updateStatus(context, sessionId, "completed", session.fileUris.size)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                if (session.isCancelled) {
                                    TransferHistoryManager.updateStatus(context, sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
                                } else if (session.sendGeneration.get() == myGen) {
                                    session.isPaused = true
                                    TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                                }
                            } finally {
                                try { socket.close() } catch(e:Exception){}
                            }
                        }
                    }
                }
                return
            }

            if (batchHeader.startsWith("DECLINE_OFFER|")) {
                val parts = batchHeader.split('|')
                if (parts.size >= 2) {
                    val sessionId = parts[1]
                    val session = activeSessions.remove(sessionId)
                    if (session != null && !session.isIncoming) {
                        session.isCancelled = true
                        TransferHistoryManager.updateStatus(context, sessionId, "declined")
                    }
                }
                socket.close()
                return
            }

            if (batchHeader.startsWith("CANCEL_TRANSFER|")) {
                val parts = batchHeader.split('|')
                if (parts.size >= 2) {
                    val sessionId = parts[1]
                    val session = activeSessions.remove(sessionId)
                    if (session != null) {
                        session.isCancelled = true
                        TransferHistoryManager.updateStatus(context, sessionId, "cancelled")
                    }
                }
                socket.close()
                return
            }

            if (!batchHeader.startsWith("BATCH|") && !batchHeader.startsWith("OFFER|")) {
                socket.close()
                return
            }

            val isOffer = batchHeader.startsWith("OFFER|")
            val parts = batchHeader.split('|')
            val sessionId = if (parts.size > 3) parts[1] else UUID.randomUUID().toString()
            val senderNameIdx = if (parts.size > 3) 2 else 1
            val totalFilesIdx = if (parts.size > 3) 3 else 2
            
            val senderName = parts[senderNameIdx]
            // Network-supplied count: bail out of this connection on malformed input
            // rather than throwing NumberFormatException.
            val totalFiles = parts[totalFilesIdx].toIntOrNull() ?: run { socket.close(); return }

            val manifestHeader = readUntilNewline(inputStream)
            val manifestParts = manifestHeader.replace("MANIFEST|", "").split('|')
            val fileList = mutableListOf<Pair<String, Long>>()

            for (item in manifestParts) {
                if (item.contains(':')) {
                    val lastColonIndex = item.lastIndexOf(':')
                    val fname = item.substring(0, lastColonIndex)
                    // Skip malformed manifest entries instead of throwing NumberFormatException.
                    val fsize = item.substring(lastColonIndex + 1).toLongOrNull() ?: continue
                    fileList.add(Pair(fname, fsize))
                }
            }

            val newSession = TransferSession(
                sessionId = sessionId,
                senderName = senderName,
                totalFiles = totalFiles,
                manifest = fileList,
                targetIp = socket.inetAddress.hostAddress ?: "",
                isIncoming = true
            )
            activeSessions[sessionId] = newSession

            if (isOffer) {
                val outStream = socket.getOutputStream()
                outStream.write("OFFER_RECEIVED\n".toByteArray(Charsets.UTF_8))
                outStream.flush()
                socket.close()
                
                val requestIntent = Intent(context, TransferRequestActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("SESSION_ID", sessionId)
                    putExtra("SENDER_NAME", senderName)
                    putExtra("TOTAL_FILES", totalFiles)
                }
                
                val pendingRequest = PendingIntent.getActivity(context, sessionId.hashCode(), requestIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notification = NotificationCompat.Builder(context, "FILE_TRANSFER_REQUEST_CHANNEL_V2")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(context.getString(R.string.transfer_incoming_title))
                    .setContentText(context.getString(R.string.transfer_incoming_text, senderName, totalFiles))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(pendingRequest)
                    .setFullScreenIntent(pendingRequest, true)
                    .setAutoCancel(true)
                    .build()
                
                notificationManager.notify(sessionId.hashCode(), notification)
                return
            }

            TransferHistoryManager.addRecord(context, TransferRecord(
                sessionId = sessionId,
                deviceName = senderName,
                direction = "incoming",
                fileNames = fileList.map { it.first.replace('\\', '/').substringAfterLast('/') },
                totalBytes = fileList.sumOf { it.second },
                timestamp = System.currentTimeMillis(),
                status = "transferring",
                filesCompleted = 0,
                totalFiles = totalFiles,
                fileSizes = fileList.map { it.second },
                currentFileOffset = 0L
            ))

            val intent = Intent(context, com.EntropicSoftwareLab.ClipboardShare.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("SHOW_HISTORY", true)
                putExtra("HISTORY_SESSION_ID", sessionId)
            }

            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(context, "FILE_TRANSFER_REQUEST_CHANNEL_V2")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.transfer_receiving_title))
                .setContentText(context.getString(R.string.transfer_receiving_text, senderName, totalFiles))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(sessionId.hashCode(), notification)
            
            executeDownloadStream(context, socket, newSession)

        } catch (e: Exception) {
            socket.close()
        }
    }

    /**
     * §2.3 protocol extension. Off-grid handshake, sender side:
     *
     *   Receiver → P2P_HELLO|token|receiverPubKey|deviceName|HMAC(K, token)
     *   Sender   → P2P_WELCOME|sessionId|HMAC(K, token || "ack")  (or P2P_BUSY)
     *
     * then OFFER + MANIFEST on the same socket, receiver replies
     * ACCEPT_OFFER|sessionId on the same socket (no reconnect — there is no
     * discovery to re-resolve the peer), then raw 8 KiB chunk streaming.
     *
     * The paired-device key check is bypassed: admission is by token + HMAC.
     * This branch is unreachable without a registered advertising context.
     */
    /**
     * Generic entry point for an out-of-band [TransportConnection] (one accepted outside the main
     * 0.0.0.0 TCP listener): it is dispatched into the same P2P_HELLO protocol the TCP listener
     * speaks. Reads the first line on IO and routes; non-HELLO traffic is dropped. (Carrier-agnostic
     * — the retired BT data tier used this; a future network-bound SoftAP server would reuse it.)
     */
    fun dispatchTransportConnection(context: Context, connection: TransportConnection) {
        daemonScope.launch(Dispatchers.IO) {
            try {
                val header = readUntilNewline(connection.input)
                if (header.startsWith("P2P_HELLO|")) {
                    handleP2pHello(context, connection, header)
                } else {
                    connection.close()
                }
            } catch (_: Exception) {
                try { connection.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleP2pHello(context: Context, connection: TransportConnection, header: String) {
        TransportDiag.log("TX: P2P_HELLO received from ${connection.remoteIdentifier} — validating")
        val ctx = p2pContext
        if (ctx == null) {
            // A receiver hosting as GO can dial its own GO IP and reach THIS server (no send context
            // here). Name that case so it isn't mis-triaged as "sender closed / token mismatch".
            val remote = connection.remoteIdentifier
            if (remote.isNotEmpty() && remote in Net.localIpv4Addresses()) {
                TransportDiag.log("TX: P2P_HELLO dropped — self-connection (dialed our own GO IP $remote)")
            } else {
                TransportDiag.log("TX: P2P_HELLO dropped — no active send context (p2pContext=null)")
            }
            try { connection.close() } catch (_: Exception) {}
            return
        }

        val parts = header.split('|')
        if (parts.size < 5) {
            TransportDiag.log("TX: P2P_HELLO dropped — malformed (${parts.size} fields)")
            try { connection.close() } catch (_: Exception) {}
            return
        }

        if (ctx.busy) {
            TransportDiag.log("TX: P2P_HELLO — already busy with another peer → sent P2P_BUSY")
            // A peer is already in HANDSHAKE/TRANSFERRING (edge case 9).
            daemonScope.launch(Dispatchers.IO) {
                try {
                    connection.output.run {
                        write("P2P_BUSY\n".toByteArray(Charsets.UTF_8))
                        flush()
                    }
                } catch (_: Exception) {
                } finally {
                    try { connection.close() } catch (_: Exception) {}
                }
            }
            return
        }

        val tokenBytes: ByteArray
        val receiverPubKey: ByteArray
        val helloHmac: ByteArray
        try {
            tokenBytes = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
            receiverPubKey = android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP)
            helloHmac = android.util.Base64.decode(parts[4], android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            TransportDiag.log("TX: P2P_HELLO dropped — base64 decode failed")
            try { connection.close() } catch (_: Exception) {}
            return
        }
        val receiverName = parts[3].ifEmpty { "Unknown Device" }

        // Token comparison must be constant-time (stale/expired tokens are
        // silently ignored — edge case 8: receiver times out client-side).
        if (!java.security.MessageDigest.isEqual(tokenBytes, ctx.token)) {
            TransportDiag.log("TX: P2P_HELLO dropped — TOKEN mismatch (stale QR / wrong session)")
            try { connection.close() } catch (_: Exception) {}
            return
        }

        // K = SHA-256(ECDH(senderPriv, receiverPub)) — same derivation as pairing.
        val sharedKey = try {
            ctx.ecdh.deriveSecrets(receiverPubKey).first
        } catch (_: Exception) {
            TransportDiag.log("TX: P2P_HELLO dropped — ECDH key derivation failed")
            try { connection.close() } catch (_: Exception) {}
            return
        }

        val expectedHmac = hmacSha256(sharedKey, tokenBytes)
        if (!java.security.MessageDigest.isEqual(helloHmac, expectedHmac)) {
            TransportDiag.log("TX: P2P_HELLO dropped — HMAC mismatch (key disagreement)")
            try { connection.close() } catch (_: Exception) {}
            return
        }
        TransportDiag.log("TX: P2P_HELLO OK from '$receiverName' → sending P2P_WELCOME + OFFER")

        ctx.busy = true
        val sessionId = UUID.randomUUID().toString()

        daemonScope.launch(Dispatchers.IO) {
            try {
                connection.setReadTimeout(30000)
                val outStream = connection.output

                val ack = hmacSha256(sharedKey, tokenBytes + "ack".toByteArray(Charsets.UTF_8))
                val welcome = "P2P_WELCOME|$sessionId|${android.util.Base64.encodeToString(ack, android.util.Base64.NO_WRAP)}\n"
                outStream.write(welcome.toByteArray(Charsets.UTF_8))

                // Existing single-socket sequence: OFFER, MANIFEST.
                // Sanitize the device name so a '|'/newline in it can't desync the positional parse.
                val offerHeader = "OFFER|$sessionId|${customDeviceName.replace('|', ' ').replace('\n', ' ').replace('\r', ' ')}|${ctx.totalFiles}\n"
                var manifestStr = "MANIFEST|"
                for (item in ctx.manifest) {
                    manifestStr += "${item.first}:${item.second}|"
                }
                manifestStr += "\n"
                outStream.write(offerHeader.toByteArray(Charsets.UTF_8))
                outStream.write(manifestStr.toByteArray(Charsets.UTF_8))
                outStream.flush()
                TransportDiag.log("TX: P2P_WELCOME+OFFER+MANIFEST flushed — awaiting ACCEPT_OFFER (30s)")

                val session = TransferSession(
                    sessionId = sessionId,
                    senderName = customDeviceName,
                    totalFiles = ctx.totalFiles,
                    manifest = ctx.manifest,
                    fileUris = ctx.fileUris,
                    targetIp = connection.remoteIdentifier,
                    isIncoming = false,
                    transport = "p2p"
                )
                activeSessions[sessionId] = session

                TransferHistoryManager.addRecord(context, TransferRecord(
                    sessionId = sessionId,
                    deviceName = receiverName,
                    direction = "outgoing",
                    fileNames = ctx.manifest.map { it.first.substringAfterLast('/') },
                    totalBytes = ctx.manifest.sumOf { it.second },
                    timestamp = System.currentTimeMillis(),
                    status = "pending",
                    filesCompleted = 0,
                    totalFiles = ctx.totalFiles,
                    fileSizes = ctx.manifest.map { it.second },
                    currentFileOffset = 0L,
                    transport = "p2p"
                ))

                ctx.onPeerConnected(receiverName)

                // Receiver replies ACCEPT_OFFER on the same socket. A COMPLETION_ACK-capable
                // receiver appends "|ack" — it will send TRANSFER_COMPLETE once everything is
                // on disk, so we hold off declaring completion until then.
                val reply = readUntilNewline(connection.input)
                val replyParts = reply.split('|')
                if (replyParts.getOrNull(0) != "ACCEPT_OFFER" || replyParts.getOrNull(1) != sessionId) {
                    TransportDiag.log("TX: expected ACCEPT_OFFER, got '${reply.take(24)}' → aborting")
                    activeSessions.remove(sessionId)
                    TransferHistoryManager.updateStatus(context, sessionId, "declined")
                    ctx.onTransferError(false)
                    return@launch
                }
                val ackExpected = replyParts.getOrNull(2) == "ack"

                TransportDiag.log("TX: ACCEPT_OFFER received → streaming files")
                TransferHistoryManager.updateStatus(context, sessionId, "transferring")
                val myGen = session.sendGeneration.incrementAndGet()

                try {
                    streamSessionFiles(context, session, outStream)
                    if (ackExpected && !awaitCompletionAck(connection, sessionId)) {
                        // Bytes were written but the receiver never confirmed delivery (late drop).
                        // Keep the session paused so the receiver's resume can reconnect to it.
                        session.isPaused = true
                        session.lastUpdated = System.currentTimeMillis()
                        TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                        ctx.onTransferError(true)
                    } else {
                        activeSessions.remove(sessionId)
                        TransferHistoryManager.updateStatus(context, sessionId, "completed", session.fileUris.size)
                        ctx.onTransferComplete(sessionId)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (session.isCancelled) {
                        TransferHistoryManager.updateStatus(context, sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
                        ctx.onTransferError(false)
                    } else if (session.sendGeneration.get() == myGen) {
                        // Only the current send generation may pause the session — a stale write here
                        // (this send timed out ~20 s after a drop) must not clobber a fresh resume.
                        session.isPaused = true
                        session.lastUpdated = System.currentTimeMillis()
                        TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                        ctx.onTransferError(true)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                TransportDiag.log("TX: P2P handshake error after WELCOME: ${e.javaClass.simpleName} ${e.message ?: ""}")
                ctx.onTransferError(false)
            } finally {
                try { connection.close() } catch (_: Exception) {}
                ctx.busy = false
                // If the sender backgrounded this transfer (left the QR/NFC screen), release the
                // Wi-Fi Direct group it held now that the upload reached a terminal state. No-op when
                // the screen is still resident (never handed off).
                com.EntropicSoftwareLab.ClipboardShare.offgrid.OffGridLiveTransfer.finishIfArmed(sessionId)
            }
        }
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun streamSessionFiles(context: Context, session: TransferSession, outStream: OutputStream, fileKey: ByteArray? = null) {
        // Non-null fileKey ⇒ seal the bulk stream per-chunk with FileSeal (negotiated via the FGCM1
        // capability + the "fgcm" ACCEPT/RESUME flag); otherwise bytes go in the clear (legacy peers
        // and the off-grid ECDH path, which both pass null).
        val aeadKeySpec = fileKey?.let { FileSeal.keySpec(FileSeal.deriveKey(it)) }
        for (i in session.currentFileIndex until session.fileUris.size) {
            val uri = android.net.Uri.parse(session.fileUris[i])
            context.contentResolver.openInputStream(uri)?.use { fileIn ->
                if (session.currentOffset > 0) {
                    // InputStream.skip may skip fewer bytes than requested; loop until the
                    // full offset is consumed (falling back to read-and-discard) so resume
                    // doesn't start mid-file and corrupt the stream.
                    var remaining = session.currentOffset
                    val skipBuffer = ByteArray(8192)
                    while (remaining > 0) {
                        val skipped = fileIn.skip(remaining)
                        if (skipped > 0) {
                            remaining -= skipped
                        } else {
                            val toRead = if (remaining > skipBuffer.size) skipBuffer.size else remaining.toInt()
                            val read = fileIn.read(skipBuffer, 0, toRead)
                            if (read == -1) break
                            remaining -= read
                        }
                    }
                }
                if (aeadKeySpec != null) {
                    // AEAD: emit the file as GCM records sized from the manifest; the reader bounds by
                    // the same size. AEAD resume restarts the file, so currentOffset is 0 here and the
                    // chunk index starts at 0 (kept general via currentOffset/CHUNK_SIZE).
                    val plainLen = session.manifest.getOrNull(i)?.second ?: 0L
                    val buffer = ByteArray(FileSeal.CHUNK_SIZE)
                    var remaining = plainLen - session.currentOffset
                    var chunkIndex = session.currentOffset / FileSeal.CHUNK_SIZE
                    while (remaining > 0) {
                        if (session.isCancelled || session.isPaused) throw Exception("Transfer cancelled")
                        val want = if (remaining > FileSeal.CHUNK_SIZE) FileSeal.CHUNK_SIZE else remaining.toInt()
                        var filled = 0
                        while (filled < want) {
                            val r = fileIn.read(buffer, filled, want - filled)
                            if (r == -1) throw java.io.IOException("File shorter than its manifest size")
                            filled += r
                        }
                        outStream.write(FileSeal.sealChunk(aeadKeySpec, buffer, filled, chunkIndex))
                        session.currentOffset += filled
                        remaining -= filled
                        chunkIndex++
                    }
                } else {
                    val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                    while (true) {
                        if (session.isCancelled || session.isPaused) throw Exception("Transfer cancelled")
                        val read = fileIn.read(buffer)
                        if (read == -1) break
                        outStream.write(buffer, 0, read)
                        session.currentOffset += read
                    }
                }
            }
            outStream.flush()
            session.currentFileIndex = i + 1
            session.currentOffset = 0
        }
    }

    /**
     * Ceiling the sender waits for the receiver's TRANSFER_COMPLETE after the last byte is
     * written. Normally the ack lands in well under a second (only the in-flight socket buffer
     * is left to drain); this only bites when the link dies right at the tail, where we'd rather
     * pause-and-keep-the-session than block forever.
     */
    private const val COMPLETION_ACK_TIMEOUT_MS = 30000

    /**
     * Auto-reconnect budget for a dropped incoming transfer. Each no-progress retry backs off
     * linearly ([AUTO_RESUME_BASE_DELAY_MS] × attempt, i.e. 1.5s/3s/4.5s/6s); after
     * [MAX_AUTO_RESUME_ATTEMPTS] consecutive failures with no advance the session is left paused
     * for a manual resume. Any byte of progress resets the counter (see [TransferSession]).
     */
    private const val MAX_AUTO_RESUME_ATTEMPTS = 4
    private const val AUTO_RESUME_BASE_DELAY_MS = 1500L

    /**
     * Adaptive discovery cadence. Broadcasting starts at [DISCOVERY_INTERVAL_MIN_MS] and doubles
     * each stable cycle up to [DISCOVERY_INTERVAL_MAX_MS] (3s → 6s → 12s), collapsing back to MIN
     * whenever the daemon (re)starts, a new peer appears, or the discovered-device set changes.
     * [DISCOVERY_WAKE_WINDOW_MS] is how long a wake event pins the fast cadence. The staleness
     * window [DISCOVERY_NODE_TIMEOUT_MS] is widened from the old hard-coded 8s (which assumed a 3s
     * broadcast) so a peer heard only on the slow cadence isn't flickered out of the list between
     * broadcasts — it gives ~8s of margin over the MAX interval. Wire format is unchanged, so this
     * stays fully interoperable with the Windows client (which keeps its own cadence).
     */
    /**
     * Discovery capability token advertised as the optional 5th field of the discovery/reply
     * message. Presence means the peer can decrypt authenticated (AES-GCM) clipboard payloads
     * ([ClipboardSeal]); senders fall back to legacy AES/CBC for any peer that omits it.
     */
    const val CLIPBOARD_GCM_CAP = "GCM1"

    /**
     * File-stream AEAD capability token, advertised comma-joined with [CLIPBOARD_GCM_CAP] in the
     * optional 5th discovery field (`...|Android|GCM1,FGCM1`). Presence means the peer can seal/open
     * authenticated bulk file streams via [FileSeal]; a peer that omits it keeps the plaintext stream.
     * Parsed into [DiscoveredDevice.supportsAuthFile].
     */
    const val FILE_GCM_CAP = "FGCM1"

    /** Capability list advertised in discovery — comma-joined, order-stable, parsed by `contains`. */
    private val DISCOVERY_CAPS = "$CLIPBOARD_GCM_CAP,$FILE_GCM_CAP"

    private const val DISCOVERY_INTERVAL_MIN_MS = 3000L
    private const val DISCOVERY_INTERVAL_MAX_MS = 12000L
    private const val DISCOVERY_WAKE_WINDOW_MS = 20000L
    private const val DISCOVERY_NODE_TIMEOUT_MS = 20000L

    /**
     * Pin discovery to the fast cadence for the next [DISCOVERY_WAKE_WINDOW_MS]. Cheap and
     * idempotent (just advances a volatile deadline), so it's safe to call from the receive loop
     * on every newly-seen peer as well as on daemon start.
     */
    private fun wakeDiscovery() {
        discoveryWakeUntilMs = System.currentTimeMillis() + DISCOVERY_WAKE_WINDOW_MS
    }

    /**
     * A dropped incoming transfer auto-reconnects by re-invoking [resumeIncomingTransfer] (which
     * re-dials the sender, recomputes the on-disk offset, and re-streams) — bounded and backed off
     * so transient blips self-heal without a tap while a dead route still degrades to manual resume.
     */
    private fun scheduleAutoResume(context: Context, session: TransferSession): Boolean {
        if (session.isCancelled) return false
        // Off-grid (Wi-Fi Direct / SoftAP) sessions can't be auto-resumed here: resumeIncomingTransfer
        // re-dials a plain default-network Socket to session.targetIp, which has no route to the
        // off-grid sender once its live TransportRoute is gone. Spending the bounded retry budget on
        // dials that can never connect only delays the paused state, so leave p2p sessions for a
        // route-aware manual resume from the off-grid scan screen instead.
        if (session.transport == "p2p") {
            TransportDiag.log("RX: auto-resume skipped for off-grid (p2p) session ${session.sessionId} — needs route-aware manual resume")
            return false
        }
        // Progress since the last attempt → the link is alive and moving; forgive prior failures.
        if (session.currentOffset > session.lastResumeOffset) {
            session.autoResumeAttempts = 0
        }
        session.lastResumeOffset = session.currentOffset
        if (session.autoResumeAttempts >= MAX_AUTO_RESUME_ATTEMPTS) {
            TransportDiag.log("RX: auto-resume gave up after $MAX_AUTO_RESUME_ATTEMPTS no-progress attempts — left paused for manual resume")
            return false
        }
        session.autoResumeAttempts++
        val attempt = session.autoResumeAttempts
        val delayMs = AUTO_RESUME_BASE_DELAY_MS * attempt
        TransportDiag.log("RX: auto-resume attempt $attempt/$MAX_AUTO_RESUME_ATTEMPTS in ${delayMs}ms (offset=${session.currentOffset})")
        daemonScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (session.isCancelled) return@launch
            resumeIncomingTransfer(context, session.sessionId)
        }
        return true
    }

    /** True once the receiver confirms delivery of [sessionId]; false on timeout/EOF/mismatch. */
    private fun awaitCompletionAck(connection: TransportConnection, sessionId: String): Boolean = try {
        connection.setReadTimeout(COMPLETION_ACK_TIMEOUT_MS)
        readUntilNewline(connection.input) == "TRANSFER_COMPLETE|$sessionId"
    } catch (_: Exception) { false }

    private fun awaitCompletionAck(socket: Socket, sessionId: String): Boolean = try {
        socket.soTimeout = COMPLETION_ACK_TIMEOUT_MS
        readUntilNewline(socket.getInputStream()) == "TRANSFER_COMPLETE|$sessionId"
    } catch (_: Exception) { false }

    // 1 MiB cap (~20k-file manifests) so large multi-file/folder OFFER manifests aren't truncated.
    private fun readUntilNewline(inputStream: InputStream, maxBytes: Int = 1024 * 1024): String {
        val baos = java.io.ByteArrayOutputStream()
        var b = inputStream.read()
        while (b != -1 && b != '\n'.code) {
            baos.write(b)
            // A header/manifest line is small; a peer streaming bytes without a newline
            // would otherwise grow this buffer until OutOfMemoryError. Cap and abort.
            if (baos.size() > maxBytes) throw java.io.IOException("Header line exceeded $maxBytes bytes")
            b = inputStream.read()
        }
        return baos.toString("UTF-8")
    }
}
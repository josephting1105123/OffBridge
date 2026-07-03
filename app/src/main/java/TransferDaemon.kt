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
import com.EntropicSoftwareLab.ClipboardShare.transport.SessionCrypto
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

object TransferDaemon {
    @Volatile var isRunning = false
    @Volatile var customDeviceName: String = run {
        val manufacturer = Build.MANUFACTURER?.trim() ?: ""
        val model = Build.MODEL?.trim() ?: ""
        if (model.startsWith(manufacturer, ignoreCase = true)) model
        else if (manufacturer.isNotEmpty()) "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        else model.ifEmpty { "Android Device" }
    }
        // The name is embedded between '|' delimiters in FILEBRIDGE_DISCOVERY/REPLY, OFFER and
        // P2P_HELLO frames; a '|' or newline in a user-set name would break the peer's positional
        // parse (Windows conforms to the same rule). Strip them once at the setter.
        set(value) {
            field = value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        }
    val myDeviceId = UUID.randomUUID().toString()

    private val _discoveredNodes = ConcurrentHashMap<String, DiscoveredDevice>()
    val discoveredNodes: Map<String, DiscoveredDevice> get() = _discoveredNodes

    private val _activeSessions = ConcurrentHashMap<String, TransferSession>()
    val activeSessions: Map<String, TransferSession> get() = _activeSessions

    fun clearDiscoveredNodes() {
        _discoveredNodes.clear()
    }

    fun putDiscoveredNode(ip: String, node: DiscoveredDevice) {
        _discoveredNodes[ip] = node
    }

    fun removeDiscoveredNodesIf(predicate: (Map.Entry<String, DiscoveredDevice>) -> Boolean) {
        _discoveredNodes.entries.removeIf(predicate)
    }

    fun removeActiveSession(sessionId: String): TransferSession? {
        return _activeSessions.remove(sessionId)
    }

    fun putActiveSession(sessionId: String, session: TransferSession) {
        _activeSessions[sessionId] = session
    }

    /** Non-null only while an off-grid sender screen is advertising. */
    @Volatile
    private var _p2pContext: P2pAdvertisingContext? = null

    val p2pContext: P2pAdvertisingContext? get() = _p2pContext

    fun setP2pContext(context: P2pAdvertisingContext?) {
        _p2pContext = context
    }

    // A failed child (uncaught exception) must not tear down its siblings â€” one dead discovery/
    // transfer coroutine should never silently kill the whole daemon. SupervisorJob isolates
    // failures; the handler logs the escape so it isn't swallowed without trace.
    private val daemonExceptionHandler = CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) {
            e.printStackTrace()
            try { TransportDiag.log("Daemon coroutine crashed: ${e.javaClass.simpleName} ${e.message ?: ""}") } catch (_: Exception) {}
        }
    }

    // var (not val) so [stop] can cancel the scope and a later [start] can rebuild it â€” a cancelled
    // CoroutineScope cannot relaunch, so the daemon needs a fresh Job/scope on restart.
    private var daemonJob = SupervisorJob()
    internal var daemonScope = CoroutineScope(Dispatchers.IO + daemonJob + daemonExceptionHandler)

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null


    fun start(context: Context) {
        if (isRunning) return
        isRunning = true
        // A prior stop() cancelled daemonScope; a cancelled scope can't relaunch, so rebuild the
        // Job + scope before starting fresh after a shutdown.
        if (!daemonScope.isActive) {
            daemonJob = SupervisorJob()
            daemonScope = CoroutineScope(Dispatchers.IO + daemonJob + daemonExceptionHandler)
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
            // Bounded acquire so a missed release() (e.g. process killed before stop()) can't pin the
            // CPU/radio awake for the whole process life â€” the lock auto-releases after the timeout.
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)

            // WifiLock has no timed acquire() overload; the CPU wake lock (above) carries the bounded
            // safety timeout, and the Wi-Fi lock is released alongside it in releaseLocks().
            wifiLock = wifiManager.createWifiLock(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "JosephClipboardClient:TransferDaemonWifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        LanDiscovery.start(context, daemonScope)

        daemonScope.launch {
            var serverSocket: ServerSocket? = null
            try {
                val server = ServerSocket()
                serverSocket = server
                server.reuseAddress = true
                // Advertise a large TCP window in the handshake so accepted sockets inherit it.
                runCatching { server.receiveBufferSize = SOCKET_BUFFER_BYTES }
                server.bind(InetSocketAddress("0.0.0.0", Ports.FILE_TRANSFER))

                while (isActive) {
                    try {
                        val clientSocket = server.accept()
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
                        if (!isActive || server.isClosed) break
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }

        daemonScope.launch {
            val pendingReconnects = mutableMapOf<String, Long>()
            val alreadyNotified = mutableSetOf<String>()

            while (isActive) {
                delay(1000)
              try {
                val now = System.currentTimeMillis()

                var newlyResumableCount = 0
                var totalResumableCount = 0

                for ((sessionId, session) in _activeSessions) {
                    if (session.isPaused && session.isIncoming) {
                        // SECURITY: the sender is resolved purely by display name, which any host on
                        // the LAN can spoof. Only auto-dial a paired peer (we hold its key); and if the
                        // session was authenticated (AES-GCM) once, refuse an IP that isn't FGCM-pinned
                        // so a same-name impostor can't force an unauthenticated downgrade. A blocked
                        // match is treated as "sender not present" â€” the session stays paused.
                        val senderIp = _discoveredNodes.entries.find { it.value.name == session.senderName }?.key
                            ?.takeIf { ip ->
                                SecurityVault.getKeyForNode(context, ip) != null &&
                                    (!session.fileGcmNegotiated || SecurityVault.isFileGcmPinned(context, ip))
                            }
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
              } catch (e: Exception) {
                // One bad iteration (e.g. a notification build failure) must not kill the whole
                // resume watcher; log and keep polling.
                e.printStackTrace()
              }
            }
        }
    }

    /**
     * Full shutdown â€” the counterpart [start] never had. Cancels the discovery / TCP-server /
     * resume coroutines and releases the three radio/CPU locks [start] acquires. Without this the
     * PARTIAL_WAKE_LOCK + Wi-Fi lock + multicast lock stayed held for the entire process lifetime
     * (pinning the CPU awake and the radio in high-power mode) with no way for the user to release
     * them short of force-stop. Idempotent; safe to call when not running. Call only on a genuine
     * app shutdown (Power Off) â€” the daemon is a shared singleton many screens [start]; a later
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


    private fun getMyIpAddress(): String = Net.getMyIpAddress()

    private fun isMyOwnIp(ip: String?): Boolean = Net.isMyOwnIp(ip)

    private fun getAllBroadcastAddresses(context: Context): List<InetAddress> = Net.getAllBroadcastAddresses(context)

    private fun handleIncomingConnection(context: Context, socket: Socket) {
        try {
            val inputStream = socket.getInputStream()
            val batchHeader = TransferUtils.readUntilNewline(inputStream)

            // Off-grid handshake authenticates itself with the QR token + ECDH HMAC (inside
            // handleP2pHello, which is stronger than IP pairing) and arrives from an ephemeral
            // Wi-Fi Direct IP that is never a paired node. It MUST be dispatched BEFORE the LAN
            // trust gate below â€” this is the "separate P2P/QR-NFC path" the gate was always meant
            // to leave unaffected. Gating it ahead of this read closed the socket before the HELLO
            // was even parsed, which silently broke every off-grid Wi-Fi Direct transfer
            // (TCP connected, then HANDSHAKE_FAILED with no P2P_WELCOME).
            if (batchHeader.startsWith("P2P_HELLO|")) {
                P2pHelloHandler.handleP2pHello(context, SocketConnection(socket), batchHeader)
                return
            }

            // Trust gate: only paired devices may push files / drive session control over the LAN
            // â€” same check ClipboardService uses for clipboard pushes. Closes the "any host on the
            // network can push files" hole. (Off-grid P2P_HELLO is handled above, so it is exempt.)
            val senderIp = (socket.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress
            if (senderIp == null || SecurityVault.getKeyForNode(context, senderIp) == null) {
                // If the trace shows this during an off-grid send, the receiver's HELLO arrived
                // garbled/partial (didn't match "P2P_HELLO|") and got caught by the LAN trust gate.
                TransportDiag.log("TX: incoming '${batchHeader.take(16)}' from $senderIp blocked â€” not a paired LAN node")
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
                    // Receiver flags after the offset, order-independent: "ack" â†’ wait for the delivery
                    // confirmation before declaring complete; "fgcm" â†’ the bulk stream is FileSeal-sealed.
                    val flags = parts.drop(4)
                    val ackExpected = flags.contains("ack")
                    // Both "fgcm" and "fgcm2" request an authenticated stream; "fgcm2" additionally
                    // selects the v2 FileSeal (per-session key + session/file-bound AAD).
                    val resumeFileKey = try {
                        TransferKeys.senderFileKey(context, senderIp, flags.contains("fgcm") || flags.contains("fgcm2"))
                    } catch (e: DowngradeBlockedException) {
                        TransportDiag.log("TX: $e â€” refusing plaintext resume")
                        socket.close(); return
                    }
                    val resumeUseV2 = flags.contains("fgcm2")
                    val resumeAead = flags.contains("fgcm") || flags.contains("fgcm2")

                    val session = _activeSessions[sessionId]
                    if (session != null && !session.isIncoming) {
                        // Bounds-check the network-supplied resume position before trusting it, so a
                        // bad (fileIndex, offset) can't make the sender skip past or re-send file data.
                        // AEAD resume restarts the file (records aren't byte-seekable): the receiver
                        // always requests offset 0 with fgcm/fgcm2, so a non-zero offset under AEAD is
                        // malformed — reject it up front instead of computing a wrong chunk index that
                        // only fails later at the GCM tag.
                        if (fileIndex < 0 || fileIndex >= session.fileUris.size || offset < 0L ||
                            (resumeAead && offset != 0L)) {
                            socket.close(); return
                        }
                        session.isPaused = false
                        session.currentFileIndex = fileIndex
                        session.currentOffset = offset
                        // Reflect the resume on the sender immediately (mirrors ACCEPT_OFFER) so the
                        // history detail flips Pausedâ†’Transferring now, not only on completion.
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

                                // Expose the live socket so a CANCEL_TRANSFER can close it and unblock
                                // a write stuck on a dead link (rather than waiting out the TCP timeout).
                                session.activeSendSocket = socket
                                UploadEngine.streamSessionFiles(context, session, outStream, resumeFileKey, myGen, resumeUseV2)

                                if (ackExpected && !UploadEngine.awaitCompletionAck(socket, sessionId)) {
                                    // Resent bytes never confirmed â€” pause and keep the session so
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
                                    _activeSessions.remove(sessionId)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                if (session.isCancelled) {
                                    TransferHistoryManager.updateStatus(context, sessionId, "cancelled", session.currentFileIndex, session.currentOffset)
                                } else if (session.sendGeneration.get() == myGen) {
                                    // A newer resume may have superseded this stale send â€” only the
                                    // current generation may mark the session paused.
                                    session.isPaused = true
                                    session.lastUpdated = System.currentTimeMillis()
                                    TransferHistoryManager.updateStatus(context, sessionId, "paused", session.currentFileIndex, session.currentOffset)
                                }
                            } finally {
                                if (session.activeSendSocket === socket) session.activeSendSocket = null
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
                    val session = _activeSessions[sessionId]
                    if (session != null && !session.isIncoming) {
                        session.isPaused = false
                        // Receiver appended "fgcm" â‡’ seal the bulk stream with FileSeal under the shared
                        // pairing key (the trust gate above guarantees we hold it). Absent â‡’ plaintext,
                        // unless we'd previously sealed for this peer (pin) â†’ refuse the downgrade.
                        // Both "fgcm" and "fgcm2" request an authenticated stream; "fgcm2" additionally
                        // selects the v2 FileSeal (per-session key + session/file-bound AAD).
                        val acceptFlags = parts.drop(2)
                        val fileKey = try {
                            TransferKeys.senderFileKey(context, senderIp, acceptFlags.contains("fgcm") || acceptFlags.contains("fgcm2"))
                        } catch (e: DowngradeBlockedException) {
                            TransportDiag.log("TX: $e â€” refusing plaintext stream")
                            socket.close(); return
                        }
                        val useV2 = acceptFlags.contains("fgcm2")
                        val myGen = session.sendGeneration.incrementAndGet()
                        TransferHistoryManager.updateStatus(context, sessionId, "transferring")

                        daemonScope.launch(Dispatchers.IO) {
                            try {
                                val outStream = socket.getOutputStream()
                                // Expose the live socket so a CANCEL_TRANSFER can close it and unblock a
                                // write stuck on a dead link (rather than waiting out the TCP timeout).
                                session.activeSendSocket = socket
                                UploadEngine.streamSessionFiles(context, session, outStream, fileKey, myGen, useV2)
                                _activeSessions.remove(sessionId)
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
                                if (session.activeSendSocket === socket) session.activeSendSocket = null
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
                    val session = _activeSessions.remove(sessionId)
                    if (session != null && !session.isIncoming) {
                        session.isCancelled = true
                        // A decline can also arrive mid-stream (older receivers send it for a live
                        // cancel) â€” close the streaming socket like CANCEL_TRANSFER does, so a
                        // blocked write aborts now instead of after the ~20 s TCP write timeout.
                        session.activeSendSocket?.let { s -> runCatching { s.close() } }
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
                    val session = _activeSessions.remove(sessionId)
                    if (session != null) {
                        session.isCancelled = true
                        // Close the live streaming socket (if any) so a send blocked on a dead-link
                        // write is interrupted now instead of hanging until the ~20 s TCP write timeout.
                        session.activeSendSocket?.let { s -> runCatching { s.close() } }
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

            val manifestHeader = TransferUtils.readUntilNewline(inputStream)
            // Shared parser (skips empty-name ":size" entries via lastColonIndex > 0) so a
            // degenerate/hostile manifest yields the SAME file list here, on the off-grid path,
            // and on Windows. An earlier local copy accepted ":size" that Windows dropped, which
            // desynced the positional byte stream across platforms.
            val fileList = TransferUtils.parseManifestEntries(manifestHeader).toMutableList()

            val newSession = TransferSession(
                sessionId = sessionId,
                senderName = senderName,
                totalFiles = totalFiles,
                manifest = fileList,
                targetIp = socket.inetAddress.hostAddress ?: "",
                isIncoming = true
            )
            _activeSessions[sessionId] = newSession

            if (isOffer) {
                val outStream = socket.getOutputStream()

                // AUTH2 admission proof: the plain trust gate above only checks that senderIp has a
                // stored pairing key, so a host spoofing a paired peer's IP (without the key) could still
                // raise this popup. If the sender advertises AUTH2, require it to PROVE possession of the
                // shared pairing key via an HMAC challenge-response BEFORE we ack the offer or show the
                // popup. A sender that doesn't advertise AUTH2 (old build) skips this â€” byte-identical to
                // before. senderIp is the same source address the trust gate validated above.
                if (_discoveredNodes[senderIp]?.supportsAuth2 == true) {
                    val pairingKey = SecurityVault.getKeyForNode(context, senderIp)
                    if (pairingKey == null) {
                        // Trust gate said we hold a key for this IP; if it vanished, fail closed.
                        _activeSessions.remove(sessionId)
                        socket.close()
                        return
                    }
                    val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    outStream.write("OFFER_CHALLENGE|${android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)}\n".toByteArray(Charsets.UTF_8))
                    outStream.flush()
                    val proofLine = TransferUtils.readUntilNewline(inputStream)
                    val proof = if (proofLine.startsWith("OFFER_PROOF|")) {
                        try { android.util.Base64.decode(proofLine.removePrefix("OFFER_PROOF|"), android.util.Base64.NO_WRAP) } catch (_: Exception) { null }
                    } else null
                    val expected = SessionCrypto.hmacSha256(pairingKey, nonce)
                    if (proof == null || !java.security.MessageDigest.isEqual(proof, expected)) {
                        // Missing/malformed/wrong proof â‡’ the sender can't prove it holds the key. Drop
                        // the offer silently: no OFFER_RECEIVED, no popup. (An IP-spoofer lands here.)
                        TransportDiag.log("TX: OFFER from $senderIp dropped â€” AUTH2 proof failed")
                        _activeSessions.remove(sessionId)
                        socket.close()
                        return
                    }
                }

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
            
            DownloadEngine.executeDownloadStream(context, socket, newSession)

        } catch (e: Exception) {
            // close() can itself throw (already-reset socket); swallow so the handler exits cleanly.
            runCatching { socket.close() }
        }
    }

    /**
     * Â§2.3 protocol extension. Off-grid handshake, sender side:
     *
     *   Receiver â†’ P2P_HELLO|token|receiverPubKey|deviceName|HMAC(K, token)
     *   Sender   â†’ P2P_WELCOME|sessionId|HMAC(K, token || "ack")  (or P2P_BUSY)
     *
     * then OFFER + MANIFEST on the same socket, receiver replies
     * ACCEPT_OFFER|sessionId on the same socket (no reconnect â€” there is no
     * discovery to re-resolve the peer), then raw 8 KiB chunk streaming.
     *
     * The paired-device key check is bypassed: admission is by token + HMAC.
     * This branch is unreachable without a registered advertising context.
     */
    /**
     * Generic entry point for an out-of-band [TransportConnection] (one accepted outside the main
     * 0.0.0.0 TCP listener): it is dispatched into the same P2P_HELLO protocol the TCP listener
     * speaks. Reads the first line on IO and routes; non-HELLO traffic is dropped. (Carrier-agnostic
     * â€” the retired BT data tier used this; a future network-bound SoftAP server would reuse it.)
     */
    fun dispatchTransportConnection(context: Context, connection: TransportConnection) {
        daemonScope.launch(Dispatchers.IO) {
            try {
                val header = TransferUtils.readUntilNewline(connection.input)
                if (header.startsWith("P2P_HELLO|")) {
                    P2pHelloHandler.handleP2pHello(context, connection, header)
                } else {
                    connection.close()
                }
            } catch (_: Exception) {
                try { connection.close() } catch (_: Exception) {}
            }
        }
    }




    /**
     * Bounded acquire ceiling for the CPU wake lock. A missed release() (e.g. the process is killed
     * before [stop] runs) would otherwise pin the CPU/radio awake for the whole process life; the
     * timeout guarantees the lock auto-releases. Long enough to cover a large transfer; a normal
     * shutdown still releases early via [releaseLocks].
     */
    private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L

    /**
     * Auto-reconnect budget for a dropped incoming transfer. Each no-progress retry backs off
     * linearly ([AUTO_RESUME_BASE_DELAY_MS] Ã— attempt, i.e. 1.5s/3s/4.5s/6s); after
     * [MAX_AUTO_RESUME_ATTEMPTS] consecutive failures with no advance the session is left paused
     * for a manual resume. Any byte of progress resets the counter (see [TransferSession]).
     */
    private const val MAX_AUTO_RESUME_ATTEMPTS = 4
    private const val AUTO_RESUME_BASE_DELAY_MS = 1500L

    /**
     * Adaptive discovery cadence. Broadcasting starts at [DISCOVERY_INTERVAL_MIN_MS] and doubles
     * each stable cycle up to [DISCOVERY_INTERVAL_MAX_MS] (3s â†’ 6s â†’ 12s), collapsing back to MIN
     * whenever the daemon (re)starts, a new peer appears, or the discovered-device set changes.
     * [DISCOVERY_WAKE_WINDOW_MS] is how long a wake event pins the fast cadence. The staleness
     * window [DISCOVERY_NODE_TIMEOUT_MS] is widened from the old hard-coded 8s (which assumed a 3s
     * broadcast) so a peer heard only on the slow cadence isn't flickered out of the list between
     * broadcasts â€” it gives ~8s of margin over the MAX interval. Wire format is unchanged, so this
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

    /**
     * FileSeal v2 AEAD capability token, advertised comma-joined after [FILE_GCM_CAP]
     * (`...|Android|GCM1,FGCM1,FGCM2`). Orthogonal to [FILE_GCM_CAP]: both are authenticated
     * streams; v2 (per-session key + session/file-bound AAD) is chosen only when BOTH peers
     * advertise FGCM2, otherwise the transfer falls back to the v1 FileSeal path. A peer that omits
     * it stays on v1. Parsed into [DiscoveredDevice.supportsAuthFileV2].
     */
    const val FILE_GCM_CAP_V2 = "FGCM2"

    /**
     * OFFER admission-proof capability token, advertised comma-joined after [FILE_GCM_CAP_V2]
     * (`...|Android|GCM1,FGCM1,FGCM2,AUTH2`). Presence means the peer will answer an OFFER_CHALLENGE
     * with an HMAC proof of the shared pairing key before the receiver shows the incoming-file popup â€”
     * closing the "spoof a paired peer's IP to raise a popup without holding the key" hole. A receiver
     * only issues the challenge to a sender that advertises this; a sender always tolerates both the
     * challenge and the direct OFFER_RECEIVED, so oldâ†”new stays byte-identical. Parsed into
     * [DiscoveredDevice.supportsAuth2].
     */
    const val OFFER_AUTH_CAP = "AUTH2"

    /** Capability list advertised in discovery â€” comma-joined, order-stable, parsed by `contains`. */
    internal val DISCOVERY_CAPS = "$CLIPBOARD_GCM_CAP,$FILE_GCM_CAP,$FILE_GCM_CAP_V2,$OFFER_AUTH_CAP"
    internal const val DISCOVERY_INTERVAL_MIN_MS = 3000L
    internal const val DISCOVERY_INTERVAL_MAX_MS = 12000L
    internal const val DISCOVERY_WAKE_WINDOW_MS = 20000L
    internal const val DISCOVERY_NODE_TIMEOUT_MS = 20000L

    /**
     * Pin discovery to the fast cadence for the next [DISCOVERY_WAKE_WINDOW_MS]. Cheap and
     * idempotent (just advances a volatile deadline), so it's safe to call from the receive loop
     * on every newly-seen peer as well as on daemon start.
     */
    private fun wakeDiscovery() {
        LanDiscovery.wakeDiscovery()
    }

    /**
     * A dropped incoming transfer auto-reconnects by re-invoking [resumeIncomingTransfer] (which
     * re-dials the sender, recomputes the on-disk offset, and re-streams) â€” bounded and backed off
     * so transient blips self-heal without a tap while a dead route still degrades to manual resume.
     */
    internal fun scheduleAutoResume(context: Context, session: TransferSession): Boolean {
        if (session.isCancelled) return false
        // Off-grid (Wi-Fi Direct / SoftAP) sessions can't be auto-resumed here: resumeIncomingTransfer
        // re-dials a plain default-network Socket to session.targetIp, which has no route to the
        // off-grid sender once its live TransportRoute is gone. Spending the bounded retry budget on
        // dials that can never connect only delays the paused state, so leave p2p sessions for a
        // route-aware manual resume from the off-grid scan screen instead.
        if (session.transport == "p2p") {
            TransportDiag.log("RX: auto-resume skipped for off-grid (p2p) session ${session.sessionId} â€” needs route-aware manual resume")
            return false
        }
        // Progress since the last attempt â†’ the link is alive and moving; forgive prior failures.
        if (session.currentOffset > session.lastResumeOffset) {
            session.autoResumeAttempts = 0
        }
        session.lastResumeOffset = session.currentOffset
        if (session.autoResumeAttempts >= MAX_AUTO_RESUME_ATTEMPTS) {
            TransportDiag.log("RX: auto-resume gave up after $MAX_AUTO_RESUME_ATTEMPTS no-progress attempts â€” left paused for manual resume")
            return false
        }
        session.autoResumeAttempts++
        val attempt = session.autoResumeAttempts
        val delayMs = AUTO_RESUME_BASE_DELAY_MS * attempt
        TransportDiag.log("RX: auto-resume attempt $attempt/$MAX_AUTO_RESUME_ATTEMPTS in ${delayMs}ms (offset=${session.currentOffset})")
        daemonScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (session.isCancelled) return@launch
            DownloadEngine.resumeIncomingTransfer(context, session.sessionId)
        }
        return true
    }

    }

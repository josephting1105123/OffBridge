package com.joseph.clipboard.windows

import com.joseph.clipboard.windows.storage.SecurityVault
import com.joseph.clipboard.windows.storage.TransferHistoryManager
import com.joseph.clipboard.windows.network.ClipboardDaemon
import com.joseph.clipboard.windows.network.DiscoveredDevice
import com.joseph.clipboard.windows.network.FileTransferDaemon
import com.joseph.clipboard.windows.network.HotspotController
import com.joseph.clipboard.windows.network.Net
import com.joseph.clipboard.windows.network.OffGridQrSender
import com.joseph.clipboard.windows.network.PairingDaemon
import com.joseph.clipboard.windows.network.Ports
import com.joseph.clipboard.windows.network.TransferDaemon
import com.joseph.clipboard.windows.os.ClipboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.Base64
import java.net.InetSocketAddress
import com.joseph.clipboard.windows.storage.EcdhSession
import java.util.UUID
import java.io.File
import java.net.Socket
import java.io.FileOutputStream
import java.io.FileInputStream

data class OutgoingFileSession(
    val sessionId: String,
    val targetIp: String,
    val files: List<File>
)

data class IncomingFileSession(
    val sessionId: String,
    val senderIp: String,
    val senderName: String,
    val manifest: List<Pair<String, Long>>
)

// Mirrors Android TransferRecord (schema v2, §3.2): per-file facts are
// persisted as a normalized `files` array ({i, n, s}); fileNames/fileSizes
// stay as the in-memory materialized view all UI code binds to.
// totalFiles/totalBytes are deliberate, documented denormalized aggregates
// for the list view. Serialization lives in storage/TransferHistoryManager.
data class TransferRecord(
    val sessionId: String,
    val deviceName: String,
    val direction: String, // "incoming" or "outgoing"
    val fileNames: List<String>,
    val totalBytes: Long,
    val timestamp: Long,
    var status: String, // "pending", "transferring", "completed", "failed"
    var filesCompleted: Int,
    val totalFiles: Int,
    val fileSizes: List<Long>,
    var currentFileOffset: Long,
    val transport: String = "lan" // "lan" or "p2p"
)

class ClipboardAppController {

    var onRequestForeground: (() -> Unit)? = null

    data class IncomingPairingData(
        val ip: String,
        val name: String,
        val pin: String,
        val onAccept: () -> Unit,
        val onDecline: () -> Unit
    )

    data class OutgoingPairingState(
        val ip: String,
        val deviceName: String,
        val status: String,
        val visualPin: String? = null
    )

    private val scope = CoroutineScope(Dispatchers.IO)

    // Daemons
    private val transferDaemon = TransferDaemon(
        deviceNameProvider = { SecurityVault.getDeviceName() }
    )
    
    // File Transfer State
    // Concurrent: written/removed from per-connection handler coroutines (QR + normal sends).
    private val activeOutgoingFileSessions = java.util.concurrent.ConcurrentHashMap<String, OutgoingFileSession>()
    private val activeIncomingFileSessions = mutableMapOf<String, IncomingFileSession>()
    // Single in-flight off-grid "send via QR" advertising window (Windows = sender).
    // Atomically CLAIMED (getAndSet) by the first scanner that dials in, so two concurrent
    // dial-ins can't both win the same one-shot session. null means "no QR being shown".
    private val pendingQrSession = java.util.concurrent.atomic.AtomicReference<OffGridQrSender.HostSession?>(null)
    // Monotonic "QR intent" counter. Bumped by start AND cancel so an in-flight startQrHostSession
    // (the hotspot spin-up can take seconds) can detect it was superseded and tear its own AP down,
    // closing the dispose-during-startup race that would otherwise leak the hotspot.
    private val qrEpoch = java.util.concurrent.atomic.AtomicLong(0)
    private val activeFileTransferSockets = java.util.concurrent.ConcurrentHashMap<String, java.net.Socket>()
    
    private val _transferHistory = MutableStateFlow<List<TransferRecord>>(emptyList())
    val transferHistory: StateFlow<List<TransferRecord>> = _transferHistory
    
    // For UI popups regarding incoming files
    private val _incomingFileOffer = MutableStateFlow<IncomingFileSession?>(null)
    val incomingFileOffer: StateFlow<IncomingFileSession?> = _incomingFileOffer
    
    // Navigation event: emits session ID when accept is clicked, so UI can show transfer history
    private val _navigateToTransferSession = MutableStateFlow<String?>(null)
    val navigateToTransferSession: StateFlow<String?> = _navigateToTransferSession.asStateFlow()
    
    fun clearNavigateToTransferSession() {
        _navigateToTransferSession.value = null
    }
    
    private val pairingDaemon = PairingDaemon(
        onIncomingPairingRequest = { ip, name, pin, accept, decline ->
            if (!_isDiscoverable.value) {
                decline()
                return@PairingDaemon
            }

            _incomingPairingRequest.value = IncomingPairingData(ip, name, pin, {
                accept()
                _incomingPairingRequest.value = null
                scope.launch {
                    delay(500) // Wait for PairingDaemon to finish saving the key
                    refreshTrustedNodes()
                }
            }, {
                decline()
                _incomingPairingRequest.value = null
            })
            onRequestForeground?.invoke()
        },
        onUnpaired = { senderIp ->
            println("Remote device $senderIp unpaired us — refreshing trusted nodes")
            refreshTrustedNodes()
        }
    )
    private val clipboardDaemon = ClipboardDaemon(
        acceptIncomingProvider = { _acceptIncomingClipboard.value },
        onClipboardReceived = { text -> 
            addToHistory(text)
        }
    )
    private val fileTransferDaemon = com.joseph.clipboard.windows.network.FileTransferDaemon(
        onOfferReceived = { ip, sessionId, deviceName, manifest ->
            // Filenames arrive off the wire and are written into the save folder, so a hostile
            // sender could embed path separators or ".." to escape it (path traversal). Reduce
            // every entry to a safe leaf name before the session is stored or any file is opened;
            // the receive and resume paths both derive their .part paths from this stored manifest.
            val safeManifest = manifest.map { (name, size) -> sanitizeIncomingFileName(name) to size }
            val session = IncomingFileSession(sessionId, ip, deviceName, safeManifest)
            activeIncomingFileSessions[sessionId] = session
            _incomingFileOffer.value = session
            onRequestForeground?.invoke()
        },
        onAcceptReceived = { sessionId, fgcm, socket ->
            val session = activeOutgoingFileSessions[sessionId]
            if (session != null) {
                // Android accepted our offer. Stream files out! (LAN accept never carries the
                // delivery-ack suffix, so ackExpected = false here.) "fgcm" ⇒ seal the bulk stream
                // with FileSeal under the shared pairing key.
                val fileKey = if (fgcm) SecurityVault.getAesKey(session.targetIp) else null
                streamFilesToSocket(session, socket, 0, 0L, ackExpected = false, fileKey = fileKey)
            } else {
                try { socket.close() } catch(e:Exception){}
            }
        },
        onResumeReceived = { sessionId, fileIndex, offset, ackExpected, fgcm, socket ->
            val session = activeOutgoingFileSessions[sessionId]
            if (session != null) {
                val fileKey = if (fgcm) SecurityVault.getAesKey(session.targetIp) else null
                streamFilesToSocket(session, socket, fileIndex, offset, ackExpected, fileKey = fileKey)
            } else {
                try { socket.close() } catch(e:Exception){}
            }
        },
        onCancelReceived = { sessionId ->
            if (_transferHistory.value.find { it.sessionId == sessionId }?.status != "completed") {
                updateRecord(sessionId) { it.copy(status = "cancelled") }
            }
        },
        onP2pHelloReceived = { socket, header -> handleP2pHello(socket, header) }
    )

    // UI State
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _trustedNodes = MutableStateFlow<Set<String>>(emptySet())
    val trustedNodes: StateFlow<Set<String>> = _trustedNodes.asStateFlow()

    private val _incomingPairingRequest = MutableStateFlow<IncomingPairingData?>(null)
    val incomingPairingRequest: StateFlow<IncomingPairingData?> = _incomingPairingRequest.asStateFlow()

    private val _outgoingPairingState = MutableStateFlow<OutgoingPairingState?>(null)
    val outgoingPairingState: StateFlow<OutgoingPairingState?> = _outgoingPairingState.asStateFlow()

    private val _deviceName = MutableStateFlow(SecurityVault.getDeviceName())
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _isDiscoverable = MutableStateFlow(SecurityVault.isDiscoverable())
    val isDiscoverable: StateFlow<Boolean> = _isDiscoverable.asStateFlow()

    private val _acceptIncomingClipboard = MutableStateFlow(true)
    val acceptIncomingClipboard: StateFlow<Boolean> = _acceptIncomingClipboard.asStateFlow()

    private val _clipboardHistory = MutableStateFlow<List<String>>(emptyList())
    val clipboardHistory: StateFlow<List<String>> = _clipboardHistory.asStateFlow()

    fun updateAcceptIncomingClipboard(accept: Boolean) {
        _acceptIncomingClipboard.value = accept
    }

    fun addToHistory(text: String) {
        if (text.isBlank()) return
        // H-p1: atomic update — addToHistory runs from multiple IO coroutines (clipboard
        // poll listener + onClipboardReceived); a plain value=f(value) read-modify-write
        // would let concurrent updates clobber each other. List contents are unchanged.
        _clipboardHistory.update { current ->
            val currentList = current.toMutableList()
            currentList.remove(text) // Remove duplicate if exists
            currentList.add(0, text)
            if (currentList.size > 100) currentList.removeAt(currentList.size - 1)
            currentList
        }
    }

    fun deleteFromHistory(text: String) {
        // H-p1: atomic update to avoid clobbering a concurrent addToHistory.
        _clipboardHistory.update { current ->
            val currentList = current.toMutableList()
            currentList.remove(text)
            currentList
        }
    }

    fun pushCurrentClipboard() {
        val text = com.joseph.clipboard.windows.os.ClipboardManager.readText()
        if (!text.isNullOrBlank()) {
            if (_clipboardHistory.value.firstOrNull() != text) {
                addToHistory(text)
            }
            pushClipboardToTrustedNodes(text)
        }
    }

    private fun pushClipboardToTrustedNodes(text: String) {
        pushClipboardToSpecificNodes(text, _trustedNodes.value.toList())
    }

    fun pushClipboardToSpecificNodes(text: String, ips: List<String>) {
        ips.forEach { ip ->
            scope.launch {
                try {
                    val aesKey = SecurityVault.getAesKey(ip)
                    if (aesKey != null) {
                        // GCM only for peers that advertised the capability in discovery; everyone
                        // else (legacy Android, older Windows) gets CBC — safe-by-default, no interop break.
                        val supportsAuth = transferDaemon.getDiscoveredNodes().any { it.ip == ip && it.supportsAuthClipboard }
                        val encryptedBase64 = if (supportsAuth)
                            com.joseph.clipboard.windows.storage.CryptoEngine.encryptStringAuthenticated(text, aesKey)
                        else com.joseph.clipboard.windows.storage.CryptoEngine.encryptString(text, aesKey)
                        // Append newline in case receiver uses readLine()
                        Net.sendString(ip, Ports.CLIPBOARD, encryptedBase64 + "\n")
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to push clipboard to $ip: ${e.message}")
                }
            }
        }
    }

    fun updateDeviceName(name: String) {
        SecurityVault.setDeviceName(name)
        _deviceName.value = name
    }

    fun updateDiscoverable(discoverable: Boolean) {
        SecurityVault.setDiscoverable(discoverable)
        _isDiscoverable.value = discoverable
    }

    fun refreshTrustedNodes() {
        _trustedNodes.value = SecurityVault.getAllTrustedNodes().keys
    }

    fun restartServices() {
        scope.launch {
            // Stop all Network Daemons
            transferDaemon.stop()
            pairingDaemon.stop()
            clipboardDaemon.stop()
            fileTransferDaemon.stop()

            // Give sockets a moment to unbind
            delay(500)

            // Start Network Daemons again
            transferDaemon.start()
            pairingDaemon.start()
            clipboardDaemon.start()
            fileTransferDaemon.start()
        }
    }

    fun start() {

        // Start Network Daemons
        transferDaemon.start()
        pairingDaemon.start()
        clipboardDaemon.start()
        fileTransferDaemon.start()

        // Start OS Integrations
        ClipboardManager.addListener { newText ->
            if (newText.isNotBlank()) {
                addToHistory(newText)
                // Auto-push
                pushClipboardToTrustedNodes(newText)
            }
        }
        ClipboardManager.startPolling()
        
        // Fetch the current Windows clipboard exactly once at startup
        val initialText = com.joseph.clipboard.windows.os.ClipboardManager.readText()
        if (!initialText.isNullOrBlank()) {
            addToHistory(initialText)
        }
        
        refreshTrustedNodes()
        _transferHistory.value = TransferHistoryManager.load()

        // Poll for discovered devices periodically
        scope.launch {
            while (isActive) {
                val discovered = transferDaemon.getDiscoveredNodes()
                _discoveredDevices.value = discovered
                // Backfill paired-device names so they persist once a peer goes offline (covers devices
                // paired before names were stored). savePairedDeviceName no-ops when the name is unchanged.
                discovered.forEach { if (SecurityVault.isTrusted(it.ip)) SecurityVault.savePairedDeviceName(it.ip, it.name) }
                delay(1000)
            }
        }
    }

    fun stop() {
        transferDaemon.stop()
        pairingDaemon.stop()
        clipboardDaemon.stop()
        fileTransferDaemon.stop()
        ClipboardManager.stopPolling()
    }

    fun initiatePairing(ip: String) {
        scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                val targetDeviceName = _discoveredDevices.value.find { it.ip == ip }?.name ?: "Device"
                _outgoingPairingState.value = OutgoingPairingState(ip, targetDeviceName, "Connecting...")
                socket = java.net.Socket()
                socket.soTimeout = 15000
                socket.connect(InetSocketAddress(ip, Ports.PAIRING), 3000)

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val ecdh = EcdhSession()
                val myPubKey = ecdh.getPublicKey()
                val myDeviceName = SecurityVault.getDeviceName()

                writer.println("PAIR_REQ|$myDeviceName|${Base64.getEncoder().encodeToString(myPubKey)}")

                _outgoingPairingState.value = OutgoingPairingState(ip, targetDeviceName, "Waiting for response...")
                val ackResponse = reader.readLine()
                if (ackResponse != null && ackResponse.startsWith("PAIR_ACK|")) {
                    val peerPubKey = Base64.getDecoder().decode(ackResponse.split("|")[1])
                    val (aesKey, visualPin) = ecdh.deriveSecrets(peerPubKey)

                    _outgoingPairingState.value = OutgoingPairingState(ip, targetDeviceName, "Waiting for $targetDeviceName to accept...", visualPin)
                    val finalResponse = reader.readLine()

                    if (finalResponse == "PAIR_ACCEPT") {
                        SecurityVault.saveTrustedNode(ip, aesKey)
                        SecurityVault.savePairedDeviceName(ip, targetDeviceName)
                        refreshTrustedNodes()
                    }
                }

                _outgoingPairingState.value = null
            } catch (e: Exception) {
                System.err.println("Pairing failed with $ip")
                e.printStackTrace()
                _outgoingPairingState.value = null
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun unpairDevice(ip: String) {
        scope.launch {
            val key = SecurityVault.getAesKey(ip)   // capture before removal — needed to answer the unpair challenge
            SecurityVault.removeTrustedNode(ip)
            refreshTrustedNodes()
            // Authenticated unpair (best-effort): prove possession of the shared key so updated peers
            // honor the removal. A plain UNPAIR_REQ is now ignored by updated peers.
            try {
                java.net.Socket().use { socket ->
                    socket.soTimeout = 3000
                    socket.connect(java.net.InetSocketAddress(ip, Ports.PAIRING), 2000)
                    val writer = java.io.PrintWriter(socket.getOutputStream(), true)
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))
                    writer.println("UNPAIR_REQ|")
                    if (key != null) {
                        val challenge = reader.readLine()
                        if (challenge != null && challenge.startsWith("UNPAIR_CHALLENGE|")) {
                            val nonce = java.util.Base64.getDecoder().decode(challenge.removePrefix("UNPAIR_CHALLENGE|"))
                            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                            mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                            val proof = mac.doFinal(nonce)
                            writer.println("UNPAIR_PROOF|${java.util.Base64.getEncoder().encodeToString(proof)}")
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }
    
    fun clearNetworkCache() {
        transferDaemon.clearCache()
        _discoveredDevices.value = emptyList()
    }
    
    fun cancelOutgoingPairing() {
        _outgoingPairingState.value = null
    }

    // --- File Transfer Methods ---

    fun sendFiles(files: List<File>, targetIp: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val sessionId = UUID.randomUUID().toString()
                val session = OutgoingFileSession(sessionId, targetIp, files)
                activeOutgoingFileSessions[sessionId] = session

                // Close our side after the handshake. Android will connect back with ACCEPT_OFFER.
                // Use an explicit connect timeout: the Socket(host, port) constructor blocks for
                // the full OS timeout (~minutes) when the target IP is unreachable.
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(targetIp, Ports.FILE_TRANSFER), 3000)
                    socket.soTimeout = 10000
                    val outStream = socket.getOutputStream()
                    val inStream = socket.getInputStream()

                    val deviceName = SecurityVault.getDeviceName()
                    val offerHeader = "OFFER|$sessionId|$deviceName|${files.size}\n"
                    var manifestStr = "MANIFEST|"
                    for (f in files) {
                        manifestStr += "${f.name}:${f.length()}|"
                    }
                    manifestStr += "\n"

                    outStream.write(offerHeader.toByteArray(Charsets.UTF_8))
                    outStream.write(manifestStr.toByteArray(Charsets.UTF_8))
                    outStream.flush()

                    // Wait for OFFER_RECEIVED\n
                    val response = Net.readLineUtf8(inStream)
                    if (response != "OFFER_RECEIVED") {
                        System.err.println("Receiver did not acknowledge offer: $response")
                    } else {
                        // Create pending TransferRecord
                        val record = TransferRecord(
                            sessionId = sessionId,
                            deviceName = targetIp, // Could look up proper name from discovered nodes
                            direction = "outgoing",
                            fileNames = files.map { it.name },
                            totalBytes = files.sumOf { it.length() },
                            timestamp = System.currentTimeMillis(),
                            status = "pending",
                            filesCompleted = 0,
                            totalFiles = files.size,
                            fileSizes = files.map { it.length() },
                            currentFileOffset = 0L
                        )
                        // H-p1: atomic prepend so a concurrent transfer's progress update isn't clobbered.
                        _transferHistory.update { listOf(record) + it }
                        saveHistory()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Off-grid "send via QR" (Windows = sender) ---

    /** Result of opening a QR host session: the QR payload plus the AP creds to display (null when LAN-only). */
    data class QrHostInfo(
        val qrJson: String,
        val ssid: String?,
        val passphrase: String?,
        val hotspotActive: Boolean
    )

    /**
     * Opens an off-grid advertising window and returns the QR payload to render. Stands up a Wi-Fi
     * Direct hotspot (for the true off-grid case) and/or advertises the LAN IP (for the same-network
     * case). Returns null when there are no real files, or when this machine can offer NEITHER a
     * hotspot NOR a shared LAN (nothing the phone could connect to).
     *
     * This blocks for ~1–4s while the hotspot spins up, so call it off the UI thread.
     */
    fun startQrHostSession(files: List<File>): QrHostInfo? {
        val realFiles = files.filter { it.exists() && it.isFile }
        if (realFiles.isEmpty()) return null

        val myEpoch = qrEpoch.incrementAndGet()
        // Off-grid AP (null if the adapter lacks Wi-Fi Direct / Wi-Fi is off) + same-network LAN IP
        // (null off-grid). At least one is required to host.
        val hotspot = HotspotController.start()
        val lanIp = Net.localLanIpv4()
        if (hotspot == null && lanIp == null) {
            if (qrEpoch.get() == myEpoch) stopHotspotIfIdle()
            return null
        }
        // Superseded while we were starting (dialog dismissed or a newer QR opened) — don't register a
        // stale session; drop the AP we just started if nothing else needs it.
        if (qrEpoch.get() != myEpoch) {
            stopHotspotIfIdle()
            return null
        }

        val session = OffGridQrSender.HostSession(
            sessionId = UUID.randomUUID().toString(),
            ecdh = EcdhSession(),
            token = OffGridQrSender.newToken(),
            files = realFiles,
            // The name rides '|'-separated, LF-terminated OFFER/QR frames — strip delimiters
            // so a user-chosen name like "Joe|Work" can't desync the receiver's positional parse.
            deviceName = SecurityVault.getDeviceName()
                .replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(32)
        )
        pendingQrSession.set(session)
        return QrHostInfo(
            qrJson = OffGridQrSender.buildPayloadJson(session, hotspot, lanIp),
            ssid = hotspot?.ssid,
            passphrase = hotspot?.passphrase,
            hotspotActive = hotspot != null
        )
    }

    /** Tear down an unscanned QR window (dialog dismissed) so a stray dial-in is rejected. */
    fun cancelQrHostSession() {
        qrEpoch.incrementAndGet() // supersede any in-flight startQrHostSession
        pendingQrSession.set(null)
        stopHotspotIfIdle()
    }

    /**
     * Drop the Wi-Fi Direct AP once nothing needs it: no QR is being shown AND no outgoing transfer is
     * streaming. (The phone must stay joined for the whole transfer, so we never stop it mid-stream.)
     */
    private fun stopHotspotIfIdle() {
        if (pendingQrSession.get() == null && activeOutgoingFileSessions.isEmpty()) {
            HotspotController.stop()
        }
    }

    /**
     * Inbound off-grid dial from an Android scanner (handed over by the file
     * daemon). Runs the LAN handshake against the pending QR window, then reuses
     * the normal byte-pump to stream the files on the same socket.
     */
    private fun handleP2pHello(socket: Socket, header: String) {
        scope.launch(Dispatchers.IO) {
            // Atomically claim the one-shot window: a second concurrent dial-in gets null → busy.
            val session = pendingQrSession.getAndSet(null)
            if (session == null) {
                OffGridQrSender.writeBusy(socket)
                try { socket.close() } catch (e: Exception) {}
                return@launch
            }
            try {
                socket.soTimeout = 30000
                val handshake = OffGridQrSender.runHandshake(socket, header, session)
                if (!handshake.accepted) {
                    // Restore the window (only if no newer QR replaced it) so a genuine retry works.
                    pendingQrSession.compareAndSet(null, session)
                    try { socket.close() } catch (e: Exception) {}
                    return@launch
                }

                val outSession = OutgoingFileSession(
                    session.sessionId,
                    socket.inetAddress.hostAddress ?: "",
                    session.files
                )
                activeOutgoingFileSessions[session.sessionId] = outSession

                val record = TransferRecord(
                    sessionId = session.sessionId,
                    deviceName = socket.inetAddress.hostAddress ?: "QR",
                    direction = "outgoing",
                    fileNames = session.files.map { it.name },
                    totalBytes = session.files.sumOf { it.length() },
                    timestamp = System.currentTimeMillis(),
                    status = "transferring",
                    filesCompleted = 0,
                    totalFiles = session.files.size,
                    fileSizes = session.files.map { it.length() },
                    currentFileOffset = 0L,
                    transport = "p2p"
                )
                // H-p1: atomic prepend so a concurrent transfer's progress update isn't clobbered.
                _transferHistory.update { listOf(record) + it }
                saveHistory()

                // Reuse the existing byte-pump: streams in manifest order, then (when the phone
                // advertised the delivery ack) waits for TRANSFER_COMPLETE before finishing.
                streamFilesToSocket(outSession, socket, 0, 0L, handshake.ackExpected)
            } catch (e: Exception) {
                e.printStackTrace()
                activeOutgoingFileSessions.remove(session.sessionId)
                // H-p3: we claimed the one-shot QR window via getAndSet(null) above; on any
                // failure here restore it (only if no newer QR replaced it) so the phone's
                // retry can dial back in instead of getting P2P_BUSY until the QR is reopened.
                pendingQrSession.compareAndSet(null, session)
                try { socket.close() } catch (e2: Exception) {}
            }
        }
    }

    fun acceptIncomingFileTransfer(sessionId: String) {
        val session = activeIncomingFileSessions[sessionId] ?: return
        _incomingFileOffer.value = null
        
        // Emit navigation event so FileTransferScreen opens the transfer history detail
        _navigateToTransferSession.value = sessionId
        
        scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                // "fgcm" requests an authenticated (FileSeal) bulk stream when the sender advertised
                // FGCM1 and we hold its pairing key; legacy senders ignore it and stream plaintext.
                val fileKey = fileKeyIfAead(session.senderIp)
                socket = Socket(session.senderIp, Ports.FILE_TRANSFER)
                socket.soTimeout = 10000
                val outStream = socket.getOutputStream()
                val acceptCmd = if (fileKey != null) "ACCEPT_OFFER|${sessionId}|fgcm\n" else "ACCEPT_OFFER|${sessionId}\n"
                outStream.write(acceptCmd.toByteArray(Charsets.UTF_8))
                outStream.flush()

                activeFileTransferSockets[sessionId] = socket
                
                val record = TransferRecord(
                    sessionId = sessionId,
                    deviceName = session.senderName,
                    direction = "incoming",
                    fileNames = session.manifest.map { it.first },
                    totalBytes = session.manifest.sumOf { it.second },
                    timestamp = System.currentTimeMillis(),
                    status = "transferring",
                    filesCompleted = 0,
                    totalFiles = session.manifest.size,
                    fileSizes = session.manifest.map { it.second },
                    currentFileOffset = 0L
                )
                // H-p1: atomic prepend so a concurrent transfer's progress update isn't clobbered.
                _transferHistory.update { listOf(record) + it }
                saveHistory()

                receiveFilesFromSocket(session, socket, sessionId, 0, 0L, fileKey)
            } catch (e: Exception) {
                e.printStackTrace()
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    /** Pairing key for [ip] iff a FileSeal stream should be used (peer advertised FGCM1 AND we hold
     *  its key); null ⇒ plaintext. Mirrors Android TransferDaemon.fileKeyIfAead. */
    private fun fileKeyIfAead(ip: String): ByteArray? {
        val key = SecurityVault.getAesKey(ip) ?: return null
        return if (transferDaemon.getDiscoveredNodes().any { it.ip == ip && it.supportsAuthFile }) key else null
    }

    fun resumeIncomingFileTransfer(sessionId: String) {
        val session = activeIncomingFileSessions[sessionId] ?: return
        val records = _transferHistory.value
        val record = records.find { it.sessionId == sessionId } ?: return
        if (record.status != "failed") return
        
        scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                val fileKey = fileKeyIfAead(session.senderIp)
                socket = Socket(session.senderIp, Ports.FILE_TRANSFER)
                socket.soTimeout = 10000
                val outStream = socket.getOutputStream()
                val startFileIndex = record.filesCompleted
                var trueOffset = 0L
                if (fileKey == null && startFileIndex < session.manifest.size) {
                    // Plaintext resume recomputes from the .part length. AEAD resume restarts the
                    // in-progress file at chunk 0 (FileSeal records aren't byte-seekable), so offset
                    // stays 0 and the receiver reopens the .part fresh.
                    val (name, _) = session.manifest[startFileIndex]
                    val saveDir = File(SecurityVault.getSaveLocation())
                    val partFile = File(saveDir, "$name.part")
                    if (partFile.exists()) {
                        trueOffset = partFile.length()
                    }
                }

                val resumeCmd = if (fileKey != null)
                    "RESUME_REQUEST|${sessionId}|${startFileIndex}|${trueOffset}|fgcm\n"
                else "RESUME_REQUEST|${sessionId}|${startFileIndex}|${trueOffset}\n"
                outStream.write(resumeCmd.toByteArray(Charsets.UTF_8))
                outStream.flush()

                receiveFilesFromSocket(session, socket, sessionId, startFileIndex, trueOffset, fileKey)
            } catch (e: Exception) {
                e.printStackTrace()
                try { socket?.close() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    // Update status back to failed if connection fails
                    updateRecord(sessionId) { it.copy(status = "failed") }
                }
            }
        }
    }

    /**
     * Reduce an untrusted manifest filename to a safe leaf written into the save folder.
     * The name comes straight off the wire, so a hostile sender could embed path separators
     * or ".." to escape the save directory. Keep only the final path component (handling both
     * '/' and '\\'), reject empty/dot/dot-dot, and strip Windows-illegal/control chars, falling
     * back to a stable default so a bad name degrades gracefully instead of failing the transfer.
     */
    private fun sanitizeIncomingFileName(rawName: String): String {
        val leaf = rawName.replace('\\', '/').substringAfterLast('/').trim()
        if (leaf.isEmpty() || leaf == "." || leaf == "..") return "received_file"
        val cleaned = leaf.filterNot { it in "<>:\"/\\|?*" || it.code < 0x20 }
        return cleaned.ifBlank { "received_file" }
    }

    private fun receiveFilesFromSocket(session: IncomingFileSession, socket: Socket, sessionId: String, startFileIndex: Int, startOffset: Long, fileKey: ByteArray? = null) {
        try {
            activeFileTransferSockets[sessionId] = socket

            updateRecord(sessionId) { it.copy(status = "transferring") }

            val saveDir = File(SecurityVault.getSaveLocation())
            if (!saveDir.exists()) saveDir.mkdirs()

            val inputStream = socket.getInputStream()
            val buffer = ByteArray(8192)
            // Non-null fileKey ⇒ the bulk stream is FileSeal-sealed; decrypt per chunk.
            val fs = com.joseph.clipboard.windows.storage.FileSeal
            val aeadKeySpec = fileKey?.let { fs.keySpec(fs.deriveKey(it)) }

            for (i in startFileIndex until session.manifest.size) {
                val (name, size) = session.manifest[i]
                val isResumingThisFile = (i == startFileIndex && startOffset > 0)

                val finalFile = File(saveDir, name)
                val partFile = File(saveDir, "$name.part")

                if (!isResumingThisFile && partFile.exists()) {
                    partFile.delete()
                }

                var remaining = size - (if (isResumingThisFile) startOffset else 0L)
                var currentOffset = if (isResumingThisFile) startOffset else 0L
                var lastUpdateTime = System.currentTimeMillis()

                FileOutputStream(partFile, isResumingThisFile).use { fos ->
                    if (aeadKeySpec != null) {
                        // AEAD: read GCM records, decrypt, append plaintext until the file's bytes are
                        // all on disk. AEAD resume restarts the file, so currentOffset is 0 here.
                        var chunkIndex = currentOffset / fs.CHUNK_SIZE
                        while (remaining > 0) {
                            val pt = fs.openChunk(aeadKeySpec, inputStream, chunkIndex, remaining)
                            fos.write(pt)
                            remaining -= pt.size
                            currentOffset += pt.size
                            chunkIndex++
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime > 100) {
                                lastUpdateTime = now
                                updateRecord(sessionId) { it.copy(currentFileOffset = currentOffset) }
                            }
                        }
                    } else {
                        while (remaining > 0) {
                            val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                            val bytesRead = inputStream.read(buffer, 0, toRead)
                            if (bytesRead == -1) break
                            fos.write(buffer, 0, bytesRead)
                            remaining -= bytesRead
                            currentOffset += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime > 100) {
                                lastUpdateTime = now
                                updateRecord(sessionId) { it.copy(currentFileOffset = currentOffset) }
                            }
                        }
                    }
                    fos.flush()
                }

                if (remaining <= 0L) {
                    if (finalFile.exists()) finalFile.delete()
                    partFile.renameTo(finalFile)

                    updateRecord(sessionId) { it.copy(filesCompleted = it.filesCompleted + 1, currentFileOffset = 0L) }
                } else {
                    throw Exception("Stream ended prematurely")
                }
            }

            updateRecord(sessionId) { it.copy(status = "completed") }
        } catch (e: Exception) {
            e.printStackTrace()
            if (_transferHistory.value.find { it.sessionId == sessionId }?.status != "cancelled") {
                updateRecord(sessionId) { it.copy(status = "failed") }
            }
        } finally {
            activeFileTransferSockets.remove(sessionId)
            try { socket.close() } catch(e:Exception){}
        }
    }
    
    fun declineIncomingFileTransfer() {
        val session = _incomingFileOffer.value
        _incomingFileOffer.value = null
        
        // Send DECLINE_OFFER back to the sender so their UI updates from "pending" to "declined"
        if (session != null) {
            scope.launch(Dispatchers.IO) {
                Net.sendString(session.senderIp, Ports.FILE_TRANSFER, "DECLINE_OFFER|${session.sessionId}\n")
            }
        }
    }

    private fun streamFilesToSocket(session: OutgoingFileSession, socket: Socket, startFileIndex: Int, startOffset: Long, ackExpected: Boolean, fileKey: ByteArray? = null) {
        scope.launch(Dispatchers.IO) {
            // When the receiver honours the delivery ack we only declare success once it confirms,
            // and keep the session around on failure so its resume can reconnect to it.
            var keepSession = false
            try {
                activeFileTransferSockets[session.sessionId] = socket
                val outStream = socket.getOutputStream()
                val buffer = ByteArray(8192)
                // Non-null fileKey ⇒ seal the bulk stream per-chunk with FileSeal; else plaintext.
                val fs = com.joseph.clipboard.windows.storage.FileSeal
                val aeadKeySpec = fileKey?.let { fs.keySpec(fs.deriveKey(it)) }

                for (i in startFileIndex until session.files.size) {
                    val file = session.files[i]
                    if (!file.exists()) continue

                    var currentOffset = if (i == startFileIndex) startOffset else 0L
                    var lastUpdateTime = System.currentTimeMillis()

                    FileInputStream(file).use { fileIn ->
                        if (i == startFileIndex && startOffset > 0) {
                            // H-p2: InputStream.skip() may skip fewer bytes than requested; looping
                            // until startOffset bytes are skipped prevents re-sending already-sent
                            // bytes, which would desync the receiver's positional read of this file.
                            var toSkip = startOffset
                            while (toSkip > 0) {
                                val skipped = fileIn.skip(toSkip)
                                if (skipped <= 0) break
                                toSkip -= skipped
                            }
                            if (toSkip > 0) throw Exception("Failed to skip to resume offset $startOffset for ${file.name}")
                        }
                        if (aeadKeySpec != null) {
                            // AEAD: seal the file as GCM records sized from the file; the receiver
                            // bounds by the manifest size. AEAD resume restarts the file (offset 0).
                            val plainLen = file.length()
                            var remaining = plainLen - currentOffset
                            var chunkIndex = currentOffset / fs.CHUNK_SIZE
                            val cbuf = ByteArray(fs.CHUNK_SIZE)
                            while (remaining > 0) {
                                val want = if (remaining > fs.CHUNK_SIZE) fs.CHUNK_SIZE else remaining.toInt()
                                var filled = 0
                                while (filled < want) {
                                    val r = fileIn.read(cbuf, filled, want - filled)
                                    if (r == -1) break
                                    filled += r
                                }
                                if (filled == 0) break
                                outStream.write(fs.sealChunk(aeadKeySpec, cbuf, filled, chunkIndex))
                                currentOffset += filled
                                remaining -= filled
                                chunkIndex++

                                val now = System.currentTimeMillis()
                                if (now - lastUpdateTime > 100) {
                                    lastUpdateTime = now
                                    updateRecord(session.sessionId) { it.copy(currentFileOffset = currentOffset) }
                                }
                            }
                        } else {
                            while (true) {
                                val read = fileIn.read(buffer)
                                if (read == -1) break
                                outStream.write(buffer, 0, read)
                                currentOffset += read

                                val now = System.currentTimeMillis()
                                if (now - lastUpdateTime > 100) {
                                    lastUpdateTime = now
                                    updateRecord(session.sessionId) { it.copy(currentFileOffset = currentOffset) }
                                }
                            }
                        }
                    }

                    updateRecord(session.sessionId) {
                        it.copy(
                            filesCompleted = it.filesCompleted + 1,
                            // Mirror the receive side (:791): zero the offset in the SAME update as the
                            // filesCompleted bump, so the next file's row doesn't briefly render with the
                            // previous file's stale (large) offset — which made the bar snap to ~100% then
                            // drop back to 0% at every multi-file boundary ("numbers jumping").
                            currentFileOffset = 0L,
                            // Defer "completed" to the delivery ack when the receiver will send one.
                            status = if (i == session.files.size - 1 && !ackExpected) "completed" else "transferring"
                        )
                    }
                }
                outStream.flush()

                if (ackExpected) {
                    if (awaitCompletionAck(socket, session.sessionId)) {
                        updateRecord(session.sessionId) { it.copy(status = "completed") }
                    } else {
                        // Bytes written but no delivery confirmation (late drop) — keep the session
                        // so the phone can resume, and surface "failed" so its UI offers resume.
                        keepSession = true
                        updateRecord(session.sessionId) { it.copy(status = "failed") }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (ackExpected && _transferHistory.value.find { it.sessionId == session.sessionId }?.status != "cancelled") {
                    keepSession = true
                    updateRecord(session.sessionId) { it.copy(status = "failed") }
                }
            } finally {
                activeFileTransferSockets.remove(session.sessionId)
                try { socket.close() } catch(e:Exception){}
                if (!keepSession) activeOutgoingFileSessions.remove(session.sessionId)
                // Transfer finished — drop the off-grid AP unless a QR is still being shown or the
                // session was kept for a resumable retry (keepSession leaves it in activeOutgoing).
                stopHotspotIfIdle()
            }
        }
    }

    /**
     * Ceiling the sender waits for the receiver's TRANSFER_COMPLETE after the last byte is written.
     * Mirrors Android `TransferDaemon.COMPLETION_ACK_TIMEOUT_MS`. Returns false on timeout/EOF/mismatch.
     */
    private fun awaitCompletionAck(socket: Socket, sessionId: String): Boolean = try {
        socket.soTimeout = 30000
        Net.readLineUtf8(socket.getInputStream()) == "TRANSFER_COMPLETE|$sessionId"
    } catch (e: Exception) { false }
    
    fun deleteTransferRecord(sessionId: String) {
        // H-p1: atomic filter so an in-flight transfer's progress update isn't clobbered.
        _transferHistory.update { records -> records.filter { it.sessionId != sessionId } }
        saveHistory()
    }
    
    fun cancelTransfer(sessionId: String) {
        // Mark cancelled locally
        val record = _transferHistory.value.find { it.sessionId == sessionId }
        if (record != null && record.status != "completed") {
            updateRecord(sessionId) { it.copy(status = "cancelled") }
        }
        // Close active socket
        try {
            activeFileTransferSockets[sessionId]?.close()
            activeFileTransferSockets.remove(sessionId)
        } catch (e: Exception) { e.printStackTrace() }
        // Notify peer
        if (record != null) {
            scope.launch(Dispatchers.IO) {
                val targetIp = activeOutgoingFileSessions[sessionId]?.targetIp
                    ?: activeIncomingFileSessions[sessionId]?.senderIp
                    ?: _discoveredDevices.value.find { it.name == record.deviceName }?.ip
                if (targetIp != null) {
                    Net.sendString(targetIp, Ports.FILE_TRANSFER, "CANCEL_TRANSFER|$sessionId\n")
                }
            }
        }
    }
    
    fun deleteMultipleTransferRecords(sessionIds: Set<String>) {
        // H-p1: atomic filter so an in-flight transfer's progress update isn't clobbered.
        _transferHistory.update { records -> records.filter { !sessionIds.contains(it.sessionId) } }
        saveHistory()
    }

    /**
     * Finds the record with [sessionId] via a single indexed lookup, applies [transform],
     * reassigns the StateFlow, and persists when [persist]. No-op if the record is absent.
     */
    private fun updateRecord(sessionId: String, persist: Boolean = true, transform: (TransferRecord) -> TransferRecord) {
        // H-p1: atomic read-modify-write. Multiple in-flight transfers each call updateRecord
        // from their own IO coroutine; a plain value=f(value) lets a slower writer overwrite a
        // sibling transfer's just-applied progress. update {} retries on a concurrent change.
        var changed = false
        _transferHistory.update { current ->
            val idx = current.indexOfFirst { it.sessionId == sessionId }
            if (idx == -1) {
                current
            } else {
                changed = true
                val records = current.toMutableList()
                records[idx] = transform(records[idx])
                records
            }
        }
        if (changed && persist) saveHistory()
    }

    private fun saveHistory() {
        scope.launch(Dispatchers.IO) {
            TransferHistoryManager.save(_transferHistory.value)
        }
    }
}

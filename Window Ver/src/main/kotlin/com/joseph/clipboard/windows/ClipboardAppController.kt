package com.joseph.clipboard.windows

import com.joseph.clipboard.windows.storage.SecurityVault
import com.joseph.clipboard.windows.storage.TransferHistoryManager
import com.joseph.clipboard.windows.network.ClipboardDaemon
import com.joseph.clipboard.windows.network.DiscoveredDevice
import com.joseph.clipboard.windows.network.DowngradePolicy
import com.joseph.clipboard.windows.network.FileTransferDaemon
import com.joseph.clipboard.windows.network.ManifestCodec
import com.joseph.clipboard.windows.network.Net
import com.joseph.clipboard.windows.network.OffGridQrSender
import com.joseph.clipboard.windows.network.QrHostCoordinator
import com.joseph.clipboard.windows.network.WireFrames
import com.joseph.clipboard.windows.network.control.SessionCrypto
import com.joseph.clipboard.windows.network.PairingClient
import com.joseph.clipboard.windows.network.PairingDaemon
import com.joseph.clipboard.windows.network.Ports
import com.joseph.clipboard.windows.network.TransferDaemon
import com.joseph.clipboard.windows.os.ClipboardManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import com.joseph.clipboard.windows.network.FileStreamer
import java.io.File
import java.net.Socket
import com.joseph.clipboard.windows.storage.CryptoEngine

/**
 * Thrown when a peer that previously negotiated an authenticated (AES-GCM) channel now offers only
 * the weaker path — a capability-strip downgrade on the unauthenticated discovery. The transfer is
 * aborted rather than continued in the clear. Mirrors Android's DowngradeBlockedException.
 */
class DowngradeBlockedException(message: String) : Exception(message)

data class OutgoingFileSession(
    val sessionId: String,
    val targetIp: String,
    val files: List<File>,
    // Per-file lengths snapshotted at OFFER time. The MANIFEST we send commits the receiver to read
    // exactly this many bytes per file; if a file's on-disk length later changes we must still stream
    // exactly this count (or abort) so a mid-transfer size change can't desync the receiver.
    val fileSizes: List<Long> = files.map { it.length() },
    // "lan" (IP-paired) or "p2p" (off-grid QR/hotspot). An off-grid receiver resumes from an
    // ephemeral, never-paired IP, so the trust gate exempts a RESUME_REQUEST that names a live p2p
    // outgoing session (XP-05) — mirrors the Android FileTransferServer exemption.
    val transport: String = "lan"
)

data class IncomingFileSession(
    val sessionId: String,
    val senderIp: String,
    val senderName: String,
    val manifest: List<Pair<String, Long>>
)

// Mirrors Android TransferRecord (schema v2): per-file facts are
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
    val transport: String = "lan", // "lan" or "p2p"
    // Absolute source paths for outgoing files, index-aligned to fileNames.
    // Empty for incoming records and for legacy records saved before this field
    // existed — the history UI falls back to the save folder in those cases.
    val filePaths: List<String> = emptyList()
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

    // SupervisorJob so one child coroutine's uncaught exception can't cancel the whole scope's Job
    // and permanently kill discovery + every future send/pair/accept until app restart; the handler
    // logs strays instead of relying solely on each call site's try/catch.
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, e -> System.err.println("ClipboardAppController scope: uncaught $e") }
    )

    // Daemons
    private val transferDaemon = TransferDaemon(
        deviceNameProvider = { SecurityVault.getDeviceName() }
    )
    
    // File Transfer State
    // Concurrent: written/removed from per-connection handler coroutines (QR + normal sends).
    private val activeOutgoingFileSessions = java.util.concurrent.ConcurrentHashMap<String, OutgoingFileSession>()
    // Concurrent: written from FileTransferDaemon's per-connection handler (onOfferReceived) and
    // read/removed from receive coroutines — must not be an unsynchronized plain map.
    private val activeIncomingFileSessions = java.util.concurrent.ConcurrentHashMap<String, IncomingFileSession>()
    // Off-grid "send via QR" advertising window (Windows = sender). All QR/hotspot/BLE-control state
    // lives in the coordinator; it asks isTransferActive() so it never drops the AP mid-stream.
    private val qrHost = QrHostCoordinator(isTransferActive = { activeOutgoingFileSessions.isNotEmpty() })
    private val activeFileTransferSockets = java.util.concurrent.ConcurrentHashMap<String, java.net.Socket>()
    
    private val _transferHistory = MutableStateFlow<List<TransferRecord>>(emptyList())
    val transferHistory: StateFlow<List<TransferRecord>> = _transferHistory

    // Sessions whose incoming byte-pump is live right now (drives the global "Receiving…" pill).
    // Deliberately NOT derived from transferHistory statuses: records persist verbatim, so a crash
    // mid-receive leaves status=="transferring" forever and would pin the pill on after restart.
    // Membership tracks the receiveFilesFromSocket try/finally instead — accept and resume both
    // funnel through that one pump — so an entry exists iff bytes can still arrive.
    private val _activeIncomingSessions = MutableStateFlow<Set<String>>(emptySet())
    val activeIncomingSessions: StateFlow<Set<String>> = _activeIncomingSessions.asStateFlow()
    
    // For UI popups regarding incoming files
    private val _incomingFileOffer = MutableStateFlow<IncomingFileSession?>(null)
    val incomingFileOffer: StateFlow<IncomingFileSession?> = _incomingFileOffer
    
    // Navigation event: emits session ID when accept is clicked, so UI can show transfer history
    private val _navigateToTransferSession = MutableStateFlow<String?>(null)
    val navigateToTransferSession: StateFlow<String?> = _navigateToTransferSession.asStateFlow()
    
    fun clearNavigateToTransferSession() {
        _navigateToTransferSession.value = null
    }

    /** Receiving-pill click: rides the same navigate-to-session flow the accept path drives. */
    fun openTransferSession(sessionId: String) {
        _navigateToTransferSession.value = sessionId
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
        },
        onClipboardDeleted = { text ->
            deleteFromHistory(text)
        }
    )
    private val fileTransferDaemon = FileTransferDaemon(
        onOfferReceived = { ip, sessionId, deviceName, manifest ->
            // Filenames arrive off the wire and are written into the save folder, so a hostile
            // sender could embed path separators or ".." to escape it (path traversal). Reduce
            // every entry to a safe leaf name before the session is stored or any file is opened;
            // the receive and resume paths both derive their .part paths from this stored manifest.
            val safeManifest = manifest.map { (name, size) -> FileStreamer.sanitizeIncomingFileName(name) to size }
            val session = IncomingFileSession(sessionId, ip, deviceName, safeManifest)
            activeIncomingFileSessions[sessionId] = session
            _incomingFileOffer.value = session
            onRequestForeground?.invoke()
        },
        onAcceptReceived = { sessionId, fgcm, useV2, socket ->
            val session = activeOutgoingFileSessions[sessionId]
            if (session != null) {
                // Android accepted our offer. Stream files out! (LAN accept never carries the
                // delivery-ack suffix, so ackExpected = false here.) "fgcm"/"fgcm2" ⇒ seal the bulk
                // stream with FileSeal under the shared pairing key; "fgcm2" selects the v2 AEAD.
                // A pinned peer that dropped fgcm is a downgrade attempt → refuse the plaintext stream.
                try {
                    val fileKey = senderFileKey(session.targetIp, fgcm)
                    // Gate v2 by the same TOFU pin the receiver enforces (XP-DEF-01): a v2-pinned peer
                    // whose ACCEPT stripped "fgcm2"→"fgcm" is a downgrade → throw, not seal v1 silently.
                    val gatedV2 = senderUseFileV2(session.targetIp, useV2)
                    streamFilesToSocket(session, socket, 0, 0L, ackExpected = false, fileKey = fileKey, useV2 = gatedV2)
                } catch (e: DowngradeBlockedException) {
                    System.err.println("TX: ${e.message} — refusing plaintext stream")
                    updateRecord(sessionId) { it.copy(status = "failed") }
                    try { socket.close() } catch(e2:Exception){}
                }
            } else {
                try { socket.close() } catch(e:Exception){}
            }
        },
        onResumeReceived = { sessionId, fileIndex, offset, ackExpected, fgcm, useV2, socket ->
            val session = activeOutgoingFileSessions[sessionId]
            // Bounds-check the network-supplied resume position before trusting it, so a bad
            // (fileIndex, offset) can't make the sender skip past or re-send file data (mirrors the
            // Android TransferDaemon RESUME_REQUEST guard). A malformed position closes the socket.
            if (session != null && fileIndex >= 0 && fileIndex < session.files.size && offset >= 0L) {
                try {
                    val fileKey = senderFileKey(session.targetIp, fgcm)
                    val gatedV2 = senderUseFileV2(session.targetIp, useV2)
                    streamFilesToSocket(session, socket, fileIndex, offset, ackExpected, fileKey = fileKey, useV2 = gatedV2)
                } catch (e: DowngradeBlockedException) {
                    System.err.println("TX: ${e.message} — refusing plaintext resume")
                    updateRecord(sessionId) { it.copy(status = "failed") }
                    try { socket.close() } catch(e2:Exception){}
                }
            } else {
                try { socket.close() } catch(e:Exception){}
            }
        },
        onCancelReceived = { sessionId ->
            if (_transferHistory.value.find { it.sessionId == sessionId }?.status != "completed") {
                updateRecord(sessionId) { it.copy(status = "cancelled") }
            }
            // Abort any live stream for this session, not just the history row: drop the session
            // state so a cancelled transfer can't be accepted/resumed later, then close the data
            // socket to interrupt a blocked read/write now. Record is marked "cancelled" BEFORE the
            // close so the stream's catch sees it and doesn't overwrite with "failed"/keep-session.
            activeOutgoingFileSessions.remove(sessionId)
            activeIncomingFileSessions.remove(sessionId)
            try { activeFileTransferSockets.remove(sessionId)?.close() } catch (e: Exception) {}
        },
        onP2pHelloReceived = { socket, header -> handleP2pHello(socket, header) },
        // Discovery (and the AUTH2 capability flag) lives in the separate transferDaemon; surface it so
        // the OFFER branch can decide whether to issue the AUTH2 admission challenge. Mirrors how the
        // FileSeal-v2 send path reads supportsAuthFileV2 off the same discovered-node set.
        supportsAuth2Provider = { ip -> transferDaemon.getDiscoveredNodes().any { it.ip == ip && it.supportsAuth2 } },
        // XP-05: admit an off-grid receiver's RESUME_REQUEST past the LAN trust gate when its sessionId
        // names a live outgoing p2p session (registered in handleP2pHello with transport="p2p").
        isOffGridResumeSession = { sid -> activeOutgoingFileSessions[sid]?.transport == "p2p" }
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
        // Atomic update — addToHistory runs from multiple IO coroutines (clipboard
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
        // Atomic update to avoid clobbering a concurrent addToHistory.
        _clipboardHistory.update { current ->
            val currentList = current.toMutableList()
            currentList.remove(text)
            currentList
        }
    }

    fun pushCurrentClipboard() {
        val text = ClipboardManager.readText()
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
                        val encryptedBase64 = sealClipboardForPeer(ip, aesKey, text)
                        if (encryptedBase64 != null) {
                            // Append newline in case receiver uses readLine()
                            Net.sendString(ip, Ports.CLIPBOARD, encryptedBase64 + "\n")
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to push clipboard to $ip: ${e.message}")
                }
            }
        }
    }

    /**
     * Seal a clipboard payload for one peer with anti-downgrade enforcement (mirrors Android
     * sealClipboardForPeer). Every peer gets an authenticated GCM seal — CBC was retired in 3.5.0,
     * so a peer that never advertised GCM1 must update to open it. Once a peer has used GCM we pin
     * it, and a later loss of GCM1 (a capability-strip on the unauthenticated discovery) makes us
     * skip the peer (return null) rather than send to a suspect channel. Fail closed.
     */
    private fun sealClipboardForPeer(ip: String, aesKey: ByteArray, plain: String): String? {
        val supportsAuth = transferDaemon.getDiscoveredNodes().any { it.ip == ip && it.supportsAuthClipboard }
        return when (DowngradePolicy.decide(supportsAuth, SecurityVault.isClipboardGcmPinned(ip))) {
            DowngradePolicy.Decision.SEAL -> {
                SecurityVault.pinClipboardGcm(ip)
                CryptoEngine.encryptStringAuthenticated(plain, aesKey)
            }
            // CBC retired in 3.5.0: a peer not (yet) advertising GCM1 still gets an authenticated GCM
            // seal (it must update to open it) instead of the malleable unauthenticated CBC path.
            DowngradePolicy.Decision.PLAINTEXT -> CryptoEngine.encryptStringAuthenticated(plain, aesKey)
            DowngradePolicy.Decision.BLOCK -> {
                System.err.println("Clipboard GCM downgrade blocked for $ip — skipping peer")
                null
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
        val initialText = ClipboardManager.readText()
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
            val targetDeviceName = _discoveredDevices.value.find { it.ip == ip }?.name ?: "Device"
            PairingClient.pair(
                ip = ip,
                deviceName = targetDeviceName,
                myDeviceName = SecurityVault.getDeviceName(),
                onEvent = { event ->
                    when (event) {
                        is PairingClient.Event.Connecting ->
                            _outgoingPairingState.value = OutgoingPairingState(event.ip, event.deviceName, "Connecting...")
                        is PairingClient.Event.WaitingForResponse ->
                            _outgoingPairingState.value = OutgoingPairingState(event.ip, event.deviceName, "Waiting for response...")
                        is PairingClient.Event.WaitingForAccept ->
                            _outgoingPairingState.value = OutgoingPairingState(event.ip, event.deviceName, "Waiting for ${event.deviceName} to accept...", event.visualPin)
                        else ->
                            _outgoingPairingState.value = null
                    }
                },
                onPaired = { peerIp, aesKey, peerName ->
                    SecurityVault.saveTrustedNode(peerIp, aesKey)
                    SecurityVault.savePairedDeviceName(peerIp, peerName)
                    refreshTrustedNodes()
                }
            )
        }
    }

    fun unpairDevice(ip: String) {
        scope.launch(Dispatchers.IO) {
            val key = SecurityVault.getAesKey(ip)
            SecurityVault.removeTrustedNode(ip)
            refreshTrustedNodes()
            PairingClient.unpair(ip, key)
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

    /**
     * Offers [files] to [targetIp]. Returns the new outgoing sessionId immediately (the offer
     * handshake runs async) so the caller can jump straight to that record's history detail.
     */
    fun sendFiles(selection: List<File>, targetIp: String): String {
        val sessionId = UUID.randomUUID().toString()
        scope.launch(Dispatchers.IO) {
            // Expand any selected directory into the files it contains (off the UI thread — it walks
            // the tree). Without this a directory File reached the byte pump and FileInputStream(dir)
            // threw, aborting the whole send at 0% and leaving a stuck "resume" (reported bug).
            val files = FileStreamer.expandSelectionToFiles(selection)
            if (files.isEmpty()) {
                // Nothing streamable (e.g. only empty folders were picked). Record a failed attempt
                // so the send isn't silently dropped, then stop.
                val emptyRecord = TransferRecord(
                    sessionId = sessionId, deviceName = targetIp, direction = "outgoing",
                    fileNames = emptyList(), totalBytes = 0L, timestamp = System.currentTimeMillis(),
                    status = "failed", filesCompleted = 0, totalFiles = 0,
                    fileSizes = emptyList(), currentFileOffset = 0L
                )
                _transferHistory.update { listOf(emptyRecord) + it }
                saveHistory()
                return@launch
            }
            val session = OutgoingFileSession(sessionId, targetIp, files)
            activeOutgoingFileSessions[sessionId] = session

            // Record the attempt up-front (mirrors the Android sender) so the history detail the
            // send flow navigates to exists from the moment Send is pressed, not after the
            // handshake. A failed/unacknowledged offer flips it to "paused" below.
            val record = TransferRecord(
                sessionId = sessionId,
                deviceName = targetIp, // shown as-is until discovery supplies a name
                direction = "outgoing",
                fileNames = files.map { it.name },
                totalBytes = session.fileSizes.sum(),
                timestamp = System.currentTimeMillis(),
                status = "pending",
                filesCompleted = 0,
                totalFiles = files.size,
                fileSizes = session.fileSizes,
                currentFileOffset = 0L,
                // Keep the real source paths so history can re-open the original file
                // (and reveal its folder) after the send completes and the session is gone.
                filePaths = files.map { it.absolutePath }
            )
            // Atomic prepend so a concurrent transfer's progress update isn't clobbered.
            _transferHistory.update { listOf(record) + it }
            saveHistory()

            try {
                // Close our side after the handshake. Android will connect back with ACCEPT_OFFER.
                // Use an explicit connect timeout: the Socket(host, port) constructor blocks for
                // the full OS timeout (~minutes) when the target IP is unreachable.
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(targetIp, Ports.FILE_TRANSFER), 3000)
                    socket.soTimeout = 10000
                    val outStream = socket.getOutputStream()
                    val inStream = socket.getInputStream()

                    val deviceName = SecurityVault.getDeviceName()
                    val offerHeader = WireFrames.offerLine(sessionId, deviceName, files.size)
                    // Use the session's OFFER-time length snapshot so the wire MANIFEST and the byte
                    // count streamFilesToSocket commits to are guaranteed identical.
                    val manifestStr = ManifestCodec.encode(
                        files.mapIndexed { idx, f -> f.name to session.fileSizes[idx] }
                    )

                    outStream.write(offerHeader.toByteArray(Charsets.UTF_8))
                    outStream.write(manifestStr.toByteArray(Charsets.UTF_8))
                    outStream.flush()

                    // Wait for the receiver's next line.
                    var response = Net.readLineUtf8(inStream)

                    // AUTH2: a new receiver may challenge us to prove we hold the shared pairing key
                    // before it shows its incoming-file popup. Answer with HMAC-SHA256(pairingKey,
                    // nonce), then read the real OFFER_RECEIVED. An old receiver sends OFFER_RECEIVED
                    // directly and we fall straight through — so we tolerate BOTH sequences without
                    // depending on the peer's advertised caps. Byte-identical to Android's sender path.
                    if (response.startsWith("OFFER_CHALLENGE|")) {
                        val nonce = java.util.Base64.getDecoder().decode(response.removePrefix("OFFER_CHALLENGE|"))
                        val pairingKey = SecurityVault.getAesKey(targetIp)
                            ?: throw Exception("No pairing key to answer OFFER_CHALLENGE for $targetIp")
                        val proof = SessionCrypto.hmacSha256(pairingKey, nonce)
                        outStream.write(WireFrames.offerProofLine(java.util.Base64.getEncoder().encodeToString(proof)).toByteArray(Charsets.UTF_8))
                        outStream.flush()
                        response = Net.readLineUtf8(inStream)
                    }

                    if (response != "OFFER_RECEIVED") {
                        System.err.println("Receiver did not acknowledge offer: $response")
                        // Same as the Android sender's failure path: keep the session alive for a
                        // receiver-initiated reconnect and surface the attempt as paused.
                        updateRecord(sessionId) { it.copy(status = "paused") }
                    }
                }
            } catch (e: Exception) {
                updateRecord(sessionId) { it.copy(status = "paused") }
                e.printStackTrace()
            }
        }
        return sessionId
    }

    // --- Off-grid "send via QR" (Windows = sender) ---

    /** Opens an off-grid QR advertising window; see [QrHostCoordinator.startQrHostSession]. */
    fun startQrHostSession(files: List<File>): QrHostCoordinator.QrHostInfo? = qrHost.startQrHostSession(files)

    /** Re-render the QR after a hotspot credential regeneration; see [QrHostCoordinator.rebuildQrJson]. */
    fun rebuildQrJson(ssid: String, psk: String): String? = qrHost.rebuildQrJson(ssid, psk)

    /** Tear down an unscanned QR window (dialog dismissed); see [QrHostCoordinator.cancelQrHostSession]. */
    fun cancelQrHostSession() = qrHost.cancelQrHostSession()

    /**
     * Inbound off-grid dial from an Android scanner (handed over by the file
     * daemon). Runs the LAN handshake against the pending QR window, then reuses
     * the normal byte-pump to stream the files on the same socket.
     */
    private fun handleP2pHello(socket: Socket, header: String) {
        scope.launch(Dispatchers.IO) {
            // Atomically claim the one-shot window: a second concurrent dial-in gets null → busy.
            val session = qrHost.claimPendingSession()
            if (session == null) {
                OffGridQrSender.writeBusy(socket)
                try { socket.close() } catch (e: Exception) {}
                return@launch
            }
            try {
                socket.soTimeout = 30000
                // ONE length snapshot drives the wire MANIFEST, the streamer's committed byte
                // counts, and the history record — three separate file.length() reads let a file
                // change size between them and desync the receiver's positional read (XP-DEF-04).
                val outSession = OutgoingFileSession(
                    session.sessionId,
                    socket.inetAddress.hostAddress ?: "",
                    session.files,
                    transport = "p2p"
                )
                val handshake = OffGridQrSender.runHandshake(
                    socket, header, session,
                    outSession.files.mapIndexed { i, f -> f.name to outSession.fileSizes[i] }
                )
                if (!handshake.accepted) {
                    // Restore the window (only if no newer QR replaced it) so a genuine retry works.
                    qrHost.restorePendingSession(session)
                    try { socket.close() } catch (e: Exception) {}
                    return@launch
                }
                // Data plane is live — tell the control channel to stop coordinating and show
                // "Transferring". This fires whether the phone reached us via the BLE-driven
                // SOFTAP_JOIN or its own direct join, since the TCP dial is the ground truth.
                qrHost.notifyTransferStarted()

                activeOutgoingFileSessions[session.sessionId] = outSession

                val record = TransferRecord(
                    sessionId = session.sessionId,
                    deviceName = socket.inetAddress.hostAddress ?: "QR",
                    direction = "outgoing",
                    fileNames = session.files.map { it.name },
                    totalBytes = outSession.fileSizes.sum(),
                    timestamp = System.currentTimeMillis(),
                    status = "transferring",
                    filesCompleted = 0,
                    totalFiles = session.files.size,
                    fileSizes = outSession.fileSizes,
                    currentFileOffset = 0L,
                    transport = "p2p"
                )
                // Atomic prepend so a concurrent transfer's progress update isn't clobbered.
                _transferHistory.update { listOf(record) + it }
                saveHistory()

                // Reuse the existing byte-pump: streams in manifest order, then (when the phone
                // advertised the delivery ack) waits for TRANSFER_COMPLETE before finishing.
                streamFilesToSocket(outSession, socket, 0, 0L, handshake.ackExpected)
            } catch (e: Exception) {
                e.printStackTrace()
                activeOutgoingFileSessions.remove(session.sessionId)
                // We claimed the one-shot QR window via claimPendingSession() above; on any
                // failure here restore it (only if no newer QR replaced it) so the phone's
                // retry can dial back in instead of getting P2P_BUSY until the QR is reopened.
                qrHost.restorePendingSession(session)
                try { socket.close() } catch (e2: Exception) {}
            }
        }
    }

    /** A terminal "cancelled" history row for an incoming offer that was refused before any bytes
     *  streamed (e.g. a blocked downgrade throws before the "transferring" record is created), so the
     *  user sees why the offer vanished instead of it disappearing silently. */
    private fun incomingCancelledRecord(session: IncomingFileSession, sessionId: String) = TransferRecord(
        sessionId = sessionId,
        deviceName = session.senderName,
        direction = "incoming",
        fileNames = session.manifest.map { it.first },
        totalBytes = session.manifest.sumOf { it.second },
        timestamp = System.currentTimeMillis(),
        status = "cancelled",
        filesCompleted = 0,
        totalFiles = session.manifest.size,
        fileSizes = session.manifest.map { it.second },
        currentFileOffset = 0L
    )

    fun acceptIncomingFileTransfer(sessionId: String) {
        val session = activeIncomingFileSessions[sessionId] ?: return
        _incomingFileOffer.value = null
        
        // Emit navigation event so FileTransferScreen opens the transfer history detail
        _navigateToTransferSession.value = sessionId
        
        scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                // "fgcm"/"fgcm2" requests an authenticated (FileSeal) bulk stream when the sender
                // advertised FGCM1 and we hold its pairing key; legacy senders ignore it and stream
                // plaintext. "fgcm2" is sent only when the sender ALSO advertises FGCM2 → v2 AEAD.
                val fileKey = fileKeyIfAead(session.senderIp)
                // TOFU-pinned so a later FGCM2-strip forcing v1 is blocked (XP-DEF-01); throws on downgrade.
                val useV2 = useFileV2(session.senderIp, fileKey != null)
                // Explicit connect timeout: the Socket(host, port) constructor blocks for the full OS
                // timeout (~minutes) when the sender is unreachable (mirrors sendFiles).
                socket = Socket().also { it.connect(InetSocketAddress(session.senderIp, Ports.FILE_TRANSFER), 3000) }
                socket.soTimeout = 10000
                val outStream = socket.getOutputStream()
                val acceptCmd = if (fileKey != null) "ACCEPT_OFFER|${sessionId}|${if (useV2) "fgcm2" else "fgcm"}\n" else "ACCEPT_OFFER|${sessionId}\n"
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
                // Atomic prepend so a concurrent transfer's progress update isn't clobbered.
                _transferHistory.update { listOf(record) + it }
                saveHistory()

                receiveFilesFromSocket(session, socket, sessionId, 0, 0L, fileKey, useV2)
            } catch (e: DowngradeBlockedException) {
                // A pinned sender dropped FGCM1/FGCM2 ⇒ downgrade attempt. Terminal (cancelled), and do
                // NOT restore the offer dialog — else Accept loops straight back into the same block.
                // Mirrors the Android DownloadEngine acceptOffer handler (terminal, not retryable).
                System.err.println("RX: ${e.message} — refusing plaintext stream")
                try { socket?.close() } catch (_: Exception) {}
                activeIncomingFileSessions.remove(sessionId)
                if (_transferHistory.value.none { it.sessionId == sessionId }) {
                    _transferHistory.update { listOf(incomingCancelledRecord(session, sessionId)) + it }
                    saveHistory()
                } else {
                    updateRecord(sessionId) { it.copy(status = "cancelled") }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                try { socket?.close() } catch (_: Exception) {}
                // Connect/handshake failed before a record was created. If a history row already
                // exists (failure after receiveFilesFromSocket started) it marks it failed itself;
                // otherwise restore the offer so the UI isn't stuck with the dialog dismissed and
                // no transfer in flight.
                if (_transferHistory.value.none { it.sessionId == sessionId }) {
                    _incomingFileOffer.value = session
                }
            }
        }
    }

    /** Pairing key for [ip] iff a FileSeal stream should be used (peer advertised FGCM1 AND we hold
     *  its key); null ⇒ plaintext. Mirrors Android TransferDaemon.fileKeyIfAead. */
    private fun fileKeyIfAead(ip: String): ByteArray? {
        val key = SecurityVault.getAesKey(ip) ?: return null
        return when (DowngradePolicy.decide(
            peerOffersAead = transferDaemon.getDiscoveredNodes().any { it.ip == ip && it.supportsAuthFile },
            pinned = SecurityVault.isFileGcmPinned(ip)
        )) {
            // TOFU: pin so a later FGCM1-strip on the unauthenticated discovery can't downgrade us.
            DowngradePolicy.Decision.SEAL -> { SecurityVault.pinFileGcm(ip); key }
            DowngradePolicy.Decision.PLAINTEXT -> null
            // Peer dropped FGCM1 after a prior sealed transfer ⇒ downgrade attempt. Fail closed.
            DowngradePolicy.Decision.BLOCK ->
                throw DowngradeBlockedException("FileSeal downgrade blocked for $ip (peer no longer advertises FGCM1)")
        }
    }

    /**
     * Receiver-side FGCM2 (v2 FileSeal) TOFU gate (XP-DEF-01). v2 applies only when the v1 key is
     * available ([fgcm1Available]) AND the peer advertises FGCM2. Once used it is pinned; a later
     * FGCM2-strip that would silently force us back to v1 is a downgrade — throw. Mirrors Android
     * TransferKeys.useFileV2 exactly (same DowngradePolicy decision + pin).
     */
    private fun useFileV2(ip: String, fgcm1Available: Boolean): Boolean {
        val peerOffersV2 = fgcm1Available && transferDaemon.getDiscoveredNodes().any { it.ip == ip && it.supportsAuthFileV2 }
        return when (DowngradePolicy.decide(peerOffersAead = peerOffersV2, pinned = SecurityVault.isFileGcmV2Pinned(ip))) {
            DowngradePolicy.Decision.SEAL -> { SecurityVault.pinFileGcmV2(ip); true }
            DowngradePolicy.Decision.PLAINTEXT -> false
            DowngradePolicy.Decision.BLOCK ->
                throw DowngradeBlockedException("FileSeal v2 downgrade blocked for $ip (peer no longer advertises FGCM2)")
        }
    }

    /**
     * Sender-side AEAD decision with anti-downgrade enforcement (mirrors Android senderFileKey).
     * [fgcm] is true iff the receiver requested a sealed stream. Returns the key to seal with, or null
     * for legitimate plaintext. Throws [DowngradeBlockedException] if the receiver dropped fgcm after a
     * prior sealed transfer.
     */
    private fun senderFileKey(ip: String, fgcm: Boolean): ByteArray? {
        return when (DowngradePolicy.decide(peerOffersAead = fgcm, pinned = SecurityVault.isFileGcmPinned(ip))) {
            // The key can vanish between the trust gate and here (unpair race). Null would read
            // as "legitimate plaintext" and silently stream cleartext to a peer that asked for
            // AEAD — fail closed instead (XP-DEF-02, mirrors Android TransferKeys.senderFileKey).
            DowngradePolicy.Decision.SEAL -> {
                SecurityVault.pinFileGcm(ip)
                SecurityVault.getAesKey(ip)
                    ?: throw DowngradeBlockedException("FileSeal key missing for $ip after SEAL decision — refusing plaintext fallback")
            }
            DowngradePolicy.Decision.PLAINTEXT -> null
            DowngradePolicy.Decision.BLOCK ->
                throw DowngradeBlockedException("FileSeal downgrade blocked (sender) for $ip (receiver dropped fgcm)")
        }
    }

    /**
     * Sender-side FGCM2 (v2 FileSeal) anti-downgrade gate — the send-path mirror of the receiver's
     * [useFileV2] (XP-DEF-01). v1 was already enforced in both directions ([senderFileKey]); v2 was
     * only enforced receiver-side, so a stripped "fgcm2"→"fgcm" ACCEPT/RESUME line silently sealed v1.
     * [fgcm2Requested] is the receiver's on-the-wire flag; a v2-pinned peer that dropped it is a
     * downgrade → throw. Legacy/v1 peers never sent fgcm2 and were never pinned → PLAINTEXT (v1),
     * unchanged. Driven by the ACCEPT flag, not discovery, so it has no empty-cache false-positive.
     */
    private fun senderUseFileV2(ip: String, fgcm2Requested: Boolean): Boolean {
        return when (DowngradePolicy.decide(peerOffersAead = fgcm2Requested, pinned = SecurityVault.isFileGcmV2Pinned(ip))) {
            DowngradePolicy.Decision.SEAL -> { SecurityVault.pinFileGcmV2(ip); true }
            DowngradePolicy.Decision.PLAINTEXT -> false
            DowngradePolicy.Decision.BLOCK ->
                throw DowngradeBlockedException("FileSeal v2 downgrade blocked (sender) for $ip (receiver dropped fgcm2)")
        }
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
                // v2 only when BOTH peers advertise FGCM2; TOFU-pinned so a later FGCM2-strip forcing
                // v1 is blocked (XP-DEF-01); throws on downgrade, caught below.
                val useV2 = useFileV2(session.senderIp, fileKey != null)
                // Explicit connect timeout so an unreachable sender fails fast instead of blocking
                // for the full OS timeout (mirrors sendFiles / acceptIncomingFileTransfer).
                socket = Socket().also { it.connect(InetSocketAddress(session.senderIp, Ports.FILE_TRANSFER), 3000) }
                socket.soTimeout = 10000
                val outStream = socket.getOutputStream()
                val startFileIndex = record.filesCompleted
                var trueOffset = 0L
                if (fileKey == null && startFileIndex < session.manifest.size) {
                    // Plaintext resume recomputes from the .part length. AEAD resume restarts the
                    // in-progress file at chunk 0 (FileSeal records aren't byte-seekable), so offset
                    // stays 0 and the receiver reopens the .part fresh. Use the same de-collided name
                    // receiveToDir writes, so a duplicate-leaf file reads its own .part length.
                    val name = FileStreamer.deCollideNames(session.manifest)[startFileIndex]
                    val saveDir = File(SecurityVault.getSaveLocation())
                    val partFile = File(saveDir, "$name.part")
                    if (partFile.exists()) {
                        trueOffset = partFile.length()
                    }
                }

                val resumeCmd = if (fileKey != null)
                    "RESUME_REQUEST|${sessionId}|${startFileIndex}|${trueOffset}|${if (useV2) "fgcm2" else "fgcm"}\n"
                else "RESUME_REQUEST|${sessionId}|${startFileIndex}|${trueOffset}\n"
                outStream.write(resumeCmd.toByteArray(Charsets.UTF_8))
                outStream.flush()

                receiveFilesFromSocket(session, socket, sessionId, startFileIndex, trueOffset, fileKey, useV2)
            } catch (e: DowngradeBlockedException) {
                // A pinned sender dropped FGCM1/FGCM2 ⇒ downgrade attempt. Mark "cancelled" (terminal),
                // NOT "failed" — the resume gate above only re-runs on status=="failed", so "failed"
                // would let the user retry straight back into the same block. Remove the session so no
                // frame can re-drive it. Mirrors the Android DownloadEngine resume handler.
                System.err.println("RX: ${e.message} — refusing plaintext resume")
                try { socket?.close() } catch (_: Exception) {}
                activeIncomingFileSessions.remove(sessionId)
                updateRecord(sessionId) { it.copy(status = "cancelled") }
            } catch (e: Exception) {
                e.printStackTrace()
                try { socket?.close() } catch (_: Exception) {}
                // updateRecord just does StateFlow.update{}, which is dispatcher-agnostic. The old
                // withContext(Dispatchers.Main) threw IllegalStateException here — :window-ver has no
                // Main dispatcher on its classpath (no coroutines-swing/javafx) — so the first failed
                // resume attempt crashed this coroutine instead of marking the record failed.
                updateRecord(sessionId) { it.copy(status = "failed") }
            }
        }
    }

    private fun receiveFilesFromSocket(session: IncomingFileSession, socket: Socket, sessionId: String, startFileIndex: Int, startOffset: Long, fileKey: ByteArray? = null, useV2: Boolean = false) {
        try {
            _activeIncomingSessions.update { it + sessionId }
            activeFileTransferSockets[sessionId] = socket

            updateRecord(sessionId) { it.copy(status = "transferring") }

            val saveDir = File(SecurityVault.getSaveLocation())

            FileStreamer.receiveToDir(
                input = socket.getInputStream(),
                saveDir = saveDir,
                manifest = session.manifest,
                sessionId = sessionId,
                startFileIndex = startFileIndex,
                startOffset = startOffset,
                fileKey = fileKey,
                useV2 = useV2,
                listener = object : FileStreamer.Listener {
                    override fun onProgress(fileIndex: Int, currentFileOffset: Long) {
                        updateRecord(sessionId, persist = false) { it.copy(currentFileOffset = currentFileOffset) }
                    }
                    override fun onFileComplete(fileIndex: Int) {
                        updateRecord(sessionId) { it.copy(filesCompleted = it.filesCompleted + 1, currentFileOffset = 0L) }
                    }
                }
            )

            updateRecord(sessionId) { it.copy(status = "completed") }
        } catch (e: Exception) {
            e.printStackTrace()
            if (_transferHistory.value.find { it.sessionId == sessionId }?.status != "cancelled") {
                updateRecord(sessionId) { it.copy(status = "failed") }
            }
        } finally {
            _activeIncomingSessions.update { it - sessionId }
            activeFileTransferSockets.remove(sessionId)
            // Drop the retained incoming session so it doesn't leak forever — EXCEPT when the record
            // ended "failed", which offers a resume that needs the session (senderIp/manifest) to
            // reconnect. Completed/cancelled transfers can never resume, so remove them here.
            if (_transferHistory.value.find { it.sessionId == sessionId }?.status != "failed") {
                activeIncomingFileSessions.remove(sessionId)
            }
            try { socket.close() } catch(e:Exception){}
        }
    }

    fun declineIncomingFileTransfer() {
        val session = _incomingFileOffer.value
        _incomingFileOffer.value = null

        // Send DECLINE_OFFER back to the sender so their UI updates from "pending" to "declined"
        if (session != null) {
            // Declined: the offer will never be accepted, so drop the retained session.
            activeIncomingFileSessions.remove(session.sessionId)
            scope.launch(Dispatchers.IO) {
                Net.sendString(session.senderIp, Ports.FILE_TRANSFER, "DECLINE_OFFER|${session.sessionId}\n")
            }
        }
    }

    private fun streamFilesToSocket(session: OutgoingFileSession, socket: Socket, startFileIndex: Int, startOffset: Long, ackExpected: Boolean, fileKey: ByteArray? = null, useV2: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            // When the receiver honours the delivery ack we only declare success once it confirms,
            // and keep the session around on failure so its resume can reconnect to it.
            var keepSession = false
            try {
                activeFileTransferSockets[session.sessionId] = socket

                FileStreamer.sendFromFiles(
                    output = socket.getOutputStream(),
                    files = session.files,
                    fileSizes = session.fileSizes,
                    sessionId = session.sessionId,
                    startFileIndex = startFileIndex,
                    startOffset = startOffset,
                    fileKey = fileKey,
                    useV2 = useV2,
                    listener = object : FileStreamer.Listener {
                        override fun onProgress(fileIndex: Int, currentFileOffset: Long) {
                            updateRecord(session.sessionId, persist = false) { it.copy(currentFileOffset = currentFileOffset) }
                        }
                        override fun onFileComplete(fileIndex: Int) {
                            updateRecord(session.sessionId) {
                                it.copy(
                                    filesCompleted = it.filesCompleted + 1,
                                    // Zero the offset in the SAME update as the filesCompleted bump,
                                    // so the next file's row doesn't briefly render with the previous
                                    // file's stale (large) offset ("numbers jumping").
                                    currentFileOffset = 0L,
                                    // Defer "completed" to the delivery ack when the receiver will send one.
                                    status = if (fileIndex == session.files.size - 1 && !ackExpected) "completed" else "transferring"
                                )
                            }
                        }
                    }
                )

                if (ackExpected) {
                    if (FileStreamer.awaitCompletionAck(socket, session.sessionId)) {
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
                if (_transferHistory.value.find { it.sessionId == session.sessionId }?.status != "cancelled") {
                    // Only the ack path can resume, so only it retains the session for a reconnect.
                    // A non-ack abort (e.g. a source file changed mid-transfer) still surfaces "failed"
                    // instead of leaving the row stuck on "transferring".
                    keepSession = ackExpected
                    updateRecord(session.sessionId) { it.copy(status = "failed") }
                }
            } finally {
                activeFileTransferSockets.remove(session.sessionId)
                try { socket.close() } catch(e:Exception){}
                if (!keepSession) activeOutgoingFileSessions.remove(session.sessionId)
                // Transfer finished — drop the off-grid AP unless a QR is still being shown or the
                // session was kept for a resumable retry (keepSession leaves it in activeOutgoing).
                qrHost.stopHotspotIfIdle()
            }
        }
    }
    
    fun deleteTransferRecord(sessionId: String) {
        // Atomic filter so an in-flight transfer's progress update isn't clobbered.
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
        // Atomic filter so an in-flight transfer's progress update isn't clobbered.
        _transferHistory.update { records -> records.filter { !sessionIds.contains(it.sessionId) } }
        saveHistory()
    }

    /**
     * Finds the record with [sessionId] via a single indexed lookup, applies [transform],
     * reassigns the StateFlow, and persists when [persist]. No-op if the record is absent.
     */
    private fun updateRecord(sessionId: String, persist: Boolean = true, transform: (TransferRecord) -> TransferRecord) {
        // Atomic read-modify-write. Multiple in-flight transfers each call updateRecord
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

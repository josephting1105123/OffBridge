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
import com.joseph.clipboard.windows.network.QrPairCoordinator
import com.joseph.clipboard.windows.network.QrPairHost
import com.joseph.clipboard.windows.network.WireFrames
import com.joseph.clipboard.windows.network.control.SessionCrypto
import com.joseph.clipboard.windows.network.ClipboardTargeting
import com.joseph.clipboard.windows.network.PairingClient
import com.joseph.clipboard.windows.network.PairingDaemon
import com.joseph.clipboard.windows.network.Ports
import com.joseph.clipboard.windows.network.TransferDaemon
import com.joseph.clipboard.windows.os.ClipboardManager
import com.joseph.clipboard.windows.os.SystemTrayManager
import com.joseph.clipboard.windows.update.AppVersion
import com.joseph.clipboard.windows.update.UpdateChecker
import com.joseph.clipboard.windows.update.UpdateInstaller
import com.joseph.clipboard.windows.update.UpdateStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.awt.TrayIcon
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

/** Minimum gap between passive (startup) update checks — the Updates page's manual check ignores this. */
private const val UPDATE_CHECK_COOLDOWN_MS = 24L * 60 * 60 * 1000

/**
 * Hard wall-clock ceiling on [ClipboardAppController.checkForUpdates] — detection must resolve
 * within 5s, one way or the other. [UpdateChecker]'s own connect (2s) / request (4s) timeouts bound
 * the HttpClient's socket phases and normally fire first (their errors are more specific); this
 * backstop sits just above them to catch the hang vectors they can't (e.g. OS-level DNS resolution
 * or Windows proxy auto-detection stalling before a connection attempt even starts) — without it
 * the Updates page's "Checking for updates…" loader can spin forever.
 */
private const val UPDATE_CHECK_TIMEOUT_MS = 5_000L

// Auto-resume backoff for a dropped incoming transfer (mirrors Android TransferDaemon:
// MAX_AUTO_RESUME_ATTEMPTS / AUTO_RESUME_BASE_DELAY_MS exactly — same cap, same linear
// 1.5s/3s/4.5s/6s backoff). See ClipboardAppController.scheduleAutoResume.
private const val MAX_AUTO_RESUME_ATTEMPTS = 4
private const val AUTO_RESUME_BASE_DELAY_MS = 1500L

// Wire constant: the exact bytes an AUTH2R challenge line opens with. Matched byte-by-byte by
// ClipboardAppController.answerAcceptChallengeIfExpected, so it must stay identical to the Android
// client's DownloadEngine spelling — see Auth2VectorTest, which pins it on both platforms.
private val ACCEPT_CHALLENGE_PREFIX = "ACCEPT_CHALLENGE|".toByteArray(Charsets.UTF_8)

// Bound on the base64 nonce following the prefix (16 raw bytes ⇒ 24 chars). Generous, but bounded
// so a peer that sends a matching prefix and then streams forever can't grow the buffer without limit.
private const val ACCEPT_NONCE_MAX_B64_BYTES = 128

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
    val manifest: List<Pair<String, Long>>,
    // Auto-resume backoff state (see ClipboardAppController.scheduleAutoResume), mutated in place on
    // this retained instance across attempts — mirrors Android TransferSession.autoResumeAttempts /
    // lastResumeOffset. -1 sentinels ensure the very first failure always counts as "no progress yet"
    // rather than a false match against the record's initial filesCompleted=0/currentFileOffset=0.
    var autoResumeAttempts: Int = 0,
    var lastResumeFilesCompleted: Int = -1,
    var lastResumeOffset: Long = -1L,
    // Atomic claim guarding resumeIncomingFileTransfer against a concurrent second attempt for the
    // SAME session. record.status doesn't flip "failed" -> "transferring" until deep inside
    // receiveFilesFromSocket (after a multi-second connect + AUTH2R challenge sequence), so a manual
    // Resume click racing the auto-retry timer (or two rapid clicks) could otherwise both pass the
    // plain status == "failed" check and open two sockets writing the SAME .part file concurrently —
    // exactly the corruption class the cross-session name-reservation fix exists to prevent, just
    // triggered by two attempts on ONE session instead of two sessions sharing a name.
    val resumeInFlight: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
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
        val onDecline: () -> Unit,
        // Wall-clock deadline mirroring PairingDaemon's own latch.await window (PAIR_CONFIRM_WINDOW_MS):
        // the daemon's parked socket gives up and sends PAIR_DECLINE on its own after this. onAccept
        // checks it before saving so a stale click (dialog left open past the window) surfaces an
        // error instead of silently completing pairing on only this side.
        val expiresAt: Long = 0L,
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
        deviceNameProvider = { SecurityVault.getDeviceName() },
        // Android's outgoing-transfer "Resume" sends this UDP nudge asking US (the receiver) to
        // reconnect; previously unhandled here, so an Android→Windows resume looked "sent" on the
        // Android side but silently vanished. See requestPeerResumeOutgoing for the mirror (Windows
        // sending the same nudge when Windows is the outgoing side).
        onResumeRequested = { _, _ ->
            SystemTrayManager.showNotification(
                "Resume Request",
                "A peer is requesting to resume a transfer. Open Clipboard Share to continue."
            )
        }
    )
    
    // File Transfer State
    // Concurrent: written/removed from per-connection handler coroutines (QR + normal sends).
    private val activeOutgoingFileSessions = java.util.concurrent.ConcurrentHashMap<String, OutgoingFileSession>()
    // Concurrent: written from FileTransferDaemon's per-connection handler (onOfferReceived) and
    // read/removed from receive coroutines — must not be an unsynchronized plain map.
    private val activeIncomingFileSessions = java.util.concurrent.ConcurrentHashMap<String, IncomingFileSession>()
    // sessionId -> the on-disk leaf name resolved for each manifest entry, computed ONCE (see
    // resolveIncomingNames) and reused unchanged for the life of the session, including every
    // resume — recomputing per-call would let the cross-session reservation drift between accept
    // and resume (a sibling session active at accept time may have finished by resume time),
    // making a resume address a DIFFERENT .part than the one it originally wrote. Removed in step
    // with activeIncomingFileSessions (see receiveFilesFromSocket's finally block).
    private val resolvedIncomingNames = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    // Off-grid "send via QR" advertising window (Windows = sender). All QR/hotspot/BLE-control state
    // lives in the coordinator; it asks isTransferActive() so it never drops the AP mid-stream.
    private val qrHost = QrHostCoordinator(
        isTransferActive = { activeOutgoingFileSessions.isNotEmpty() },
        isPairingWindowOpen = { QrPairHost.isOpen(System.currentTimeMillis()) },
    )
    // "Pair with QR" window. Separate from qrHost (that one advertises files); this one only ever
    // hands out a pairing credential, and defers to qrHost for the shared AP's lifetime.
    private val qrPair = QrPairCoordinator(releaseHotspot = { qrHost.stopHotspotIfIdle() })
    // Name of the device that just paired by scanning our code — the QR dialog turns this into its
    // success state. Declared ahead of pairingDaemon so the daemon's callback never sees it unset.
    private val _qrPairedDevice = MutableStateFlow<String?>(null)
    val qrPairedDevice: StateFlow<String?> = _qrPairedDevice.asStateFlow()
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

            val expiresAt = System.currentTimeMillis() + PairingDaemon.PAIR_CONFIRM_WINDOW_MS
            _incomingPairingRequest.value = IncomingPairingData(ip, name, pin, {
                // Stale click guard (mirrors Android's AND-36 fix): past this deadline the daemon's
                // parked socket already gave up and sent PAIR_DECLINE on its own — accept() here would
                // be a no-op countDown on an already-released latch, and saving the key would trust a
                // peer that saw DECLINE, not ACCEPT (paired on only one side). Refuse and tell the user
                // instead of silently closing the dialog as if pairing had actually succeeded.
                if (System.currentTimeMillis() > expiresAt) {
                    _incomingPairingRequest.value = null
                    SystemTrayManager.showNotification("Pairing Request Expired", "Ask $name to try pairing again.", TrayIcon.MessageType.WARNING)
                } else {
                    accept()
                    _incomingPairingRequest.value = null
                    scope.launch {
                        delay(500) // Wait for PairingDaemon to finish saving the key
                        refreshTrustedNodes()
                    }
                }
            }, {
                decline()
                _incomingPairingRequest.value = null
            }, expiresAt)
            onRequestForeground?.invoke()
        },
        onUnpaired = { senderIp ->
            println("Remote device $senderIp unpaired us — refreshing trusted nodes")
            refreshTrustedNodes()
        },
        onQrPaired = { senderIp, senderName ->
            // The daemon has already stored the key by the time this fires (it only fires after the
            // scanner's confirmation verified), so the list can be refreshed straight away.
            refreshTrustedNodes()
            _qrPairedDevice.value = senderName.ifBlank { senderIp }
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
            // A peer CANCEL can arrive while we're between resume attempts (mid-backoff, no live
            // receiveFilesFromSocket coroutine to hit its own finally-block cleanup) — without this,
            // resolveIncomingNames' cache for this sessionId leaks forever and needlessly reserves
            // its names against any future session that happens to share a filename.
            resolvedIncomingNames.remove(sessionId)
            try { activeFileTransferSockets.remove(sessionId)?.close() } catch (e: Exception) {}
        },
        onP2pHelloReceived = { socket, header -> handleP2pHello(socket, header) },
        // Discovery (and the AUTH2 capability flag) lives in the separate transferDaemon; surface it so
        // the OFFER branch can decide whether to issue the AUTH2 admission challenge. Mirrors how the
        // FileSeal-v2 send path reads supportsAuthFileV2 off the same discovered-node set.
        supportsAuth2Provider = { ip -> transferDaemon.getDiscoveredNodes().any { it.ip == ip && it.supportsAuth2 } },
        supportsAcceptAuth2Provider = { ip -> transferDaemon.getDiscoveredNodes().any { it.ip == ip && it.supportsAuth2Accept } },
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

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

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
            pushClipboardToTrustedNodes(text, notify = true)
        }
    }

    /**
     * Tray feedback for a MANUAL push (tray "Push Clipboard" / the History screen button): an
     * explicit user action that previously gave zero success/failure feedback, so a failed send
     * looked identical to a successful one. Mirrors Android's showSuccessNotification /
     * toast_push_failed_no_devices.
     *
     * Only ever called with notify=true from [pushCurrentClipboard]. The automatic clipboard-change
     * path ([start]'s ClipboardManager listener) shares the same send function but must stay silent
     * — it fires on EVERY copy the user makes, so notifying there would balloon a toast per copy,
     * and a WARNING toast per copy whenever no paired device happens to be online.
     */
    private fun notifyPushResult(successCount: Int, totalCount: Int) {
        if (totalCount == 0) {
            SystemTrayManager.showNotification("Clipboard Share", "Push failed: no paired devices online.", TrayIcon.MessageType.WARNING)
        } else if (successCount > 0) {
            SystemTrayManager.showNotification("Clipboard Share", "Clipboard pushed to $successCount device${if (successCount == 1) "" else "s"}.")
        } else {
            SystemTrayManager.showNotification("Clipboard Share", "Clipboard push failed — no device reachable.", TrayIcon.MessageType.WARNING)
        }
    }

    /**
     * Auto-push: send the clipboard to every paired device that is reachable right now, following a
     * device that changed DHCP lease to its new address by the stable id it advertises in discovery
     * (see [ClipboardTargeting]). The key and TOFU pin stay under the pairing address [keyIp] even
     * when the bytes go to a new [targetIp]. A send that gets no ack is always logged; [notify]
     * additionally surfaces the outcome as a tray notification, and is set ONLY by the manual
     * [pushCurrentClipboard] — see [notifyPushResult] for why the automatic path must stay silent.
     */
    private fun pushClipboardToTrustedNodes(text: String, notify: Boolean = false) {
        val trusted = trustedNodes.value.map { ClipboardTargeting.Trusted(it, SecurityVault.getPairedDeviceId(it)) }
        val live = transferDaemon.getDiscoveredNodes().map { ClipboardTargeting.Live(it.ip, it.deviceId) }
        val targets = ClipboardTargeting.resolve(trusted, live)
        if (targets.isEmpty()) {
            if (notify) notifyPushResult(successCount = 0, totalCount = 0)
            return
        }
        scope.launch {
            val results = targets.map { (keyIp, targetIp) ->
                async {
                    try {
                        val aesKey = SecurityVault.getAesKey(keyIp) ?: return@async false
                        val sealed = sealClipboardForPeer(keyIp, targetIp, aesKey, text) ?: return@async false
                        // Append newline in case receiver uses readLine()
                        val sent = Net.sendString(targetIp, Ports.CLIPBOARD, sealed + "\n")
                        if (!sent) System.err.println("Clipboard auto-push to $targetIp (paired as $keyIp) failed to send")
                        sent
                    } catch (e: Exception) {
                        System.err.println("Failed to auto-push clipboard to $targetIp: ${e.message}")
                        false
                    }
                }
            }.awaitAll()
            if (notify) notifyPushResult(successCount = results.count { it }, totalCount = targets.size)
        }
    }

    /** Manual send to explicitly chosen live addresses (the send dialog). The chosen ip is both the
     *  key source and the destination — the user picked a device currently on the list. */
    fun pushClipboardToSpecificNodes(text: String, ips: List<String>) {
        if (ips.isEmpty()) return
        scope.launch {
            val results = ips.map { ip ->
                async {
                    try {
                        val aesKey = SecurityVault.getAesKey(ip) ?: return@async false
                        val encryptedBase64 = sealClipboardForPeer(ip, ip, aesKey, text) ?: return@async false
                        // Append newline in case receiver uses readLine()
                        Net.sendString(ip, Ports.CLIPBOARD, encryptedBase64 + "\n")
                    } catch (e: Exception) {
                        System.err.println("Failed to push clipboard to $ip: ${e.message}")
                        false
                    }
                }
            }.awaitAll()
            notifyPushResult(successCount = results.count { it }, totalCount = ips.size)
        }
    }

    /**
     * Seal a clipboard payload for one peer with anti-downgrade enforcement (mirrors Android
     * sealClipboardForPeer). Every peer gets an authenticated GCM seal — CBC was retired in 3.5.0,
     * so a peer that never advertised GCM1 must update to open it. Once a peer has used GCM we pin
     * it, and a later loss of GCM1 (a capability-strip on the unauthenticated discovery) makes us
     * skip the peer (return null) rather than send to a suspect channel. Fail closed.
     */
    private fun sealClipboardForPeer(keyIp: String, targetIp: String, aesKey: ByteArray, plain: String): String? {
        // Capability is read from the device we're actually sending to (it may have moved), but the
        // anti-downgrade pin stays keyed to the pairing address so a moved device can't reset its own
        // TOFU state by advertising from a fresh IP.
        val supportsAuth = transferDaemon.getDiscoveredNodes().any { it.ip == targetIp && it.supportsAuthClipboard }
        return when (DowngradePolicy.decide(supportsAuth, SecurityVault.isClipboardGcmPinned(keyIp))) {
            DowngradePolicy.Decision.SEAL -> {
                SecurityVault.pinClipboardGcm(keyIp)
                CryptoEngine.encryptStringAuthenticated(plain, aesKey)
            }
            // CBC retired in 3.5.0: a peer not (yet) advertising GCM1 still gets an authenticated GCM
            // seal (it must update to open it) instead of the malleable unauthenticated CBC path.
            DowngradePolicy.Decision.PLAINTEXT -> CryptoEngine.encryptStringAuthenticated(plain, aesKey)
            DowngradePolicy.Decision.BLOCK -> {
                System.err.println("Clipboard GCM downgrade blocked for $keyIp — skipping peer")
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

    // --- Update check / install ---

    /**
     * Checks GitHub for a newer Windows release. [force] = true (Updates page opened) always hits
     * the network and updates [updateStatus]. [force] = false (passive startup check) additionally
     * respects [SecurityVault.isAutoUpdateCheckEnabled] and a cooldown, so it silently no-ops most
     * launches — this path only drives the sidebar badge, never a popup.
     */
    fun checkForUpdates(force: Boolean) {
        if (!force) {
            if (!SecurityVault.isAutoUpdateCheckEnabled()) return
            val elapsed = System.currentTimeMillis() - SecurityVault.getLastUpdateCheckEpochMillis()
            if (elapsed < UPDATE_CHECK_COOLDOWN_MS) return
        }
        scope.launch {
            _updateStatus.value = UpdateStatus.Checking
            // UpdateChecker.check() is a blocking call. Run it on its own IO thread and race it
            // against UPDATE_CHECK_TIMEOUT_MS instead of just awaiting it inline — a hang inside the
            // blocking call can't be interrupted by cancellation, but this coroutine can still give
            // up on it and unstick the UI; the abandoned thread dies on its own once the OS-level
            // call eventually returns.
            val pending = async(Dispatchers.IO) { UpdateChecker.check(AppVersion.VERSION) }
            val result = withTimeoutOrNull(UPDATE_CHECK_TIMEOUT_MS) { pending.await() }
                ?: UpdateStatus.CheckFailed("Update check timed out")
            _updateStatus.value = result
            SecurityVault.setLastUpdateCheckEpochMillis(System.currentTimeMillis())
        }
    }

    /**
     * Downloads and launches the pending update (must be called only while [updateStatus] is
     * [UpdateStatus.UpdateAvailable] — the Updates page gates the button on that state). Exits the
     * app on success; on failure, surfaces [UpdateStatus.CheckFailed] instead of crashing so the
     * user sees why and can retry.
     */
    fun downloadAndInstallUpdate() {
        val pending = _updateStatus.value as? UpdateStatus.UpdateAvailable ?: return
        // compareAndSet (not a plain read-then-set) atomically claims the transition: the read of
        // _updateStatus.value above happens on the caller's thread, but the actual state flip only
        // happens once this coroutine runs — a rapid double-click on "Continue" could otherwise pass
        // the guard twice and launch two concurrent downloads that truncate-write the same temp
        // file. If another caller already claimed it, this one backs out instead of racing it.
        scope.launch {
            if (!_updateStatus.compareAndSet(pending, UpdateStatus.Downloading)) return@launch
            try {
                val installer = UpdateInstaller.download(pending.downloadUrl, pending.version)
                UpdateInstaller.launchAndExit(installer) // does not return on success
            } catch (e: Exception) {
                e.printStackTrace()
                _updateStatus.value = UpdateStatus.CheckFailed(e.message ?: e.javaClass.simpleName)
            }
        }
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
                onPaired = { peerIp, aesKey, peerName, peerDeviceId ->
                    SecurityVault.saveTrustedNode(peerIp, aesKey, peerDeviceId, peerName)
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
        // Close the handshake socket so the blocking read unwinds and can't finish pairing in the
        // background; nulling the state alone would only hide the overlay while the pair completed.
        PairingClient.cancel()
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

    // --- "Pair with QR" (Windows shows the code; the phone scans it) ---

    /** Opens a QR pairing window; see [QrPairCoordinator.open]. Blocks when it raises the AP. */
    fun startQrPairSession(forceHotspot: Boolean = false): QrPairCoordinator.PairQrInfo? {
        _qrPairedDevice.value = null
        return qrPair.open(forceHotspot)
    }

    /** Closes the pairing window (dialog dismissed or pairing finished). */
    fun cancelQrPairSession() {
        qrPair.close()
        _qrPairedDevice.value = null
    }

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
                // A non-null sealKey means the phone asked for the sealed off-grid stream, so the
                // same FileSeal v2 path the LAN transfers use runs here too (useV2 is implied — the
                // off-grid seal has no v1 form, it was introduced already at v2).
                streamFilesToSocket(
                    outSession, socket, 0, 0L, handshake.ackExpected,
                    fileKey = handshake.sealKey,
                    useV2 = handshake.sealKey != null,
                )
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
                // Small pushback buffer: lets answerAcceptChallengeIfExpected speculatively peek a
                // short ACCEPT_CHALLENGE line and losslessly restore any consumed bytes if the sender
                // decided not to challenge this time — see that function's doc.
                val pushbackInput = java.io.PushbackInputStream(socket.getInputStream(), 512)
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

                answerAcceptChallengeIfExpected(pushbackInput, outStream, session.senderIp)
                receiveFilesFromSocket(session, socket, sessionId, 0, 0L, fileKey, useV2, pushbackInput)
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

    /**
     * Windows' outgoing-transfer "Resume" button was previously a dead no-op — correct that the
     * actual reconnect must come from the receiver, but wrong that Windows did nothing at all.
     * Sends the same FILEBRIDGE_RESUME_REQUEST nudge Android's outgoing resume sends, prompting
     * the receiver (Android or Windows) to reconnect and drive the real TCP RESUME_REQUEST.
     */
    fun requestPeerResumeOutgoing(sessionId: String) {
        val targetIp = activeOutgoingFileSessions[sessionId]?.targetIp
        if (targetIp == null) {
            SystemTrayManager.showNotification("Clipboard Share", "Can't resume: the receiving device is no longer known.", TrayIcon.MessageType.WARNING)
            return
        }
        scope.launch {
            val sent = Net.sendUdp(targetIp, Ports.DISCOVERY, "FILEBRIDGE_RESUME_REQUEST|$sessionId")
            SystemTrayManager.showNotification(
                "Clipboard Share",
                if (sent) "Resume request sent." else "Failed to send resume request.",
                if (sent) TrayIcon.MessageType.INFO else TrayIcon.MessageType.WARNING
            )
        }
    }

    fun resumeIncomingFileTransfer(sessionId: String) {
        val session = activeIncomingFileSessions[sessionId] ?: return
        val records = _transferHistory.value
        val record = records.find { it.sessionId == sessionId } ?: return
        if (record.status != "failed") return
        // The real synchronization point — see IncomingFileSession.resumeInFlight's doc. The
        // status == "failed" check above is a UI-facing hint, not a lock: it stays "failed" for the
        // whole connect+challenge window, so without this CAS a second concurrent call (manual
        // Resume racing the auto-retry timer) would also pass it.
        if (!session.resumeInFlight.compareAndSet(false, true)) return

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
                // Small pushback buffer: lets answerAcceptChallengeIfExpected speculatively peek a
                // short ACCEPT_CHALLENGE line and losslessly restore any consumed bytes if the sender
                // decided not to challenge this time — see that function's doc.
                val pushbackInput = java.io.PushbackInputStream(socket.getInputStream(), 512)
                val outStream = socket.getOutputStream()
                val startFileIndex = record.filesCompleted
                var trueOffset = 0L
                if (fileKey == null && startFileIndex < session.manifest.size) {
                    // Plaintext resume recomputes from the .part length. AEAD resume restarts the
                    // in-progress file at chunk 0 (FileSeal records aren't byte-seekable), so offset
                    // stays 0 and the receiver reopens the .part fresh. Read from the SAME cached,
                    // cross-session-resolved name receiveToDir actually wrote to (resolveIncomingNames
                    // is idempotent — this session's names were already resolved at accept time, so
                    // this just returns the cached list) — a raw recompute here could pick a different
                    // name than the original accept did if another session's reservation changed.
                    val saveDir = File(SecurityVault.getSaveLocation())
                    val name = resolveIncomingNames(sessionId, session, saveDir)[startFileIndex]
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

                answerAcceptChallengeIfExpected(pushbackInput, outStream, session.senderIp)
                receiveFilesFromSocket(session, socket, sessionId, startFileIndex, trueOffset, fileKey, useV2, pushbackInput)
            } catch (e: DowngradeBlockedException) {
                // A pinned sender dropped FGCM1/FGCM2 ⇒ downgrade attempt. Mark "cancelled" (terminal),
                // NOT "failed" — the resume gate above only re-runs on status=="failed", so "failed"
                // would let the user retry straight back into the same block. Remove the session so no
                // frame can re-drive it. Mirrors the Android DownloadEngine resume handler.
                System.err.println("RX: ${e.message} — refusing plaintext resume")
                try { socket?.close() } catch (_: Exception) {}
                activeIncomingFileSessions.remove(sessionId)
                // This session may have been resolved during an earlier accept/resume cycle — drop
                // that cache entry too, or it leaks forever (this is a terminal state; the session
                // can never resume again to reuse it).
                resolvedIncomingNames.remove(sessionId)
                updateRecord(sessionId) { it.copy(status = "cancelled") }
            } catch (e: Exception) {
                e.printStackTrace()
                try { socket?.close() } catch (_: Exception) {}
                // updateRecord just does StateFlow.update{}, which is dispatcher-agnostic. The old
                // withContext(Dispatchers.Main) threw IllegalStateException here — :window-ver has no
                // Main dispatcher on its classpath (no coroutines-swing/javafx) — so the first failed
                // resume attempt crashed this coroutine instead of marking the record failed.
                updateRecord(sessionId) { it.copy(status = "failed") }
                scheduleAutoResume(sessionId, session)
            } finally {
                // Release the claim on every exit — success, either catch, or (via receiveFilesFromSocket
                // returning normally after handling its own internal failure) a completed attempt that
                // already scheduled its own retry. scheduleAutoResume's delay (>=1.5s) is released well
                // before its own timer fires, so this can't race the retry it just scheduled.
                session.resumeInFlight.set(false)
            }
        }
    }

    /**
     * A dropped incoming transfer auto-reconnects via [resumeIncomingFileTransfer] — bounded and
     * linearly backed off (1.5s/3s/4.5s/6s) so transient blips self-heal without a click, while a
     * genuinely dead route still degrades to the manual Resume/paused button. Mirrors Android
     * TransferDaemon.scheduleAutoResume: same attempt cap, same backoff, same
     * progress-since-last-attempt reset (forgives prior failures once bytes are moving again).
     * Called from both a failed reconnect attempt ([resumeIncomingFileTransfer]'s own catch) and a
     * mid-stream drop ([receiveFilesFromSocket]'s catch) so the retry chain covers either failure
     * point, same as Android's three call sites.
     */
    private fun scheduleAutoResume(sessionId: String, session: IncomingFileSession) {
        val record = _transferHistory.value.find { it.sessionId == sessionId } ?: return
        val progressed = record.filesCompleted > session.lastResumeFilesCompleted ||
            (record.filesCompleted == session.lastResumeFilesCompleted && record.currentFileOffset > session.lastResumeOffset)
        if (progressed) session.autoResumeAttempts = 0
        session.lastResumeFilesCompleted = record.filesCompleted
        session.lastResumeOffset = record.currentFileOffset
        if (session.autoResumeAttempts >= MAX_AUTO_RESUME_ATTEMPTS) return
        session.autoResumeAttempts++
        val delayMs = AUTO_RESUME_BASE_DELAY_MS * session.autoResumeAttempts
        scope.launch {
            delay(delayMs)
            resumeIncomingFileTransfer(sessionId)
        }
    }

    /**
     * Final on-disk leaf name for every entry in [session]'s manifest, resolved once and cached
     * under [sessionId] so accept and every subsequent resume reuse the identical assignment (see
     * [resolvedIncomingNames] and [FileStreamer.receiveToDir]'s `names` contract). Reserves against:
     *  - names any OTHER currently-active incoming session has already resolved, so two concurrent
     *    same-name transfers land on different files instead of racing on one shared ".part";
     *  - any "<name>.part" sidecar already on disk, closing the same-instant race where a sibling
     *    session claimed a name microseconds ago and hasn't reached [resolvedIncomingNames] yet.
     * computeIfAbsent makes the "resolve once" itself atomic under concurrent accepts; only reading
     * (never writing) other entries of the same map from inside the computation is safe under
     * ConcurrentHashMap's reentrancy rules.
     */
    private fun resolveIncomingNames(sessionId: String, session: IncomingFileSession, saveDir: File): List<String> =
        resolvedIncomingNames.computeIfAbsent(sessionId) {
            val reserved = HashSet<String>()
            for ((otherId, otherNames) in resolvedIncomingNames) {
                if (otherId != sessionId) reserved.addAll(otherNames)
            }
            try {
                saveDir.list()?.forEach { entry ->
                    if (entry.endsWith(".part")) reserved.add(entry.removeSuffix(".part"))
                }
            } catch (_: Exception) {}
            FileStreamer.deCollideNames(session.manifest, reserved)
        }

    /**
     * Accept/resume-direction AUTH2R challenge, requester side (mirrors
     * FileTransferDaemon.performAcceptAuth2Challenge, the responder side). ALWAYS speculatively
     * peeks up to one short line off [pushback] for an ACCEPT_CHALLENGE and, if present, answers it
     * with an HMAC proof of the shared pairing key — proving to the sender that WE hold the key
     * before it starts streaming bytes to us.
     *
     * Deliberately unconditional (no discovery-capability gate): the sender's decision to challenge
     * is driven by ITS OWN belief about US (does IT see us as AUTH2R-capable), which is a DIFFERENT
     * fact from whether WE believe the sender supports AUTH2R — the two beliefs are independently
     * derived from passively-received, lossy UDP discovery caches and can disagree, especially right
     * after a network blip (exactly when auto-resume fires). An earlier version gated this peek on
     * our own belief about the sender; when that belief was stale/false while the sender's belief
     * about US was fresh/true, the sender sent a real challenge that this function never looked for,
     * and the raw "ACCEPT_CHALLENGE|..." bytes got fed to FileStreamer.receiveToDir as if they were
     * file content — corrupting the start of the file. Peeking unconditionally costs one bounded,
     * non-blocking-beyond-soTimeout buffer scan (a few method calls, not a network round trip) and
     * removes the race entirely: the requester no longer needs to correctly guess anything.
     *
     * Safety: [pushback] must be the SAME PushbackInputStream later passed as receiveFilesFromSocket's
     * `input` — see that call site. If the sender decided NOT to challenge this time (its own
     * DowngradePolicy computed PLAINTEXT, or it's an old build that never understood
     * ACCEPT_AUTH_CAP at all), whatever this read consumed is losslessly pushed back onto that same
     * stream — FileStreamer.receiveToDir then reads the file's original, untouched bytes from the
     * top. This holds regardless of capability beliefs on either side.
     *
     * The probe matches [ACCEPT_CHALLENGE_PREFIX] one byte at a time and gives up at the FIRST
     * differing byte, rather than reading ahead to a newline. That distinction is load-bearing, not
     * a micro-optimisation: a newline-seeking probe has to keep reading until the file's own bytes
     * happen to contain a 0x0A, so against a non-challenging sender (every already-shipped
     * 3.7.1/3.7.4 peer) a transfer whose ENTIRE byte stream is shorter than the scan bound and
     * contains no newline — a short no-trailing-newline note, a tiny binary, a small sealed file
     * whose ciphertext happens to lack 0x0A — would block until the socket's own soTimeout and fail
     * the transfer outright. Matching the prefix instead reads exactly one byte in the overwhelming
     * common case (a file almost never starts with 'A'), and at most [ACCEPT_CHALLENGE_PREFIX].size.
     */
    private fun answerAcceptChallengeIfExpected(pushback: java.io.PushbackInputStream, output: java.io.OutputStream, targetIp: String) {
        val consumed = java.io.ByteArrayOutputStream()
        var isChallenge = true
        try {
            for (expected in ACCEPT_CHALLENGE_PREFIX) {
                val b = pushback.read()
                if (b == -1) { isChallenge = false; break }
                consumed.write(b)
                if (b.toByte() != expected) { isChallenge = false; break }
            }
        } catch (e: java.net.SocketTimeoutException) {
            // The peer sent nothing at all within the socket timeout. Either a zero-byte payload
            // from a non-challenging sender, or a link that just died — both are the stream path's
            // problem to report, not this probe's. Restore and hand over. A read timeout leaves the
            // socket usable, and the timed-out read itself consumed nothing.
            isChallenge = false
        }
        if (!isChallenge) {
            // Not a challenge — restore every byte this probe consumed, in original order, so
            // receiveToDir reads the file's exact original bytes from the top.
            val bytes = consumed.toByteArray()
            if (bytes.isNotEmpty()) pushback.unread(bytes)
            return
        }
        // The full prefix matched, so a challenge really is in flight and the rest of the line is
        // owed to us — read it to the newline and fail the transfer if it never arrives, rather than
        // restoring a half-consumed challenge into the file stream.
        val nonceB64 = java.io.ByteArrayOutputStream()
        while (nonceB64.size() < ACCEPT_NONCE_MAX_B64_BYTES) {
            val b = pushback.read()
            if (b == -1 || b == '\n'.code) break
            nonceB64.write(b)
        }
        val nonce = try {
            java.util.Base64.getDecoder().decode(nonceB64.toString(Charsets.UTF_8.name()))
        } catch (e: Exception) {
            throw Exception("Malformed ACCEPT_CHALLENGE from $targetIp")
        }
        val pairingKey = SecurityVault.getAesKey(targetIp)
            ?: throw Exception("No pairing key to answer ACCEPT_CHALLENGE for $targetIp")
        val proof = SessionCrypto.hmacSha256(pairingKey, nonce)
        output.write("ACCEPT_PROOF|${java.util.Base64.getEncoder().encodeToString(proof)}\n".toByteArray(Charsets.UTF_8))
        output.flush()
        SecurityVault.pinAuth2Accept(targetIp)
    }

    private fun receiveFilesFromSocket(session: IncomingFileSession, socket: Socket, sessionId: String, startFileIndex: Int, startOffset: Long, fileKey: ByteArray? = null, useV2: Boolean = false, input: java.io.InputStream = socket.getInputStream()) {
        try {
            _activeIncomingSessions.update { it + sessionId }
            activeFileTransferSockets[sessionId] = socket

            updateRecord(sessionId) { it.copy(status = "transferring") }

            val saveDir = File(SecurityVault.getSaveLocation())
            val names = resolveIncomingNames(sessionId, session, saveDir)

            FileStreamer.receiveToDir(
                input = input,
                saveDir = saveDir,
                manifest = session.manifest,
                sessionId = sessionId,
                startFileIndex = startFileIndex,
                startOffset = startOffset,
                fileKey = fileKey,
                useV2 = useV2,
                names = names,
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
                scheduleAutoResume(sessionId, session)
            }
        } finally {
            _activeIncomingSessions.update { it - sessionId }
            activeFileTransferSockets.remove(sessionId)
            // Drop the retained incoming session so it doesn't leak forever — EXCEPT when the record
            // ended "failed", which offers a resume that needs the session (senderIp/manifest) to
            // reconnect. Completed/cancelled transfers can never resume, so remove them here.
            if (_transferHistory.value.find { it.sessionId == sessionId }?.status != "failed") {
                activeIncomingFileSessions.remove(sessionId)
                resolvedIncomingNames.remove(sessionId)
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

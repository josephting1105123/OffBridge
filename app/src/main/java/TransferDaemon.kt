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

    // A failed child (uncaught exception) must not tear down its siblings — one dead discovery/
    // transfer coroutine should never silently kill the whole daemon. SupervisorJob isolates
    // failures; the handler logs the escape so it isn't swallowed without trace.
    private val daemonExceptionHandler = CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) {
            e.printStackTrace()
            try { TransportDiag.log("Daemon coroutine crashed: ${e.javaClass.simpleName} ${e.message ?: ""}") } catch (_: Exception) {}
        }
    }

    // var (not val) so [stop] can cancel the scope and a later [start] can rebuild it — a cancelled
    // CoroutineScope cannot relaunch, so the daemon needs a fresh Job/scope on restart.
    private var daemonJob = SupervisorJob()
    internal var daemonScope = CoroutineScope(Dispatchers.IO + daemonJob + daemonExceptionHandler)

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null


    fun start(context: Context) {
        // start() is called unconditionally from several sites, some off the main thread
        // (SenderSessionController network callbacks, OffGridAdvertiser coroutines). An unsynchronized
        // check-then-set let two callers both pass the guard and double-acquire the Wi-Fi/multicast
        // locks — the second WifiLock/MulticastLock instance overwrote the field, leaking the first
        // (no auto-timeout) and pinning the radio. Serialize the guard so only one caller initializes.
        synchronized(this) {
            if (isRunning) return
            isRunning = true
        }
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
            // CPU/radio awake for the whole process life — the lock auto-releases after the timeout.
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)

            // WifiLock has no timed acquire() overload; the CPU wake lock (above) carries the bounded
            // safety timeout, and the Wi-Fi lock is released alongside it in releaseLocks().
            wifiLock = wifiManager.createWifiLock(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "JosephClipboardClient:TransferDaemonWifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        LanDiscovery.start(context, daemonScope)

        FileTransferServer.start(context, daemonScope)

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
                        // match is treated as "sender not present" — the session stays paused.
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
                    
                    val notification = NotificationCompat.Builder(context, NotificationChannels.TRANSFER_REQUEST)
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




    /**
     * Bounded acquire ceiling for the CPU wake lock. A missed release() (e.g. the process is killed
     * before [stop] runs) would otherwise pin the CPU/radio awake for the whole process life; the
     * timeout guarantees the lock auto-releases. Long enough to cover a large transfer; a normal
     * shutdown still releases early via [releaseLocks].
     */
    private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L

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
     * with an HMAC proof of the shared pairing key before the receiver shows the incoming-file popup —
     * closing the "spoof a paired peer's IP to raise a popup without holding the key" hole. A receiver
     * only issues the challenge to a sender that advertises this; a sender always tolerates both the
     * challenge and the direct OFFER_RECEIVED, so old↔new stays byte-identical. Parsed into
     * [DiscoveredDevice.supportsAuth2].
     */
    const val OFFER_AUTH_CAP = "AUTH2"

    /** Capability list advertised in discovery — comma-joined, order-stable, parsed by `contains`. */
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
     * re-dials the sender, recomputes the on-disk offset, and re-streams) — bounded and backed off
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
            DownloadEngine.resumeIncomingTransfer(context, session.sessionId)
        }
        return true
    }

    }

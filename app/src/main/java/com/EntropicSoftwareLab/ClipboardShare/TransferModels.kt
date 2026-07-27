package com.EntropicSoftwareLab.ClipboardShare

import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

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
    val supportsAuthFile: Boolean = false,
    /** Peer advertised the [TransferDaemon.FILE_GCM_CAP_V2] token → it supports the FileSeal v2 AEAD
     * (per-session key + session/file-bound AAD). Orthogonal to [supportsAuthFile]: v2 is used only
     * when BOTH peers advertise it, otherwise the negotiation falls back to v1. Defaults false. */
    val supportsAuthFileV2: Boolean = false,
    /** Peer advertised the [TransferDaemon.OFFER_AUTH_CAP] token → it will answer an OFFER_CHALLENGE
     * with an HMAC proof of the shared pairing key before we show its incoming-file popup. The receiver
     * only issues the challenge to a sender with this flag set; senders that omit it keep today's
     * direct OFFER_RECEIVED handshake. Defaults false so un-updated peers are byte-identical. */
    val supportsAuth2: Boolean = false,
    /** Peer's stable install id from the discovery `did:` token; null for peers that predate it. Lets
     * clipboard auto-push follow this device to a new DHCP address (see [ClipboardTargeting]). */
    val deviceId: String? = null,
    /** Peer advertised the [TransferDaemon.ACCEPT_AUTH_CAP] token → it will answer an
     * ACCEPT_CHALLENGE with an HMAC proof of the shared pairing key before we (as sender) start
     * streaming bytes in response to its ACCEPT_OFFER/RESUME_REQUEST — the accept/resume-direction
     * mirror of [supportsAuth2]. Appended at the end (not inserted among the existing fields) so
     * every pre-existing positional [DiscoveredDevice] constructor call stays valid unchanged.
     * Defaults false so un-updated peers are byte-identical. */
    val supportsAuth2Accept: Boolean = false,
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
    @Volatile var lastResumeOffset: Long = -1L,
    /**
     * Set on the receiver once this incoming session's bytes have been negotiated as FileSeal
     * (AES-GCM) sealed. The name-based resume watcher uses it as a security gate: a session that was
     * authenticated once must never be silently re-dialed against an IP that isn't FGCM-pinned (a
     * same-display-name impostor on the LAN), which would be a downgrade to an unauthenticated peer.
     */
    @Volatile var fileGcmNegotiated: Boolean = false,
    /**
     * Set on the receiver once this incoming session's FileSeal bytes have been negotiated as v2
     * (both peers advertised FGCM2 → the receiver sent "fgcm2"). The receive loop reads this to
     * derive the v2 per-session key and bind the v2 AAD prefix; false keeps the v1 FileSeal path.
     * Orthogonal to [fileGcmNegotiated] (which stays true for both v1 and v2 authenticated streams).
     */
    @Volatile var fileV2Negotiated: Boolean = false,
    /**
     * One-shot accept latch (incoming sessions): flipped by [DownloadEngine.acceptOffer] under a
     * lock on the session so a double-tap on the offer UI can't spawn two download coroutines
     * writing the same .part (AND-19). Lives on the session — not a global registry — so a
     * sender re-offering the same sessionId (fresh session object) can be accepted again.
     */
    @Volatile var acceptStarted: Boolean = false,
    /**
     * Off-grid (p2p) sessions only: the RAW ECDH secret agreed during the P2P_HELLO/P2P_WELCOME
     * handshake, present iff both ends negotiated [TransportCaps.SEALED_STREAM]. Handed to the
     * download/upload engine as its `fileKey` (with the v2 path), which applies
     * [FileSeal.deriveKeyV2] itself — so this stays RAW, exactly like the stored pairing key does on
     * the LAN path. Pre-deriving here would derive twice and the two ends would never agree.
     * Null on a legacy peer, which keeps the pre-3.7.7 plaintext off-grid stream.
     */
    @Volatile var p2pSealKey: ByteArray? = null,
    /**
     * Receive-side destination snapshot, resolved from the "save_location_uri"/"save_media_to"
     * prefs ONCE when the session's first download stream opens and pinned here for every later
     * write pass and resume-offset probe. Each pass used to re-read the prefs live, so flipping
     * a save setting mid-transfer made the resume path append mid-file bytes into a fresh empty
     * .part at the NEW destination while the partial data sat at the OLD one — the promoted file
     * was silently missing its head (FOLLOWUPS-2026-07-06 #1).
     */
    @Volatile var destResolved: Boolean = false,
    @Volatile var destSaveTreeUri: String? = null,
    @Volatile var destSaveMediaTo: String = TransferUtils.SAVE_MEDIA_GALLERY
) {
    /**
     * Bumped when a new outgoing send attempt starts. A stale send coroutine — still blocked on a dead
     * socket until its ~20 s TCP write timeout — checks this in its terminal handler, so when it finally
     * fails it can't overwrite a fresh resume's "transferring" status back to "paused".
     */
    val sendGeneration = AtomicInteger(0)

    /**
     * The live outgoing streaming socket for a sender-side session, if one is currently active.
     * A CANCEL_TRANSFER closes it so a write blocked on a dead socket is interrupted immediately
     * instead of hanging until the ~20 s TCP write timeout. Set when streaming starts, cleared when
     * it ends.
     */
    @Volatile var activeSendSocket: Socket? = null
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

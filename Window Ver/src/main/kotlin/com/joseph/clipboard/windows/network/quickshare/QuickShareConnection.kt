package com.joseph.clipboard.windows.network.quickshare

import com.google.protobuf.ByteString
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.ConnectionResponseFrame
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.DisconnectionFrame
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.KeepAliveFrame
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.OfflineFrame
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.OsInfo
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.PayloadTransferFrame
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.PayloadTransferFrame.PayloadHeader.PayloadType
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.V1Frame
import com.joseph.clipboard.windows.network.quickshare.proto.SharingWireProto
import com.joseph.clipboard.windows.storage.DiagLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID

private typealias SharingStatus = SharingWireProto.ConnectionResponseFrame.Status
private typealias SharingType = SharingWireProto.V1Frame.FrameType

/** One file a sender offered. [name] is already safe to use on disk. */
data class QuickShareFile(val name: String, val size: Long)

/** What the accept prompt shows. [pin] is the code the phone displays for the same connection. */
data class QuickShareOffer(val senderName: String, val pin: String, val files: List<QuickShareFile>)

/**
 * How far an accepted transfer is. [files] lists the finished files first, in the order they
 * finished and under the names they were saved as, then the file being written, then the rest, so
 * the first [completedCount] entries are on disk.
 */
data class QuickShareProgress(val files: List<QuickShareFile>, val completedCount: Int, val currentFileBytes: Long)

sealed class QuickShareOutcome {
    object Completed : QuickShareOutcome()
    object Declined : QuickShareOutcome()
    object TimedOut : QuickShareOutcome()
    object CancelledBySender : QuickShareOutcome()
    data class Failed(val reason: String) : QuickShareOutcome()
}

/** Callbacks arrive on the connection's own threads. */
interface QuickShareListener {
    /** The sender is waiting: answer with [QuickShareConnection.accept] or [QuickShareConnection.decline]. */
    fun onOffer(connection: QuickShareConnection, offer: QuickShareOffer)

    fun onProgress(connection: QuickShareConnection, progress: QuickShareProgress)

    /** Called exactly once per connection, whether or not [onOffer] ever was. */
    fun onFinished(connection: QuickShareConnection, outcome: QuickShareOutcome)
}

/**
 * One inbound Quick Share connection, from the sender's ConnectionRequest to the last file chunk.
 * [run] reads on the calling thread. [accept], [decline] and [tick] may be called from others, and
 * [isOverdue] never blocks, so a watchdog can use it even while a write is stuck.
 *
 * The sender is unauthenticated until the user compares the PIN and accepts, so everything before
 * that is size- and time-bounded, and no file data is taken before it.
 */
class QuickShareConnection(
    private val input: InputStream,
    private val output: OutputStream,
    /** Closes the underlying socket; safe to call more than once. */
    private val closeTransport: () -> Unit,
    private val listener: QuickShareListener,
    private val saveDirProvider: () -> File,
    private val reservedNames: () -> Set<String> = { emptySet() },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val random: SecureRandom = SecureRandom(),
) {
    val sessionId: String = "quickshare-" + UUID.randomUUID()

    private enum class Phase {
        HANDSHAKE,
        AWAIT_PAIRED_KEY_ENCRYPTION,
        AWAIT_PAIRED_KEY_RESULT,
        AWAIT_INTRODUCTION,
        AWAIT_CONSENT,
        RECEIVING,
        DONE,
    }

    private val stateLock = Any()
    private val sendLock = Any()

    @Volatile private var phase = Phase.HANDSHAKE
    @Volatile private var channel: SecureChannel? = null
    private val startedAt = clock()
    @Volatile private var lastInboundAt = startedAt
    @Volatile private var offeredAt = 0L
    @Volatile private var doneAt = 0L
    private var lastKeepAliveAt = startedAt // tick thread only

    private var senderName = "Quick Share"
    private var pin = ""
    private var saveDir: File? = null

    // Reader thread only.
    private class IncomingFile(val spec: QuickShareFile) {
        var savedName = spec.name
        var part: File? = null
        var stream: FileOutputStream? = null
        var received = 0L
        var done = false
    }

    private val incoming = LinkedHashMap<Long, IncomingFile>()
    private val completed = ArrayList<IncomingFile>()
    private val partialBytes = HashMap<Long, ByteArrayOutputStream>()
    private var lastProgressAt = 0L

    val isFinished: Boolean get() = phase == Phase.DONE

    /** True once [accept] has taken the offer, and from then on. */
    @Volatile var wasAccepted = false
        private set

    /** Reads until the connection ends. Never throws; the result goes to the listener. */
    fun run() {
        try {
            handshake()
            while (true) {
                val frame = QuickShareFraming.read(input, QuickShareFraming.MAX_FRAME) ?: break
                val offline = OfflineFrame.parseFrom(channel!!.open(frame))
                lastInboundAt = clock()
                // Once there is an outcome, only drain until the sender closes, so that our last
                // frames aren't lost to a connection reset.
                if (phase != Phase.DONE) handle(offline)
            }
            finish(QuickShareOutcome.CancelledBySender)
        } catch (e: Exception) {
            if (e is Ukey2Server.HandshakeRejected) runCatching { send(e.alert) }
            finish(QuickShareOutcome.Failed(e.message ?: e.javaClass.simpleName))
        } finally {
            discardPartFiles()
            closeTransport()
        }
    }

    /** Takes the offer. Returns false if it is no longer open. */
    fun accept(): Boolean {
        synchronized(stateLock) {
            if (phase != Phase.AWAIT_CONSENT) return false
            lastInboundAt = clock()
            phase = Phase.RECEIVING
            wasAccepted = true
        }
        return try {
            sendResponse(SharingStatus.ACCEPT)
            true
        } catch (e: IOException) {
            finish(QuickShareOutcome.Failed(e.message ?: "send failed"))
            closeTransport()
            false
        }
    }

    /** Turns the offer down. Returns false if it is no longer open. */
    fun decline(): Boolean = refuse(SharingStatus.REJECT, QuickShareOutcome.Declined)

    /** Drops the connection at whatever stage it is. */
    fun abort(outcome: QuickShareOutcome) {
        finish(outcome)
        closeTransport()
    }

    /** Sends the periodic keep-alive and expires an unanswered offer. Call about once a second. */
    fun tick() {
        val now = clock()
        if (phase == Phase.AWAIT_CONSENT && now - offeredAt >= CONSENT_TIMEOUT_MS) {
            refuse(SharingStatus.TIMED_OUT, QuickShareOutcome.TimedOut)
        }
        if (channel != null && phase != Phase.DONE && now - lastKeepAliveAt >= KEEP_ALIVE_INTERVAL_MS) {
            lastKeepAliveAt = now
            runCatching { sendKeepAlive(ack = false) }
        }
    }

    /**
     * True once the connection has outstayed a deadline and its socket should be closed: no offer
     * in time, a silent or stuck peer, or a finished connection the sender won't close.
     */
    fun isOverdue(): Boolean {
        val now = clock()
        val current = phase
        return when {
            current == Phase.DONE -> now - doneAt >= DRAIN_TIMEOUT_MS
            current < Phase.AWAIT_CONSENT && now - startedAt >= HANDSHAKE_TIMEOUT_MS -> true
            // Only the consent clock here, so tick() gets to answer TIMED_OUT before the socket goes.
            current == Phase.AWAIT_CONSENT -> now - offeredAt >= CONSENT_TIMEOUT_MS + DRAIN_TIMEOUT_MS
            else -> now - lastInboundAt >= IDLE_TIMEOUT_MS
        }
    }

    // --- handshake ---------------------------------------------------------------------------

    private fun handshake() {
        val request = OfflineFrame.parseFrom(readHandshakeFrame())
        if (request.v1.type != V1Frame.FrameType.CONNECTION_REQUEST) {
            throw QuickShareProtocolException("expected a ConnectionRequest")
        }
        val connectionRequest = request.v1.connectionRequest
        senderName = QuickShareEndpoint.displayName(
            QuickShareEndpoint.parseDeviceName(connectionRequest.endpointInfo.toByteArray())
                ?: connectionRequest.endpointName
        )

        val ukey = Ukey2Server(random)
        send(ukey.onClientInit(readHandshakeFrame()))
        val secrets = ukey.onClientFinished(readHandshakeFrame())
        pin = QuickShareCrypto.pinFromAuthString(secrets.authString)

        var response = OfflineFrame.parseFrom(readHandshakeFrame())
        // A keep-alive may already be due; the handshake deadline bounds how long we skip them.
        while (response.v1.type == V1Frame.FrameType.KEEP_ALIVE) {
            response = OfflineFrame.parseFrom(readHandshakeFrame())
        }
        if (response.v1.type != V1Frame.FrameType.CONNECTION_RESPONSE ||
            response.v1.connectionResponse.response == ConnectionResponseFrame.ResponseStatus.REJECT
        ) {
            throw QuickShareProtocolException("sender did not accept the connection")
        }
        send(
            offline(V1Frame.FrameType.CONNECTION_RESPONSE) {
                setConnectionResponse(
                    ConnectionResponseFrame.newBuilder()
                        .setStatus(0)
                        .setResponse(ConnectionResponseFrame.ResponseStatus.ACCEPT)
                        .setOsInfo(OsInfo.newBuilder().setType(OsInfo.OsType.WINDOWS))
                )
            }
        )

        val keys = QuickShareCrypto.deriveSessionKeys(secrets.nextSecret)
        channel = SecureChannel(sendKeys = keys.server, receiveKeys = keys.client, random = random)
        if (!advance(Phase.AWAIT_PAIRED_KEY_ENCRYPTION)) return

        // We hold no Google-account certificates, so both values are filler and the check that
        // follows ends in UNABLE, which is what makes the sender fall back to the on-screen PIN.
        sendSharing(SharingType.PAIRED_KEY_ENCRYPTION) {
            setPairedKeyEncryption(
                SharingWireProto.PairedKeyEncryptionFrame.newBuilder()
                    .setSecretIdHash(randomBytes(6))
                    .setSignedData(randomBytes(72))
            )
        }
    }

    private fun readHandshakeFrame(): ByteArray {
        val frame = QuickShareFraming.read(input, QuickShareFraming.MAX_HANDSHAKE_FRAME)
            ?: throw QuickShareProtocolException("sender closed during the handshake")
        lastInboundAt = clock()
        return frame
    }

    // --- encrypted frames --------------------------------------------------------------------

    private fun handle(frame: OfflineFrame) {
        when (frame.v1.type) {
            // Reply only to a request; answering an ack would ping-pong forever.
            V1Frame.FrameType.KEEP_ALIVE -> if (!frame.v1.keepAlive.ack) sendKeepAlive(ack = true)
            V1Frame.FrameType.PAYLOAD_TRANSFER -> handlePayload(frame.v1.payloadTransfer)
            V1Frame.FrameType.DISCONNECTION -> abort(QuickShareOutcome.CancelledBySender)
            else -> Unit // bandwidth upgrades and the like are optional
        }
    }

    private fun handlePayload(transfer: PayloadTransferFrame) {
        if (transfer.packetType == PayloadTransferFrame.PacketType.CONTROL) {
            val event = transfer.controlMessage.event
            if (event == PayloadTransferFrame.ControlMessage.EventType.PAYLOAD_CANCELED ||
                event == PayloadTransferFrame.ControlMessage.EventType.PAYLOAD_ERROR
            ) {
                abort(QuickShareOutcome.CancelledBySender)
            }
            return
        }
        if (transfer.packetType != PayloadTransferFrame.PacketType.DATA) return
        when (transfer.payloadHeader.type) {
            PayloadType.BYTES -> handleBytesChunk(transfer)
            PayloadType.FILE -> handleFileChunk(transfer)
            else -> throw QuickShareProtocolException("unsupported payload type")
        }
    }

    private fun handleBytesChunk(transfer: PayloadTransferFrame) {
        val id = transfer.payloadHeader.id
        val chunk = transfer.payloadChunk
        if (id !in partialBytes && partialBytes.size >= MAX_PARTIAL_PAYLOADS) {
            throw QuickShareProtocolException("too many unfinished payloads")
        }
        val buffer = partialBytes.getOrPut(id) { ByteArrayOutputStream() }
        if (chunk.offset != buffer.size().toLong()) throw QuickShareProtocolException("payload chunk out of order")
        if (partialBytes.values.sumOf { it.size() } + chunk.body.size() > MAX_BYTES_PAYLOAD) {
            throw QuickShareProtocolException("control payload too large")
        }
        chunk.body.writeTo(buffer)
        if (chunk.flags and LAST_CHUNK != 0) {
            partialBytes.remove(id)
            handleSharing(SharingWireProto.Frame.parseFrom(buffer.toByteArray()))
        }
    }

    private fun handleSharing(frame: SharingWireProto.Frame) {
        val v1 = frame.v1
        if (v1.type == SharingType.CANCEL) {
            abort(QuickShareOutcome.CancelledBySender)
            return
        }
        when {
            phase == Phase.AWAIT_PAIRED_KEY_ENCRYPTION && v1.type == SharingType.PAIRED_KEY_ENCRYPTION -> {
                if (!advance(Phase.AWAIT_PAIRED_KEY_RESULT)) return
                sendSharing(SharingType.PAIRED_KEY_RESULT) {
                    setPairedKeyResult(
                        SharingWireProto.PairedKeyResultFrame.newBuilder()
                            .setStatus(SharingWireProto.PairedKeyResultFrame.Status.UNABLE)
                    )
                }
            }
            phase == Phase.AWAIT_PAIRED_KEY_RESULT && v1.type == SharingType.PAIRED_KEY_RESULT ->
                advance(Phase.AWAIT_INTRODUCTION)
            phase == Phase.AWAIT_INTRODUCTION && v1.type == SharingType.INTRODUCTION ->
                handleIntroduction(v1.introduction)
            // Certificate and progress frames carry nothing we act on.
        }
    }

    private fun handleIntroduction(introduction: SharingWireProto.IntroductionFrame) {
        offeredAt = clock() // before the phase: the timer threads read it once they see AWAIT_CONSENT
        if (!advance(Phase.AWAIT_CONSENT)) return
        val files = introduction.fileMetadataList
        if (files.isEmpty() || introduction.textMetadataCount > 0 ||
            introduction.wifiCredentialsMetadataCount > 0 || introduction.appMetadataCount > 0 ||
            introduction.streamMetadataCount > 0
        ) {
            refuse(SharingStatus.UNSUPPORTED_ATTACHMENT_TYPE, QuickShareOutcome.Failed("only files are supported"))
            return
        }
        if (files.size > MAX_FILES) return reject("too many files")
        var total = 0L
        for (metadata in files) {
            val size = metadata.size
            // Compared against the remaining allowance so the running sum can't overflow.
            if (size < 0 || size > MAX_TOTAL_BYTES - total) {
                refuse(SharingStatus.NOT_ENOUGH_SPACE, QuickShareOutcome.Failed("offer is too large"))
                return
            }
            total += size
            val entry = IncomingFile(QuickShareFile(QuickShareFiles.safeName(metadata.name), size))
            if (incoming.put(metadata.payloadId, entry) != null) return reject("duplicate payload id")
        }
        val dir = saveDirProvider()
        if (!dir.isDirectory && !dir.mkdirs()) return reject("save folder is unavailable")
        // usableSpace reports 0 when it can't tell (some network shares), so 0 doesn't refuse.
        val free = dir.usableSpace
        if (free in 1 until total) {
            refuse(SharingStatus.NOT_ENOUGH_SPACE, QuickShareOutcome.Failed("not enough disk space"))
            return
        }
        saveDir = dir
        listener.onOffer(this, QuickShareOffer(senderName, pin, incoming.values.map { it.spec }))
    }

    /** Refuses an offer before the user sees it, so the sender gets an answer and not a dead socket. */
    private fun reject(reason: String) {
        DiagLog.w("QuickShare: offer refused: $reason")
        refuse(SharingStatus.REJECT, QuickShareOutcome.Failed(reason))
    }

    private fun handleFileChunk(transfer: PayloadTransferFrame) {
        if (phase != Phase.RECEIVING) throw QuickShareProtocolException("file data before the offer was accepted")
        val dir = saveDir!!
        val file = incoming[transfer.payloadHeader.id]?.takeUnless { it.done }
            ?: throw QuickShareProtocolException("data for a file that wasn't offered")
        val chunk = transfer.payloadChunk
        val length = chunk.body.size()
        if (chunk.offset != file.received) throw QuickShareProtocolException("file chunk out of order")
        if (length > file.spec.size - file.received) throw QuickShareProtocolException("file is longer than offered")

        val stream = file.stream ?: run {
            val part = QuickShareFiles.createPartFile(dir)
            file.part = part
            FileOutputStream(part).also { file.stream = it }
        }
        chunk.body.writeTo(stream)
        file.received += length

        if (chunk.flags and LAST_CHUNK == 0) {
            val now = clock()
            if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                lastProgressAt = now
                reportProgress(file)
            }
            return
        }
        if (file.received != file.spec.size) throw QuickShareProtocolException("file is shorter than offered")
        stream.close()
        file.stream = null
        file.savedName = QuickShareFiles.finalizeFile(file.part!!, dir, file.spec.name, reservedNames()).name
        file.part = null
        file.done = true
        completed += file
        reportProgress(null)

        if (completed.size == incoming.size) {
            runCatching { sendDisconnection() }
            finish(QuickShareOutcome.Completed)
        }
    }

    private fun reportProgress(current: IncomingFile?) {
        val ordered = completed + listOfNotNull(current) + incoming.values.filter { !it.done && it !== current }
        listener.onProgress(
            this,
            QuickShareProgress(
                files = ordered.map { QuickShareFile(it.savedName, it.spec.size) },
                completedCount = completed.size,
                currentFileBytes = current?.received ?: 0L,
            )
        )
    }

    private fun discardPartFiles() {
        for (file in incoming.values) {
            runCatching { file.stream?.close() }
            file.stream = null
            file.part?.delete()
            file.part = null
        }
    }

    /**
     * Moves the reader thread on to [next] unless another thread has already ended the connection:
     * [Phase.DONE] is never left.
     */
    private fun advance(next: Phase): Boolean = synchronized(stateLock) {
        if (phase == Phase.DONE) return false
        phase = next
        true
    }

    /** Records the first outcome and reports it; later calls do nothing. */
    private fun finish(outcome: QuickShareOutcome) {
        synchronized(stateLock) {
            if (phase == Phase.DONE) return
            doneAt = clock()
            phase = Phase.DONE
        }
        runCatching { listener.onFinished(this, outcome) }
    }

    private fun refuse(status: SharingStatus, outcome: QuickShareOutcome): Boolean {
        synchronized(stateLock) {
            if (phase != Phase.AWAIT_CONSENT) return false
            doneAt = clock()
            phase = Phase.DONE
        }
        runCatching {
            sendResponse(status)
            sendDisconnection()
        }
        runCatching { listener.onFinished(this, outcome) }
        return true
    }

    // --- sending -----------------------------------------------------------------------------

    /** Sealing and writing share one lock so sequence numbers reach the wire in order. */
    private fun send(frame: ByteArray) = synchronized(sendLock) {
        QuickShareFraming.write(output, channel?.seal(frame) ?: frame)
    }

    private fun offline(type: V1Frame.FrameType, body: V1Frame.Builder.() -> Unit): ByteArray =
        OfflineFrame.newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(V1Frame.newBuilder().setType(type).apply(body))
            .build()
            .toByteArray()

    private fun sendKeepAlive(ack: Boolean) = send(
        offline(V1Frame.FrameType.KEEP_ALIVE) { setKeepAlive(KeepAliveFrame.newBuilder().setAck(ack)) }
    )

    private fun sendDisconnection() = send(
        offline(V1Frame.FrameType.DISCONNECTION) { setDisconnection(DisconnectionFrame.newBuilder()) }
    )

    private fun sendResponse(status: SharingStatus) = sendSharing(SharingType.RESPONSE) {
        setConnectionResponse(SharingWireProto.ConnectionResponseFrame.newBuilder().setStatus(status))
    }

    /** A sharing frame travels as a BYTES payload: the data chunk, then an empty "last" chunk. */
    private fun sendSharing(type: SharingType, body: SharingWireProto.V1Frame.Builder.() -> Unit) {
        val payload = SharingWireProto.Frame.newBuilder()
            .setVersion(SharingWireProto.Frame.Version.V1)
            .setV1(SharingWireProto.V1Frame.newBuilder().setType(type).apply(body))
            .build()
            .toByteString()
        val header = PayloadTransferFrame.PayloadHeader.newBuilder()
            .setId(random.nextLong())
            .setType(PayloadType.BYTES)
            .setTotalSize(payload.size().toLong())
            .setIsSensitive(false)
            .build()
        fun chunk(flags: Int, offset: Int, data: ByteString?) = offline(V1Frame.FrameType.PAYLOAD_TRANSFER) {
            val piece = PayloadTransferFrame.PayloadChunk.newBuilder().setFlags(flags).setOffset(offset.toLong())
            if (data != null) piece.setBody(data)
            setPayloadTransfer(
                PayloadTransferFrame.newBuilder()
                    .setPacketType(PayloadTransferFrame.PacketType.DATA)
                    .setPayloadHeader(header)
                    .setPayloadChunk(piece)
            )
        }
        synchronized(sendLock) {
            send(chunk(0, 0, payload))
            send(chunk(LAST_CHUNK, payload.size(), null))
        }
    }

    private fun randomBytes(count: Int): ByteString =
        ByteString.copyFrom(ByteArray(count).also { random.nextBytes(it) })

    companion object {
        const val MAX_FILES = 500
        const val MAX_TOTAL_BYTES = 50L * 1024 * 1024 * 1024
        private const val MAX_BYTES_PAYLOAD = 1024 * 1024
        private const val MAX_PARTIAL_PAYLOADS = 4
        private const val LAST_CHUNK = 1

        const val HANDSHAKE_TIMEOUT_MS = 30_000L
        const val CONSENT_TIMEOUT_MS = 60_000L
        const val IDLE_TIMEOUT_MS = 60_000L
        const val KEEP_ALIVE_INTERVAL_MS = 10_000L
        private const val DRAIN_TIMEOUT_MS = 5_000L
        private const val PROGRESS_INTERVAL_MS = 100L
    }
}

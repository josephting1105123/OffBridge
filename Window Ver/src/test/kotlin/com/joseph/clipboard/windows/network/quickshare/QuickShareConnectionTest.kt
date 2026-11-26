package com.joseph.clipboard.windows.network.quickshare

import com.google.protobuf.ByteString
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.ConnectionRequestFrame
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.ConnectionResponseFrame
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.KeepAliveFrame
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.OfflineFrame
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.PayloadTransferFrame
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.PayloadTransferFrame.PayloadHeader.PayloadType
import com.joseph.clipboard.windows.network.quickshare.proto.OfflineWireProto.V1Frame
import com.joseph.clipboard.windows.network.quickshare.proto.SharingWireProto
import com.joseph.clipboard.windows.network.quickshare.proto.UkeyProto
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private typealias SharingType = SharingWireProto.V1Frame.FrameType
private typealias SharingStatus = SharingWireProto.ConnectionResponseFrame.Status

/**
 * Drives the receiver over real sockets with a stand-in sender written from the same protocol
 * notes. It proves the state machine, limits and file handling; it can't prove that a real Android
 * phone agrees with those notes.
 */
class QuickShareConnectionTest {

    @get:Rule val folder = TemporaryFolder()

    private val closeables = ArrayList<AutoCloseable>()

    @After fun tearDown() = closeables.asReversed().forEach { runCatching { it.close() } }

    private class RecordingListener(private val onOfferAction: (QuickShareConnection) -> Unit = {}) : QuickShareListener {
        val offers = LinkedBlockingQueue<QuickShareOffer>()
        val outcomes = LinkedBlockingQueue<QuickShareOutcome>()
        val progress = LinkedBlockingQueue<QuickShareProgress>()
        @Volatile var connection: QuickShareConnection? = null

        override fun onOffer(connection: QuickShareConnection, offer: QuickShareOffer) {
            this.connection = connection
            offers.add(offer)
            onOfferAction(connection)
        }

        override fun onProgress(connection: QuickShareConnection, progress: QuickShareProgress) {
            this.progress.add(progress)
        }

        override fun onFinished(connection: QuickShareConnection, outcome: QuickShareOutcome) {
            outcomes.add(outcome)
        }

        fun offer(): QuickShareOffer = checkNotNull(offers.poll(5, TimeUnit.SECONDS)) { "no offer; $outcomes" }
        fun outcome(): QuickShareOutcome = checkNotNull(outcomes.poll(5, TimeUnit.SECONDS)) { "no outcome" }
    }

    private class Harness(
        val sender: FakeSender,
        val connection: QuickShareConnection,
        val listener: RecordingListener,
        val saveDir: File,
        val reader: Thread,
        val clock: AtomicLong,
    )

    private fun harness(saveDir: File = folder.newFolder()): Harness {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket(server.inetAddress, server.localPort).apply { soTimeout = 5_000 }
        val accepted = server.accept()
        closeables += listOf(server, client, accepted)
        val listener = RecordingListener()
        val clock = AtomicLong(1_000)
        val connection = QuickShareConnection(
            input = accepted.getInputStream(),
            output = accepted.getOutputStream(),
            closeTransport = { accepted.close() },
            listener = listener,
            saveDirProvider = { saveDir },
            clock = clock::get,
        )
        val reader = thread(isDaemon = true) { connection.run() }
        return Harness(FakeSender(client), connection, listener, saveDir, reader, clock)
    }

    /** Handshake, paired-key exchange and introduction; returns the offer the PC raised. */
    private fun Harness.introduce(vararg files: Triple<Long, String, Long>): QuickShareOffer {
        sender.connect("Pixel 9")
        sender.pairedKeyExchange()
        sender.introduce(*files)
        return listener.offer()
    }

    private fun leftovers(dir: File) = dir.list().orEmpty().filter { it.endsWith(".part") }

    // --- the whole flow ----------------------------------------------------------------------

    @Test
    fun receivesFilesAfterAccept() {
        val h = harness()
        File(h.saveDir, "notes.txt").writeText("already here")
        val photo = ByteArray(1_300_000).also { SecureRandom().nextBytes(it) }

        val offer = h.introduce(
            Triple(11L, "photo.jpg", photo.size.toLong()),
            Triple(22L, "..\\..\\notes.txt", 5L),
            Triple(33L, "empty.bin", 0L),
        )
        assertEquals("Pixel 9", offer.senderName)
        assertEquals(h.sender.pin, offer.pin)
        assertEquals(listOf("photo.jpg", "notes.txt", "empty.bin"), offer.files.map { it.name })

        assertTrue(h.connection.accept())
        assertEquals(SharingStatus.ACCEPT, h.sender.readResponse())

        // Out of manifest order, to check what progress reports.
        h.sender.sendFileChunk(22L, 5, 0, "hello".toByteArray(), last = false)
        h.sender.sendFileChunk(22L, 5, 5, null, last = true)
        var offset = 0
        while (offset < photo.size) {
            val end = minOf(offset + 512 * 1024, photo.size)
            h.sender.sendFileChunk(11L, photo.size.toLong(), offset.toLong(), photo.copyOfRange(offset, end), false)
            offset = end
        }
        h.sender.sendFileChunk(11L, photo.size.toLong(), offset.toLong(), null, last = true)
        h.sender.sendFileChunk(33L, 0, 0, null, last = true)

        assertEquals(V1Frame.FrameType.DISCONNECTION, h.sender.readNonKeepAlive().v1.type)
        assertEquals(QuickShareOutcome.Completed, h.listener.outcome())
        assertArrayEquals(photo, File(h.saveDir, "photo.jpg").readBytes())
        assertEquals("already here", File(h.saveDir, "notes.txt").readText())
        assertEquals("hello", File(h.saveDir, "notes (1).txt").readText())
        assertEquals(0L, File(h.saveDir, "empty.bin").length())
        assertEquals(emptyList<String>(), leftovers(h.saveDir))

        val last = h.listener.progress.toList().last()
        assertEquals(3, last.completedCount)
        assertEquals(listOf("notes (1).txt", "photo.jpg", "empty.bin"), last.files.map { it.name })
        assertEquals(listOf(5L, photo.size.toLong(), 0L), last.files.map { it.size })

        h.sender.close()
        h.reader.join(5_000)
        assertFalse(h.reader.isAlive)
        assertNull("exactly one outcome", h.listener.outcomes.poll())
    }

    @Test
    fun declineTellsTheSenderAndWritesNothing() {
        val h = harness()
        h.introduce(Triple(1L, "a.txt", 3L))
        assertTrue(h.connection.decline())
        assertEquals(SharingStatus.REJECT, h.sender.readResponse())
        assertEquals(QuickShareOutcome.Declined, h.listener.outcome())
        assertFalse(h.connection.accept())
        assertEquals(0, h.saveDir.list()!!.size)
    }

    // --- hostile or broken senders -----------------------------------------------------------

    @Test
    fun fileDataBeforeAcceptIsRefused() {
        val h = harness()
        h.introduce(Triple(1L, "a.txt", 3L))
        h.sender.sendFileChunk(1L, 3, 0, "abc".toByteArray(), last = true)
        assertTrue(h.listener.outcome() is QuickShareOutcome.Failed)
        assertEquals(0, h.saveDir.list()!!.size)
    }

    @Test
    fun aFileLongerThanOfferedIsDroppedWithItsPartFile() = assertBadChunk { sender ->
        sender.sendFileChunk(1L, 8, 4, ByteArray(5), last = false)
    }

    @Test
    fun aChunkAtTheWrongOffsetIsRefused() = assertBadChunk { sender ->
        sender.sendFileChunk(1L, 8, 6, ByteArray(2), last = true)
    }

    @Test
    fun aShortFileMarkedFinishedIsRefused() = assertBadChunk { sender ->
        sender.sendFileChunk(1L, 8, 4, null, last = true)
    }

    @Test
    fun dataForAnUnofferedFileIsRefused() = assertBadChunk { sender ->
        sender.sendFileChunk(99L, 8, 0, ByteArray(8), last = true)
    }

    /** Sends 4 good bytes of an 8-byte file, then [bad], and expects nothing left behind. */
    private fun assertBadChunk(bad: (FakeSender) -> Unit) {
        val h = harness()
        h.introduce(Triple(1L, "a.bin", 8L))
        h.connection.accept()
        h.sender.sendFileChunk(1L, 8, 0, ByteArray(4), last = false)
        bad(h.sender)
        assertTrue(h.listener.outcome() is QuickShareOutcome.Failed)
        h.reader.join(5_000)
        assertEquals(0, h.saveDir.list()!!.size)
    }

    @Test
    fun aSenderThatDropsMidFileLeavesNoPartFile() {
        val h = harness()
        h.introduce(Triple(1L, "a.bin", 8L))
        h.connection.accept()
        h.sender.sendFileChunk(1L, 8, 0, ByteArray(4), last = false)
        h.sender.close()
        h.listener.outcome()
        h.reader.join(5_000)
        assertEquals(0, h.saveDir.list()!!.size)
    }

    @Test
    fun cancelFromTheSenderEndsTheTransfer() {
        val h = harness()
        h.introduce(Triple(1L, "a.bin", 8L))
        h.sender.sendSharing(SharingType.CANCEL) {}
        assertEquals(QuickShareOutcome.CancelledBySender, h.listener.outcome())
        assertFalse(h.connection.accept())
    }

    @Test
    fun textSharesAreRefusedAsUnsupported() {
        val h = harness()
        h.sender.connect("Pixel 9")
        h.sender.pairedKeyExchange()
        h.sender.sendSharing(SharingType.INTRODUCTION) {
            setIntroduction(
                SharingWireProto.IntroductionFrame.newBuilder()
                    .addTextMetadata(SharingWireProto.TextMetadata.newBuilder().setPayloadId(1).setSize(4))
            )
        }
        assertEquals(SharingStatus.UNSUPPORTED_ATTACHMENT_TYPE, h.sender.readResponse())
        assertNull(h.listener.offers.poll())
    }

    @Test
    fun oversizedAndOverflowingOffersAreRefusedUnprompted() {
        val offers = listOf(
            listOf(QuickShareConnection.MAX_TOTAL_BYTES + 1),
            listOf(Long.MAX_VALUE, Long.MAX_VALUE),
            listOf(-1L),
        )
        for (sizes in offers) {
            val h = harness()
            h.sender.connect("Pixel 9")
            h.sender.pairedKeyExchange()
            h.sender.introduce(*sizes.mapIndexed { i, size -> Triple(i + 1L, "f$i", size) }.toTypedArray())
            assertEquals(SharingStatus.NOT_ENOUGH_SPACE, h.sender.readResponse())
            assertNull(h.listener.offers.poll())
        }
    }

    @Test
    fun duplicatePayloadIdsAndTooManyFilesAreAnsweredWithARefusal() {
        for (files in listOf(
            listOf(Triple(1L, "a", 1L), Triple(1L, "b", 1L)),
            List(QuickShareConnection.MAX_FILES + 1) { Triple(it.toLong(), "f$it", 1L) },
        )) {
            val h = harness()
            h.sender.connect("Pixel 9")
            h.sender.pairedKeyExchange()
            h.sender.introduce(*files.toTypedArray())
            assertEquals(SharingStatus.REJECT, h.sender.readResponse())
            assertTrue(h.listener.outcome() is QuickShareOutcome.Failed)
            assertNull(h.listener.offers.poll())
        }
    }

    @Test
    fun anUnusableSaveFolderIsAnsweredWithARefusal() {
        val h = harness(saveDir = File(folder.newFile(), "below-a-file"))
        h.sender.connect("Pixel 9")
        h.sender.pairedKeyExchange()
        h.sender.introduce(Triple(1L, "a", 1L))
        assertEquals(SharingStatus.REJECT, h.sender.readResponse())
        assertNull(h.listener.offers.poll())
    }

    @Test
    fun aShareCarryingAStreamIsRefusedBeforeAnyPrompt() {
        val h = harness()
        h.sender.connect("Pixel 9")
        h.sender.pairedKeyExchange()
        h.sender.sendSharing(SharingType.INTRODUCTION) {
            setIntroduction(
                SharingWireProto.IntroductionFrame.newBuilder()
                    .addFileMetadata(SharingWireProto.FileMetadata.newBuilder().setPayloadId(1).setName("a").setSize(1))
                    .addStreamMetadata(SharingWireProto.StreamMetadata.newBuilder())
            )
        }
        assertEquals(SharingStatus.UNSUPPORTED_ATTACHMENT_TYPE, h.sender.readResponse())
        assertNull(h.listener.offers.poll())
    }

    @Test
    fun aKeepAliveBeforeTheConnectionResponseIsSkipped() {
        val h = harness()
        h.sender.connect("Pixel 9", keepAliveBeforeResponse = true)
        h.sender.pairedKeyExchange()
        h.sender.introduce(Triple(1L, "a", 1L))
        h.listener.offer()
    }

    @Test
    fun aHugeFirstFrameIsRefused() {
        val h = harness()
        h.sender.writeRaw(byteArrayOf(0x00, 0x40, 0x00, 0x00)) // claims 4 MiB before any handshake
        assertTrue(h.listener.outcome() is QuickShareOutcome.Failed)
    }

    // --- keep-alives and deadlines -----------------------------------------------------------

    @Test
    fun answersKeepAliveRequestsButNotAcks() {
        val h = harness()
        h.introduce(Triple(1L, "a", 1L))
        h.sender.sendKeepAlive(ack = true)
        h.sender.sendKeepAlive(ack = false)
        val reply = h.sender.readOffline()
        assertEquals(V1Frame.FrameType.KEEP_ALIVE, reply.v1.type)
        assertTrue(reply.v1.keepAlive.ack)
        // Only one reply: the next thing on the wire is our response to the offer.
        h.connection.decline()
        assertEquals(V1Frame.FrameType.PAYLOAD_TRANSFER, h.sender.readOffline().v1.type)
    }

    @Test
    fun tickSendsKeepAlivesAndExpiresAnUnansweredOffer() {
        val h = harness()
        h.introduce(Triple(1L, "a", 1L))
        h.clock.addAndGet(QuickShareConnection.KEEP_ALIVE_INTERVAL_MS)
        h.connection.tick()
        val keepAlive = h.sender.readOffline()
        assertEquals(V1Frame.FrameType.KEEP_ALIVE, keepAlive.v1.type)
        assertFalse(keepAlive.v1.keepAlive.ack)

        h.clock.addAndGet(QuickShareConnection.CONSENT_TIMEOUT_MS)
        h.connection.tick()
        assertEquals(SharingStatus.TIMED_OUT, h.sender.readResponse())
        assertEquals(QuickShareOutcome.TimedOut, h.listener.outcome())
        assertFalse(h.connection.accept())
    }

    @Test
    fun aStalledHandshakeBecomesOverdue() {
        val h = harness()
        assertFalse(h.connection.isOverdue())
        h.clock.addAndGet(QuickShareConnection.HANDSHAKE_TIMEOUT_MS)
        assertTrue(h.connection.isOverdue())
    }

    @Test
    fun aSilentTransferBecomesOverdue() {
        val h = harness()
        h.introduce(Triple(1L, "a", 1L))
        h.connection.accept()
        h.clock.addAndGet(QuickShareConnection.IDLE_TIMEOUT_MS - 1)
        assertFalse(h.connection.isOverdue())
        h.clock.addAndGet(1)
        assertTrue(h.connection.isOverdue())
    }

    @Test
    fun anOpenPromptOutlivesTheIdleLimitUntilTickCanAnswer() {
        val h = harness()
        h.introduce(Triple(1L, "a", 1L))
        h.clock.addAndGet(QuickShareConnection.CONSENT_TIMEOUT_MS) // also the idle limit
        assertFalse(h.connection.isOverdue())
        h.connection.tick()
        assertEquals(SharingStatus.TIMED_OUT, h.sender.readResponse())
    }

    // --- the server --------------------------------------------------------------------------

    @Test
    fun receiverServesOverTcpAndStopClosesEverything() {
        val dir = folder.newFolder()
        val listener = RecordingListener { it.accept() }
        val receiver = QuickShareReceiver(
            listener = listener,
            deviceName = { "Test PC" },
            saveDir = { dir },
            requestedPort = 0,
            advertise = false,
        )
        receiver.start()
        closeables += AutoCloseable { receiver.stop() }
        assertTrue(receiver.isAlive)
        val port = receiver.port!!

        val socket = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 5_000 }
        closeables += socket
        val sender = FakeSender(socket)
        sender.connect("Pixel 9")
        sender.pairedKeyExchange()
        sender.introduce(Triple(5L, "over-tcp.txt", 2L))
        assertEquals(SharingStatus.ACCEPT, sender.readResponse())
        assertNotNull(receiver.socketFor(listener.connection!!.sessionId))
        sender.sendFileChunk(5L, 2, 0, "ok".toByteArray(), last = true)
        assertEquals(QuickShareOutcome.Completed, listener.outcome())
        assertEquals("ok", File(dir, "over-tcp.txt").readText())

        receiver.stop()
        assertFalse(receiver.isAlive)
        assertNull(receiver.port)
        assertTrue(runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.isFailure)
    }

    @Test
    fun stoppingMidTransferHasRemovedThePartFileByTheTimeItReturns() {
        val dir = folder.newFolder()
        val listener = RecordingListener { it.accept() }
        val receiver = QuickShareReceiver(
            listener = listener,
            deviceName = { "Test PC" },
            saveDir = { dir },
            requestedPort = 0,
            advertise = false,
        )
        receiver.start()
        closeables += AutoCloseable { receiver.stop() }
        val socket = Socket(InetAddress.getLoopbackAddress(), receiver.port!!).apply { soTimeout = 5_000 }
        closeables += socket
        val sender = FakeSender(socket)
        sender.connect("Pixel 9")
        sender.pairedKeyExchange()
        sender.introduce(Triple(5L, "big.bin", 1_000L))
        assertEquals(SharingStatus.ACCEPT, sender.readResponse())
        sender.sendFileChunk(5L, 1_000, 0, ByteArray(100), last = false)
        checkNotNull(listener.progress.poll(5, TimeUnit.SECONDS)) { "no progress" }
        assertEquals(1, leftovers(dir).size)

        receiver.stop()

        assertEquals(0, dir.list()!!.size)
        assertTrue(listener.outcome() is QuickShareOutcome.Failed)
        assertNull("exactly one outcome", listener.outcomes.poll())
    }

    @Test
    fun aHostWhoseOffersAreNeverAcceptedStopsBeingHeard() {
        val listener = RecordingListener { it.decline() }
        val receiver = QuickShareReceiver(
            listener = listener,
            deviceName = { "Test PC" },
            saveDir = { folder.root },
            requestedPort = 0,
            advertise = false,
        )
        receiver.start()
        closeables += AutoCloseable { receiver.stop() }
        fun connectSender(): FakeSender {
            val socket = Socket(InetAddress.getLoopbackAddress(), receiver.port!!).apply { soTimeout = 5_000 }
            closeables += socket
            return FakeSender(socket)
        }
        repeat(3) {
            val sender = connectSender()
            sender.connect("Pixel 9")
            sender.pairedKeyExchange()
            sender.introduce(Triple(1L, "a", 1L))
            assertEquals(SharingStatus.REJECT, sender.readResponse())
            assertEquals(QuickShareOutcome.Declined, listener.outcome())
            sender.close()
        }
        assertTrue(runCatching { connectSender().connect("Pixel 9") }.isFailure)
        assertEquals(3, listener.offers.size)
    }

    @Test
    fun receiverReportsAPortThatIsTaken() {
        val holder = ServerSocket(0).also { closeables += it }
        val receiver = QuickShareReceiver(
            listener = RecordingListener(),
            deviceName = { "Test PC" },
            saveDir = { folder.root },
            requestedPort = holder.localPort,
            advertise = false,
        )
        receiver.start()
        assertFalse(receiver.isAlive)
        assertNotNull(receiver.lastBindError)
    }

    // --- the stand-in sender -----------------------------------------------------------------

    private class FakeSender(private val socket: Socket) : AutoCloseable {
        private val random = SecureRandom()
        private var channel: SecureChannel? = null
        lateinit var pin: String
            private set

        override fun close() = socket.close()

        fun writeRaw(data: ByteArray) {
            socket.getOutputStream().write(data)
            socket.getOutputStream().flush()
        }

        private fun send(frame: ByteArray) =
            QuickShareFraming.write(socket.getOutputStream(), channel?.seal(frame) ?: frame)

        private fun receive(): ByteArray {
            val frame = checkNotNull(QuickShareFraming.read(socket.getInputStream(), QuickShareFraming.MAX_FRAME)) {
                "receiver closed the connection"
            }
            return channel?.open(frame) ?: frame
        }

        private fun offline(type: V1Frame.FrameType, body: V1Frame.Builder.() -> Unit): ByteArray =
            OfflineFrame.newBuilder()
                .setVersion(OfflineFrame.Version.V1)
                .setV1(V1Frame.newBuilder().setType(type).apply(body))
                .build()
                .toByteArray()

        private fun ukey(type: UkeyProto.Ukey2Message.Type, data: ByteString): ByteArray =
            UkeyProto.Ukey2Message.newBuilder().setMessageType(type).setMessageData(data).build().toByteArray()

        fun connect(name: String, keepAliveBeforeResponse: Boolean = false) {
            send(offline(V1Frame.FrameType.CONNECTION_REQUEST) {
                setConnectionRequest(
                    ConnectionRequestFrame.newBuilder()
                        .setEndpointId("abcd")
                        .setEndpointInfo(ByteString.copyFrom(QuickShareEndpoint.endpointInfo(name)))
                )
            })

            val keyPair = QuickShareCrypto.generateP256KeyPair()
            val finished = ukey(
                UkeyProto.Ukey2Message.Type.CLIENT_FINISH,
                UkeyProto.Ukey2ClientFinished.newBuilder()
                    .setPublicKey(ByteString.copyFrom(QuickShareCrypto.encodePublicKey(keyPair.public as ECPublicKey)))
                    .build()
                    .toByteString(),
            )
            val init = ukey(
                UkeyProto.Ukey2Message.Type.CLIENT_INIT,
                UkeyProto.Ukey2ClientInit.newBuilder()
                    .setVersion(1)
                    .setRandom(ByteString.copyFrom(ByteArray(32).also { random.nextBytes(it) }))
                    .addCipherCommitments(
                        UkeyProto.Ukey2ClientInit.CipherCommitment.newBuilder()
                            .setHandshakeCipher(UkeyProto.Ukey2HandshakeCipher.P256_SHA512)
                            .setCommitment(ByteString.copyFrom(QuickShareCrypto.sha512(finished)))
                    )
                    .setNextProtocol(Ukey2Server.NEXT_PROTOCOL)
                    .build()
                    .toByteString(),
            )
            send(init)
            val serverInitFrame = receive()
            val serverInit = UkeyProto.Ukey2ServerInit.parseFrom(
                UkeyProto.Ukey2Message.parseFrom(serverInitFrame).messageData
            )
            val secrets = QuickShareCrypto.deriveUkey2Secrets(
                QuickShareCrypto.ecdh(
                    keyPair.private,
                    QuickShareCrypto.decodePublicKey(serverInit.publicKey.toByteArray()),
                ),
                init,
                serverInitFrame,
            )
            pin = QuickShareCrypto.pinFromAuthString(secrets.authString)
            send(finished)
            if (keepAliveBeforeResponse) send(offline(V1Frame.FrameType.KEEP_ALIVE) {
                setKeepAlive(KeepAliveFrame.newBuilder().setAck(false))
            })
            send(offline(V1Frame.FrameType.CONNECTION_RESPONSE) {
                setConnectionResponse(
                    ConnectionResponseFrame.newBuilder().setResponse(ConnectionResponseFrame.ResponseStatus.ACCEPT)
                )
            })
            val response = OfflineFrame.parseFrom(receive())
            assertEquals(ConnectionResponseFrame.ResponseStatus.ACCEPT, response.v1.connectionResponse.response)
            val keys = QuickShareCrypto.deriveSessionKeys(secrets.nextSecret)
            channel = SecureChannel(sendKeys = keys.client, receiveKeys = keys.server)
        }

        fun pairedKeyExchange() {
            assertEquals(SharingType.PAIRED_KEY_ENCRYPTION, readSharing().v1.type)
            sendSharing(SharingType.PAIRED_KEY_ENCRYPTION) {}
            assertEquals(SharingType.PAIRED_KEY_RESULT, readSharing().v1.type)
            sendSharing(SharingType.PAIRED_KEY_RESULT) {}
        }

        fun introduce(vararg files: Triple<Long, String, Long>) = sendSharing(SharingType.INTRODUCTION) {
            val introduction = SharingWireProto.IntroductionFrame.newBuilder()
            files.forEach { (id, name, size) ->
                introduction.addFileMetadata(
                    SharingWireProto.FileMetadata.newBuilder().setPayloadId(id).setName(name).setSize(size)
                )
            }
            setIntroduction(introduction)
        }

        fun sendSharing(type: SharingType, body: SharingWireProto.V1Frame.Builder.() -> Unit) {
            val payload = SharingWireProto.Frame.newBuilder()
                .setVersion(SharingWireProto.Frame.Version.V1)
                .setV1(SharingWireProto.V1Frame.newBuilder().setType(type).apply(body))
                .build()
                .toByteArray()
            val id = random.nextLong()
            sendChunk(PayloadType.BYTES, id, payload.size.toLong(), 0, payload, last = false)
            sendChunk(PayloadType.BYTES, id, payload.size.toLong(), payload.size.toLong(), null, last = true)
        }

        fun sendFileChunk(id: Long, total: Long, offset: Long, body: ByteArray?, last: Boolean) =
            sendChunk(PayloadType.FILE, id, total, offset, body, last)

        private fun sendChunk(type: PayloadType, id: Long, total: Long, offset: Long, body: ByteArray?, last: Boolean) {
            val chunk = PayloadTransferFrame.PayloadChunk.newBuilder().setOffset(offset).setFlags(if (last) 1 else 0)
            if (body != null) chunk.setBody(ByteString.copyFrom(body))
            send(offline(V1Frame.FrameType.PAYLOAD_TRANSFER) {
                setPayloadTransfer(
                    PayloadTransferFrame.newBuilder()
                        .setPacketType(PayloadTransferFrame.PacketType.DATA)
                        .setPayloadHeader(
                            PayloadTransferFrame.PayloadHeader.newBuilder().setId(id).setType(type).setTotalSize(total)
                        )
                        .setPayloadChunk(chunk)
                )
            })
        }

        fun sendKeepAlive(ack: Boolean) = send(offline(V1Frame.FrameType.KEEP_ALIVE) {
            setKeepAlive(KeepAliveFrame.newBuilder().setAck(ack))
        })

        fun readOffline(): OfflineFrame = OfflineFrame.parseFrom(receive())

        fun readNonKeepAlive(): OfflineFrame {
            while (true) {
                val frame = readOffline()
                if (frame.v1.type != V1Frame.FrameType.KEEP_ALIVE) return frame
            }
        }

        private fun readSharing(): SharingWireProto.Frame {
            val data = readNonKeepAlive().v1.payloadTransfer
            assertEquals(PayloadType.BYTES, data.payloadHeader.type)
            val end = readNonKeepAlive().v1.payloadTransfer
            assertEquals(data.payloadHeader.id, end.payloadHeader.id)
            assertEquals(1, end.payloadChunk.flags)
            assertEquals(data.payloadChunk.body.size().toLong(), end.payloadChunk.offset)
            return SharingWireProto.Frame.parseFrom(data.payloadChunk.body)
        }

        fun readResponse(): SharingStatus {
            val frame = readSharing()
            assertEquals(SharingType.RESPONSE, frame.v1.type)
            return frame.v1.connectionResponse.status
        }
    }
}

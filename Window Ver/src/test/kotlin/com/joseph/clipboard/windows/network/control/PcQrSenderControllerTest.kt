package com.joseph.clipboard.windows.network.control

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.joseph.clipboard.windows.storage.EcdhSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Drives [PcQrSenderController] over a fake [ControlChannel] playing the phone's side of the
 * wire protocol (real ECDH, real [SessionCrypto]): HELLO gate, DATA_READY/TRY_TRANSPORT
 * sequencing, the 3-attempt retry ladder with credential regeneration, the firewall-suspect
 * HANDSHAKE_FAILED branch, and the no-result watchdog.
 */
class PcQrSenderControllerTest {

    private class FakeChannel : ControlChannel {
        val sent = LinkedBlockingQueue<ByteArray>()
        override fun send(frame: ByteArray) { sent.put(frame) }
        override var onMessage: ((ByteArray) -> Unit)? = null
        override var onClosed: (() -> Unit)? = null
        override fun close() {}
        fun deliver(frame: ByteArray) = onMessage!!.invoke(frame)
    }

    /** The phone half of the handshake: its own ECDH key + the shared secret with the PC. */
    private class Phone(pcPublicKey: ByteArray, val token: ByteArray) {
        val ecdh = EcdhSession()
        val sharedKey: ByteArray = ecdh.deriveSecrets(pcPublicKey).first
        val crypto = SessionCrypto(sharedKey, isSender = false)

        fun helloRx(): ByteArray {
            val b64 = Base64.getEncoder()
            val body = JsonObject().apply {
                addProperty("hm", b64.encodeToString(SessionCrypto.hmacSha256(sharedKey, token)))
                addProperty("pk", b64.encodeToString(ecdh.getPublicKey()))
                addProperty("name", "TestPhone")
            }
            return ControlProtocol.plainFrame(ControlProtocol.HELLO_RX, body)
        }

        fun result(ok: Boolean, code: Int = 0): ByteArray =
            crypto.seal(ControlProtocol.TRANSPORT_RESULT, JsonObject().apply {
                addProperty("t", PcQrSenderController.SOFTAP_JOIN_ID)
                addProperty("ok", ok)
                addProperty("code", code)
            }.toString().toByteArray(Charsets.UTF_8))
    }

    private val token = ByteArray(16) { it.toByte() }

    private fun nextFrame(channel: FakeChannel, timeoutMs: Long = 3_000): ControlProtocol.Frame {
        val raw = channel.sent.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("no frame within ${timeoutMs}ms")
        return ControlProtocol.parse(raw) ?: throw AssertionError("unparseable frame")
    }

    /** Opens the next sealed frame with the phone's crypto, transparently answering nothing. */
    private fun nextOpened(channel: FakeChannel, phone: Phone): Pair<Int, JsonObject> {
        while (true) {
            val f = nextFrame(channel)
            val clear = phone.crypto.open(f.type, f.seq, f.body)
                ?: throw AssertionError("frame type=${f.type} did not open")
            if (f.type == ControlProtocol.PING) continue // liveness noise, not protocol
            return f.type to JsonParser.parseString(String(clear, Charsets.UTF_8)).asJsonObject
        }
    }

    private fun link(
        channel: FakeChannel,
        controller: PcQrSenderController,
        pcEcdh: EcdhSession
    ): Phone {
        controller.begin()
        val phone = Phone(pcEcdh.getPublicKey(), token)
        channel.deliver(phone.helloRx())

        val ackFrame = nextFrame(channel)
        assertEquals(ControlProtocol.HELLO_ACK, ackFrame.type)
        val ackBody = JsonParser.parseString(String(ackFrame.body, Charsets.UTF_8)).asJsonObject
        val expectedAck =
            SessionCrypto.hmacSha256(phone.sharedKey, token + "ack".toByteArray(Charsets.UTF_8))
        assertTrue(
            MessageDigest.isEqual(expectedAck, Base64.getDecoder().decode(ackBody.get("ack").asString))
        )
        assertEquals(PcQrSenderController.CAP_BITS, ackBody.get("cap").asInt)
        assertEquals(1, ackBody.getAsJsonArray("plan").size())

        val (readyType, ready) = nextOpened(channel, phone)
        assertEquals(ControlProtocol.DATA_READY, readyType)
        assertEquals("192.168.49.1", ready.get("ip").asString)
        assertEquals(PcQrSenderController.TRANSFER_PORT, ready.get("port").asInt)
        return phone
    }

    private fun expectTry(channel: FakeChannel, phone: Phone, ssid: String): JsonObject {
        val (type, body) = nextOpened(channel, phone)
        assertEquals(ControlProtocol.TRY_TRANSPORT, type)
        assertEquals(PcQrSenderController.SOFTAP_JOIN_ID, body.get("t").asInt)
        assertEquals(ssid, body.get("ssid").asString)
        assertEquals(1, body.get("nk").asInt)
        return body
    }

    @Test
    fun `HELLO links, sends DATA_READY then TRY_TRANSPORT, and a join OK awaits the dial`() {
        val channel = FakeChannel()
        val pcEcdh = EcdhSession()
        val controller = PcQrSenderController(
            channel, token, pcEcdh, "192.168.49.1", "OffBridge-1234", "pass12345678"
        )
        val phone = link(channel, controller, pcEcdh)
        expectTry(channel, phone, "OffBridge-1234")
        assertEquals(QrControlState.Joining(1, "OffBridge-1234"), controller.state.value)

        channel.deliver(phone.result(ok = true))
        awaitState(controller) { it == QrControlState.AwaitingDial }

        controller.notifyTransferStarted()
        awaitState(controller) { it == QrControlState.Transferring }
    }

    @Test
    fun `a HELLO retry queued behind the first one still gets the ACK replayed`() {
        val channel = FakeChannel()
        val pcEcdh = EcdhSession()
        val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val controller = PcQrSenderController(
            channel, token, pcEcdh, "192.168.49.1", "OffBridge-1234", "pass12345678",
            executor = executor
        )
        controller.begin()
        val phone = Phone(pcEcdh.getPublicKey(), token)
        // Hold the executor so both HELLOs queue on the pre-session handler before either runs —
        // the phone's retry landing while the PC is still busy with the first.
        val gate = java.util.concurrent.CountDownLatch(1)
        executor.execute { gate.await() }
        channel.deliver(phone.helloRx())
        channel.deliver(phone.helloRx())
        gate.countDown()

        assertEquals(ControlProtocol.HELLO_ACK, nextFrame(channel).type)
        assertEquals(ControlProtocol.DATA_READY, nextOpened(channel, phone).first)
        expectTry(channel, phone, "OffBridge-1234")
        // The retry is answered from the live session, not discarded.
        assertEquals(ControlProtocol.HELLO_ACK, nextFrame(channel).type)
        assertEquals(ControlProtocol.DATA_READY, nextOpened(channel, phone).first)
        expectTry(channel, phone, "OffBridge-1234")
        executor.shutdownNow()
    }

    @Test
    fun `a pre-HELLO unsubscribe keeps the channel, a post-HELLO one does not`() {
        val channel = FakeChannel()
        val pcEcdh = EcdhSession()
        val controller = PcQrSenderController(
            channel, token, pcEcdh, "192.168.49.1", "OffBridge-1234", "pass12345678"
        )
        controller.begin()
        controller.onCentralSubscribed()
        awaitState(controller) { it == QrControlState.CentralConnected }
        // The phone's first connect collapsed before HELLO: keep listening, back to Advertising.
        assertTrue(controller.onCentralUnsubscribed())
        awaitState(controller) { it == QrControlState.Advertising }

        // It reconnects and links; from now on a drop is real.
        controller.onCentralSubscribed()
        val phone = Phone(pcEcdh.getPublicKey(), token)
        channel.deliver(phone.helloRx())
        assertEquals(ControlProtocol.HELLO_ACK, nextFrame(channel).type)
        awaitState(controller) { it is QrControlState.Linked || it is QrControlState.Joining }
        assertTrue(!controller.onCentralUnsubscribed())
    }

    @Test
    fun `a bad HELLO hmac is ignored`() {
        val channel = FakeChannel()
        val pcEcdh = EcdhSession()
        val controller = PcQrSenderController(
            channel, token, pcEcdh, "192.168.49.1", "ssid", "pskpskpsk123"
        )
        controller.begin()
        val phone = Phone(pcEcdh.getPublicKey(), ByteArray(16) { 0x55 }) // wrong token → wrong hm
        channel.deliver(phone.helloRx())
        assertNull(channel.sent.poll(400, TimeUnit.MILLISECONDS))
        assertEquals(QrControlState.Advertising, controller.state.value)
    }

    @Test
    fun `retry ladder re-pushes, regenerates creds, then aborts as Failed`() {
        val channel = FakeChannel()
        val pcEcdh = EcdhSession()
        val controller = PcQrSenderController(
            channel, token, pcEcdh, "192.168.49.1", "OLD-SSID", "oldpsk123456",
            regenerateCreds = { "NEW-SSID" to "newpsk123456" }
        )
        val phone = link(channel, controller, pcEcdh)

        expectTry(channel, phone, "OLD-SSID")                    // attempt 1
        channel.deliver(phone.result(ok = false, code = 1))      // PICKER_TIMEOUT

        expectTry(channel, phone, "OLD-SSID")                    // attempt 2: same creds re-pushed
        channel.deliver(phone.result(ok = false, code = 1))

        expectTry(channel, phone, "NEW-SSID")                    // attempt 3: regenerated creds
        assertEquals("NEW-SSID" to "newpsk123456", controller.creds.value)
        channel.deliver(phone.result(ok = false, code = 7))

        val (abortType, abortBody) = nextOpened(channel, phone)
        assertEquals(ControlProtocol.ABORT, abortType)
        assertEquals("GENERIC", abortBody.get("reason").asString)
        awaitState(controller) { it == QrControlState.Failed(7, firewallSuspected = false) }
    }

    @Test
    fun `HANDSHAKE_FAILED after a join OK fails as firewall-suspect without regenerating`() {
        var regenerated = false
        val channel = FakeChannel()
        val pcEcdh = EcdhSession()
        val controller = PcQrSenderController(
            channel, token, pcEcdh, "192.168.49.1", "SSID", "psk123456789",
            regenerateCreds = { regenerated = true; "X" to "Y" }
        )
        val phone = link(channel, controller, pcEcdh)
        expectTry(channel, phone, "SSID")

        channel.deliver(phone.result(ok = true))
        awaitState(controller) { it == QrControlState.AwaitingDial }

        channel.deliver(phone.result(ok = false, code = PcQrSenderController.FAILURE_HANDSHAKE_FAILED))
        val (abortType, _) = nextOpened(channel, phone)
        assertEquals(ControlProtocol.ABORT, abortType)
        awaitState(controller) { it == QrControlState.Failed(8, firewallSuspected = true) }
        assertEquals(false, regenerated)
    }

    @Test
    fun `a silent phone trips the result watchdog and drives the next attempt`() {
        val channel = FakeChannel()
        val pcEcdh = EcdhSession()
        val controller = PcQrSenderController(
            channel, token, pcEcdh, "192.168.49.1", "SSID", "psk123456789",
            resultTimeoutMs = 300
        )
        val phone = link(channel, controller, pcEcdh)
        expectTry(channel, phone, "SSID")   // attempt 1 — never answered
        expectTry(channel, phone, "SSID")   // watchdog fired → attempt 2
        awaitState(controller) { it == QrControlState.Joining(2, "SSID") }
    }

    private fun awaitState(
        controller: PcQrSenderController,
        timeoutMs: Long = 3_000,
        predicate: (QrControlState) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(controller.state.value)) return
            Thread.sleep(20)
        }
        throw AssertionError("state never matched; last=${controller.state.value}")
    }
}

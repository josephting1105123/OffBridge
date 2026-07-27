package com.EntropicSoftwareLab.ClipboardShare.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Pins the QR-pairing codec and the one-shot host window. The payload and frame shapes ARE the
 * cross-platform wire format, so the Windows module's QrPairCodecTest asserts the same things
 * against its port; a drift in either direction surfaces here rather than as a phone that can't
 * pair with a PC.
 */
class QrPairCodecTest {

    private val now = 1_700_000_000_000L
    private val deviceId = "0123456789abcdef0123456789abcdef"
    private val token = ByteArray(16) { (0x10 + it).toByte() }
    private lateinit var publicKey: ByteArray

    @Before
    fun setUp() {
        publicKey = newP256PublicKey()
        QrPairHost.close()
    }

    // ---- payload ------------------------------------------------------------------------------

    @Test
    fun `round trips every field`() {
        val payload = PairQrPayload.build(
            ip = "192.168.1.20",
            port = 65435,
            name = "Joe's PC",
            deviceId = deviceId,
            publicKey = publicKey,
            token = token,
            nowMs = now,
            ssid = "ClipboardShare-1234",
            psk = "hunter2hunter2",
            lanIp = "192.168.1.20",
        )
        val parsed = PairQrPayload.parse(payload.encode(), now) as PairQrPayload.ParseResult.Valid
        assertEquals(payload, parsed.payload)
        assertEquals("Joe's PC", parsed.payload.name)
        assertArrayEquals(token, parsed.payload.token)
        assertEquals("101112131415161718191a1b1c1d1e1f", parsed.payload.tokenHex())
        assertTrue(parsed.payload.hasApCredentials())
    }

    /** A name carrying the field delimiter must not shift the fields after it. */
    @Test
    fun `a delimiter in the device name survives the round trip`() {
        val hostile = "Joe|Work\nPC"
        val encoded = payload(name = hostile).encode()
        val parsed = PairQrPayload.parse(encoded, now) as PairQrPayload.ParseResult.Valid
        assertEquals(hostile, parsed.payload.name)
        assertEquals(deviceId, parsed.payload.deviceId)
        assertArrayEquals(token, parsed.payload.token)
    }

    @Test
    fun `a LAN-only payload carries no AP credentials`() {
        val parsed = PairQrPayload.parse(payload().encode(), now) as PairQrPayload.ParseResult.Valid
        assertFalse(parsed.payload.hasApCredentials())
        assertNull(parsed.payload.ssid)
        assertNull(parsed.payload.psk)
    }

    /** Half a credential pair is worse than none — it sends the scanner at an AP it can't join. */
    @Test
    fun `an ssid without a psk is dropped rather than half-used`() {
        val encoded = payload().encode() + "|ssid:" + b64("ClipboardShare-1234")
        val parsed = PairQrPayload.parse(encoded, now) as PairQrPayload.ParseResult.Valid
        assertFalse(parsed.payload.hasApCredentials())
    }

    @Test
    fun `a stale code reports expiry rather than a generic rejection`() {
        val encoded = payload().encode()
        assertSame(
            PairQrPayload.ParseResult.Expired,
            PairQrPayload.parse(encoded, now + PairQrPayload.VALIDITY_MS)
        )
    }

    @Test
    fun `a newer schema asks for an update instead of failing silently`() {
        val encoded = payload().encode().replaceFirst("CSPAIR1", "CSPAIR9")
        assertSame(PairQrPayload.ParseResult.UnsupportedVersion, PairQrPayload.parse(encoded, now))
    }

    @Test
    fun `foreign and malformed codes are rejected`() {
        val valid = payload().encode()
        val cases = listOf(
            """{"v":1,"t":"p2p","ssid":"x"}""",                           // the off-grid file QR
            "https://example.com",
            "",
            "CSPAIR1|not-an-ip|65435|" + valid.substringAfter("|65435|"),
            valid.replaceFirst("|65435|", "|0|"),                          // port out of range
            valid.replaceFirst("|65435|", "|70000|"),
            valid.split("|").take(7).joinToString("|"),                    // truncated
            "CSPAIR1|" + "9".repeat(2000),                                 // absurdly long
        )
        cases.forEach { assertSame("accepted: $it", PairQrPayload.ParseResult.Invalid, PairQrPayload.parse(it, now)) }
    }

    @Test
    fun `a non-P256 public key is rejected before any network action`() {
        val encoded = payload().encode().replaceFirst(b64Bytes(publicKey), b64("not-a-key"))
        assertSame(PairQrPayload.ParseResult.Invalid, PairQrPayload.parse(encoded, now))
    }

    @Test
    fun `a wrong-size token is rejected`() {
        val encoded = payload().encode().replaceFirst(b64Bytes(token), b64Bytes(ByteArray(8)))
        assertSame(PairQrPayload.ParseResult.Invalid, PairQrPayload.parse(encoded, now))
    }

    @Test
    fun `a malformed device id is rejected`() {
        val encoded = payload().encode().replaceFirst(deviceId, "NOTHEX")
        assertSame(PairQrPayload.ParseResult.Invalid, PairQrPayload.parse(encoded, now))
    }

    @Test
    fun `the Wi-Fi Direct subnet is never advertised as a LAN route`() {
        val built = PairQrPayload.build(
            ip = "192.168.137.1", port = 65435, name = "PC", deviceId = deviceId,
            publicKey = publicKey, token = token, nowMs = now, lanIp = "192.168.49.1",
        )
        assertNull(built.lanIp)
    }

    /**
     * The daemon screens a peer's key with this before running ECDH, so it has to reject a key on
     * the wrong curve — not merely one that fails to parse. An invalid-curve point is the classic
     * way to extract a private key from a careless ECDH implementation.
     */
    @Test
    fun `the key screen accepts P-256 and rejects everything else`() {
        assertTrue(PairQrPayload.isP256PublicKey(publicKey))
        assertFalse(PairQrPayload.isP256PublicKey(ByteArray(0)))
        assertFalse(PairQrPayload.isP256PublicKey("not-a-key".toByteArray(Charsets.UTF_8)))
        assertFalse(PairQrPayload.isP256PublicKey(publicKey.copyOf(publicKey.size - 1)))

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp384r1"))
        assertFalse(PairQrPayload.isP256PublicKey(kpg.generateKeyPair().public.encoded))
    }

    @Test
    fun `only our own codes are routed to the pairing parser`() {
        assertTrue(PairQrPayload.looksLikePairCode(payload().encode()))
        assertFalse(PairQrPayload.looksLikePairCode("""{"v":1,"t":"p2p"}"""))
    }

    // ---- frames -------------------------------------------------------------------------------

    @Test
    fun `request frame round trips`() {
        val line = QrPairWire.req("Joe|Work", publicKey, token, deviceId)
        assertTrue(line.startsWith("PAIR_QR|"))
        val req = QrPairWire.parseReq(line)!!
        assertEquals("Joe|Work", req.deviceName)
        assertArrayEquals(publicKey, req.publicKey)
        assertArrayEquals(token, req.token)
        assertEquals(deviceId, req.deviceId)
    }

    @Test
    fun `ack and fin frames round trip`() {
        val seal = ByteArray(48) { it.toByte() }
        val ack = QrPairWire.parseAck(QrPairWire.ack(deviceId, seal))!!
        assertEquals(deviceId, ack.deviceId)
        assertArrayEquals(seal, ack.seal)
        assertArrayEquals(seal, QrPairWire.parseFin(QrPairWire.fin(seal)))
    }

    @Test
    fun `a denial is never mistaken for an ack`() {
        val deny = QrPairWire.deny(QrPairWire.DENY_NO_SESSION)
        assertEquals(QrPairWire.DENY_NO_SESSION, QrPairWire.parseDeny(deny))
        assertNull(QrPairWire.parseAck(deny))
        assertNull(QrPairWire.parseDeny(QrPairWire.ack(deviceId, ByteArray(4))))
    }

    @Test
    fun `malformed frames parse to null instead of throwing`() {
        listOf(
            "PAIR_QR",
            "PAIR_QR|!!!|!!!|!!!",
            "PAIR_QR|${b64("x")}|${b64Bytes(publicKey)}|${b64Bytes(ByteArray(4))}", // short token
            "PAIR_REQ|Joe|key",
        ).forEach { assertNull("parsed: $it", QrPairWire.parseReq(it)) }
        assertNull(QrPairWire.parseAck("PAIR_QR_ACK|did:$deviceId"))
        assertNull(QrPairWire.parseFin("PAIR_QR_FIN"))
    }

    @Test
    fun `an oversized name in a request frame is rejected`() {
        val line = "PAIR_QR|${b64("n".repeat(64))}|${b64Bytes(publicKey)}|${b64Bytes(token)}|did:$deviceId"
        assertNull(QrPairWire.parseReq(line))
    }

    /**
     * ...but our own over-long device name must never be what rejects it. A user whose device name
     * exceeds the limit would otherwise have every pairing fail as "malformed" with nothing on
     * either screen to explain it.
     */
    @Test
    fun `an over-long local device name is truncated rather than rejected`() {
        val req = QrPairWire.parseReq(QrPairWire.req("n".repeat(64), publicKey, token, deviceId))
        org.junit.Assert.assertNotNull(req)
        assertEquals(PairQrPayload.MAX_NAME_LEN, req!!.deviceName.length)
    }

    // ---- host window --------------------------------------------------------------------------

    @Test
    fun `a window can be claimed exactly once`() {
        QrPairHost.open(EcdhStub.session(), token, now, "192.168.1.20", null)
        assertNotNull(QrPairHost.claim(token, now))
        assertNull("a second scanner must not get the same window", QrPairHost.claim(token, now))
    }

    @Test
    fun `a wrong token never claims the window`() {
        QrPairHost.open(EcdhStub.session(), token, now, "192.168.1.20", null)
        assertNull(QrPairHost.claim(ByteArray(16), now))
        assertNull(QrPairHost.claim(token.copyOf(15), now))
        // Still claimable by the genuine scanner afterwards.
        assertNotNull(QrPairHost.claim(token, now))
    }

    @Test
    fun `an expired window is not claimable`() {
        QrPairHost.open(EcdhStub.session(), token, now, "192.168.1.20", null)
        assertNull(QrPairHost.claim(token, now + PairQrPayload.VALIDITY_MS))
        assertFalse(QrPairHost.isOpen(now + PairQrPayload.VALIDITY_MS))
    }

    @Test
    fun `a restored window is claimable again`() {
        QrPairHost.open(EcdhStub.session(), token, now, "192.168.1.20", null)
        val claimed = QrPairHost.claim(token, now)!!
        QrPairHost.restore(claimed)
        assertNotNull(QrPairHost.claim(token, now))
    }

    @Test
    fun `closing the window denies every later dial-in`() {
        QrPairHost.open(EcdhStub.session(), token, now, "192.168.1.20", null)
        QrPairHost.close()
        assertNull(QrPairHost.claim(token, now))
        assertFalse(QrPairHost.isOpen(now))
    }

    @Test
    fun `opening a second window retires the first`() {
        val first = ByteArray(16) { 1 }
        QrPairHost.open(EcdhStub.session(), first, now, "192.168.1.20", null)
        QrPairHost.open(EcdhStub.session(), token, now, "192.168.1.20", null)
        assertNull("the superseded code must stop working", QrPairHost.claim(first, now))
        assertNotNull(QrPairHost.claim(token, now))
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun payload(name: String = "PC") = PairQrPayload.build(
        ip = "192.168.1.20", port = 65435, name = name, deviceId = deviceId,
        publicKey = publicKey, token = token, nowMs = now,
    )

    private fun b64(text: String) = b64Bytes(text.toByteArray(Charsets.UTF_8))

    private fun b64Bytes(bytes: ByteArray) =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun newP256PublicKey(): ByteArray {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair().public.encoded
    }

    /** The host window only needs a real ECDH session object; no agreement runs in these tests. */
    private object EcdhStub {
        fun session() = com.EntropicSoftwareLab.ClipboardShare.EcdhSession()
    }
}

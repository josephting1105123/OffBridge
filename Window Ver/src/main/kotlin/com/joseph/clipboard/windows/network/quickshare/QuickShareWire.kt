package com.joseph.clipboard.windows.network.quickshare

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64

/** TCP framing: a 4-byte big-endian length, then one protobuf message. */
object QuickShareFraming {
    /** Cap before the encrypted channel exists. Handshake messages are a few hundred bytes. */
    const val MAX_HANDSHAKE_FRAME = 64 * 1024

    /** Cap afterwards. Android sends file chunks of about 512 KiB. */
    const val MAX_FRAME = 5 * 1024 * 1024

    /**
     * Reads one frame. Returns null on a clean end of stream between frames; throws on a stream
     * that ends inside a frame or on a length outside 1..[maxLength] (checked before allocating).
     */
    fun read(input: InputStream, maxLength: Int): ByteArray? {
        val first = input.read()
        if (first == -1) return null
        val data = DataInputStream(input)
        var length = first
        repeat(3) {
            val next = data.read()
            if (next == -1) throw EOFException("stream ended inside a frame header")
            length = (length shl 8) or next
        }
        if (length <= 0 || length > maxLength) {
            throw QuickShareProtocolException("bad frame length $length")
        }
        return ByteArray(length).also { data.readFully(it) }
    }

    fun write(output: OutputStream, payload: ByteArray) {
        val framed = ByteArray(payload.size + 4)
        framed[0] = (payload.size ushr 24).toByte()
        framed[1] = (payload.size ushr 16).toByte()
        framed[2] = (payload.size ushr 8).toByte()
        framed[3] = payload.size.toByte()
        payload.copyInto(framed, 4)
        output.write(framed)
        output.flush()
    }
}

/**
 * The two identifiers Quick Share reads from the mDNS advert, and the "endpoint info" blob that
 * appears both in the TXT record and in the sender's ConnectionRequest.
 */
object QuickShareEndpoint {
    const val SERVICE_TYPE = "_FC9F5ED42C8A._tcp.local."
    private const val DEVICE_TYPE_LAPTOP = 3

    /** Longest device name advertised, so the TXT entry stays under mDNS's 255-byte limit. */
    const val MAX_NAME_BYTES = 128

    private const val ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun randomEndpointId(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(4) { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)].code.toByte() }

    /** base64url(0x23, endpoint id, service id FC 9F 5E, 00 00), unpadded. */
    fun instanceName(endpointId: ByteArray): String {
        require(endpointId.size == 4) { "endpoint id must be 4 bytes" }
        val raw = byteArrayOf(0x23) + endpointId + byteArrayOf(0xFC.toByte(), 0x9F.toByte(), 0x5E, 0, 0)
        return base64Url(raw)
    }

    /**
     * Byte 0 is version (3 bits), hidden flag (1 bit, 0 = visible), device type (3 bits) and a
     * reserved bit. Then 16 bytes that only mean something to Google's account-based sharing, so
     * random is fine, then the length-prefixed UTF-8 name.
     */
    fun endpointInfo(deviceName: String, random: SecureRandom = SecureRandom()): ByteArray {
        val name = truncateUtf8(deviceName, MAX_NAME_BYTES).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { random.nextBytes(it) }
        return byteArrayOf((DEVICE_TYPE_LAPTOP shl 1).toByte()) + salt + byteArrayOf(name.size.toByte()) + name
    }

    /** The sender's name from its endpoint info, or null when it is absent or malformed. */
    fun parseDeviceName(info: ByteArray): String? {
        if (info.size < 18) return null
        if (info[0].toInt() and 0x10 != 0) return null // hidden: no plain-text name
        val length = info[17].toInt() and 0xFF
        if (length == 0 || info.size < 18 + length) return null
        return String(info, 18, length, Charsets.UTF_8)
    }

    /**
     * Makes a peer-chosen name safe to show in the accept prompt and history: no control or
     * formatting characters (which includes bidi overrides), and a bounded length.
     */
    fun displayName(raw: String?): String {
        val cleaned = raw.orEmpty()
            .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
            .trim()
        return truncateUtf8(cleaned, 64).ifBlank { "Quick Share" }
    }

    fun base64Url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        var used = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (used + bytes > maxBytes) break
            used += bytes
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }
}

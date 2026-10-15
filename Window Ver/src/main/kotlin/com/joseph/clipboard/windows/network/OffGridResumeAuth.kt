package com.joseph.clipboard.windows.network

import com.joseph.clipboard.windows.network.control.SessionCrypto

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Proof that an off-grid RESUME_REQUEST comes from the peer that completed the P2P_HELLO.
 *
 * An off-grid receiver resumes from an ephemeral, never-paired IP, so the LAN trust gate and AUTH2R
 * can't vet it. Before this, the sessionId alone admitted a resume: anyone who learned it (it travels
 * in the clear in P2P_WELCOME) could pull the rest of the files. Now the sender answers a resume with
 * a fresh nonce and streams only after the receiver returns an HMAC under the raw ECDH secret from
 * the original handshake. A new IP is fine; a missing key is not.
 *
 *   receiver → `RESUME_REQUEST|sid|idx|off|…|p2pauth`
 *   sender   → `P2P_RESUME_CHALLENGE|b64(nonce16)`
 *   receiver → `P2P_RESUME_PROOF|b64(HMAC-SHA256(K, "p2p-resume-v1" ‖ nonce ‖ utf8(sid)))`
 *
 * The sender refuses any off-grid resume without [FLAG]: issuing the challenge to an old receiver
 * would land the challenge line in its .part file as data, so an old receiver gets a clean close and
 * re-scans instead. Wire contract — byte-identical to Android `transport/OffGridResumeAuth.kt`, pinned
 * by OffGridResumeAuthVectorTest on both platforms.
 */
object OffGridResumeAuth {
    const val FLAG = "p2pauth"
    const val CHALLENGE = "P2P_RESUME_CHALLENGE"
    const val PROOF = "P2P_RESUME_PROOF"
    private const val NONCE_LEN = 16
    private const val MAX_LINE = 256
    private val LABEL = "p2p-resume-v1".toByteArray(Charsets.UTF_8)

    fun proof(key: ByteArray, nonce: ByteArray, sessionId: String): ByteArray =
        SessionCrypto.hmacSha256(key, LABEL + nonce + sessionId.toByteArray(Charsets.UTF_8))

    /**
     * Sender side. Writes the challenge and checks the proof line read from [input] — which must be
     * the same stream the RESUME_REQUEST header came from. False ⇒ close without streaming.
     */
    fun challenge(input: InputStream, output: OutputStream, key: ByteArray, sessionId: String): Boolean {
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        output.write("$CHALLENGE|${Base64.getEncoder().encodeToString(nonce)}\n".toByteArray(Charsets.UTF_8))
        output.flush()
        val line = readLine(input) ?: return false
        if (!line.startsWith("$PROOF|")) return false
        val got = try { Base64.getDecoder().decode(line.removePrefix("$PROOF|")) } catch (_: Exception) { return false }
        return MessageDigest.isEqual(got, proof(key, nonce, sessionId))
    }

    /**
     * Receiver side, called right after sending a RESUME_REQUEST carrying [FLAG]. Reads the challenge
     * byte by byte (no read-ahead, so the file stream that follows is untouched) and answers it.
     */
    fun answer(input: InputStream, output: OutputStream, key: ByteArray, sessionId: String): Boolean {
        val line = readLine(input) ?: return false
        if (!line.startsWith("$CHALLENGE|")) return false
        val nonce = try { Base64.getDecoder().decode(line.removePrefix("$CHALLENGE|")) } catch (_: Exception) { return false }
        if (nonce.size != NONCE_LEN) return false
        val p = Base64.getEncoder().encodeToString(proof(key, nonce, sessionId))
        output.write("$PROOF|$p\n".toByteArray(Charsets.UTF_8))
        output.flush()
        return true
    }

    /** Short capped line read; null on EOF, overlong line, or a peer that never sends '\n'. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) return buf.toString("UTF-8")
            buf.write(b)
            if (buf.size() > MAX_LINE) return null
        }
    }
}

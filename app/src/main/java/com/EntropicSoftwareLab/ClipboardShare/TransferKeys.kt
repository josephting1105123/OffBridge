package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import com.EntropicSoftwareLab.ClipboardShare.transport.DowngradePolicy

/**
 * Indicates a peer dropped AEAD capability after a previous authenticated transfer, 
 * implying a potential man-in-the-middle downgrade attack.
 */
class DowngradeBlockedException(message: String) : Exception(message)

object TransferKeys {
    /**
     * Receiver-side FileSeal TOFU trust gate (LAN only). Resolves the AES-GCM pairing key to seal
     * the plaintext stream. The receiver calls this to decide whether to request "fgcm" on
     * ACCEPT_OFFER / RESUME_REQUEST. (Off-grid never reaches here — it uses an ECDH token key, not a
     * stored pairing key. That no longer means off-grid is plaintext: since 3.7.3 it is sealed on its
     * own path via OffGridHandshake's SEALED_STREAM negotiation, which carries the ECDH secret in
     * TransferSession.p2pSealKey instead of going through this TOFU gate.)
     */
    fun fileKeyIfAead(context: Context, ip: String): ByteArray? {
        val key = SecurityVault.getKeyForNode(context, ip) ?: return null
        return when (DowngradePolicy.decide(
            peerOffersAead = TransferDaemon.discoveredNodes[ip]?.supportsAuthFile == true,
            pinned = SecurityVault.isFileGcmPinned(context, ip)
        )) {
            // TOFU: pin so a later FGCM1-strip on the unauthenticated discovery can't downgrade us.
            DowngradePolicy.Decision.SEAL -> { SecurityVault.pinFileGcm(context, ip); key }
            DowngradePolicy.Decision.PLAINTEXT -> null
            // Peer dropped FGCM1 after a prior sealed transfer ⇒ downgrade attempt. Fail closed.
            DowngradePolicy.Decision.BLOCK ->
                throw DowngradeBlockedException("FileSeal downgrade blocked for $ip (peer no longer advertises FGCM1)")
        }
    }

    /**
     * Sender-side counterpart to [fileKeyIfAead]: decides whether to seal the outgoing bulk stream.
     * [fgcmRequested] is true iff the receiver appended "fgcm" to ACCEPT_OFFER / RESUME_REQUEST.
     * Returns the pairing key to seal with, or null for a legitimate plaintext stream (the peer never
     * negotiated AEAD). Throws [DowngradeBlockedException] if the receiver dropped "fgcm" after a
     * prior sealed transfer — refusing to stream in the clear. The trust gate guarantees we hold the key.
     */
    fun senderFileKey(context: Context, ip: String, fgcmRequested: Boolean): ByteArray? {
        return when (DowngradePolicy.decide(
            peerOffersAead = fgcmRequested,
            pinned = SecurityVault.isFileGcmPinned(context, ip)
        )) {
            DowngradePolicy.Decision.SEAL -> {
                SecurityVault.pinFileGcm(context, ip)
                // The trust gate admitted this IP, but the key can still vanish between the gate
                // and here (unpair race, corrupt-keyset recreate). Returning null would be read
                // as "legitimate plaintext" and silently stream cleartext to a peer that asked
                // for AEAD — fail closed instead (XP-DEF-02).
                SecurityVault.getKeyForNode(context, ip)
                    ?: throw DowngradeBlockedException("FileSeal key missing for $ip after SEAL decision — refusing plaintext fallback")
            }
            DowngradePolicy.Decision.PLAINTEXT -> null
            DowngradePolicy.Decision.BLOCK ->
                throw DowngradeBlockedException("FileSeal downgrade blocked (sender) for $ip (receiver dropped fgcm)")
        }
    }

    /**
     * Receiver-side FGCM2 (v2 FileSeal) TOFU gate (XP-DEF-01). v2 layers on v1: it applies only when the
     * v1 key is available ([fgcm1Available]) AND the peer advertises FGCM2. Once a v2 stream has run with
     * a peer it is pinned; a later loss of FGCM2 that would silently force us back to v1 is treated as a
     * downgrade and blocked (fail closed), reusing [DowngradePolicy.decide] exactly like the FGCM1/GCM1
     * gates. Returns whether to negotiate v2. Off-grid (fgcm1Available=false) never reaches v2.
     */
    fun useFileV2(context: Context, ip: String, fgcm1Available: Boolean): Boolean {
        val peerOffersV2 = fgcm1Available && TransferDaemon.discoveredNodes[ip]?.supportsAuthFileV2 == true
        return when (DowngradePolicy.decide(
            peerOffersAead = peerOffersV2,
            pinned = SecurityVault.isFileGcmV2Pinned(context, ip)
        )) {
            DowngradePolicy.Decision.SEAL -> { SecurityVault.pinFileGcmV2(context, ip); true }
            DowngradePolicy.Decision.PLAINTEXT -> false
            DowngradePolicy.Decision.BLOCK ->
                throw DowngradeBlockedException("FileSeal v2 downgrade blocked for $ip (peer no longer advertises FGCM2)")
        }
    }

    /**
     * Sender-side counterpart to [useFileV2] (XP-DEF-01). v1 was enforced in both directions
     * ([senderFileKey]); v2 was only enforced receiver-side, so a stripped "fgcm2"→"fgcm" ACCEPT /
     * RESUME line silently sealed v1 instead of failing closed. [fgcm2Requested] is the receiver's
     * on-the-wire flag; a v2-pinned peer that dropped it is a downgrade → throw. Legacy/v1 peers never
     * sent "fgcm2" and were never v2-pinned → PLAINTEXT (v1), unchanged. Driven by the ACCEPT flag,
     * not discovery, so it carries no empty-cache false-positive.
     */
    fun senderUseFileV2(context: Context, ip: String, fgcm2Requested: Boolean): Boolean {
        return when (DowngradePolicy.decide(
            peerOffersAead = fgcm2Requested,
            pinned = SecurityVault.isFileGcmV2Pinned(context, ip)
        )) {
            DowngradePolicy.Decision.SEAL -> { SecurityVault.pinFileGcmV2(context, ip); true }
            DowngradePolicy.Decision.PLAINTEXT -> false
            DowngradePolicy.Decision.BLOCK ->
                throw DowngradeBlockedException("FileSeal v2 downgrade blocked (sender) for $ip (receiver dropped fgcm2)")
        }
    }
}

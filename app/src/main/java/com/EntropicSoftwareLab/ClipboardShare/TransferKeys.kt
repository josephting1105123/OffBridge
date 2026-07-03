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
     * stored pairing key, so it stays plaintext by construction.)
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
            DowngradePolicy.Decision.SEAL -> { SecurityVault.pinFileGcm(context, ip); SecurityVault.getKeyForNode(context, ip) }
            DowngradePolicy.Decision.PLAINTEXT -> null
            DowngradePolicy.Decision.BLOCK ->
                throw DowngradeBlockedException("FileSeal downgrade blocked (sender) for $ip (receiver dropped fgcm)")
        }
    }
}

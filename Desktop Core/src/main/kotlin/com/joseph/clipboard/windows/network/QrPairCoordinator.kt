package com.joseph.clipboard.windows.network

import com.joseph.clipboard.windows.storage.DiagLog
import com.joseph.clipboard.windows.storage.EcdhSession
import com.joseph.clipboard.windows.storage.SecurityVault

/**
 * Owns the "pair with QR" window on Windows: the ephemeral key half in [QrPairHost] and the payload
 * the dialog renders. Mirror of Android `QrPairSession`.
 *
 * QR pairing is same-network only. The code carries this PC's LAN address and nothing else, so both
 * devices have to already be on one Wi-Fi. Raising a Wi-Fi Direct group so a phone could pair
 * off-grid was tried and removed: on many adapters the group knocks the PC off its own network, it
 * costs seconds of spin-up on every open, and it is a second radio path to get wrong in the one flow
 * that has to be reliable — a pairing that half-works leaves nothing usable behind.
 *
 * Off-grid transfer is unaffected and still needs no pairing: the send window raises its own group
 * and carries its key in the QR ([QrHostCoordinator]), so it never consults a trust record.
 */
class QrPairCoordinator {

    /** What the dialog needs to draw: the QR text and when it goes stale. */
    data class PairQrInfo(
        val qrText: String,
        val expiresAtMs: Long,
    )

    /**
     * Opens a fresh pairing window, retiring any previous one, and returns what to render. Null when
     * this PC has no LAN address: the code would name an address the phone cannot dial, and a QR
     * nobody can act on is worse than an error message.
     */
    fun open(): PairQrInfo? {
        val lanIp = Net.localLanIpv4()
        if (lanIp == null) {
            DiagLog.w("QR pair: no LAN address on this PC — pairing needs both devices on one network")
            close()
            return null
        }

        val now = System.currentTimeMillis()
        val session = QrPairHost.open(
            ecdh = EcdhSession(),
            token = PairQrPayload.newToken(),
            nowMs = now,
            advertisedIp = lanIp,
            lanIp = lanIp,
        )

        val payload = PairQrPayload.build(
            ip = lanIp,
            port = Ports.PAIRING,
            // The name rides a '|'-separated frame after base64, but strip delimiters anyway so the
            // decoded value can't desync a peer that logs or re-emits it.
            name = SecurityVault.getDeviceName()
                .replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim(),
            deviceId = SecurityVault.localDeviceId(),
            publicKey = session.ecdh.getPublicKey(),
            token = session.token,
            nowMs = now,
            // No AP is raised for pairing, so there are no credentials to carry, and the LAN address
            // is already the dial target rather than a second route. The wire fields stay in
            // PairQrPayload: an older peer still populates them and a scanner meeting one should keep
            // understanding it.
            ssid = null,
            psk = null,
            lanIp = null,
        )

        return PairQrInfo(
            qrText = payload.encode(),
            expiresAtMs = session.expiresAtMs,
        )
    }

    /** Closes the window (dialog dismissed or pairing done). */
    fun close() {
        QrPairHost.close()
    }
}

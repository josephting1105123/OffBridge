package com.EntropicSoftwareLab.ClipboardShare.pairing

import android.content.Context
import com.EntropicSoftwareLab.ClipboardShare.EcdhSession
import com.EntropicSoftwareLab.ClipboardShare.Net
import com.EntropicSoftwareLab.ClipboardShare.Ports
import com.EntropicSoftwareLab.ClipboardShare.SecurityVault
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon

/**
 * Host side of QR pairing on Android: opens the window in [QrPairHost] and produces the code to
 * render. Mirror of Windows `QrPairCoordinator`.
 *
 * QR pairing is same-network only. Both devices must already be on one Wi-Fi; the code carries this
 * device's LAN address and nothing else. Raising a local-only hotspot to pair off-grid was tried and
 * removed: it drops the phone off its own Wi-Fi, takes up to 14s to come up or refuse, and is a
 * second radio path to get wrong in the one flow that has to be reliable, since a failed pairing
 * leaves nothing usable behind. Off-grid transfer does not need it either — the send flow raises its
 * own group and carries the key in the QR, so it never depends on a pairing record.
 */
object QrPairSession {

    /** What the QR screen renders. */
    data class PairQrInfo(
        val qrText: String,
        val expiresAtMs: Long,
    )

    /** Failure reasons the screen turns into a message; success is a non-null [PairQrInfo]. */
    sealed class OpenResult {
        data class Ready(val info: PairQrInfo) : OpenResult()

        /** No LAN address — this device is not on a network a scanner could reach it on. */
        object NoRoute : OpenResult()
    }

    /**
     * Opens a fresh window, retiring any previous one. Returns [OpenResult.NoRoute] when this device
     * has no LAN address, since the code would then name an address nobody can dial.
     */
    fun open(context: Context): OpenResult {
        close()

        val lanIp = Net.localLanIpv4() ?: return OpenResult.NoRoute
        val advertisedIp = lanIp
        val now = System.currentTimeMillis()
        val session = QrPairHost.open(
            ecdh = EcdhSession(),
            token = PairQrPayload.newToken(),
            nowMs = now,
            advertisedIp = advertisedIp,
            lanIp = lanIp,
        )

        val payload = PairQrPayload.build(
            ip = advertisedIp,
            port = Ports.PAIRING,
            // The name rides a '|'-separated frame after base64, but strip delimiters anyway so the
            // decoded value can't desync a peer that logs or re-emits it.
            name = TransferDaemon.customDeviceName
                .replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim(),
            deviceId = SecurityVault.localDeviceId(context),
            publicKey = session.ecdh.getPublicKey(),
            token = session.token,
            nowMs = now,
            // No AP is ever raised for pairing, so there are no credentials to carry and the LAN
            // address is already the dial target. The wire fields stay in PairQrPayload: an older
            // peer still populates them, and a scanner that meets one should keep understanding it.
            ssid = null,
            psk = null,
            lanIp = null,
        )

        return OpenResult.Ready(
            PairQrInfo(
                qrText = payload.encode(),
                expiresAtMs = session.expiresAtMs,
            )
        )
    }

    /** Closes the window. Idempotent. */
    fun close() {
        QrPairHost.close()
    }
}

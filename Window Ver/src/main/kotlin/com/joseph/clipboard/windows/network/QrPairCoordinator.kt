package com.joseph.clipboard.windows.network

import com.joseph.clipboard.windows.storage.EcdhSession
import com.joseph.clipboard.windows.storage.SecurityVault

/**
 * Owns the "pair with QR" window on Windows: the ephemeral key half in [QrPairHost], the payload the
 * dialog renders, and the off-grid AP when there's no shared network to pair over.
 *
 * The AP is raised only when this machine has no LAN address of its own. Starting one
 * unconditionally would be worse than useless in the common case — the phone is already on the same
 * Wi-Fi, and on many adapters raising a Direct group knocks the PC off its own network. The user can
 * still force one from the dialog ([open] with `forceHotspot`) for the genuinely off-grid case where
 * the PC is on a wired LAN the phone can't reach.
 *
 * [releaseHotspot] is the file-transfer coordinator's idle check, so closing this window never drops
 * an AP a live transfer is still streaming over.
 */
class QrPairCoordinator(private val releaseHotspot: () -> Unit) {

    /** What the dialog needs to draw: the QR text, the AP creds to print, and when it goes stale. */
    data class PairQrInfo(
        val qrText: String,
        val ssid: String?,
        val passphrase: String?,
        val hotspotActive: Boolean,
        val expiresAtMs: Long,
    )

    @Volatile private var hotspotRaisedHere = false

    /**
     * Opens a fresh pairing window, retiring any previous one, and returns what to render. Null when
     * this machine can offer neither a LAN address nor an AP — there would be nothing for the phone
     * to connect to, and a QR nobody can act on is worse than an error message.
     *
     * Blocks for up to ~9s when it raises the AP, so call it off the UI thread.
     */
    fun open(forceHotspot: Boolean = false): PairQrInfo? {
        val lanIp = Net.localLanIpv4()
        val hotspot = if (forceHotspot || lanIp == null) {
            HotspotController.start()?.also { hotspotRaisedHere = true }
        } else {
            null
        }
        if (lanIp == null && hotspot == null) {
            close()
            return null
        }

        // Dial address: the AP gateway when we raised one (a phone that joins the group can only
        // reach us there), else our LAN address.
        val advertisedIp = hotspot?.gatewayIp ?: lanIp!!
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
            name = SecurityVault.getDeviceName()
                .replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim(),
            deviceId = SecurityVault.localDeviceId(),
            publicKey = session.ecdh.getPublicKey(),
            token = session.token,
            nowMs = now,
            ssid = hotspot?.ssid,
            psk = hotspot?.passphrase,
            // Only worth advertising as a separate route when it isn't the address we already
            // published as the dial target.
            lanIp = lanIp?.takeIf { it != advertisedIp },
        )

        return PairQrInfo(
            qrText = payload.encode(),
            ssid = hotspot?.ssid,
            passphrase = hotspot?.passphrase,
            hotspotActive = hotspot != null,
            expiresAtMs = session.expiresAtMs,
        )
    }

    /** Closes the window (dialog dismissed or pairing done) and drops an AP raised only for it. */
    fun close() {
        QrPairHost.close()
        if (hotspotRaisedHere) {
            hotspotRaisedHere = false
            releaseHotspot()
        }
    }
}

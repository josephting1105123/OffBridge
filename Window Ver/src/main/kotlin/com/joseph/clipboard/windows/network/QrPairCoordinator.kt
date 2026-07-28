package com.joseph.clipboard.windows.network

import com.joseph.clipboard.windows.storage.DiagLog
import com.joseph.clipboard.windows.storage.EcdhSession
import com.joseph.clipboard.windows.storage.SecurityVault

/**
 * Owns the "pair with QR" window on Windows: the ephemeral key half in [QrPairHost], the payload the
 * dialog renders, and the off-grid AP.
 *
 * The connection ladder here is deliberately the same one the file-send window offers in
 * [QrHostCoordinator.startQrHostSession]: raise the AP, read the LAN address, publish both routes and
 * let the phone choose. Pairing previously raised the AP only when this machine had no LAN address of
 * its own, so a PC the phone could not reach on its LAN — different subnet, AP/client isolation, guest
 * VLAN, wired-only host — simply failed to pair, even though the very same PC would have offered a
 * working AP route had the user been sending a file instead. Nothing on the phone needed changing:
 * [QrPairClient] already tries lanIp, then the advertised address, then joins the AP. It just never
 * received credentials to use.
 *
 * Reading the LAN address AFTER the group is up is load-bearing rather than incidental. On adapters
 * where raising a Direct group drops the PC off its own network, the address we would otherwise have
 * advertised is already dead by then, so this ordering means a dead route is never published. The
 * phone still prefers the LAN address when it survives, which keeps trust records keyed to an address
 * that outlives the AP subnet.
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
     * Blocks for up to ~9s while the AP spins up, so call it off the UI thread.
     */
    fun open(): PairQrInfo? {
        // AP first, LAN second — the same order as the send path, and the order is the point.
        // lanBefore exists only to tell the two failure modes apart in the log: "this PC never had a
        // LAN address" and "raising the group took it away" look identical from lanIp alone, and the
        // second one is the case worth knowing about when a user reports pairing got slower.
        val lanBefore = Net.localLanIpv4()
        val hotspot = HotspotController.start()?.also { hotspotRaisedHere = true }
        val lanIp = Net.localLanIpv4()
        if (lanIp == null && hotspot == null) {
            close()
            return null
        }
        if (lanBefore != null && lanIp == null) {
            DiagLog.w(
                "QR pair: the Wi-Fi Direct group displaced this PC's LAN address ($lanBefore) — " +
                    "advertising the AP route only; the phone will have to join the hotspot"
            )
        }

        // Primary dial address: the AP gateway whenever a group is up, since a phone that joins it can
        // only reach us there. The LAN address rides along below as the second route rather than being
        // replaced — the phone tries it FIRST (QrPairClient), so the common same-Wi-Fi pair still
        // completes over the LAN and records the durable address, exactly as before this change.
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

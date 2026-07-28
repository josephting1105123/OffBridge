package com.EntropicSoftwareLab.ClipboardShare.pairing

import android.content.Context
import com.EntropicSoftwareLab.ClipboardShare.EcdhSession
import com.EntropicSoftwareLab.ClipboardShare.Net
import com.EntropicSoftwareLab.ClipboardShare.Ports
import com.EntropicSoftwareLab.ClipboardShare.SecurityVault
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import com.EntropicSoftwareLab.ClipboardShare.transport.FailureCode
import com.EntropicSoftwareLab.ClipboardShare.transport.hotspot.LocalOnlyHotspotHost
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Host side of QR pairing on Android: opens the window in [QrPairHost] and produces the code to
 * render. Mirror of Windows `QrPairCoordinator`.
 *
 * A local-only hotspot is raised only on request. Doing it automatically would drop the phone off
 * its own Wi-Fi for the overwhelmingly common case where both devices are already on the same
 * network and the LAN address is all the scanner needs.
 */
object QrPairSession {

    /** What the QR screen renders. */
    data class PairQrInfo(
        val qrText: String,
        val ssid: String?,
        val passphrase: String?,
        val hotspotActive: Boolean,
        val expiresAtMs: Long,
    )

    /** Failure reasons the screen turns into a message; success is a non-null [PairQrInfo]. */
    sealed class OpenResult {
        data class Ready(val info: PairQrInfo) : OpenResult()

        /** Neither a LAN address nor a hotspot — nothing a scanner could connect to. */
        object NoRoute : OpenResult()

        /** The hotspot was asked for and refused (permission, OEM limits, tethering already on). */
        object HotspotUnavailable : OpenResult()
    }

    private const val LOHS_BUDGET_MS = 12_000L

    @Volatile private var hotspot: LocalOnlyHotspotHost? = null

    /**
     * Opens a fresh window, retiring any previous one. Suspends for up to [LOHS_BUDGET_MS] when
     * [withHotspot] is set, so callers run it off the main dispatcher.
     *
     * The device SHOWING a code is the one that hosts an AP; the device scanning joins it. Two APs
     * would be nonsense, which is why the scan mode retires this window on the way in rather than
     * opening its own. Same split as the off-grid send flow, where the sender creates the group and
     * the scanner joins.
     *
     * [hotspotRequired] separates the two ways the AP gets asked for. The window opens with an attempt
     * every time, matching the send screen, which calls createGroup unconditionally — but an attempt
     * that fails must not cost a user a pairing that would have worked over the LAN. When the user
     * taps "start a hotspot" by name, silence would be the confusing outcome, so that path still
     * reports the failure.
     */
    suspend fun open(
        context: Context,
        withHotspot: Boolean,
        hotspotRequired: Boolean = true,
    ): OpenResult {
        close()

        // Read before the AP only to decide whether a failed AP is fatal. The address actually
        // advertised is re-read afterwards — see below.
        val lanBefore = Net.localLanIpv4()
        var apSsid: String? = null
        var apPsk: String? = null
        var apHostIp: String? = null

        if (withHotspot) {
            val host = LocalOnlyHotspotHost(context)
            val started = withTimeoutOrNull(LOHS_BUDGET_MS + 2_000L) {
                suspendCancellableCoroutine<Triple<String, String, String>?> { cont ->
                    host.start(LOHS_BUDGET_MS, object : LocalOnlyHotspotHost.Listener {
                        override fun onStarted(ssid: String, psk: String, hostIp: String) {
                            if (cont.isActive) cont.resume(Triple(ssid, psk, hostIp))
                        }

                        override fun onFailed(code: FailureCode) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                    cont.invokeOnCancellation { host.stop() }
                }
            }
            if (started == null) {
                host.stop()
                // Only fatal when the user asked for the AP outright, or when there is no LAN address
                // to fall back to and the AP was therefore the only possible route.
                if (hotspotRequired || lanBefore == null) return OpenResult.HotspotUnavailable
            } else {
                hotspot = host
                apSsid = started.first
                apPsk = started.second
                apHostIp = started.third
            }
        }

        // Re-read AFTER the AP attempt rather than reusing lanBefore. LocalOnlyHotspot commonly drops
        // the phone off the Wi-Fi it was on, so the address captured a moment ago may already be dead,
        // and publishing it would send a scanner at a route that no longer exists. Same ordering as
        // QrPairCoordinator on Windows.
        val lanIp = Net.localLanIpv4()

        // Dial address: the AP interface when we raised one (a scanner that joins the group can only
        // reach us there), else our address on the shared network.
        val advertisedIp = apHostIp ?: lanIp ?: return OpenResult.NoRoute
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
            ssid = apSsid,
            psk = apPsk,
            // Only worth a separate route token when it isn't the address already published as the
            // dial target.
            lanIp = lanIp?.takeIf { it != advertisedIp },
        )

        return OpenResult.Ready(
            PairQrInfo(
                qrText = payload.encode(),
                ssid = apSsid,
                passphrase = apPsk,
                hotspotActive = apHostIp != null,
                expiresAtMs = session.expiresAtMs,
            )
        )
    }

    /** Closes the window and drops a hotspot raised for it. Idempotent. */
    fun close() {
        QrPairHost.close()
        hotspot?.let {
            hotspot = null
            it.stop()
        }
    }
}

package com.joseph.clipboard.windows.network

import com.joseph.clipboard.windows.network.control.BleControlPeripheral
import com.joseph.clipboard.windows.network.control.ControlProtocol
import com.joseph.clipboard.windows.network.control.PcQrSenderController
import com.joseph.clipboard.windows.network.control.QrControlState
import com.joseph.clipboard.windows.storage.EcdhSession
import com.joseph.clipboard.windows.storage.SecurityVault
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the off-grid "send via QR" advertising window (Windows is always the sender): the one-shot
 * pending [OffGridQrSender.HostSession], the Wi-Fi Direct hotspot, and the optional BLE control
 * channel. Extracted from ClipboardAppController so the controller keeps only the transfer/history
 * work; the inbound P2P_HELLO dial handler stays there and drives this via [claimPendingSession] /
 * [restorePendingSession] / [notifyTransferStarted].
 *
 * [isTransferActive] lets [stopHotspotIfIdle] respect a live outgoing stream (the phone must stay
 * joined for the whole transfer) without this class knowing about the controller's session maps.
 * Wire format is unchanged and stays byte-identical to the Android scanner.
 */
class QrHostCoordinator(private val isTransferActive: () -> Boolean) {

    private val pendingQrSession = AtomicReference<OffGridQrSender.HostSession?>(null)
    // Monotonic "QR intent" counter. Bumped by start AND cancel so an in-flight startQrHostSession
    // can detect it was superseded (dialog dismissed / a newer QR opened) and not register a stale AP.
    private val qrEpoch = AtomicLong(0)

    @Volatile private var qrControl: PcQrSenderController? = null
    @Volatile private var qrPeripheral: BleControlPeripheral? = null

    @Volatile private var qrLanIp: String? = null
    // Build-level caps: this generation both waits for the delivery ack and can FileSeal the
    // off-grid stream. BLE_ADVERTISE is added per-session below, only once the GATT advert starts.
    @Volatile private var qrCap: Int =
        OffGridQrSender.CAP_COMPLETION_ACK or OffGridQrSender.CAP_SEALED_STREAM
    @Volatile private var qrGatewayIp: String = "192.168.49.1"

    /** Result of opening a QR host session: the QR payload plus the AP creds to display (null when LAN-only). */
    data class QrHostInfo(
        val qrJson: String,
        val ssid: String?,
        val passphrase: String?,
        val hotspotActive: Boolean,
        /**
         * Live control-plane state when the PC advertises a BLE control channel, else null. Non-null
         * lets the QR dialog show "phone connected — joining Wi-Fi…", regeneration prompts, and an
         * actionable failure instead of waiting silently forever.
         */
        val controlState: StateFlow<QrControlState>? = null,
        /**
         * Live AP creds — republished after a credential regeneration so the dialog can re-render the
         * QR and the manual-join hint. Null when there's no BLE control channel to trigger a regen.
         */
        val creds: StateFlow<Pair<String, String>>? = null
    )

    /**
     * Opens an off-grid advertising window and returns the QR payload to render. Stands up a Wi-Fi
     * Direct hotspot (for the true off-grid case) and/or advertises the LAN IP (for the same-network
     * case). Returns null when there are no real files, or when this machine can offer NEITHER a
     * hotspot NOR a shared LAN (nothing the phone could connect to).
     *
     * This blocks for ~1–4s while the hotspot spins up, so call it off the UI thread.
     */
    fun startQrHostSession(files: List<File>): QrHostInfo? {
        // Expand picked directories into their files first (this method already runs off the UI
        // thread); otherwise a selected folder was dropped by the isFile filter and an off-grid
        // "send folder" silently offered nothing.
        val realFiles = FileStreamer.expandSelectionToFiles(files).filter { it.exists() && it.isFile }
        if (realFiles.isEmpty()) return null

        val myEpoch = qrEpoch.incrementAndGet()
        // Retire any control channel from a prior QR window before opening a new one.
        qrControl?.closeWithAbort()
        qrControl = null
        qrPeripheral = null
        // Off-grid AP (null if the adapter lacks Wi-Fi Direct / Wi-Fi is off) + same-network LAN IP
        // (null off-grid). At least one is required to host.
        val hotspot = HotspotController.start()
        val lanIp = Net.localLanIpv4()
        if (hotspot == null && lanIp == null) {
            if (qrEpoch.get() == myEpoch) stopHotspotIfIdle()
            return null
        }
        // Superseded while we were starting (dialog dismissed or a newer QR opened) — don't register a
        // stale session; drop the AP we just started if nothing else needs it.
        if (qrEpoch.get() != myEpoch) {
            stopHotspotIfIdle()
            return null
        }

        val session = OffGridQrSender.HostSession(
            sessionId = UUID.randomUUID().toString(),
            ecdh = EcdhSession(),
            token = OffGridQrSender.newToken(),
            files = realFiles,
            // The name rides '|'-separated, LF-terminated OFFER/QR frames — strip delimiters
            // so a user-chosen name like "Joe|Work" can't desync the receiver's positional parse.
            deviceName = SecurityVault.getDeviceName()
                .replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(32)
        )
        pendingQrSession.set(session)

        // Off-grid hotspot mode only: stand up a BLE control channel so the phone can link the
        // instant it scans (parallel with its Wi-Fi join) and the PC gets live status + coordinated
        // retry. LAN-only sessions don't need it (the phone dials the LAN IP directly). Fail-soft:
        // if the adapter can't advertise, cap stays COMPLETION_ACK and the QR is unchanged from before.
        var cap = OffGridQrSender.CAP_COMPLETION_ACK or OffGridQrSender.CAP_SEALED_STREAM
        if (hotspot != null) {
            val peripheral = BleControlPeripheral()
            val advertising = try {
                peripheral.start(ControlProtocol.sessionServiceUuid(session.token))
            } catch (e: Exception) {
                System.err.println("QR: BLE control unavailable (${e.message}) — QR-only fallback")
                false
            }
            if (advertising && qrEpoch.get() == myEpoch) {
                cap = cap or OffGridQrSender.CAP_BLE_ADVERTISE
                val controller = PcQrSenderController(
                    channel = peripheral,
                    token = session.token,
                    ecdh = session.ecdh,
                    gatewayIp = hotspot.gatewayIp,
                    initialSsid = hotspot.ssid,
                    initialPsk = hotspot.passphrase,
                    regenerateCreds = {
                        HotspotController.restart()?.let { it.ssid to it.passphrase }
                    }
                )
                peripheral.onSubscribed = { controller.onCentralSubscribed() }
                controller.begin()
                qrPeripheral = peripheral
                qrControl = controller
            } else {
                peripheral.close() // superseded, or advertising never started
            }
        }

        qrLanIp = lanIp
        qrCap = cap
        qrGatewayIp = hotspot?.gatewayIp ?: qrGatewayIp
        return QrHostInfo(
            qrJson = OffGridQrSender.buildPayloadJson(session, hotspot, lanIp, cap),
            ssid = hotspot?.ssid,
            passphrase = hotspot?.passphrase,
            hotspotActive = hotspot != null,
            controlState = qrControl?.state,
            creds = qrControl?.creds
        )
    }

    /**
     * Rebuild the QR JSON for the still-open QR window with regenerated hotspot creds ([ssid]/[psk]).
     * The token, ECDH key and BLE service UUID are unchanged, so only the printed QR (and the manual-
     * join hint) need refreshing — a not-yet-linked phone rescans the fresh code. Null if the window
     * was already claimed or dismissed.
     */
    fun rebuildQrJson(ssid: String, psk: String): String? {
        val session = pendingQrSession.get() ?: return null
        val hotspot = HotspotController.HotspotInfo(ssid, psk, qrGatewayIp)
        return OffGridQrSender.buildPayloadJson(session, hotspot, qrLanIp, qrCap)
    }

    /** Tear down an unscanned QR window (dialog dismissed) so a stray dial-in is rejected. */
    fun cancelQrHostSession() {
        qrEpoch.incrementAndGet() // supersede any in-flight startQrHostSession
        pendingQrSession.set(null)
        // Sealed ABORT + kill the BLE helper BEFORE dropping the AP, so a linked phone fails fast
        // instead of joining a hotspot that's about to vanish.
        qrControl?.closeWithAbort()
        qrControl = null
        qrPeripheral = null
        stopHotspotIfIdle()
    }

    /**
     * Drop the Wi-Fi Direct AP once nothing needs it: no QR is being shown AND no outgoing transfer is
     * streaming. (The phone must stay joined for the whole transfer, so we never stop it mid-stream.)
     */
    fun stopHotspotIfIdle() {
        if (pendingQrSession.get() == null && !isTransferActive()) {
            HotspotController.stop()
        }
    }

    /** Atomically claim the one-shot QR window for an inbound dial. Null ⇒ nothing pending (busy). */
    fun claimPendingSession(): OffGridQrSender.HostSession? = pendingQrSession.getAndSet(null)

    /** Restore a claimed session if no newer QR replaced it, so a genuine retry can dial back in. */
    fun restorePendingSession(session: OffGridQrSender.HostSession) {
        pendingQrSession.compareAndSet(null, session)
    }

    /** Data plane is live — tell the control channel to stop coordinating and show "Transferring". */
    fun notifyTransferStarted() {
        qrControl?.notifyTransferStarted()
    }
}

package com.EntropicSoftwareLab.ClipboardShare.offgrid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.EntropicSoftwareLab.ClipboardShare.EcdhSession
import com.EntropicSoftwareLab.ClipboardShare.P2pAdvertisingContext
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.SecurityVault
import com.EntropicSoftwareLab.ClipboardShare.SkeletonHelper
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import com.EntropicSoftwareLab.ClipboardShare.data.db.IdentityKeyStore
import com.EntropicSoftwareLab.ClipboardShare.sendvia.TransferItemArgs
import com.EntropicSoftwareLab.ClipboardShare.sendvia.TransferItemExpander
import com.EntropicSoftwareLab.ClipboardShare.transport.SenderSessionController
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDescriptor
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag
import com.EntropicSoftwareLab.ClipboardShare.transport.wifidirect.P2pGroupManager
import java.net.Inet4Address
import java.net.NetworkInterface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sender screen: creates the Wi-Fi Direct group, renders the credential QR,
 * arms NFC (HCE) and drives the §2.2 sender state machine:
 *
 *   IDLE → CREATING_GROUP → ADVERTISING → (HANDSHAKE) → TRANSFERRING → TEARDOWN
 *                 │ busy/error                 │ 120 s no peer → EXPIRED (regen)
 *                 ▼
 *             ERROR(retryable)
 */
class QrDisplayFragment : Fragment() {

    private enum class SenderState { IDLE, CREATING_GROUP, ADVERTISING, TRANSFERRING, ERROR, TEARDOWN }

    private lateinit var tvStatus: TextView
    private lateinit var ivQrCode: ImageView
    private lateinit var pbQrLoading: ProgressBar
    private lateinit var skeletonQrContainer: FrameLayout
    private lateinit var tvNfcChip: TextView
    private lateinit var tvBandWarningChip: TextView
    private lateinit var tvAcceptHint: TextView
    private lateinit var tvHotspotSetup: TextView
    private lateinit var tvHotspotSpeedTip: TextView
    private var tvDiag: TextView? = null
    private lateinit var progressSection: LinearLayout
    private lateinit var pbTransfer: ProgressBar
    private lateinit var tvTransferProgress: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnCancel: Button
    private lateinit var btnDone: Button

    private var state = SenderState.IDLE
    private var groupManager: P2pGroupManager? = null
    /** V4 §3: coordinates the transport ladder over the BLE control channel. */
    private var senderController: SenderSessionController? = null
    private var groupSsid: String? = null
    private var groupPsk: String? = null
    private var groupOwnerIp: String? = null
    private var groupGoMac: String? = null
    private var groupFreq: Int? = null
    private var groupUsedBandPin: Boolean = true

    private val fragmentJob = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + fragmentJob)
    private var expiryJob: Job? = null
    private var graceJob: Job? = null
    private var progressJob: Job? = null

    /** Set once the live transport is handed to [OffGridLiveTransfer] and we leave for history —
     *  tells onDestroyView NOT to tear the transport down (the holder owns it until the transfer ends). */
    private var handedOff = false

    /** "Send via NFC" presentation: show the NFC tap-position guide instead of the QR code. The
     *  transport (HCE payload + Wi-Fi Direct controller) is identical — only the rendezvous visual differs. */
    private var nfcMode = false

    /** True once the credential payload is actually staged into the HCE service ([NdefHceService]).
     *  Before this the NFC guide must NOT invite a tap — the HCE would serve an empty/unformatted tag
     *  (group still coming up), which reads as nothing and silently fails the handshake. */
    private var nfcPayloadStaged = false

    private var manifest: List<Pair<String, Long>> = emptyList()
    private var fileUris: List<String> = emptyList()

    /** Deferred controller start across the BT-permission round-trip (API 31+). */
    private var pendingControllerStart: (() -> Unit)? = null
    private var btPermissionAskedOnce = false

    private val requestBtPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // Granted or denied, proceed — BleControlServer.start() degrades
            // cleanly to QR-only advertising when blocked.
            pendingControllerStart?.invoke()
            pendingControllerStart = null
        }

    companion object {
        private const val ADVERTISING_EXPIRY_MS = 120_000L
        private const val RESUME_GRACE_MS = 60_000L
        private const val QR_SIZE_PX = 800
        /** Brief dwell on the "complete" state before opening history details. */
        private const val COMPLETE_NAV_DELAY_MS = 1_200L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_qr_display, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvStatus = view.findViewById(R.id.tvQrStatus)
        ivQrCode = view.findViewById(R.id.ivQrCode)
        pbQrLoading = view.findViewById(R.id.pbQrLoading)
        skeletonQrContainer = view.findViewById(R.id.skeletonQrContainer)
        tvNfcChip = view.findViewById(R.id.tvNfcChip)
        tvBandWarningChip = view.findViewById(R.id.tvBandWarningChip)
        tvAcceptHint = view.findViewById(R.id.tvAcceptHint)
        tvHotspotSetup = view.findViewById(R.id.tvHotspotSetup)
        tvHotspotSpeedTip = view.findViewById(R.id.tvHotspotSpeedTip)
        tvDiag = view.findViewById(R.id.tvDiag)
        tvDiag?.setOnLongClickListener { copyDiag(); true }
        progressSection = view.findViewById(R.id.progressSection)
        pbTransfer = view.findViewById(R.id.pbTransfer)
        tvTransferProgress = view.findViewById(R.id.tvTransferProgress)
        btnRetry = view.findViewById(R.id.btnRetryGroup)
        btnCancel = view.findViewById(R.id.btnOffGridCancel)
        btnDone = view.findViewById(R.id.btnOffGridDone)

        // The QR must be scannable, so the screen stays on (edge case 17).
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        btnCancel.setOnClickListener { requireActivity().finish() }
        // "Done" is the affirmative dismiss of this passive QR/advertise screen; like Cancel it just
        // closes it. There is nothing distinct to "complete" here — a transfer that actually starts
        // hands itself off and navigates to the history page on its own (onPeerConnected), so both
        // buttons are only ever reached before that, where closing simply stops advertising.
        btnDone.setOnClickListener { requireActivity().finish() }
        btnRetry.setOnClickListener {
            btnRetry.visibility = View.GONE
            startGroup()
        }

        nfcMode = arguments?.getString(OffGridSendActivity.EXTRA_PRESENTATION) ==
            OffGridSendActivity.PRESENTATION_NFC
        view.findViewById<TextView>(R.id.tvOffGridTitle).setText(
            if (nfcMode) R.string.offgrid_send_title_nfc else R.string.offgrid_send_title_qr
        )
        if (nfcMode) setupNfcPresentation(view)

        updateNfcChip()
        updateHotspotSetupButton()

        // Expand folders / read sizes off-main, then bring the group up.
        val items = arguments?.let { TransferItemArgs.fromBundle(it) } ?: emptyList()
        SkeletonHelper.show(skeletonQrContainer, R.layout.skeleton_file_card, 1, horizontal = false)
        uiScope.launch {
            val appContext = requireContext().applicationContext
            val prepared = withContext(Dispatchers.IO) {
                val expanded = TransferItemExpander.expand(appContext, items)
                Pair(expanded.map { it.uri.toString() }, TransferItemExpander.buildManifest(appContext, expanded))
            }
            fileUris = prepared.first
            manifest = prepared.second
            if (manifest.isEmpty()) {
                showError(getString(R.string.offgrid_error_no_files), retryable = false)
                return@launch
            }
            startGroup()
        }
    }

    private fun startGroup() {
        // Hotspot/tethering on → the single Wi-Fi radio can't create a Wi-Fi Direct group, so DON'T
        // try (and never ask the user to retry or turn the hotspot off). Show the QR for the
        // handshake and bridge the transfer over the EXISTING hotspot network — create nothing new:
        //   • saved hotspot creds → embed them so the receiver auto-joins the hotspot, then transfers
        //   • no saved creds → LAN-only QR (receiver already on the hotspot, or it auto-joins once
        //     creds are saved) + a one-time offer to set creds up.
        // If the hotspot can't bridge, the ladder reports exhaustion with actionable guidance.
        // Checked before the Wi-Fi-enabled gate because the Wi-Fi STA is normally OFF while a
        // hotspot is on.
        TransportDiag.attach(requireContext())
        TransportDiag.reset()
        val hotspotIp = detectHotspotIp()
        TransportDiag.log("TX: send start — hotspot ${if (hotspotIp != null) "active @ $hotspotIp" else "off"}")
        if (hotspotIp != null) {
            val creds = SecurityVault.getHotspotCredentials(requireContext())
            if (creds != null) {
                enterAdvertisingHotspotCreds(creds.first, creds.second, hotspotIp)
            } else {
                enterAdvertisingLan(hotspotIp)
            }
            return
        }

        // Detect a disabled Wi-Fi radio before showing the QR screen (edge case 4).
        val wifiManager = requireContext().applicationContext
            .getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled) {
            showError(getString(R.string.offgrid_error_wifi_off), retryable = true)
            try {
                startActivity(Intent(Settings.Panel.ACTION_WIFI))
            } catch (_: Exception) {}
            return
        }

        state = SenderState.CREATING_GROUP
        tvStatus.text = getString(R.string.offgrid_status_creating_group)

        P2pSessionService.start(requireContext())

        val manager = groupManager ?: P2pGroupManager(requireContext()).also { groupManager = it }
        manager.createGroup(object : P2pGroupManager.Listener {
            override fun onGroupReady(
                ssid: String,
                passphrase: String,
                groupOwnerIp: String?,
                goDeviceAddress: String?,
                frequencyMhz: Int?,
                usedBandPin: Boolean
            ) {
                if (!isAdded) return
                TransportDiag.log("TX: Wi-Fi Direct group ready (ssid='$ssid' freq=$frequencyMhz bandPin=$usedBandPin)")
                groupSsid = ssid
                groupPsk = passphrase
                this@QrDisplayFragment.groupOwnerIp = groupOwnerIp
                groupGoMac = goDeviceAddress
                groupFreq = frequencyMhz
                groupUsedBandPin = usedBandPin
                enterAdvertising()
            }

            override fun onGroupError(message: String, retryable: Boolean) {
                if (!isAdded) return
                // Wi-Fi Direct is unavailable — commonly a hotspot on the single radio that the
                // up-front detection missed. Don't dead-end on Retry: bridge over the existing
                // hotspot / shared LAN and show the QR for the handshake. Only a genuine no-network
                // case (no bridge IP at all) falls through to the error.
                TransportDiag.log("TX: Wi-Fi Direct group ERROR ($message) — bridging over existing network")
                val bridgeIp = detectHotspotIp()
                    ?: com.EntropicSoftwareLab.ClipboardShare.Net.localLanIpv4()
                if (bridgeIp != null) {
                    val creds = SecurityVault.getHotspotCredentials(requireContext())
                    if (creds != null) {
                        enterAdvertisingHotspotCreds(creds.first, creds.second, bridgeIp)
                    } else {
                        enterAdvertisingLan(bridgeIp)
                    }
                } else {
                    showError(message, retryable)
                }
            }

            override fun onGroupLost() {
                if (!isAdded) return
                onGroupLostInternal()
            }
        })
    }

    /**
     * The framework channel died (root cause #2). While ADVERTISING the manager
     * auto-recreates the group and fires onGroupReady → enterAdvertising (fresh QR);
     * we just reflect the gap. Mid-transfer it routes to the existing paused/resume
     * machinery rather than silently regenerating credentials.
     */
    private fun onGroupLostInternal() {
        when (state) {
            SenderState.ADVERTISING -> {
                expiryJob?.cancel()
                state = SenderState.CREATING_GROUP
                ivQrCode.visibility = View.INVISIBLE
                tvBandWarningChip.visibility = View.GONE
                tvAcceptHint.visibility = View.GONE
                tvStatus.text = getString(R.string.offgrid_status_reestablishing)
                // HCE no longer has live creds for this dropped group — revert the NFC guide to
                // "Preparing" so the user doesn't tap into an empty/stale tag mid-reestablish.
                nfcPayloadStaged = false
                if (nfcMode) view?.let { refreshNfcSendState(it) }
            }
            SenderState.TRANSFERRING -> onTransferError(paused = true)
            else -> {}
        }
    }

    /**
     * The §3 control-channel listener, shared by the Wi-Fi Direct path and the two hotspot
     * advertising paths. [reAdvertise] re-enters the SAME advertising mode (fresh token/QR) if the
     * control link drops before a data route is up.
     */
    private fun newControllerListener(reAdvertise: () -> Unit) = object : SenderSessionController.Listener {
        override fun onControlLinked(receiverName: String) {
            if (!isAdded || state != SenderState.ADVERTISING) return
            // The receiver is live on BLE — freeze this QR/token (a regen would orphan the session).
            expiryJob?.cancel()
            tvStatus.text = getString(R.string.offgrid_status_control_linked, receiverName)
        }

        override fun onTierChanged(tier: TransportDescriptor, ordinal: Int, total: Int) {
            if (!isAdded || state != SenderState.ADVERTISING) return
            tvStatus.text = getString(R.string.offgrid_status_trying_method, ordinal, total)
        }

        override fun onRequireGroupTeardown(onDone: () -> Unit) {
            // T2/T3/T6 need the autonomous GO gone (a no-op when advertising over a hotspot). Signal
            // [onDone] only once removeGroup has settled so the ladder can join another group safely.
            // Do NOT clear the NFC payload here: this fires on tier escalation while we are STILL
            // advertising, and the HCE would then serve an empty NDEF (a tap reads nothing). The QR on
            // screen keeps serving this same payload all session, so NFC must too; the genuine teardown
            // paths (hand-off, teardownTransport) clear it. The creds may be slightly stale after a tier
            // change, but that's recoverable over the BLE control channel — same as the QR.
            val mgr = groupManager
            if (mgr != null) mgr.teardownAndSettle(onDone) else onDone()
        }

        override fun onLadderExhausted(
            reason: com.EntropicSoftwareLab.ClipboardShare.transport.LadderExhaustReason
        ) {
            if (!isAdded) return
            when (reason) {
                com.EntropicSoftwareLab.ClipboardShare.transport.LadderExhaustReason.SAVE_HOTSPOT_CREDS -> {
                    // We host a hotspot but the receiver can't auto-join without its password.
                    showError(getString(R.string.offgrid_error_save_hotspot_creds), retryable = true)
                    HotspotCredentialsDialog.show(requireContext()) { reAdvertise() }
                }
                com.EntropicSoftwareLab.ClipboardShare.transport.LadderExhaustReason.TURN_OFF_HOTSPOT -> {
                    // A hotspot in play can't be bridged — turning one off lets Wi-Fi Direct take over.
                    showError(getString(R.string.offgrid_error_turn_off_hotspot), retryable = true)
                    try {
                        startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    } catch (_: Exception) {}
                }
                com.EntropicSoftwareLab.ClipboardShare.transport.LadderExhaustReason.CONTROL_FAILED -> {
                    // Wi-Fi exhausted AND the BLE coordination link failed — name Bluetooth as the cause.
                    showError(getString(R.string.offgrid_error_control_failed), retryable = true)
                }
                else -> showError(getString(R.string.offgrid_error_all_methods), retryable = true)
            }
        }

        override fun onControlLost() {
            if (!isAdded || state != SenderState.ADVERTISING) return
            // §9 #11: revert to autonomous advertising. If the ladder tore the group down, rebuild
            // it (fresh QR via onGroupReady); otherwise re-arm this same advertising mode.
            if (senderController?.isGroupTornDown() == true) {
                startGroup()
            } else {
                tvStatus.text = getString(R.string.offgrid_status_waiting_for_scan)
                expiryJob?.cancel()
                expiryJob = uiScope.launch {
                    delay(ADVERTISING_EXPIRY_MS)
                    if (state == SenderState.ADVERTISING) reAdvertise()
                }
            }
        }
    }

    /** Fresh token + ephemeral key on every ADVERTISING entry (edge case 8). */
    private fun enterAdvertising() {
        val ssid = groupSsid ?: return
        val psk = groupPsk ?: return
        state = SenderState.ADVERTISING
        TransportDiag.log("TX: advertising Wi-Fi Direct group '$ssid' (freq=$groupFreq)")

        senderController?.teardown()
        val pair = com.example.josephclipboardclient.transport.OffGridAdvertiser.startAdvertising(
            context = requireContext(),
            uiScope = uiScope,
            ssid = ssid,
            psk = psk,
            hostIp = groupOwnerIp ?: OffGridPayload.GROUP_OWNER_IP,
            goMac = groupGoMac,
            freq = groupFreq,
            manifest = manifest,
            fileUris = fileUris,
            netKind = com.EntropicSoftwareLab.ClipboardShare.transport.NetKind.P2P_GROUP,
            softApBridge = false,
            isLanOnly = false,
            onPeerConnected = { onPeerConnected(it) },
            onTransferComplete = { onTransferComplete(it) },
            onTransferError = { onTransferError(it) },
            listener = newControllerListener { enterAdvertising() }
        )
        senderController = pair.first

        val payloadJson = pair.second.toJson()
        stageSendPayload(payloadJson)
        startControllerWithPermissions(pair.first)
        renderQr(payloadJson, R.string.offgrid_accept_hint)

        expiryJob?.cancel()
        expiryJob = uiScope.launch {
            delay(ADVERTISING_EXPIRY_MS)
            if (state == SenderState.ADVERTISING) {
                enterAdvertising()
            }
        }
    }

    /** Encode + show the QR for [payloadJson] off the main thread. [hintTextRes], if set, replaces
     *  the accept hint with a hotspot tip; null hides the hint. */
    private fun renderQr(payloadJson: String, hintTextRes: Int? = null) {
        // QR encode is O(M²) in module count — ~10 ms, but never on the main thread.
        uiScope.launch {
            // H-i: an oversized payload makes zxing throw WriterException; catch it on the
            // worker thread so it can't propagate out of the coroutine and crash the fragment.
            val bitmap = withContext(Dispatchers.Default) {
                try {
                    encodeQr(payloadJson)
                } catch (_: Exception) {
                    null
                }
            }
            if (!isAdded || state != SenderState.ADVERTISING) return@launch
            if (bitmap == null) {
                showError(getString(R.string.offgrid_error_transfer_failed), retryable = true)
                return@launch
            }
            SkeletonHelper.hide(skeletonQrContainer)
            pbQrLoading.visibility = View.GONE
            ivQrCode.setImageBitmap(bitmap)
            ivQrCode.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.offgrid_status_waiting_for_scan)
            updateBandWarningChip()
            if (hintTextRes != null) {
                tvAcceptHint.setText(hintTextRes)
                tvAcceptHint.visibility = View.VISIBLE
            } else {
                tvAcceptHint.visibility = View.GONE
            }
        }
    }

    /**
     * Advertise over an EXISTING hotspot WITH the user's saved credentials embedded: a NORMAL
     * payload (real ssid/psk + the hotspot gateway IP). A receiver not yet on the hotspot AUTO-JOINS
     * it via the existing SPECIFIER (WifiNetworkSpecifier) path; one already on it transfers
     * instantly over LAN. The QR remains mandatory — it also carries the transfer handshake.
     */
    private fun enterAdvertisingHotspotCreds(ssid: String, psk: String, hostIp: String) {
        groupSsid = ssid
        groupPsk = psk
        groupOwnerIp = hostIp
        groupGoMac = null
        groupFreq = null
        groupUsedBandPin = true
        state = SenderState.ADVERTISING
        TransportDiag.log("TX: advertising over saved hotspot creds '$ssid' @ $hostIp")

        senderController?.teardown()
        val pair = com.example.josephclipboardclient.transport.OffGridAdvertiser.startAdvertising(
            context = requireContext(),
            uiScope = uiScope,
            ssid = ssid,
            psk = psk,
            hostIp = hostIp,
            goMac = null,
            freq = null,
            manifest = manifest,
            fileUris = fileUris,
            netKind = com.EntropicSoftwareLab.ClipboardShare.transport.NetKind.SOFTAP,
            softApBridge = true,
            isLanOnly = false,
            onPeerConnected = { onPeerConnected(it) },
            onTransferComplete = { onTransferComplete(it) },
            onTransferError = { onTransferError(it) },
            listener = newControllerListener { enterAdvertisingHotspotCreds(ssid, psk, hostIp) }
        )
        senderController = pair.first

        val payloadJson = pair.second.toJson()
        stageSendPayload(payloadJson)
        startControllerWithPermissions(pair.first)
        renderQr(payloadJson, R.string.offgrid_status_hotspot_autojoin)

        // 120 s with no peer → fresh token + QR over the same hotspot.
        expiryJob?.cancel()
        expiryJob = uiScope.launch {
            delay(ADVERTISING_EXPIRY_MS)
            if (state == SenderState.ADVERTISING) {
                enterAdvertisingHotspotCreds(ssid, psk, hostIp)
            }
        }
    }

    /**
     * Finds this device's hotspot/tethering AP IPv4 if one is active, else null. A hotspot uses the
     * single Wi-Fi radio, so when it's on the Wi-Fi Direct GO usually can't be created — we bridge
     * over the hotspot network instead. Two signals:
     *   1. An AP-named interface (ap, swlan, softap, wlan1, or one ending in _ap) — an unambiguous SoftAP.
     *   2. A private IPv4 on a Wi-Fi-family interface WHILE the Wi-Fi STA is OFF: you can't be a
     *      Wi-Fi client with Wi-Fi off, so that address can only be a hotspot AP. This catches OEMs
     *      that run the SoftAP on wlan0 itself, and tether subnets beyond 192.168.x (10.x / 172.x).
     *      Gated on STA-off so a normal home-Wi-Fi connection is never mistaken for a hotspot.
     */
    private fun detectHotspotIp(): String? =
        com.EntropicSoftwareLab.ClipboardShare.Net.detectHotspotIp(requireContext())

    /**
     * Advertise over an EXISTING hotspot / shared network (no Wi-Fi Direct, no new hotspot): a
     * LAN-only QR/NFC payload (empty creds) carrying [hostIp]. A receiver already connected to the
     * hotspot transfers straight over it. Reuses the same token/key/data plane as the Wi-Fi Direct
     * path, so the handshake (TransferDaemon) is identical.
     */
    private fun enterAdvertisingLan(hostIp: String) {
        groupSsid = ""
        groupPsk = ""
        groupOwnerIp = hostIp
        groupGoMac = null
        groupFreq = null
        groupUsedBandPin = true
        state = SenderState.ADVERTISING
        TransportDiag.log("TX: advertising LAN-only @ $hostIp (no Wi-Fi creds; receiver must already be on this network — save hotspot creds to enable auto-join)")

        senderController?.teardown()
        val pair = com.example.josephclipboardclient.transport.OffGridAdvertiser.startAdvertising(
            context = requireContext(),
            uiScope = uiScope,
            ssid = "",
            psk = "",
            hostIp = hostIp,
            goMac = null,
            freq = null,
            manifest = manifest,
            fileUris = fileUris,
            netKind = com.EntropicSoftwareLab.ClipboardShare.transport.NetKind.P2P_GROUP,
            softApBridge = false,
            isLanOnly = true,
            onPeerConnected = { onPeerConnected(it) },
            onTransferComplete = { onTransferComplete(it) },
            onTransferError = { onTransferError(it) },
            listener = newControllerListener { enterAdvertisingLan(hostIp) }
        )
        senderController = pair.first

        val payloadJson = pair.second.toJson()
        stageSendPayload(payloadJson)
        startControllerWithPermissions(pair.first)
        renderQr(payloadJson, R.string.offgrid_status_hotspot_lan)

        // 120 s with no peer → fresh token + QR over the same hotspot.
        expiryJob?.cancel()
        expiryJob = uiScope.launch {
            delay(ADVERTISING_EXPIRY_MS)
            if (state == SenderState.ADVERTISING) {
                enterAdvertisingLan(hostIp)
            }
        }
    }

    /**
     * The runtime Bluetooth pair (API 31+) gates the control channel; asked at
     * most once per screen. Denial is not an error — the controller's BLE
     * start simply fails closed and the session is QR-credentials-only.
     */
    private fun startControllerWithPermissions(controller: SenderSessionController) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter {
                ContextCompat.checkSelfPermission(requireContext(), it) !=
                        PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty() && !btPermissionAskedOnce) {
                btPermissionAskedOnce = true
                pendingControllerStart = {
                    if (isAdded && state == SenderState.ADVERTISING) controller.start()
                }
                requestBtPermissions.launch(missing.toTypedArray())
                return
            }
        }
        controller.start()
    }

    private fun encodeQr(content: String): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX, hints)
        val pixels = IntArray(QR_SIZE_PX * QR_SIZE_PX)
        for (y in 0 until QR_SIZE_PX) {
            for (x in 0 until QR_SIZE_PX) {
                pixels[y * QR_SIZE_PX + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, QR_SIZE_PX, QR_SIZE_PX, Bitmap.Config.RGB_565)
    }

    private fun onPeerConnected(deviceName: String) {
        if (!isAdded) return
        state = SenderState.TRANSFERRING
        expiryJob?.cancel()
        graceJob?.cancel()
        // The handshake created the outgoing session before this callback fired (TransferDaemon
        // handleP2pHello), so it is already in activeSessions. Hand the live Wi-Fi Direct group off
        // and leave for the transfer-history details page — the upload keeps streaming in the
        // background. If we somehow can't find the session, fall back to the resident progress UI.
        val sessionId = TransferDaemon.activeSessions.values
            .find { it.transport == "p2p" && !it.isIncoming }?.sessionId
        if (sessionId != null) {
            handOffAndOpenHistory(sessionId)
            return
        }
        tvAcceptHint.visibility = View.GONE
        tvBandWarningChip.visibility = View.GONE
        tvStatus.text = getString(R.string.offgrid_status_sending, deviceName, manifest.size)
        progressSection.visibility = View.VISIBLE
        startProgressPolling()
    }

    /**
     * Transfer started → hand the live Wi-Fi Direct group + control session to the process-lifetime
     * [OffGridLiveTransfer] holder (so leaving this screen doesn't tear the link down), then finish to
     * the transfer-history details page. The holder runs this teardown when the upload reaches a
     * terminal state (driven from TransferDaemon's finally → finishIfArmed). Captures only app context
     * + the transport objects, never the fragment.
     */
    private fun handOffAndOpenHistory(sessionId: String) {
        if (!isAdded) return
        val appCtx = requireContext().applicationContext
        val controller = senderController
        val group = groupManager
        OffGridLiveTransfer.arm(sessionId) {
            com.example.josephclipboardclient.transport.OffGridAdvertiser.stopAdvertising()
            NdefHceService.clearPayload()
            controller?.teardown()
            group?.teardown()
            P2pSessionService.stop(appCtx)
        }
        handedOff = true
        openHistoryDetails(sessionId)
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = uiScope.launch {
            val totalBytes = manifest.sumOf { it.second }.coerceAtLeast(1L)
            while (isActive) {
                val session = TransferDaemon.activeSessions.values
                    .find { it.transport == "p2p" && !it.isIncoming }
                if (session != null) {
                    var sent = session.currentOffset
                    for (i in 0 until session.currentFileIndex.coerceAtMost(manifest.size)) {
                        sent += manifest[i].second
                    }
                    val pct = ((sent.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                    pbTransfer.progress = pct
                    tvTransferProgress.text = getString(
                        R.string.offgrid_progress_format,
                        pct, sent / 1048576.0, totalBytes / 1048576.0
                    )
                }
                delay(500)
            }
        }
    }

    private fun onTransferComplete(sessionId: String) {
        if (!isAdded) return
        state = SenderState.TEARDOWN
        progressJob?.cancel()
        pbTransfer.progress = 100
        tvStatus.text = getString(R.string.offgrid_status_complete)
        tvTransferProgress.text = getString(R.string.offgrid_status_complete)
        btnCancel.text = getString(R.string.action_close)
        // Land on the transfer-history details page for this session, matching the
        // normal send flow. Brief pause so the "complete" state is visible first.
        uiScope.launch {
            delay(COMPLETE_NAV_DELAY_MS)
            openHistoryDetails(sessionId)
        }
    }

    /** Opens MainActivity focused on [sessionId]'s history details (same intent the
     *  completion notification uses), then finishes this off-grid screen. */
    private fun openHistoryDetails(sessionId: String) {
        if (!isAdded) return
        val intent = Intent(requireContext(), com.EntropicSoftwareLab.ClipboardShare.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("SHOW_HISTORY", true)
            putExtra("HISTORY_SESSION_ID", sessionId)
            // Reached on transfer start (hand-off) and on completion → we are leaving the send screen
            // either way, so clear the picked files for a clean next send.
            putExtra("CLEAR_SEND_SELECTION", true)
        }
        startActivity(intent)
        requireActivity().finish()
    }

    private fun onTransferError(paused: Boolean) {
        if (!isAdded) return
        progressJob?.cancel()
        if (paused) {
            // Keep the group alive for a 60 s resume grace window (edge case 11);
            // after that, teardown — the session persists in history as paused.
            tvStatus.text = getString(R.string.offgrid_status_interrupted)
            graceJob?.cancel()
            graceJob = uiScope.launch {
                val deadline = System.currentTimeMillis() + RESUME_GRACE_MS
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(1000)
                    val session = TransferDaemon.activeSessions.values
                        .find { it.transport == "p2p" && !it.isIncoming }
                    if (session != null && !session.isPaused) {
                        // Receiver resumed via RESUME_REQUEST.
                        onPeerConnected(getString(R.string.unknown_device))
                        return@launch
                    }
                }
                if (isActive && isAdded) {
                    tvStatus.text = getString(R.string.offgrid_status_rescan_to_resume)
                    btnCancel.text = getString(R.string.action_close)
                    teardownTransport()
                }
            }
        } else {
            showError(getString(R.string.offgrid_error_transfer_failed), retryable = true)
        }
    }

    private fun showError(message: String, retryable: Boolean) {
        state = SenderState.ERROR
        SkeletonHelper.hide(skeletonQrContainer)
        pbQrLoading.visibility = View.GONE
        ivQrCode.visibility = View.INVISIBLE
        tvBandWarningChip.visibility = View.GONE
        tvAcceptHint.visibility = View.GONE
        tvStatus.text = message
        btnRetry.visibility = if (retryable) View.VISIBLE else View.GONE
    }

    /**
     * Warns when the group is on a band the receiver's picker may not scan
     * (root cause #1): the 2.4 GHz band pin was ignored/fell back, or the group
     * actually came up above 2.5 GHz.
     */
    private fun updateBandWarningChip() {
        val freq = groupFreq
        val showWarning = freq != null && (!groupUsedBandPin || freq > 2500)
        if (showWarning) {
            tvBandWarningChip.text = getString(R.string.offgrid_band_warning, freq)
            tvBandWarningChip.visibility = View.VISIBLE
        } else {
            tvBandWarningChip.visibility = View.GONE
        }
    }

    /**
     * "Send via NFC" presentation: hide the QR and its chip, show the same tap-position guide the
     * receive flow uses (auto-detected antenna spot). HCE + the transport controller still run — only
     * the visual changes. [refreshNfcSendState] keeps the hint honest about the NFC adapter state.
     */
    private fun setupNfcPresentation(view: View) {
        view.findViewById<View>(R.id.qrFrame).visibility = View.GONE
        SkeletonHelper.hide(skeletonQrContainer)
        tvNfcChip.visibility = View.GONE
        view.findViewById<View>(R.id.sendNfcGuideSection).visibility = View.VISIBLE
        view.findViewById<NfcTapGuideView>(R.id.sendNfcGuideView).antennaFraction =
            NfcAntennaLocator.antennaVerticalFraction(requireContext())
        refreshNfcSendState(view)
    }

    /** Stage the credential payload into HCE; in NFC-send mode also flip the guide hint to "Ready"
     *  (a tap before this would read an empty tag — see [nfcPayloadStaged]). */
    private fun stageSendPayload(payloadJson: String) {
        NdefHceService.stagePayload(payloadJson)
        nfcPayloadStaged = true
        if (nfcMode) view?.let { refreshNfcSendState(it) }
    }

    private fun refreshNfcSendState(view: View) {
        val hint = view.findViewById<TextView>(R.id.sendNfcGuideHint)
        val adapter = NfcAdapter.getDefaultAdapter(requireContext())
        when {
            adapter == null -> {
                hint.text = getString(R.string.offgrid_nfc_unavailable)
                hint.setOnClickListener(null)
            }
            !adapter.isEnabled -> {
                hint.text = getString(R.string.offgrid_nfc_off)
                hint.setOnClickListener {
                    try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (_: Exception) {}
                }
            }
            // Link not up yet — DON'T invite a tap: the HCE has no payload staged, so it would serve
            // an empty tag and the handshake would silently fail.
            !nfcPayloadStaged -> {
                hint.text = getString(R.string.offgrid_nfc_preparing)
                hint.setOnClickListener(null)
            }
            else -> {
                val zone = when (NfcAntennaLocator.zoneFor(requireContext())) {
                    NfcZone.TOP -> R.string.nfc_hint_auto_top
                    NfcZone.CENTER -> R.string.nfc_hint_auto_center
                    NfcZone.BOTTOM -> R.string.nfc_hint_auto_bottom
                }
                hint.text = "${getString(R.string.offgrid_nfc_ready_prefix)} ${getString(zone)}"
                hint.setOnClickListener(null)
            }
        }
    }

    private fun updateNfcChip() {
        // In NFC-send mode the full tap guide replaces the QR + chip; keep the chip hidden.
        if (nfcMode) { tvNfcChip.visibility = View.GONE; return }
        val adapter = NfcAdapter.getDefaultAdapter(requireContext())
        when {
            adapter == null -> {
                // No NFC hardware → QR-only mode (edge case 1).
                tvNfcChip.visibility = View.VISIBLE
                tvNfcChip.text = getString(R.string.offgrid_nfc_unavailable)
                tvNfcChip.setOnClickListener(null)
            }
            !adapter.isEnabled -> {
                tvNfcChip.visibility = View.VISIBLE
                tvNfcChip.text = getString(R.string.offgrid_nfc_off)
                tvNfcChip.setOnClickListener {
                    try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (_: Exception) {}
                }
            }
            else -> {
                tvNfcChip.visibility = View.VISIBLE
                tvNfcChip.text = getString(R.string.offgrid_nfc_ready)
                // NFC is on (HCE auto-stages the payload) — tap to see where to hold the phones.
                tvNfcChip.setOnClickListener {
                    startActivity(NfcTapGuideActivity.intent(requireContext(), NfcTapGuideActivity.ROLE_SEND))
                }
            }
        }
    }

    /**
     * The persistent hotspot-password button under the NFC chip — always visible. Its label
     * reflects whether creds are saved; the speed-tip caption shows only while unset (as
     * motivation). Tapping opens the shared dialog; on save/clear we refresh the label and, if a
     * hotspot is currently active, re-sync the live advertising to the new creds.
     */
    private fun updateHotspotSetupButton() {
        if (!isAdded) return
        // Shown in BOTH QR and NFC presentation: saving the hotspot password enables the SoftAP
        // auto-join path, which is what lets a transfer work when the two phones are NOT on the same
        // Wi-Fi network (the receiver auto-joins the sender's hotspot).
        tvHotspotSetup.visibility = View.VISIBLE
        val saved = SecurityVault.getHotspotCredentials(requireContext()) != null
        tvHotspotSetup.setText(
            if (saved) R.string.offgrid_hotspot_setup_saved
            else R.string.offgrid_hotspot_setup_set
        )
        tvHotspotSpeedTip.visibility = if (saved) View.GONE else View.VISIBLE
        tvHotspotSetup.setOnClickListener {
            HotspotCredentialsDialog.show(requireContext()) {
                if (!isAdded) return@show
                updateHotspotSetupButton()
                reAdvertiseAfterHotspotCredChange()
            }
        }
    }

    /**
     * After the dialog saves or clears creds, re-sync the live advertising IF we're currently
     * advertising over a detected hotspot. Wi-Fi Direct sessions are unaffected (no hotspot in
     * play) — the new creds are simply stored for next time. Safe no-op otherwise.
     */
    private fun reAdvertiseAfterHotspotCredChange() {
        if (!isAdded || state != SenderState.ADVERTISING) return
        val hotspotIp = detectHotspotIp() ?: return
        val creds = SecurityVault.getHotspotCredentials(requireContext())
        if (creds != null) {
            enterAdvertisingHotspotCreds(creds.first, creds.second, hotspotIp)
        } else {
            enterAdvertisingLan(hotspotIp)
        }
    }

    private fun copyDiag() {
        val snap = TransportDiag.snapshot()
        val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("transport-diag", snap))
        android.widget.Toast.makeText(requireContext(), R.string.offgrid_diag_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateNfcChip()
        updateHotspotSetupButton()
        if (nfcMode) view?.let { refreshNfcSendState(it) }
        enablePreferredHce()
        TransportDiag.listener = { snap -> view?.post { tvDiag?.text = snap } }
        tvDiag?.text = TransportDiag.snapshot()
    }

    override fun onPause() {
        super.onPause()
        disablePreferredHce()
        TransportDiag.listener = null
    }

    /**
     * Force OUR [NdefHceService] to be the foreground-preferred HCE while this send screen is up, so a
     * reader's SELECT AID routes to us even if another service/default would otherwise claim it.
     * Belt-and-suspenders alongside the private AID. Foreground-only API; safe no-op if unsupported.
     */
    private fun enablePreferredHce() {
        val adapter = NfcAdapter.getDefaultAdapter(requireContext()) ?: return
        try {
            CardEmulation.getInstance(adapter).setPreferredService(
                requireActivity(),
                android.content.ComponentName(requireContext(), NdefHceService::class.java)
            )
            TransportDiag.log("NFC: preferred HCE service set")
        } catch (_: Exception) {}
    }

    private fun disablePreferredHce() {
        val adapter = NfcAdapter.getDefaultAdapter(requireContext()) ?: return
        try {
            CardEmulation.getInstance(adapter).unsetPreferredService(requireActivity())
        } catch (_: Exception) {}
    }

    /** Deterministic teardown (edge case 16) — idempotent. */
    private fun teardownTransport() {
        com.example.josephclipboardclient.transport.OffGridAdvertiser.stopAdvertising()
        NdefHceService.clearPayload()
        senderController?.teardown()
        senderController = null
        groupManager?.teardown()
        context?.let { P2pSessionService.stop(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        state = SenderState.TEARDOWN
        fragmentJob.cancel()
        // When the transfer was handed off (we left for the history page mid-transfer),
        // OffGridLiveTransfer now owns the live group and tears it down on completion — tearing it
        // down here would kill the in-flight upload.
        if (!handedOff) teardownTransport()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

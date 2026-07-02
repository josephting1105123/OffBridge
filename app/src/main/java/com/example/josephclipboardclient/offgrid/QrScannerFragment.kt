package com.EntropicSoftwareLab.ClipboardShare.offgrid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.EntropicSoftwareLab.ClipboardShare.EcdhSession
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.applyModalPolish
import com.EntropicSoftwareLab.ClipboardShare.SkeletonHelper
import com.EntropicSoftwareLab.ClipboardShare.Net
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon
import com.EntropicSoftwareLab.ClipboardShare.TransferHistoryManager
import com.EntropicSoftwareLab.ClipboardShare.TransferRecord
import com.EntropicSoftwareLab.ClipboardShare.TransferSession
import com.EntropicSoftwareLab.ClipboardShare.TutorialOverlayView
import com.EntropicSoftwareLab.ClipboardShare.TutorialTour
import com.EntropicSoftwareLab.ClipboardShare.TutorialStep
import com.EntropicSoftwareLab.ClipboardShare.tuneForBulkTransfer
import com.EntropicSoftwareLab.ClipboardShare.transport.LanRoute
import com.EntropicSoftwareLab.ClipboardShare.transport.ReceiverSessionController
import com.EntropicSoftwareLab.ClipboardShare.transport.SocketConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.control.BleControlClient
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportCaps
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportConnection
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDescriptor
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDiag
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportRoute
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receiver screen: CameraX + ML Kit QR scanning and NFC reader mode run
 * simultaneously — a tap works on the same screen as the scan, no mode
 * switch. The first valid payload wins; both sources then disarm.
 *
 * V4 receiver state machine (§3):
 *   IDLE → SCANNING → PARSING → CONNECTING → HANDSHAKE → RECEIVING → TEARDOWN
 *
 * CONNECTING is the whole transport ladder: the [ReceiverSessionController]
 * either executes sender-coordinated TRY_TRANSPORT tiers (BLE control channel
 * up) or runs the local T1 → T0 → T2 ladder (degraded mode). The fragment is
 * a thin UI over it; the handshake runs over whatever [TransportRoute] wins.
 */
class QrScannerFragment : Fragment() {

    private enum class ReceiverState {
        IDLE, SCANNING, PARSING, CONNECTING, HANDSHAKE, RECEIVING, TEARDOWN, FAILED
    }

    private var state = ReceiverState.IDLE

    // NFC reading is delegated to a shared helper; the binder-thread callback
    // is hopped onto the UI scope before touching fragment state.
    private val nfcReader = NfcPayloadReader(
        onRawPayload = { raw -> uiScope.launch { onCandidatePayload(raw) } },
        // Accumulate read breadcrumbs (binder thread → hop to main) into whichever diagnostic area
        // is on screen, so a failed tap shows WHERE it stopped (e.g. "SELECT AID rejected").
        onEvent = { msg ->
            uiScope.launch {
                if (nfcDiagLines.size >= 6) nfcDiagLines.removeFirst()
                nfcDiagLines.addLast(msg)
                val joined = nfcDiagLines.joinToString("\n")
                receiveNfcDiag?.let { it.visibility = View.VISIBLE; it.text = joined }
                tvDiag?.text = joined
            }
        }
    )

    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var tvNfcChip: TextView
    private lateinit var messageCard: LinearLayout
    private lateinit var tvMessage: TextView
    private lateinit var btnMessageAction: Button
    private lateinit var confirmCard: LinearLayout
    private lateinit var tvConfirmTitle: TextView
    private lateinit var tvConfirmSubtitle: TextView
    private lateinit var receiveProgressSection: LinearLayout
    private lateinit var pbReceive: ProgressBar
    private lateinit var tvReceiveProgress: TextView
    private lateinit var btnReceive: Button
    private lateinit var btnConfirmCancel: Button
    private lateinit var skeletonCameraContainer: FrameLayout
    private lateinit var degradedBanner: LinearLayout
    private lateinit var tvDegradedBanner: TextView
    private lateinit var btnDegradedGrant: Button
    private lateinit var connectingOverlay: LinearLayout
    private lateinit var tvConnectingStatus: TextView
    private lateinit var hotspotWarningBanner: LinearLayout
    /** Session-scoped: suppresses the hotspot banner until the user leaves and returns (reset in onResume). */
    private var hotspotWarningDismissed = false
    private lateinit var receiveBlockedOverlay: View
    /** True while the hard-block overlay is up (hotspot ON + on another Wi-Fi) — suppresses scanning. */
    @Volatile private var receiveBlocked = false
    private var tvDiag: TextView? = null
    private var diagScroll: android.widget.ScrollView? = null

    private val payloadConsumed = AtomicBoolean(false)
    private var acceptedPayload: OffGridPayload? = null

    private var cameraProvider: ProcessCameraProvider? = null
    // M-13: vars (not val/by-lazy) so a reused fragment instance with a fresh view can
    // rebuild them in startCamera after onDestroyView shut them down. Restricting
    // formats cuts per-frame work materially.
    private var analysisExecutor = Executors.newSingleThreadExecutor()
    private var barcodeScanner = newBarcodeScanner()

    private fun newBarcodeScanner() =
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )

    /** Owns the control channel + ladder; one per Receive attempt. */
    private var sessionController: ReceiverSessionController? = null
    /** The live transport once a tier wins (specifier network, P2P join or BT link). */
    private var activeRoute: TransportRoute? = null
    /** Exact sender endpoint from DATA_READY — overrides candidate guessing (§9 #8). */
    private var exactSenderIp: String? = null
    private var exactSenderPort: Int? = null
    /** Stashed across the nearby-permission request round-trip. Null = the grant
     *  came from the up-front ask (no transfer in flight yet). */
    private var pendingFallbackPayload: OffGridPayload? = null
    /** Payload awaiting the one-time Bluetooth-permission ask on the PC-QR (SoftAP) path. */
    private var pendingBtControlPayload: OffGridPayload? = null
    private var activeSessionId: String? = null
    private var activeConnection: TransportConnection? = null

    /** Set once the live joiner is handed to [OffGridLiveTransfer] and we leave for history —
     *  tells onDestroyView NOT to tear the transport down (the holder owns it until the download ends). */
    private var handedOff = false

    /** NFC receive mode (PRESENTATION_NFC): swaps the camera for the tap-position guide while the SAME
     *  NFC reader stays armed and feeds onCandidatePayload exactly like a QR scan. Mirrors the send side. */
    private var nfcMode = false
    private var receiveNfcGuideSection: LinearLayout? = null
    private var receiveNfcGuideView: NfcTapGuideView? = null
    private var receiveNfcGuideHint: TextView? = null
    private var receiveNfcDiag: TextView? = null
    /** Last NFC read breadcrumbs for the on-screen diagnostic (newest last, capped) — so the real
     *  failure step stays visible instead of being overwritten by the final generic line. */
    private val nfcDiagLines = ArrayDeque<String>(6)

    private val fragmentJob = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + fragmentJob)
    private var progressJob: Job? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
                // Now that the camera dialog is dismissed, request the ladder permissions (no dialog
                // race) so the strong tiers are available for the denied-then-granted path too.
                ensureLadderPermissions()
            } else {
                // NFC reader mode still works as a fallback on the same screen (edge case 2).
                showMessage(
                    getString(R.string.offgrid_camera_denied),
                    getString(R.string.action_go_to_settings)
                ) {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", requireContext().packageName, null)
                        })
                    } catch (_: Exception) {}
                }
            }
        }

    /**
     * Transport-ladder permission bundle, asked ONCE up front (§3.1
     * permission-aware ordering): nearby/fine-location for the true-P2P tiers
     * (T1/T2) plus the runtime Bluetooth pair (API 31+) for the BLE control
     * channel and the T4 data tier. Either way the ladder runs — denials just
     * reduce it to the permissionless tiers.
     */
    private val requestFallbackPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val nearbyGranted = grants[nearbyPermission()] == true ||
                    ContextCompat.checkSelfPermission(requireContext(), nearbyPermission()) ==
                    PackageManager.PERMISSION_GRANTED
            val payload = pendingFallbackPayload
            pendingFallbackPayload = null

            // If the request couldn't show a dialog (nearby permanently denied / OEM-suppressed),
            // launch() returns immediately still-denied — the "Grant" button then appears dead and the
            // Nearby setting never changes (the reported bug). Escalate to the app's permission settings
            // (where the Nearby devices toggle lives) and DON'T start a doomed degraded ladder (which
            // would pop the flaky Wi-Fi picker over the settings screen); leave a retry for when they
            // return having toggled it on.
            if (!nearbyGranted && isPermanentlyDenied(missingLadderPermissions())) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.offgrid_nearby_grant_in_settings),
                    Toast.LENGTH_LONG
                ).show()
                openAppSettings()
                if (payload != null) {
                    // We've already opened settings; leave a clear RETRY for when they come back having
                    // toggled Nearby devices on (connectToGroup re-checks the permission). No redundant
                    // second "Go to Settings" action.
                    showMessage(
                        getString(R.string.offgrid_nearby_grant_in_settings),
                        getString(R.string.action_retry)
                    ) {
                        messageCard.visibility = View.GONE
                        connectToGroup(payload)
                    }
                    btnReceive.isEnabled = true
                } else {
                    updateDegradedBanner(false)
                }
                return@registerForActivityResult
            }

            if (payload != null) {
                // Post-scan retry: proceed into the ladder with whatever was granted.
                messageCard.visibility = View.GONE
                startTransportLadder(payload, nearbyGranted)
            } else {
                // Up-front ask (no transfer in flight): just reflect full vs. limited
                // mode so the user knows before they scan.
                updateDegradedBanner(nearbyGranted)
            }
        }

    /**
     * One-time Bluetooth-permission ask for the PC-QR (SoftAP) path. A PC that advertises a BLE
     * control channel lets the phone link the instant it scans — live PC status + coordinated retry
     * instead of the PC waiting blind. That needs BLUETOOTH_SCAN/CONNECT (API 31+), which the SoftAP
     * path never requested before (it only ever needed CHANGE_WIFI_STATE to join). Denial is fine:
     * [connectToGroup] proceeds into the same direct SoftAP join regardless of the grant, so the
     * transfer is never blocked on the answer.
     */
    private val requestBtControlPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            val payload = pendingBtControlPayload
            pendingBtControlPayload = null
            if (payload != null) startTransportLadder(payload, hasNearbyPermission = false)
        }

    private fun nearbyPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    /** BLUETOOTH_SCAN/CONNECT still missing (API 31+ only); the runtime gate for BLE control. */
    private fun missingBtControlPermissions(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .filter {
                ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
            }
    }

    private fun btControlPermRequested(): Boolean =
        requireContext().getSharedPreferences(PERM_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BT_CONTROL_PERM_REQUESTED, false)

    private fun markBtControlPermRequested() {
        requireContext().getSharedPreferences(PERM_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BT_CONTROL_PERM_REQUESTED, true).apply()
    }

    private fun ladderPermissions(): Array<String> {
        val perms = mutableListOf(nearbyPermission())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return perms.toTypedArray()
    }

    private fun missingLadderPermissions(): List<String> =
        ladderPermissions().filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

    private fun ladderPermRequested(): Boolean =
        requireContext().getSharedPreferences(PERM_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LADDER_PERM_REQUESTED, false)

    private fun markLadderPermRequested() {
        requireContext().getSharedPreferences(PERM_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LADDER_PERM_REQUESTED, true).apply()
    }

    /**
     * `shouldShowRequestPermissionRationale` is false BOTH before the first ask
     * and after a permanent "Don't ask again" denial, so the prefs flag
     * disambiguates: once we've fired the request, a still-missing permission that
     * can't show a rationale was permanently denied (or silently blocked, as MIUI
     * often does for NEARBY_WIFI_DEVICES).
     */
    private fun isPermanentlyDenied(missing: List<String>): Boolean {
        if (missing.isEmpty() || !ladderPermRequested()) return false
        return missing.any { !shouldShowRequestPermissionRationale(it) }
    }

    /**
     * Ask for the transport-ladder permissions up front so the no-dialog T0
     * credential join + BLE control channel can lead instead of falling through to
     * the flaky SPECIFIER picker. Launches the system prompt directly (no buried
     * "Continue" card). Caller must ensure no other permission dialog is pending.
     */
    private fun ensureLadderPermissions() {
        val missing = missingLadderPermissions()
        if (missing.isEmpty() || ladderPermRequested()) return
        pendingFallbackPayload = null
        markLadderPermRequested()
        requestFallbackPermission.launch(missing.toTypedArray())
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            })
        } catch (_: Exception) {}
    }

    private fun isMiui(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    /**
     * Part C: make degraded mode visible instead of silent. When the nearby
     * permission is missing the ladder is SPECIFIER-only, so surface a banner with
     * a one-tap Grant affordance (or an App-info deep link if permanently denied).
     */
    private fun updateDegradedBanner(hasNearbyPermission: Boolean) {
        if (!::degradedBanner.isInitialized) return
        if (hasNearbyPermission) {
            degradedBanner.visibility = View.GONE
            return
        }
        val sb = StringBuilder(getString(R.string.offgrid_degraded_mode))
        val bleOff = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) !=
                        PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) !=
                        PackageManager.PERMISSION_GRANTED)
        if (bleOff) sb.append(getString(R.string.offgrid_degraded_bt_off))
        tvDegradedBanner.text = sb.toString()
        degradedBanner.visibility = View.VISIBLE
        btnDegradedGrant.setOnClickListener {
            val missing = missingLadderPermissions()
            when {
                missing.isEmpty() -> updateDegradedBanner(true)
                isPermanentlyDenied(missing) -> openAppSettings()
                else -> {
                    pendingFallbackPayload = null
                    markLadderPermRequested()
                    requestFallbackPermission.launch(missing.toTypedArray())
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_qr_scanner, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        nfcMode = arguments?.getString(ARG_PRESENTATION) == OffGridSendActivity.PRESENTATION_NFC

        previewView = view.findViewById(R.id.cameraPreview)
        tvStatus = view.findViewById(R.id.tvScanStatus)
        tvNfcChip = view.findViewById(R.id.tvScannerNfcChip)
        messageCard = view.findViewById(R.id.scannerMessageCard)
        tvMessage = view.findViewById(R.id.tvScannerMessage)
        btnMessageAction = view.findViewById(R.id.btnScannerMessageAction)
        confirmCard = view.findViewById(R.id.confirmCard)
        tvConfirmTitle = view.findViewById(R.id.tvConfirmTitle)
        tvConfirmSubtitle = view.findViewById(R.id.tvConfirmSubtitle)
        receiveProgressSection = view.findViewById(R.id.receiveProgressSection)
        pbReceive = view.findViewById(R.id.pbReceive)
        tvReceiveProgress = view.findViewById(R.id.tvReceiveProgress)
        btnReceive = view.findViewById(R.id.btnConfirmReceive)
        btnConfirmCancel = view.findViewById(R.id.btnConfirmCancel)
        skeletonCameraContainer = view.findViewById(R.id.skeletonCameraContainer)
        degradedBanner = view.findViewById(R.id.degradedBanner)
        tvDegradedBanner = view.findViewById(R.id.tvDegradedBanner)
        btnDegradedGrant = view.findViewById(R.id.btnDegradedGrant)
        connectingOverlay = view.findViewById(R.id.connectingOverlay)
        tvConnectingStatus = view.findViewById(R.id.tvConnectingStatus)
        hotspotWarningBanner = view.findViewById(R.id.hotspotWarningBanner)
        view.findViewById<Button>(R.id.btnHotspotWarningDismiss).setOnClickListener {
            hotspotWarningBanner.visibility = View.GONE
            hotspotWarningDismissed = true
        }
        view.findViewById<Button>(R.id.btnHotspotWarningSettings).setOnClickListener {
            Net.openHotspotSettings(requireContext())
        }
        receiveBlockedOverlay = view.findViewById(R.id.receiveBlockedOverlay)
        view.findViewById<Button>(R.id.btnBlockedDisconnectWifi).setOnClickListener {
            Net.openWifiSettings(requireContext())
        }
        view.findViewById<Button>(R.id.btnBlockedTurnOffHotspot).setOnClickListener {
            Net.openHotspotSettings(requireContext())
        }
        tvDiag = view.findViewById(R.id.tvDiag)
        diagScroll = view.findViewById(R.id.diagScroll)
        tvDiag?.setOnLongClickListener { copyDiag(); true }

        if (nfcMode) {
            receiveNfcGuideSection = view.findViewById(R.id.receiveNfcGuideSection)
            receiveNfcGuideView = view.findViewById(R.id.receiveNfcGuideView)
            receiveNfcGuideHint = view.findViewById(R.id.receiveNfcGuideHint)
            receiveNfcDiag = view.findViewById(R.id.receiveNfcDiag)
        }

        btnConfirmCancel.setOnClickListener { requireActivity().finish() }

        // NFC tap from the Receive screen pre-fills the payload; feed it straight
        // into the validation path so the confirm card comes up without the camera.
        val preset = arguments?.getString(ARG_PRESET_PAYLOAD)
        val presetIsValid = preset != null &&
                OffGridPayload.parse(preset) is OffGridPayload.ParseResult.Valid

        if (preset != null) {
            // Legacy pre-filled payload (a prior NFC-tap handoff / deep link): straight into validation.
            onCandidatePayload(preset)
            ensureLadderPermissions()
        } else if (nfcMode) {
            // NFC receive: show the tap-position guide, never start the camera. The already-armed NFC
            // reader (onResume) feeds onCandidatePayload exactly like a QR scan → same confirm/connect.
            setupNfcReceivePresentation()
            ensureLadderPermissions()
        } else {
            // Camera QR-scan mode (default).
            val cameraGranted = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (cameraGranted) {
                startCamera()
                ensureLadderPermissions()
            } else {
                // ensureLadderPermissions() runs from the camera-permission callback to avoid two
                // permission dialogs racing in the same frame.
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        maybeShowFirstLaunchTip()
    }

    /**
     * NFC receive presentation: hide the camera surfaces and show the tap-position guide. The NFC
     * reader is armed by onResume (state == SCANNING), so a tap runs the IDENTICAL onCandidatePayload →
     * confirm → handshake → transfer path as a QR scan. Mirrors the send-side QrDisplayFragment.nfcMode.
     */
    private fun setupNfcReceivePresentation() {
        // Make the onResume NFC-arm guard (state == SCANNING) pass without starting the camera.
        state = ReceiverState.SCANNING
        previewView.visibility = View.GONE
        view?.findViewById<View>(R.id.scanReticle)?.visibility = View.GONE
        view?.findViewById<View>(R.id.scannerHeaderBlock)?.visibility = View.GONE
        skeletonCameraContainer.visibility = View.GONE
        tvNfcChip.visibility = View.GONE
        receiveNfcGuideSection?.visibility = View.VISIBLE
        receiveNfcGuideView?.antennaFraction =
            NfcAntennaLocator.antennaVerticalFraction(requireContext())
        refreshNfcReceiveHint()
    }

    /** Keep the NFC guide hint honest about the adapter state: unavailable / off (→ settings) / ready. */
    private fun refreshNfcReceiveHint() {
        val hint = receiveNfcGuideHint ?: return
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
            else -> {
                val zone = when (NfcAntennaLocator.zoneFor(requireContext())) {
                    NfcZone.TOP -> getString(R.string.nfc_hint_auto_top)
                    NfcZone.CENTER -> getString(R.string.nfc_hint_auto_center)
                    NfcZone.BOTTOM -> getString(R.string.nfc_hint_auto_bottom)
                }
                hint.text = "${getString(R.string.offgrid_nfc_receive_ready_prefix)} $zone"
                hint.setOnClickListener(null)
            }
        }
    }

    /**
     * Scanner tip. Standalone (no chain) it is a one-shot single step gated by
     * the SharedPreferences flag. When reached via the Receive-screen chain
     * ([ARG_TUTORIAL]) it renders as "3 / 3 — Done" with the offset applied.
     */
    private fun maybeShowFirstLaunchTip() {
        // No camera scan-tip in NFC receive mode — the guide replaces the camera + reticle (which the
        // tip would spotlight). The NFC path is never launched with the tutorial/forced-tour args.
        if (nfcMode) return
        val tutorialMode = arguments?.getBoolean(ARG_TUTORIAL) == true
        val forcedTour = arguments?.getBoolean(ARG_FORCED_TOUR) == true
        val prefs = requireContext().getSharedPreferences("FileBridgePrefs", Context.MODE_PRIVATE)

        if (!tutorialMode) {
            // The confirm card is already up for a preset payload — no scan tip needed.
            if (arguments?.getString(ARG_PRESET_PAYLOAD) != null) return
            if (prefs.getBoolean("scanner_tutorial_shown", false)) return
        }
        // The chained step 3 also sets this so a later direct scanner visit shows no duplicate tip.
        prefs.edit().putBoolean("scanner_tutorial_shown", true).apply()

        val rootView = requireActivity().window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val overlay = TutorialOverlayView(requireContext())
        rootView.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.start(
            listOf(
                TutorialStep(
                    requireView().findViewById(R.id.scanReticle),
                    getString(R.string.scanner_tutorial_step),
                    hideSkipButton = forcedTour,
                    forceTooltipAbove = true,
                    tooltipAboveSpotlight = true
                )
            ),
            // Forced tour: this is the FINAL step 22/22. Chained-from-Receive (non-forced): step 3/3.
            stepIndexOffset = when {
                forcedTour -> TutorialTour.OFFSET_SCANNER
                tutorialMode -> 2
                else -> 0
            },
            totalStepsOverride = when {
                forcedTour -> TutorialTour.TOTAL_STEPS
                tutorialMode -> 3
                else -> null
            },
            onComplete = when {
                forcedTour -> {
                    {
                        // Durably mark the whole tour complete BEFORE finishing, so completion
                        // survives a process death on the return to MainActivity. Tutorial-only;
                        // no transfer happens.
                        if (isAdded) {
                            TutorialTour.markCompleted(requireContext().applicationContext)
                            requireActivity().finish()
                        }
                    }
                }
                // Chained from the Receive screen (non-forced): step 3 is the last receive step —
                // return to the Receive interface instead of leaving the user on the camera.
                tutorialMode -> {
                    { if (isAdded) requireActivity().finish() }
                }
                else -> null
            }
        )
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        if (nfcMode) return  // NFC receive mode never starts the camera (defensive).
        state = ReceiverState.SCANNING
        // M-13: if this fragment instance is reused with a fresh view, the executor was
        // shut down and the scanner closed in onDestroyView. Rebuild both so
        // setAnalyzer/process don't throw RejectedExecution/IllegalState (silent dead scan).
        if (analysisExecutor.isShutdown) {
            analysisExecutor = Executors.newSingleThreadExecutor()
            barcodeScanner = newBarcodeScanner()
        }
        // Camera-bind is the worst perceived lag in the flow — mask it (~300–700 ms).
        SkeletonHelper.show(skeletonCameraContainer, R.layout.skeleton_device_card, 2, horizontal = false)

        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener({
            if (!isAdded) return@addListener
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { imageProxy -> analyzeFrame(imageProxy) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            SkeletonHelper.hide(skeletonCameraContainer)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: androidx.camera.core.ImageProxy) {
        if (payloadConsumed.get()) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val raw = barcode.rawValue ?: continue
                    onCandidatePayload(raw)
                    break
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    // ------------------------------------------------------------------ nfc

    /** Render the transport diagnostic trace into the on-screen panel (main thread). */
    private fun renderDiag(snapshot: String) {
        val tv = tvDiag ?: return
        tv.text = snapshot
        diagScroll?.post { diagScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyDiag() {
        val snap = TransportDiag.snapshot()
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("transport-diag", snap))
        Toast.makeText(requireContext(), R.string.offgrid_diag_copied, Toast.LENGTH_SHORT).show()
    }

    /** Pop the full diagnostic trace in a copyable dialog (used on connection failure). */
    private fun showDiagDialog() {
        if (!isAdded) return
        val snap = TransportDiag.snapshot()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val tv = TextView(requireContext()).apply {
            text = snap
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        val scroll = android.widget.ScrollView(requireContext()).apply { addView(tv) }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.offgrid_diag_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.offgrid_diag_copy) { _, _ -> copyDiag() }
            .create()
            .also { it.applyModalPolish() }
            .show()
    }

    /**
     * On a failed SoftAP-bridge join (the sender is on its hotspot) when THIS device is already
     * occupied by a different Wi-Fi: a single-radio device can't join the hotspot while on another
     * Wi-Fi, and Android forbids a normal app from disconnecting/connecting Wi-Fi for the user — so
     * guide them to free it. Once they disconnect (or connect to the hotspot), SOFTAP_JOIN works and
     * the OS auto-restores their network when the transfer ends. Otherwise show the generic error.
     */
    private fun showSoftApGuidanceOrGeneric(payload: OffGridPayload, controlFailed: Boolean = false): Boolean {
        // Reset any stale guidance card from a prior failure so the generic branch never leaves it up.
        messageCard.visibility = View.GONE
        val onOtherWifi = Net.isConnectedToOtherWifi(requireContext())
        val openWifiSettings = {
            messageCard.visibility = View.GONE
            // Prefer the in-context slide-up Wi-Fi panel (Settings.Panel.ACTION_WIFI, API 29+ = our
            // minSdk) so the user can connect to the sender's hotspot / toggle Wi-Fi without leaving the
            // flow; fall back to the full Wi-Fi settings screen on any device that doesn't surface it.
            val opened = try {
                startActivity(Intent(Settings.Panel.ACTION_WIFI)); true
            } catch (_: Exception) { false }
            if (!opened) {
                try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Exception) {}
            }
        }
        // (a) SoftAP-bridge join failed because this phone is on a different Wi-Fi.
        if (payload.softApBridge && onOtherWifi) {
            // Single surface: the message card carries the full guidance + a Wi-Fi-settings action.
            tvStatus.text = ""
            showMessage(
                getString(R.string.offgrid_error_join_hotspot_free_wifi),
                getString(R.string.action_open_wifi),
                openWifiSettings
            )
            return true
        }
        // (b) Plain Wi-Fi Direct group transfer exhausted while this phone is associated to another
        // Wi-Fi: a single-radio phone can't join the sender's group on a different channel than its STA
        // (the MCC wall), and when the control channel also fails there's no role-flip to fall back on.
        // Disconnecting this phone's Wi-Fi frees the radio so the join associates.
        if (!payload.softApBridge && !payload.isLanOnly() && onOtherWifi) {
            tvStatus.text = ""
            showMessage(
                getString(R.string.offgrid_error_wifidirect_free_wifi),
                getString(R.string.action_open_wifi),
                openWifiSettings
            )
            return true
        }
        // No hotspot/Wi-Fi-radio guidance applied — show the generic copy, or name the Bluetooth
        // coordination link when that's the likely cause (sender's CONTROL_FAILED / local degrade).
        tvStatus.text = getString(
            if (controlFailed) R.string.offgrid_error_control_failed else R.string.offgrid_error_all_methods
        )
        return false
    }

    /**
     * Re-evaluate the radio conflicts that affect receiving, off the main thread:
     *  - hotspot ON **and** this phone is on another Wi-Fi → receiving can't work at all → HARD BLOCK
     *    (full-screen modal + scanning suppressed).
     *  - hotspot ON only → soft dismissible banner (Wi-Fi Direct may be impaired, but a transfer can
     *    still work, e.g. the sender joining this hotspot).
     * detectHotspotIp / isConnectedToOtherWifi iterate NetworkInterface, so they run on Dispatchers.IO.
     */
    private fun evaluateReceiveBlockers() {
        val appCtx = requireContext().applicationContext
        uiScope.launch {
            val hotspotOn = withContext(Dispatchers.IO) { Net.detectHotspotIp(appCtx) != null }
            val onOtherWifi = hotspotOn && withContext(Dispatchers.IO) { Net.isConnectedToOtherWifi(appCtx) }
            // State may have advanced (a scan slipped in) during the IO — don't paint over a non-scan UI.
            if (!isAdded || state != ReceiverState.SCANNING) return@launch
            val hardBlock = hotspotOn && onOtherWifi
            TransportDiag.log("RX: pre-scan radio check — hotspot=$hotspotOn otherWifi=$onOtherWifi → ${if (hardBlock) "BLOCKED" else "ok"}")
            receiveBlocked = hardBlock
            receiveBlockedOverlay.visibility = if (hardBlock) View.VISIBLE else View.GONE
            hotspotWarningBanner.visibility =
                if (hotspotOn && !onOtherWifi && !hotspotWarningDismissed) View.VISIBLE else View.GONE
            // Arm the NFC reader ONLY now that we've confirmed receiving isn't hard-blocked (and NFC is
            // on). NFC was disarmed entering onResume, so a tap can't slip through the async check window.
            if (!hardBlock && !payloadConsumed.get() &&
                NfcAdapter.getDefaultAdapter(requireContext())?.isEnabled == true
            ) {
                NfcPayloadReader.arm(requireActivity(), nfcReader)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TransportDiag.listener = { snap -> view?.post { renderDiag(snap) } }
        renderDiag(TransportDiag.snapshot())
        val adapter = NfcAdapter.getDefaultAdapter(requireContext())
        when {
            adapter == null -> {
                // In nfcMode the header (and this chip) is hidden — the guide hint covers NFC state.
                if (!nfcMode) tvNfcChip.visibility = View.VISIBLE
                tvNfcChip.text = getString(R.string.offgrid_nfc_unavailable)
                tvNfcChip.setOnClickListener(null)
            }
            !adapter.isEnabled -> {
                if (!nfcMode) tvNfcChip.visibility = View.VISIBLE
                tvNfcChip.text = getString(R.string.offgrid_nfc_off)
                tvNfcChip.setOnClickListener {
                    try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (_: Exception) {}
                }
            }
            else -> {
                tvNfcChip.visibility = View.GONE
                // NFC is armed by evaluateReceiveBlockers() in the onResume tail — only AFTER confirming
                // receiving isn't hard-blocked (hotspot + other-Wi-Fi), so a tap can't slip through the
                // async check window into a doomed transfer. The (!payloadConsumed && SCANNING) guard is
                // applied there too. (NFC starts disarmed on resume: onPause disarms it.)
            }
        }
        // Keep the NFC guide hint honest about the adapter state (e.g. user toggled NFC in settings).
        if (nfcMode) refreshNfcReceiveHint()

        // Proactive "your hotspot is on" warning — re-evaluated on every return (e.g. from the
        // tethering screen), and only while actively scanning so it never coexists with the failure
        // card or runs during the ladder. Reset the dismiss first so a return always re-checks the
        // live hotspot state.
        hotspotWarningDismissed = false
        if (state == ReceiverState.SCANNING && !payloadConsumed.get()) {
            evaluateReceiveBlockers()
        } else {
            hotspotWarningBanner.visibility = View.GONE
            receiveBlockedOverlay.visibility = View.GONE
            receiveBlocked = false
        }
    }

    override fun onPause() {
        super.onPause()
        TransportDiag.listener = null
        NfcPayloadReader.disarm(requireActivity())
    }

    // ----------------------------------------------------------- validation

    private fun onCandidatePayload(raw: String) {
        if (payloadConsumed.get() || receiveBlocked) return
        when (val result = OffGridPayload.parse(raw)) {
            is OffGridPayload.ParseResult.Valid -> {
                // First valid payload wins; both sources then disarm.
                if (!payloadConsumed.compareAndSet(false, true)) return
                uiScope.launch { onPayloadAccepted(result.payload) }
            }
            OffGridPayload.ParseResult.UnsupportedVersion -> {
                uiScope.launch {
                    if (isAdded) tvStatus.text = getString(R.string.offgrid_error_update_app)
                }
            }
            OffGridPayload.ParseResult.Invalid -> {
                // Wrong-app QR — ignore and keep scanning (edge case 10).
            }
        }
    }

    private fun onPayloadAccepted(payload: OffGridPayload) {
        if (!isAdded) return
        state = ReceiverState.PARSING
        acceptedPayload = payload
        disarmSources()
        hotspotWarningBanner.visibility = View.GONE  // a payload arrived — drop the pre-scan warning
        receiveBlockedOverlay.visibility = View.GONE

        tvConfirmTitle.text = getString(R.string.offgrid_confirm_title, payload.senderName.ifEmpty { getString(R.string.unknown_device) })
        tvConfirmSubtitle.text = getString(
            R.string.offgrid_confirm_subtitle,
            payload.fileCount, payload.totalBytes / 1048576.0
        )
        confirmCard.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.offgrid_status_code_scanned)

        btnReceive.text = getString(R.string.action_receive)
        btnReceive.setOnClickListener { connectToGroup(payload) }
    }

    private fun disarmSources() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        // H-h1: this runs from onDestroyView too, where the fragment may already be
        // detached — requireActivity() would throw IllegalStateException. Use the
        // nullable activity so disarm is skipped instead of crashing teardown.
        activity?.let { NfcPayloadReader.disarm(it) }
    }

    // ----------------------------------------------------------- connecting

    /**
     * Returns true if the radios needed to resolve the sender's group are on.
     * On failure it shows the message/action card and re-enables Receive so the
     * user can fix the setting and tap Retry.
     */
    private fun receiverPreflight(): Boolean {
        if (!isAdded) return false
        val ctx = requireContext()

        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi != null && !wifi.isWifiEnabled) {
            showMessage(
                getString(R.string.offgrid_error_wifi_off_receiver),
                getString(R.string.action_turn_on)
            ) {
                try { startActivity(Intent(Settings.Panel.ACTION_WIFI)) } catch (_: Exception) {}
            }
            btnReceive.isEnabled = true
            return false
        }

        val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager != null && !locationManager.isLocationEnabled) {
            showMessage(
                getString(R.string.offgrid_error_location_off),
                getString(R.string.action_turn_on)
            ) {
                try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
            }
            btnReceive.isEnabled = true
            return false
        }

        return true
    }

    private fun connectToGroup(payload: OffGridPayload) {
        // Preflight: WifiNetworkSpecifier never resolves with Wi-Fi off (mobile-data
        // case), and the OS scan results feeding the picker are gated on Location on
        // API 29–30. Re-run on every Retry.
        if (!receiverPreflight()) return

        messageCard.visibility = View.GONE
        btnReceive.isEnabled = false
        state = ReceiverState.CONNECTING
        tvStatus.text = getString(R.string.offgrid_status_connecting, payload.senderName)

        TransportDiag.attach(requireContext())
        TransportDiag.reset()
        TransportDiag.log(
            "RX: scanned '${payload.senderName}' lanOnly=${payload.isLanOnly()} " +
                "cap=0x${Integer.toHexString(payload.cap)} ssid='${payload.ssid}' lanIp=${payload.lanIp}"
        )

        // LAN-only (sender on an existing hotspot / shared network) needs no Wi-Fi Direct or
        // Bluetooth — connect straight over LAN, skipping the ladder permission gate.
        if (payload.isLanOnly()) {
            startTransportLadder(payload, hasNearbyPermission = false)
            return
        }

        // SoftAP bridge: the sender's mobile hotspot is joined via WifiNetworkSpecifier, which needs
        // only CHANGE_WIFI_STATE/CHANGE_NETWORK_STATE (normal, manifest-declared) — NOT
        // NEARBY_WIFI_DEVICES. Skip the nearby-permission gate (mirrors LAN-only) so a receiver that
        // denied NEARBY_WIFI_DEVICES can still join the hotspot. BLE control is still attempted
        // opportunistically inside the ladder (gated only on its own BLUETOOTH_SCAN permission).
        if (payload.softApBridge) {
            // PC-QR case: when the sender (a PC) advertises a BLE control channel, asking for the
            // Bluetooth runtime permission ONCE here lets the phone link over BLE — giving the PC live
            // status and coordinated retry instead of waiting blind. PC-only users never granted it via
            // the phone↔phone flow, so without this the rescue would silently never engage. Ask only if
            // askable and not asked before; the transfer proceeds into the same direct join either way.
            if ((payload.cap and TransportCaps.BLE_ADVERTISE) != 0 &&
                !btControlPermRequested() &&
                missingBtControlPermissions().isNotEmpty() &&
                pendingBtControlPayload == null
            ) {
                pendingBtControlPayload = payload
                markBtControlPermRequested()
                requestBtControlPermission.launch(missingBtControlPermissions().toTypedArray())
                return
            }
            startTransportLadder(payload, hasNearbyPermission = false)
            return
        }

        // §3.1 permission-aware ordering: without the nearby/Bluetooth grant the
        // ladder collapses to the flaky SPECIFIER picker, so make the grant
        // reachable instead of silently degrading.
        val missing = missingLadderPermissions()
        when {
            missing.isEmpty() ->
                startTransportLadder(payload, hasNearbyPermission = true)

            isPermanentlyDenied(missing) -> {
                // The prompt can't appear (permanent "Don't ask again", or MIUI
                // silently blocked it). Send the user to App info; never silently
                // run the picker-only ladder behind their back. They can still
                // proceed in limited mode via the Receive button (re-checked at tap).
                val msg = getString(R.string.offgrid_permission_permanently_denied) +
                        if (isMiui()) getString(R.string.offgrid_permission_miui_hint) else ""
                showMessage(msg, getString(R.string.action_go_to_settings)) { openAppSettings() }
                btnReceive.isEnabled = true
                btnReceive.text = getString(R.string.action_receive)
                btnReceive.setOnClickListener {
                    messageCard.visibility = View.GONE
                    val nowGranted = ContextCompat.checkSelfPermission(
                        requireContext(), nearbyPermission()
                    ) == PackageManager.PERMISSION_GRANTED
                    startTransportLadder(payload, nowGranted)
                }
            }

            else -> {
                // Askable: launch the system prompt DIRECTLY (no buried Continue card).
                pendingFallbackPayload = payload
                markLadderPermRequested()
                requestFallbackPermission.launch(missing.toTypedArray())
            }
        }
    }

    /**
     * Hands the connection problem to the [ReceiverSessionController]: control
     * channel when the pair supports BLE, the local T1 → T0 → T2 ladder
     * otherwise. The UI never dead-ends on a single tier any more — it shows
     * "trying method k of n" until a route wins or the whole ladder fails.
     */
    private fun startTransportLadder(payload: OffGridPayload, hasNearbyPermission: Boolean) {
        messageCard.visibility = View.GONE
        btnReceive.isEnabled = false
        state = ReceiverState.CONNECTING
        updateDegradedBanner(hasNearbyPermission)
        // Clear loading screen over the camera so it doesn't look like the camera froze.
        tvConnectingStatus.text = getString(R.string.offgrid_status_connecting, payload.senderName)
        connectingOverlay.visibility = View.VISIBLE

        // Same-network shortcut: if the sender published a LAN IP, try a direct LAN
        // socket first (fast, no Wi-Fi Direct / channel conflict). The socket merely
        // reaching the sender over our default network IS the "same network" check;
        // a fast failure falls through to the Wi-Fi Direct ladder.
        val lanIp = payload.lanIp
        if (lanIp != null) {
            TransportDiag.log("LAN: probing $lanIp:${payload.port} over current network…")
            uiScope.launch {
                val conn = withContext(Dispatchers.IO) { probeLanConnect(lanIp, payload.port) }
                if (!isAdded || state != ReceiverState.CONNECTING) {
                    conn?.let { try { it.close() } catch (_: Exception) {} }
                    return@launch
                }
                TransportDiag.log("LAN: $lanIp:${payload.port} ${if (conn != null) "reachable ✓ (same network)" else "unreachable → ladder"}")
                when {
                    conn != null -> startHandshake(payload, LanRoute(conn))
                    payload.isLanOnly() -> {
                        // No AP credentials to join — the sender is on its own hotspot / a shared
                        // network and expects this phone to already be on it. (Bluetooth is no longer
                        // a data fallback.)
                        state = ReceiverState.FAILED
                        connectingOverlay.visibility = View.GONE
                        btnReceive.isEnabled = true
                        btnReceive.text = getString(R.string.action_retry)
                        btnReceive.setOnClickListener { connectToGroup(payload) }
                        tvStatus.text = getString(R.string.offgrid_error_lan_unreachable)
                        showDiagDialog()
                    }
                    else -> startLadder(payload, hasNearbyPermission)
                }
            }
            return
        }
        startLadder(payload, hasNearbyPermission)
    }

    /** Connect a plain LAN socket to the sender over our default network. Returns
     *  the connection if reachable (same network), null otherwise. Blocking. */
    private fun probeLanConnect(lanIp: String, port: Int): TransportConnection? {
        return try {
            val socket = java.net.Socket()
            socket.tuneForBulkTransfer(beforeConnect = true)
            socket.connect(java.net.InetSocketAddress(lanIp, port), LAN_PROBE_TIMEOUT_MS)
            SocketConnection(socket)
        } catch (_: Exception) {
            null
        }
    }

    /** Brings up the control channel + Wi-Fi Direct / SoftAP ladder. */
    private fun startLadder(payload: OffGridPayload, hasNearbyPermission: Boolean) {
        sessionController?.teardown()
        val controller = ReceiverSessionController(
            requireContext(), payload, hasNearbyPermission,
            object : ReceiverSessionController.Listener {
                override fun onTierStarted(tier: TransportDescriptor, ordinal: Int, total: Int) {
                    if (!isAdded) return
                    val status = when (tier) {
                        TransportDescriptor.SPECIFIER, TransportDescriptor.LOHS ->
                            // The OS picker dialog is about to appear — tell the user why.
                            getString(R.string.offgrid_status_connecting, payload.senderName)
                        TransportDescriptor.P2P_NEGOTIATED ->
                            getString(R.string.offgrid_status_trying_direct)
                        else ->
                            getString(R.string.offgrid_status_trying_method, ordinal, maxOf(total, ordinal))
                    }
                    tvStatus.text = status
                    tvConnectingStatus.text = status
                }

                override fun onRouteReady(
                    tier: TransportDescriptor,
                    route: TransportRoute,
                    exactIp: String?,
                    exactPort: Int?
                ) {
                    if (!isAdded) return
                    activeRoute = route
                    exactSenderIp = exactIp
                    exactSenderPort = exactPort
                    startHandshake(payload, route)
                }

                override fun onAllFailed(controlFailed: Boolean) {
                    if (!isAdded) return
                    state = ReceiverState.FAILED
                    connectingOverlay.visibility = View.GONE
                    btnReceive.isEnabled = true
                    btnReceive.text = getString(R.string.action_retry)
                    btnReceive.setOnClickListener { connectToGroup(payload) }
                    // Skip the developer diag dialog when we showed the actionable Wi-Fi guidance card —
                    // it'd pop on top and block the "Go to Settings" button.
                    if (!showSoftApGuidanceOrGeneric(payload, controlFailed)) showDiagDialog()
                }

                override fun onRouteLost() {
                    if (!isAdded) return
                    onReceiveInterrupted()
                }
            })
        sessionController = controller
        controller.start()
    }

    private fun startHandshake(payload: OffGridPayload, route: TransportRoute) {
        state = ReceiverState.HANDSHAKE
        tvStatus.text = getString(R.string.offgrid_status_handshaking)
        val appContext = requireContext().applicationContext

        uiScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runHandshakeAndReceive(appContext, payload, route)
            }
            if (!isAdded) return@launch
            when (outcome) {
                HandshakeOutcome.STARTED -> {
                    state = ReceiverState.RECEIVING
                    // Host tiers (RECEIVER_AS_GO/SENDER_JOINS_AP) defer their "CONNECTED ✓" report until
                    // the TCP data path is actually live — report it now so the sender's ladder settles on
                    // a real success rather than the premature ok it used to get on DATA_READY arrival.
                    sessionController?.reportHandshakeSucceeded()
                    // Transfer started → leave the camera and go to the transfer-history details page;
                    // the download keeps streaming in the background. The Wi-Fi joiner this download
                    // rides on is handed to OffGridLiveTransfer so leaving doesn't tear it down. If we
                    // somehow lack a session id, fall back to the old resident-overlay behavior.
                    val sid = activeSessionId
                    if (sid != null) {
                        handOffAndOpenHistory(sid)
                    } else {
                        connectingOverlay.visibility = View.GONE
                        tvStatus.text = getString(R.string.offgrid_status_receiving)
                        btnReceive.visibility = View.GONE
                        receiveProgressSection.visibility = View.VISIBLE
                        degradedBanner.visibility = View.GONE
                        startReceiveProgressPolling()
                    }
                }
                HandshakeOutcome.BUSY -> {
                    // Host tiers deferred their result to the handshake: a TCP-connected-but-rejected
                    // outcome must free the sender's ladder (it's waiting for our TRANSPORT_RESULT).
                    sessionController?.reportHostHandshakeRejected()
                    connectingOverlay.visibility = View.GONE
                    tvStatus.text = getString(R.string.offgrid_error_sender_busy)
                    btnReceive.isEnabled = true
                    btnReceive.text = getString(R.string.action_retry)
                    btnReceive.setOnClickListener { connectToGroup(payload) }
                }
                HandshakeOutcome.EXPIRED -> {
                    sessionController?.reportHostHandshakeRejected()
                    connectingOverlay.visibility = View.GONE
                    tvStatus.text = getString(R.string.offgrid_error_code_expired)
                    btnReceive.isEnabled = true
                    btnReceive.text = getString(R.string.action_retry)
                    btnReceive.setOnClickListener { connectToGroup(payload) }
                }
                HandshakeOutcome.FAILED -> {
                    state = ReceiverState.FAILED
                    connectingOverlay.visibility = View.GONE
                    tvStatus.text = getString(R.string.offgrid_error_connect_failed)
                    btnReceive.isEnabled = true
                    btnReceive.text = getString(R.string.action_retry)
                    // Tell the coordinating sender so its ladder advances past a
                    // route that joined but cannot reach the TCP server (§9 #10).
                    sessionController?.reportHandshakeFailed()
                    // The route may still be usable (specifier OK, socket failed):
                    // retry the handshake on it — no second OS dialog.
                    btnReceive.setOnClickListener {
                        btnReceive.isEnabled = false
                        startHandshake(payload, route)
                    }
                }
            }
        }
    }

    private enum class HandshakeOutcome { STARTED, BUSY, EXPIRED, FAILED }

    /**
     * §2.3, receiver side. The duplex stream is opened by the active
     * [TransportRoute] — SpecifierRoute from the joined network's factory (the
     * OS does not route to a specifier network by default, edge case 13);
     * P2pRoute binds the local p2p source address; LanRoute hands over the
     * already-connected same-LAN link. Protocol bytes are identical on all.
     */
    private fun runHandshakeAndReceive(
        context: Context,
        payload: OffGridPayload,
        route: TransportRoute
    ): HandshakeOutcome {
        var connection: TransportConnection? = null
        // Ownership of the connected socket transfers to TransferDaemon.executeDownloadStream only on
        // the STARTED path. Every other return (and any thrown exception) must close it here, or the
        // socket leaks. The finally is the single cleanup point — the catch no longer closes to avoid
        // a double-close.
        var handedOff = false
        try {
            val conn = connectToSender(route, payload)
            if (conn == null) {
                TransportDiag.log("handshake: couldn't open TCP to sender (route up but unreachable) → FAILED")
                return HandshakeOutcome.FAILED
            }
            connection = conn
            connection.setReadTimeout(10000)
            TransportDiag.log("handshake: TCP connected ✓ to ${conn.remoteIdentifier} — sending P2P_HELLO")

            // One ephemeral key for both planes: the control HELLOs already used
            // it, and the sender derives the same K from this pk either way.
            val ecdh = sessionController?.ecdh ?: EcdhSession()
            val sharedKey = ecdh.deriveSecrets(payload.senderPublicKey).first

            val tokenB64 = android.util.Base64.encodeToString(payload.token, android.util.Base64.NO_WRAP)
            val pubB64 = android.util.Base64.encodeToString(ecdh.getPublicKey(), android.util.Base64.NO_WRAP)
            val hmacB64 = android.util.Base64.encodeToString(
                hmacSha256(sharedKey, payload.token), android.util.Base64.NO_WRAP
            )
            val hello = "P2P_HELLO|$tokenB64|$pubB64|${TransferDaemon.customDeviceName}|$hmacB64\n"

            val outStream = connection.output
            val inStream = connection.input
            outStream.write(hello.toByteArray(Charsets.UTF_8))
            outStream.flush()

            val welcome = readLine(inStream)
            if (welcome == null) {
                TransportDiag.log("handshake: connected but no P2P_WELCOME (sender closed / token mismatch) → retry")
                return HandshakeOutcome.EXPIRED
            }
            if (welcome == "P2P_BUSY") { TransportDiag.log("handshake: sender BUSY"); return HandshakeOutcome.BUSY }
            if (!welcome.startsWith("P2P_WELCOME|")) {
                TransportDiag.log("handshake: unexpected reply '${welcome.take(24)}' → retry")
                return HandshakeOutcome.EXPIRED
            }

            val welcomeParts = welcome.split('|')
            if (welcomeParts.size < 3) { TransportDiag.log("handshake: malformed WELCOME → FAILED"); return HandshakeOutcome.FAILED }
            val sessionId = welcomeParts[1]
            val ackBytes = try {
                android.util.Base64.decode(welcomeParts[2], android.util.Base64.NO_WRAP)
            } catch (_: Exception) { TransportDiag.log("handshake: bad ack b64 → FAILED"); return HandshakeOutcome.FAILED }

            // Mutual authentication: key-confirmation HMAC over token || "ack".
            val expectedAck = hmacSha256(sharedKey, payload.token + "ack".toByteArray(Charsets.UTF_8))
            if (!MessageDigest.isEqual(ackBytes, expectedAck)) {
                TransportDiag.log("handshake: ack HMAC mismatch (wrong key/token) → retry")
                return HandshakeOutcome.EXPIRED
            }
            TransportDiag.log("handshake: WELCOME + ack OK — receiving OFFER/MANIFEST")

            // Existing single-socket sequence: OFFER, MANIFEST.
            val offerLine = readLine(inStream) ?: return HandshakeOutcome.FAILED
            if (!offerLine.startsWith("OFFER|")) return HandshakeOutcome.FAILED
            val offerParts = offerLine.split('|')
            if (offerParts.size < 4) return HandshakeOutcome.FAILED
            val senderName = offerParts[2]
            val totalFiles = offerParts[3].toIntOrNull() ?: return HandshakeOutcome.FAILED

            val manifestLine = readLine(inStream) ?: return HandshakeOutcome.FAILED
            if (!manifestLine.startsWith("MANIFEST|")) return HandshakeOutcome.FAILED
            val manifest = mutableListOf<Pair<String, Long>>()
            for (item in manifestLine.removePrefix("MANIFEST|").split('|')) {
                val lastColonIndex = item.lastIndexOf(':')
                // Split on the LAST ':' so a filename containing ':' is preserved; require a
                // non-empty name (index > 0) and a parseable size — skip malformed entries rather
                // than admitting a phantom 0-byte file. Matches Windows ManifestCodec.parse.
                if (lastColonIndex > 0) {
                    val fname = item.substring(0, lastColonIndex)
                    val fsize = item.substring(lastColonIndex + 1).toLongOrNull() ?: continue
                    manifest.add(Pair(fname, fsize))
                }
            }
            if (manifest.isEmpty()) return HandshakeOutcome.FAILED

            // Same-socket accept — no reconnect, there is no discovery to
            // re-resolve the peer. If the sender honours the delivery ack, signal that we'll
            // send TRANSFER_COMPLETE so it waits for confirmation before finishing (this is the
            // fix for the sender showing "done" while we're stuck mid-download with a dead resume).
            val ackSupported = (payload.cap and TransportCaps.COMPLETION_ACK) != 0
            val acceptCmd = if (ackSupported) "ACCEPT_OFFER|$sessionId|ack\n" else "ACCEPT_OFFER|$sessionId\n"
            outStream.write(acceptCmd.toByteArray(Charsets.UTF_8))
            outStream.flush()

            val session = TransferSession(
                sessionId = sessionId,
                senderName = senderName,
                totalFiles = totalFiles,
                manifest = manifest,
                targetIp = payload.ip,
                isIncoming = true,
                transport = "p2p",
                completionAckRequired = ackSupported
            )
            TransferDaemon.activeSessions[sessionId] = session
            activeSessionId = sessionId
            activeConnection = connection

            TransferHistoryManager.addRecord(context, TransferRecord(
                sessionId = sessionId,
                deviceName = senderName,
                direction = "incoming",
                fileNames = manifest.map { it.first.replace('\\', '/').substringAfterLast('/') },
                totalBytes = manifest.sumOf { it.second },
                timestamp = System.currentTimeMillis(),
                status = "transferring",
                filesCompleted = 0,
                totalFiles = totalFiles,
                fileSizes = manifest.map { it.second },
                currentFileOffset = 0L,
                transport = "p2p"
            ))

            // Hand the stream to the proven engine — byte-for-byte the same
            // download path as LAN mode, regardless of carrier.
            TransferDaemon.executeDownloadStream(context, connection, session)
            handedOff = true
            return HandshakeOutcome.STARTED
        } catch (e: Exception) {
            e.printStackTrace()
            return HandshakeOutcome.FAILED
        } finally {
            if (!handedOff) {
                try { connection?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * The DATA_READY endpoint (exact, from the sender) leads; then two passes
     * over the route's candidate addresses — DHCP / the p2p interface can
     * still be settling right after the route resolves, so a clean miss on
     * every address earns one short pause and a final retry before giving up.
     */
    private fun connectToSender(route: TransportRoute, payload: OffGridPayload): TransportConnection? {
        val port = exactSenderPort ?: payload.port
        val candidates = linkedSetOf<String>()
        exactSenderIp?.let { candidates.add(it) }
        candidates.addAll(route.senderCandidates(payload))
        // Never dial our own addresses. When WE host as GO (RECEIVER_AS_GO), the route's candidate
        // fallbacks (payload.ip, <p2p subnet>.1) resolve to our own GO IP (192.168.49.1) — dialing it
        // connects to our own server and the sender side logs "no active send context". Drop self-IPs
        // so a dead route fails cleanly and the ladder advances instead of looping back to itself.
        val selfIps = Net.localIpv4Addresses()
        val dialable = candidates.filterNot { it in selfIps }
        if (dialable.isEmpty()) {
            TransportDiag.log("handshake: no reachable sender address (all candidates were self) — route failed")
            return null
        }
        TransportDiag.log("handshake: dialing sender at ${dialable.joinToString(",")}:$port")
        repeat(5) { attempt ->
            for (ip in dialable) {
                try {
                    return route.openConnection(ip, port)
                } catch (_: Exception) {
                }
            }
            if (attempt < 4) {
                // Wi-Fi Direct DHCP can take several seconds after the group forms — back off
                // progressively (≈1.0s,1.7s,2.4s,3.1s) so the p2p route comes up before we give up.
                Thread.sleep(1000L + attempt * 700L)
                // H-h3: Thread.sleep isn't cancellation-aware. This runs on the IO
                // thread under fragmentJob; if the fragment was torn down during the
                // pause, bail so we don't open a socket against a dead view.
                if (!fragmentJob.isActive) return null
            }
        }
        return null
    }

    private fun readLine(inputStream: java.io.InputStream): String? {
        val baos = java.io.ByteArrayOutputStream()
        var b = inputStream.read()
        while (b != -1 && b != '\n'.code) {
            baos.write(b)
            // H-h4: any peer on the joined group could stream an endless line with no
            // newline and OOM us. Control lines are tiny, so cap at 64 KiB and reject.
            if (baos.size() > MAX_LINE_BYTES) {
                throw java.io.IOException("control line exceeded $MAX_LINE_BYTES bytes")
            }
            b = inputStream.read()
        }
        if (b == -1 && baos.size() == 0) return null
        return baos.toString("UTF-8")
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    // ------------------------------------------------------------ receiving

    private fun startReceiveProgressPolling() {
        progressJob?.cancel()
        progressJob = uiScope.launch {
            val sessionId = activeSessionId ?: return@launch
            while (isActive) {
                val session = TransferDaemon.activeSessions[sessionId]
                if (session == null) {
                    // Session gone: completed (or cancelled) — read the record.
                    val record = TransferHistoryManager.getRecords(requireContext())
                        .find { it.sessionId == sessionId }
                    if (record?.status == "completed") {
                        pbReceive.progress = 100
                        tvReceiveProgress.text = getString(R.string.offgrid_status_complete)
                        tvStatus.text = getString(R.string.offgrid_status_complete)
                        btnConfirmCancel.text = getString(R.string.action_close)
                        Toast.makeText(requireContext(), getString(R.string.offgrid_status_complete), Toast.LENGTH_LONG).show()
                        // Land on the transfer-history details page for this session,
                        // matching the normal receive flow. Brief dwell on "complete" first.
                        delay(COMPLETE_NAV_DELAY_MS)
                        openHistoryDetails(sessionId)
                    }
                    return@launch
                }
                if (session.isPaused) {
                    onReceiveInterrupted()
                    return@launch
                }
                val totalBytes = session.manifest.sumOf { it.second }.coerceAtLeast(1L)
                var received = session.currentOffset
                for (i in 0 until session.currentFileIndex.coerceAtMost(session.manifest.size)) {
                    received += session.manifest[i].second
                }
                val pct = ((received.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                pbReceive.progress = pct
                tvReceiveProgress.text = getString(
                    R.string.offgrid_progress_format,
                    pct, received / 1048576.0, totalBytes / 1048576.0
                )
                delay(500)
            }
        }
    }

    /**
     * Download started → hand the live Wi-Fi joiner/ladder to the process-lifetime [OffGridLiveTransfer]
     * holder (so leaving the camera doesn't tear the link down) and start the foreground session service
     * to keep the process alive while the download runs off-screen, then finish to the transfer-history
     * details page. The holder runs this teardown when the download reaches a terminal state (driven from
     * TransferDaemon's finally → finishIfArmed). Captures only app context + the controller, never the
     * fragment.
     */
    private fun handOffAndOpenHistory(sessionId: String) {
        if (!isAdded) return
        val appCtx = requireContext().applicationContext
        val controller = sessionController
        // Keep the process (and its wakelocks) alive while the download streams in the background.
        P2pSessionService.start(appCtx)
        OffGridLiveTransfer.arm(sessionId) {
            controller?.teardown()
            P2pSessionService.stop(appCtx)
        }
        handedOff = true
        openHistoryDetails(sessionId)
    }

    /** Opens MainActivity focused on [sessionId]'s history details (same intent the
     *  completion notification uses), then finishes this off-grid screen. */
    private fun openHistoryDetails(sessionId: String) {
        if (!isAdded) return
        val intent = Intent(requireContext(), com.EntropicSoftwareLab.ClipboardShare.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("SHOW_HISTORY", true)
            putExtra("HISTORY_SESSION_ID", sessionId)
        }
        startActivity(intent)
        requireActivity().finish()
    }

    private fun onReceiveInterrupted() {
        if (!isAdded) return
        progressJob?.cancel()
        connectingOverlay.visibility = View.GONE
        tvStatus.text = getString(R.string.offgrid_status_interrupted)
        btnReceive.visibility = View.VISIBLE
        btnReceive.isEnabled = true
        btnReceive.text = getString(R.string.action_resume)
        btnReceive.setOnClickListener { resumeReceive() }
    }

    /**
     * Resume after a mid-transfer drop reuses RESUME_REQUEST verbatim, but
     * requires the group (and our specifier network) to still exist; if
     * teardown already ran, the UX is "re-scan to resume" — the session
     * record persists in history as paused (edge case 11).
     */
    private fun resumeReceive() {
        val sessionId = activeSessionId ?: return
        val payload = acceptedPayload ?: return
        val route = activeRoute
        val session = TransferDaemon.activeSessions[sessionId]
        if (route == null || session == null) {
            tvStatus.text = getString(R.string.offgrid_status_rescan_to_resume)
            return
        }

        btnReceive.isEnabled = false
        val appContext = requireContext().applicationContext

        uiScope.launch {
            val resumed = withContext(Dispatchers.IO) {
                var connection: TransportConnection? = null
                try {
                    connection = connectToSender(route, payload)
                        ?: throw java.io.IOException("sender unreachable")
                    connection.setReadTimeout(10000)
                    session.isPaused = false
                    // Re-assert the delivery-ack contract on the resumed leg so the sender keeps
                    // the session alive until we confirm, mirroring the initial ACCEPT_OFFER.
                    val ackSuffix = if (session.completionAckRequired) "|ack" else ""
                    val cmd = "RESUME_REQUEST|$sessionId|${session.currentFileIndex}|${session.currentOffset}$ackSuffix\n"
                    connection.output.run {
                        write(cmd.toByteArray(Charsets.UTF_8))
                        flush()
                    }
                    TransferHistoryManager.updateStatus(appContext, sessionId, "transferring")
                    activeConnection = connection
                    TransferDaemon.executeDownloadStream(appContext, connection, session)
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    try { connection?.close() } catch (_: Exception) {}
                    session.isPaused = true
                    false
                }
            }
            if (!isAdded) return@launch
            if (resumed) {
                tvStatus.text = getString(R.string.offgrid_status_receiving)
                btnReceive.visibility = View.GONE
                startReceiveProgressPolling()
            } else {
                btnReceive.isEnabled = true
                tvStatus.text = getString(R.string.offgrid_status_rescan_to_resume)
            }
        }
    }

    // ------------------------------------------------------------- messages

    private fun showMessage(message: String, actionLabel: String, action: () -> Unit) {
        messageCard.visibility = View.VISIBLE
        tvMessage.text = message
        btnMessageAction.text = actionLabel
        btnMessageAction.setOnClickListener { action() }
    }

    // ------------------------------------------------------------- teardown

    override fun onDestroyView() {
        super.onDestroyView()
        state = ReceiverState.TEARDOWN
        fragmentJob.cancel()
        disarmSources()
        // When the download was handed off (we left the camera mid-transfer), OffGridLiveTransfer now
        // owns the joiner and tears it down on completion — tearing it down here would kill the
        // in-flight download. Otherwise release the control channel + every joiner the ladder drove
        // (the specifier request, P2P groups, BT links); the phone returns to its home Wi-Fi.
        if (!handedOff) sessionController?.teardown()
        sessionController = null
        activeRoute = null
        receiveNfcGuideSection = null
        receiveNfcGuideView = null
        receiveNfcGuideHint = null
        receiveNfcDiag = null
        nfcDiagLines.clear()
        analysisExecutor.shutdown()
        try { barcodeScanner.close() } catch (_: Exception) {}
    }

    companion object {
        /** Raw JSON payload pre-filled from an NFC tap on the Receive screen. */
        const val ARG_PRESET_PAYLOAD = "ARG_PRESET_PAYLOAD"
        /** Renders the scanner tip as the chained step 3 / 3 of the Receive tutorial. */
        const val ARG_TUTORIAL = "ARG_TUTORIAL"
        /** Renders the scanner tip as the final step 22 / 22 of the forced first-launch tour. */
        const val ARG_FORCED_TOUR = "ARG_FORCED_TOUR"
        /** Presentation forwarded from OffGridSendActivity; PRESENTATION_NFC → NFC tap-guide (no camera). */
        const val ARG_PRESENTATION = "ARG_PRESENTATION"

        /** Brief dwell on the "complete" state before opening history details. */
        private const val COMPLETE_NAV_DELAY_MS = 1_200L

        /** Same-network probe timeout: short, so a different-network case falls through
         *  to Wi-Fi Direct quickly. */
        private const val LAN_PROBE_TIMEOUT_MS = 2_500

        /** H-h4: hard cap on a single control line — guards against a hostile peer on
         *  the joined group streaming an unbounded line to OOM the receiver. */
        private const val MAX_LINE_BYTES = 64 * 1024

        /** Shared prefs file (app-wide) + key tracking whether the ladder-permission
         *  bundle has been requested at least once — disambiguates "never asked"
         *  from "permanently denied" for [isPermanentlyDenied]. */
        private const val PERM_PREFS = "FileBridgePrefs"
        private const val KEY_LADDER_PERM_REQUESTED = "ladder_perm_requested_v1"
        /** Tracks the one-time Bluetooth-permission ask on the PC-QR (SoftAP + BLE cap) path. */
        private const val KEY_BT_CONTROL_PERM_REQUESTED = "bt_control_perm_requested_v1"
    }
}

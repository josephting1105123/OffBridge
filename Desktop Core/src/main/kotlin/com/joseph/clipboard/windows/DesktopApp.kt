package com.joseph.clipboard.windows

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.NavigationRail
import androidx.compose.material.NavigationRailItem
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.border
import com.joseph.clipboard.windows.theme.ClipboardTheme
import com.joseph.clipboard.windows.theme.DarkSurface
import com.joseph.clipboard.windows.theme.ErrorMuted
import com.joseph.clipboard.windows.theme.ErrorText
import com.joseph.clipboard.windows.theme.GreenPrimary
import com.joseph.clipboard.windows.theme.ThemeMode
import com.joseph.clipboard.windows.ui.AndroidVersionScreen
import com.joseph.clipboard.windows.ui.BackdropController
import com.joseph.clipboard.windows.ui.BackdropEffect
import com.joseph.clipboard.windows.ui.BackdropScrim
import com.joseph.clipboard.windows.ui.FileTransferScreen
import com.joseph.clipboard.windows.ui.FirstRunTour
import com.joseph.clipboard.windows.ui.HistoryScreen
import com.joseph.clipboard.windows.ui.LocalBackdrop
import com.joseph.clipboard.windows.ui.ReceivingPill
import com.joseph.clipboard.windows.ui.SettingsScreen
import com.joseph.clipboard.windows.ui.SystemStatusScreen
import com.joseph.clipboard.windows.ui.TutorialOverlay
import com.joseph.clipboard.windows.ui.TutorialStep
import com.joseph.clipboard.windows.ui.UpdateScreen
import com.joseph.clipboard.windows.ui.backdropBlur
import com.joseph.clipboard.windows.ui.modalPopIn
import com.joseph.clipboard.windows.update.UpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess
import androidx.compose.foundation.BorderStroke
import com.joseph.clipboard.windows.storage.SecurityVault
import com.joseph.clipboard.windows.storage.TranslationManager
import com.joseph.clipboard.windows.theme.SurfaceElevatedDark
import com.joseph.clipboard.windows.theme.GreenText
import com.joseph.clipboard.windows.theme.HairlineDark
import com.joseph.clipboard.windows.theme.TextPrimaryDark
import com.joseph.clipboard.windows.theme.TextSecondaryDark

/**
 * The portable Compose app shell: navigation, screens, and the pairing/transfer overlays. Split
 * out of Main.kt when the desktop client gained a second platform — everything here renders
 * identically on Windows and Linux, while each platform module keeps its own main() for window
 * creation, DPI handling and tray wiring.
 */
enum class Screen {
    SEND_FILES, SYSTEM_STATUS, HISTORY, SETTINGS, ANDROID_VERSION, SYSTEM_UPDATE
}

// Global Theme State
val LocalThemeMode = compositionLocalOf<MutableState<ThemeMode>> { error("No ThemeMode provided") }

/**
 * The global "Receiving…" pill, isolated in its own composable so its flow reads stay out of
 * AppContent's recompose scope: transferHistory re-emits on every progress tick during a receive,
 * so it is only read at click time, never during composition.
 */
@Composable
private fun ReceivingPillOverlay(
    appController: ClipboardAppController,
    hiddenByDialog: Boolean,
    modifier: Modifier = Modifier,
) {
    val activeIncoming by appController.activeIncomingSessions.collectAsState()
    if (activeIncoming.isEmpty() || hiddenByDialog) return
    val history = appController.transferHistory.collectAsState()
    ReceivingPill(
        text = TranslationManager.getString("receiving_label").takeIf { it != "receiving_label" } ?: "Receiving",
        onClick = {
            // Resolved at click time: newest history record still pumping wins (history is
            // newest-first), falling back to any live id. Both collections can shift between
            // frames, so guard the just-emptied race instead of first()-throwing.
            val id = history.value.firstOrNull { it.sessionId in activeIncoming }?.sessionId
                ?: activeIncoming.firstOrNull()
            if (id != null) appController.openTransferSession(id)
        },
        modifier = modifier,
    )
}

@Composable
fun AppContent(onMinimize: () -> Unit, appController: ClipboardAppController) {
    var currentScreen by remember { mutableStateOf(Screen.SEND_FILES) }
    var showPowerMenu by remember { mutableStateOf(false) }
    // Whether FileTransferScreen's transfer-history dialog is on screen, reported via callback —
    // the flag lives inside that screen, which is disposed on nav-away. Gates the receiving pill.
    var transferHistoryDialogVisible by remember { mutableStateOf(false) }
    val currentLanguage by TranslationManager.currentLanguageState
    var tutorialChainTarget by remember { mutableStateOf<Screen?>(null) }
    var tutorialChainCounter by remember { mutableStateOf(0) }

    val onTutorialChainNext: (Screen) -> Unit = { screen ->
        currentScreen = screen
        tutorialChainTarget = screen
        tutorialChainCounter++
    }

    // First-ever-launch guided tour — six conceptual steps, one per main interface plus a closer
    // pointing at the "How to use" chip. See [FirstRunTour] for the copy and for why this replaced
    // the old behaviour (chaining every full per-screen tutorial, ~18 steps, on first launch).
    //
    // Marked shown immediately rather than on completion, so quitting or closing the window mid-tour
    // never re-offers it on a later launch. Window coordinates for the spotlight targets are
    // published into tourBounds by the nav rail below and by FileTransferScreen's chip.
    var tourActive by remember { mutableStateOf(false) }
    val tourBounds = remember { mutableStateMapOf<String, Rect>() }
    LaunchedEffect(Unit) {
        if (!SecurityVault.isFirstRunTourShown()) {
            SecurityVault.markFirstRunTourShown()
            tourActive = true
        }
    }

    val incomingPairing by appController.incomingPairingRequest.collectAsState()
    val outgoingPairing by appController.outgoingPairingState.collectAsState()

    val navigateToSession by appController.navigateToTransferSession.collectAsState(initial = null)
    LaunchedEffect(navigateToSession) {
        if (navigateToSession != null) {
            currentScreen = Screen.SEND_FILES
        }
    }

    // While any modal (dialog / pairing / power menu) is up, the screen content blurs behind it.
    val backdropController = remember { BackdropController() }

    CompositionLocalProvider(LocalBackdrop provides backdropController) {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val transferText = TranslationManager.getString("nav_send").takeIf { it != "nav_send" } ?: "Send"
            val statusText = TranslationManager.getString("nav_pairing").takeIf { it != "nav_pairing" } ?: "Pairing"
            val historyText = TranslationManager.getString("nav_clipboard").takeIf { it != "nav_clipboard" } ?: "Clipboard"
            val settingsText = TranslationManager.getString("settings_title").takeIf { it != "settings_title" } ?: "Settings"
            val androidText = TranslationManager.getString("nav_android").takeIf { it != "nav_android" } ?: "Android"
            val updatesText = TranslationManager.getString("nav_updates").takeIf { it != "nav_updates" } ?: "Updates"
            val updateStatus by appController.updateStatus.collectAsState()

            Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(modifier = Modifier.width(80.dp)) {
                NavigationRailItem(
                    icon = { Icon(Icons.Filled.Send, contentDescription = transferText) },
                    label = { Text(transferText, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                    selected = currentScreen == Screen.SEND_FILES,
                    onClick = { currentScreen = Screen.SEND_FILES },
                    modifier = Modifier.onGloballyPositioned {
                        tourBounds[FirstRunTour.TARGET_NAV_SEND] = it.boundsInWindow()
                    }
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Filled.Link, contentDescription = statusText) },
                    label = { Text(statusText, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                    selected = currentScreen == Screen.SYSTEM_STATUS,
                    onClick = { currentScreen = Screen.SYSTEM_STATUS },
                    modifier = Modifier.onGloballyPositioned {
                        tourBounds[FirstRunTour.TARGET_NAV_PAIRING] = it.boundsInWindow()
                    }
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Filled.ContentPaste, contentDescription = historyText) },
                    label = { Text(historyText, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                    selected = currentScreen == Screen.HISTORY,
                    onClick = { currentScreen = Screen.HISTORY },
                    modifier = Modifier.onGloballyPositioned {
                        tourBounds[FirstRunTour.TARGET_NAV_CLIPBOARD] = it.boundsInWindow()
                    }
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = settingsText) },
                    label = { Text(settingsText, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                    selected = currentScreen == Screen.SETTINGS,
                    onClick = { currentScreen = Screen.SETTINGS }
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Filled.Android, contentDescription = androidText) },
                    label = { Text(androidText, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                    selected = currentScreen == Screen.ANDROID_VERSION,
                    onClick = { currentScreen = Screen.ANDROID_VERSION }
                )
                NavigationRailItem(
                    icon = {
                        Box {
                            Icon(Icons.Filled.SystemUpdate, contentDescription = updatesText)
                            // Passive nudge: a new release was found by the startup check, without
                            // the user having to remember to open this page and look.
                            if (updateStatus is UpdateStatus.UpdateAvailable) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(8.dp)
                                        .background(ErrorText, CircleShape)
                                )
                            }
                        }
                    },
                    label = { Text(updatesText, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                    selected = currentScreen == Screen.SYSTEM_UPDATE,
                    onClick = { currentScreen = Screen.SYSTEM_UPDATE }
                )

                // Push power button to bottom
                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = { showPowerMenu = true },
                    modifier = Modifier.padding(bottom = 16.dp).size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.Build, // Placeholder for Power icon
                        // No "power" key exists in translation.json.txt; "menu_title" ("Menu") would
                        // mislabel this control for screen readers, so keep the accurate literal.
                        contentDescription = "Power",
                        tint = MaterialTheme.colors.onSurface
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                Crossfade(
                    targetState = currentScreen,
                    animationSpec = tween(220),
                    label = "screen",
                    // The page recedes (blurs) behind any modal; the rail stays sharp and usable.
                    modifier = Modifier.backdropBlur(backdropController),
                ) { screen ->
                    when (screen) {
                        Screen.SEND_FILES -> FileTransferScreen(
                            appController = appController,
                            onTutorialChainNext = onTutorialChainNext,
                            tutorialTrigger = if (tutorialChainTarget == Screen.SEND_FILES) tutorialChainCounter else 0,
                            onNavigateToPairing = { currentScreen = Screen.SYSTEM_STATUS },
                            onTransferHistoryVisibility = { transferHistoryDialogVisible = it },
                            onHowToUseChipBounds = { tourBounds[FirstRunTour.TARGET_HOW_TO_USE_CHIP] = it }
                        )
                        Screen.SYSTEM_STATUS -> SystemStatusScreen(
                            appController = appController,
                            onTutorialChainNext = onTutorialChainNext,
                            tutorialTrigger = if (tutorialChainTarget == Screen.SYSTEM_STATUS) tutorialChainCounter else 0
                        )
                        Screen.HISTORY -> HistoryScreen(
                            appController = appController,
                            onTutorialChainNext = onTutorialChainNext,
                            tutorialTrigger = if (tutorialChainTarget == Screen.HISTORY) tutorialChainCounter else 0
                        )
                        Screen.SETTINGS -> SettingsScreen(appController)
                        Screen.ANDROID_VERSION -> AndroidVersionScreen()
                        Screen.SYSTEM_UPDATE -> UpdateScreen(appController)
                    }
                }

                // Global "Receiving…" pill — floats above every screen (under the modal scrims
                // below) while an incoming byte-pump is live, unless the transfer-history dialog
                // is already showing the transfer. Click jumps to the session via the same
                // navigate-to-session flow the accept path drives.
                ReceivingPillOverlay(
                    appController = appController,
                    hiddenByDialog = transferHistoryDialogVisible,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 20.dp)
                        .backdropBlur(backdropController),
                )

                if (showPowerMenu) {
                    BackdropEffect()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BackdropScrim)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { showPowerMenu = false }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = DarkSurface,
                            border = BorderStroke(1.dp, HairlineDark),
                            elevation = 24.dp,
                            modifier = Modifier.modalPopIn().clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { /* Do nothing, consume click */ }
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp).width(300.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                                    Text(
                                        TranslationManager.getString("menu_title").takeIf { it != "menu_title" } ?: "Menu",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimaryDark,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                    IconButton(
                                        onClick = { showPowerMenu = false },
                                        modifier = Modifier.align(Alignment.CenterEnd).size(28.dp)
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondaryDark, modifier = Modifier.size(18.dp))
                                    }
                                }

                                // Restart Services — glass card (neutral elevated fill + hairline, the app's
                                // glassSurface recipe); the emerald text/icon carries the meaning, not the fill.
                                Button(
                                    onClick = {
                                        // Restart the network daemons in-process instead of relaunching
                                        // the JVM. Relaunching via ProcessBuilder/exitProcess(0) failed in dev
                                        // and could spawn a second instance that cannot bind the daemon ports.
                                        appController.restartServices()
                                        showPowerMenu = false
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = SurfaceElevatedDark,
                                        contentColor = GreenText
                                    ),
                                    elevation = ButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                                    border = BorderStroke(1.dp, HairlineDark),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp), tint = GreenText)
                                    Spacer(Modifier.width(8.dp))
                                    Text(TranslationManager.getString("menu_restart_services"), color = GreenText, fontWeight = FontWeight.Medium)
                                }

                                // Power Off — same neutral glass card; the red text/icon carries the
                                // destructive meaning, no red fill.
                                Button(
                                    // Daemon teardown happens in the JVM shutdown hook (see main()).
                                    onClick = { exitProcess(0) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = SurfaceElevatedDark,
                                        contentColor = ErrorText
                                    ),
                                    elevation = ButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                                    border = BorderStroke(1.dp, HairlineDark),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp), tint = ErrorText)
                                    Spacer(Modifier.width(8.dp))
                                    Text(TranslationManager.getString("menu_power_off"), color = ErrorText, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }

                // Modal Overlays (Moved to be inside the content box so they don't cover the sidebar)
                val incomingFileOffer by appController.incomingFileOffer.collectAsState()
                
                incomingFileOffer?.let { session ->
                    ReceiverFileOfferOverlay(session, appController)
                }

                incomingPairing?.let { data ->
                    ReceiverPairingOverlay(data)
                }

                outgoingPairing?.let { data ->
                    SenderPairingOverlay(data, onCancel = { appController.cancelOutgoingPairing() })
                }
            }
        }
        } // Close Surface

        // First-launch tour. Deliberately a sibling of the Surface rather than a child of the
        // content Box: its spotlight has to reach the NAV RAIL, which every per-screen tutorial
        // sits inside of and therefore never had to cover. Gated on the first step's target having
        // reported bounds, so the overlay can't flash a hole-less scrim on the very first frame.
        if (tourActive && tourBounds.containsKey(FirstRunTour.TARGET_NAV_PAIRING)) {
            TutorialOverlay(
                steps = FirstRunTour.steps.map { step ->
                    val isChipStep = step.targetKey == FirstRunTour.TARGET_HOW_TO_USE_CHIP
                    TutorialStep(
                        targetKey = step.targetKey,
                        text = FirstRunTour.textOf(step),
                        // Forced tour: no way out but through, matching Android's hideSkipButton.
                        hideSkipButton = true,
                        // Nav items sit shoulder to shoulder; shrink the hole so it frames one item
                        // instead of bleeding onto its neighbours (Android uses the same 0.95f).
                        spotlightScale = if (isChipStep) 1f else 0.95f,
                        // Only the chip needs to wait: it lives inside the Send screen, so its bounds
                        // are only republished once the Crossfade back to that screen has settled.
                        delayMs = if (isChipStep) FirstRunTour.SCREEN_SETTLE_MS else 0L,
                        onShow = { currentScreen = step.screen },
                    )
                },
                totalSteps = FirstRunTour.TOTAL_STEPS,
                boundsMap = tourBounds,
                onDismiss = { tourActive = false },
                onComplete = { tourActive = false },
            )
        }
    } // Close Box
    } // Close CompositionLocalProvider
} // Close AppContent

/** Read-only segmented verification code (brand-bordered boxes, white digits) — mirrors the Android
 * `view_pin_code` design. The pairing overlays sit on a dark surface, so the digits are white. */
@Composable
private fun PinCodeBoxes(pin: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 6) {
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 50.dp)
                    .border(1.5.dp, GreenPrimary, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = pin.getOrNull(i)?.toString() ?: "",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PairingVerifyLabel() {
    Text(
        TranslationManager.getString("pairing_verify_label")
            .takeIf { it != "pairing_verify_label" } ?: "Make sure this code matches on both devices",
        color = Color.Gray,
        fontSize = 13.sp,
        textAlign = TextAlign.Center
    )
}

@Composable
fun ReceiverPairingOverlay(data: ClipboardAppController.IncomingPairingData) {
    BackdropEffect()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackdropScrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* Block clicks */ },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, HairlineDark),
            elevation = 24.dp,
            modifier = Modifier.modalPopIn()
        ) {
            Column(
                modifier = Modifier.padding(32.dp).width(300.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    TranslationManager.getString("pairing_request_title").takeIf { it != "pairing_request_title" } ?: "Pairing Request", 
                    style = MaterialTheme.typography.h5.copy(fontWeight = FontWeight.Bold, color = Color.White)
                )
                Spacer(modifier = Modifier.height(8.dp))
                val fromText = TranslationManager.getString("label_from").takeIf { it != "label_from" } ?: "From: "
                Text("$fromText${data.name}", color = GreenPrimary, fontSize = 16.sp)

                data.pin.takeIf { it.isNotEmpty() }?.let { pin ->
                    Spacer(modifier = Modifier.height(24.dp))
                    PairingVerifyLabel()
                    Spacer(modifier = Modifier.height(12.dp))
                    PinCodeBoxes(pin)
                }

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = { data.onAccept() },
                    colors = ButtonDefaults.buttonColors(backgroundColor = GreenPrimary),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(TranslationManager.getString("action_accept").takeIf { it != "action_accept" } ?: "Accept", color = Color.Black)
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = { data.onDecline() },
                    colors = ButtonDefaults.buttonColors(backgroundColor = ErrorMuted),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(TranslationManager.getString("action_decline").takeIf { it != "action_decline" } ?: "Decline", color = ErrorText)
                }
            }
        }
    }
}

@Composable
fun SenderPairingOverlay(data: ClipboardAppController.OutgoingPairingState, onCancel: () -> Unit) {
    BackdropEffect()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackdropScrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* Block clicks */ },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, HairlineDark),
            elevation = 24.dp,
            modifier = Modifier.modalPopIn()
        ) {
            Column(
                modifier = Modifier.padding(32.dp).width(300.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.height(24.dp))
                val reqText = TranslationManager.getString("requesting_pair_title").takeIf { it != "requesting_pair_title" } ?: "Requesting Pair"
                Text(reqText, style = MaterialTheme.typography.h5.copy(fontWeight = FontWeight.Bold, color = Color.White))
                Spacer(modifier = Modifier.height(8.dp))
                

                Text(data.status, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))

                data.visualPin?.takeIf { it.isNotEmpty() }?.let { pin ->
                    Spacer(modifier = Modifier.height(24.dp))
                    PairingVerifyLabel()
                    Spacer(modifier = Modifier.height(12.dp))
                    PinCodeBoxes(pin)
                }

                Spacer(modifier = Modifier.height(48.dp))

                CircularProgressIndicator(
                    color = GreenPrimary,
                    modifier = Modifier.size(32.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // The requester isn't fully dependent on the other side: if the peer never responds
                // (or never received the request), Cancel aborts the handshake instead of leaving it
                // spinning until the socket times out.
                Button(
                    onClick = { onCancel() },
                    colors = ButtonDefaults.buttonColors(backgroundColor = ErrorMuted),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(TranslationManager.getString("action_cancel").takeIf { it != "action_cancel" } ?: "Cancel", color = ErrorText)
                }
            }
        }
    }
}

@Composable
fun ReceiverFileOfferOverlay(session: IncomingFileSession, appController: ClipboardAppController) {
    BackdropEffect()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackdropScrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* Block clicks */ },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, HairlineDark),
            elevation = 24.dp,
            modifier = Modifier.modalPopIn()
        ) {
            Column(
                modifier = Modifier.padding(32.dp).width(300.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.height(24.dp))
                val incomingFilesText = TranslationManager.getString("incoming_files_title").takeIf { it != "incoming_files_title" } ?: "Incoming File(s)"
                Text(incomingFilesText, style = MaterialTheme.typography.h5.copy(fontWeight = FontWeight.Bold, color = Color.White))
                Spacer(modifier = Modifier.height(8.dp))
                val fromText = TranslationManager.getString("label_from").takeIf { it != "label_from" } ?: "From: "
                Text("$fromText${session.senderName}", color = GreenPrimary, fontSize = 16.sp)
                
                Spacer(modifier = Modifier.height(8.dp))
                val totalBytes = session.manifest.sumOf { it.second }
                val sizeStr = if (totalBytes > 1024 * 1024) {
                    String.format("%.2f MB", totalBytes / (1024f * 1024f))
                } else {
                    String.format("%.2f KB", totalBytes / 1024f)
                }
                val filesText = TranslationManager.getString("label_files").takeIf { it != "label_files" } ?: "file(s)"
                Text("${session.manifest.size} $filesText - $sizeStr", color = Color.Gray, fontSize = 14.sp)
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Button(
                    onClick = { appController.acceptIncomingFileTransfer(session.sessionId) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = GreenPrimary),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(TranslationManager.getString("action_accept").takeIf { it != "action_accept" } ?: "Accept", color = Color.Black)
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = { appController.declineIncomingFileTransfer() },
                    colors = ButtonDefaults.buttonColors(backgroundColor = ErrorMuted),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(TranslationManager.getString("action_decline").takeIf { it != "action_decline" } ?: "Decline", color = ErrorText)
                }
            }
        }
    }
}

package com.joseph.clipboard.windows

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
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
import com.joseph.clipboard.windows.ui.HistoryScreen
import com.joseph.clipboard.windows.ui.LocalBackdrop
import com.joseph.clipboard.windows.ui.SettingsScreen
import com.joseph.clipboard.windows.ui.SystemStatusScreen
import com.joseph.clipboard.windows.ui.backdropBlur
import com.joseph.clipboard.windows.ui.modalPopIn
import com.joseph.clipboard.windows.os.SystemTrayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

enum class Screen {
    SEND_FILES, SYSTEM_STATUS, HISTORY, SETTINGS, ANDROID_VERSION
}

// Global Theme State
val LocalThemeMode = compositionLocalOf<MutableState<ThemeMode>> { error("No ThemeMode provided") }

@Composable
fun AppContent(onMinimize: () -> Unit, appController: ClipboardAppController) {
    var currentScreen by remember { mutableStateOf(Screen.SEND_FILES) }
    var showPowerMenu by remember { mutableStateOf(false) }
    val currentLanguage by com.joseph.clipboard.windows.storage.TranslationManager.currentLanguageState
    var tutorialChainTarget by remember { mutableStateOf<Screen?>(null) }
    var tutorialChainCounter by remember { mutableStateOf(0) }

    val onTutorialChainNext: (Screen) -> Unit = { screen ->
        currentScreen = screen
        tutorialChainTarget = screen
        tutorialChainCounter++
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
            val transferText = com.joseph.clipboard.windows.storage.TranslationManager.getString("nav_send").takeIf { it != "nav_send" } ?: "Send"
            val statusText = com.joseph.clipboard.windows.storage.TranslationManager.getString("nav_pairing").takeIf { it != "nav_pairing" } ?: "Pairing"
            val historyText = com.joseph.clipboard.windows.storage.TranslationManager.getString("nav_clipboard").takeIf { it != "nav_clipboard" } ?: "Clipboard"
            val settingsText = com.joseph.clipboard.windows.storage.TranslationManager.getString("settings_title").takeIf { it != "settings_title" } ?: "Settings"
            val androidText = com.joseph.clipboard.windows.storage.TranslationManager.getString("nav_android").takeIf { it != "nav_android" } ?: "Android"

            Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(modifier = Modifier.width(80.dp)) {
                NavigationRailItem(
                    icon = { Icon(Icons.Filled.Send, contentDescription = transferText) },
                    label = { Text(transferText, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                    selected = currentScreen == Screen.SEND_FILES,
                    onClick = { currentScreen = Screen.SEND_FILES }
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Filled.Link, contentDescription = statusText) },
                    label = { Text(statusText, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                    selected = currentScreen == Screen.SYSTEM_STATUS,
                    onClick = { currentScreen = Screen.SYSTEM_STATUS }
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Filled.ContentPaste, contentDescription = historyText) },
                    label = { Text(historyText, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                    selected = currentScreen == Screen.HISTORY,
                    onClick = { currentScreen = Screen.HISTORY }
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
                            onNavigateToPairing = { currentScreen = Screen.SYSTEM_STATUS }
                        )
                        Screen.SYSTEM_STATUS -> SystemStatusScreen(
                            appController = appController,
                            onTutorialChainNext = onTutorialChainNext
                        )
                        Screen.HISTORY -> HistoryScreen(appController)
                        Screen.SETTINGS -> SettingsScreen(appController)
                        Screen.ANDROID_VERSION -> AndroidVersionScreen()
                    }
                }
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
                            border = androidx.compose.foundation.BorderStroke(1.dp, com.joseph.clipboard.windows.theme.HairlineDark),
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
                                        com.joseph.clipboard.windows.storage.TranslationManager.getString("menu_title").takeIf { it != "menu_title" } ?: "Menu",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = com.joseph.clipboard.windows.theme.TextPrimaryDark,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                    IconButton(
                                        onClick = { showPowerMenu = false },
                                        modifier = Modifier.align(Alignment.CenterEnd).size(28.dp)
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = com.joseph.clipboard.windows.theme.TextSecondaryDark, modifier = Modifier.size(18.dp))
                                    }
                                }

                                // Restart Services — emerald tonal (the safe action), on-brand instead of
                                // the old flat near-black fill. Flat (0 elevation) + hairline = modern tonal.
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
                                        backgroundColor = com.joseph.clipboard.windows.theme.AccentMutedDark,
                                        contentColor = com.joseph.clipboard.windows.theme.GreenText
                                    ),
                                    elevation = ButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, com.joseph.clipboard.windows.theme.HairlineDark),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp), tint = com.joseph.clipboard.windows.theme.GreenText)
                                    Spacer(Modifier.width(8.dp))
                                    Text(com.joseph.clipboard.windows.storage.TranslationManager.getString("menu_restart_services"), color = com.joseph.clipboard.windows.theme.GreenText, fontWeight = FontWeight.Medium)
                                }

                                // Power Off — destructive tonal, same muted-red language as the Decline button.
                                Button(
                                    // Daemon teardown happens in the JVM shutdown hook (see main()).
                                    onClick = { exitProcess(0) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = ErrorMuted,
                                        contentColor = ErrorText
                                    ),
                                    elevation = ButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, ErrorText.copy(alpha = 0.22f)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp), tint = ErrorText)
                                    Spacer(Modifier.width(8.dp))
                                    Text(com.joseph.clipboard.windows.storage.TranslationManager.getString("menu_power_off"), color = ErrorText, fontWeight = FontWeight.Medium)
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
        com.joseph.clipboard.windows.storage.TranslationManager.getString("pairing_verify_label")
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
            border = androidx.compose.foundation.BorderStroke(1.dp, com.joseph.clipboard.windows.theme.HairlineDark),
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
                    com.joseph.clipboard.windows.storage.TranslationManager.getString("pairing_request_title").takeIf { it != "pairing_request_title" } ?: "Pairing Request", 
                    style = MaterialTheme.typography.h5.copy(fontWeight = FontWeight.Bold, color = Color.White)
                )
                Spacer(modifier = Modifier.height(8.dp))
                val fromText = com.joseph.clipboard.windows.storage.TranslationManager.getString("label_from").takeIf { it != "label_from" } ?: "From: "
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
                    Text(com.joseph.clipboard.windows.storage.TranslationManager.getString("action_accept").takeIf { it != "action_accept" } ?: "Accept", color = Color.Black)
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = { data.onDecline() },
                    colors = ButtonDefaults.buttonColors(backgroundColor = ErrorMuted),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(com.joseph.clipboard.windows.storage.TranslationManager.getString("action_decline").takeIf { it != "action_decline" } ?: "Decline", color = ErrorText)
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
            border = androidx.compose.foundation.BorderStroke(1.dp, com.joseph.clipboard.windows.theme.HairlineDark),
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
                val reqText = com.joseph.clipboard.windows.storage.TranslationManager.getString("requesting_pair_title").takeIf { it != "requesting_pair_title" } ?: "Requesting Pair"
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
            }
        }
    }
}

@Composable
fun ReceiverFileOfferOverlay(session: com.joseph.clipboard.windows.IncomingFileSession, appController: ClipboardAppController) {
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
            border = androidx.compose.foundation.BorderStroke(1.dp, com.joseph.clipboard.windows.theme.HairlineDark),
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
                val incomingFilesText = com.joseph.clipboard.windows.storage.TranslationManager.getString("incoming_files_title").takeIf { it != "incoming_files_title" } ?: "Incoming File(s)"
                Text(incomingFilesText, style = MaterialTheme.typography.h5.copy(fontWeight = FontWeight.Bold, color = Color.White))
                Spacer(modifier = Modifier.height(8.dp))
                val fromText = com.joseph.clipboard.windows.storage.TranslationManager.getString("label_from").takeIf { it != "label_from" } ?: "From: "
                Text("$fromText${session.senderName}", color = GreenPrimary, fontSize = 16.sp)
                
                Spacer(modifier = Modifier.height(8.dp))
                val totalBytes = session.manifest.sumOf { it.second }
                val sizeStr = if (totalBytes > 1024 * 1024) {
                    String.format("%.2f MB", totalBytes / (1024f * 1024f))
                } else {
                    String.format("%.2f KB", totalBytes / 1024f)
                }
                val filesText = com.joseph.clipboard.windows.storage.TranslationManager.getString("label_files").takeIf { it != "label_files" } ?: "file(s)"
                Text("${session.manifest.size} $filesText - $sizeStr", color = Color.Gray, fontSize = 14.sp)
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Button(
                    onClick = { appController.acceptIncomingFileTransfer(session.sessionId) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = GreenPrimary),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(com.joseph.clipboard.windows.storage.TranslationManager.getString("action_accept").takeIf { it != "action_accept" } ?: "Accept", color = Color.Black)
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = { appController.declineIncomingFileTransfer() },
                    colors = ButtonDefaults.buttonColors(backgroundColor = ErrorMuted),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(com.joseph.clipboard.windows.storage.TranslationManager.getString("action_decline").takeIf { it != "action_decline" } ?: "Decline", color = ErrorText)
                }
            }
        }
    }
}

fun main() = application {
    val windowState = rememberWindowState()
    val appController = remember {
        ClipboardAppController().apply {
            start()
            // Every exit path (window close, tray Quit, Power Off, future ones) must release
            // the daemon ports and clipboard polling, but exitProcess(0) never reaches
            // DisposableEffect.onDispose. A JVM shutdown hook covers them all; stop() is
            // idempotent, so the onDispose call below double-firing it is harmless.
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { stop() } })
        }
    }
    // Tray menu callbacks fire on the AWT event thread; push the (potentially blocking) clipboard
    // read + network send onto IO so the tray/UI never stalls.
    val trayScope = remember { CoroutineScope(Dispatchers.IO) }

    DisposableEffect(Unit) {
        SystemTrayManager.initialize(
            onOpenApp = { windowState.isMinimized = false },
            onPushClipboard = { trayScope.launch { appController.pushCurrentClipboard() } },
            // Daemon teardown happens in the JVM shutdown hook (see appController above).
            onQuit = { exitProcess(0) }
        )
        onDispose {
            trayScope.cancel()
            appController.stop()
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Clipboard Share",
        undecorated = true,
        icon = painterResource("icon.xml")
    ) {
        // Undecorated windows maximize to the FULL screen (covering the Windows taskbar) unless we
        // constrain the maximized bounds to the monitor's work area. Recompute before each maximize so
        // it follows the window onto whichever monitor it currently sits on.
        fun workArea(): java.awt.Rectangle? = window.graphicsConfiguration?.let { gc ->
            val b = gc.bounds
            val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
            java.awt.Rectangle(
                b.x + insets.left,
                b.y + insets.top,
                b.width - insets.left - insets.right,
                b.height - insets.top - insets.bottom,
            )
        }
        // setMaximizedBounds takes the same user-space coordinates gc.bounds/getScreenInsets report,
        // and a spec-correct runtime scales them to device pixels exactly once. A previous build
        // pre-divided by the monitor scale unconditionally (countering a JBR seen applying the scale
        // twice), which on single-scaling runtimes shrank every maximize to workArea/scale — the
        // "maximized window fills only ~2/3 of the screen at 150%" bug. Neither behavior can be
        // assumed, so calibrate at runtime instead: apply the spec-correct rect, and if the
        // post-maximize check (calibrateMaximizedBoundsOnce below) sees the real bounds overflowing
        // the work area, flip to the pre-divided rect and re-maximize once, keeping that answer for
        // the rest of the session.
        val divideMaximizedBounds = remember { mutableStateOf(false) }
        fun refreshMaximizedBounds() {
            val gc = window.graphicsConfiguration ?: return
            val wa = workArea() ?: return
            window.maximizedBounds = if (divideMaximizedBounds.value) {
                val sx = gc.defaultTransform.scaleX
                val sy = gc.defaultTransform.scaleY
                java.awt.Rectangle(
                    (wa.x / sx).toInt(),
                    (wa.y / sy).toInt(),
                    (wa.width / sx).toInt(),
                    (wa.height / sy).toInt(),
                )
            } else wa
        }
        val uiScope = rememberCoroutineScope()
        fun calibrateMaximizedBoundsOnce() {
            if (divideMaximizedBounds.value) return // already calibrated this session
            uiScope.launch {
                // Bounded wait for the native maximize to land. This scope inherits the UI
                // dispatcher (AWT EDT), so the window reads/writes below are thread-safe.
                var waitedMs = 0L
                while ((window.extendedState and java.awt.Frame.MAXIMIZED_BOTH) != java.awt.Frame.MAXIMIZED_BOTH && waitedMs < 1000L) {
                    delay(50); waitedMs += 50
                }
                if ((window.extendedState and java.awt.Frame.MAXIMIZED_BOTH) != java.awt.Frame.MAXIMIZED_BOTH) return@launch
                delay(50) // let the bounds settle before measuring
                val wa = workArea() ?: return@launch
                // >10% overflow can't be insets noise, and the smallest real Windows scale step
                // (125%) overshoots by 25%, so this cleanly separates the two runtime behaviors.
                // A floating window can never exceed the work area by that much, so a user racing
                // the measurement with a restore can't trip a false positive.
                if (window.width > wa.width * 1.1 || window.height > wa.height * 1.1) {
                    divideMaximizedBounds.value = true
                    refreshMaximizedBounds()
                    windowState.placement = WindowPlacement.Floating
                    delay(50) // let the restore apply; same-frame placement writes would coalesce
                    windowState.placement = WindowPlacement.Maximized
                }
            }
        }
        LaunchedEffect(Unit) {
            refreshMaximizedBounds()
            appController.onRequestForeground = {
                windowState.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
        }
        val themeModeState = remember { mutableStateOf(ThemeMode.SYSTEM) }
        
        CompositionLocalProvider(LocalThemeMode provides themeModeState) {
            ClipboardTheme(themeMode = themeModeState.value) {
                Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                    WindowDraggableArea {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(modifier = Modifier.width(80.dp).fillMaxHeight(), color = MaterialTheme.colors.surface, elevation = 8.dp) {}
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { windowState.isMinimized = true }, modifier = Modifier.size(40.dp)) {
                                Box(modifier = Modifier.width(12.dp).height(2.dp).background(MaterialTheme.colors.onBackground))
                            }
                            
                            IconButton(onClick = {
                                if (windowState.placement == WindowPlacement.Maximized) {
                                    windowState.placement = WindowPlacement.Floating
                                } else {
                                    refreshMaximizedBounds()
                                    windowState.placement = WindowPlacement.Maximized
                                    calibrateMaximizedBoundsOnce()
                                }
                            }, modifier = Modifier.size(40.dp)) {
                                Box(modifier = Modifier.size(12.dp).border(1.5.dp, MaterialTheme.colors.onBackground))
                            }
                            IconButton(onClick = ::exitApplication, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colors.onBackground, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        AppContent(
                            onMinimize = { windowState.isMinimized = true },
                            appController = appController
                        )
                    }
                }
            }
        }
    }
}

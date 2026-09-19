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
import com.joseph.clipboard.windows.os.SystemTrayManager
import com.joseph.clipboard.windows.update.UpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess
import androidx.compose.foundation.BorderStroke
import com.joseph.clipboard.windows.os.SingleInstance
import com.joseph.clipboard.windows.storage.SecurityVault
import com.joseph.clipboard.windows.storage.TranslationManager
import com.joseph.clipboard.windows.theme.SurfaceElevatedDark
import com.joseph.clipboard.windows.theme.GreenText
import com.joseph.clipboard.windows.theme.HairlineDark
import com.joseph.clipboard.windows.theme.TextPrimaryDark
import com.joseph.clipboard.windows.theme.TextSecondaryDark
import com.joseph.clipboard.desktop.platform.Platform
import com.joseph.clipboard.windows.platform.WindowsPlatform

/** Minimum usable width (dp) for the floating portrait window: the 80.dp nav rail plus content. */
private const val MIN_FLOATING_WIDTH_DP = 340f

fun main() {
    // Must be the very first thing the process does: :desktop-core reaches the OS only through this
    // backend, and SecurityVault/DiagLog resolve their paths from it on first touch.
    Platform.install(WindowsPlatform)
    windowsApplication()
}

private fun windowsApplication() = application {
    // The floating (non-maximized) window is a tall, phone-shaped portrait: ~80% of the current
    // monitor's work-area height, with width derived from a 9:19.5 phone aspect — so it mirrors the
    // Android app's vertical shape rather than the monitor's landscape ratio. Computed from the
    // primary display before the ComposeWindow exists — this app has exactly one window and no
    // cross-launch position persistence — so it's what Compose would resolve to anyway, and doing it
    // statically avoids a startup flash from Compose's stock 800x600 box.
    //
    // Usability floor: a pure 9:19.5 window is narrow, so width is coerced to at least
    // MIN_FLOATING_WIDTH_DP (nav rail + content) and never wider than the work area (small screens).
    val defaultFloatingSize = remember {
        val gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
        val b = gc.bounds
        val waWidthPx = b.width - insets.left - insets.right
        val waHeightPx = b.height - insets.top - insets.bottom
        val heightDp = (waHeightPx * 0.80 / gc.defaultTransform.scaleY).toFloat()
        val maxWidthDp = (waWidthPx / gc.defaultTransform.scaleX).toFloat()
        val widthDp = (heightDp * (9f / 19.5f))
            .coerceIn(MIN_FLOATING_WIDTH_DP.coerceAtMost(maxWidthDp), maxWidthDp)
        DpSize(widthDp.dp, heightDp.dp)
    }
    val floatingAspectRatio = remember { (defaultFloatingSize.width.value / defaultFloatingSize.height.value).toDouble() }
    // Single source of truth for the resize floor — both window.minimumSize (below) and the resize
    // handles read this one value, pinned to the same primary-monitor scale defaultFloatingSize used,
    // instead of each re-deriving it from window.graphicsConfiguration at a different point in time
    // (that used to let the two floors drift apart, or disagree on a null graphicsConfiguration).
    // The floor is the larger of half the default width and the usable minimum, so a portrait window
    // can't be dragged narrow enough to crush the nav rail + content.
    val minFloatingWidthPx = remember {
        val gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val floorDp = maxOf(defaultFloatingSize.width.value * 0.5f, MIN_FLOATING_WIDTH_DP)
        (floorDp * gc.defaultTransform.scaleX).toInt()
    }
    val windowState = rememberWindowState(size = defaultFloatingSize)
    // Publish the single-instance mutex once (not per recomposition) so the Inno installer's AppMutex
    // can detect a running instance — even one only minimized to the system tray — and prompt the user
    // to close it before an in-place upgrade replaces an in-use runtime file
    // (runtime\bin\ucrtbase.dll -> "MoveFile failed; code 5"). Best-effort; see os/SingleInstance.kt.
    remember { SingleInstance.acquire() }
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
        title = "OffBridge",
        undecorated = true,
        // The four corner handles below are the only way to resize the floating window — disabling
        // native resize here guarantees there's exactly one resize mechanism, not a native one
        // fighting the ratio-locked one (undecorated windows are inconsistent across JDK/runtime
        // versions about whether they keep a native resize border at all).
        resizable = false,
        // The full launcher mark — white glyph ON its #1E1E1E squircle, exactly what the phone shows.
        // icon.xml is only the adaptive icon's FOREGROUND layer, so the taskbar got a white glyph on
        // nothing; against a light taskbar that is very nearly an empty slot.
        icon = painterResource("windows/app-256.png")
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
            // Secondary floor alongside the resize handles' own coerceAtLeast — minimumSize doesn't
            // self-enforce on the handles' programmatic window.setBounds calls, but it's cheap
            // insurance against any other code path resizing the window smaller than usable.
            window.minimumSize = java.awt.Dimension(
                minFloatingWidthPx,
                kotlin.math.round(minFloatingWidthPx / floatingAspectRatio).toInt()
            )
            appController.onRequestForeground = {
                windowState.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
        }
        // Passive update check, well after startup so it never competes with daemon/UI init. Only
        // drives the sidebar badge (checkForUpdates(force = false) also respects the cooldown + the
        // Settings toggle) — the Updates page runs its own always-fresh check when opened.
        LaunchedEffect(Unit) {
            delay(10_000)
            appController.checkForUpdates(force = false)
        }
        // Seed from config.json; anything unreadable (missing field, hand-edited garbage) falls back
        // to SYSTEM rather than crashing.
        val themeModeState = remember {
            mutableStateOf(
                runCatching { ThemeMode.valueOf(SecurityVault.getTheme()) }
                    .getOrDefault(ThemeMode.SYSTEM)
            )
        }
        // Single persistence point for every theme writer (Settings dialog, power menu, future ones):
        // whoever flips the state, the choice lands in config.json. The guard skips the redundant
        // save on first composition.
        LaunchedEffect(themeModeState.value) {
            val name = themeModeState.value.name
            if (SecurityVault.getTheme() != name) {
                // setTheme → save() rewrites config.json synchronously — keep it off the UI thread.
                withContext(Dispatchers.IO) {
                    SecurityVault.setTheme(name)
                }
            }
        }

        CompositionLocalProvider(LocalThemeMode provides themeModeState) {
            ClipboardTheme(themeMode = themeModeState.value) {
                Box(modifier = Modifier.fillMaxSize()) {
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
                    // Maximized fills the whole work area — nothing to resize into, so the handles
                    // only make sense (and only render) while floating.
                    if (windowState.placement == WindowPlacement.Floating) {
                        ResizeHandle(ResizeCorner.TopStart, window, floatingAspectRatio, minFloatingWidthPx, Alignment.TopStart)
                        ResizeHandle(ResizeCorner.TopEnd, window, floatingAspectRatio, minFloatingWidthPx, Alignment.TopEnd)
                        ResizeHandle(ResizeCorner.BottomStart, window, floatingAspectRatio, minFloatingWidthPx, Alignment.BottomStart)
                        ResizeHandle(ResizeCorner.BottomEnd, window, floatingAspectRatio, minFloatingWidthPx, Alignment.BottomEnd)
                    }
                }
            }
        }
    }
}

private enum class ResizeCorner { TopStart, TopEnd, BottomStart, BottomEnd }

// Undecorated + resizable=false (see the Window(...) call above) means there is no native resize
// border at all — these four corner handles are the *only* way to resize the floating window. Each
// drag derives the other dimension from the locked aspect ratio, so the shape is correct at every
// intermediate frame instead of being corrected after the fact; the opposite corner stays fixed as
// the anchor. Plain edges are deliberately not draggable — under a locked ratio a pure edge-drag is
// redundant with a corner-drag along one axis, so four handles already give full resize coverage.
//
// The top corners are inset below the 40dp title bar (see TITLE_BAR_HEIGHT below) rather than
// sitting flush in the true corner: the Close button occupies that exact top-right 40x40dp square
// with no margin, so an un-inset handle there would sit entirely inside the Close button's clickable
// area and steal every click aimed at closing the window.
private val TITLE_BAR_HEIGHT = 40.dp

@Composable
private fun BoxScope.ResizeHandle(
    corner: ResizeCorner,
    window: ComposeWindow,
    aspectRatio: Double,
    minWidthPx: Int,
    alignment: Alignment,
) {
    val topInset = if (corner == ResizeCorner.TopStart || corner == ResizeCorner.TopEnd) TITLE_BAR_HEIGHT else 0.dp
    Box(
        modifier = Modifier
            .align(alignment)
            .offset(y = topInset)
            .size(14.dp)
            .pointerInput(corner) {
                var startBounds = window.bounds
                var cumDxPx = 0f
                var cumDyPx = 0f
                detectDragGestures(
                    onDragStart = {
                        startBounds = window.bounds
                        cumDxPx = 0f
                        cumDyPx = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // dragAmount arrives in Compose's density-scaled pointer space, but
                        // window.bounds/setBounds are unscaled AWT logical units — dividing by the
                        // monitor's transform here keeps the two in the same unit (on a scaled
                        // display, skipping this makes the handle run away from the cursor by
                        // exactly the scale factor).
                        val gc = window.graphicsConfiguration
                        val scaleX = (gc?.defaultTransform?.scaleX ?: 1.0).toFloat()
                        val scaleY = (gc?.defaultTransform?.scaleY ?: 1.0).toFloat()
                        cumDxPx += (if (corner == ResizeCorner.TopEnd || corner == ResizeCorner.BottomEnd) {
                            dragAmount.x
                        } else {
                            -dragAmount.x
                        }) / scaleX
                        cumDyPx += (if (corner == ResizeCorner.BottomStart || corner == ResizeCorner.BottomEnd) {
                            dragAmount.y
                        } else {
                            -dragAmount.y
                        }) / scaleY
                        // Whichever axis moved further (in its own raw pixels) drives the resize —
                        // covers a pure vertical drag on a corner, not just horizontal.
                        val newWidthPx = (
                            if (kotlin.math.abs(cumDxPx) >= kotlin.math.abs(cumDyPx)) {
                                startBounds.width + cumDxPx
                            } else {
                                (startBounds.height + cumDyPx) * aspectRatio.toFloat()
                            }
                        ).toInt().coerceAtLeast(minWidthPx)
                        val newHeightPx = kotlin.math.round(newWidthPx / aspectRatio).toInt()
                        val newX = if (corner == ResizeCorner.TopStart || corner == ResizeCorner.BottomStart) {
                            startBounds.x + startBounds.width - newWidthPx
                        } else {
                            startBounds.x
                        }
                        val newY = if (corner == ResizeCorner.TopStart || corner == ResizeCorner.TopEnd) {
                            startBounds.y + startBounds.height - newHeightPx
                        } else {
                            startBounds.y
                        }
                        window.setBounds(newX, newY, newWidthPx, newHeightPx)
                    }
                )
            }
    )
}

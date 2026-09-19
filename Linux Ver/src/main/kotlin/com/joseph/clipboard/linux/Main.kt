package com.joseph.clipboard.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.joseph.clipboard.desktop.platform.Platform
import com.joseph.clipboard.linux.platform.LinuxPlatform
import com.joseph.clipboard.windows.AppContent
import com.joseph.clipboard.windows.ClipboardAppController
import com.joseph.clipboard.windows.LocalThemeMode
import com.joseph.clipboard.windows.storage.SecurityVault
import com.joseph.clipboard.windows.storage.TranslationManager
import com.joseph.clipboard.windows.theme.ClipboardTheme
import com.joseph.clipboard.windows.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Must precede everything: :desktop-core reaches the OS only through this backend, and
    // SecurityVault/DiagLog resolve their paths from it on first touch.
    Platform.install(LinuxPlatform)
    LinuxPlatform.hardenConfigPermissions()

    if (args.contains("--selftest")) {
        exitProcess(LinuxSelfTest.run())
    }

    linuxApplication()
}

private fun linuxApplication() = application {
    val windowState = rememberWindowState(size = DpSize(420.dp, 820.dp))

    val appController = remember {
        ClipboardAppController().apply {
            start()
            // Every exit path must release the daemon ports and clipboard polling, but
            // exitProcess(0) never reaches DisposableEffect.onDispose. A JVM shutdown hook covers
            // them all; stop() is idempotent, so onDispose double-firing it is harmless.
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { stop() } })
        }
    }

    // Tray callbacks arrive on the AWT event thread; push the (potentially blocking) clipboard read
    // and network send onto IO so the tray never stalls the UI.
    val trayScope = remember { CoroutineScope(Dispatchers.IO) }

    DisposableEffect(Unit) {
        // On a stock GNOME session this is a no-op — SystemTray.isSupported() is false there, and
        // LinuxTray falls back to notify-send for messages. The app stays fully usable without it.
        Platform.current.tray.initialize(
            onOpenApp = { windowState.isMinimized = false },
            onPushClipboard = { trayScope.launch { appController.pushCurrentClipboard() } },
            onQuit = { exitProcess(0) },
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
        // Deliberately decorated, unlike the Windows client. That one draws its own title bar and
        // carries ~150 lines of JBR-specific maximize/DPI calibration; none of it transfers to
        // X11/Wayland, where the compositor owns window decoration and fractional scaling.
        icon = painterResource("linux/app-256.png"),
    ) {
        LaunchedEffect(Unit) {
            appController.onRequestForeground = {
                windowState.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
        }
        // Passive update check, well after startup so it never competes with daemon/UI init.
        LaunchedEffect(Unit) {
            delay(10_000)
            appController.checkForUpdates(force = false)
        }

        val themeModeState = remember {
            mutableStateOf(
                runCatching { ThemeMode.valueOf(SecurityVault.getTheme()) }
                    .getOrDefault(ThemeMode.SYSTEM)
            )
        }
        LaunchedEffect(themeModeState.value) {
            val name = themeModeState.value.name
            if (SecurityVault.getTheme() != name) {
                withContext(Dispatchers.IO) { SecurityVault.setTheme(name) }
            }
        }

        CompositionLocalProvider(LocalThemeMode provides themeModeState) {
            ClipboardTheme(themeMode = themeModeState.value) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)
                ) {
                    AppContent(
                        onMinimize = { windowState.isMinimized = true },
                        appController = appController,
                    )
                }
            }
        }
    }
}

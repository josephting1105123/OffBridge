package com.joseph.clipboard.windows.platform

import com.joseph.clipboard.desktop.platform.FileDialogs
import com.joseph.clipboard.desktop.platform.HotspotInfo
import com.joseph.clipboard.desktop.platform.NotificationLevel
import com.joseph.clipboard.desktop.platform.OffGridHost
import com.joseph.clipboard.desktop.platform.PlatformId
import com.joseph.clipboard.desktop.platform.PlatformServices
import com.joseph.clipboard.desktop.platform.SecretStore
import com.joseph.clipboard.desktop.platform.TrayService
import com.joseph.clipboard.desktop.platform.Updater
import com.joseph.clipboard.windows.network.HotspotController
import com.joseph.clipboard.windows.network.control.BleControlPeripheral
import com.joseph.clipboard.windows.network.control.ControlPeripheral
import com.joseph.clipboard.windows.os.FileDialogHelper
import com.joseph.clipboard.windows.os.SingleInstance
import com.joseph.clipboard.windows.os.SystemTrayManager
import com.joseph.clipboard.windows.update.AppVersion
import com.joseph.clipboard.windows.update.UpdateInstaller
import java.awt.Desktop
import java.awt.TrayIcon
import java.io.File
import java.net.URI

/**
 * The Windows backend for :desktop-core's platform seam. Everything here is a thin adapter over the
 * Windows-only implementations that stayed in this module — this file adds no behaviour of its own,
 * so the Windows client behaves exactly as it did before the split.
 */
object WindowsPlatform : PlatformServices {
    override val id = PlatformId.WINDOWS
    override val appVersion: String get() = AppVersion.VERSION
    override val defaultDeviceName = "Windows PC"

    override val configDir: File =
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "OffBridge")

    override val defaultDownloadDir: File = File(System.getProperty("user.home"), "Downloads")

    override val secrets: SecretStore = DpapiSecretStore
    override val tray: TrayService = WindowsTray
    override val fileDialogs: FileDialogs = WindowsFileDialogs
    override val updater: Updater = WindowsUpdater
    override val offGridHost: OffGridHost = WindowsOffGridHost

    override fun acquireSingleInstanceLock(): Boolean {
        // The Windows guard is advisory — it publishes a named mutex for the Inno installer's
        // AppMutex check rather than preventing a second launch, so it always reports success.
        SingleInstance.acquire()
        return true
    }
}

/** DPAPI (Windows Data Protection API), reached through JNA's Crypt32 binding. */
private object DpapiSecretStore : SecretStore {
    override val available: Boolean =
        try { com.sun.jna.Platform.isWindows() } catch (e: Throwable) { false }

    override fun wrap(raw: ByteArray): ByteArray? = try {
        com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(raw)
    } catch (e: Throwable) {
        null
    }

    override fun unwrap(wrapped: ByteArray): ByteArray? = try {
        com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(wrapped)
    } catch (e: Throwable) {
        null
    }
}

private object WindowsTray : TrayService {
    override val available: Boolean = try {
        java.awt.SystemTray.isSupported()
    } catch (e: Throwable) {
        false
    }

    override fun initialize(onOpenApp: () -> Unit, onPushClipboard: () -> Unit, onQuit: () -> Unit) {
        SystemTrayManager.initialize(onOpenApp, onPushClipboard, onQuit)
    }

    override fun showNotification(title: String, message: String, level: NotificationLevel) {
        SystemTrayManager.showNotification(title, message, level.toAwt())
    }

    private fun NotificationLevel.toAwt(): TrayIcon.MessageType = when (this) {
        NotificationLevel.INFO -> TrayIcon.MessageType.INFO
        NotificationLevel.WARNING -> TrayIcon.MessageType.WARNING
        NotificationLevel.ERROR -> TrayIcon.MessageType.ERROR
    }
}

private object WindowsFileDialogs : FileDialogs {
    override fun openFile(title: String): File? = FileDialogHelper.openFilePicker(title)
    override fun openMultipleFiles(title: String): List<File> = FileDialogHelper.openMultipleFilesPicker(title)
    override fun openDirectory(title: String): File? = FileDialogHelper.openDirectoryPicker(title)
}

private object WindowsUpdater : Updater {
    override val canSelfInstall = true

    override fun download(url: String, version: String): File = UpdateInstaller.download(url, version)

    override fun launchAndExit(installer: File) = UpdateInstaller.launchAndExit(installer)

    override fun openReleasePage(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url))
            }
        }
    }
}

/** Wi-Fi Direct SoftAP plus the BLE GATT peripheral, both via WinRT. */
private object WindowsOffGridHost : OffGridHost {
    override val supported = true

    override fun start(): HotspotInfo? = HotspotController.start()
    override fun restart(): HotspotInfo? = HotspotController.restart()
    override fun stop() = HotspotController.stop()

    override fun createControlPeripheral(): ControlPeripheral = BleControlPeripheral()
}

package com.joseph.clipboard.linux.platform

import com.joseph.clipboard.desktop.platform.FileDialogs
import com.joseph.clipboard.desktop.platform.HotspotInfo
import com.joseph.clipboard.desktop.platform.NotificationLevel
import com.joseph.clipboard.desktop.platform.OffGridHost
import com.joseph.clipboard.desktop.platform.PlatformId
import com.joseph.clipboard.desktop.platform.PlatformServices
import com.joseph.clipboard.desktop.platform.SecretStore
import com.joseph.clipboard.desktop.platform.TrayService
import com.joseph.clipboard.desktop.platform.Updater
import com.joseph.clipboard.linux.update.AppVersion
import com.joseph.clipboard.windows.network.control.ControlPeripheral
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * The Linux backend for :desktop-core's platform seam.
 *
 * v1 is LAN + clipboard only. The off-grid ladder's host side (Wi-Fi Direct SoftAP, BLE GATT
 * peripheral) reports unsupported rather than being faked, so the transport ladder skips those
 * tiers cleanly and the UI can say so honestly.
 */
object LinuxPlatform : PlatformServices {
    override val id = PlatformId.LINUX
    override val appVersion: String get() = AppVersion.VERSION
    override val defaultDeviceName = "Linux PC"

    /** $XDG_CONFIG_HOME/OffBridge, falling back to the spec's ~/.config default. */
    override val configDir: File = File(XdgDirs.configHome(), "OffBridge")

    override val defaultDownloadDir: File = XdgDirs.downloadDir()

    override val secrets: SecretStore = NoSecretStore
    override val tray: TrayService = LinuxTray
    override val fileDialogs: FileDialogs = LinuxFileDialogs
    override val updater: Updater = LinuxUpdater
    override val offGridHost: OffGridHost = UnsupportedOffGridHost

    override fun acquireSingleInstanceLock(): Boolean = SingleInstanceLock.acquire(configDir)

    /**
     * Tightens the config directory to 0700 and every file in it to 0600.
     *
     * This is the mitigation that stands in for Windows' DPAPI while [NoSecretStore] reports
     * unavailable: pairing keys sit in config.json as base64, so at minimum no other local user
     * should be able to read them. Called from main() after the config dir exists. Best-effort —
     * a filesystem without POSIX permissions (a FAT-formatted home, some container mounts) is not
     * a reason to refuse to start.
     */
    fun hardenConfigPermissions() {
        runCatching {
            configDir.mkdirs()
            val dirPerms = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
            Files.setPosixFilePermissions(configDir.toPath(), dirPerms)
            val filePerms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            configDir.listFiles()?.filter { it.isFile }?.forEach {
                runCatching { Files.setPosixFilePermissions(it.toPath(), filePerms) }
            }
        }
    }
}

/**
 * XDG Base Directory resolution. Pure string/env logic on purpose — it is the part of the Linux
 * backend most likely to be wrong, and keeping it free of side effects lets it be unit-tested from
 * the Windows dev machine via [resolveConfigHome]/[parseUserDirs].
 */
object XdgDirs {
    fun configHome(): File = File(resolveConfigHome(System.getenv(), System.getProperty("user.home")))

    /** $XDG_CONFIG_HOME when set and absolute, else $HOME/.config (per the XDG spec, a relative
     *  value is invalid and must be ignored). */
    fun resolveConfigHome(env: Map<String, String>, home: String): String {
        val xdg = env["XDG_CONFIG_HOME"]
        return if (!xdg.isNullOrBlank() && xdg.startsWith("/")) xdg else "$home/.config"
    }

    fun downloadDir(): File {
        val home = System.getProperty("user.home")
        val fromEnv = System.getenv("XDG_DOWNLOAD_DIR")
        if (!fromEnv.isNullOrBlank() && fromEnv.startsWith("/")) return File(fromEnv)
        val userDirs = File(configHome(), "user-dirs.dirs")
        val parsed = runCatching {
            if (userDirs.isFile) parseUserDirs(userDirs.readText(), home) else null
        }.getOrNull()
        return File(parsed ?: "$home/Downloads")
    }

    /**
     * Pulls XDG_DOWNLOAD_DIR out of a user-dirs.dirs file. Lines look like
     * `XDG_DOWNLOAD_DIR="$HOME/Downloads"` — quoted, optionally $HOME-relative, and the file may
     * carry comments. Returns null when the key is absent or unusable.
     */
    fun parseUserDirs(contents: String, home: String): String? {
        val line = contents.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("#") }
            .lastOrNull { it.startsWith("XDG_DOWNLOAD_DIR=") }
            ?: return null
        val raw = line.substringAfter('=').trim().trim('"')
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("\$HOME/") -> home + raw.removePrefix("\$HOME")
            raw == "\$HOME" -> home
            raw.startsWith("/") -> raw
            else -> null // relative paths are not meaningful here
        }
    }
}

/**
 * No OS-backed secret store in v1, so pairing keys stay as legacy base64 in config.json exactly as
 * they do on any non-Windows run today, and [LinuxPlatform.hardenConfigPermissions] restricts the
 * file to the owner.
 *
 * Reporting `available = false` rather than faking success is deliberate: SecurityVault's
 * fail-closed contract means a store that claims to work and then can't unwrap would silently drop
 * the user's pairings. A libsecret/D-Bus implementation is the follow-up that closes this gap.
 */
private object NoSecretStore : SecretStore {
    override val available = false
    override fun wrap(raw: ByteArray): ByteArray? = null
    override fun unwrap(wrapped: ByteArray): ByteArray? = null
}

/**
 * AWT tray. [available] is genuinely false on a stock GNOME session — GNOME has shipped no legacy
 * XEmbed tray since 3.26, and `SystemTray.isSupported()` reports that honestly — so every call here
 * has to survive there being no tray at all.
 */
private object LinuxTray : TrayService {
    override val available: Boolean = try {
        SystemTray.isSupported()
    } catch (e: Throwable) {
        false
    }

    @Volatile private var trayIcon: TrayIcon? = null

    override fun initialize(onOpenApp: () -> Unit, onPushClipboard: () -> Unit, onQuit: () -> Unit) {
        if (!available) return
        runCatching {
            val image = java.awt.Toolkit.getDefaultToolkit()
                .createImage(javaClass.getResource("/linux/app-48.png"))
            val popup = java.awt.PopupMenu()
            java.awt.MenuItem("Open OffBridge").also { it.addActionListener { onOpenApp() }; popup.add(it) }
            java.awt.MenuItem("Push clipboard").also { it.addActionListener { onPushClipboard() }; popup.add(it) }
            popup.addSeparator()
            java.awt.MenuItem("Quit").also { it.addActionListener { onQuit() }; popup.add(it) }
            val icon = TrayIcon(image, "OffBridge", popup).apply {
                isImageAutoSize = true
                addActionListener { onOpenApp() }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
        }
    }

    override fun showNotification(title: String, message: String, level: NotificationLevel) {
        val icon = trayIcon
        if (icon != null) {
            runCatching { icon.displayMessage(title, message, level.toAwt()) }
            return
        }
        // No tray: fall back to the freedesktop notification daemon, which most desktops run even
        // when they have no XEmbed tray. Failing that the message is dropped rather than lost to an
        // exception — a notification must never break a transfer.
        runCatching {
            ProcessBuilder("notify-send", "--app-name=OffBridge", title, message)
                .redirectErrorStream(true)
                .start()
        }
    }

    private fun NotificationLevel.toAwt(): TrayIcon.MessageType = when (this) {
        NotificationLevel.INFO -> TrayIcon.MessageType.INFO
        NotificationLevel.WARNING -> TrayIcon.MessageType.WARNING
        NotificationLevel.ERROR -> TrayIcon.MessageType.ERROR
    }
}

/**
 * AWT dialogs throughout. The Windows client needed PowerShell/COM only because AWT's FileDialog
 * cannot pick directories on Windows; on Linux the Motif/GTK peer handles both modes, so the whole
 * shell-out disappears.
 */
private object LinuxFileDialogs : FileDialogs {
    override fun openFile(title: String): File? = awtDialog(title, multiple = false).firstOrNull()

    override fun openMultipleFiles(title: String): List<File> = awtDialog(title, multiple = true)

    override fun openDirectory(title: String): File? {
        // Swing's chooser is the portable way to get a directories-only picker; AWT's FileDialog
        // has no such mode outside macOS.
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
        }
        return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            null
        }
    }

    private fun awtDialog(title: String, multiple: Boolean): List<File> {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
        dialog.isMultipleMode = multiple
        dialog.isVisible = true
        return dialog.files?.toList().orEmpty()
    }
}

/**
 * Linux builds are installed as a .deb or run as an AppImage, both of which the user (or their
 * package manager) owns — replacing those from inside the running process is not something this app
 * should do. The Updates screen therefore offers the release page instead of an in-app install.
 */
private object LinuxUpdater : Updater {
    override val canSelfInstall = false

    override fun download(url: String, version: String): File =
        throw UnsupportedOperationException("Linux builds do not self-install; use openReleasePage")

    override fun launchAndExit(installer: File) =
        throw UnsupportedOperationException("Linux builds do not self-install; use openReleasePage")

    override fun openReleasePage(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url))
                return
            }
        }
        // Headless or a JDK without the Desktop peer — xdg-open is the desktop-agnostic fallback.
        runCatching { ProcessBuilder("xdg-open", url).start() }
    }
}

/**
 * Off-grid hosting is out of scope for the Linux v1: it would need a NetworkManager/wpa_supplicant
 * P2P group owner plus a BlueZ D-Bus GATT server, neither of which has anything in common with the
 * WinRT implementation. Reporting unsupported keeps the QR ladder's fallbacks honest.
 */
private object UnsupportedOffGridHost : OffGridHost {
    override val supported = false
    override fun start(): HotspotInfo? = null
    override fun restart(): HotspotInfo? = null
    override fun stop() = Unit
    override fun createControlPeripheral(): ControlPeripheral? = null
}

/**
 * Lock-file single-instance guard, the Linux counterpart to the Windows named mutex. Unlike the
 * Windows one (advisory, so an installer can detect a running app) this one genuinely reports
 * whether another instance holds the lock, since a second copy binding the same daemon ports would
 * fail in a much more confusing way.
 */
private object SingleInstanceLock {
    // Held for process lifetime on purpose; the OS releases it on exit, including a crash.
    @Volatile private var lock: FileLock? = null
    @Volatile private var handle: RandomAccessFile? = null

    fun acquire(configDir: File): Boolean {
        if (lock != null) return true
        return try {
            configDir.mkdirs()
            val file = RandomAccessFile(File(configDir, "instance.lock"), "rw")
            val acquired = file.channel.tryLock()
            if (acquired == null) {
                file.close()
                false
            } else {
                handle = file
                lock = acquired
                true
            }
        } catch (t: Throwable) {
            // An unlockable filesystem (some network homes) must not stop the app from starting.
            System.err.println("SingleInstanceLock: could not acquire lock (${t.message}) — continuing")
            true
        }
    }
}

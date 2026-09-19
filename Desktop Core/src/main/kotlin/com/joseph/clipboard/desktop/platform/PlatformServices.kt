package com.joseph.clipboard.desktop.platform

import com.joseph.clipboard.windows.network.control.ControlPeripheral
import java.io.File

/**
 * The one seam between the portable desktop code in :desktop-core and the OS it happens to be
 * running on. Before this existed the module had exactly one platform branch in ~19,000 lines
 * (`Platform.isWindows()` inside SecurityVault), so there was nowhere to plug a second OS in and
 * nothing to fake in a test.
 *
 * Two rules keep it useful:
 *  - Core code never asks *which* OS it is on, it asks the backend to do the thing. The one
 *    exception is [PlatformId.wireToken], which is data that genuinely differs per platform.
 *  - Every capability that an OS might not have returns an explicit "unsupported" value rather
 *    than throwing, so a missing tray or a missing radio is a supported state and not a crash.
 */
interface PlatformServices {
    val id: PlatformId

    /** This build's own version, injected because each platform module generates its own
     *  AppVersion from its own Gradle `version` (Android, Windows and Linux track separately).
     *  Feeds UpdateChecker.check(). */
    val appVersion: String

    /** Seed value for the user-visible device name on first run, before the user renames it. */
    val defaultDeviceName: String

    /** Where config.json and diagnostics.log live. Created on demand by the callers. */
    val configDir: File

    /** Default target for received files, used until the user picks their own. */
    val defaultDownloadDir: File

    val secrets: SecretStore
    val tray: TrayService
    val fileDialogs: FileDialogs
    val updater: Updater
    val offGridHost: OffGridHost

    /**
     * Best-effort "am I the only instance" guard. Windows needs it so the Inno installer can detect
     * a running instance before an in-place upgrade (the ucrtbase.dll lock); other platforms can use
     * a lock file or simply always succeed. Returns false when another instance already holds it.
     */
    fun acquireSingleInstanceLock(): Boolean
}

/**
 * [wireToken] is written into the 4th field of the UDP presence frame and parsed by the Android
 * client. These strings ARE wire protocol — changing one needs a matching Android change and an
 * interop check, exactly like the frame formats in WireFrames.
 */
enum class PlatformId(val wireToken: String) {
    WINDOWS("Windows"),
    LINUX("Linux"),
}

/**
 * Process-wide holder. The platform module's `main()` installs its backend as the very first thing
 * it does, before any daemon or vault is touched; core code reads [current] from then on.
 *
 * A holder rather than constructor injection because the code this seam serves is largely made of
 * long-lived `object` singletons (SecurityVault, DiagLog) reached from every layer — threading a
 * parameter through all of them would be a far larger and riskier change than the port itself.
 */
object Platform {
    @Volatile
    private var impl: PlatformServices? = null

    fun install(services: PlatformServices) {
        impl = services
    }

    val current: PlatformServices
        get() = impl ?: error(
            "PlatformServices was not installed. The platform module's main() must call " +
                "Platform.install(...) before touching any core code; a test must install a fake."
        )

    /** True once a backend is installed. Lets best-effort paths (DiagLog) degrade quietly instead
     *  of throwing during very early startup or in a test that never installed one. */
    val isInstalled: Boolean get() = impl != null
}

/**
 * Encryption-at-rest for pairing keys. Windows wraps them with DPAPI so config.json holds no
 * recoverable key material; Android's equivalent is the Keystore. A platform with no OS keystore
 * reports [available] = false and the caller keeps the legacy plaintext-base64 form.
 *
 * Both directions return null on failure rather than throwing, because the caller's contract is to
 * fail closed — a key that cannot be wrapped is dropped rather than written to disk in the clear.
 */
interface SecretStore {
    val available: Boolean

    /** Wrap raw key bytes for disk. Null means "could not encrypt" — caller must NOT persist. */
    fun wrap(raw: ByteArray): ByteArray?

    /** Unwrap what [wrap] produced. Null means corrupt or not decryptable by this user. */
    fun unwrap(wrapped: ByteArray): ByteArray?
}

/**
 * Tray icon and balloon notifications.
 *
 * [available] is load-bearing, not decoration: `java.awt.SystemTray.isSupported()` returns false on
 * a stock GNOME session, which has shipped no legacy tray since 3.26 without the AppIndicator
 * extension. Since the background clipboard-sync model leans on the tray, "no tray" has to be a
 * state the UI handles rather than a failure.
 */
interface TrayService {
    val available: Boolean

    fun initialize(onOpenApp: () -> Unit, onPushClipboard: () -> Unit, onQuit: () -> Unit)

    fun showNotification(title: String, message: String, level: NotificationLevel = NotificationLevel.INFO)
}

enum class NotificationLevel { INFO, WARNING, ERROR }

/** Native file/folder pickers. Cancelling returns null (or an empty list). Titles carry the same
 *  defaults the Windows helper used, so call sites read unchanged. */
interface FileDialogs {
    fun openFile(title: String = "Select File"): File?
    fun openMultipleFiles(title: String = "Select Files"): List<File>
    fun openDirectory(title: String = "Select Directory"): File?
}

/**
 * In-app update delivery. Windows downloads and launches a setup .exe, replacing the running app.
 * A platform that has no equivalent (Linux, where the app may be a .deb or an AppImage the user
 * manages) reports [canSelfInstall] = false, and the UI offers the release page instead.
 */
interface Updater {
    val canSelfInstall: Boolean

    /**
     * Download the installer. Throws on any failure — untrusted host, HTTP error, size mismatch —
     * rather than returning a partially-downloaded file, so the caller surfaces the reason instead
     * of ever launching a truncated installer. Only called when [canSelfInstall] is true.
     */
    fun download(url: String, version: String): File

    /** Launch the downloaded installer and exit. Does not return on success. */
    fun launchAndExit(installer: File)

    /** Fallback for [canSelfInstall] = false: open the release page in the user's browser. */
    fun openReleasePage(url: String)
}

/**
 * The off-grid QR ladder's host side: stand up a SoftAP and a BLE GATT peripheral so a phone with
 * no shared LAN can still reach us. Windows drives WinRT for both; Linux would need NetworkManager
 * P2P-GO plus a BlueZ D-Bus GATT server, which is out of scope for the Linux v1.
 *
 * When [supported] is false the transport ladder must skip these tiers cleanly — that skip is
 * exactly what the capability-degradation tests assert.
 */
interface OffGridHost {
    val supported: Boolean

    /** Start the AP. Null when unsupported or the adapter could not bring one up. */
    fun start(): HotspotInfo?

    /** Tear down and stand up a fresh AP, returning the new credentials. */
    fun restart(): HotspotInfo?

    fun stop()

    /**
     * A BLE control peripheral for this QR session, or null when the platform has no GATT-server
     * support. Null is a supported outcome, not an error: the session drops the BLE_ADVERTISE
     * capability and continues QR-only.
     */
    fun createControlPeripheral(): ControlPeripheral?
}

/** Credentials a phone needs to join the host's SoftAP. Plain data so it lives in core even on
 *  platforms that can never produce one. */
data class HotspotInfo(
    val ssid: String,
    val passphrase: String,
    val gatewayIp: String,
)

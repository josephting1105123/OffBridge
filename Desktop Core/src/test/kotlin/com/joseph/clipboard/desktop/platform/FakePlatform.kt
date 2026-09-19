package com.joseph.clipboard.desktop.platform

import com.joseph.clipboard.windows.network.control.ControlPeripheral
import java.io.File

/**
 * In-memory [PlatformServices] for unit tests. This is the point of the whole seam: it lets the
 * Linux client's behaviour — XDG paths, a missing tray, a plaintext vault, radios that report
 * unsupported — be asserted from the Windows dev machine and from the Linux CI runner alike,
 * without either OS actually being present.
 *
 * Every capability defaults to the *awkward* answer (no tray, no secret store, no self-install, no
 * off-grid host) so a test that forgets to opt in exercises the degradation path rather than the
 * happy path.
 */
class FakePlatform(
    override val id: PlatformId = PlatformId.LINUX,
    override val appVersion: String = "0.0.0-test",
    override val defaultDeviceName: String = "Test PC",
    override val configDir: File = createTempDir("offbridge-config"),
    override val defaultDownloadDir: File = createTempDir("offbridge-downloads"),
    override val secrets: SecretStore = FakeSecretStore(available = false),
    override val tray: FakeTray = FakeTray(available = false),
    override val fileDialogs: FileDialogs = FakeFileDialogs(),
    override val updater: Updater = FakeUpdater(canSelfInstall = false),
    override val offGridHost: OffGridHost = FakeOffGridHost(supported = false),
    private val singleInstanceAvailable: Boolean = true,
) : PlatformServices {

    override fun acquireSingleInstanceLock(): Boolean = singleInstanceAvailable

    companion object {
        private fun createTempDir(prefix: String): File =
            File.createTempFile(prefix, "").let { probe ->
                probe.delete()
                probe.mkdirs()
                probe.deleteOnExit()
                probe
            }

        /** Installs a fake for the duration of [body] and restores nothing afterwards — tests run in
         *  a fresh JVM per Gradle worker, and leaving it installed is what most tests want anyway. */
        fun install(platform: FakePlatform = FakePlatform()): FakePlatform {
            Platform.install(platform)
            return platform
        }
    }
}

/** Reversible byte-flip standing in for DPAPI: obviously not encryption, but it proves the caller
 *  wraps and unwraps through the seam instead of writing raw key bytes. */
class FakeSecretStore(
    override val available: Boolean,
    private val failWrap: Boolean = false,
    private val failUnwrap: Boolean = false,
) : SecretStore {
    override fun wrap(raw: ByteArray): ByteArray? =
        if (!available || failWrap) null else ByteArray(raw.size) { (raw[it].toInt() xor 0x5A).toByte() }

    override fun unwrap(wrapped: ByteArray): ByteArray? =
        if (!available || failUnwrap) null else ByteArray(wrapped.size) { (wrapped[it].toInt() xor 0x5A).toByte() }
}

/** Records what the app tried to show, so tests can assert notifications still resolve sensibly
 *  when [available] is false — the GNOME case we cannot reproduce in WSL. */
class FakeTray(override val available: Boolean) : TrayService {
    data class Notification(val title: String, val message: String, val level: NotificationLevel)

    val notifications = mutableListOf<Notification>()
    var initialized = false
        private set

    override fun initialize(onOpenApp: () -> Unit, onPushClipboard: () -> Unit, onQuit: () -> Unit) {
        initialized = available
    }

    override fun showNotification(title: String, message: String, level: NotificationLevel) {
        notifications += Notification(title, message, level)
    }
}

class FakeFileDialogs(
    private val file: File? = null,
    private val files: List<File> = emptyList(),
    private val directory: File? = null,
) : FileDialogs {
    override fun openFile(title: String): File? = file
    override fun openMultipleFiles(title: String): List<File> = files
    override fun openDirectory(title: String): File? = directory
}

class FakeUpdater(override val canSelfInstall: Boolean) : Updater {
    var openedPage: String? = null
        private set

    override fun download(url: String, version: String): File =
        throw UnsupportedOperationException("FakeUpdater cannot download; canSelfInstall=$canSelfInstall")

    override fun launchAndExit(installer: File) = Unit
    override fun openReleasePage(url: String) {
        openedPage = url
    }
}

class FakeOffGridHost(override val supported: Boolean) : OffGridHost {
    var startCount = 0
        private set

    override fun start(): HotspotInfo? {
        startCount++
        return if (supported) HotspotInfo("OffBridge-TEST", "testpass123", "192.168.137.1") else null
    }

    override fun restart(): HotspotInfo? = start()
    override fun stop() = Unit

    /** Always null: no test needs a real GATT server, and null is the Linux answer too — which is
     *  precisely the QR-only fallback path worth exercising. */
    override fun createControlPeripheral(): ControlPeripheral? = null
}

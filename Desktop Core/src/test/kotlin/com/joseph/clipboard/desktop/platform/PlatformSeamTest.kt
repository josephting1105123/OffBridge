package com.joseph.clipboard.desktop.platform

import com.joseph.clipboard.windows.network.TransferDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The platform seam's contract, including the parts that are wire protocol.
 *
 * Everything here runs on any OS, which is the point: a Linux client's degradation behaviour is
 * asserted from the Windows dev machine long before a Linux machine is involved.
 */
class PlatformSeamTest {

    /**
     * These two strings go into field 3 of every FILEBRIDGE_DISCOVERY / FILEBRIDGE_REPLY frame and
     * Android branches on them (`os.equals("Windows", ignoreCase = true)` in HistoryFragment,
     * SystemStatusFragment and SendToDevicesFragment). Changing either one is a wire change and
     * needs a matching Android-side change plus an interop check — do not "fix" this test to make a
     * rename pass.
     */
    @Test
    fun `platform wire tokens are the exact strings Android parses`() {
        assertEquals("Windows", PlatformId.WINDOWS.wireToken)
        assertEquals("Linux", PlatformId.LINUX.wireToken)
    }

    /**
     * Dropping Wi-Fi Direct and BLE on Linux must NOT change the advertised capability list: those
     * tokens describe crypto capabilities (clipboard/file AEAD, admission proofs), not radios, and
     * Android's negotiation keys off them. A Linux build that advertised a shorter list would
     * silently renegotiate every paired peer down.
     */
    @Test
    fun `discovery capability tokens are unchanged by dropping the radios`() {
        assertEquals("GCM1", TransferDaemon.CLIPBOARD_GCM_CAP)
        assertEquals("FGCM1", TransferDaemon.FILE_GCM_CAP)
        assertEquals("FGCM2", TransferDaemon.FILE_GCM_CAP_V2)
        assertEquals("AUTH2", TransferDaemon.OFFER_AUTH_CAP)
        assertEquals("AUTH2R", TransferDaemon.ACCEPT_AUTH_CAP)
    }

    @Test
    fun `an unsupported off-grid host yields no hotspot and no control peripheral`() {
        val host = FakeOffGridHost(supported = false)
        assertFalse(host.supported)
        assertNull("a platform without Wi-Fi Direct must not invent a hotspot", host.start())
        assertNull(host.restart())
        assertNull("no GATT server means QR-only, not a crash", host.createControlPeripheral())
        host.stop() // must not throw
    }

    @Test
    fun `a supported off-grid host still reports no peripheral when it has no GATT server`() {
        val host = FakeOffGridHost(supported = true)
        val info = host.start()
        assertEquals("OffBridge-TEST", info?.ssid)
        assertNull(host.createControlPeripheral())
    }

    /**
     * The GNOME case: `SystemTray.isSupported()` is false there, so notifications must still be
     * accepted and routed somewhere rather than throwing into a transfer code path.
     */
    @Test
    fun `notifications are accepted when no tray is available`() {
        val tray = FakeTray(available = false)
        tray.initialize(onOpenApp = {}, onPushClipboard = {}, onQuit = {})
        assertFalse("a tray that isn't supported must not claim it initialized", tray.initialized)

        tray.showNotification("OffBridge", "Push failed", NotificationLevel.WARNING)
        assertEquals(1, tray.notifications.size)
        assertEquals(NotificationLevel.WARNING, tray.notifications.single().level)
    }

    @Test
    fun `an unavailable secret store refuses to wrap rather than returning the raw key`() {
        val store = FakeSecretStore(available = false)
        assertFalse(store.available)
        assertNull(
            "wrap() must fail closed — returning the plaintext would silently store key material",
            store.wrap(byteArrayOf(1, 2, 3))
        )
        assertNull(store.unwrap(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `an available secret store round-trips key bytes`() {
        val store = FakeSecretStore(available = true)
        val raw = byteArrayOf(0x00, 0x7F, -0x80, 0x42)
        val wrapped = requireNonNull(store.wrap(raw))
        assertFalse("wrapped form must differ from the raw key", raw.contentEquals(wrapped))
        assertTrue(raw.contentEquals(requireNonNull(store.unwrap(wrapped))))
    }

    /**
     * A wrapped entry that cannot be unwrapped — a Windows config.json opened on Linux, or a DPAPI
     * blob belonging to another user — must come back null so SecurityVault drops the node and
     * forces a re-pair, rather than treating an undecryptable blob as a usable key.
     */
    @Test
    fun `an undecryptable entry unwraps to null`() {
        val store = FakeSecretStore(available = true, failUnwrap = true)
        assertNull(store.unwrap(byteArrayOf(9, 9, 9)))
    }

    @Test
    fun `a platform that cannot self-install still offers the release page`() {
        val updater = FakeUpdater(canSelfInstall = false)
        assertFalse(updater.canSelfInstall)
        updater.openReleasePage("https://example.invalid/releases")
        assertEquals("https://example.invalid/releases", updater.openedPage)
    }

    @Test
    fun `installing a fake makes it the current platform`() {
        val fake = FakePlatform.install(FakePlatform(id = PlatformId.LINUX))
        assertTrue(Platform.isInstalled)
        assertEquals(PlatformId.LINUX, Platform.current.id)
        assertEquals("Linux", Platform.current.id.wireToken)
        assertEquals(fake.configDir, Platform.current.configDir)
    }

    private fun <T : Any> requireNonNull(value: T?): T = value ?: error("expected a non-null value")
}

package com.joseph.clipboard.linux

import com.joseph.clipboard.desktop.platform.Platform
import com.joseph.clipboard.windows.network.Ports
import com.joseph.clipboard.windows.storage.SecurityVault
import com.joseph.clipboard.windows.storage.TranslationManager
import java.io.File
import java.net.DatagramSocket
import java.net.ServerSocket

/**
 * Headless smoke test, reachable as `--selftest`.
 *
 * It exists because CI has no display and the desktop client is a GUI app: without this, a Linux
 * runner can compile the code and run the unit tests but can never answer "does this actually come
 * up on a real Linux machine". These checks are the ones that would break first on a path,
 * permission or socket regression, and every one of them is safe with no X11/Wayland session — the
 * app's own startup is not, because clipboard polling needs an AWT toolkit.
 *
 * Prints one line per check and returns a process exit code: 0 all passed, 1 otherwise.
 */
object LinuxSelfTest {

    fun run(): Int {
        val results = mutableListOf<Pair<String, String?>>()

        fun check(name: String, body: () -> Unit) {
            val failure = try {
                body()
                null
            } catch (t: Throwable) {
                t.message ?: t::class.java.simpleName
            }
            results += name to failure
        }

        println("OffBridge Linux self-test")
        println("  platform      : ${Platform.current.id} (wire token '${Platform.current.id.wireToken}')")
        println("  version       : ${Platform.current.appVersion}")
        println("  config dir    : ${Platform.current.configDir}")
        println("  download dir  : ${Platform.current.defaultDownloadDir}")
        println("  secret store  : ${if (Platform.current.secrets.available) "available" else "unavailable (keys stored as base64, dir 0700)"}")
        println("  tray          : ${if (Platform.current.tray.available) "available" else "unavailable (notify-send fallback)"}")
        println("  off-grid host : ${if (Platform.current.offGridHost.supported) "supported" else "unsupported (LAN only)"}")
        println()

        check("config dir is writable") {
            val dir = Platform.current.configDir
            dir.mkdirs()
            val probe = File(dir, ".selftest")
            probe.writeText("ok")
            val readBack = probe.readText()
            probe.delete()
            require(readBack == "ok") { "wrote 'ok' but read back '$readBack'" }
        }

        check("single-instance lock acquires") {
            require(Platform.current.acquireSingleInstanceLock()) {
                "lock refused — another instance running, or the filesystem rejects locks"
            }
        }

        // The four ports the installer opens and the daemons bind. Binding them here proves nothing
        // else on the box holds them and that an unprivileged user may take them, which is the most
        // common first-run surprise when moving to a new OS.
        check("TCP ${Ports.CLIPBOARD} (clipboard) binds") { ServerSocket(Ports.CLIPBOARD).close() }
        check("TCP ${Ports.FILE_TRANSFER} (file transfer) binds") { ServerSocket(Ports.FILE_TRANSFER).close() }
        check("TCP ${Ports.PAIRING} (pairing) binds") { ServerSocket(Ports.PAIRING).close() }
        check("UDP ${Ports.DISCOVERY} (discovery) binds") { DatagramSocket(Ports.DISCOVERY).close() }

        check("UDP broadcast is permitted") {
            DatagramSocket().use { it.broadcast = true }
        }

        check("vault round-trips through config.json") {
            val original = SecurityVault.getDeviceName()
            SecurityVault.setDeviceName("selftest-device")
            require(SecurityVault.getDeviceName() == "selftest-device") { "device name did not persist" }
            SecurityVault.setDeviceName(original)
        }

        check("translations load from the core jar") {
            val value = TranslationManager.getString("action_decline")
            require(value.isNotBlank()) { "empty translation lookup" }
        }

        println("Results:")
        var failed = 0
        for ((name, failure) in results) {
            if (failure == null) {
                println("  PASS  $name")
            } else {
                failed++
                println("  FAIL  $name — $failure")
            }
        }
        println()
        println(if (failed == 0) "All ${results.size} checks passed." else "$failed of ${results.size} checks FAILED.")
        return if (failed == 0) 0 else 1
    }
}

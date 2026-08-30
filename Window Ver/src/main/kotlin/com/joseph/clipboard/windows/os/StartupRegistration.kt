package com.joseph.clipboard.windows.os

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * "Start OffBridge when I sign in", as a per-user registry Run entry.
 *
 * The app had no autostart of any kind: no Run key, no Startup shortcut, no scheduled task. After
 * a reboot it was simply not running, which is most of why the phone would report a paired laptop
 * it could never actually reach. Phone Link is always there because Windows starts it; this is the
 * same mechanism.
 *
 * HKEY_CURRENT_USER rather than HKEY_LOCAL_MACHINE on purpose — it needs no elevation, so the
 * Settings toggle works at any time instead of only during an admin install, and it follows the
 * user rather than the machine.
 *
 * The registered command carries `--tray` so a login start goes straight to the tray instead of
 * throwing a window in the user's face every time they sign in.
 */
object StartupRegistration {

    // Raw string: an escaped Kotlin literal would need every path separator doubled.
    private const val RUN_KEY = """Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val VALUE_NAME = "OffBridge"

    /** Told to the launcher by [com.joseph.clipboard.windows.main] to start hidden. */
    const val TRAY_ARG = "--tray"

    /**
     * The packaged launcher's path, or null when there isn't one.
     *
     * Null means the app is running from Gradle (`ProcessHandle` reports java.exe, not a launcher),
     * where registering anything would write a Run entry pointing at a JDK that would never start
     * the app. Callers use null to disable the toggle rather than to report an error.
     */
    fun launcherPath(): String? {
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        val file = File(command)
        if (!isLauncherName(file.name)) return null
        if (!file.exists()) return null
        return file.absolutePath
    }

    /**
     * Whether a process-command filename looks like the packaged launcher rather than a JVM.
     *
     * Split out from [launcherPath] because it is the part that can be wrong without anyone
     * noticing: accepting java.exe would write a Run entry that starts a bare JVM at every login.
     */
    internal fun isLauncherName(fileName: String): Boolean {
        val name = fileName.lowercase()
        if (!name.endsWith(".exe")) return false
        return name != "java.exe" && name != "javaw.exe"
    }

    /**
     * The exact string written to the Run key.
     *
     * The quotes are load-bearing: the default install path contains "Program Files", and Windows
     * parses an unquoted command at the first space - it would look for C:Program.exe.
     */
    internal fun runCommandFor(path: String): String = "\"$path\" $TRAY_ARG"

    /** True when this machine can register autostart at all — i.e. we're running as a packaged app. */
    fun isSupported(): Boolean = launcherPath() != null

    /**
     * Whether the Run entry exists AND points at this build.
     *
     * The path check matters after a move or reinstall: a stale entry aimed at a deleted folder
     * would otherwise read as "enabled" while doing nothing at login, which is exactly the kind of
     * silent lie this whole change is meant to remove.
     */
    fun isEnabled(): Boolean = try {
        val stored = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
        val expected = launcherPath()
        stored != null && expected != null && stored.contains(expected, ignoreCase = true)
    } catch (t: Throwable) {
        // registryGetStringValue throws when the value is absent, which is the common case.
        false
    }

    /**
     * Writes or removes the Run entry. Returns true when the registry now matches [enabled].
     *
     * Quoting is not optional: the default install path contains "Program Files", and an unquoted
     * command would have Windows try `C:\Program.exe`.
     */
    fun setEnabled(enabled: Boolean): Boolean = try {
        if (enabled) {
            val path = launcherPath() ?: return false
            Advapi32Util.registrySetStringValue(
                WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, runCommandFor(path)
            )
        } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
        }
        true
    } catch (t: Throwable) {
        System.err.println("StartupRegistration: could not update the Run key: ${t.message}")
        false
    }
}

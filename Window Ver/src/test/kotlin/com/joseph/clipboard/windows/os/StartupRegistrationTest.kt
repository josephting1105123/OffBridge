package com.joseph.clipboard.windows.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two parts of autostart registration that can be silently wrong.
 *
 * The registry write itself isn't tested here on purpose: it would have to touch the real per-user
 * Run key, and a test that leaves the machine trying to launch a build output at every login is a
 * worse bug than the one it would catch.
 */
class StartupRegistrationTest {

    @Test
    fun acceptsThePackagedLauncher() {
        assertTrue(StartupRegistration.isLauncherName("OffBridge.exe"))
        assertTrue(StartupRegistration.isLauncherName("offbridge.EXE"))
    }

    @Test
    fun rejectsAJvmLaunchedFromGradle() {
        // Accepting these would register a Run entry that starts a bare JVM at every login.
        assertFalse(StartupRegistration.isLauncherName("java.exe"))
        assertFalse(StartupRegistration.isLauncherName("javaw.exe"))
        assertFalse(StartupRegistration.isLauncherName("JAVA.EXE"))
    }

    @Test
    fun rejectsAnythingThatIsNotAnExecutable() {
        assertFalse(StartupRegistration.isLauncherName("OffBridge"))
        assertFalse(StartupRegistration.isLauncherName("OffBridge.bat"))
        assertFalse(StartupRegistration.isLauncherName(""))
    }

    @Test
    fun quotesThePathSoAnInstallUnderProgramFilesSurvives() {
        // The space is what matters, not the separator: Windows parses an unquoted Run command at
        // the first space and would go looking for C:/Program.exe.
        val exe = "C:/Program Files/OffBridge/OffBridge.exe"
        assertEquals("\"" + exe + "\" --tray", StartupRegistration.runCommandFor(exe))
    }

    @Test
    fun alwaysAsksForATrayStart() {
        // A login start that throws a window on screen every morning is the reason people turn
        // autostart back off.
        assertTrue(StartupRegistration.runCommandFor("C:/x/OffBridge.exe").endsWith(" --tray"))
    }
}

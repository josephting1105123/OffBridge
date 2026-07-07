package com.joseph.clipboard.windows.os

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT.HANDLE

/**
 * Holds a named Windows mutex for the whole process lifetime so the Inno Setup installer's
 * `AppMutex=ClipboardShareInstanceMutex` can detect that Clipboard Share is running — even when the
 * window is only minimized to the system tray — and prompt the user to close it BEFORE an upgrade
 * tries to replace an in-use bundled runtime file (e.g. runtime\bin\ucrtbase.dll, which otherwise
 * fails with "MoveFile failed; code 5. Access is denied.").
 *
 * The name MUST match ClipboardShare.iss [Setup] AppMutex exactly. It is session-local (unprefixed):
 * a UAC-elevated installer runs in the SAME interactive session, so it can open a mutex this app
 * created at medium integrity. The handle is intentionally never closed — Windows frees it when the
 * process exits, which is exactly the window the installer cares about.
 *
 * Best-effort: a failure to create the mutex only weakens the installer's close-detection; it never
 * affects the running app. Only meaningful on Windows (the sole target of this module).
 */
object SingleInstance {
    /** Keep in lockstep with ClipboardShare.iss AppMutex. */
    const val MUTEX_NAME = "ClipboardShareInstanceMutex"

    @Volatile private var handle: HANDLE? = null

    fun acquire() {
        if (handle != null) return
        handle = try {
            // lpMutexAttributes=null (default DACL grants this user; the same user's elevated installer
            // token can open it), bInitialOwner=false, lpName=the shared name.
            Kernel32.INSTANCE.CreateMutex(null, false, MUTEX_NAME)
        } catch (t: Throwable) {
            System.err.println("SingleInstance: could not create '$MUTEX_NAME' mutex: ${t.message}")
            null
        }
    }
}

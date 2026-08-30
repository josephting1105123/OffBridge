package com.joseph.clipboard.windows.os

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT.HANDLE

/**
 * One running OffBridge per user session, and a way for a second launch to raise the first.
 *
 * The mutex has been here a while, but only as a flag for the installer: `AppMutex` in OffBridge.iss
 * reads this name to detect a running instance before an in-place upgrade overwrites a locked
 * runtime file. It never checked whether the mutex already existed, so a second launch happily ran
 * alongside the first, and the newcomer's daemons simply failed to bind the ports the original
 * already held — the app looked open and did nothing.
 *
 * That was survivable while every launch was a deliberate double-click. It stops being survivable
 * once "start with Windows" is on: login starts one copy, the user clicks the desktop shortcut, and
 * the visible window is the broken one. So the check is now real, and the second launch does the
 * useful thing instead — it signals the first to show itself and exits.
 *
 * The names are session-local (no `Global\` prefix) on purpose: an elevated installer in the same
 * session can still see them, and two different users logged into one machine each get their own
 * instance, which is what you want.
 */
object SingleInstance {

    const val MUTEX_NAME = "OffBridgeInstanceMutex"

    /** Auto-reset event a second launch pulses to ask the first instance to surface. */
    private const val SHOW_EVENT_NAME = "OffBridgeShowWindowEvent"

    /** From winnt.h. Not exposed as a JNA constant under a stable name, so spelled out here. */
    private const val EVENT_MODIFY_STATE = 0x0002

    @Volatile private var mutexHandle: HANDLE? = null
    @Volatile private var showEventHandle: HANDLE? = null
    @Volatile private var waiterThread: Thread? = null

    /**
     * Claims the instance mutex.
     *
     * Returns true when this process is the first instance and should carry on starting up, false
     * when another instance already holds it — in which case the caller should [signalExisting] and
     * exit.
     *
     * Fails open: if the Win32 call throws (non-Windows, a stripped runtime, a sandbox), this
     * returns true. Refusing to start because we couldn't ask is strictly worse than running twice.
     */
    fun acquire(): Boolean {
        if (mutexHandle != null) return true
        return try {
            val handle = Kernel32.INSTANCE.CreateMutex(null, false, MUTEX_NAME)
            val alreadyRunning = Kernel32.INSTANCE.GetLastError() == WinError.ERROR_ALREADY_EXISTS
            mutexHandle = handle
            !alreadyRunning
        } catch (t: Throwable) {
            System.err.println("SingleInstance: mutex unavailable, continuing: ${t.message}")
            true
        }
    }

    /**
     * Starts listening for a later launch asking us to surface. First instance only.
     *
     * [onShowRequested] fires on the waiter thread, so the callback must hop to the UI thread
     * itself. The thread is a daemon: it must never be the reason the JVM stays alive after the
     * user quits.
     */
    fun listenForShowRequests(onShowRequested: () -> Unit) {
        if (waiterThread != null) return
        val event = try {
            Kernel32.INSTANCE.CreateEvent(null, false, false, SHOW_EVENT_NAME)
        } catch (t: Throwable) {
            System.err.println("SingleInstance: show-event unavailable: ${t.message}")
            return
        } ?: return
        showEventHandle = event

        waiterThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val result = try {
                    Kernel32.INSTANCE.WaitForSingleObject(event, WinBase.INFINITE)
                } catch (t: Throwable) {
                    break
                }
                if (result != WinBase.WAIT_OBJECT_0) break
                try {
                    onShowRequested()
                } catch (t: Throwable) {
                    // A failure to raise the window must not kill the listener for the rest of
                    // the session — the next launch should still be able to ask.
                    System.err.println("SingleInstance: show request failed: ${t.message}")
                }
            }
        }.apply {
            name = "OffBridge-SingleInstance-Waiter"
            isDaemon = true
            start()
        }
    }

    /**
     * Asks the already-running instance to show its window. Called by the losing process just
     * before it exits; best-effort, because a first instance that is mid-shutdown is a normal race
     * and not worth reporting to the user.
     */
    fun signalExisting() {
        try {
            val event = Kernel32.INSTANCE.OpenEvent(EVENT_MODIFY_STATE, false, SHOW_EVENT_NAME) ?: return
            Kernel32.INSTANCE.SetEvent(event)
            Kernel32.INSTANCE.CloseHandle(event)
        } catch (t: Throwable) {
            System.err.println("SingleInstance: could not signal the running instance: ${t.message}")
        }
    }
}

package com.EntropicSoftwareLab.ClipboardShare.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide holder for the latest update-check result. A silent check fires at app launch
 * (SplashActivity) to pre-warm this, so opening the Updates page reveals the answer after the loader's
 * 2s floor instead of waiting on a fresh network round-trip. The page observes [status] and can force
 * a re-check ("Check again" / "Try again").
 */
object UpdateRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Checking)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    // Reuse a recent answered result rather than re-hitting GitHub on every launch/page-open. A failed
    // check is NOT cached (answered == resolved-with-an-answer), so a transient failure re-checks.
    private const val COOLDOWN_MS = 30L * 60 * 1000
    @Volatile private var lastCheckMs = 0L
    @Volatile private var inFlight = false

    /**
     * Kick off a check. [force] bypasses the cooldown (manual re-check); an in-flight check is never
     * duplicated. Safe to call from the main thread — the network runs on IO.
     */
    fun check(context: Context, force: Boolean) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (inFlight) return
            val answered = _status.value is UpdateStatus.UpToDate ||
                _status.value is UpdateStatus.UpdateAvailable
            if (!force && answered && lastCheckMs != 0L &&
                System.currentTimeMillis() - lastCheckMs < COOLDOWN_MS
            ) {
                return
            }
            inFlight = true
        }
        val version = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }
        scope.launch {
            try {
                _status.value = UpdateStatus.Checking
                _status.value = UpdateChecker.check(version)
                lastCheckMs = System.currentTimeMillis()
            } finally {
                inFlight = false
            }
        }
    }
}

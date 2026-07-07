package com.EntropicSoftwareLab.ClipboardShare

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide "is one of our activities currently visible" flag, maintained via
 * [Application.ActivityLifecycleCallbacks]. Registered once from [ClipboardService.onCreate]
 * (the daemon host), so it is live for the whole window during which an incoming file OFFER can
 * arrive — the file server only runs while the daemon is up.
 *
 * Used by [FileTransferServer] to decide whether an incoming transfer request should pop its
 * accept/decline screen immediately (app open) or only raise a notification (app backgrounded).
 * "Started" (onStart..onStop), not "resumed", is the right visibility signal: an activity is
 * visible to the user across that whole span, so a request should surface in-app even if a
 * transient dialog momentarily paused the top activity.
 */
object AppForeground {
    private val startedActivities = AtomicInteger(0)

    /** True while at least one of the app's activities is started (visible to the user). */
    val isForeground: Boolean get() = startedActivities.get() > 0

    @Volatile private var registered = false

    /** Idempotent: safe to call from every service start; only the first call registers callbacks. */
    @Synchronized
    fun register(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities.incrementAndGet() }
            override fun onActivityStopped(activity: Activity) {
                // Never let the count go negative if a stop is somehow seen without its start.
                startedActivities.updateAndGet { if (it > 0) it - 1 else 0 }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

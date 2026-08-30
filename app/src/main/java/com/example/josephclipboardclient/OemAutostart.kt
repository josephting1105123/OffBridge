package com.EntropicSoftwareLab.ClipboardShare

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Deep-links into the OEM "autostart" screens that decide whether this app is allowed to run at all.
 *
 * Stock Android has one background-execution gate (battery optimization) and the framework exposes
 * it. Several large OEMs added a second, stricter one that the framework does not expose and does
 * not report: until the user ticks the app in the vendor's own list, BOOT_COMPLETED is withheld and
 * the process is killed shortly after the last Activity goes away. From inside the app this is
 * indistinguishable from a healthy system that simply never delivers anything, which is why the app
 * appeared to "just close" with no error to show for it.
 *
 * There is no API to query these, only to open them, so this offers the door and cannot confirm the
 * user walked through it. Every component below is guarded by [resolves] — vendor package names
 * move between ROM versions, and an unresolvable Intent would throw ActivityNotFoundException.
 */
object OemAutostart {

    /**
     * Candidate screens per vendor, most specific first.
     *
     * More than one entry per vendor is deliberate: these components get renamed across ROM
     * versions, so the list is tried in order and the first that resolves wins.
     */
    private val CANDIDATES: List<Pair<String, ComponentName>> = listOf(
        // Xiaomi / Redmi / POCO (MIUI, HyperOS)
        "xiaomi" to ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        // Oppo / Realme (ColorOS) — the safecenter package was renamed at least twice.
        "oppo" to ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ),
        "oppo" to ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity"
        ),
        "oppo" to ComponentName(
            "com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity"
        ),
        "realme" to ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ),
        // Vivo / iQOO (Funtouch, OriginOS)
        "vivo" to ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
        "vivo" to ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
        ),
        // Huawei / Honor (EMUI, MagicOS)
        "huawei" to ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        "honor" to ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        // OnePlus (OxygenOS) — shares ColorOS internals on recent versions.
        "oneplus" to ComponentName(
            "com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
        ),
        // Asus (ZenUI)
        "asus" to ComponentName(
            "com.asus.mobilemanager",
            "com.asus.mobilemanager.autostart.AutoStartActivity"
        ),
        // Letv / Meizu, still common on older hardware.
        "letv" to ComponentName(
            "com.letv.android.letvsafe",
            "com.letv.android.letvsafe.AutobootManageActivity"
        ),
        "meizu" to ComponentName(
            "com.meizu.safe",
            "com.meizu.safe.security.SHOW_APPSEC"
        )
    )

    /**
     * True when this device is from a vendor known to gate autostart AND the screen actually
     * resolves. Callers use this to decide whether to show the row at all — offering it on a Pixel
     * would be noise, and offering it on a ROM where the Intent dead-ends would be worse.
     */
    fun isAvailable(context: Context): Boolean = resolveIntent(context) != null

    /**
     * Opens the vendor's autostart list, falling back to this app's own system settings page.
     *
     * The fallback matters: on a device whose vendor screen has moved, "Open settings" landing
     * somewhere useful beats a button that does nothing.
     */
    fun open(context: Context) {
        val intent = resolveIntent(context) ?: appSettingsIntent(context)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Some ROMs export the component but refuse the launch (SecurityException). Falling
            // back keeps the button honest rather than silently doing nothing.
            try {
                context.startActivity(appSettingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ignored: Exception) {
            }
        }
    }

    /** The vendor whose screen would open, for display ("Xiaomi", "Vivo"). Null when none matches. */
    fun vendorLabel(): String? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return CANDIDATES.firstOrNull { manufacturer.contains(it.first) }
            ?.first
            ?.replaceFirstChar { it.uppercase() }
    }

    private fun resolveIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        for ((vendor, component) in CANDIDATES) {
            if (!manufacturer.contains(vendor)) continue
            val intent = Intent().setComponent(component)
            if (resolves(context, intent)) return intent
        }
        return null
    }

    private fun resolves(context: Context, intent: Intent): Boolean = try {
        context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    private fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
}

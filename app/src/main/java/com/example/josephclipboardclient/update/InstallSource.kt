package com.EntropicSoftwareLab.ClipboardShare.update

import android.content.Context
import android.os.Build

/**
 * Tells whether this build was installed from the Google Play Store. The Updates page uses it to
 * route an available update: Play installs go to the Play listing (matching signature); anything else
 * (a sideloaded / GitHub-downloaded APK) updates in-app from the release APK.
 */
object InstallSource {
    private const val PLAY_STORE_PACKAGE = "com.android.vending"

    fun isFromPlay(context: Context): Boolean {
        val pm = context.packageManager
        val pkg = context.packageName
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkg)
            }
        } catch (e: Exception) {
            null
        }
        return installer == PLAY_STORE_PACKAGE
    }
}

package com.EntropicSoftwareLab.ClipboardShare.update

/**
 * State machine for the in-app "Updates" page. [UpdateAvailable] carries the release APK URL when the
 * latest GitHub release has one; the Updates page routes on install source — a Play install opens the
 * Play listing, a sideloaded install downloads [apkUrl] and hands it to the system installer.
 */
sealed class UpdateStatus {
    object Checking : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class UpdateAvailable(val version: String, val apkUrl: String?) : UpdateStatus()
    data class CheckFailed(val reason: String) : UpdateStatus()
}

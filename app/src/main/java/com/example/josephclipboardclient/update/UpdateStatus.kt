package com.EntropicSoftwareLab.ClipboardShare.update

/**
 * State machine for the in-app "Updates" page. When [UpdateAvailable] is reached, the page sends the
 * user to the Google Play listing to install it; the app never downloads or installs an update itself.
 */
sealed class UpdateStatus {
    object Checking : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class UpdateAvailable(val version: String) : UpdateStatus()
    data class CheckFailed(val reason: String) : UpdateStatus()
}

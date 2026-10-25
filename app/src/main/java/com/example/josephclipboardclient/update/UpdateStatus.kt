package com.EntropicSoftwareLab.ClipboardShare.update

/**
 * State machine for the in-app "Updates" page. When a newer version exists, the page sends the user to
 * the Play listing — the app never downloads or installs updates itself (Play policy).
 */
sealed class UpdateStatus {
    object Checking : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class UpdateAvailable(val version: String) : UpdateStatus()
    data class CheckFailed(val reason: String) : UpdateStatus()
}

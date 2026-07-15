package com.joseph.clipboard.windows.update

/** State of the in-app update checker — backs both the sidebar badge and the Updates page. */
sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    object Downloading : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class UpdateAvailable(
        val version: String,
        val notes: String,
        val downloadUrl: String,
    ) : UpdateStatus()
    data class CheckFailed(val reason: String) : UpdateStatus()
}

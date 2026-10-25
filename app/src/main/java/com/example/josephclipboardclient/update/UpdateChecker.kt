package com.EntropicSoftwareLab.ClipboardShare.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the OffBridge-Showcase GitHub repo's latest release for a newer Android build, then the
 * Updates screen sends the user to Google Play to install it (a Play-distributed app can't
 * self-install). Same repo the Windows checker and the website target.
 *
 * The repo tags releases by the WINDOWS version and carries only a Windows installer asset; the
 * Android version rides along in the release TITLE as "… / Android X.Y.Z" (Android ships via Play, so
 * there's no Android-versioned tag). So we read the Android version out of the title.
 * Never throws — network/parse failures come back as [UpdateStatus.CheckFailed].
 */
object UpdateChecker {
    private const val OWNER = "josephting1105123"
    private const val REPO = "OffBridge-Showcase"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    // Connect fails fast at 2s (unreachable/offline network); reads may stall at most 4s each.
    // Mirrors the Windows checker's 2s/4s split — detection is meant to resolve within the 5s
    // ceiling UpdateRepository enforces overall.
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val READ_TIMEOUT_MS = 4_000

    private val ANDROID_VERSION_IN_TITLE = Regex("""Android\s+([0-9][0-9.]*)""")

    suspend fun check(currentVersion: String): UpdateStatus = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub's REST API rejects unauthenticated requests that omit a User-Agent.
                setRequestProperty("User-Agent", "OffBridge-Android/$currentVersion")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext UpdateStatus.CheckFailed("HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val title = json.optString("name", "")
                val latest = ANDROID_VERSION_IN_TITLE.find(title)?.groupValues?.get(1)?.trim().orEmpty()
                if (latest.isEmpty()) {
                    return@withContext UpdateStatus.CheckFailed("Latest release has no Android version")
                }
                if (VersionCompare.isNewer(latest, currentVersion)) {
                    UpdateStatus.UpdateAvailable(latest)
                } else {
                    UpdateStatus.UpToDate(currentVersion)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            UpdateStatus.CheckFailed(e.message ?: e.javaClass.simpleName)
        }
    }
}

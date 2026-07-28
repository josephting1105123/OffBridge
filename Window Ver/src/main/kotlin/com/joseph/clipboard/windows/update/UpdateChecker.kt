package com.joseph.clipboard.windows.update

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Checks the OffBridge-Showcase GitHub repo's latest release for a newer Windows build.
 * Reads directly off the release the existing manual RUNBOOK process already publishes — no
 * separate manifest file, no extra release-day step. See release/RUNBOOK.md for the publish flow
 * (tag vX.Y.Z, attach a stably-named ClipboardShareSetup.exe asset).
 */
object UpdateChecker {
    // Same owner/repo the website's download button targets — see the CS_CONFIG.windows block in
    // website/js/config.js. The two are independent copies with nothing enforcing agreement; if the
    // releases repo is ever renamed, update both.
    private const val OWNER = "josephting1105123"
    private const val REPO = "OffBridge-Showcase"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val INSTALLER_ASSET_NAME = "ClipboardShareSetup.exe"

    // api.github.com returns the release JSON with 200 directly (no redirect today), but follow
    // redirects defensively — NORMAL follows them without allowing an HTTPS->HTTP downgrade.
    // Connect times out at 2s so an unreachable/offline network fails fast (the Updates page otherwise
    // sits on the loader); the whole request is hard-capped at 4s by HttpRequest.timeout below, keeping
    // detection inside the 5s ceiling ClipboardAppController.checkForUpdates enforces overall.
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val gson = Gson()

    private data class GitHubAsset(
        val name: String? = null,
        @SerializedName("browser_download_url") val browserDownloadUrl: String? = null,
    )

    private data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String? = null,
        val body: String? = null,
        val assets: List<GitHubAsset>? = null,
    )

    /**
     * Fetches the latest release and compares it against [currentVersion]. Never throws — network
     * or parse failures come back as [UpdateStatus.CheckFailed] instead. Blocking network call;
     * always invoke from a background dispatcher (IO), never the UI thread.
     */
    fun check(currentVersion: String): UpdateStatus {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(LATEST_RELEASE_URL))
                .header("Accept", "application/vnd.github+json")
                // GitHub's REST API rejects unauthenticated requests that omit a User-Agent header.
                .header("User-Agent", "ClipboardShare-Windows/$currentVersion")
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return UpdateStatus.CheckFailed("GitHub returned HTTP ${response.statusCode()}")
            }
            val release = gson.fromJson(response.body(), GitHubRelease::class.java)
            val remoteVersion = release?.tagName?.removePrefix("v")?.trim()
            val downloadUrl = release?.assets?.firstOrNull { it.name == INSTALLER_ASSET_NAME }?.browserDownloadUrl
            if (remoteVersion.isNullOrBlank() || downloadUrl.isNullOrBlank()) {
                return UpdateStatus.CheckFailed("Release response missing a version tag or installer asset")
            }
            if (VersionCompare.isNewer(remoteVersion, currentVersion)) {
                UpdateStatus.UpdateAvailable(
                    version = remoteVersion,
                    notes = release.body?.trim().orEmpty(),
                    downloadUrl = downloadUrl,
                )
            } else {
                UpdateStatus.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            UpdateStatus.CheckFailed(e.message ?: e.javaClass.simpleName)
        }
    }
}

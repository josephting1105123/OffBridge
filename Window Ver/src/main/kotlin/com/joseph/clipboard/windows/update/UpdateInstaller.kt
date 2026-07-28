package com.joseph.clipboard.windows.update

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Downloads and launches an update installer entirely in-app — no browser, no leaving the Updates
 * page. No SHA-256 pinning (see the plan's explicit scope decision): trust is HTTPS + the GitHub
 * account, same level as today's manual download from the release page.
 */
object UpdateInstaller {
    // The download URL comes from a parsed GitHub API response (UpdateChecker) — restrict it to
    // GitHub's own asset hosts before ever downloading/executing it, so a tampered or unexpected
    // API response can't redirect this flow into fetching + running an arbitrary binary.
    private val ALLOWED_HOST_SUFFIXES = listOf("github.com", "githubusercontent.com")

    class UntrustedDownloadHostException(host: String) :
        IOException("Refusing to download update installer from untrusted host: $host")

    // A GitHub release-asset URL (browser_download_url) 302-redirects to GitHub's CDN
    // (objects.githubusercontent.com), so redirects MUST be followed or every download fails with
    // HTTP 302. NORMAL (not ALWAYS) follows them but refuses an HTTPS->HTTP downgrade. The host
    // allowlist below validates the initial URL; the redirect stays within GitHub's own infra.
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Downloads the installer at [downloadUrl] to a temp file, verifying the byte count against
     * the server's Content-Length. Throws on any failure (untrusted host, HTTP error, size
     * mismatch) rather than returning a partially-downloaded file — callers surface the failure via
     * [UpdateStatus.CheckFailed] instead of ever launching a truncated installer.
     */
    fun download(downloadUrl: String, targetVersion: String): File {
        val uri = URI.create(downloadUrl)
        val host = uri.host?.lowercase() ?: ""
        if (ALLOWED_HOST_SUFFIXES.none { host == it || host.endsWith(".$it") }) {
            throw UntrustedDownloadHostException(host)
        }

        val tempDir = File(System.getProperty("java.io.tmpdir"), "OffBridgeUpdate")
        tempDir.mkdirs()
        val target = File(tempDir, "OffBridgeSetup-$targetVersion.exe")

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .header("User-Agent", "OffBridge-Windows/$targetVersion")
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target.toPath()))
        if (response.statusCode() !in 200..299) {
            target.delete()
            throw IOException("Download failed: HTTP ${response.statusCode()}")
        }
        val expectedLength = response.headers().firstValueAsLong("Content-Length").orElse(-1)
        if (expectedLength >= 0 && target.length() != expectedLength) {
            target.delete()
            throw IOException("Downloaded file size (${target.length()}) did not match Content-Length ($expectedLength)")
        }
        return target
    }

    /**
     * Launches the downloaded installer as a detached process and exits this app so the installer
     * can replace files currently in use. Does not return on success (mirrors the tray Quit path's
     * exitProcess(0) in Main.kt).
     */
    fun launchAndExit(installer: File) {
        ProcessBuilder(installer.absolutePath)
            .directory(installer.parentFile)
            .start()
        kotlin.system.exitProcess(0)
    }
}

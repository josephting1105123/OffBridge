package com.joseph.clipboard.windows.update

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
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
    // (objects.githubusercontent.com / release-assets.githubusercontent.com). The client does NOT
    // follow redirects itself: download() walks them by hand so every hop is checked against
    // [requireTrusted] before any body is written to disk. Letting the client follow them would only
    // let us check the final URL after the whole installer was already downloaded.
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private const val MAX_REDIRECTS = 5

    /** HTTPS only, and only GitHub's release hosts. Applied to the API-supplied URL and to every
     *  redirect hop. */
    internal fun requireTrusted(uri: URI) {
        if (!"https".equals(uri.scheme, ignoreCase = true)) {
            throw IOException("Refusing non-HTTPS update download: ${uri.scheme}")
        }
        val host = uri.host?.lowercase() ?: ""
        if (ALLOWED_HOST_SUFFIXES.none { host == it || host.endsWith(".$it") }) {
            throw UntrustedDownloadHostException(host)
        }
    }

    /** Resolves a redirect's Location against the URL that returned it (Location may be relative). */
    internal fun redirectTarget(current: URI, location: String?): URI {
        if (location.isNullOrBlank()) throw IOException("Redirect without a Location header")
        return current.resolve(URI.create(location.trim()))
    }

    /**
     * Downloads the installer at [downloadUrl] to a temp file, verifying the byte count against
     * the server's Content-Length. Throws on any failure (untrusted host or redirect hop, HTTP
     * error, size mismatch) rather than returning a partially-downloaded file — callers surface the
     * failure via [UpdateStatus.CheckFailed] instead of ever launching a truncated installer.
     */
    fun download(downloadUrl: String, targetVersion: String): File {
        var uri = URI.create(downloadUrl)
        requireTrusted(uri)

        val tempDir = File(System.getProperty("java.io.tmpdir"), "OffBridgeUpdate")
        tempDir.mkdirs()
        // targetVersion is the release tag from the API response; keep only version characters so a
        // hostile tag can't put path separators or ".." into the installer's file name.
        val safeVersion = targetVersion.filter { it.isLetterOrDigit() || it == '.' || it == '-' }
            .trim('.').ifEmpty { "latest" }
        val target = File(tempDir, "OffBridgeSetup-$safeVersion.exe")

        // Redirect responses have their body thrown away: only a 2xx from a checked host gets written
        // to [target]. ofFile() does not truncate, so clear any leftover from an earlier attempt first.
        target.delete()
        var hops = 0
        while (true) {
            val request = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", "OffBridge-Windows/$targetVersion")
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build()
            val response = try {
                client.send(request, HttpResponse.BodyHandler<Path?> { info ->
                    if (info.statusCode() in 200..299) HttpResponse.BodySubscribers.ofFile(target.toPath())
                    else HttpResponse.BodySubscribers.replacing(null)
                })
            } catch (e: Exception) {
                target.delete() // never leave a half-written installer behind
                throw e
            }
            val status = response.statusCode()
            if (status in 300..399) {
                if (++hops > MAX_REDIRECTS) throw IOException("Too many redirects downloading update")
                uri = redirectTarget(uri, response.headers().firstValue("Location").orElse(null))
                requireTrusted(uri)
                continue
            }
            if (status !in 200..299) {
                target.delete()
                throw IOException("Download failed: HTTP $status")
            }
            val expectedLength = response.headers().firstValueAsLong("Content-Length").orElse(-1)
            if (expectedLength >= 0 && target.length() != expectedLength) {
                target.delete()
                throw IOException("Downloaded file size (${target.length()}) did not match Content-Length ($expectedLength)")
            }
            return target
        }
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

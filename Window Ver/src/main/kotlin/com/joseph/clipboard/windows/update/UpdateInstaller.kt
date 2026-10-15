package com.joseph.clipboard.windows.update

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * Downloads and launches an update installer entirely in-app — no browser, no leaving the Updates
 * page. The installer runs elevated, so before it is launched its SHA-256 must match the value the
 * release ritual (commit-windows-version) publishes on the website at
 * offbridge.app/checksums/OffBridgeSetup-<version>.exe.sha256. That file comes from Cloudflare Pages,
 * not from the GitHub release that served the installer, so a swapped release asset alone can't pass.
 * Both are still pushed from the same GitHub account, so a full account takeover is not covered.
 * (This reverses the original no-pinning scope decision, on the user's call, 2026-10-15.) No
 * checksum, or a mismatch, means the installer is deleted and never run.
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

    // Where the release ritual publishes each installer's checksum. Exact host, no redirects
    // followed: the file is tiny and lives on our own site, so anything else is a misconfiguration.
    private const val CHECKSUM_HOST = "offbridge.app"
    private const val CHECKSUM_MAX_BYTES = 4096

    /** The installer could not be matched to its published checksum (missing, unreadable, or
     *  different). Deliberately not an IOException: the Updates page must not call this "your
     *  network". */
    class InstallerVerificationException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    internal fun checksumUrl(safeVersion: String): URI =
        URI("https", CHECKSUM_HOST, "/checksums/OffBridgeSetup-$safeVersion.exe.sha256", null)

    /** Reads the hash from a sha256sum-style body ("<64 hex>  name"). Null if it isn't one. */
    internal fun parseChecksum(body: String): ByteArray? {
        val hex = body.trimStart('﻿').trim().split(Regex("\\s+")).firstOrNull() ?: return null
        if (hex.length != 64 || hex.any { Character.digit(it, 16) < 0 }) return null
        return ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun fetchPublishedSha256(safeVersion: String, userAgent: String): ByteArray {
        val uri = checksumUrl(safeVersion)
        val request = HttpRequest.newBuilder()
            .uri(uri)
            .header("User-Agent", userAgent)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val body = try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { stream ->
                if (response.statusCode() != 200) {
                    throw InstallerVerificationException("No published checksum ($uri: HTTP ${response.statusCode()})")
                }
                stream.readNBytes(CHECKSUM_MAX_BYTES)
            }
        } catch (e: InstallerVerificationException) {
            throw e
        } catch (e: Exception) {
            throw InstallerVerificationException("Couldn't fetch the published checksum from $uri: ${e.message}", e)
        }
        return parseChecksum(String(body, Charsets.UTF_8))
            ?: throw InstallerVerificationException("Published checksum at $uri is not a SHA-256")
    }

    internal fun sha256Of(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }

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
     * the server's Content-Length and its SHA-256 against the published checksum. Throws on any
     * failure (untrusted host or redirect hop, HTTP error, size mismatch, missing or different
     * checksum) rather than returning the file — callers surface the failure via
     * [UpdateStatus.CheckFailed] instead of ever launching a truncated or unverified installer.
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

        // Fetched first so a missing checksum fails in a second instead of after an 80 MB download.
        val expectedSha256 = fetchPublishedSha256(safeVersion, "OffBridge-Windows/$targetVersion")

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
            if (!MessageDigest.isEqual(sha256Of(target), expectedSha256)) {
                target.delete()
                throw InstallerVerificationException(
                    "Downloaded installer does not match the SHA-256 published for $safeVersion"
                )
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

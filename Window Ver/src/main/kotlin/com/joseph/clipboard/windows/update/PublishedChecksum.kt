package com.joseph.clipboard.windows.update

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Checks a downloaded installer against the SHA-256 the release process publishes in the website's
 * `js/config.js` (the "Verify checksum" value on the download card).
 *
 * The installer comes from the OffBridge-Showcase release; the checksum is read from the code repo's
 * `main` branch, which is a separate repository. Someone who can swap a release asset (a leaked upload
 * token, a tampered release) can't also make the hashes agree without pushing to the code repo. It is
 * read from raw.githubusercontent.com rather than the website so a website or DNS outage can't block it.
 */
object PublishedChecksum {
    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/josephting1105123/OffBridge/main/website/js/config.js"
    private const val MAX_CONFIG_BYTES = 256 * 1024

    /** The published hash doesn't match the downloaded file. Not an IOException: retrying won't help. */
    class ChecksumMismatchException(message: String) : SecurityException(message)

    /** No hash is published for this version yet (the release went out before the website commit). */
    class ChecksumUnavailableException(message: String) : IllegalStateException(message)

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Pulls the Windows card's `version` and `sha256` out of config.js. Returns null when the block or
     * either field is missing or malformed. The hash must be 64 lowercase hex characters.
     */
    internal fun parseWindows(configJs: String): Pair<String, String>? {
        val start = Regex("""(?m)^\s*windows\s*:\s*\{""").find(configJs) ?: return null
        val end = configJs.indexOf('}', start.range.last)
        if (end < 0) return null
        val block = configJs.substring(start.range.last, end)
        val version = Regex("""\bversion\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: return null
        val sha = Regex("""\bsha256\s*:\s*"([0-9a-f]{64})"""").find(block)?.groupValues?.get(1) ?: return null
        return version.trim() to sha
    }

    internal fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Decides whether [actualSha] is the published hash for [targetVersion]. Throws
     * [ChecksumUnavailableException] if config.js doesn't describe that version yet, and
     * [ChecksumMismatchException] if the hashes differ.
     */
    internal fun check(configJs: String, targetVersion: String, actualSha: String) {
        val (publishedVersion, publishedSha) = parseWindows(configJs)
            ?: throw ChecksumUnavailableException("No Windows checksum found in the published config")
        if (publishedVersion.removePrefix("v") != targetVersion.removePrefix("v").trim()) {
            throw ChecksumUnavailableException(
                "Checksum for $targetVersion isn't published yet (website lists $publishedVersion). Try again later."
            )
        }
        if (!MessageDigest.isEqual(publishedSha.toByteArray(), actualSha.lowercase().toByteArray())) {
            throw ChecksumMismatchException("Downloaded installer doesn't match the published SHA-256; refusing to run it")
        }
    }

    /** Fetches config.js and verifies [installer]; throws instead of returning on any doubt. */
    fun verify(installer: File, targetVersion: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(CONFIG_URL))
            .header("User-Agent", "OffBridge-Windows")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            throw IOException("Couldn't fetch the published checksum: HTTP ${response.statusCode()}")
        }
        val body = response.body()
        if (body.size > MAX_CONFIG_BYTES) throw IOException("Published config is unexpectedly large")
        check(String(body, Charsets.UTF_8), targetVersion, sha256Hex(installer))
    }
}

package com.EntropicSoftwareLab.ClipboardShare.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the release APK and hands it to the system package installer — the sideloaded/GitHub
 * counterpart of the Windows client's UpdateInstaller. Android verifies the downloaded APK's
 * signature against the installed app on update, so a build signed with a different key simply fails
 * to install; there is no separate signature check here beyond the Content-Length integrity guard.
 */
object ApkUpdateInstaller {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 5 * 60 * 1000
    // Only ever download+install from the release host (and its CDN), never an arbitrary URL — guards
    // against a tampered API response pointing the installer at some other binary.
    private val ALLOWED_HOST_SUFFIXES = listOf("github.com", "githubusercontent.com")

    /** HTTPS only, and only GitHub's release hosts. Checked on the API-supplied URL and again on the
     *  post-redirect URL, so neither a tampered response nor a redirect can swap in another server. */
    private fun requireTrusted(url: URL) {
        if (!url.protocol.equals("https", ignoreCase = true)) throw IOException("Refusing non-HTTPS download: ${url.protocol}")
        val host = url.host?.lowercase() ?: throw IOException("No host in download URL")
        if (ALLOWED_HOST_SUFFIXES.none { host == it || host.endsWith(".$it") }) {
            throw IOException("Untrusted download host: $host")
        }
    }

    /** Downloads [url] to a private cache file. Throws on a bad host, non-2xx, or truncated body. */
    suspend fun download(context: Context, url: String): File = withContext(Dispatchers.IO) {
        val parsed = URL(url)
        requireTrusted(parsed)
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true // GitHub 302-redirects release assets to its CDN
            setRequestProperty("User-Agent", "OffBridge-Android")
        }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "OffBridge-update.apk")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            // Redirects were followed by now; conn.url is where the bytes actually come from.
            requireTrusted(conn.url)
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            val expected = conn.getHeaderField("Content-Length")?.toLongOrNull()
            if (expected != null && expected > 0 && target.length() != expected) {
                throw IOException("Incomplete download (${target.length()} of $expected bytes)")
            }
            target
        } catch (e: Exception) {
            // Don't leave a truncated/corrupt APK behind on any failure (bad status, dropped
            // connection mid-copy, size mismatch) — the next attempt should start clean, and nothing
            // should ever hand a partial file to the system installer.
            target.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Launches the system installer for [apk]. Returns false without installing if the app lacks the
     * "install unknown apps" permission — in that case the user is sent to grant it and should retry.
     */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val grant = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(grant)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}

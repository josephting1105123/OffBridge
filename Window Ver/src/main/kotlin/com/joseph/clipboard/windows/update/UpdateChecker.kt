package com.joseph.clipboard.windows.update

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.joseph.clipboard.windows.storage.DiagLog
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * Checks the OffBridge-Showcase GitHub repo's latest release for a newer Windows build.
 * Reads directly off the release the existing manual RUNBOOK process already publishes — no
 * separate manifest file, no extra release-day step. See release/RUNBOOK.md for the publish flow
 * (tag vX.Y.Z, attach a stably-named OffBridgeSetup.exe asset).
 */
object UpdateChecker {
    // Same owner/repo the website's download button targets — see the CS_CONFIG.windows block in
    // website/js/config.js. The two are independent copies with nothing enforcing agreement; if the
    // releases repo is ever renamed, update both.
    private const val OWNER = "josephting1105123"
    private const val REPO = "OffBridge-Showcase"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val INSTALLER_ASSET_NAME = "OffBridgeSetup.exe"

    /**
     * Connect phase only. Kept short so a genuinely offline machine fails fast instead of parking
     * the Updates page on its loader; 3s rather than 2s because a tethered or congested link
     * regularly needs more than two seconds just to complete TCP + TLS to a CDN edge.
     */
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)

    /**
     * Budget for the WHOLE exchange — and this is the asymmetry that made the same check succeed on
     * Android while failing here. Android uses HttpURLConnection, whose `readTimeout` applies to each
     * individual read, so a slow-but-progressing response can take far longer than its nominal 4s and
     * still complete. `HttpRequest.timeout` has no such per-read reset: it caps DNS + connect + TLS +
     * headers + body together. The old 4s therefore bought a much tighter budget than Android's
     * identical-looking 4s, and a slow link spent it before the ~6 KB release JSON arrived. 8s covers
     * the same exchange with real headroom while keeping two attempts inside the page's ceiling.
     */
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(8)

    /**
     * One extra attempt, timeouts only. A timeout is the failure a retry actually fixes (transient
     * congestion, a stalled first SYN); an HTTP error is not, and re-issuing the request after a 403
     * just spends another token of GitHub's 60-per-hour unauthenticated budget.
     *
     * No backoff between the two: the first attempt already spent seconds failing, which is all the
     * settling time a backoff would buy, and this whole call is racing a wall-clock ceiling in
     * ClipboardAppController — sleeping here would only make the retry likelier to be cut off.
     */
    private const val MAX_ATTEMPTS = 2

    // api.github.com returns the release JSON with 200 directly (no redirect today), but follow
    // redirects defensively — NORMAL follows them without allowing an HTTPS->HTTP downgrade.
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
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
     * or parse failures come back as [UpdateStatus.CheckFailed], classified by
     * [UpdateStatus.FailureKind] so the Updates page can say which one happened. Blocking network
     * call; always invoke from a background dispatcher (IO), never the UI thread.
     */
    fun check(currentVersion: String): UpdateStatus {
        var lastTimeout: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return interpret(fetch(currentVersion), currentVersion)
            } catch (e: HttpTimeoutException) {
                lastTimeout = e
            } catch (e: java.net.SocketTimeoutException) {
                lastTimeout = e
            } catch (e: IOException) {
                // Offline, DNS failure, TLS failure, connection reset: reached nothing. Not retried —
                // these fail immediately, so a second identical attempt only doubles the wait.
                DiagLog.w("update check: unreachable (${e.javaClass.simpleName}: ${e.message})")
                return UpdateStatus.CheckFailed(
                    e.message ?: e.javaClass.simpleName,
                    UpdateStatus.FailureKind.UNREACHABLE,
                )
            } catch (e: Exception) {
                DiagLog.w("update check: failed (${e.javaClass.simpleName}: ${e.message})")
                return UpdateStatus.CheckFailed(
                    e.message ?: e.javaClass.simpleName,
                    UpdateStatus.FailureKind.BAD_RESPONSE,
                )
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                DiagLog.w("update check: attempt ${attempt + 1} timed out after ${REQUEST_TIMEOUT.seconds}s — retrying once")
            }
        }
        val e = lastTimeout
        DiagLog.w("update check: timed out after $MAX_ATTEMPTS attempts of ${REQUEST_TIMEOUT.seconds}s")
        return UpdateStatus.CheckFailed(
            e?.message ?: "Request timed out",
            UpdateStatus.FailureKind.UNREACHABLE,
        )
    }

    /** One HTTP round trip. Throws on transport failure so [check] can classify and retry. */
    private fun fetch(currentVersion: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(LATEST_RELEASE_URL))
            .header("Accept", "application/vnd.github+json")
            // GitHub's REST API rejects unauthenticated requests that omit a User-Agent header.
            .header("User-Agent", "OffBridge-Windows/$currentVersion")
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun interpret(response: HttpResponse<String>, currentVersion: String): UpdateStatus {
        if (response.statusCode() !in 200..299) {
            return UpdateStatus.CheckFailed(
                "HTTP ${response.statusCode()}",
                classifyHttpFailure(
                    response.statusCode(),
                    response.headers().firstValue("x-ratelimit-remaining").orElse(null),
                ),
            )
        }
        val release = gson.fromJson(response.body(), GitHubRelease::class.java)
        val remoteVersion = release?.tagName?.removePrefix("v")?.trim()
        val downloadUrl = release?.assets?.firstOrNull { it.name == INSTALLER_ASSET_NAME }?.browserDownloadUrl
        if (remoteVersion.isNullOrBlank() || downloadUrl.isNullOrBlank()) {
            return UpdateStatus.CheckFailed(
                "Release response missing a version tag or installer asset",
                UpdateStatus.FailureKind.BAD_RESPONSE,
            )
        }
        return if (VersionCompare.isNewer(remoteVersion, currentVersion)) {
            UpdateStatus.UpdateAvailable(
                version = remoteVersion,
                notes = release.body?.trim().orEmpty(),
                downloadUrl = downloadUrl,
            )
        } else {
            UpdateStatus.UpToDate(currentVersion)
        }
    }

    /**
     * Which failure card a non-success status deserves: GitHub throttling this address, or a server
     * fault. Unauthenticated callers get 60 requests per hour per IP and the Updates page spends one
     * on every open and every Try-again, so a throttled user is an ordinary user, not an abuser —
     * and "try again" is the wrong advice for them.
     *
     * 429 is unambiguous. A 403 counts only when the rate-limit budget is actually reported spent,
     * so a genuine permission 403 still reads as a server error rather than telling the user to wait
     * for a quota that was never the problem.
     *
     * @param rateLimitRemaining the `x-ratelimit-remaining` header value, or null when absent.
     */
    internal fun classifyHttpFailure(
        statusCode: Int,
        rateLimitRemaining: String?,
    ): UpdateStatus.FailureKind = when {
        statusCode == 429 -> UpdateStatus.FailureKind.RATE_LIMITED
        statusCode == 403 && rateLimitRemaining?.trim() == "0" -> UpdateStatus.FailureKind.RATE_LIMITED
        else -> UpdateStatus.FailureKind.HTTP_ERROR
    }
}

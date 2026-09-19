package com.joseph.clipboard.windows.network

import com.joseph.clipboard.desktop.platform.HotspotInfo

import com.google.gson.Gson
import com.joseph.clipboard.windows.os.startDrained
import java.io.File
import java.security.SecureRandom

/**
 * Creates a **Wi-Fi Direct autonomous Group Owner** in legacy/SoftAP mode so an Android phone can
 * JOIN it directly for *off-grid* file transfer — no shared router required.
 *
 * Why Wi-Fi Direct (not `netsh wlan hostednetwork` or the Mobile Hotspot tethering API):
 *  - `netsh` hosted-network is dead on modern WDI Wi-Fi drivers ("Hosted network supported: No").
 *  - The Mobile Hotspot ([NetworkOperatorTetheringManager]) needs an internet connection profile to
 *    share — `GetInternetConnectionProfile()` returns null with no internet, i.e. exactly the off-grid
 *    case we care about.
 *  - A Wi-Fi Direct autonomous GO with `WiFiDirectLegacySettings` needs no internet and **runs its own
 *    DHCP server at 192.168.49.1/24**, handing 192.168.49.x to legacy (non-WFD) clients over WPA2-PSK.
 *    That matches the Android receiver's [OffGridPayload.GROUP_OWNER_IP] default and `192.168.49.*`
 *    handling exactly. No admin elevation needed.
 *
 * The WinRT API can't be called from the JVM, so we drive it via a long-running PowerShell helper
 * (same `ProcessBuilder` pattern as `FileDialogHelper`). The helper starts the advertisement, prints
 * one JSON line of `{ssid, passphrase, ip, status}`, then blocks forever — the publisher (and thus the
 * AP) stays alive only while that process lives, so [stop] kills it to bring the AP down.
 *
 * Reference: Microsoft Windows-classic-samples `WiFiDirectLegacyAP`, govert/WiFiDirectLegacyAPDemo.
 */
object HotspotController {

    private val gson = Gson()
    // Guards the start() critical section so two concurrent start() calls can't race. stop() deliberately
    // does NOT take this lock — it must be able to kill the helper process while start() is still holding
    // the lock and blocking on the ~9s PowerShell spin-up (otherwise the UI thread that triggers stop()
    // would stall for the whole start window). stop() works off the @Volatile handles instead.
    private val lock = Any()

    @Volatile private var process: Process? = null
    @Volatile private var scriptFile: File? = null
    @Volatile private var current: HotspotInfo? = null
    /** Set by stop() so an in-flight start() can bail out after its blocking wait instead of publishing a doomed AP. */
    @Volatile private var cancelRequested = false

    /** Helper must report status within this window (Start + async status settle + IP probe). */
    private const val START_TIMEOUT_MS = 9000L
    private const val GO_SUBNET_PREFIX = "192.168.49."
    private const val DEFAULT_GATEWAY = "192.168.49.1"

    init {
        // Child PowerShell survives parent JVM death on Windows — make sure a clean app exit drops the AP.
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    /**
     * Idempotent: returns the already-running AP's info if one is up, otherwise starts a fresh GO with
     * random ephemeral credentials. Returns null when the AP couldn't be created (e.g. the Wi-Fi adapter
     * doesn't support Wi-Fi Direct, or Wi-Fi is off) — callers then fall back to the LAN-only path.
     */
    fun start(): HotspotInfo? = synchronized(lock) {
        current?.let { if (process?.isAlive == true) return it }
        killProcess() // clear any stale handle before a fresh attempt
        cancelRequested = false // fresh attempt — arm cancellation for THIS start()

        val ssid = "OffBridge-" + (1000 + SecureRandom().nextInt(9000))
        val psk = randomPassphrase()
        val script = try {
            File.createTempFile("cs_hotspot", ".ps1").apply { writeText(PS_SCRIPT) }
        } catch (e: Exception) {
            System.err.println("HotspotController: cannot write helper script: ${e.message}")
            return null
        }
        scriptFile = script

        val proc = try {
            ProcessBuilder(
                "powershell", "-NoProfile", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-File", script.absolutePath
            ).apply {
                // Pass creds via env, not args, so quoting / special chars can't break the command line.
                environment()["CS_SSID"] = ssid
                environment()["CS_PSK"] = psk
                redirectErrorStream(false)
            }.startDrained("HotspotController helper")
        } catch (e: Exception) {
            System.err.println("HotspotController: failed to launch PowerShell: ${e.message}")
            killProcess()
            return null
        }
        process = proc

        val line = readFirstLine(proc, START_TIMEOUT_MS)
        // stop() may have fired on another thread during the blocking read above (it kills `proc` directly).
        // If so, don't publish a doomed AP — clean up and bail.
        if (cancelRequested || line.isNullOrBlank()) {
            if (!cancelRequested) System.err.println("HotspotController: no status from helper within ${START_TIMEOUT_MS}ms")
            killProcess()
            return null
        }

        val info = parseHelperResult(line, ssid, psk)
        if (cancelRequested || info == null) {
            killProcess()
            return null
        }
        current = info
        return info
    }

    /**
     * Bring the AP down (kills the helper process → the WFD advertisement stops). Safe to call repeatedly and
     * from any thread. Deliberately lock-free so it returns immediately even while start() holds `lock` during
     * its ~9s spin-up — callers on the UI thread must not stall. It signals cancellation and kills the current
     * process handle directly; an in-flight start() sees `cancelRequested` after its blocking read and bails.
     */
    fun stop() {
        cancelRequested = true
        killProcess()
    }

    /**
     * Fresh AP with fresh random credentials — the QR control plane's credential-regeneration
     * rescue when a phone repeatedly fails to join the current one. Blocking (~9s worst case,
     * like [start]); call off the UI thread. Returns null when the new AP couldn't be created.
     */
    fun restart(): HotspotInfo? {
        stop()
        return start() // start() re-arms cancelRequested for the fresh attempt
    }

    /** Kill the helper process and drop transient state. Safe to call from any thread (operates on @Volatile fields). */
    private fun killProcess() {
        process?.let { try { it.destroyForcibly() } catch (_: Exception) {} }
        process = null
        current = null
        // PowerShell can still hold the script open while the kill lands — fall back to
        // a JVM-exit delete rather than silently leaking the temp file.
        scriptFile?.let { try { if (!it.delete()) it.deleteOnExit() } catch (_: Exception) {} }
        scriptFile = null
    }

    private fun parseHelperResult(line: String, fallbackSsid: String, fallbackPsk: String): HotspotInfo? {
        val obj = try {
            gson.fromJson(line, HelperResult::class.java)
        } catch (e: Exception) {
            System.err.println("HotspotController: unparseable helper output '$line': ${e.message}")
            return null
        }
        val status = obj?.status
        // The publisher status settles asynchronously; a documented quirk leaves it "Created" even on a
        // working AP. So accept Created/Started and only reject the clear failures (Aborted/Stopped/Error).
        val ok = status != null &&
            !status.startsWith("Error", ignoreCase = true) &&
            !status.equals("Aborted", ignoreCase = true) &&
            !status.equals("Stopped", ignoreCase = true)
        if (!ok) {
            System.err.println("HotspotController: Wi-Fi Direct AP not started (status='$status')")
            return null
        }
        return HotspotInfo(
            ssid = obj.ssid?.takeIf { it.isNotBlank() } ?: fallbackSsid,
            passphrase = obj.passphrase?.takeIf { it.isNotBlank() } ?: fallbackPsk,
            gatewayIp = obj.ip?.takeIf { it.startsWith(GO_SUBNET_PREFIX) } ?: DEFAULT_GATEWAY
        )
    }

    /** Read exactly the first stdout line on a daemon thread so a hung helper can't block forever. */
    private fun readFirstLine(proc: Process, timeoutMs: Long): String? {
        val holder = arrayOfNulls<String>(1)
        val reader = Thread {
            try { holder[0] = proc.inputStream.bufferedReader().readLine() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        reader.join(timeoutMs)
        return holder[0]
    }

    private data class HelperResult(
        val ssid: String?,
        val passphrase: String?,
        val ip: String?,
        val status: String?
    )

    /** WPA2 passphrase, 12 chars from an unambiguous alphabet (8–63 satisfies WifiNetworkSpecifier). */
    private fun randomPassphrase(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return buildString { repeat(12) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
    }

    /**
     * Starts an autonomous-GO legacy advertisement, reports creds + GO IP as one JSON line, then blocks
     * so the publisher object (and the AP) stays alive until this process is killed.
     */
    private val PS_SCRIPT = """
        ${'$'}ErrorActionPreference = 'Stop'
        try {
            ${'$'}pub = [Windows.Devices.WiFiDirect.WiFiDirectAdvertisementPublisher,Windows.Devices.WiFiDirect,ContentType=WindowsRuntime]::new()
            ${'$'}adv = ${'$'}pub.Advertisement
            ${'$'}adv.IsAutonomousGroupOwnerEnabled = ${'$'}true
            ${'$'}legacy = ${'$'}adv.LegacySettings
            ${'$'}legacy.IsEnabled = ${'$'}true
            ${'$'}legacy.Ssid = ${'$'}env:CS_SSID
            ${'$'}legacy.Passphrase.Password = ${'$'}env:CS_PSK
            ${'$'}pub.Start()
        } catch {
            Write-Output (ConvertTo-Json -Compress @{ status = "Error: ${'$'}(${'$'}_.Exception.Message)" })
            [Console]::Out.Flush()
            exit 1
        }
        # Status settles asynchronously after Start(); wait for it to leave 'Created' (or time out).
        ${'$'}deadline = (Get-Date).AddSeconds(4)
        while ("${'$'}(${'$'}pub.Status)" -eq 'Created' -and (Get-Date) -lt ${'$'}deadline) { Start-Sleep -Milliseconds 200 }
        ${'$'}ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.IPAddress -like '192.168.49.*' } | Select-Object -First 1 -ExpandProperty IPAddress)
        if (-not ${'$'}ip) { ${'$'}ip = '192.168.49.1' }
        Write-Output (ConvertTo-Json -Compress @{ ssid = ${'$'}legacy.Ssid; passphrase = ${'$'}legacy.Passphrase.Password; ip = ${'$'}ip; status = "${'$'}(${'$'}pub.Status)" })
        [Console]::Out.Flush()
        # Keep the publisher (and the AP) alive until the parent kills this process.
        while (${'$'}true) { Start-Sleep -Seconds 3600 }
    """.trimIndent()
}

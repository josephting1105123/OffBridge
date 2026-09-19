package com.joseph.clipboard.windows.storage

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.joseph.clipboard.desktop.platform.Platform
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

import java.util.concurrent.ConcurrentHashMap

object SecurityVault {
    // Resolved through the platform seam on every read rather than captured at class-init:
    // %LOCALAPPDATA%\OffBridge on Windows, $XDG_CONFIG_HOME/OffBridge on Linux. An `object`'s
    // initialisers run on first touch from anywhere, which is not guaranteed to be after main()
    // installed the backend.
    private val configDir: File get() = Platform.current.configDir
    private val configFile: File get() = File(configDir, "config.json")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // Null means "not set by the user and not present in config.json" — the platform default is
    // substituted on read (see [getDeviceName]), so no OS-specific string is baked in here.
    private var deviceName: String? = null
    private var isDiscoverable: Boolean = true
    private var language: String = "System Default"
    // Appearance persisted as the ThemeMode enum name ("LIGHT"/"DARK"/"SYSTEM") so the vault stays
    // UI-agnostic; the UI layer parses it and falls back to SYSTEM on anything unreadable.
    private var theme: String = "SYSTEM"
    // As with [deviceName]: null until set, resolved to the platform's download dir on read.
    private var saveLocation: String? = null
    // Passive startup update-check cooldown gate + opt-out — the Updates page's own manual check
    // ignores both (explicit user action). 0L means "never checked".
    private var lastUpdateCheckEpochMillis: Long = 0L
    private var autoUpdateCheckEnabled: Boolean = true
    // First-ever-launch guided tour (see Main.kt's tutorial chain): set once the tour auto-fires, so
    // an install that quit mid-tour isn't re-offered it every subsequent launch. Mirrors Android's
    // TutorialTour.KEY_COMPLETED_V2 in spirit, but simpler — Windows has no Lite/Pro mode gate, so
    // this is a single persisted "has this ever been shown" flag, not a resumable step counter.
    private var firstRunTourShown: Boolean = false
    // This install's own stable identifier, minted once and persisted. Trust records are keyed by IP,
    // so a peer that re-pairs from a new DHCP lease used to land as a SECOND entry next to the stale
    // one (two "Windows PC" rows in the Paired list). Peers exchange this id during the pairing
    // handshake so the old record can be evicted. Survives app updates — the installer never touches
    // %LOCALAPPDATA%\OffBridge — and is only lost on a full uninstall, which drops the trust
    // store with it. Not derived from the off-grid identity key: that one has no Windows counterpart
    // and is scoped to the QR/BLE ladder.
    private var deviceId: String = ""
    private val trustedNodes = ConcurrentHashMap<String, String>()
    // Human-readable name per trusted IP, so the Paired list shows names even when a device is offline.
    private val pairedDeviceNames = ConcurrentHashMap<String, String>()
    // Peer device id per trusted IP (absent for records paired before device ids existed). Non-secret
    // — it is only an equality token for "same device, new address".
    private val pairedDeviceIds = ConcurrentHashMap<String, String>()
    // Anti-downgrade pins (TOFU): IPs we've actually negotiated an authenticated channel with. Once
    // pinned, a peer that stops advertising the capability is treated as a downgrade attempt — file
    // transfers refuse the plaintext stream and clipboard sends skip the peer — even if a MITM strips
    // the capability token from the unauthenticated UDP discovery. Mirrors the Android SecurityVault
    // pins; cleared on unpair.
    private val fileGcmPinned = ConcurrentHashMap.newKeySet<String>()
    private val clipboardGcmPinned = ConcurrentHashMap.newKeySet<String>()
    // XP-DEF-01: the FGCM1/GCM1 pins above didn't cover the AUTH2 admission challenge or the v2 FileSeal
    // (per-session key), which also ride the unauthenticated discovery. Pin both once genuinely used and
    // fail closed on a later strip. Mirrors the Android SecurityVault pins; cleared on unpair.
    private val auth2Pinned = ConcurrentHashMap.newKeySet<String>()
    private val fileGcmV2Pinned = ConcurrentHashMap.newKeySet<String>()
    // Separate set from auth2Pinned on purpose — see TransferDaemon.ACCEPT_AUTH_CAP's doc for why
    // the capability token itself must be a new one, not a reuse of AUTH2; the pin follows the same
    // reasoning (an already-shipped AUTH2-pinned peer must not be treated as having proven the
    // accept/resume-direction challenge it doesn't yet implement).
    private val acceptAuth2Pinned = ConcurrentHashMap.newKeySet<String>()

    // The platform's secret store wraps each pairing key at rest so config.json no longer holds
    // recoverable key material — DPAPI on Windows, libsecret on Linux, and Android keeps the
    // equivalents in the Keystore. Where no store is available (Linux CI, a headless self-test) the
    // vault falls back to legacy plaintext base64 so the module still loads.
    // New entries carry [ENC_PREFIX]; legacy plaintext entries are re-wrapped on first load.
    private const val ENC_PREFIX = "p1_"  // note: '_' is not in the standard base64 alphabet, so this
                                          // never collides with a legacy base64 value or the ip ':' split

    /** Raw-key base64 (the in-memory form) -> the string persisted in config.json, or null if it
     *  can't be encrypted — the caller (see [save]) then drops the entry rather than ever write raw
     *  key material to disk. Previously fell back to plaintext-on-disk on ANY DPAPI failure ("never
     *  lose a key over an encryption failure"), silently downgrading every pairing key to cleartext
     *  storage on a transient DPAPI/JNA hiccup with no signal to the user. Failing closed means a
     *  broken keyset just doesn't persist across restart (forcing re-pair) instead of ever sitting on
     *  disk unencrypted — matches Android's equivalent failure mode (a corrupted keyset is dropped and
     *  recreated, never silently written as plaintext). Does NOT touch the in-memory [trustedNodes]
     *  map, so a transient failure self-heals on the next [save] once DPAPI recovers. */
    private fun encodeKeyForDisk(base64Raw: String): String? {
        val secrets = Platform.current.secrets
        if (!secrets.available) return base64Raw
        return try {
            val raw = Base64.getDecoder().decode(base64Raw)
            val wrapped = secrets.wrap(raw) ?: throw IllegalStateException("secret store declined to wrap the key")
            ENC_PREFIX + Base64.getEncoder().encodeToString(wrapped)
        } catch (e: Throwable) {
            // Both sinks on purpose: stderr is invisible in a windowed (javaw) launch, so without the
            // DiagLog line this drop would be completely silent to a user who later finds a pairing
            // gone after a restart. diagnostics.log is what testers actually send back.
            System.err.println("SecurityVault: DPAPI key encryption failed (${e.message}) — refusing to persist this key in plaintext; it will not survive a restart until DPAPI recovers")
            DiagLog.w("SecurityVault: DPAPI key encryption failed (${e.message}) — this pairing key was NOT written to config.json (refusing plaintext-at-rest) and will be lost on restart unless DPAPI recovers before the next save. Re-pair if the device disappears.")
            null
        }
    }

    /** Persisted config.json value -> raw-key base64 (the in-memory form), or null if unreadable.
     *
     *  A wrapped entry read on a platform with no secret store (a Windows config.json opened by the
     *  Linux build, or a DPAPI blob belonging to a different Windows user) returns null here, which
     *  drops the node and forces a re-pair. That is the intended fail-closed outcome — the
     *  alternative would be treating an undecryptable blob as a usable key. */
    private fun decodeKeyFromDisk(stored: String): String? {
        return try {
            if (stored.startsWith(ENC_PREFIX)) {
                val wrapped = Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length))
                Platform.current.secrets.unwrap(wrapped)?.let { Base64.getEncoder().encodeToString(it) }
            } else {
                stored // legacy plaintext base64(key)
            }
        } catch (e: Throwable) {
            null // corrupt / not decryptable by this user -> drop the node (peer must re-pair)
        }
    }

    // Mirrors the on-disk config.json schema; nodes stays a single "ip:base64key,ip:base64key" string.
    private data class ConfigDto(
        val deviceName: String? = null,
        val isDiscoverable: Boolean? = null,
        val language: String? = null,
        val saveLocation: String? = null,
        val nodes: String? = null,
        val pairedNames: Map<String, String>? = null,
        val pairedIds: Map<String, String>? = null,
        val deviceId: String? = null,
        val fileGcmPinned: List<String>? = null,
        val clipboardGcmPinned: List<String>? = null,
        val auth2Pinned: List<String>? = null,
        val fileGcmV2Pinned: List<String>? = null,
        val acceptAuth2Pinned: List<String>? = null,
        val theme: String? = null,
        val lastUpdateCheckEpochMillis: Long? = null,
        val autoUpdateCheckEnabled: Boolean? = null,
        val firstRunTourShown: Boolean? = null
    )

    init {
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        load()
    }

    private fun load() {
        if (!configFile.exists()) {
            save()
            return
        }
        try {
            val content = configFile.readText(Charsets.UTF_8)
            val dto = gson.fromJson(content, ConfigDto::class.java) ?: return
            dto.deviceName?.let { deviceName = it }
            dto.isDiscoverable?.let { isDiscoverable = it }
            dto.language?.let { language = it }
            dto.theme?.let { theme = it }
            dto.saveLocation?.let { saveLocation = it }
            dto.lastUpdateCheckEpochMillis?.let { lastUpdateCheckEpochMillis = it }
            dto.autoUpdateCheckEnabled?.let { autoUpdateCheckEnabled = it }
            dto.firstRunTourShown?.let { firstRunTourShown = it }
            var legacyKeysPresent = false
            dto.nodes?.let { nodesStr ->
                if (nodesStr.isNotBlank()) {
                    nodesStr.split(",").forEach { pair ->
                        // Split on the LAST ':' only so IPv6 peers (whose address
                        // contains ':') are parsed correctly; stored values never contain ':'.
                        if (pair.contains(":")) {
                            val ip = pair.substringBeforeLast(":")
                            val stored = pair.substringAfterLast(":")
                            if (ip.isNotEmpty() && stored.isNotEmpty()) {
                                val base64Raw = decodeKeyFromDisk(stored)
                                if (base64Raw != null) {
                                    trustedNodes[ip] = base64Raw
                                    // A legacy plaintext entry (no wrap prefix) is re-wrapped on save,
                                    // but only where the platform actually has a secret store to wrap with.
                                    if (Platform.current.secrets.available && !stored.startsWith(ENC_PREFIX)) legacyKeysPresent = true
                                }
                            }
                        }
                    }
                }
            }
            dto.deviceId?.let { if (it.isNotBlank()) deviceId = it }
            dto.pairedNames?.let { pairedDeviceNames.putAll(it) }
            dto.pairedIds?.let { pairedDeviceIds.putAll(it) }
            dto.fileGcmPinned?.let { fileGcmPinned.addAll(it) }
            dto.clipboardGcmPinned?.let { clipboardGcmPinned.addAll(it) }
            dto.auth2Pinned?.let { auth2Pinned.addAll(it) }
            dto.fileGcmV2Pinned?.let { fileGcmV2Pinned.addAll(it) }
            dto.acceptAuth2Pinned?.let { acceptAuth2Pinned.addAll(it) }
            // One-time migration: rewrite config.json so legacy plaintext keys become DPAPI-wrapped.
            if (legacyKeysPresent) save()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // @Synchronized serializes concurrent writers so two saves can't interleave.
    @Synchronized
    private fun save() {
        try {
            val nodesStr = trustedNodes.mapNotNull { (ip, key) -> encodeKeyForDisk(key)?.let { "$ip:$it" } }.joinToString(",")
            // Named arguments on purpose: ConfigDto gains fields over time and a positional call
            // silently shifts every value after the insertion point.
            val dto = ConfigDto(
                // Effective values, not the raw nullable backing fields: persisting the resolved
                // default keeps config.json self-describing and unchanged in shape from before the
                // platform seam existed.
                deviceName = getDeviceName(),
                isDiscoverable = isDiscoverable,
                language = language,
                saveLocation = getSaveLocation(),
                nodes = nodesStr,
                pairedNames = pairedDeviceNames.toMap(),
                pairedIds = pairedDeviceIds.toMap(),
                deviceId = deviceId.ifBlank { null },
                fileGcmPinned = fileGcmPinned.toList(),
                clipboardGcmPinned = clipboardGcmPinned.toList(),
                auth2Pinned = auth2Pinned.toList(),
                fileGcmV2Pinned = fileGcmV2Pinned.toList(),
                acceptAuth2Pinned = acceptAuth2Pinned.toList(),
                theme = theme,
                lastUpdateCheckEpochMillis = lastUpdateCheckEpochMillis,
                autoUpdateCheckEnabled = autoUpdateCheckEnabled,
                firstRunTourShown = firstRunTourShown,
            )
            // Atomic write — write to a temp file then move it into place so a
            // crash/race mid-write can't truncate config.json and lose all trusted-node keys.
            val tmpFile = File(configDir, "config.json.tmp")
            tmpFile.writeText(gson.toJson(dto), Charsets.UTF_8)
            try {
                Files.move(
                    tmpFile.toPath(),
                    configFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: AtomicMoveNotSupportedException) {
                // Fall back to a non-atomic replace when the filesystem can't do atomic moves.
                Files.move(
                    tmpFile.toPath(),
                    configFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getDeviceName(): String = deviceName ?: Platform.current.defaultDeviceName

    fun setDeviceName(name: String) {
        // The name is embedded between '|' delimiters in OFFER, discovery and P2P_HELLO frames;
        // strip '|'/newlines so a user-set name can't break the peer's positional parse (parity
        // with the Android name setter).
        deviceName = name.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        save()
    }

    fun isDiscoverable(): Boolean = isDiscoverable

    fun setDiscoverable(discoverable: Boolean) {
        isDiscoverable = discoverable
        save()
    }

    fun getLanguage(): String = language

    fun setLanguage(lang: String) {
        language = lang
        save()
    }

    fun getTheme(): String = theme

    fun setTheme(mode: String) {
        theme = mode
        save()
    }

    fun getSaveLocation(): String = saveLocation ?: Platform.current.defaultDownloadDir.absolutePath

    fun setSaveLocation(path: String) {
        saveLocation = path
        save()
    }

    fun getLastUpdateCheckEpochMillis(): Long = lastUpdateCheckEpochMillis

    fun setLastUpdateCheckEpochMillis(epochMillis: Long) {
        lastUpdateCheckEpochMillis = epochMillis
        save()
    }

    fun isAutoUpdateCheckEnabled(): Boolean = autoUpdateCheckEnabled

    fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        autoUpdateCheckEnabled = enabled
        save()
    }

    fun isFirstRunTourShown(): Boolean = firstRunTourShown

    fun markFirstRunTourShown() {
        if (firstRunTourShown) return
        firstRunTourShown = true
        save()
    }

    fun getAesKey(ipAddress: String): ByteArray? {
        val base64Key = trustedNodes[ipAddress] ?: return null
        return try {
            Base64.getDecoder().decode(base64Key)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * This install's stable device id (32 lowercase hex), minted on first use. Sent to peers in the
     * pairing handshake so they can recognise this machine again after its LAN address changes.
     */
    @Synchronized
    fun localDeviceId(): String {
        if (deviceId.isBlank()) {
            deviceId = java.util.UUID.randomUUID().toString().replace("-", "")
            save()
        }
        return deviceId
    }

    fun getPairedDeviceId(ipAddress: String): String? = pairedDeviceIds[ipAddress]

    /**
     * Persists the pairing key for [ipAddress], first dropping any older record that belongs to the
     * SAME physical device at a stale address — otherwise a peer that re-pairs after a DHCP lease
     * change shows up twice in the Paired list, and the dead entry can never be removed (the unpair
     * action only exists on devices currently visible on the LAN).
     *
     * [peerDeviceId] is only ever taken from a completed PAIR_REQ/PAIR_ACK exchange, never from the
     * unauthenticated UDP discovery — the user has confirmed the PIN by then, so a LAN host can't
     * claim someone else's id and evict their pairing.
     */
    fun saveTrustedNode(ipAddress: String, key: ByteArray, peerDeviceId: String? = null, peerName: String? = null) {
        trustedNodes.keys
            .filter { it != ipAddress && isSameDeviceAs(it, peerDeviceId, peerName) }
            .forEach { stale ->
                println("Re-pairing $ipAddress replaces its stale record at $stale")
                dropNode(stale)
            }
        trustedNodes[ipAddress] = Base64.getEncoder().encodeToString(key)
        if (peerDeviceId != null) pairedDeviceIds[ipAddress] = peerDeviceId
        if (!peerName.isNullOrBlank()) pairedDeviceNames[ipAddress] = peerName
        save()
    }

    /** True when the record at [existingIp] is the device now pairing from another address. */
    private fun isSameDeviceAs(existingIp: String, peerDeviceId: String?, peerName: String?): Boolean {
        val existingId = pairedDeviceIds[existingIp]
        if (existingId != null) return peerDeviceId != null && existingId == peerDeviceId
        // Records written before device ids existed carry no id to match on, so fall back to an exact
        // name match to clear duplicates that pre-date this version. Self-limiting: every record
        // written from now on has an id and takes the branch above. Two distinct devices sharing one
        // name (both left on the "Windows PC" default) would cost one re-pair, once.
        return !peerName.isNullOrBlank() && pairedDeviceNames[existingIp] == peerName
    }

    /** Persist a human-readable name for a paired IP. No-ops when unchanged so it's cheap to call from
     *  the 1s discovery poll (which backfills names for devices paired before names were stored). */
    fun savePairedDeviceName(ipAddress: String, name: String) {
        if (name.isBlank() || pairedDeviceNames[ipAddress] == name) return
        pairedDeviceNames[ipAddress] = name
        save()
    }

    fun getPairedDeviceName(ipAddress: String): String? = pairedDeviceNames[ipAddress]

    fun removeTrustedNode(ipAddress: String) {
        dropNode(ipAddress)
        save()
    }

    /** In-memory half of [removeTrustedNode]; the caller saves (so a batch costs one write). */
    private fun dropNode(ipAddress: String) {
        trustedNodes.remove(ipAddress)
        pairedDeviceNames.remove(ipAddress)
        pairedDeviceIds.remove(ipAddress)
        // Drop the anti-downgrade pins too, so a deliberate re-pair starts from a clean TOFU state.
        fileGcmPinned.remove(ipAddress)
        clipboardGcmPinned.remove(ipAddress)
        auth2Pinned.remove(ipAddress)
        fileGcmV2Pinned.remove(ipAddress)
        acceptAuth2Pinned.remove(ipAddress)
    }

    // ---- Anti-downgrade pins (TOFU) — mirrors Android SecurityVault ------------------------
    /** Remember that [ipAddress] negotiated an authenticated (FileSeal/FGCM1) bulk-file stream. */
    fun pinFileGcm(ipAddress: String) { if (fileGcmPinned.add(ipAddress)) save() }

    /** True once [ipAddress] supported FileSeal — a later loss of FGCM1 is a downgrade attempt. */
    fun isFileGcmPinned(ipAddress: String): Boolean = fileGcmPinned.contains(ipAddress)

    /** Remember that [ipAddress] negotiated an authenticated (AES-GCM/GCM1) clipboard payload. */
    fun pinClipboardGcm(ipAddress: String) { if (clipboardGcmPinned.add(ipAddress)) save() }

    /** True once [ipAddress] supported authenticated clipboard — a later loss of GCM1 is a downgrade. */
    fun isClipboardGcmPinned(ipAddress: String): Boolean = clipboardGcmPinned.contains(ipAddress)

    /** Remember that [ipAddress] proved possession of the pairing key via the AUTH2 OFFER challenge. */
    fun pinAuth2(ipAddress: String) { if (auth2Pinned.add(ipAddress)) save() }

    /** True once [ipAddress] completed an AUTH2 proof — a later loss of AUTH2 is a downgrade attempt. */
    fun isAuth2Pinned(ipAddress: String): Boolean = auth2Pinned.contains(ipAddress)

    /** Remember that [ipAddress] proved possession of the pairing key via the AUTH2R
     *  ACCEPT_CHALLENGE (accept/resume direction — see [acceptAuth2Pinned]). */
    fun pinAuth2Accept(ipAddress: String) { if (acceptAuth2Pinned.add(ipAddress)) save() }

    /** True once [ipAddress] completed an AUTH2R proof — a later loss of AUTH2R is a downgrade. */
    fun isAuth2AcceptPinned(ipAddress: String): Boolean = acceptAuth2Pinned.contains(ipAddress)

    /** Remember that [ipAddress] negotiated a v2 (per-session key) FileSeal stream. */
    fun pinFileGcmV2(ipAddress: String) { if (fileGcmV2Pinned.add(ipAddress)) save() }

    /** True once [ipAddress] supported FGCM2 — a later loss (forcing v1) is a downgrade attempt. */
    fun isFileGcmV2Pinned(ipAddress: String): Boolean = fileGcmV2Pinned.contains(ipAddress)

    fun isTrusted(ipAddress: String): Boolean {
        return trustedNodes.containsKey(ipAddress)
    }

    fun getAllTrustedNodes(): Map<String, String> {
        return trustedNodes.toMap()
    }
}

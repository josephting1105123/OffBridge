package com.joseph.clipboard.windows.storage

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

import java.util.concurrent.ConcurrentHashMap

object SecurityVault {
    private val appDataPath = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
    private val configDir = File("$appDataPath\\ClipboardShare")
    private val configFile = File(configDir, "config.json")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private var deviceName: String = "Windows PC"
    private var isDiscoverable: Boolean = true
    private var language: String = "System Default"
    private var saveLocation: String = System.getProperty("user.home") + "\\Downloads"
    private val trustedNodes = ConcurrentHashMap<String, String>()
    // Human-readable name per trusted IP, so the Paired list shows names even when a device is offline.
    private val pairedDeviceNames = ConcurrentHashMap<String, String>()
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

    // DPAPI (Windows Data Protection API) wraps each pairing key at rest so config.json no longer holds
    // recoverable key material (Android keeps the equivalents in the Keystore). Windows-only; on any
    // other platform (e.g. Linux CI) we fall back to legacy plaintext base64 so the module still loads.
    // New entries carry [ENC_PREFIX]; legacy plaintext entries are re-wrapped on first load.
    private const val ENC_PREFIX = "p1_"  // note: '_' is not in the standard base64 alphabet, so this
                                          // never collides with a legacy base64 value or the ip ':' split
    private val dpapiAvailable: Boolean = try { com.sun.jna.Platform.isWindows() } catch (e: Throwable) { false }

    /** Raw-key base64 (the in-memory form) -> the string persisted in config.json. */
    private fun encodeKeyForDisk(base64Raw: String): String {
        if (!dpapiAvailable) return base64Raw
        return try {
            val raw = Base64.getDecoder().decode(base64Raw)
            val wrapped = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(raw)
            ENC_PREFIX + Base64.getEncoder().encodeToString(wrapped)
        } catch (e: Throwable) {
            base64Raw // never lose a key over an encryption failure
        }
    }

    /** Persisted config.json value -> raw-key base64 (the in-memory form), or null if unreadable. */
    private fun decodeKeyFromDisk(stored: String): String? {
        return try {
            if (stored.startsWith(ENC_PREFIX)) {
                val wrapped = Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length))
                Base64.getEncoder().encodeToString(com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(wrapped))
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
        val fileGcmPinned: List<String>? = null,
        val clipboardGcmPinned: List<String>? = null,
        val auth2Pinned: List<String>? = null,
        val fileGcmV2Pinned: List<String>? = null
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
            dto.saveLocation?.let { saveLocation = it }
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
                                    // A legacy plaintext entry (no DPAPI prefix) is re-wrapped on save.
                                    if (dpapiAvailable && !stored.startsWith(ENC_PREFIX)) legacyKeysPresent = true
                                }
                            }
                        }
                    }
                }
            }
            dto.pairedNames?.let { pairedDeviceNames.putAll(it) }
            dto.fileGcmPinned?.let { fileGcmPinned.addAll(it) }
            dto.clipboardGcmPinned?.let { clipboardGcmPinned.addAll(it) }
            dto.auth2Pinned?.let { auth2Pinned.addAll(it) }
            dto.fileGcmV2Pinned?.let { fileGcmV2Pinned.addAll(it) }
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
            val nodesStr = trustedNodes.map { "${it.key}:${encodeKeyForDisk(it.value)}" }.joinToString(",")
            val dto = ConfigDto(deviceName, isDiscoverable, language, saveLocation, nodesStr, pairedDeviceNames.toMap(), fileGcmPinned.toList(), clipboardGcmPinned.toList(), auth2Pinned.toList(), fileGcmV2Pinned.toList())
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

    fun getDeviceName(): String = deviceName

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

    fun getSaveLocation(): String = saveLocation

    fun setSaveLocation(path: String) {
        saveLocation = path
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

    fun saveTrustedNode(ipAddress: String, key: ByteArray) {
        trustedNodes[ipAddress] = Base64.getEncoder().encodeToString(key)
        save()
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
        trustedNodes.remove(ipAddress)
        pairedDeviceNames.remove(ipAddress)
        // Drop the anti-downgrade pins too, so a deliberate re-pair starts from a clean TOFU state.
        fileGcmPinned.remove(ipAddress)
        clipboardGcmPinned.remove(ipAddress)
        auth2Pinned.remove(ipAddress)
        fileGcmV2Pinned.remove(ipAddress)
        save()
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

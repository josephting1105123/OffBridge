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

    // Mirrors the on-disk config.json schema; nodes stays a single "ip:base64key,ip:base64key" string.
    private data class ConfigDto(
        val deviceName: String? = null,
        val isDiscoverable: Boolean? = null,
        val language: String? = null,
        val saveLocation: String? = null,
        val nodes: String? = null,
        val pairedNames: Map<String, String>? = null
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
            dto.nodes?.let { nodesStr ->
                if (nodesStr.isNotBlank()) {
                    nodesStr.split(",").forEach { pair ->
                        // M-15: split on the LAST ':' only so IPv6 peers (whose address
                        // contains ':') are parsed correctly; base64 keys never contain ':'.
                        if (pair.contains(":")) {
                            val ip = pair.substringBeforeLast(":")
                            val key = pair.substringAfterLast(":")
                            if (ip.isNotEmpty() && key.isNotEmpty()) {
                                trustedNodes[ip] = key
                            }
                        }
                    }
                }
            }
            dto.pairedNames?.let { pairedDeviceNames.putAll(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // M-16: @Synchronized serializes concurrent writers so two saves can't interleave.
    @Synchronized
    private fun save() {
        try {
            val nodesStr = trustedNodes.map { "${it.key}:${it.value}" }.joinToString(",")
            val dto = ConfigDto(deviceName, isDiscoverable, language, saveLocation, nodesStr, pairedDeviceNames.toMap())
            // M-16: atomic write — write to a temp file then move it into place so a
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
        deviceName = name
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
        save()
    }

    fun isTrusted(ipAddress: String): Boolean {
        return trustedNodes.containsKey(ipAddress)
    }

    fun getAllTrustedNodes(): Map<String, String> {
        return trustedNodes.toMap()
    }
}

package com.EntropicSoftwareLab.ClipboardShare

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurityVault {
    // Encrypted keystore — holds the per-node AES keys ONLY. Never write plaintext here:
    // mixing a plain SharedPreferences with EncryptedSharedPreferences on the same file
    // corrupts the keyset (InvalidProtocolBufferException on the next open).
    private const val PREFS_NAME = "ClipboardShare_Secure_Vault"
    // Separate plain file for non-secret flags / display names.
    private const val PLAIN_PREFS_NAME = "ClipboardShare_Prefs"
    private const val MIGRATED_FLAG = "__migrated_from_vault"

    private fun getEncryptedPrefs(context: Context): android.content.SharedPreferences {
        return try {
            buildEncryptedPrefs(context)
        } catch (e: Exception) {
            // Keyset corrupted (restore-to-new-device, Keystore reset, biometric/lock change,
            // or a legacy plaintext write into this file). Drop it and recreate so the app
            // stays usable — the only cost is that trusted nodes must be re-paired.
            try { context.deleteSharedPreferences(PREFS_NAME) } catch (_: Exception) {}
            buildEncryptedPrefs(context)
        }
    }

    private fun buildEncryptedPrefs(context: Context): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getPlainPrefs(context: Context): android.content.SharedPreferences {
        val prefs = context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MIGRATED_FLAG, false)) {
            // One-time best-effort migration of the non-secret flag that used to live
            // (as plaintext) inside the encrypted vault file.
            try {
                val legacy = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                if (legacy.contains("is_discoverable")) {
                    editor.putBoolean("is_discoverable", legacy.getBoolean("is_discoverable", true))
                }
                editor.putBoolean(MIGRATED_FLAG, true).apply()
            } catch (_: Exception) {
                prefs.edit().putBoolean(MIGRATED_FLAG, true).apply()
            }
        }
        return prefs
    }

    fun saveTrustedNode(context: Context, ipAddress: String, aesKey: ByteArray) {
        val prefs = getEncryptedPrefs(context)
        val base64Key = Base64.encodeToString(aesKey, Base64.NO_WRAP)
        prefs.edit().putString(ipAddress, base64Key).apply()
    }

    fun getPairedDeviceName(context: Context, ip: String): String? {
        val prefs = getPlainPrefs(context)
        return prefs.getString("paired_device_name_$ip", null)
    }

    /** Persist a human-readable name for a paired IP so the Paired list shows names even when the device
     *  is offline. Names are non-secret, so they live in plain prefs alongside the discoverable flag. */
    fun savePairedDeviceName(context: Context, ip: String, name: String) {
        if (name.isBlank()) return
        getPlainPrefs(context).edit().putString("paired_device_name_$ip", name).apply()
    }

    fun isDiscoverable(context: Context): Boolean {
        val prefs = getPlainPrefs(context)
        return prefs.getBoolean("is_discoverable", true) // Default to true
    }

    fun setDiscoverable(context: Context, state: Boolean) {
        val prefs = getPlainPrefs(context)
        prefs.edit().putBoolean("is_discoverable", state).apply()
    }

    fun removeTrustedNode(context: Context, ipAddress: String) {
        getEncryptedPrefs(context).edit().remove(ipAddress).apply()
        // Also drop the cached display name so an unpaired device doesn't linger in the Paired list.
        getPlainPrefs(context).edit().remove("paired_device_name_$ipAddress").apply()
    }

    fun getKeyForNode(context: Context, ipAddress: String): ByteArray? {
        val prefs = getEncryptedPrefs(context)
        val base64Key = prefs.getString(ipAddress, null) ?: return null
        return try {
            Base64.decode(base64Key, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * IP addresses of all trusted (paired) nodes — every encrypted-prefs entry is an `ip -> key`
     * pairing except the two named hotspot-credential keys, which are excluded so the list is
     * devices-only. The "Paired" tile count and popup are both derived from this.
     */
    fun getTrustedNodes(context: Context): List<String> {
        return getEncryptedPrefs(context).all.keys
            .filter { it != KEY_HOTSPOT_SSID && it != KEY_HOTSPOT_PSK }
    }

    // ---- Off-grid hotspot auto-connect ----------------------------------------------------
    // Android exposes no API to read a user's MANUAL hotspot SSID/password, so the user enters
    // them once; we store them encrypted (a Wi-Fi password is a secret) and embed them in the
    // off-grid QR so a receiver auto-joins the hotspot.
    private const val KEY_HOTSPOT_SSID = "hotspot_ssid"
    private const val KEY_HOTSPOT_PSK = "hotspot_psk"

    fun saveHotspotCredentials(context: Context, ssid: String, psk: String) {
        getEncryptedPrefs(context).edit()
            .putString(KEY_HOTSPOT_SSID, ssid)
            .putString(KEY_HOTSPOT_PSK, psk)
            .apply()
    }

    /** (ssid, psk) if both are set, else null. */
    fun getHotspotCredentials(context: Context): Pair<String, String>? {
        val prefs = getEncryptedPrefs(context)
        val ssid = prefs.getString(KEY_HOTSPOT_SSID, null)
        val psk = prefs.getString(KEY_HOTSPOT_PSK, null)
        return if (!ssid.isNullOrEmpty() && !psk.isNullOrEmpty()) Pair(ssid, psk) else null
    }

    fun clearHotspotCredentials(context: Context) {
        getEncryptedPrefs(context).edit()
            .remove(KEY_HOTSPOT_SSID)
            .remove(KEY_HOTSPOT_PSK)
            .apply()
    }
}
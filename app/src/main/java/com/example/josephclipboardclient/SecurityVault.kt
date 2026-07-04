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

    // Built once per process: MasterKey + EncryptedSharedPreferences.create is full Keystore
    // work, and hot paths (the 2s notification loop, per-peer key lookups during broadcast)
    // used to redo it on every call — on the Main thread (AND-06).
    @Volatile private var cachedEncryptedPrefs: android.content.SharedPreferences? = null

    private fun getEncryptedPrefs(context: Context): android.content.SharedPreferences {
        cachedEncryptedPrefs?.let { return it }
        synchronized(this) {
            cachedEncryptedPrefs?.let { return it }
            val appCtx = context.applicationContext
            val prefs = try {
                buildEncryptedPrefs(appCtx)
            } catch (e: Exception) {
                // Keyset corrupted (restore-to-new-device, Keystore reset, biometric/lock change,
                // or a legacy plaintext write into this file). Drop it and recreate so the app
                // stays usable — the only cost is that trusted nodes must be re-paired.
                try { appCtx.deleteSharedPreferences(PREFS_NAME) } catch (_: Exception) {}
                buildEncryptedPrefs(appCtx)
            }
            cachedEncryptedPrefs = prefs
            return prefs
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
        // Also drop the cached display name AND the anti-downgrade pins so an unpaired device
        // doesn't linger in the Paired list and a fresh re-pair starts from a clean TOFU state.
        getPlainPrefs(context).edit()
            .remove("paired_device_name_$ipAddress")
            .remove(PIN_FILE_GCM_PREFIX + ipAddress)
            .remove(PIN_CLIP_GCM_PREFIX + ipAddress)
            .remove(PIN_AUTH2_PREFIX + ipAddress)
            .remove(PIN_FILE_GCM_V2_PREFIX + ipAddress)
            .apply()
    }

    // ---- Anti-downgrade pins (TOFU) -------------------------------------------------------
    // Capability advertisement happens over UNAUTHENTICATED UDP discovery, so an on-path attacker
    // could strip the GCM1 / FGCM1 token from a peer's advert and force two AEAD-capable peers back
    // onto the weaker path (malleable CBC clipboard / plaintext file bytes). Once we have actually
    // negotiated an authenticated channel with a peer we pin that fact here, keyed by IP, and
    // afterwards refuse to silently downgrade — we fail closed instead. These are non-secret
    // booleans, so they live in plain prefs; both peers pin independently and enforce on their own
    // side. Pins are cleared on unpair (removeTrustedNode), so a deliberate re-pair resets TOFU.
    private const val PIN_FILE_GCM_PREFIX = "file_gcm_pinned_"
    private const val PIN_CLIP_GCM_PREFIX = "clipboard_gcm_pinned_"
    // XP-DEF-01: the FGCM1/GCM1 pins above didn't cover two stronger capabilities that also ride the
    // unauthenticated discovery — the AUTH2 admission challenge and the v2 FileSeal (per-session key).
    // A spoofed advert could strip AUTH2 (re-opening the IP-spoof offer hole) or force a v2→v1 file-key
    // downgrade. Pin both once genuinely used and fail closed on a later strip, same TOFU rule.
    private const val PIN_AUTH2_PREFIX = "auth2_pinned_"
    private const val PIN_FILE_GCM_V2_PREFIX = "file_gcm_v2_pinned_"

    /** Remember that [ip] negotiated an authenticated (FileSeal/FGCM1) bulk-file stream. */
    fun pinFileGcm(context: Context, ip: String) {
        getPlainPrefs(context).edit().putBoolean(PIN_FILE_GCM_PREFIX + ip, true).apply()
    }

    /** True once [ip] has been seen to support FileSeal — a later loss of FGCM1 is a downgrade attempt. */
    fun isFileGcmPinned(context: Context, ip: String): Boolean =
        getPlainPrefs(context).getBoolean(PIN_FILE_GCM_PREFIX + ip, false)

    /** Remember that [ip] proved possession of the pairing key via the AUTH2 OFFER challenge. */
    fun pinAuth2(context: Context, ip: String) {
        getPlainPrefs(context).edit().putBoolean(PIN_AUTH2_PREFIX + ip, true).apply()
    }

    /** True once [ip] has completed an AUTH2 proof — a later loss of AUTH2 is a downgrade attempt. */
    fun isAuth2Pinned(context: Context, ip: String): Boolean =
        getPlainPrefs(context).getBoolean(PIN_AUTH2_PREFIX + ip, false)

    /** Remember that [ip] negotiated a v2 (per-session key) FileSeal stream. */
    fun pinFileGcmV2(context: Context, ip: String) {
        getPlainPrefs(context).edit().putBoolean(PIN_FILE_GCM_V2_PREFIX + ip, true).apply()
    }

    /** True once [ip] has been seen to support FGCM2 — a later loss (forcing v1) is a downgrade attempt. */
    fun isFileGcmV2Pinned(context: Context, ip: String): Boolean =
        getPlainPrefs(context).getBoolean(PIN_FILE_GCM_V2_PREFIX + ip, false)

    /** Remember that [ip] negotiated an authenticated (AES-GCM / GCM1) clipboard payload. */
    fun pinClipboardGcm(context: Context, ip: String) {
        getPlainPrefs(context).edit().putBoolean(PIN_CLIP_GCM_PREFIX + ip, true).apply()
    }

    /** True once [ip] has been seen to support authenticated clipboard — a later loss of GCM1 is a downgrade. */
    fun isClipboardGcmPinned(context: Context, ip: String): Boolean =
        getPlainPrefs(context).getBoolean(PIN_CLIP_GCM_PREFIX + ip, false)

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
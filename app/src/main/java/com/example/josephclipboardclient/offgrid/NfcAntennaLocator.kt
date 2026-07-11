package com.EntropicSoftwareLab.ClipboardShare.offgrid

import android.content.Context
import android.nfc.NfcAdapter
import android.os.Build
import com.EntropicSoftwareLab.ClipboardShare.FILE_BRIDGE_PREFS

/** A coarse vertical zone on the back of a phone where the NFC antenna sits. */
enum class NfcZone { TOP, CENTER, BOTTOM }

/**
 * How the user should physically orient the two phones for the cleanest NFC tap,
 * derived from where this device's antenna sits:
 *
 *  - [FLAT]     antenna at the very top   → lay the phones flat and touch the tops (AirDrop-style).
 *  - [ANGLED]   antenna in the upper area → stand the phones at ~45° and tap the upper backs.
 *  - [VERTICAL] antenna near the middle   → stand the phones upright and tap back-to-back.
 */
enum class TapStyle { FLAT, ANGLED, VERTICAL }

/**
 * Determines (once, cached) where this device's NFC antenna is, so the tap-guide
 * animation can show the user how to hold the phones.
 *
 *  - Android 14+ (API 34): the real [android.nfc.NfcAntennaInfo] coordinates,
 *    mapped to a [NfcZone] / [TapStyle].
 *  - Older devices: a small built-in lookup by manufacturer/model family.
 *  - Unknown: TOP / FLAT (the most common location, and the default the "other phone"
 *    in the animation always uses).
 *
 * The result is device-global, so it is cached in SharedPreferences on first use.
 */
object NfcAntennaLocator {

    private const val PREFS = FILE_BRIDGE_PREFS
    private const val KEY_ZONE = "nfc_antenna_zone"
    private const val KEY_STYLE = "nfc_tap_style"

    /** Self-caching: computes + stores on first call, reads the cache afterward. */
    fun zoneFor(context: Context): NfcZone {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ZONE, null)?.let { cached ->
            return runCatching { NfcZone.valueOf(cached) }.getOrDefault(NfcZone.TOP)
        }
        val zone = computeZone(context)
        prefs.edit().putString(KEY_ZONE, zone.name).apply()
        return zone
    }

    /**
     * Vertical position of this device's NFC antenna as a fraction of the device height,
     * where 1.0 is the very top of the back and 0.0 is the bottom. Used to place the wave
     * origin in the tap-guide animation.
     *
     * Prefers the real API-34+ coordinates; otherwise derives a sensible fraction from the
     * cached [NfcZone] (TOP ~0.9, CENTER ~0.5, BOTTOM ~0.15). Defaults to ~0.9 (TOP).
     */
    fun antennaVerticalFraction(context: Context): Float {
        antennaFraction(context)?.let { return it }
        return when (zoneFor(context)) {
            NfcZone.TOP -> 0.9f
            NfcZone.CENTER -> 0.5f
            NfcZone.BOTTOM -> 0.15f
        }
    }

    /** Self-caching companion to [zoneFor]: the recommended hold orientation. */
    fun tapStyleFor(context: Context): TapStyle {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_STYLE, null)?.let { cached ->
            return runCatching { TapStyle.valueOf(cached) }.getOrDefault(TapStyle.FLAT)
        }
        val style = computeStyle(context)
        prefs.edit().putString(KEY_STYLE, style.name).apply()
        return style
    }

    private fun computeZone(context: Context): NfcZone {
        antennaFraction(context)?.let { return zoneFromFraction(it) }
        return tableZone()
    }

    private fun computeStyle(context: Context): TapStyle {
        // The exact antenna fraction gives a finer FLAT / ANGLED / VERTICAL split than
        // the 3-bucket zone, so prefer it when API 34+ exposes real coordinates.
        antennaFraction(context)?.let { return styleFromFraction(it) }
        return styleFromZone(tableZone())
    }

    private fun zoneFromFraction(frac: Float): NfcZone = when {
        frac >= 0.60f -> NfcZone.TOP
        frac <= 0.33f -> NfcZone.BOTTOM
        else -> NfcZone.CENTER
    }

    private fun styleFromFraction(frac: Float): TapStyle = when {
        frac >= 0.80f -> TapStyle.FLAT       // antenna right at the top edge
        frac >= 0.55f -> TapStyle.ANGLED     // upper area, but not the tip
        else -> TapStyle.VERTICAL            // middle or lower → hold upright
    }

    private fun styleFromZone(zone: NfcZone): TapStyle = when (zone) {
        NfcZone.TOP -> TapStyle.FLAT
        NfcZone.CENTER -> TapStyle.VERTICAL
        NfcZone.BOTTOM -> TapStyle.VERTICAL
    }

    /** API 34+ antenna position as a fraction of device height (1.0 = top), or null. */
    private fun antennaFraction(context: Context): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return try {
            val adapter = NfcAdapter.getDefaultAdapter(context) ?: return null
            val info = adapter.nfcAntennaInfo ?: return null
            val antennas = info.availableNfcAntennas
            if (antennas.isEmpty()) return null
            val deviceHeight = info.deviceHeight
            if (deviceHeight <= 0) return null
            // locationY is millimetres from the BOTTOM-left of the device back, so a
            // high fraction means physically near the top.
            (antennas[0].locationY.toFloat() / deviceHeight.toFloat()).coerceIn(0f, 1f)
        } catch (_: Exception) {
            null
        }
    }

    /** Best-effort lookup for pre-34 devices. Most modern phones are upper-back. */
    private fun tableZone(): NfcZone {
        val maker = Build.MANUFACTURER?.lowercase()?.trim() ?: ""
        val model = Build.MODEL?.lowercase()?.trim() ?: ""
        return when {
            maker.contains("samsung") ->
                // Galaxy A mid-range tends to be center; flagship S/Z and rest are upper.
                if (model.startsWith("sm-a") || model.contains("galaxy a")) NfcZone.CENTER else NfcZone.TOP
            maker.contains("google") || model.contains("pixel") -> NfcZone.CENTER
            maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco") -> NfcZone.TOP
            maker.contains("oppo") || maker.contains("oneplus") || maker.contains("realme") -> NfcZone.CENTER
            maker.contains("motorola") || maker.contains("moto") -> NfcZone.CENTER
            maker.contains("sony") -> NfcZone.TOP
            maker.contains("huawei") || maker.contains("honor") -> NfcZone.TOP
            maker.contains("nothing") -> NfcZone.CENTER
            else -> NfcZone.TOP
        }
    }
}

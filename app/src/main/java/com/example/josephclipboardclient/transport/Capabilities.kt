package com.EntropicSoftwareLab.ClipboardShare.transport

import android.content.Context
import com.EntropicSoftwareLab.ClipboardShare.Net
import com.EntropicSoftwareLab.ClipboardShare.SecurityVault

/**
 * A device's Wi-Fi rendezvous capabilities, computed once per off-grid session and exchanged over
 * the control channel so the sender's [SenderSessionController.buildPlan] can pick a route that
 * actually works. The two derived flags are the whole decision input for the "hotspot is on" logic:
 *
 *  - [canJoinAp]  — my Wi-Fi STA is free to connect to a peer's AP/hotspot. True when I host no
 *                   hotspot, OR I host one but my chip supports STA+AP concurrency (so the STA radio
 *                   is still available). This is why "hotspot on" no longer means "no Wi-Fi".
 *  - [canHostAp]  — I can be the rendezvous AP: I have a live hotspot AND saved its creds (the OS
 *                   forbids reading a manual hotspot's own SSID/psk, so the user must save them once).
 *  - [staConnected] — my Wi-Fi STA is currently joined to a real external AP/router (NOT free). This is
 *                   the "who is free to host" input: when exactly one device is free (not staConnected),
 *                   it should host so neither phone has to drop its Wi-Fi (see GoFitness.electHost).
 *
 * Bluetooth is intentionally absent — it is control-only now, never a data path.
 */
data class DeviceCaps(
    val hotspotActive: Boolean,
    val staApConcurrency: Boolean,
    val hasCreds: Boolean,
    val staConnected: Boolean,
    val hotspotIp: String?,
    val lanIp: String?
) {
    val canJoinAp: Boolean get() = !hotspotActive || staApConcurrency
    val canHostAp: Boolean get() = hotspotActive && hasCreds

    companion object {
        /** Conservative defaults for a legacy peer that didn't send capability fields. */
        fun legacyDefault(): DeviceCaps =
            DeviceCaps(
                hotspotActive = false, staApConcurrency = false, hasCreds = false,
                staConnected = false, hotspotIp = null, lanIp = null
            )

        /** Rebuild a peer's caps from the booleans it advertised in HELLO (host-only fields nullable). */
        fun fromPeer(
            hotspotActive: Boolean,
            staApConcurrency: Boolean,
            hasCreds: Boolean,
            staConnected: Boolean
        ): DeviceCaps =
            DeviceCaps(hotspotActive, staApConcurrency, hasCreds, staConnected, hotspotIp = null, lanIp = null)
    }
}

object Capabilities {
    /** Snapshot THIS device's rendezvous capabilities. Cheap; reads live interfaces + saved creds. */
    fun compute(context: Context): DeviceCaps {
        val hotspotIp = Net.detectHotspotIp(context)
        return DeviceCaps(
            hotspotActive = hotspotIp != null,
            staApConcurrency = Net.staApConcurrencySupported(context),
            hasCreds = SecurityVault.getHotspotCredentials(context) != null,
            // "Not free": my Wi-Fi STA is already joined to a real router/AP (excludes p2p/hotspot/cellular).
            staConnected = Net.isConnectedToOtherWifi(context),
            hotspotIp = hotspotIp,
            lanIp = Net.localLanIpv4()
        )
    }
}

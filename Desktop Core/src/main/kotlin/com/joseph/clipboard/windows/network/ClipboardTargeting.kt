package com.joseph.clipboard.windows.network

/**
 * Decides which address to send each paired device's clipboard to, so auto-push follows a device
 * that changed DHCP lease instead of writing to its dead pairing address. Pure and platform-neutral:
 * the Android client holds a byte-identical copy (`ClipboardTargeting.kt`) and both `ClipboardTargetingTest`s
 * pin the same behaviour.
 */
object ClipboardTargeting {
    /** A stored trust record: the address it was paired at, and the peer's stable device id (null for
     *  records paired before device ids existed, which therefore can't be followed). */
    data class Trusted(val ip: String, val deviceId: String?)

    /** A device currently seen on the LAN: its live address and advertised device id (null for peers
     *  that predate the discovery `did:` token). */
    data class Live(val ip: String, val deviceId: String?)

    /**
     * `(keyIp, targetIp)` for each trusted record reachable right now. `keyIp` is always the pairing
     * address — where the AES key and the TOFU pin live — even when `targetIp` is somewhere new.
     *
     * - **Our device still at its pairing address** → send there. This is the common case, and the
     *   safest: an unauthenticated discovery packet can't redirect a device that never moved. "Ours"
     *   means the ids agree, or one side has no id to compare (a legacy record, or a peer not
     *   advertising one) — in which case we fall back to address identity as before.
     * - **Otherwise, followed by id** — when the pairing address is empty OR a DIFFERENT device now
     *   holds it (a reused DHCP lease), and EXACTLY one live device advertises the record's id. Zero
     *   means offline; more than one means a spoofer is racing the real device, and both fail safe to
     *   "don't send" rather than deliver to the wrong host.
     * - **Legacy records** (no stored device id) can't be followed, so they are skipped once their
     *   pairing address is empty or taken by another device.
     *
     * Nothing is persisted: resolved fresh on every push, so a transient bad match self-corrects on
     * the next copy.
     */
    fun resolve(trusted: List<Trusted>, live: List<Live>): List<Pair<String, String>> =
        trusted.mapNotNull { record ->
            val here = live.firstOrNull { it.ip == record.ip }
            if (here != null && (record.deviceId == null || here.deviceId == null || here.deviceId == record.deviceId)) {
                record.ip to record.ip
            } else {
                val id = record.deviceId ?: return@mapNotNull null
                val moved = live.filter { it.deviceId == id }.singleOrNull() ?: return@mapNotNull null
                record.ip to moved.ip
            }
        }
}

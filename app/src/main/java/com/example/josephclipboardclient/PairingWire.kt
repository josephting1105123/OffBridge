package com.EntropicSoftwareLab.ClipboardShare

/**
 * Builders/parsers for the LAN pairing handshake lines. These strings ARE the cross-platform wire
 * format — the Windows client's `network/WireFrames.kt` holds the byte-identical counterpart, and
 * `PairingWireTest` pins both shapes.
 *
 * Both frames end in an unordered token tail. `pv2` (PIN v2) has ridden there since 3.5.0 and both
 * platforms already read it with `contains`, so appending `did:<32 hex>` is invisible to older
 * builds — they keep their positional fields and ignore the extra token. A peer that sends no did
 * simply doesn't get stale-record eviction.
 */
object PairingWire {

    private const val DEVICE_ID_TOKEN = "did:"

    /** `PAIR_REQ|name|pubkeyB64|pv2|did:<hex>` */
    fun pairReq(deviceName: String, pubKeyB64: String, deviceId: String): String =
        "PAIR_REQ|$deviceName|$pubKeyB64|pv2|$DEVICE_ID_TOKEN$deviceId"

    /** `PAIR_ACK|pubkeyB64|pv2|did:<hex>` */
    fun pairAck(pubKeyB64: String, deviceId: String): String =
        "PAIR_ACK|$pubKeyB64|pv2|$DEVICE_ID_TOKEN$deviceId"

    /**
     * Peer device id from a frame's token tail (the fields after the positional ones), or null when
     * absent or malformed. Only 32 lowercase hex chars are accepted — the value is persisted and
     * compared, so a peer must not be able to smuggle a delimiter or an arbitrary-length blob in.
     */
    fun parseDeviceId(tokens: List<String>): String? = tokens
        .firstOrNull { it.startsWith(DEVICE_ID_TOKEN) }
        ?.removePrefix(DEVICE_ID_TOKEN)
        ?.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}

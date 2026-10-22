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

    /**
     * Tail token: "I take part in the initiator confirm step". The initiator sends it to say it will
     * send [PAIR_CONFIRM] once its own user has compared PINs, so the responder holds trust until
     * that line arrives. The responder sends it to say it waits for that line. Either side missing
     * it means an older peer, and the initiator neither sends nor expects the extra line.
     */
    const val CONFIRM_TOKEN = "pc1"

    /** Initiator -> responder, after PAIR_ACCEPT and after its user tapped "Codes match". */
    const val PAIR_CONFIRM = "PAIR_CONFIRM"

    /** Prefix marking the stable device-id token in both the pairing tail and the discovery frame. */
    const val DEVICE_ID_TOKEN = "did:"

    /** `PAIR_REQ|name|pubkeyB64|pv2|did:<hex>|pc1` */
    fun pairReq(deviceName: String, pubKeyB64: String, deviceId: String): String =
        "PAIR_REQ|$deviceName|$pubKeyB64|pv2|$DEVICE_ID_TOKEN$deviceId|$CONFIRM_TOKEN"

    /** `PAIR_ACK|pubkeyB64|pv2|did:<hex>|pc1` */
    fun pairAck(pubKeyB64: String, deviceId: String): String =
        "PAIR_ACK|$pubKeyB64|pv2|$DEVICE_ID_TOKEN$deviceId|$CONFIRM_TOKEN"

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

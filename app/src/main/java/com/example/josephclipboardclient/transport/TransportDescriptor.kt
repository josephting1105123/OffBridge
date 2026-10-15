package com.EntropicSoftwareLab.ClipboardShare.transport

/**
 * Transport tiers of the V4 ladder (§3.2 of the V4 plan).
 *
 * [id] values are STABLE: they are the primary keys of the `transport`
 * dimension table and the ids carried by TRY_TRANSPORT / TRANSPORT_RESULT
 * control frames. Never renumber.
 */
enum class TransportDescriptor(val id: Int, val wireName: String, val budgetMs: Long) {
    /** T1 — join the sender's existing GO with SSID/PSK via WifiP2pConfig.Builder. */
    P2P_CRED_JOIN(0, "P2P_CRED_JOIN", 15_000L),

    /** T0 — WifiNetworkSpecifier picker (demoted: runs when T1's permission is denied). */
    SPECIFIER(1, "SPECIFIER", 25_000L),

    /** T2 — negotiated P2P connect (bidirectional when the control channel is up, PBC join when not). */
    P2P_NEGOTIATED(2, "P2P_NEGOTIATED", 20_000L),

    /** T3 — LocalOnlyHotspot host on the sender; receiver joins via specifier. */
    LOHS(3, "LOHS", 25_000L),

    /**
     * T4 — RETIRED as a data path: Bluetooth (any PHY) tops out ~150-200 KB/s, ~100x slower than
     * Wi-Fi, so it is never added to a plan or executed anymore. The id + [TransportCaps.BT_L2CAP]
     * stay declared ONLY for DB/wire stability (existing `transport_attempt` rows reference id 4).
     * Bluetooth now survives solely as a CONTROL channel (BLE GATT → RFCOMM SPP fallback), not data.
     */
    BT_L2CAP(4, "BT_L2CAP", 10_000L),

    /** T5 — Wi-Fi Aware (optional Phase 5 tier; declared for id stability). */
    AWARE(5, "AWARE", 15_000L),

    /**
     * T6 — role-flipped Wi-Fi Direct: the RECEIVER creates the autonomous group
     * and the SENDER joins as client (GO election). Reuses the proven
     * P2pGroupManager + P2pCredentialJoiner primitives with the devices swapped;
     * the file-sender still serves TCP, now on its client p2p IP.
     */
    RECEIVER_AS_GO(6, "RECEIVER_AS_GO", 20_000L),

    /**
     * T7 — SoftAP bridge: the RECEIVER joins the SENDER's mobile hotspot (a real SoftAP, not a
     * Wi-Fi Direct GO) via [WifiNetworkSpecifier] / SpecifierJoiner using creds the sender saved.
     * The sender already serves TCP on its hotspot interface. This is the correct path for hotspot
     * creds — distinct from P2P_CRED_JOIN, which only joins a Wi-Fi Direct GO (WifiP2pConfig).
     */
    SOFTAP_JOIN(7, "SOFTAP_JOIN", 25_000L),

    /**
     * T8 — SoftAP bridge, role-flipped: the SENDER joins the RECEIVER's mobile hotspot via
     * SpecifierJoiner, binds its TCP server on that interface, and sends DATA_READY(senderApIp);
     * the receiver (hotspot host) connects out. The SoftAP mirror of RECEIVER_AS_GO. Only viable
     * when the receiver already has a hotspot up + saved creds (the app cannot start a SoftAP).
     */
    SENDER_JOINS_AP(8, "SENDER_JOINS_AP", 25_000L);

    companion object {
        fun fromId(id: Int): TransportDescriptor? = values().firstOrNull { it.id == id }
    }
}

/**
 * What kind of access point a rendezvous SSID/PSK refers to. Carried in HELLO (`nk`) and in
 * TRY_TRANSPORT / HOST_READY params so each side joins correctly: a Wi-Fi Direct group via
 * [com.EntropicSoftwareLab.ClipboardShare.transport.wifidirect.P2pCredentialJoiner], a mobile
 * hotspot (SoftAP) via [com.EntropicSoftwareLab.ClipboardShare.transport.hotspot.SpecifierJoiner].
 */
enum class NetKind(val id: Int) {
    P2P_GROUP(0),
    SOFTAP(1);

    companion object {
        fun fromId(id: Int): NetKind = values().firstOrNull { it.id == id } ?: P2P_GROUP
    }
}

/** Stable failure codes recorded in `transport_attempt.failure_code` and TRANSPORT_RESULT frames. */
enum class FailureCode(val id: Int) {
    NONE(0),
    PICKER_TIMEOUT(1),
    GO_BUSY(2),
    LOHS_DENIED(3),
    PERMISSION_DENIED(4),
    RADIO_OFF(5),
    BAND_MISMATCH(6),
    CONNECT_TIMEOUT(7),
    HANDSHAKE_FAILED(8),
    CHANNEL_DIED(9),
    UNSUPPORTED(10),
    CANCELLED(11);

    companion object {
        fun fromId(id: Int): FailureCode = values().firstOrNull { it.id == id } ?: NONE
    }
}

/** `transport_attempt.outcome` values. */
object AttemptOutcome {
    const val PENDING = 0
    const val CONNECTED = 1
    const val FAILED = 2
    const val SKIPPED = 3
}

/** Capability bitmap carried in the payload `cap` field and HELLO frames (§5). */
object TransportCaps {
    const val BLE_ADVERTISE = 1 shl 0
    const val BLE_SCAN = 1 shl 1
    const val BT_L2CAP = 1 shl 2
    const val LOHS = 1 shl 3
    const val AWARE = 1 shl 4
    /**
     * Data-plane (not a transport tier): the sender honours an end-to-end
     * TRANSFER_COMPLETE ack — it declares the transfer done only after the
     * receiver confirms every byte landed, instead of on TCP write. Lets the
     * receiver's resume reconnect to a still-live session after a late drop.
     */
    const val COMPLETION_ACK = 1 shl 5

    /**
     * Data-plane (not a transport tier): the sender will FileSeal (AES-256-GCM) the off-grid file
     * stream when the receiver asks for it, keyed by the session's ECDH secret. Identical to Windows
     * `OffGridQrSender.CAP_SEALED_STREAM`.
     *
     * Historically the off-grid stream was PLAINTEXT — the ECDH secret authenticated admission (the
     * P2P_HELLO/P2P_WELCOME HMACs) but never protected the bytes, unlike the LAN path which has been
     * sealed since 3.4.0. Negotiation is two-sided so nothing already installed breaks: an old sender
     * never sets this bit so a new receiver never asks, and an old receiver ignores an unknown bit so
     * it never sends the "seal" flag. Only new↔new seals.
     */
    const val SEALED_STREAM = 1 shl 6

    /**
     * Control plane: this build seals BLE control frames with [SessionCrypto] v2, which binds the
     * frame header (type|seq|len) as GCM AAD under its own HKDF label. Identical to Windows
     * `OffGridQrSender.CAP_CTRL_AAD`.
     *
     * The receiver decides from the QR/NFC payload's `cap` (shown out of band, so a BLE attacker
     * can't edit it) and always sets this bit in HELLO_RX; the sender decides from HELLO_RX. Old↔new
     * stays v1 on both ends. If an attacker strips the bit from the plaintext HELLO_RX, the sender
     * falls to v1 while the receiver stays on v2, nothing opens, and the session fails closed instead
     * of downgrading.
     */
    const val CTRL_AAD = 1 shl 7
}

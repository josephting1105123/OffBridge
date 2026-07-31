package com.EntropicSoftwareLab.ClipboardShare.transport

/**
 * Can this device receive from THIS sender at all?
 *
 * The receive screen used to answer that before a code had even been scanned, from the radio state
 * alone: "hotspot on AND associated to another Wi-Fi" put up a full-screen block and suppressed
 * scanning. That decision is made without knowing the sender's route, and it is wrong whenever the
 * sender is reachable anyway — the two phones sharing a Wi-Fi (LAN carries the transfer), or the
 * sender joining this phone's hotspot (which only works BECAUSE the hotspot is on).
 *
 * So the answer is computed here instead, against a decoded [TransportDescriptor]-bearing payload,
 * and it names the routes that survive rather than returning a bare boolean — the caller blocks only
 * when nothing survives. Pure, so the whole matrix is unit-tested.
 */
object ReceiveViability {

    /** A rendezvous this pairing could still use. */
    enum class Route {
        /** Both devices already share a network and the sender published a reachable LAN IP. */
        LAN,
        /** The sender hosts a hotspot/SoftAP we can join (our Wi-Fi station has to be free). */
        JOIN_SENDER_AP,
        /** The sender joins OUR mobile hotspot and serves from there (tier SENDER_JOINS_AP). */
        SENDER_JOINS_MY_HOTSPOT,
        /** Wi-Fi Direct off the credentials carried in the QR/NFC payload — needs no control channel. */
        WIFI_DIRECT,
    }

    /**
     * @param lanReachable            the payload carries a lanIp that one of our interfaces puts
     *                                on-link (see `Net.hasOnLinkRoute`).
     * @param senderIsSoftApBridge    the payload's credentials describe the sender's mobile hotspot.
     * @param senderHasApCredentials  the payload carries a joinable ssid+psk of any kind.
     * @param onOtherWifi             our Wi-Fi station is already associated to an unrelated network.
     * @param hotspotOn               our mobile hotspot is up.
     * @param haveSavedHotspotCreds   we know our own hotspot's password, so we can hand it over.
     * @param controlChannelPossible  both sides can run the BLE control channel (the only way to
     *                                relay our hotspot credentials to the sender).
     */
    fun viableRoutes(
        lanReachable: Boolean,
        senderIsSoftApBridge: Boolean,
        senderHasApCredentials: Boolean,
        onOtherWifi: Boolean,
        hotspotOn: Boolean,
        haveSavedHotspotCreds: Boolean,
        controlChannelPossible: Boolean
    ): Set<Route> {
        val routes = linkedSetOf<Route>()
        if (lanReachable) routes.add(Route.LAN)
        // Joining someone else's AP means retuning our single radio, which the OS will not do for a
        // per-app WifiNetworkSpecifier while the station is held by another network.
        if (senderIsSoftApBridge && senderHasApCredentials && !onOtherWifi) routes.add(Route.JOIN_SENDER_AP)
        // The mirror image, and the case the old pre-scan block destroyed: our hotspot being on is
        // the precondition, not the obstacle. It still needs saved credentials (Android exposes no
        // API to read a manual hotspot's password) and a control channel to deliver them.
        if (hotspotOn && haveSavedHotspotCreds && controlChannelPossible) {
            routes.add(Route.SENDER_JOINS_MY_HOTSPOT)
        }
        // Credentials from the payload drive P2P_CRED_JOIN with no control channel at all. A
        // SoftAP-bridge payload's credentials are the sender's hotspot, not a Wi-Fi Direct group.
        if (senderHasApCredentials && !senderIsSoftApBridge) routes.add(Route.WIFI_DIRECT)
        return routes
    }

    /** Convenience: nothing can carry this transfer, so the blocking sheet is the honest answer. */
    fun isHardBlocked(
        lanReachable: Boolean,
        senderIsSoftApBridge: Boolean,
        senderHasApCredentials: Boolean,
        onOtherWifi: Boolean,
        hotspotOn: Boolean,
        haveSavedHotspotCreds: Boolean,
        controlChannelPossible: Boolean
    ): Boolean = viableRoutes(
        lanReachable, senderIsSoftApBridge, senderHasApCredentials,
        onOtherWifi, hotspotOn, haveSavedHotspotCreds, controlChannelPossible
    ).isEmpty()
}

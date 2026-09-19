package com.joseph.clipboard.windows.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the clipboard auto-push target resolution. Mirrors Android's ClipboardTargetingTest — the two
 * resolvers are byte-identical, so a divergence here is a cross-platform bug.
 */
class ClipboardTargetingTest {
    private fun t(ip: String, id: String? = null) = ClipboardTargeting.Trusted(ip, id)
    private fun l(ip: String, id: String? = null) = ClipboardTargeting.Live(ip, id)

    @Test
    fun sendsToThePairingAddressWhenTheDeviceIsStillThere() {
        assertEquals(
            listOf("192.168.0.10" to "192.168.0.10"),
            ClipboardTargeting.resolve(listOf(t("192.168.0.10", "id1")), listOf(l("192.168.0.10", "id1"))),
        )
    }

    @Test
    fun followsAMovedDeviceToItsNewAddressById() {
        assertEquals(
            listOf("192.168.0.10" to "192.168.0.55"),
            ClipboardTargeting.resolve(listOf(t("192.168.0.10", "id1")), listOf(l("192.168.0.55", "id1"))),
        )
    }

    @Test
    fun skipsAnOfflineDevice() {
        assertEquals(
            emptyList<Pair<String, String>>(),
            ClipboardTargeting.resolve(listOf(t("192.168.0.10", "id1")), emptyList()),
        )
    }

    /** No stored device id → the device can't be followed once it leaves its pairing address. */
    @Test
    fun skipsALegacyRecordThatIsNoLongerAtItsPairingAddress() {
        assertEquals(
            emptyList<Pair<String, String>>(),
            ClipboardTargeting.resolve(listOf(t("192.168.0.10", null)), listOf(l("192.168.0.55", "id1"))),
        )
    }

    /** A spoofer racing the real device → ambiguous → skip rather than misdeliver. */
    @Test
    fun refusesToFollowWhenTwoDevicesClaimTheSameId() {
        assertEquals(
            emptyList<Pair<String, String>>(),
            ClipboardTargeting.resolve(
                listOf(t("192.168.0.10", "id1")),
                listOf(l("192.168.0.55", "id1"), l("192.168.0.99", "id1")),
            ),
        )
    }

    /** Device present at its pairing address AND an impostor elsewhere claims its id → send to the
     *  pairing address and ignore the id entirely. */
    @Test
    fun prefersTheDirectAddressEvenIfAnotherHostClaimsTheId() {
        assertEquals(
            listOf("192.168.0.10" to "192.168.0.10"),
            ClipboardTargeting.resolve(
                listOf(t("192.168.0.10", "id1")),
                listOf(l("192.168.0.10", "id1"), l("192.168.0.99", "id1")),
            ),
        )
    }

    /** The pairing address was reused by a DIFFERENT device (new DHCP lease) while ours moved →
     *  follow ours by id, don't send to the new occupant. */
    @Test
    fun followsByIdWhenTheOldAddressWasTakenByAnotherDevice() {
        assertEquals(
            listOf("192.168.0.10" to "192.168.0.55"),
            ClipboardTargeting.resolve(
                listOf(t("192.168.0.10", "mine")),
                listOf(l("192.168.0.10", "someone-else"), l("192.168.0.55", "mine")),
            ),
        )
    }

    /** A peer at the pairing address that isn't advertising an id yet → can't compare, so trust the
     *  address and send there. */
    @Test
    fun sendsToThePairingAddressWhenTheLiveDeviceHasNoId() {
        assertEquals(
            listOf("192.168.0.10" to "192.168.0.10"),
            ClipboardTargeting.resolve(listOf(t("192.168.0.10", "mine")), listOf(l("192.168.0.10", null))),
        )
    }

    @Test
    fun resolvesEachRecordIndependentlyForAMixedSet() {
        val out = ClipboardTargeting.resolve(
            listOf(t("10.0.0.1", "a"), t("10.0.0.2", "b"), t("10.0.0.3", null)),
            listOf(l("10.0.0.9", "a"), l("10.0.0.2", "b")),
        )
        assertEquals(listOf("10.0.0.1" to "10.0.0.9", "10.0.0.2" to "10.0.0.2"), out)
    }
}

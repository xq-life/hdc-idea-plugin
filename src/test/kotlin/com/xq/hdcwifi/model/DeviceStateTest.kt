package com.xq.hdcwifi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the authoritative four-state model of UX-CONTRACT §1 and the change signature that
 * UX-CONTRACT §3.5 / UX-SPEC §6 rely on to skip pointless UI rebuilds.
 *
 * Complements (does not duplicate) [HdcDeviceTest], which covers [groupDevices] and [HdcDevice.details].
 */
class DeviceStateTest {

    @Test
    fun `declares exactly the four contract states in order`() {
        assertEquals(
            listOf("DISCONNECTED", "CONNECTING", "CONNECTED", "FAILED"),
            DeviceConnectionState.values().map { it.name }
        )
        assertEquals(4, DeviceConnectionState.values().toSet().size)
        DeviceConnectionState.values().forEach { assertEquals(it, DeviceConnectionState.valueOf(it.name)) }
    }

    @Test
    fun `withState keeps exactly one state and mirrors the connected flag`() {
        val base = HdcDevice("192.168.1.10:5555", "Phone A", "HarmonyOS 5", "18")
        DeviceConnectionState.values().forEach { state ->
            val updated = base.withState(state, "why")
            assertEquals(state, updated.state)
            assertEquals(state == DeviceConnectionState.CONNECTED, updated.connected)
            assertEquals(state == DeviceConnectionState.CONNECTED, updated.isConnected)
            // everything the row shows must survive a state transition
            assertEquals(base.address, updated.address)
            assertEquals(base.name, updated.name)
            assertEquals(base.details, updated.details)
        }
    }

    @Test
    fun `withState carries the failure reason only when one is given`() {
        val failed = HdcDevice("a:1", "P").withState(DeviceConnectionState.FAILED, "boom")
        assertEquals("boom", failed.failureReason)
        assertFalse(failed.isConnected)

        // retry / disconnect clear the stale reason
        assertNull(failed.withState(DeviceConnectionState.DISCONNECTED).failureReason)
        assertNull(failed.withState(DeviceConnectionState.CONNECTING).failureReason)
        assertNull(failed.withState(DeviceConnectionState.CONNECTED).failureReason)
    }

    @Test
    fun `device signature is stable for identical data`() {
        fun snapshot() = DeviceGroups(
            connected = listOf(HdcDevice("10.0.0.1:5555", "Phone").withState(DeviceConnectionState.CONNECTED)),
            previous = listOf(HdcDevice("10.0.0.2:5555", "Tab", "HarmonyOS 5", "12").withState(DeviceConnectionState.FAILED, "e")),
            available = listOf(HdcDevice("10.0.0.3:5555", "Watch"))
        )

        val first = deviceSignature(snapshot())
        repeat(50) { assertEquals(first, deviceSignature(snapshot())) }
        assertEquals(first, deviceSignature(snapshot()))
    }

    @Test
    fun `device signature changes when any field of a row changes`() {
        val base = DeviceGroups(
            connected = listOf(HdcDevice("10.0.0.1:5555", "Phone")),
            previous = listOf(HdcDevice("10.0.0.2:5555", "Tab", "HarmonyOS 5", "12")),
            available = listOf(HdcDevice("10.0.0.3:5555", "Watch"))
        )
        val signature = deviceSignature(base)

        // address
        assertNotEquals(signature, deviceSignature(base.copy(connected = listOf(HdcDevice("10.0.0.9:5555", "Phone")))))
        // name
        assertNotEquals(signature, deviceSignature(base.copy(connected = listOf(HdcDevice("10.0.0.1:5555", "Renamed")))))
        // details -> system version
        assertNotEquals(signature, deviceSignature(base.copy(previous = listOf(HdcDevice("10.0.0.2:5555", "Tab", "HarmonyOS 6", "12")))))
        // details -> api version
        assertNotEquals(signature, deviceSignature(base.copy(previous = listOf(HdcDevice("10.0.0.2:5555", "Tab", "HarmonyOS 5", "13")))))
        // details -> no metadata at all (falls back to "HarmonyOS device - address")
        assertNotEquals(signature, deviceSignature(base.copy(previous = listOf(HdcDevice("10.0.0.2:5555", "Tab")))))
        // connection state, failure reason untouched
        assertNotEquals(signature, deviceSignature(base.copy(previous = listOf(base.previous.single().copy(state = DeviceConnectionState.FAILED)))))
        // failure reason, state untouched
        assertNotEquals(signature, deviceSignature(base.copy(previous = listOf(base.previous.single().copy(failureReason = "why")))))
        // group membership: the same device moved from Connected to Available
        assertNotEquals(signature, deviceSignature(base.copy(connected = emptyList(), available = listOf(HdcDevice("10.0.0.1:5555", "Phone")))))
        // a row added / removed
        assertNotEquals(signature, deviceSignature(base.copy(available = emptyList())))
        // a device promoted to Connected moves in the concatenation
        assertNotEquals(signature, deviceSignature(base.copy(connected = listOf(base.connected.single(), base.available.single()), available = emptyList())))
        // Group boundaries are part of the signature (GROUP_SEPARATOR), so a device moving from one
        // group to another is a visible change; the round-1 defect counterexample is guarded in
        // ContractRegressionTest#`a device moving between groups changes the signature`.
    }

    @Test
    fun `device signature is injective over single-field mutations`() {
        // The signature is the panel's only change detector, so two different snapshots must never
        // produce one string. Each entry below differs from `base` in exactly one respect (the reason
        // markers NO_REASON/HAS_REASON included).
        val base = HdcDevice("10.0.0.1:5555", "Phone", "HarmonyOS 4", "12").copy(failureReason = null)
        assertNull(base.failureReason)
        val variants = linkedMapOf(
            "base (no reason)" to DeviceGroups(listOf(base), emptyList()),
            "reason empty" to DeviceGroups(listOf(base.copy(failureReason = "")), emptyList()),
            "reason real" to DeviceGroups(listOf(base.copy(failureReason = "boom")), emptyList()),
            "reason absent marker" to DeviceGroups(listOf(base.copy(failureReason = "\u0000none\u0000")), emptyList()),
            "reason present marker" to DeviceGroups(listOf(base.copy(failureReason = "\u0000some\u0000")), emptyList()),
            "reason field separator" to DeviceGroups(listOf(base.copy(failureReason = "\u0000F\u0000")), emptyList()),
            "address" to DeviceGroups(listOf(base.copy(address = "10.0.0.2:5555")), emptyList()),
            "name" to DeviceGroups(listOf(base.copy(name = "Other")), emptyList()),
            "system version" to DeviceGroups(listOf(base.copy(systemVersion = "HarmonyOS 5")), emptyList()),
            "api version" to DeviceGroups(listOf(base.copy(apiVersion = "13")), emptyList()),
            "state" to DeviceGroups(listOf(base.copy(state = DeviceConnectionState.FAILED)), emptyList()),
            "connected flag" to DeviceGroups(listOf(base.copy(state = DeviceConnectionState.CONNECTED, connected = true)), emptyList()),
            "no rows" to DeviceGroups(emptyList(), emptyList()),
            "row moved to previous" to DeviceGroups(emptyList(), listOf(base)),
            "second row appended" to DeviceGroups(listOf(base, base.copy(address = "10.0.0.9:5555")), emptyList())
        )
        val signatures = variants.mapValues { deviceSignature(it.value) }
        val collisions = signatures.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertTrue("different snapshots collided: $collisions", collisions.isEmpty())
        assertEquals(variants.size, signatures.values.toSet().size)
    }

    @Test
    fun `device signature ignores nothing that the row renders`() {
        // $connected is derived from $state, so it is not an independent field; assert that a device
        // whose state agrees with connected keeps the same signature.
        val connectedFlagOnly = HdcDevice("10.0.0.1:5555", "Phone", connected = true)
        val stateOnly = HdcDevice("10.0.0.1:5555", "Phone").withState(DeviceConnectionState.CONNECTED)
        assertEquals(
            deviceSignature(DeviceGroups(listOf(connectedFlagOnly), emptyList())),
            deviceSignature(DeviceGroups(listOf(stateOnly), emptyList()))
        )
    }

    @Test
    fun `empty device set has a distinct stable signature`() {
        val empty = deviceSignature(DeviceGroups(emptyList(), emptyList()))
        assertEquals(empty, deviceSignature(DeviceGroups(emptyList(), emptyList())))
        assertEquals(empty, deviceSignature(DeviceGroups(emptyList(), emptyList(), emptyList())))
        // must not be blank: the panel seeds `lastSignature` with "" before its first render
        assertTrue("an empty device set must still produce a non-empty signature", empty.isNotEmpty())
        // any single device anywhere is a different identity
        assertNotEquals(empty, deviceSignature(DeviceGroups(listOf(HdcDevice("a:1")), emptyList())))
        assertNotEquals(empty, deviceSignature(DeviceGroups(emptyList(), listOf(HdcDevice("a:1")))))
        assertNotEquals(empty, deviceSignature(DeviceGroups(emptyList(), emptyList(), listOf(HdcDevice("a:1")))))
    }
}

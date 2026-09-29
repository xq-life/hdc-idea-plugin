package com.xq.hdcwifi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdcDeviceTest {

    @Test
    fun `groups connected and previous devices without duplicates`() {
        val saved = listOf(
            HdcDevice("192.168.1.10:5555", "Phone A", "HarmonyOS 5", "18"),
            HdcDevice("192.168.1.11:5555", "Phone B")
        )

        val groups = groupDevices(saved, listOf("192.168.1.10:5555", "USB123"))

        assertEquals(listOf("Phone A", "USB123"), groups.connected.map { it.name })
        assertEquals(listOf("Phone B"), groups.previous.map { it.name })
        assertTrue(groups.connected.all { it.connected })
        assertFalse(groups.previous.single().connected)
    }

    @Test
    fun `formats device details like adb wifi rows`() {
        val device = HdcDevice("192.168.1.10:5555", "Mate", "HarmonyOS 5", "18")
        assertEquals("HarmonyOS 5 (API 18) - 192.168.1.10:5555", device.details)
    }
}

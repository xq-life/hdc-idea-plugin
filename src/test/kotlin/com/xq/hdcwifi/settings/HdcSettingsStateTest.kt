package com.xq.hdcwifi.settings

import com.xq.hdcwifi.model.DeviceConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-CONTRACT §5 / UX-SPEC §8: the two auto-connect options are a safety-relevant default and must
 * be off, the custom CIDR is empty, and the refresh interval stays clamped to `0..3600`.
 */
class HdcSettingsStateTest {

    @Test
    fun `new state fields default to the safe values`() {
        val state = HdcSettings.State()
        assertFalse("autoConnectDiscovered must default to false", state.autoConnectDiscovered)
        assertFalse("autoConnectSaved must default to false", state.autoConnectSaved)
        assertEquals("", state.customScanCidr)
        // unchanged contract values
        assertEquals(0, state.autoRefreshSeconds)
        assertEquals(5555, state.defaultPort)
        assertEquals("", state.hdcPath)
    }

    @Test
    fun `a fresh settings instance never auto connects`() {
        val settings = HdcSettings()
        assertFalse(settings.autoConnectDiscovered)
        assertFalse(settings.autoConnectSaved)
        assertEquals("", settings.customScanCidr)
        assertEquals(0, settings.autoRefreshSeconds)
        assertEquals(HdcSettings.DEFAULT_PORT, settings.defaultPort)
        assertTrue(settings.savedDevices().isEmpty())
    }

    @Test
    fun `the custom cidr is trimmed and the refresh interval is clamped`() {
        val settings = HdcSettings()

        settings.customScanCidr = "  10.0.0.0/24  "
        assertEquals("10.0.0.0/24", settings.customScanCidr)
        settings.customScanCidr = "   "
        assertEquals("", settings.customScanCidr)

        settings.autoRefreshSeconds = -5
        assertEquals(0, settings.autoRefreshSeconds)
        settings.autoRefreshSeconds = 99_999
        assertEquals(3600, settings.autoRefreshSeconds)
        settings.autoRefreshSeconds = 30
        assertEquals(30, settings.autoRefreshSeconds)

        settings.defaultPort = 0
        assertEquals(1, settings.defaultPort)
        settings.defaultPort = 70_000
        assertEquals(65535, settings.defaultPort)
    }

    @Test
    fun `auto connect flags round trip`() {
        val settings = HdcSettings()
        settings.autoConnectDiscovered = true
        settings.autoConnectSaved = true
        assertTrue(settings.autoConnectDiscovered)
        assertTrue(settings.autoConnectSaved)
        assertTrue(settings.getState().autoConnectDiscovered)
        assertTrue(settings.getState().autoConnectSaved)
    }

    @Test
    fun `remembering a device is idempotent and rejects blanks`() {
        val settings = HdcSettings()
        settings.rememberDevice("192.168.1.10:5555")
        settings.rememberDevice("192.168.1.10:5555")
        settings.rememberDevice("")
        settings.rememberDevice("   ")

        assertEquals(listOf("192.168.1.10:5555"), settings.savedDevices().map { it.address })
        assertEquals(DeviceConnectionState.DISCONNECTED, settings.savedDevices().single().state)
    }

    @Test
    fun `device metadata ignores dash placeholders and forget drops the recent host`() {
        val settings = HdcSettings()
        settings.rememberDevice("192.168.1.10:5555")
        settings.rememberHost("192.168.1.10")

        settings.updateDevice("192.168.1.10:5555", "Pixel Pad", "HarmonyOS 5.0.0", "12")
        var device = settings.savedDevices().single()
        assertEquals("Pixel Pad", device.name)
        assertEquals("HarmonyOS 5.0.0", device.systemVersion)
        assertEquals("12", device.apiVersion)

        // a failed `param get` must not overwrite good data with its placeholder
        settings.updateDevice("192.168.1.10:5555", "-", "-", "-")
        device = settings.savedDevices().single()
        assertEquals("Pixel Pad", device.name)
        assertEquals("HarmonyOS 5.0.0", device.systemVersion)
        assertEquals("12", device.apiVersion)

        settings.removeDevice("192.168.1.10:5555")
        assertTrue(settings.savedDevices().isEmpty())
        assertTrue(settings.recentHosts().isEmpty())
    }
}

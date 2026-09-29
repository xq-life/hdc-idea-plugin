package com.xq.hdcwifi.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class ConnectDeviceDialogTest {

    @Test
    fun `splits inline port from host`() {
        assertEquals("192.168.1.20" to 8710, parseHdcAddress("192.168.1.20:8710", 5555))
    }

    @Test
    fun `keeps fallback port when host has no inline port`() {
        assertEquals("device.local" to 5555, parseHdcAddress("device.local", 5555))
    }

    @Test
    fun `leaves invalid inline port for dialog validation`() {
        assertEquals("device.local:bad" to 5555, parseHdcAddress("device.local:bad", 5555))
    }

    @Test
    fun `commits typed port before connect`() {
        val spinner = JSpinner(SpinnerNumberModel(5555, 1, 65535, 1))
        spinner.editor = JSpinner.NumberEditor(spinner, "0")
        (spinner.editor as JSpinner.NumberEditor).textField.text = "38343"

        assertEquals(38343, readCommittedPort(spinner))
    }
}

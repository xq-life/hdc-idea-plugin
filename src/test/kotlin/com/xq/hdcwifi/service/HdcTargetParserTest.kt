package com.xq.hdcwifi.service

import org.junit.Assert.assertEquals
import org.junit.Test

class HdcTargetParserTest {

    @Test
    fun `parses usb serial and wifi address`() {
        val output = "FMR0223C21202498\n192.168.1.20:5555\n"
        assertEquals(listOf("FMR0223C21202498", "192.168.1.20:5555"), HdcTargetParser.parse(output))
    }

    @Test
    fun `returns empty list when no device is attached`() {
        assertEquals(emptyList<String>(), HdcTargetParser.parse("[Empty]\n"))
    }

    @Test
    fun `ignores status and failure lines`() {
        val output = "[Fail]Connect failed\n[Info]Device is ready\nABC123\n"
        assertEquals(listOf("ABC123"), HdcTargetParser.parse(output))
    }

    @Test
    fun `drops trailing status column after a serial`() {
        val output = "FMR0223C21202498\t\tUnknown\n10.0.0.5:5555\t\tUnknown\n"
        assertEquals(listOf("FMR0223C21202498", "10.0.0.5:5555"), HdcTargetParser.parse(output))
    }

    @Test
    fun `handles blank lines carriage returns and duplicates`() {
        val output = "\r\n  ABC123  \r\n\r\nABC123\r\n   \r\n"
        assertEquals(listOf("ABC123"), HdcTargetParser.parse(output))
    }

    @Test
    fun `handles empty output`() {
        assertEquals(emptyList<String>(), HdcTargetParser.parse(""))
    }
}

class HdcConnectOutputTest {

    @Test
    fun `accepts successful connect`() {
        org.junit.Assert.assertTrue(HdcService.connectAccepted(0, "Connect ok!"))
    }

    @Test
    fun `accepts already connected device`() {
        org.junit.Assert.assertTrue(HdcService.connectAccepted(0, "Already connected"))
        org.junit.Assert.assertTrue(HdcService.connectAccepted(0, "[Info]Target is connected"))
    }

    @Test
    fun `recovers a stale repeated connection`() {
        org.junit.Assert.assertTrue(
            HdcService.connectionNeedsRecovery(0, "[Info]Target is connected, repeat operation", online = false)
        )
        org.junit.Assert.assertFalse(
            HdcService.connectionNeedsRecovery(0, "[Info]Target is connected, repeat operation", online = true)
        )
    }

    @Test
    fun `recovers a timed out connection but not an explicit failure`() {
        org.junit.Assert.assertTrue(
            HdcService.connectionNeedsRecovery(-1, "[hdc command timed out after 20s]", online = false)
        )
        org.junit.Assert.assertFalse(
            HdcService.connectionNeedsRecovery(0, "[Fail]Connect failed", online = false)
        )
    }

    @Test
    fun `rejects failure output`() {
        org.junit.Assert.assertFalse(HdcService.connectAccepted(0, "[Fail]Connect failed"))
        org.junit.Assert.assertFalse(HdcService.connectAccepted(1, "Connect ok!"))
        org.junit.Assert.assertFalse(HdcService.connectAccepted(0, "Target is not connected"))
        org.junit.Assert.assertFalse(HdcService.connectAccepted(0, ""))
    }

    @Test
    fun `recognizes hdc failures that still exit zero`() {
        org.junit.Assert.assertFalse(HdcResult(0, "[Fail]No target available").ok)
        org.junit.Assert.assertFalse(HdcResult(0, "Unknown operation command...").ok)
        org.junit.Assert.assertTrue(HdcResult(0, "Connect ok!").ok)
    }

    @Test
    fun `disconnect rejects semantic failure`() {
        org.junit.Assert.assertFalse(HdcService.disconnectAccepted(0, "[Fail]No target available"))
        org.junit.Assert.assertTrue(HdcService.disconnectAccepted(0, "Remove forward ruler success"))
    }

    @Test
    fun `recognizes file and target failures`() {
        org.junit.Assert.assertTrue(outputIndicatesFailure("[Fail]Not match target founded, check connect-key please"))
        org.junit.Assert.assertTrue(outputIndicatesFailure("[Fail]No target available"))
        org.junit.Assert.assertFalse(outputIndicatesFailure("FileTransfer finish, Size:100"))
    }
}

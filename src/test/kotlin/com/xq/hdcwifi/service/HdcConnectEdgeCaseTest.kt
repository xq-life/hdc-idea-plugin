package com.xq.hdcwifi.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary cases of the companion pure functions that decide whether an `hdc tconn` result counts as
 * a successful connect/disconnect, and when a stale link must be recovered. Complements (does not
 * repeat) [HdcConnectOutputTest].
 */
class HdcConnectEdgeCaseTest {

    @Test
    fun `success wording is matched case insensitively`() {
        assertTrue(HdcService.connectAccepted(0, "CONNECT OK!"))
        assertTrue(HdcService.connectAccepted(0, "Already Connected"))
        assertTrue(HdcService.connectAccepted(0, "[INFO]TARGET IS CONNECTED"))
    }

    @Test
    fun `a failure keyword always wins over a success keyword`() {
        assertFalse(HdcService.connectAccepted(0, "Connect ok! but error while registering"))
        assertFalse(HdcService.connectAccepted(0, "connected\n[Fail]something went wrong"))
        assertFalse(HdcService.connectAccepted(0, "Connected? no: not connected"))
    }

    @Test
    fun `a non zero exit code is always rejected`() {
        intArrayOf(-1, 1, 127, 255).forEach { code ->
            assertFalse("exit=$code must not be accepted", HdcService.connectAccepted(code, "Connect ok!"))
            assertFalse("exit=$code must not be accepted", HdcService.disconnectAccepted(code, "Remove forward ruler success"))
        }
    }

    @Test
    fun `an empty output is not a successful connect but is a successful silent disconnect`() {
        // connect must prove itself; a silent tconn -remove is how a successful detach looks
        assertFalse(HdcService.connectAccepted(0, ""))
        assertFalse(HdcService.connectAccepted(0, "   \n  "))
        assertTrue(HdcService.disconnectAccepted(0, ""))
    }

    @Test
    fun `recovery only triggers for the runner timeout text`() {
        assertTrue(HdcService.connectionNeedsRecovery(-1, "[hdc command timed out after 20s]", online = false))
        assertTrue(HdcService.connectionNeedsRecovery(-1, "Failed to connect\n[hdc command timed out after 20s]", online = false))
        // an unrelated timeout wording must not trigger a server restart
        assertFalse(HdcService.connectionNeedsRecovery(-1, "Socket timeout while connecting", online = false))
        assertFalse(HdcService.connectionNeedsRecovery(-1, "", online = false))
    }

    @Test
    fun `an online target never needs recovery`() {
        listOf(
            Triple(0, "[Fail]Connect failed", "explicit failure"),
            Triple(0, "Already connected, repeat operation", "stale link"),
            Triple(-1, "[hdc command timed out after 20s]", "timeout")
        ).forEach { (code, output, label) ->
            assertFalse("$label must not be recovered while the target is online", HdcService.connectionNeedsRecovery(code, output, online = true))
        }
    }

    @Test
    fun `recovery needs the stale link wording in addition to an accepted connect`() {
        assertTrue(HdcService.connectionNeedsRecovery(0, "Already connected", online = false))
        assertTrue(HdcService.connectionNeedsRecovery(0, "[Info]Target is connected, repeat operation", online = false))
        // a plain success that is simply not online yet must not restart the server
        assertFalse(HdcService.connectionNeedsRecovery(0, "Connect ok!", online = false))
        // a hard failure must not be retried through recovery either
        assertFalse(HdcService.connectionNeedsRecovery(0, "[Fail]Connect failed", online = false))
    }

    @Test
    fun `a present but semantically failing line rejects the disconnect`() {
        assertFalse(HdcService.disconnectAccepted(0, "[Fail]No target available"))
        assertFalse(HdcService.disconnectAccepted(0, "Unknown operation command"))
        assertFalse(HdcService.disconnectAccepted(0, "not match target"))
    }
}

package com.xq.hdcwifi

import com.xq.hdcwifi.model.DeviceConnectionState
import com.xq.hdcwifi.model.DeviceGroups
import com.xq.hdcwifi.model.HdcDevice
import com.xq.hdcwifi.model.deviceSignature
import com.xq.hdcwifi.model.withState
import com.xq.hdcwifi.service.HdcScanResult
import com.xq.hdcwifi.service.HdcService
import com.xq.hdcwifi.service.HdcServiceReflection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Regression guards for the defects found during independent verification and fixed afterwards
 * (see docs/ux/ACCEPTANCE.md §3). Round 1 pinned the *broken* behaviour here
 * (`KnownDefectsCharacterizationTest`); every entry below asserts the **contract** form, i.e. the
 * defect must not come back.
 *
 * Round-1 defects → contract form:
 * | D1  | different device sets must have different signatures                        |
 * | D1a | group membership is part of the signature                                   |
 * | D2  | a saved address is probed on its own port                                   |
 * | D3  | scan feedback is a transient toolbar notice                                 |
 * | D4  | becoming visible again refreshes                                            |
 * | D5  | the scan button reflects hdc availability                                   |
 * | D12 | `没有之前连接过的设备。`（Chinese copy since v1.8）                           |
 *
 * Round-2 findings → contract form (N1–N7, A9.4):
 * | N1  | only a successful scan may claim the network was empty                      |
 * | N2  | a running scan stays cancellable, and the tooltip says so                   |
 * | N3  | the accessible name tracks the tooltip                                      |
 * | N4  | a manual connect is promoted, never silently dropped                        |
 * | N6  | interface enumeration failures are swallowed                                |
 * | N7  | a null and an empty failure reason are different identities                 |
 * | A9.4| the section header answers Space/Enter/Left/Right                           |
 *
 * Panel behaviour is asserted on the production source: `HdcMainPanel` needs the IntelliJ
 * application and a `Project`, neither of which exists in this JVM (see ACCEPTANCE §0).
 */
class ContractRegressionTest {

    private val service = HdcService()

    @After
    fun tearDown() {
        service.dispose()
    }

    private fun entry(device: HdcDevice): String =
        "${device.address}:${device.name}:${device.details}:${device.state}:${device.failureReason}"

    // ---- D1: signature collisions -----------------------------------------------------------------

    @Test
    fun `different device sets can no longer collide in the signature`() {
        val phoneB = HdcDevice("10.0.0.6:5555", "PhoneB")
        val phoneA = HdcDevice("10.0.0.5:5555", "PhoneA").copy(
            state = DeviceConnectionState.CONNECTED,
            connected = true,
            failureReason = "boom"
        )
        // round-1 counterexample: the failure reason was crafted to swallow a whole second entry
        val collapsed = phoneA.copy(failureReason = "boom|" + entry(phoneB))

        val twoDevices = DeviceGroups(connected = emptyList(), previous = listOf(phoneA), available = listOf(phoneB))
        val oneDevice = DeviceGroups(connected = emptyList(), previous = listOf(collapsed), available = emptyList())

        assertNotEquals(twoDevices, oneDevice)
        assertNotEquals(deviceSignature(twoDevices), deviceSignature(oneDevice))
    }

    @Test
    fun `a device moving between groups changes the signature`() {
        // round-1 counterexample: previous=[Aaa, Zzz] and previous=[Aaa] + available=[Zzz] used to
        // produce the same concatenation, so Forget() looked like it did nothing.
        val savedTail = HdcDevice("10.0.0.9:5555", "Aaa")
        val forgotten = HdcDevice("10.0.0.1:5555", "Zzz")

        val before = DeviceGroups(connected = emptyList(), previous = listOf(savedTail, forgotten), available = emptyList())
        val after = DeviceGroups(connected = emptyList(), previous = listOf(savedTail), available = listOf(forgotten))

        assertNotEquals(before, after)
        assertNotEquals(deviceSignature(before), deviceSignature(after))
    }

    @Test
    fun `a null and an empty failure reason are different identities`() {
        // N7: the two render differently (no tooltip vs "Connection failed: Unknown error."), so they
        // must not collapse into one signature. Not reachable from the panel today, but the
        // signature claims to be an identity of what the row renders.
        fun failed(reason: String?) = DeviceGroups(
            emptyList(),
            listOf(HdcDevice("a:1", "P").withState(DeviceConnectionState.FAILED, reason)),
            emptyList()
        )

        val absent = deviceSignature(failed(null))
        val empty = deviceSignature(failed(""))
        val real = deviceSignature(failed("boom"))
        assertNotEquals(absent, empty)
        assertNotEquals(empty, real)
        assertNotEquals(absent, real)

        // the "absent" marker itself must not be reachable as a real reason
        assertNotEquals(absent, deviceSignature(failed("\u0000none\u0000")))
        // and the same input stays stable
        assertEquals(absent, deviceSignature(failed(null)))
    }

    // ---- D2: saved address ports ------------------------------------------------------------------

    @Test
    fun `a saved address keeps its own port and only different ports are never merged`() {
        // 198.51.100.0/24 is RFC 5737 documentation space: never a local interface, so these
        // assertions cannot be perturbed by the host's own network ranges.
        val targets = HdcServiceReflection.candidateTargets(
            service,
            listOf("198.51.100.10:6000", "198.51.100.20:8710"),
            5555,
            ""
        )
        assertTrue("saved port must be probed, not the default", targets.contains("198.51.100.10" to 6000))
        assertTrue(targets.contains("198.51.100.20" to 8710))
        assertFalse("the default port must not replace the saved one", targets.contains("198.51.100.10" to 5555))

        // two saved addresses that differ only by port are two distinct candidates
        val sameHost = HdcServiceReflection.candidateTargets(
            service,
            listOf("198.51.100.10:6000", "198.51.100.10:6001"),
            5555,
            ""
        )
        assertEquals(
            listOf("198.51.100.10" to 6000, "198.51.100.10" to 6001),
            sameHost.filter { it.first == "198.51.100.10" }
        )

        // the same (host, port) twice is still one candidate
        val duplicated = HdcServiceReflection.candidateTargets(
            service,
            listOf("198.51.100.10:6000", "198.51.100.10:6000"),
            5555,
            ""
        )
        assertEquals(1, duplicated.count { it == "198.51.100.10" to 6000 })

        // an unusable saved port falls back to the default port
        val fallback = HdcServiceReflection.candidateTargets(
            service,
            listOf("198.51.100.10:not-a-port", "198.51.100.20:0"),
            5555,
            ""
        )
        assertTrue(fallback.any { it.first == "198.51.100.10" && it.second == 5555 })
        assertTrue(fallback.any { it.first == "198.51.100.20" && it.second == 5555 })

        // ranges derived from a CIDR use the default port
        val cidr = HdcServiceReflection.candidateTargets(service, emptyList(), 5555, "10.99.0.0/30")
        assertTrue(cidr.contains("10.99.0.1" to 5555))
        assertTrue(cidr.contains("10.99.0.2" to 5555))

        // v1.9 (2.2): the saved ports are folded into the port set that every derived host is probed
        // on, so a device that moved to a new address on a remembered port is still reachable.
        val derived = HdcServiceReflection.candidateSet(service, listOf("198.51.100.10:6000"), 5555, "10.99.0.0/30")
        assertEquals(listOf(5555, 6000), derived.ports)
        assertTrue("the range host must also be probed on the saved port", derived.targets.contains("10.99.0.1" to 6000))
        assertTrue("and still on the default port", derived.targets.contains("10.99.0.1" to 5555))
    }

    @Test
    fun `the public scan result carries the port it actually probed`() {
        // scanDevices reports "$host:$port" for whatever candidate it reached; the panel then parses
        // that port back for the manual/auto connect path, so the two must agree.
        val panel = MainSources.panel
        assertTrue(panel.contains("val port = address.substringAfterLast(':').toIntOrNull() ?: settings.defaultPort"))
        assertTrue(MainSources.service.contains("found.add(\"\$host:\$candidatePort\")"))
    }

    // ---- D12 / D3 / N1: copy, transient feedback and the empty-state claim -------------------------

    @Test
    fun `previously connected empty state has the contract full stop`() {
        // v1.8: the copy is Chinese now, so the contract full stop is `。` rather than `.`.
        val panel = MainSources.panel
        assertTrue(panel.contains("\"没有之前连接过的设备。\""))
        assertFalse("the sentence must not lose its full stop", panel.contains("\"没有之前连接过的设备\""))
        assertFalse("an ASCII full stop is not the Chinese contract wording", panel.contains("\"没有之前连接过的设备.\""))
        assertFalse("the English copy must not come back", panel.contains("No previously connected devices"))
    }

    @Test
    fun `scan feedback is transient and never claims nothing was found`() {
        val panel = MainSources.panel
        // the Available group's empty text depends only on a completed scan, never on the notice
        assertTrue(panel.contains("if (scanCompleted) emptyScanText() else \"点击 Scan for devices 扫描网络中的设备。\""))
        assertFalse("the transient notice must not replace the empty state", panel.contains("scanStatus ?:"))

        // notices self-clear: 4s for results, 8s for errors (lifecycle asserted in
        // PanelInteractionContractTest, which owns the setScanStatus wiring)
        assertTrue(panel.contains("private const val SCAN_NOTICE_MS = 4000"))
        assertTrue(panel.contains("private const val SCAN_ERROR_MS = 8000"))
        assertTrue(panel.contains("setScanStatus(\"扫描已取消。\", autoClearMs = SCAN_NOTICE_MS)"))
        assertTrue("a failed scan reports with the longer error timeout", panel.contains("setScanStatus(message, autoClearMs = SCAN_ERROR_MS)"))

        // and the notice timer is released with the panel
        assertTrue(MainSources.bodyOf(panel, "override fun dispose()").contains("scanStatusTimer?.stop()"))
    }

    @Test
    fun `only a successful scan may claim the network was empty`() {
        // N1: `scanCompleted` drives the Available group's "No devices found" text, so it must not be
        // set when the scan failed (invalid CIDR, oversized range, ...).
        val panel = MainSources.panel
        assertEquals("scanCompleted may be written in exactly one place", 1, Regex("scanCompleted = true").findAll(panel).count())

        val start = MainSources.bodyOf(panel, "private fun startScan(")
        val success = start.indexOf("onSuccess = { scan ->")
        val failure = start.indexOf("onFailure = { error ->")
        val assignment = start.indexOf("scanCompleted = true")
        assertTrue("the success branch must exist", success >= 0)
        assertTrue("the failure branch must exist", failure > success)
        assertTrue("the assignment must sit inside onSuccess", assignment in success until failure)
        assertFalse("a failed scan must not touch it", start.substring(failure).contains("scanCompleted"))
    }

    @Test
    fun `a cancelled scan does not claim that no devices were found`() {
        // Round-2 additional ruling: cancel must keep the previous empty-state semantics.
        val cancel = MainSources.bodyOf(MainSources.panel, "private fun cancelScan(")
        assertFalse("cancel must not mark the scan as completed", cancel.contains("scanCompleted = true"))
        assertTrue(cancel.contains("setScanStatus(\"扫描已取消。\", autoClearMs = SCAN_NOTICE_MS)"))
    }

    // ---- D5 / N2 / N3: scan button state ----------------------------------------------------------

    @Test
    fun `the scan button reflects hdc availability`() {
        val setScanAvailable = MainSources.bodyOf(MainSources.panel, "private fun setScanAvailable(")
        assertTrue("availability is recorded, not applied inline", setScanAvailable.contains("hdcAvailable = available"))
        assertTrue(setScanAvailable.contains("refreshScanButton()"))

        val refresh = MainSources.bodyOf(MainSources.panel, "private fun refreshScanButton(")
        assertTrue(refresh.contains("\"hdc 不可用，请在设置中配置。\""))

        val listing = MainSources.bodyOf(MainSources.panel, "private fun refreshDevices(")
        assertTrue("a successful listing proves hdc works", listing.contains("setScanAvailable(true)"))
        assertTrue("a missing hdc binary must disable scanning", listing.contains("setScanAvailable(false)"))
        assertTrue(listing.contains("error is HdcToolNotFoundException"))
    }

    @Test
    fun `a running scan stays cancellable even when hdc disappears`() {
        // N2: the button used to be disabled whenever hdc was unavailable, which could strand a scan
        // that was already running (its own Cancel button became unclickable).
        val refresh = MainSources.bodyOf(MainSources.panel, "private fun refreshScanButton(")
        assertTrue(refresh.contains("val scanning = scanHandle != null"))
        assertTrue(
            "enabled = available OR scanning",
            refresh.contains("scanButton.isEnabled = hdcAvailable || scanning")
        )
        // the tooltip must describe the Cancel meaning first, so a mid-scan hdc loss is not mislabelled
        val branches = refresh.substringAfter("val tooltip = when {")
        assertTrue(branches.indexOf("\"Cancel scan\"") in 0 until branches.indexOf("\"hdc 不可用"))

        // every transition of the scan handle re-derives the button
        assertTrue("starting a scan", MainSources.bodyOf(MainSources.panel, "private fun startScan(").contains("refreshScanButton()"))
        assertTrue("finishing a scan", MainSources.bodyOf(MainSources.panel, "private fun startScan(").contains("scanHandle = null"))
        assertTrue("cancelling a scan", MainSources.bodyOf(MainSources.panel, "private fun cancelScan(").contains("refreshScanButton()"))
    }

    @Test
    fun `the scan button has a single derivation point`() {
        // N3: tooltip and accessible name are written together, so a screen reader cannot read a stale
        // state; both live only in refreshScanButton.
        val panel = MainSources.panel
        assertEquals(1, Regex("scanButton\\.isEnabled = ").findAll(panel).count())
        assertEquals(1, Regex("scanButton\\.toolTipText = ").findAll(panel).count())
        assertEquals(1, Regex("scanButton\\.accessibleContext\\.accessibleName = ").findAll(panel).count())

        val refresh = MainSources.bodyOf(panel, "private fun refreshScanButton(")
        assertTrue(refresh.contains("scanButton.toolTipText = tooltip"))
        assertTrue(refresh.contains("scanButton.accessibleContext.accessibleName = tooltip"))
    }

    // ---- D4: become visible again -----------------------------------------------------------------

    @Test
    fun `becoming visible again always triggers one refresh`() {
        val panel = MainSources.panel
        assertTrue(panel.contains("if (showing && !wasShowing)"))
        assertTrue(panel.contains("wasShowing = showing"))
        assertFalse("the old one-shot guard must be gone", panel.contains("showing && !hasBeenShown)"))
    }

    // ---- D11 / N6: tolerant local range enumeration -----------------------------------------------

    @Test
    fun `a malformed local interface range is skipped while a user CIDR is reported`() {
        val candidateTargets = MainSources.bodyOf(MainSources.service, "private fun candidateTargets(")
        assertTrue(candidateTargets.contains("runCatching { expandCidr(cidr) }.getOrNull()?.forEach"))
        assertTrue("the user's own CIDR must still fail loudly", candidateTargets.contains("expandCidr(customCidr.trim()).forEach"))

        val localCidrs = MainSources.bodyOf(MainSources.service, "private fun localNetworkCidrs(")
        assertTrue(localCidrs.contains("mapNotNull"))
        assertFalse("no empty-string pseudo CIDR may be produced", localCidrs.contains("else \"\""))
    }

    @Test
    fun `an unreadable interface list is reported instead of looking like an empty network`() {
        // R3 (round 4): the earlier fix wrapped enumeration errors in a getOrDefault(emptyList()),
        // which turned "cannot read this machine's interfaces" into "this machine has no networks" —
        // the scan then failed to find anything and the group claimed an empty network. The error is
        // now propagated as null -> INTERFACE_ERROR -> a failed scan.
        val service = MainSources.service
        assertTrue("the helper must be nullable so failure is expressible", service.contains("private fun localNetworkCidrs(): List<String>? = runCatching {"))
        val local = MainSources.bodyOf(service, "private fun localNetworkCidrs(")
        assertTrue("null on failure, not an empty list", local.contains(".getOrNull()"))
        assertFalse("the failure must not be flattened into an empty list", local.contains("getOrDefault(emptyList())"))

        val candidates = MainSources.bodyOf(service, "private fun candidateTargets(")
        assertTrue("the warning is derived from the null", candidates.contains("if (local == null) INTERFACE_ERROR else null"))
        // v1.9: CandidateSet grew the coverage fields, so the mirror in HdcServiceReflection must be
        // kept in step; each component is asserted separately so a silent drop cannot pass.
        assertTrue("and carried out of the helper", service.contains("private data class CandidateSet("))
        assertTrue("still carrying the warning", service.contains("val warning: String?,"))
        assertTrue("and now the ranges it covered", service.contains("val ranges: List<String> = emptyList(),"))
        assertTrue("and the ports it probed", service.contains("val ports: List<Int> = emptyList()"))
        assertTrue("which the scan result also carries out", service.contains("val probed: Int = 0"))

        // the constant is the SPEC §7 sentence and is reachable from the panel
        assertTrue(HdcService.INTERFACE_ERROR.startsWith("Unable to read local network interfaces."))
        assertTrue(MainSources.service.contains("internal const val INTERFACE_ERROR ="))

        // on a real machine the enumeration succeeds, so the happy path must not produce a warning
        val cidrs = HdcServiceReflection.localNetworkCidrs(service = this.service)
        assertNotNull("interface enumeration failed on this machine, so the happy path is untested here", cidrs)
        assertEquals(null, HdcServiceReflection.candidateSet(this.service, emptyList(), 5555, "").warning)
    }

    @Test
    fun `a scan with no candidates at all fails instead of succeeding with nothing`() {
        // SPEC §7: when every enumerable source is empty the user must be told, not shown an empty
        // Available group. scanDevices fails before probing anything.
        val scan = MainSources.bodyOf(MainSources.service, "fun scanDevices(")
        val empty = scan.indexOf("if (candidates.isEmpty()) {")
        val failure = scan.indexOf("Result.failure(IllegalStateException(message))", empty)
        assertTrue("the empty-candidate case must fail", empty >= 0)
        assertTrue("with the no-addresses message", failure > empty)
        assertTrue(
            "and the SPEC §7 sentence is part of it",
            scan.contains("\"No network addresses are available to scan. Add a saved device or set a custom network range.\"")
        )
        assertTrue("an unreadable interface list takes precedence over \"no addresses\"", scan.contains("?: \"No network addresses"))
    }

    @Test
    fun `a warning plus no results is a failure, a warning plus results is a success`() {
        val scan = MainSources.bodyOf(MainSources.service, "fun scanDevices(")
        val warning = scan.indexOf("val warning: String?")
        assertTrue(warning >= 0)
        // nothing found *because* the interfaces were unreadable: report the reason, never "no devices"
        assertTrue(
            "the empty-plus-warning case must fail",
            scan.contains("if (warning != null && found.isEmpty()) {")
        )
        assertTrue(
            "with the warning as the message",
            scan.contains("callback(Result.failure(IllegalStateException(warning)))")
        )
        // a partial scan that did find something succeeds, carrying the caveat
        assertTrue(
            "a partial scan stays a success",
            scan.contains("callback(Result.success(HdcScanResult(found.distinct().sorted(), warning, ranges, ports, candidates.size)))")
        )
        // v1.9: the coverage fields are read out of the candidate set and handed to the result, so the
        // console log describes the scan that actually ran rather than a re-derived guess.
        assertTrue("the ranges travel from the candidate set", scan.contains("ranges = resolved.ranges"))
        assertTrue("and so do the ports", scan.contains("ports = resolved.ports"))
    }

    @Test
    fun `a scan result can carry a warning and the panel writes it to the console`() {
        // The field used to be an unread "Scanned n/N" summary (a dead value); it is now the scan
        // warning and the panel is its only consumer.
        val result = HdcScanResult(listOf("1.2.3.4:6000"))
        assertEquals(listOf("1.2.3.4:6000"), result.addresses)
        assertEquals("no warning by default", null, result.description)
        assertEquals(HdcService.INTERFACE_ERROR, HdcScanResult(emptyList(), HdcService.INTERFACE_ERROR).description)
        assertTrue(MainSources.service.contains("data class HdcScanResult("))
        assertTrue("the warning stays optional", MainSources.service.contains("val description: String? = null,"))

        // v1.9 (2.3): the coverage fields exist with safe defaults and are filled in by `scanDevices`.
        assertEquals("a bare result covers nothing", emptyList<String>(), result.ranges)
        assertEquals("and probed nothing", emptyList<Int>(), result.ports)
        assertEquals("a bare result has no probe count", 0, result.probed)
        val covered = HdcScanResult(listOf("1.2.3.4:6000"), null, listOf("10.0.0.0/24"), listOf(5555, 38343), 508)
        assertEquals(listOf("10.0.0.0/24"), covered.ranges)
        assertEquals(listOf(5555, 38343), covered.ports)
        assertEquals(508, covered.probed)
        assertTrue(
            "the scan must backfill all three from the candidate set it actually used",
            MainSources.service.contains("HdcScanResult(found.distinct().sorted(), warning, ranges, ports, candidates.size)")
        )

        val panel = MainSources.panel
        assertTrue("the panel must actually read the warning", panel.contains("scan.description?.let { appendConsole(it) }"))
        // the old dead summary must not come back
        assertFalse("the removed summary field must stay removed", MainSources.service.contains("\"Scanned \$completed/\$total\""))
    }

    @Test
    fun `a completed scan says which sources it actually covered`() {
        // UX-SPEC §7 (lines 207-213) requires the "nothing found" sentence to be source-specific. The
        // defect (ACCEPTANCE §3.4 R5 / item A4.20) was that one generic sentence claimed local networks
        // even after a scan whose only non-default source was a custom range. The panel now derives the
        // sentence from the configured sources, so the four mixes get four distinct, factually correct
        // sentences — and the custom-range arm must never be the local-only one.
        //
        // v1.8 note: the copy is Chinese now. This test deliberately keeps counting the four arms on
        // the *actual* sentences instead of relaxing to "no English found": the count, the pairwise
        // distinctness and the per-arm source naming below are the anti-regression value.
        val body = MainSources.bodyOf(MainSources.panel, "private fun emptyScanText()")
        val arms = Regex("""(saved && custom|custom|saved|else) -> "([^"]*)"""")
            .findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("four source mixes must each have their own sentence", 4, arms.size)
        assertEquals(
            "without saved devices or a custom range only the local networks were scanned",
            "在本地网络中没有找到设备。", arms.getValue("else")
        )
        listOf("saved", "custom", "saved && custom").forEach { mix ->
            assertNotEquals("a $mix scan must not claim only local networks", arms.getValue("else"), arms.getValue(mix))
        }
        assertTrue("the custom range must be named", arms.getValue("custom").contains("自定义网段"))
        assertTrue("saved addresses must be named", arms.getValue("saved").contains("已保存地址"))
        val both = arms.getValue("saved && custom")
        assertTrue(
            "the combined arm must name local networks, saved addresses and the custom range",
            both.contains("本地网络") && both.contains("已保存地址") && both.contains("自定义网段")
        )
        assertEquals("the four sentences must be distinct", 4, arms.values.toSet().size)

        // Non-vacuous source-coverage count: every arm must state the local networks it always covers.
        // `在本地网络` occurs once per arm — four times, no more, no fewer — so a degenerate rewrite
        // that emits one generic sentence (or drops the source detail) cannot pass this test.
        assertEquals(
            "each of the four source mixes must state that local networks were covered",
            4, Regex("在本地网络").findAll(body).count()
        )
        arms.values.forEach { sentence ->
            assertTrue("every sentence must end with the Chinese full stop: $sentence", sentence.endsWith("。"))
        }
        assertFalse("no English source-coverage sentence may come back", body.contains("No devices found"))

        // the sentence is derived from the settings, and only consulted once a scan really completed
        assertTrue(body.contains("settings.savedDevices().isNotEmpty()"))
        assertTrue(body.contains("settings.customScanCidr.isNotBlank()"))
        assertTrue(
            "the empty state is still gated on a completed scan (N1)",
            MainSources.panel.contains("if (scanCompleted) emptyScanText() else")
        )
    }

    @Test
    fun `a rebuilt section header gets keyboard focus back`() {
        // A4.20's sibling finding: every header activation calls render(force = true), which empties
        // devicesPanel and rebuilds the headers, so the focused header was destroyed and the keyboard
        // user had to Tab back after each keypress. The panel now remembers the focused section and
        // re-focuses its rebuilt twin.
        val panel = MainSources.panel
        assertEquals(
            "the focus key literal must exist exactly once, as the shared constant",
            1, Regex("\"hdc\\.sectionKey\"").findAll(panel).count()
        )
        assertTrue(
            "each header must record which section it is",
            MainSources.bodyOf(panel, "private fun sectionHeader(").contains("putClientProperty(FOCUS_SECTION_KEY, title)")
        )

        val find = MainSources.bodyOf(panel, "private fun findSectionHeader(")
        assertTrue(
            "the lookup must match on the same key it was stored under",
            find.contains("getClientProperty(FOCUS_SECTION_KEY) == title")
        )
        assertTrue(
            "headers sit inside the group panels, so the lookup must recurse",
            find.contains("findSectionHeader(component, title)")
        )

        val render = MainSources.bodyOf(panel, "private fun render(")
        val earlyReturn = render.indexOf("if (!force && signature == lastSignature) return")
        val capture = render.indexOf("getCurrentKeyboardFocusManager().focusOwner")
        val removeAll = render.indexOf("devicesPanel.removeAll()")
        assertTrue("an unchanged signature must return before focus is touched", earlyReturn in 0 until capture)
        assertTrue("focus must be captured before the panel is emptied", capture in 0 until removeAll)
        assertTrue(
            "the capture must be null-safe and scoped to the devices panel",
            Regex("""focusOwner\)\s*\?\.takeIf \{ SwingUtilities\.isDescendingFrom\(it, devicesPanel\) \}""")
                .containsMatchIn(render)
        )
        assertFalse("focusOwner may be null, so it must never be dereferenced with !!", render.contains("focusOwner!!"))

        val deferred = render.substring(render.indexOf("SwingUtilities.invokeLater {"))
        assertTrue(
            "the rebuilt header must be asked for focus after the rebuild",
            deferred.contains("findSectionHeader(devicesPanel, focusedSection)?.requestFocusInWindow()")
        )
        assertTrue("the scroll position must still be restored in the same pass", deferred.contains("deviceScroll.verticalScrollBar.value = scroll"))
        assertFalse("the focus restore must not re-enter render", deferred.contains("render("))
        assertEquals("focus is requested once per rebuild", 1, Regex("requestFocusInWindow").findAll(render).count())
        assertEquals("and nowhere else in the panel", 1, Regex("requestFocusInWindow").findAll(panel).count())
    }

    @Test
    fun `local network ranges are well formed on this machine`() {
        // v1.9 (2.1): the range is no longer flattened to `/24` from the first three octets — the
        // prefix comes from the interface's own `networkPrefixLength`. The check therefore compares
        // the advertised prefixes against the prefixes the JDK reports for the same interfaces,
        // instead of assuming a width, so it holds on any machine and still fails the moment the
        // netmask is ignored.
        val cidrs = HdcServiceReflection.localNetworkCidrs(service)
        assertNotNull("interface enumeration failed on this machine", cidrs)
        val shape = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})/(\d{1,2})$""")
        cidrs!!.forEach { cidr ->
            val match = shape.matchEntire(cidr)
            assertNotNull("not a canonical a.b.c.d/prefix network: $cidr", match)
            val found = match ?: return@forEach
            val octets = (1..4).map { found.groupValues[it].toInt() }
            assertTrue("an octet is out of range: $cidr", octets.all { it in 0..255 })
            val prefix = found.groupValues[5].toInt()
            assertTrue("the prefix is out of range: $cidr", prefix in 0..32)
            // the network must be the *masked* address: the host bits have to be zero
            val value = octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
            val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            assertEquals("the host bits must be masked off: $cidr", 0L, value and mask.inv() and 0xFFFFFFFFL)
            assertFalse("loopback range leaked: $cidr", cidr.startsWith("127."))
            assertFalse("link-local range leaked: $cidr", cidr.startsWith("169.254."))
        }
        // an advertised range is worthless if the scan cannot expand it
        cidrs.forEach { cidr ->
            val expanded = runCatching { HdcServiceReflection.expandCidr(service, cidr) }
            assertTrue("$cidr cannot be expanded: ${expanded.exceptionOrNull()}", expanded.getOrNull()?.isNotEmpty() == true)
        }

        // the real netmasks, read straight from the JDK with the same usability filters
        val realPrefixes = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress }
            .map { it.networkPrefixLength.toInt() }
            .toSet()
        assertTrue(
            "this machine needs at least one usable IPv4 interface for the check to mean anything",
            realPrefixes.isNotEmpty()
        )
        val advertisedPrefixes = cidrs.map { it.substringAfterLast('/').toInt() }.toSet()
        assertEquals(
            "the advertised prefixes must be this machine's real netmasks, not a hardcoded /24",
            realPrefixes,
            advertisedPrefixes
        )
        // restated as the counterexample: an interface that is not a /24 must not be advertised as one
        realPrefixes.filter { it != 24 }.forEach { prefix ->
            assertTrue("a /$prefix interface must be advertised as /$prefix, not flattened to /24", advertisedPrefixes.contains(prefix))
        }

        // and the candidate set reports exactly those ranges as its coverage
        assertEquals(cidrs, HdcServiceReflection.candidateSet(service, emptyList(), 5555, "").ranges)
    }

    // ---- A9.4: header keyboard operability --------------------------------------------------------

    @Test
    fun `section headers are keyboard operable from space enter left and right`() {
        val header = MainSources.bodyOf(MainSources.panel, "private fun sectionHeader(")
        listOf("VK_SPACE", "VK_ENTER").forEach { key ->
            assertTrue(
                "missing toggle binding: $key",
                header.contains("header.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.$key, 0), TOGGLE_ACTION)")
            )
        }
        assertTrue(
            "missing expand binding",
            header.contains("header.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), EXPAND_ACTION)")
        )
        assertTrue(
            "missing collapse binding",
            header.contains("header.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), COLLAPSE_ACTION)")
        )
        assertTrue("every binding needs its action", header.contains("header.actionMap.put(TOGGLE_ACTION, toggleAction)"))
        assertTrue(header.contains("header.actionMap.put(EXPAND_ACTION, object : AbstractAction() {"))
        assertTrue(header.contains("header.actionMap.put(COLLAPSE_ACTION, object : AbstractAction() {"))
        assertTrue(header.contains("isFocusable = true"))
    }

    @Test
    fun `left only collapses and right only expands`() {
        // R2 (round 4): all four keys used to share one toggle, so Right on an expanded section
        // collapsed it. Each direction now guards on the state it owns, which makes the opposite
        // press a no-op instead of the opposite action (UX-SPEC §9 line 270).
        val panel = MainSources.panel
        val header = MainSources.bodyOf(panel, "private fun sectionHeader(")
        val expand = header.substringAfter("header.actionMap.put(EXPAND_ACTION,").substringBefore("header.actionMap.put(COLLAPSE_ACTION")
        val collapse = header.substringAfter("header.actionMap.put(COLLAPSE_ACTION,")
        assertTrue("Right must only expand a collapsed section", expand.contains("if (!expanded) toggle()"))
        assertFalse("Right must never collapse", expand.contains("if (expanded) toggle()"))
        assertTrue("Left must only collapse an expanded section", collapse.contains("if (expanded) toggle()"))
        assertFalse("Left must never expand", collapse.contains("if (!expanded) toggle()"))

        // three distinct actions, none of them shared across directions
        val actions = listOf("hdc.toggleSection", "hdc.expandSection", "hdc.collapseSection")
        assertEquals(3, actions.distinct().size)
        assertEquals("console action names", 3, actions.count { panel.contains("= \"$it\"") })
        // the label/tooltip still describes the toggle, not a direction
        assertTrue(header.contains("toolTipText = if (expanded) \"折叠 \$title\" else \"展开 \$title\""))
    }

    @Test
    fun `the section header announces itself as a push button`() {
        // R1 (round 4): UX-SPEC §9 line 270 wants a focusable button OR a component whose accessible
        // role is PUSH_BUTTON. `accessibleRole` has no public setter, so the header overrides its
        // accessible context instead.
        val panel = MainSources.panel
        val header = MainSources.bodyOf(panel, "private fun sectionHeader(")
        assertTrue("the header must use the role-carrying panel", header.contains("val header = SectionHeaderPanel().apply {"))
        assertTrue("and still be focusable", header.contains("isFocusable = true"))
        assertTrue(header.contains("accessibleContext.accessibleName = text"))

        val custom = MainSources.bodyOf(panel, "private class SectionHeaderPanel")
        assertTrue("it must be a JPanel subclass", custom.contains(": JPanel(BorderLayout())"))
        assertTrue("overriding the accessible context", custom.contains("override fun getAccessibleContext(): AccessibleContext"))
        assertTrue("and reporting a push button", custom.contains("override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PUSH_BUTTON"))
        assertTrue("with the panel role's behaviour intact", custom.contains("AccessibleJPanel()"))
        // the override must actually assign the context, otherwise the role is never seen
        assertTrue(custom.contains("accessibleContext = context"))
        assertTrue(custom.contains("return context"))
    }

    // ---- N4: manual connect promotion -------------------------------------------------------------

    @Test
    fun `a manual connect promotes an address that is already queued`() {
        // N4: the manual branch used to `return` when the address was already queued, so a click was
        // silently dropped and the target kept its automatic position.
        val request = MainSources.bodyOf(MainSources.panel, "private fun requestConnection(")
        val manualStart = request.indexOf("if (origin == ConnectionOrigin.MANUAL) {")
        val elseStart = request.indexOf("} else {", manualStart)
        assertTrue("the busy branch must split manual from automatic", manualStart >= 0 && elseStart > manualStart)

        val manual = request.substring(manualStart, elseStart)
        assertTrue(manual.contains("autoQueue.removeAll { it.first == address }"))
        assertTrue(manual.contains("autoQueue.addFirst(address to origin)"))
        assertTrue("drop older copies before jumping the queue", manual.indexOf("removeAll") < manual.indexOf("addFirst"))

        // and the promotion must not weaken the suppression contract for automatic requests
        assertTrue(request.contains("if (origin != ConnectionOrigin.MANUAL && address in autoSuppressed) return"))
    }
}


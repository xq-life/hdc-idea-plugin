package com.xq.hdcwifi.toolwindow

import com.xq.hdcwifi.MainSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sources-level contracts for the connection queue, the deferred auto-connect, the scan-button state
 * machine and the scan-status plumbing (round-2 fixes D3/D4/D5/D8/D9/D10/D14, round-3 fixes
 * N1–N6).
 *
 * `HdcMainPanel` cannot be instantiated in this JVM (no IntelliJ application, no `Project`) and
 * `HdcSettingsConfigurable` needs `HdcSettings.instance`, so the wiring is asserted on the production
 * source. Each test states the behaviour it pins down; the runtime half is covered by the manual
 * checklist in docs/ux/ACCEPTANCE.md §5.
 */
class PanelInteractionContractTest {

    private val panel = MainSources.panel

    // ---- D8 + N4: the queue is duplicate-free from both entry points ----------------------------

    @Test
    fun `neither queue branch can produce a duplicate entry`() {
        val request = MainSources.bodyOf(panel, "private fun requestConnection(")
        val manualStart = request.indexOf("if (origin == ConnectionOrigin.MANUAL) {")
        val elseStart = request.indexOf("} else {", manualStart)
        val tailStart = request.indexOf("\n            return", elseStart)
        assertTrue("the busy branch must split manual from automatic", manualStart >= 0 && elseStart > manualStart)
        assertTrue("the busy branch must end with a return", tailStart > elseStart)

        val manual = request.substring(manualStart, elseStart)
        assertTrue("manual drops every older copy first", manual.contains("autoQueue.removeAll { it.first == address }"))
        assertTrue("and re-adds exactly one at the front", manual.contains("autoQueue.addFirst(address to origin)"))
        assertTrue(manual.indexOf("removeAll") < manual.indexOf("addFirst"))

        val auto = request.substring(elseStart, tailStart)
        assertTrue("automatic refuses a duplicate", auto.contains("if (autoQueue.any { it.first == address }) return"))
        assertTrue(auto.contains("autoQueue.addLast(address to origin)"))
        assertTrue(auto.indexOf("autoQueue.any") < auto.indexOf("addLast"))

        // both ends must exist for the priority scheme to be cheap and explicit
        assertTrue(panel.contains("private val autoQueue = ArrayDeque<Pair<String, ConnectionOrigin>>()"))
    }

    // ---- round-2 fix D9: a manual disconnect wins over the automatic queue -----------------------

    @Test
    fun `a manual disconnect is recorded before the disconnect is attempted`() {
        val disconnect = MainSources.bodyOf(panel, "private fun disconnect(")
        val suppress = disconnect.indexOf("autoSuppressed.add(device.address)")
        val call = disconnect.indexOf("service.disconnect(")
        assertTrue(suppress >= 0)
        assertTrue(call >= 0)
        assertTrue("the target must be suppressed before the async round trip", suppress < call)
    }

    @Test
    fun `an automatic target is refused while it is suppressed`() {
        val request = MainSources.bodyOf(panel, "private fun requestConnection(")
        assertTrue(request.contains("if (origin != ConnectionOrigin.MANUAL && address in autoSuppressed) return"))
        // a manual request, by contrast, clears the suppression
        assertEquals(1, Regex("autoSuppressed\\.remove\\(address\\)").findAll(request).count())
    }

    @Test
    fun `the automatic queue skips suppressed entries and keeps going`() {
        val run = MainSources.bodyOf(panel, "private fun runNextAuto(")
        assertTrue("must drain, not just inspect the head", run.contains("while (autoQueue.isNotEmpty())"))
        assertTrue(run.contains("if (origin != ConnectionOrigin.MANUAL && address in autoSuppressed) continue"))

        val skip = run.indexOf("in autoSuppressed) continue")
        val start = run.indexOf("requestConnection(address, origin)")
        assertTrue("the suppressed check must run before starting the attempt", skip in 0 until start)
        assertTrue("a queued target must actually be attempted", start >= 0)
    }

    // ---- round-2 fix D10: saved auto-connect waits for the first listing -------------------------

    @Test
    fun `saved auto connect waits for the first successful target listing`() {
        assertTrue(panel.contains("savedAutoConnectPending = settings.autoConnectSaved"))

        val init = MainSources.bodyOf(panel, "init {")
        assertFalse("construction must not enqueue anything", init.contains("enqueueAuto("))

        val refresh = MainSources.bodyOf(panel, "private fun refreshDevices(")
        assertTrue(refresh.contains("if (savedAutoConnectPending) {"))
        assertTrue(refresh.contains("enqueueAuto(settings.savedDevices().map { it.address }, ConnectionOrigin.AUTO_SAVED)"))
        assertTrue(
            "the pending flag is consumed on success, not on failure",
            refresh.indexOf("savedAutoConnectPending") < refresh.indexOf("onFailure = { error ->")
        )
        assertTrue(refresh.contains("savedAutoConnectPending = false"))
    }

    @Test
    fun `automatic enqueue skips what is already connected, attempted or suppressed`() {
        val enqueue = MainSources.bodyOf(panel, "private fun enqueueAuto(")
        assertTrue(enqueue.contains("it !in autoAttempted && it !in autoSuppressed && it !in connected"))
        assertTrue("attempts are recorded before queueing", enqueue.indexOf("autoAttempted.add(it)") < enqueue.indexOf("autoQueue.addLast(it to origin)"))
    }

    // ---- round-2 fix D3: the scan notice has its own home and lifetime ---------------------------

    @Test
    fun `the scan notice lives in the toolbar under exactly one timer`() {
        // animation, auto-refresh, scan notice -- a fourth timer would need its own dispose handling
        assertEquals(3, Regex("javax\\.swing\\.Timer\\(").findAll(panel).count())

        val setScanStatus = MainSources.bodyOf(panel, "private fun setScanStatus(")
        assertTrue("a new notice cancels the previous timer", setScanStatus.contains("scanStatusTimer?.stop()"))
        assertTrue("the stale timer reference must be dropped", setScanStatus.contains("scanStatusTimer = null"))
        assertTrue(setScanStatus.contains("scanStatusTimer = javax.swing.Timer(autoClearMs)"))
        assertTrue("notices must not repeat", setScanStatus.contains("isRepeats = false"))
        assertTrue(setScanStatus.contains("start()"))
    }

    @Test
    fun `the notice is rendered from the panel state so it cannot go stale`() {
        val panel = MainSources.panel
        // v1.9 (2.5): the label write moved into applyScanStatus() so a progress tick can refresh the
        // notice without rebuilding the device list. The state -> label direction must stay the only
        // one, or the notice could be written from somewhere that never reads the state back.
        val apply = MainSources.bodyOf(panel, "private fun applyScanStatus(")
        assertTrue(apply.contains("scanStatusLabel.text = scanStatus.orEmpty()"))
        assertTrue(apply.contains("scanStatusLabel.isVisible = scanStatus != null"))
        assertEquals("the label text may only be written from the state", 1, Regex("scanStatusLabel\\.text = ").findAll(panel).count())
        assertEquals("and so may its visibility", 1, Regex("scanStatusLabel\\.isVisible = ").findAll(panel).count())

        val render = MainSources.bodyOf(panel, "private fun render(")
        assertTrue("render must apply the state rather than copy-paste it", render.contains("applyScanStatus()"))
        assertFalse("render must not write the label directly any more", render.contains("scanStatusLabel.text"))

        val set = MainSources.bodyOf(panel, "private fun setScanStatus(")
        assertEquals("both the set path and the auto-clear path apply the state", 2, Regex("applyScanStatus\\(\\)").findAll(set).count())
        assertTrue("the auto-clear timer must go through the same door", set.contains("scanStatus = null"))

        // the signature must include the notice, otherwise setting one would not repaint the toolbar
        assertTrue(render.contains("\":\$scanStatus:\$availableExpanded"))
    }

    @Test
    fun `scan progress updates the notice without rebuilding the device list`() {
        // v1.9 (2.5): the progress callback used to call render(), so a 3064-probe scan rebuilt the
        // whole device list once per progress event. That is the regression this pins down.
        val start = MainSources.bodyOf(MainSources.panel, "private fun startScan(")
        val progress = start.substringAfter("progress = { progress ->").substringBefore("callback = { result ->")
        assertTrue("the progress callback must still update the notice", progress.contains("setScanStatus("))
        assertTrue("and must be generation-guarded", progress.contains("generation == scanGeneration"))
        assertFalse("progress must never trigger a full rebuild", progress.contains("render("))

        // the terminal branches, by contrast, must still repaint
        assertTrue("a finished scan repaints", start.substringAfter("onSuccess = { scan ->").contains("render()"))
        assertTrue("a failed scan repaints", start.substringAfter("onFailure = { error ->").contains("render()"))
        assertTrue("starting the scan repaints once", start.substringBefore("scanHandle = service.scanDevices").contains("render()"))
    }

    @Test
    fun `every scan outcome is written to the console`() {
        // v1.9 (2.3): the ranges and ports are unknown before a scan runs, so the console log is the
        // only place where "we never looked there" can be told apart from "nothing is out there".
        val panel = MainSources.panel
        val start = MainSources.bodyOf(panel, "private fun startScan(")
        assertTrue("the scan announces what it is about to do", start.contains("开始扫描：默认端口 "))
        assertTrue(start.contains("附加端口 ["))
        assertTrue(start.contains("自定义网段 ["))
        assertTrue("and where the configured extras come from", start.contains("settings.scanPorts.ifBlank { \"无\" }"))

        val summary = MainSources.bodyOf(panel, "private fun scanSummary(")
        assertTrue(summary.contains("扫描完成：网段 ["))
        assertTrue("the covered ranges must be named", summary.contains("scan.ranges.joinToString(\"、\")"))
        assertTrue("the probed ports must be named", summary.contains("scan.ports.joinToString(\"、\")"))
        assertTrue("and so must the probe count", summary.contains("共探测 \${scan.probed} 个目标"))
        assertTrue(summary.contains("找到 \$found 台设备。"))
        assertTrue("the empty cases must not print an empty bracket", summary.contains("\"无本地网段\"") && summary.contains("\"无\""))
        assertTrue(
            "the success branch must print the summary once",
            start.contains("appendConsole(scanSummary(scan, found = scan.addresses.size))")
        )
        assertEquals("exactly one summary call site", 1, Regex("scanSummary\\(").findAll(start).count())

        // cancel and failure have their own lines; the failure text must not masquerade as a result
        val cancel = MainSources.bodyOf(panel, "private fun cancelScan(")
        assertTrue(cancel.contains("appendConsole(\"扫描已取消。\")"))
        val failure = start.substringAfter("onFailure = { error ->")
        assertTrue(failure.contains("appendConsole(\"扫描未执行：\$message\")"))
        assertTrue("the toolbar keeps the bare message", failure.contains("setScanStatus(message, autoClearMs = SCAN_ERROR_MS)"))
        assertFalse("a failed scan must not print a completion summary", failure.contains("scanSummary("))
    }

    // ---- D5 / N2 / N3: availability is recorded, the button state is derived --------------------

    @Test
    fun `availability is only recorded, the button state is derived separately`() {
        val setScanAvailable = MainSources.bodyOf(panel, "private fun setScanAvailable(")
        assertTrue(setScanAvailable.contains("hdcAvailable = available"))
        assertFalse("the recorder must not touch the button itself", setScanAvailable.contains("scanButton."))

        val refresh = MainSources.bodyOf(panel, "private fun refreshScanButton(")
        val branches = refresh.substringAfter("val tooltip = when {")
        assertTrue(branches.contains("scanning -> \"Cancel scan\""))
        assertTrue(branches.contains("!hdcAvailable -> \"hdc 不可用，请在设置中配置。\""))
        assertTrue(branches.contains("else -> \"Scan for devices\""))
    }

    // ---- round-2 fix D14 + round-3 N5: nothing blocking on a non-daemon thread -------------------

    @Test
    fun `auto detect runs on a daemon thread and reports back on the EDT`() {
        val configurable = MainSources.settingsConfigurable
        assertTrue(configurable.contains("label.text = \"正在搜索 hdc…\""))
        assertTrue(configurable.contains("detect.isEnabled = false"))
        assertTrue(configurable.contains("runCatching { HdcPathResolver.detect() }"))
        assertTrue(configurable.contains("\"hdc-detect\""))
        assertTrue(configurable.contains("apply { isDaemon = true }.start()"))
        assertTrue(configurable.contains("detect.isEnabled = true"))

        val threadStart = configurable.indexOf("\"hdc-detect\"")
        val marshalled = configurable.lastIndexOf("invokeLater", threadStart)
        assertTrue("the result must be applied back on the EDT", marshalled in 0 until threadStart)
    }

    @Test
    fun `the hdc test button runs on a daemon thread too`() {
        // N5: the Test button waits up to 10s for `hdc -v`; a non-daemon worker would delay IDE exit.
        val configurable = MainSources.settingsConfigurable
        assertTrue(configurable.contains("}, \"hdc-test\").apply { isDaemon = true }.start()"))
        assertTrue("the wait stays bounded", configurable.contains("process.waitFor(10, TimeUnit.SECONDS)"))
        assertTrue("and a hung process is killed", configurable.contains("process.destroyForcibly()"))

        val threadStart = configurable.indexOf("\"hdc-test\"")
        assertTrue("the outcome must be applied back on the EDT", configurable.lastIndexOf("invokeLater", threadStart) in 0 until threadStart)
    }

    // ---- teardown invariant ----------------------------------------------------------------------

    @Test
    fun `dispose stops every timer and cancels the running scan`() {
        val dispose = MainSources.bodyOf(panel, "override fun dispose()")
        listOf("autoTimer?.stop()", "animationTimer.stop()", "scanStatusTimer?.stop()", "scanHandle?.cancel()").forEach {
            assertTrue("dispose must call $it", dispose.contains(it))
        }
    }
}

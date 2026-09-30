package com.xq.hdcwifi

import com.xq.hdcwifi.service.HdcService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract items that are properties of the production source itself, including the two
 * historical defects fixed by the Lead (UX-CONTRACT §8 v1.2):
 *
 * 1. `@Service` was attached to the `HdcScanProgress` data class, so `HdcService` stopped being a
 *    registered service — it compiled but threw `ServiceNotFoundException` at runtime.
 * 2. A literal `EOF` line leaked into the end of `HdcDevice.kt`, breaking compilation.
 *
 * A runtime service-registration test is not possible here: the IntelliJ application is not started in
 * this JVM (`ApplicationManager.getApplication() == null`), so `service<HdcService>()` cannot be
 * called. The annotation placement is therefore asserted on the source, which is exactly what broke.
 */
class ContractConformanceTest {

    @Test
    fun `service annotation is attached to a class everywhere, and to HdcService specifically`() {
        MainSources.all().forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (line.trim() != "@Service") return@forEachIndexed
                val target = lines.drop(index + 1)
                    .firstOrNull { it.isNotBlank() && !it.trim().startsWith("@") }
                    ?.trim()
                    .orEmpty()
                assertTrue(
                    "${file.name}:${index + 1} @Service must annotate a class, but the next declaration is '$target'",
                    target.startsWith("class ")
                )
            }
        }

        val serviceSource = MainSources.service
        val annotationIndex = serviceSource.lines().indexOfFirst { it.trim() == "@Service" }
        assertTrue("@Service is missing from HdcService.kt", annotationIndex >= 0)
        val annotated = serviceSource.lines().drop(annotationIndex + 1).first { it.isNotBlank() }.trim()
        assertEquals("class HdcService : Disposable {", annotated)

        // the exact historical defect: no annotation on the scan-progress data class
        val progressIndex = serviceSource.lines().indexOfFirst { it.contains("data class HdcScanProgress") }
        assertTrue(progressIndex >= 0)
        assertFalse(
            "HdcScanProgress must not carry @Service",
            serviceSource.lines().take(progressIndex).last { it.isNotBlank() }.trim() == "@Service"
        )
    }

    @Test
    fun `no stray EOF marker leaked into any main source`() {
        MainSources.all().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                assertFalse(
                    "${file.name}:${index + 1} contains a stray EOF marker",
                    line.trim() == "EOF"
                )
            }
        }
    }

    @Test
    fun `removed legacy styling does not come back`() {
        val sources = MainSources.all().associate { it.name to it.readText() }
        sources.forEach { (name, text) ->
            assertFalse("$name: hardcoded legacy green 0x59A869", text.contains("0x59A869"))
            assertFalse("$name: text arrow \u2303 must be an AllIcons arrow", text.contains("\u2303"))
            assertFalse("$name: text arrow \u2304 must be an AllIcons arrow", text.contains("\u2304"))
            assertFalse("$name: fixed 76px row height", text.contains("scale(76)"))

            // UX-CONTRACT §0: colours must come from the platform theme; a raw AWT colour is only
            // acceptable as one half of a JBColor pair.
            text.lines().forEach { line ->
                if (line.contains("Color(0x")) {
                    assertTrue("$name: raw colour is not theme aware -> $line", line.contains("JBColor("))
                }
            }
        }
    }

    @Test
    fun `forget uses the remove icon and never the gc icon`() {
        val panel = MainSources.panel
        val forgetLines = panel.lines().filter { it.contains("忘记设备") }
        assertTrue("未找到忘记设备按钮", forgetLines.isNotEmpty())
        forgetLines.forEach { line ->
            assertTrue("Forget must use AllIcons.General.Remove -> $line", line.contains("AllIcons.General.Remove"))
        }
        panel.lines().filter { it.contains("AllIcons.Actions.GC") }.forEach { line ->
            assertFalse("Actions.GC (trash/GC semantics) must not mean Forget -> $line", line.contains("Forget"))
        }
    }

    @Test
    fun `connect and disconnect differ in icon and colour`() {
        val row = MainSources.bodyOf(MainSources.panel, "private fun deviceRow(")
        assertTrue(
            "Connect and Disconnect must use different icons",
            row.contains("icon = if (online) AllIcons.Actions.Suspend else AllIcons.Actions.Execute")
        )
        assertTrue(
            "Connected rows must offer Disconnect, the other states Connect",
            row.contains("text = if (online) \"断开连接\" else \"连接\"")
        )

        val actionButton = MainSources.bodyOf(MainSources.panel, "private fun actionButton(")
        assertTrue(
            "Disconnect must not use the Connect (success) colour",
            actionButton.contains("foreground = if (secondary) UIUtil.getLabelForeground() else SUCCESS")
        )
        assertTrue(actionButton.contains("isContentAreaFilled = false"))
        assertFalse("Disconnect must not be painted with the success colour", actionButton.contains("foreground = SUCCESS"))
    }

    @Test
    fun `a connecting row keeps a visible but disabled action button`() {
        val row = MainSources.bodyOf(MainSources.panel, "private fun deviceRow(")
        assertEquals(
            "每个条件分支中的设备工具、复制地址、忘记设备和主按钮都必须绑定连接中禁用状态",
            4,
            Regex("isEnabled = !connecting").findAll(row).count()
        )
        assertTrue("the button must stay in the row while connecting", row.contains("add(actionButton("))
        assertFalse(
            "the status must not be written into the button",
            row.contains("text = if (connecting)") || row.contains("连接中...")
        )
        val panel = MainSources.panel
        assertTrue(panel.contains("\"连接中\\u2026\""))
        assertFalse("the three-dot ellipsis is not the contract wording", panel.contains("连接中..."))
        assertFalse("the status text must be the Chinese contract copy", panel.contains("Connecting..."))
    }

    @Test
    fun `the device row is auto sized and section headers use arrow icons`() {
        val row = MainSources.bodyOf(MainSources.panel, "private fun deviceRow(")
        assertFalse("the device row must not pin its own height (fixed height comes from FixedHeightPanel)", row.contains("maximumSize"))

        val header = MainSources.bodyOf(MainSources.panel, "private fun sectionHeader(")
        assertTrue(header.contains("if (count > 0) \"\$title (\$count)\" else title"))
        assertTrue(header.contains("AllIcons.General.ArrowDown"))
        assertTrue(header.contains("AllIcons.General.ArrowRight"))
        assertFalse(header.contains("\u2303"))
        assertFalse(header.contains("\u2304"))
    }

    @Test
    fun `the spinner timer runs only while a device is connecting and is stopped on dispose`() {
        val panel = MainSources.panel
        val sync = MainSources.bodyOf(panel, "private fun syncAnimationTimer(")
        assertTrue(sync.contains("states.values.any { it.state == DeviceConnectionState.CONNECTING }"))
        assertTrue(sync.contains("animationTimer.start()"))
        assertTrue(sync.contains("animationTimer.stop()"))
        assertTrue(panel.contains("private const val ANIMATION_INTERVAL_MS = 83"))

        val disposeStart = panel.indexOf("override fun dispose()")
        assertTrue(disposeStart >= 0)
        val dispose = panel.substring(disposeStart, minOf(panel.length, disposeStart + 400))
        assertTrue("dispose must stop the auto-refresh timer", dispose.contains("autoTimer?.stop()"))
        assertTrue("dispose must stop the spinner timer", dispose.contains("animationTimer.stop()"))
        assertTrue("dispose must cancel a running scan", dispose.contains("scanHandle?.cancel()"))
    }

    @Test
    fun `auto refresh keeps the contract intervals, default and tooltip wording`() {
        val panel = MainSources.panel
        assertTrue(panel.contains("private val REFRESH_INTERVALS = listOf(0, 5, 10, 30, 60)"))
        assertTrue(panel.contains("private const val DEFAULT_REFRESH_SECONDS = 10"))
        // U4 (contract v1.7): the menu states the current value in a disabled header.
        // Round 7 (v1.8): the tool window copy is Chinese while action words stay English.
        assertTrue(panel.contains("JMenuItem(if (current == 0) \"自动刷新：关闭\" else \"自动刷新：\${current}s\")"))
        // U3: the tooltip is now a full status sentence, one per state.
        assertTrue(panel.contains("\"自动刷新已开启：每 \${seconds}s 一次。点击关闭。\""))
        assertTrue(panel.contains("\"自动刷新已关闭。点击后每 \${DEFAULT_REFRESH_SECONDS}s 刷新一次。\""))
        assertTrue(panel.contains("\"选择自动刷新间隔\""))
        // UX-CONTRACT §3.6 / SPEC §5.1: detail TTL and scan timeout
        assertTrue(panel.contains("private const val DETAIL_TTL_MS = 60_000L"))
        assertTrue(panel.contains("private const val SCAN_TIMEOUT_MS = 300"))
    }

    @Test
    fun `the auto refresh control states the interval and differs by more than selection`() {
        // U3 (contract v1.7): the interval used to be visible only inside the drop-down, and the
        // on/off states were told apart by the LAF's selected background alone.
        val panel = MainSources.panel
        val configure = MainSources.bodyOf(panel, "private fun configureAutoRefresh()")
        assertTrue(configure.contains("autoButton.isSelected = on"))
        assertTrue(configure.contains("autoButton.text = if (on) \"\${seconds}s\" else \"关闭\""))
        assertTrue(configure.contains("autoButton.foreground = if (on) SUCCESS else UIUtil.getContextHelpForeground()"))
        assertTrue(configure.contains("autoButton.font = autoButton.font.deriveFont(if (on) Font.BOLD else Font.PLAIN)"))
        assertTrue(configure.contains("autoButton.accessibleContext.accessibleName = tip"))

        // The factory must not pin a size any more: the button also carries text, so it has to size
        // itself to its content (mirrored at the Swing level in AutoRefreshToggleStyleTest).
        val factory = MainSources.bodyOf(panel, "private fun toggleButton(")
        assertTrue(factory.contains("margin = JBUI.insets(2, 6)"))
        assertFalse("a fixed 30x28 button cannot fit \"30s\"", factory.contains("preferredSize"))
        assertTrue(factory.contains("isContentAreaFilled = true"))
        assertTrue(factory.contains("isBorderPainted = true"))
    }

    @Test
    fun `the refresh menu states the current value before the interval list`() {
        // U4 (contract v1.7).
        val menu = MainSources.bodyOf(MainSources.panel, "private fun showRefreshMenu(")
        val header = menu.indexOf("add(JMenuItem(if (current == 0) \"自动刷新：关闭\" else \"自动刷新：\${current}s\")")
        val disabled = menu.indexOf("isEnabled = false")
        val separator = menu.indexOf("addSeparator()")
        val list = menu.indexOf("REFRESH_INTERVALS.forEach")
        assertTrue("the current-value header must exist", header >= 0)
        assertTrue("the header must be disabled so it states rather than changes", disabled in header until separator)
        assertTrue("the header must come first", header < separator)
        assertTrue("the separator must precede the interval list", separator < list)
        assertTrue("the current interval must stay ticked", menu.contains("val checked = if (seconds == current) \"\\u2713 \" else \"\""))
    }

    @Test
    fun `the settings ui is chinese and keeps only product and thread identifiers`() {
        // U6 (contract v1.7): the settings page was English; the user asked for Chinese UI copy.
        // The invariant below is the non-vacuous half: *every* user-visible literal passed to a
        // widget or a layout helper has to contain a CJK ideograph, so new English copy fails too.
        val configurable = MainSources.settingsConfigurable
        val patterns = listOf(
            Regex("""JButton\(\s*"([^"]*)""""),
            Regex("""JCheckBox\(\s*"([^"]*)""""),
            Regex("""JBLabel\(\s*"([^"]*)""""),
            Regex("""\bgroup\(\s*"([^"]*)""""),
            Regex("""\brow\(\s*"([^"]*)""""),
            Regex("""\bcomment\(\s*"([^"]*)""""),
            Regex("""(?<![\w.])label\(\s*"([^"]*)""""),
            Regex("""ConfigurationException\(\s*"([^"]*)""""),
            Regex("""label\.text\s*=\s*"([^"]*)"""")
        )
        val literals = patterns.flatMap { pattern -> pattern.findAll(configurable).map { it.groupValues[1] }.toList() }
        assertTrue("the settings page must have UI copy to check", literals.size >= 20)
        val cjk = Regex("[\\u4e00-\\u9fff]")
        literals.filter { it.isNotEmpty() }.forEach {
            assertTrue("English UI copy came back: $it", cjk.containsMatchIn(it))
        }

        listOf(
            "自动连接扫描发现的设备", "打开工具窗口时自动连接已保存的设备",
            "浏览…", "自动检测", "测试", "正在搜索 hdc…",
            "在已知 SDK 位置和 PATH 中都没有找到 hdc。", "不是可执行文件：", "hdc -v 执行超时（10 秒）",
            "hdc 可执行文件", "路径：", "连接", "默认 Wi-Fi 端口：", "自动刷新间隔：",
            "秒（0 表示关闭）", "自定义网段（CIDR）：", "请填写有效的数值连接设置。"
        ).forEach { assertTrue("missing Chinese copy: $it", configurable.contains(it)) }

        // The exceptions the user's rule keeps: the product name and the thread identifiers.
        assertTrue(configurable.contains("override fun getDisplayName(): String = \"HDC Wi-Fi\""))
        assertTrue(configurable.contains("\"hdc-detect\""))
        assertTrue(configurable.contains("\"hdc-test\""))
    }

    @Test
    fun `scan concurrency and cidr cap are verifiable constants`() {
        val service = MainSources.service
        // SPEC §5.1 / CONTRACT §8 v1.1(1): the cap is a readable constant, not an inline literal.
        // v1.9 raised it to 64: a whole-/22 scan probes thousands of hosts and the wall clock is
        // bound by the concurrency cap.
        assertEquals(64, HdcService.SCAN_CONCURRENCY)
        assertTrue("the probe semaphore must use the constant", service.contains("Semaphore(SCAN_CONCURRENCY)"))
        assertTrue("and so must the scan pool", service.contains("Executors.newFixedThreadPool(SCAN_CONCURRENCY)"))
        assertTrue(service.contains("private const val MAX_SCAN_ADDRESSES = 4096L"))
        assertTrue(service.contains("Socket().use { socket ->"))
    }

    @Test
    fun `scanning runs on its own bounded pool so candidates cannot spawn unbounded threads`() {
        val service = MainSources.service
        // D6: probes used to be submitted to the shared unbounded cached pool, one thread per probe
        val scanDevices = MainSources.bodyOf(service, "fun scanDevices(")
        assertFalse("probes must not use the shared cached pool", scanDevices.contains("executor.submit"))
        assertTrue("probes must use the dedicated scan pool", scanDevices.contains("scanExecutor.submit"))
        assertTrue("the scan pool is daemon", service.contains("Thread(runnable, \"hdc-scan\").apply { isDaemon = true }"))
        assertTrue("dispose must release it", service.contains("scanExecutor.shutdownNow()"))
        // the scan pool is fixed, never cached
        assertFalse(Regex("scanExecutor: ExecutorService = Executors\\.newCachedThreadPool").containsMatchIn(service))
    }

    @Test
    fun `the scan path never writes a discovered device into the saved list`() {
        // UX-CONTRACT §4.6 / UX-SPEC §5.3.3: only a successful connect may remember a device.
        val scan = MainSources.bodyOf(MainSources.panel, "private fun startScan(")
        assertFalse("scanning must not save devices", scan.contains("rememberDevice"))
        assertFalse("scanning must not save hosts", scan.contains("rememberHost"))
        assertFalse("scanning must not touch connection state", scan.contains("states["))
        // and the only remember* call sites in the panel are `list targets` and a successful connect
        assertEquals(2, Regex("rememberDevice").findAll(MainSources.panel).count())
        assertEquals(1, Regex("rememberHost").findAll(MainSources.panel).count())
    }

    @Test
    fun `settings expose and compare every new option including the extra scan ports`() {
        val state = MainSources.settings
        listOf(
            "var autoConnectDiscovered: Boolean = false",
            "var autoConnectSaved: Boolean = false",
            "var customScanCidr: String = \"\"",
            // v1.9 (2.10): the extra ports a scan probes on every range host.
            "var scanPorts: String = \"\""
        ).forEach { assertTrue("missing State field: $it", state.contains(it)) }

        // The property must be trimmed on the way in, like the sibling string settings. The anchor
        // includes the getter on purpose: `"var scanPorts: String"` alone first matches the `State`
        // field `var scanPorts: String = ""`, which has no setter at all.
        val property = MainSources.bodyOf(state, "var scanPorts: String\n        get() = myState.scanPorts")
        assertTrue("the setter must trim its input", property.contains(SCAN_PORTS_TRIMMING_SETTER))

        val configurable = MainSources.settingsConfigurable
        assertTrue(configurable.contains("pendingAutoConnectDiscovered != settings.autoConnectDiscovered"))
        assertTrue(configurable.contains("pendingAutoConnectSaved != settings.autoConnectSaved"))
        assertTrue(configurable.contains("pendingCustomScanCidr != settings.customScanCidr"))
        assertTrue("an unapplied port edit must mark the page modified", configurable.contains("pendingScanPorts != settings.scanPorts"))
        assertTrue(configurable.contains("settings.autoConnectDiscovered = pendingAutoConnectDiscovered"))
        assertTrue(configurable.contains("settings.autoConnectSaved = pendingAutoConnectSaved"))
        assertTrue(configurable.contains("settings.customScanCidr = pendingCustomScanCidr"))
        assertTrue("and the port edit must reach the settings", configurable.contains("settings.scanPorts = pendingScanPorts"))

        // wiring in all four lifecycle directions, including the reset back-reference
        assertEquals("the field must be seeded from the settings", 1, Regex("JBTextField\\(settings\\.scanPorts\\)").findAll(configurable).count())
        assertTrue(configurable.contains("pendingScanPorts = settings.scanPorts"))
        assertTrue("reset must push the stored value back into the widget", configurable.contains("scanPortsField.text = pendingScanPorts"))
        assertTrue("and a failed apply must not silently drop it", configurable.contains("pendingScanPorts = scanPortsField.text.trim()"))
        // the panel is the only consumer: it hands the setting to the scan
        assertTrue("the scan must use the configured ports", MainSources.panel.contains("extraPorts = settings.scanPorts"))
        assertTrue(configurable.contains("row(\"附加扫描端口：\")"))
    }

    @Test
    fun `the scan ports trim assertion catches a setter that stopped trimming`() {
        // Negative control for the assertion above, run on an in-memory copy of the production
        // source (src/main is never touched): with the setter reverted to the raw value the
        // extraction must no longer satisfy the invariant, so the guard is not vacuous.
        assertTrue("the real source must satisfy it", scanPortsIsTrimmed(MainSources.settings))
        assertFalse(
            "reverting the setter to `myState.scanPorts = value` must break the guard",
            scanPortsIsTrimmed(MainSources.settings.replace(SCAN_PORTS_TRIMMING_SETTER, SCAN_PORTS_UNTRIMMED_SETTER))
        )
    }

    private fun scanPortsIsTrimmed(source: String): Boolean =
        MainSources.bodyOf(source, "var scanPorts: String\n        get() = myState.scanPorts")
            .contains(SCAN_PORTS_TRIMMING_SETTER)

    private companion object {
        const val SCAN_PORTS_TRIMMING_SETTER = "set(value) { myState.scanPorts = value.trim() }"
        const val SCAN_PORTS_UNTRIMMED_SETTER = "set(value) { myState.scanPorts = value }"
    }
}

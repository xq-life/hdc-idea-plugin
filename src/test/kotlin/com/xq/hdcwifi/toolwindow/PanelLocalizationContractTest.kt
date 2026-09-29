package com.xq.hdcwifi.toolwindow

import com.xq.hdcwifi.MainSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 7 / UX-CONTRACT v1.8 (A): the tool window used to be fully English while the settings page had
 * already been translated. The product decision is a mixed-language UI — all descriptive copy is
 * Chinese, while the action words the user reads as commands stay English.
 *
 * `HdcMainPanel` needs the IntelliJ application and a `Project`, so it cannot be constructed in this
 * JVM; the copy is asserted on the production source (see `MainSources`). The invariant is deliberately
 * **not** "the tool window must contain Chinese" (that would be vacuous — the file obviously does):
 * it is "every user-visible literal either contains a CJK ideograph or is one of the explicitly
 * whitelisted English tokens", so a new English tooltip fails even though Chinese is present.
 */
class PanelLocalizationContractTest {

    private val panel = MainSources.panel

    private val cjk = Regex("[\\u4e00-\\u9fff]")

    /**
     * The English the user's decision keeps, grouped by why it is allowed:
     *  - action labels (buttons, menu items, action tooltips) — "action words stay English";
     *  - the product name;
     *  - structural templates: the interval text, the header text template, the check-mark prefix;
     *  - `hdc` output lookup keys — translating these silently breaks every device detail;
     *  - client-property / action-map keys and thread names (identifiers, never displayed).
     */
    private val allowedEnglish: Set<String> = setOf(
        "Connect", "Disconnect", "Forget device \${device.address}",
        "Scan for devices", "Cancel scan", "Copy address", "Device tools...",
        "HDC Wi-Fi",
        "\${seconds}s", "\$title (\$count)", "\$checked\$label", "\u2713 ",
        "Model", "Product name", "System release", "Software version", "API version",
        "hdc.sectionKey", "hdc.toggleSection", "hdc.expandSection", "hdc.collapseSection",
        "hdc-detect", "hdc-test"
    )

    // ---- the invariant -------------------------------------------------------------------------

    @Test
    fun `every tool window literal is chinese unless it is an explicitly whitelisted english token`() {
        val literals = whitelistableLiterals(panel)
        assertTrue(
            "the extractor must actually find tool window copy (found ${literals.size})",
            literals.size >= 40
        )
        assertEquals(
            "English UI copy came back outside the whitelist",
            emptyList<String>(),
            englishViolations(panel)
        )
    }

    @Test
    fun `the invariant still catches a genuine english literal`() {
        // Negative control: a tooltip translated back to English must be reported. Without it the
        // punctuation filter in the extractor could silently degrade the invariant to "always
        // empty" and nobody would notice.
        val reverted = panel.replace("\"刷新设备列表\"", "\"Refresh device list\"")
        assertTrue("the substitution must actually apply", reverted != panel)
        assertEquals(
            "an English tooltip must be caught",
            listOf("Refresh device list"),
            englishViolations(reverted)
        )
    }

    @Test
    fun `the key tool window copy is the chinese contract wording`() {
        listOf(
            // section headers
            "网络上的可用设备", "已连接设备", "之前连接过的设备",
            // status words
            "已连接", "连接中\\u2026", "失败", "未连接",
            // the four source-specific scan empty states
            "在本地网络中没有找到设备。",
            "在本地网络和已保存地址中都没有找到设备。",
            "在本地网络和自定义网段中都没有找到设备。",
            "在本地网络、已保存地址和自定义网段中都没有找到设备。",
            // other empty states and the pre-scan hint
            "点击 Scan for devices 扫描网络中的设备。",
            "没有已连接的设备。", "没有之前连接过的设备。",
            // toolbar copy and tooltips
            "清空命令输出", "添加 HDC 设备", "显示或隐藏命令输出", "刷新设备列表",
            "自动刷新", "选择自动刷新间隔", "HDC Wi-Fi 设置", "的更多操作",
            "折叠 \$title", "展开 \$title",
            // last-updated
            "最后更新 -", "最后更新 ",
            // scan feedback
            "已扫描 0/\\u2026", "找到 \${scan.addresses.size} 台设备。",
            "无法扫描网络。", "扫描已取消。", "hdc 不可用，请在设置中配置。",
            // console / failure copy
            "未找到 hdc 可执行文件。请在「设置 > 工具 > HDC Wi-Fi」中配置。",
            "刷新失败：", "连接失败：", "断开失败：", "未知错误。",
            // auto-refresh control and menu header
            "autoButton.text = if (on) \"\${seconds}s\" else \"关闭\"",
            "自动刷新：关闭", "自动刷新：\${current}s",
            "自动刷新已开启：每 \${seconds}s 一次。点击关闭。",
            "自动刷新已关闭。点击后每 \${DEFAULT_REFRESH_SECONDS}s 刷新一次。"
        ).forEach { assertTrue("missing Chinese tool window copy: $it", panel.contains(it)) }
    }

    @Test
    fun `the hdc info lookup keys stay english`() {
        // `HdcService.deviceInfo` returns a map keyed by the raw `hdc param get` names. Translating a
        // lookup key is a silent functional break: every device detail would become an empty string.
        listOf("Model", "Product name", "System release", "Software version", "API version").forEach { key ->
            assertTrue("hdc lookup key must stay English: info[\"$key\"]", panel.contains("info[\"$key\"]"))
        }
        assertFalse(
            "a translated lookup key silently breaks device info",
            Regex("info\\[\"[\\u4e00-\\u9fff]").containsMatchIn(panel)
        )
    }

    @Test
    fun `the action words, product name and structural templates stay english`() {
        listOf(
            "\"Scan for devices\"", "\"Cancel scan\"", "\"Forget device \${device.address}\"",
            "\"Connect\"", "\"Disconnect\"", "\"Copy address\"", "\"Device tools...\"",
            "\"HDC Wi-Fi\"",
            "\"\${seconds}s\"", "\"\\u2713 \""
        ).forEach { assertTrue("this token must stay English as chosen: $it", panel.contains(it)) }

        // the structural constants the wording is built on must not drift either
        assertTrue(panel.contains("private val REFRESH_INTERVALS = listOf(0, 5, 10, 30, 60)"))
        assertTrue(panel.contains("private const val DEFAULT_REFRESH_SECONDS = 10"))
    }

    // ---- the extractor -------------------------------------------------------------------------

    /** The non-vacuous invariant, shared by the positive test and its negative control. */
    private fun englishViolations(source: String): List<String> =
        whitelistableLiterals(source).filter { !cjk.containsMatchIn(it) && it !in allowedEnglish }

    /**
     * Every literal that reaches a widget as user-visible text, gathered from the known sinks: the
     * text-taking widget factories, the copy-bearing property assignments, the copy-only helper
     * bodies and the three `addGroup` call sites. Comments are stripped first, so English prose in a
     * comment is never mistaken for UI copy.
     */
    private fun whitelistableLiterals(source: String): List<String> {
        val code = stripComments(source)
        val literals = mutableListOf<String>()

        val widgetSinks = listOf("iconButton(", "iconButtonWithSource(", "toggleButton(", "JBLabel(", "JMenuItem(")
        code.lines()
            .filter { line -> widgetSinks.any { line.contains(it) } }
            .forEach { line -> literals += literalsIn(line) }

        listOf(
            "autoButton.text = if (on)",
            "toolTipText = if (expanded)",
            "val text = if (count > 0)",
            "showSettingsDialog(project,",
            "text = if (online)"
        ).forEach { marker ->
            code.lines()
                .filter { line -> line.contains(marker) }
                .forEach { line -> literals += literalsIn(line) }
        }

        listOf(
            "private fun statusText(",
            "private fun emptyScanText(",
            "private fun refreshScanButton(",
            "private fun cancelScan(",
            "private fun startScan("
        ).forEach { signature -> literals += literalsIn(MainSources.bodyOf(code, signature)) }

        ADD_GROUP_CALL.findAll(code).forEach { call -> literals += literalsIn(call.groupValues[1]) }

        return literals
    }

    private fun literalsIn(text: String): List<String> =
        STRING_LITERAL.findAll(text).map { it.groupValues[1] }
            .filter { it.isNotEmpty() && LETTER.containsMatchIn(it) }
            .toList()

    /**
     * Drops `//` line comments and `/* ... */` block comments while leaving string literals intact,
     * so a `//` inside copy (a URL, say) cannot truncate the source.
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        var inString = false
        while (index < source.length) {
            val current = source[index]
            val next = if (index + 1 < source.length) source[index + 1] else '\u0000'
            if (inString) {
                out.append(current)
                when {
                    current == '\\' && index + 1 < source.length -> {
                        out.append(next)
                        index += 2
                        continue
                    }
                    current == '"' -> inString = false
                }
                index++
                continue
            }
            when {
                current == '"' -> {
                    inString = true
                    out.append(current)
                    index++
                }
                current == '/' && next == '/' -> while (index < source.length && source[index] != '\n') index++
                current == '/' && next == '*' -> {
                    index += 2
                    while (index < source.length &&
                        !(source[index] == '*' && index + 1 < source.length && source[index + 1] == '/')
                    ) index++
                    index += 2
                }
                else -> {
                    out.append(current)
                    index++
                }
            }
        }
        return out.toString()
    }

    private companion object {
        /** One Kotlin string literal, escapes included. */
        val STRING_LITERAL = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")

        /**
         * A fragment counts as copy only if it holds at least one letter. Kotlin templates nest
         * quotes — `"端口 [${x.ifBlank { "无" }}]"` — and the flat scan below necessarily splits at
         * the inner quote, yielding punctuation-only tails such as ` }}]`. Those are not UI copy;
         * they used to be reported as "English UI copy" because they contain no CJK either.
         */
        val LETTER = Regex("\\p{L}")

        /** An `addGroup(...)` call site, up to its `showForget` argument. */
        val ADD_GROUP_CALL = Regex(
            "(?m)^\\s+addGroup\\((.*?)showForget = (?:true|false)\\s*\\)",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        )
    }
}

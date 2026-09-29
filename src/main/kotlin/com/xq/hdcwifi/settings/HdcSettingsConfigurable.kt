package com.xq.hdcwifi.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.xq.hdcwifi.service.HdcCommandRunner
import com.xq.hdcwifi.service.HdcPathResolver
import java.io.File
import java.awt.BorderLayout
import java.awt.Dimension
import java.text.NumberFormat
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFormattedTextField
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent

class HdcSettingsConfigurable : Configurable {

    private val settings = HdcSettings.instance
    private var pathField: JBTextField? = null
    private var resultLabel: JBLabel? = null
    private var panel: DialogPanel? = null

    private var pendingPath: String = settings.hdcPath
    private var pendingPort: Int = settings.defaultPort
    private var pendingRefresh: Int = settings.autoRefreshSeconds
    private var pendingAutoConnectDiscovered: Boolean = settings.autoConnectDiscovered
    private var pendingAutoConnectSaved: Boolean = settings.autoConnectSaved
    private val autoConnectDiscoveredBox = JCheckBox("自动连接扫描发现的设备")
    private val autoConnectSavedBox = JCheckBox("打开工具窗口时自动连接已保存的设备")
    private val customScanCidrField = JBTextField(settings.customScanCidr)
    private val scanPortsField = JBTextField(settings.scanPorts)
    private var pendingCustomScanCidr: String = settings.customScanCidr
    private var pendingScanPorts: String = settings.scanPorts

    private val portSpinner = JSpinner(SpinnerNumberModel(pendingPort, 1, 65535, 1))
    private val refreshSpinner = JSpinner(SpinnerNumberModel(pendingRefresh, 0, 3600, 5))

    init {
        autoConnectDiscoveredBox.isSelected = pendingAutoConnectDiscovered
        autoConnectSavedBox.isSelected = pendingAutoConnectSaved
        autoConnectDiscoveredBox.addActionListener { pendingAutoConnectDiscovered = autoConnectDiscoveredBox.isSelected }
        autoConnectSavedBox.addActionListener { pendingAutoConnectSaved = autoConnectSavedBox.isSelected }
        customScanCidrField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { pendingCustomScanCidr = customScanCidrField.text.trim() }
        })
        scanPortsField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { pendingScanPorts = scanPortsField.text.trim() }
        })
        portSpinner.addChangeListener { pendingPort = portSpinner.value as Int }
        refreshSpinner.addChangeListener { pendingRefresh = refreshSpinner.value as Int }
        configureNumberEditor(portSpinner, 72)
        configureNumberEditor(refreshSpinner, 72)
    }

    override fun getDisplayName(): String = "HDC Wi-Fi"

    override fun createComponent(): JComponent {
        val field = JBTextField()
        pathField = field
        field.text = pendingPath
        val label = JBLabel("")
        label.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        resultLabel = label

        val browse = JButton("浏览…")
        browse.addActionListener {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            val chosen = FileChooser.chooseFile(descriptor, null, null)
            if (chosen != null) {
                field.text = chosen.path
            }
        }

        val detect = JButton("自动检测")
        detect.addActionListener {
            // Detection walks the filesystem and PATH; doing that on the EDT freezes the dialog.
            label.text = "正在搜索 hdc…"
            detect.isEnabled = false
            Thread({
                val candidates = runCatching { HdcPathResolver.detect() }.getOrDefault(emptyList())
                ApplicationManager.getApplication().invokeLater {
                    detect.isEnabled = true
                    if (candidates.isEmpty()) {
                        label.text = "在已知 SDK 位置和 PATH 中都没有找到 hdc。"
                    } else {
                        field.text = candidates.first().path
                        label.text = "找到 ${candidates.size} 个：" + candidates.joinToString("；") { "${it.path}（${it.source}）" }
                    }
                }
            }, "hdc-detect").apply { isDaemon = true }.start()
        }

        val test = JButton("测试")
        test.addActionListener {
            label.text = "测试中…"
            // Disabled until the probe reports back, so repeated clicks cannot pile up probes and
            // the button visibly reports that something is in flight.
            test.isEnabled = false
            val executable = field.text.trim()
            Thread({
                val outcome = probeHdc(executable)
                ApplicationManager.getApplication().invokeLater {
                    test.isEnabled = true
                    label.text = outcome
                }
            }, "hdc-test").apply { isDaemon = true }.start()
        }

        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                pendingPath = field.text.trim()
                label.text = ""
            }
        })

        val pathRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(field, BorderLayout.CENTER)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                add(test)
                add(detect)
                add(browse)
            }, BorderLayout.EAST)
        }

        val root = panel {
            group("hdc 可执行文件") {
                row("路径：") {
                    cell(pathRow).align(Align.FILL)
                }
                row {
                    comment(
                        "留空则自动从 DevEco Studio、HarmonyOS/OpenHarmony SDK 或 PATH 中查找 hdc；" +
                            "可用「测试」验证所选可执行文件。"
                    )
                }
            }
            row {
                cell(label).align(Align.FILL)
            }
            group("连接") {
                row("默认 Wi-Fi 端口：") {
                    cell(portSpinner)
                }
                row("自动刷新间隔：") {
                    cell(refreshSpinner)
                    label("秒（0 表示关闭）")
                }
                row {
                    comment(
                        "工具窗口隐藏时暂停轮询，重新显示时立即刷新一次并恢复轮询；" +
                            "工具栏上也有同一项设置，并提供常用间隔的快捷选择。"
                    )
                }
                row { cell(autoConnectDiscoveredBox) }
                row { comment("扫描发现的设备会自动连接一次；连接失败会保持可见，不会自动重试。") }
                row { cell(autoConnectSavedBox) }
                row { comment("打开工具窗口后，已保存的设备会逐个自动连接；连接失败会保持可见，不会自动重试。") }
                row("自定义网段（CIDR）：") { cell(customScanCidrField).align(Align.FILL) }
                row { comment("可选。扫描时额外探测的 IPv4 网段，例如 192.168.10.0/24；超过 4096 个地址的网段不会被扫描。") }
                row("附加扫描端口：") { cell(scanPortsField).align(Align.FILL) }
                row { comment("可选。用逗号分隔，例如 5555,38343。无线调试常分配随机高位端口，只探默认端口会找不到设备；已连接过的设备端口会自动加入。最多 6 个。") }
            }
        }
        panel = root
        return root
    }

    override fun isModified(): Boolean {
        commitNumericEditorsIfValid()
        return pendingPath != settings.hdcPath ||
            pendingPort != settings.defaultPort ||
            pendingRefresh != settings.autoRefreshSeconds ||
            pendingAutoConnectDiscovered != settings.autoConnectDiscovered ||
            pendingAutoConnectSaved != settings.autoConnectSaved ||
            pendingCustomScanCidr != settings.customScanCidr ||
            pendingScanPorts != settings.scanPorts
    }

    override fun apply() {
        if (!commitNumericEditorsIfValid()) {
            throw com.intellij.openapi.options.ConfigurationException("请填写有效的数值连接设置。")
        }
        panel?.apply()
        settings.hdcPath = pendingPath
        settings.defaultPort = pendingPort
        settings.autoRefreshSeconds = pendingRefresh
        settings.autoConnectDiscovered = pendingAutoConnectDiscovered
        settings.autoConnectSaved = pendingAutoConnectSaved
        settings.customScanCidr = pendingCustomScanCidr
        settings.scanPorts = pendingScanPorts
        HdcCommandRunner.resetCache()
    }

    override fun reset() {
        pendingPath = settings.hdcPath
        pendingPort = settings.defaultPort
        pendingRefresh = settings.autoRefreshSeconds
        pendingAutoConnectDiscovered = settings.autoConnectDiscovered
        pendingAutoConnectSaved = settings.autoConnectSaved
        pendingCustomScanCidr = settings.customScanCidr
        pendingScanPorts = settings.scanPorts
        customScanCidrField.text = pendingCustomScanCidr
        scanPortsField.text = pendingScanPorts
        autoConnectDiscoveredBox.isSelected = pendingAutoConnectDiscovered
        autoConnectSavedBox.isSelected = pendingAutoConnectSaved
        pathField?.text = pendingPath
        resultLabel?.text = ""
        portSpinner.value = pendingPort
        refreshSpinner.value = pendingRefresh
        panel?.reset()
    }

    /**
     * Probes the configured hdc and always comes back with something to show.
     *
     * The entire probe — path resolution, process start and output collection — runs under one
     * deadline. Whatever blocks (a slow `hdc -v`, a spawned server holding the output pipe, a
     * filesystem that never answers), the label ends up with a result instead of sitting on
     * "测试中…" forever.
     */
    private fun probeHdc(executable: String): String {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hdc-probe").apply { isDaemon = true }
        }
        return try {
            executor.submit(Callable { probeHdcBlocking(executable) })
                .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            "探测 hdc 超时（${PROBE_TIMEOUT_MS / 1000} 秒）。请确认所选文件是 hdc 可执行文件，或改用“自动检测”。"
        } catch (error: Exception) {
            "运行 hdc 失败：${error.cause?.message ?: error.message}"
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probeHdcBlocking(executable: String): String {
        if (executable.isNotEmpty() && !HdcPathResolver.isExecutableFile(executable)) {
            return "不是可执行文件：$executable"
        }
        val args = executable.ifEmpty { HdcPathResolver.resolve() ?: "hdc" }
        return runCatching { runHdcVersion(args) }.getOrElse { "运行 hdc 失败：${it.message}" }
    }

    /**
     * Runs `hdc -v`, collecting output on a second daemon thread rather than reading the stream
     * inline. A spawned hdc server can inherit the pipe and hold it open after the `-v` process
     * itself has exited, and reading inline in that case never returns.
     */
    private fun runHdcVersion(executable: String): String {
        val process = ProcessBuilder(executable, "-v").redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread({
            runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        }, "hdc-test-read").apply {
            isDaemon = true
            start()
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "hdc -v 执行超时（10 秒）"
        }
        // Bounded wait only: the pipe may never reach EOF.
        reader.join(1_000)
        return output.toString().trim().ifEmpty { "hdc -v 执行成功" }
    }

    private fun configureNumberEditor(spinner: JSpinner, width: Int) {
        val editor = JSpinner.NumberEditor(spinner, "0")
        val formatter = editor.textField.formatter
        if (formatter is javax.swing.text.NumberFormatter) {
            formatter.format = NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }
        }
        editor.textField.horizontalAlignment = JFormattedTextField.RIGHT
        spinner.editor = editor
        spinner.preferredSize = Dimension(JBUI.scale(width), spinner.preferredSize.height)
    }

    private fun commitNumericEditorsIfValid(): Boolean {
        if (runCatching { portSpinner.commitEdit(); refreshSpinner.commitEdit() }.isFailure) return false
        pendingPort = (portSpinner.value as Number).toInt()
        pendingRefresh = (refreshSpinner.value as Number).toInt()
        return pendingPort in 1..65535 && pendingRefresh in 0..3600
    }

    private companion object {
        /** Upper bound for the whole hdc probe, so the Test button always reports a result. */
        const val PROBE_TIMEOUT_MS = 15_000L
    }
}

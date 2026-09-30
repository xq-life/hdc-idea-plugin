package com.xq.hdcwifi.toolwindow

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.xq.hdcwifi.settings.HdcSettings
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.text.NumberFormat
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFormattedTextField
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent

/** Wi-Fi connect dialog: device address (with recent history) + port. */
class ConnectDeviceDialog : DialogWrapper(true) {

    private val settings = HdcSettings.instance
    private val hostBox = JComboBox(settings.recentHosts().toTypedArray())
    private val portSpinner = JSpinner(SpinnerNumberModel(settings.defaultPort, 1, 65535, 1))
    private val hint = JBLabel(
        "<html>请在设备上开启无线调试，然后填入设备显示的 IP 地址与端口。</html>"
    )

    private var resolvedHost: String = ""
    private var resolvedPort: Int = settings.defaultPort

    val host: String
        get() = resolvedHost

    val port: Int
        get() = resolvedPort

    init {
        title = "连接 HDC 设备（Wi-Fi）"
        setOKButtonText("连接")
        isResizable = true

        hostBox.isEditable = true
        hostBox.editor.item = settings.recentHosts().firstOrNull() ?: ""
        hostBox.preferredSize = Dimension(360, hostBox.preferredSize.height)
        configurePortEditor()

        val editorField = hostBox.editor.editorComponent
        if (editorField is javax.swing.text.JTextComponent) {
            editorField.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    setErrorText(null)
                }
            })
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val labelConstraints = GridBagConstraints().apply {
            gridx = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 0, 4, 12)
        }
        val fieldConstraints = GridBagConstraints().apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 0, 4, 0)
        }
        labelConstraints.gridy = 0
        fieldConstraints.gridy = 0
        panel.add(JBLabel("设备 IP 或主机名："), labelConstraints)
        panel.add(hostBox, fieldConstraints)
        labelConstraints.gridy = 1
        fieldConstraints.gridy = 1
        fieldConstraints.fill = GridBagConstraints.NONE
        fieldConstraints.anchor = GridBagConstraints.WEST
        panel.add(JBLabel("端口："), labelConstraints)
        panel.add(portSpinner, fieldConstraints)
        panel.add(hint, GridBagConstraints().apply {
            gridx = 0
            gridy = 2
            gridwidth = 2
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(8, 0, 0, 0)
        })
        // Width is fixed so the hint has a stable wrap point; the height follows the text, so the
        // dialog does not keep the extra blank row the longer English hint used to need.
        panel.preferredSize = Dimension(DIALOG_WIDTH, maxOf(panel.preferredSize.height, DIALOG_MIN_HEIGHT))
        return panel
    }

    override fun doOKAction() {
        val rawAddress = hostBox.editor.item?.toString()?.trim().orEmpty()
        val editedPort = try {
            readCommittedPort(portSpinner)
        } catch (_: Exception) {
            setErrorText(PORT_ERROR, portSpinner)
            return
        }
        val (address, parsedPort) = parseHdcAddress(rawAddress, editedPort)
        if (address.isEmpty()) {
            setErrorText("请输入设备 IP 地址", hostBox)
            return
        }
        if (!address.matches(Regex("[A-Za-z0-9._-]+"))) {
            setErrorText("主机名无效：$rawAddress", hostBox)
            return
        }
        if (parsedPort !in 1..65535) {
            setErrorText(PORT_ERROR, portSpinner)
            return
        }
        resolvedHost = address
        resolvedPort = parsedPort
        hostBox.selectedItem = address
        portSpinner.value = parsedPort
        settings.defaultPort = parsedPort
        super.doOKAction()
    }

    private fun configurePortEditor() {
        val editor = JSpinner.NumberEditor(portSpinner, "0")
        val formatter = editor.textField.formatter
        if (formatter is javax.swing.text.NumberFormatter) {
            formatter.format = NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }
            formatter.allowsInvalid = true
        }
        editor.textField.horizontalAlignment = JFormattedTextField.RIGHT
        portSpinner.editor = editor
        portSpinner.preferredSize = Dimension(110, portSpinner.preferredSize.height)
    }

    private companion object {
        const val DIALOG_WIDTH = 520
        const val DIALOG_MIN_HEIGHT = 92
        const val PORT_ERROR = "请输入 1 到 65535 之间的端口"
    }
}

internal fun parseHdcAddress(input: String, fallbackPort: Int): Pair<String, Int> {
    val address = input.trim()
    val parts = address.split(":", limit = 2)
    if (parts.size != 2) return address to fallbackPort
    val inlinePort = parts[1].toIntOrNull() ?: return address to fallbackPort
    return parts[0] to inlinePort
}

internal fun readCommittedPort(spinner: JSpinner): Int {
    spinner.commitEdit()
    return (spinner.value as Number).toInt()
}

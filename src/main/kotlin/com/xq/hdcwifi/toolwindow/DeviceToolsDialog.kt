package com.xq.hdcwifi.toolwindow

import com.intellij.openapi.ui.DialogWrapper
import com.xq.hdcwifi.service.HdcService
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JTabbedPane

class DeviceToolsDialog(private val target: String, service: HdcService) : DialogWrapper(true) {

    private val infoPane = DeviceInfoPane(service)
    private val shellPane = ShellPane(service)
    private val filePane = FilePane(service)
    private val logPane = LogPane(service)

    init {
        title = "HDC Device Tools - $target"
        setOKButtonText("Close")
        isResizable = true
        init()
        infoPane.bind(target)
        shellPane.bind(target)
        filePane.bind(target)
        logPane.bind(target)
    }

    override fun createCenterPanel(): JComponent = JTabbedPane().apply {
        addTab("Info", infoPane)
        addTab("Shell", shellPane)
        addTab("Files", filePane)
        addTab("Hilog", logPane)
        preferredSize = Dimension(760, 520)
    }

    override fun dispose() {
        filePane.dispose()
        logPane.bind(null)
        super.dispose()
    }
}

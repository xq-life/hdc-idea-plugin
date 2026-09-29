package com.xq.hdcwifi.toolwindow

import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTextArea
import com.xq.hdcwifi.service.HdcService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel

class DeviceInfoPane(private val service: HdcService) : JPanel(BorderLayout()) {

    private val textArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        lineWrap = false
    }
    private val refreshButton = JButton("Refresh Info")
    private var target: String? = null

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply { add(refreshButton) }
        add(toolbar, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(textArea), BorderLayout.CENTER)

        refreshButton.addActionListener { loadInfo(forceRefresh = true) }
        bind(null)
    }

    fun bind(target: String?) {
        this.target = target
        if (target == null) {
            textArea.text = "Select a device to view its information."
        } else {
            textArea.text = "Target: $target\nLoading... (takes a few seconds)"
            loadInfo(forceRefresh = false)
        }
    }

    private fun loadInfo(forceRefresh: Boolean) {
        val current = target ?: return
        refreshButton.isEnabled = false
        service.deviceInfo(current, forceRefresh) { info ->
            refreshButton.isEnabled = true
            // Ignore stale responses after switching devices.
            if (target != current) return@deviceInfo
            val width = info.keys.maxOf { it.length }
            textArea.text = info.entries.joinToString("\n") { (k, v) ->
                k.padEnd(width + 2) + v
            }
        }
    }
}

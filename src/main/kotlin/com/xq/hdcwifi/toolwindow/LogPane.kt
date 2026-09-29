package com.xq.hdcwifi.toolwindow

import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.xq.hdcwifi.service.HdcService
import com.xq.hdcwifi.service.HdcStream
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel

class LogPane(private val service: HdcService) : JPanel(BorderLayout()) {

    private val filterField = JBTextField()
    private val startButton = JButton("Start")
    private val stopButton = JButton("Stop")
    private val clearButton = JButton("Clear")
    private val autoScroll = JBCheckBox("Auto-scroll", true)
    private val statusLabel = JBLabel("Idle")
    private val outputArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }
    private var target: String? = null
    private var stream: HdcStream? = null
    private var streamGeneration = 0

    init {
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JBLabel("Filter:"))
            add(filterField)
            filterField.preferredSize = java.awt.Dimension(160, filterField.preferredSize.height)
            add(startButton)
            add(stopButton)
            add(clearButton)
            add(autoScroll)
            add(statusLabel)
        }
        add(controls, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(outputArea), BorderLayout.CENTER)

        startButton.addActionListener { start() }
        stopButton.addActionListener { stop() }
        clearButton.addActionListener { outputArea.text = "" }
        bind(null)
    }

    fun bind(target: String?) {
        this.target = target
        stop()
        startButton.isEnabled = target != null
    }

    private fun start() {
        val current = target ?: return
        stop()
        val generation = ++streamGeneration
        statusLabel.text = "Streaming hilog..."
        startButton.isEnabled = false
        stream = service.startHilog(
            current,
            onLine = { line ->
                val filter = filterField.text.trim()
                if (filter.isEmpty() || line.contains(filter, ignoreCase = true)) {
                    appendLine(line)
                }
            },
            onExit = {
                if (generation != streamGeneration) return@startHilog
                stream = null
                startButton.isEnabled = target != null
                statusLabel.text = "Stopped"
            }
        )
        if (stream == null) {
            startButton.isEnabled = true
            statusLabel.text = "Failed to start"
        }
    }

    private fun stop() {
        streamGeneration++
        val current = stream
        stream = null
        current?.stop()
        startButton.isEnabled = target != null
        statusLabel.text = "Idle"
    }

    private fun appendLine(line: String) {
        outputArea.append(line + "\n")
        trimIfNeeded()
        if (autoScroll.isSelected) {
            outputArea.caretPosition = outputArea.document.length
        }
    }

    private fun trimIfNeeded() {
        val max = 6000
        if (outputArea.lineCount > max) {
            val cut = outputArea.getLineStartOffset(outputArea.lineCount - max + 1000)
            outputArea.replaceRange("", 0, cut)
        }
    }
}

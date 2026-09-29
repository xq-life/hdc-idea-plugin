package com.xq.hdcwifi.toolwindow

import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.xq.hdcwifi.service.HdcService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JButton
import javax.swing.JPanel

class ShellPane(private val service: HdcService) : JPanel(BorderLayout()) {

    private val commandField = JBTextField()
    private val runButton = JButton("Run")
    private val clearButton = JButton("Clear")
    private val outputArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }
    private var target: String? = null

    init {
        val inputPanel = JPanel(BorderLayout(6, 0)).apply {
            add(JBLabel("Command:"), BorderLayout.WEST)
            add(commandField, BorderLayout.CENTER)
            add(runButton, BorderLayout.EAST)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(inputPanel)
            add(clearButton)
        }
        add(buttons, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(outputArea), BorderLayout.CENTER)

        runButton.addActionListener { runCommand() }
        clearButton.addActionListener { outputArea.text = "" }
        commandField.addKeyListener(object : KeyListener {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) runCommand()
            }

            override fun keyReleased(e: KeyEvent) {}
            override fun keyTyped(e: KeyEvent) {}
        })
        bind(null)
    }

    fun bind(target: String?) {
        this.target = target
        runButton.isEnabled = target != null
        append(if (target != null) "\n--- target: $target ---\n" else "\n--- no device selected ---\n")
    }

    private fun runCommand() {
        val current = target ?: return
        val command = commandField.text.trim()
        if (command.isEmpty()) return
        append("\$ hdc -t $current shell $command\n")
        runButton.isEnabled = false
        service.shell(current, command) { result ->
            runButton.isEnabled = target != null
            append(result.output)
            if (!result.ok) append("[command failed, exit ${result.exitCode}]\n")
        }
    }

    private fun append(text: String) {
        outputArea.append(text)
        outputArea.caretPosition = outputArea.document.length
    }
}

package com.xq.hdcwifi.toolwindow

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.xq.hdcwifi.service.HdcService
import com.xq.hdcwifi.service.HdcStream
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel

class FilePane(private val service: HdcService) : JPanel(BorderLayout()) {

    private val localField = JBTextField()
    private val remoteField = JBTextField("/data/local/tmp/")
    private val sendButton = JButton("发送到设备")
    private val receiveButton = JButton("从设备接收")
    private val stopButton = JButton("停止")
    private val statusLabel = JBLabel("就绪")
    private val outputArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }
    private var target: String? = null
    private var stream: HdcStream? = null
    private var transferGeneration = 0

    init {
        val browseSend = JButton("浏览…").apply {
            addActionListener {
                val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                val file = FileChooser.chooseFile(descriptor, null, null)
                if (file != null) {
                    localField.text = file.path
                    remoteField.text = "/data/local/tmp/" + file.name
                }
            }
        }
        val localRow = JPanel(BorderLayout(6, 0)).apply {
            add(localField, BorderLayout.CENTER)
            add(browseSend, BorderLayout.EAST)
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("本地文件："), localRow)
            .addLabeledComponent(JBLabel("设备路径："), remoteField)
            .panel
            .apply { border = JBUI.Borders.empty(8) }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(sendButton)
            add(receiveButton)
            add(stopButton)
            add(statusLabel)
        }

        val top = JPanel(BorderLayout()).apply {
            add(form, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }

        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(outputArea), BorderLayout.CENTER)

        sendButton.addActionListener { send() }
        receiveButton.addActionListener { receive() }
        stopButton.addActionListener { stopCurrent() }
        stopButton.isEnabled = false
        bind(null)
    }

    fun bind(target: String?) {
        this.target = target
        sendButton.isEnabled = target != null
        receiveButton.isEnabled = target != null
    }

    fun dispose() {
        stopCurrent()
    }

    private fun send() {
        val current = target ?: return
        val local = localField.text.trim()
        val remote = remoteField.text.trim()
        if (local.isEmpty() || remote.isEmpty()) {
            statusLabel.text = "请填写本地路径和设备路径"
            return
        }
        startTransfer("send") { onLine, onExit -> service.startFileSend(current, local, remote, onLine, onExit) }
    }

    private fun receive() {
        val current = target ?: return
        val remote = remoteField.text.trim()
        var local = localField.text.trim()
        if (remote.isEmpty() || local.isEmpty()) {
            statusLabel.text = "请填写设备路径和本地路径"
            return
        }
        // If the local path is an existing directory, append the remote file name.
        val localFile = java.io.File(local)
        if (localFile.isDirectory) {
            val name = remote.substringAfterLast('/')
            if (name.isNotEmpty()) local = java.io.File(localFile, name).absolutePath
        }
        startTransfer("recv") { onLine, onExit -> service.startFileRecv(current, remote, local, onLine, onExit) }
    }

    private fun startTransfer(
        kind: String,
        starter: ((String) -> Unit, (Int) -> Unit) -> HdcStream?
    ) {
        stopCurrent()
        val generation = ++transferGeneration
        statusLabel.text = if (kind == "send") "正在发送…" else "正在接收…"
        sendButton.isEnabled = false
        receiveButton.isEnabled = false
        stopButton.isEnabled = true
        stream = starter(
            { line -> outputArea.append(line + "\n"); scrollEnd() },
            { code ->
                if (generation != transferGeneration) return@starter
                stream = null
                sendButton.isEnabled = target != null
                receiveButton.isEnabled = target != null
                stopButton.isEnabled = false
                statusLabel.text = if (code == 0) "传输完成" else "传输失败（退出码 $code）"
            }
        )
        if (stream == null) {
            sendButton.isEnabled = target != null
            receiveButton.isEnabled = target != null
            stopButton.isEnabled = false
            statusLabel.text = "无法启动传输"
        }
    }

    private fun stopCurrent() {
        val current = stream ?: return
        transferGeneration++
        stream = null
        current.stop()
        stopButton.isEnabled = false
        statusLabel.text = "已停止"
        sendButton.isEnabled = target != null
        receiveButton.isEnabled = target != null
    }

    private fun scrollEnd() {
        outputArea.caretPosition = outputArea.document.length
    }
}

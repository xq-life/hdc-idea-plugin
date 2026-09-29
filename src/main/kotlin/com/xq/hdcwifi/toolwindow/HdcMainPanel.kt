package com.xq.hdcwifi.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.xq.hdcwifi.model.DeviceConnectionState
import com.xq.hdcwifi.model.DeviceGroups
import com.xq.hdcwifi.model.HdcDevice
import com.xq.hdcwifi.model.deviceSignature
import com.xq.hdcwifi.service.HdcScanHandle
import com.xq.hdcwifi.service.HdcScanResult
import com.xq.hdcwifi.service.HdcService
import com.xq.hdcwifi.service.HdcToolNotFoundException
import com.xq.hdcwifi.settings.HdcSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.KeyboardFocusManager
import java.awt.LayoutManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.HierarchyEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSplitPane
import javax.swing.JToggleButton
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/** How a connection attempt was started; manual attempts always win over queued automatic ones. */
private enum class ConnectionOrigin { MANUAL, AUTO_SAVED, AUTO_DISCOVERED }

/** Connection state plus the raw failure text for one target. */
private data class Status(val state: DeviceConnectionState, val error: String? = null)

class HdcMainPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = HdcService.instance
    private val settings = HdcSettings.instance

    private val devicesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
    }
    private val deviceScroll = ScrollPaneFactory.createScrollPane(devicesPanel, true).apply {
        border = JBUI.Borders.empty()
    }
    private val console = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        border = JBUI.Borders.empty(8)
        // Log lines get long — a scan summary lists every range and port it covered — so they wrap
        // to the panel width instead of being clipped and forcing horizontal scrolling.
        lineWrap = true
        wrapStyleWord = true
    }
    private val consoleScroll = ScrollPaneFactory.createScrollPane(console).apply {
        border = JBUI.Borders.empty()
        minimumSize = Dimension(0, 90)
        preferredSize = Dimension(500, 220)
    }
    private val consolePanel = JPanel(BorderLayout())
    private val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, deviceScroll, consolePanel).apply {
        resizeWeight = 0.72
        dividerSize = JBUI.scale(5)
        isContinuousLayout = true
        border = JBUI.Borders.empty()
        // A bare JSplitPane divider takes its colour from the LAF's SplitPane.background, which
        // under the dark theme stays at the light default: it painted as a bright white bar.
        // Paint the grab strip in the panel colour with a hairline separator down its middle.
        setUI(object : BasicSplitPaneUI() {
            override fun createDefaultDivider(): BasicSplitPaneDivider = object : BasicSplitPaneDivider(this) {
                override fun paint(g: Graphics) {
                    g.color = UIUtil.getPanelBackground()
                    g.fillRect(0, 0, width, height)
                    g.color = UIUtil.getBoundsColor()
                    g.fillRect(0, height / 2, width, 1)
                }
            }
        })
    }

    private val states = mutableMapOf<String, Status>()
    private val detailRequests = mutableMapOf<String, Long>()
    private val autoQueue = ArrayDeque<Pair<String, ConnectionOrigin>>()
    private val autoAttempted = mutableSetOf<String>()
    private val autoSuppressed = mutableSetOf<String>()

    private var connected: List<String> = emptyList()
    private var available: List<String> = emptyList()
    private var scanCompleted = false
    private var scanStatus: String? = null
    private var scanHandle: HdcScanHandle? = null
    private var scanGeneration = 0L
    private var refreshGeneration = 0L
    private var refreshInFlight = false
    private var refreshRequested = false
    private var connectionInFlight: String? = null
    private var disposed = false
    private var hasBeenShown = false
    private var wasShowing = false
    private var savedAutoConnectPending = false
    private var hdcAvailable = true
    private var scanStatusTimer: javax.swing.Timer? = null
    private var consoleVisible = true
    private var availableExpanded = true
    private var connectedExpanded = true
    private var previousExpanded = true
    private var lastSignature = ""
    private var autoTimer: javax.swing.Timer? = null

    private var animationFrame = 0

    /** Drives the Connecting spinner. Started and stopped by [syncAnimationTimer]. */
    private val animationTimer = javax.swing.Timer(ANIMATION_INTERVAL_MS) {
        animationFrame = (animationFrame + 1) % SPINNERS.size
        devicesPanel.repaint()
    }

    private lateinit var scanButton: JButton
    private lateinit var refreshButton: JButton
    private lateinit var autoButton: JToggleButton
    private lateinit var updatedLabel: JBLabel
    private lateinit var scanStatusLabel: JBLabel

    private val commandListener: (String) -> Unit = { appendConsole(it) }

    init {
        preferredSize = Dimension(680, 720)

        consolePanel.add(JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(38), 0)
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, UIUtil.getBoundsColor())
            add(iconButton(AllIcons.Actions.GC, "清空命令输出") { console.text = "" }, BorderLayout.NORTH)
        }, BorderLayout.WEST)
        consolePanel.add(consoleScroll, BorderLayout.CENTER)

        add(toolbar(), BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)

        service.addCommandListener(commandListener)

        // The tool window keeps one panel instance for its lifetime, so becoming visible again
        // is the only signal that the list may have gone stale while it was hidden.
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                val showing = isShowing
                if (showing && !wasShowing) {
                    refreshDevices(manual = false)
                }
                wasShowing = showing
                if (showing) hasBeenShown = true
            }
        }

        render(force = true)
        refreshDevices(manual = false)
        configureAutoRefresh()

        // Deferred until the first target listing, so devices that are already connected are
        // not needlessly connected a second time.
        savedAutoConnectPending = settings.autoConnectSaved
    }

    private fun toolbar(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2)).apply {
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, UIUtil.getBoundsColor())
        add(iconButton(AllIcons.General.Add, "添加 HDC 设备") { showConnectDialog() })

        scanButton = iconButton(AllIcons.Actions.Search, "Scan for devices") {
            if (scanHandle == null) startScan() else cancelScan()
        }
        add(scanButton)

        add(iconButton(AllIcons.Debugger.Console, "显示或隐藏命令输出") { toggleConsole() })

        refreshButton = iconButton(AllIcons.Actions.Refresh, "刷新设备列表") { refreshDevices(manual = true) }
        add(refreshButton)

        autoButton = toggleButton(AllIcons.Actions.Refresh, "自动刷新") {
            settings.autoRefreshSeconds = if (settings.autoRefreshSeconds == 0) DEFAULT_REFRESH_SECONDS else 0
            configureAutoRefresh()
        }
        add(autoButton)
        add(iconButtonWithSource(AllIcons.General.ArrowDown, "选择自动刷新间隔") { button ->
            showRefreshMenu(button)
        })

        add(iconButton(AllIcons.General.GearPlain, "HDC Wi-Fi 设置") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "HDC Wi-Fi")
            configureAutoRefresh()
            refreshDevices(manual = false)
        })

        updatedLabel = JBLabel("最后更新 -").apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(8)
        }
        add(updatedLabel)

        // Scan progress and its result need a home of their own: the Available group's empty
        // text disappears as soon as a scan returns anything, which used to hide progress.
        scanStatusLabel = JBLabel().apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(8)
            isVisible = false
        }
        add(scanStatusLabel)
    }

    private fun iconButton(icon: Icon, tooltip: String, action: () -> Unit): JButton =
        iconButtonWithSource(icon, tooltip) { action() }

    /** Same as [iconButton] but hands the created button to the action, for menus anchored to it. */
    private fun iconButtonWithSource(icon: Icon, tooltip: String, action: (JButton) -> Unit): JButton =
        JButton(icon).apply {
            toolTipText = tooltip
            accessibleContext.accessibleName = tooltip
            preferredSize = Dimension(JBUI.scale(30), JBUI.scale(28))
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusable = true
            addActionListener { action(this) }
        }

    /**
     * A toggle keeps the platform's selected background, so its on/off state stays visible.
     * A flat icon button would swallow the selected state and look identical either way.
     * No fixed size: the button also carries the current interval as text.
     */
    private fun toggleButton(icon: Icon, tooltip: String, action: () -> Unit): JToggleButton =
        JToggleButton(icon).apply {
            toolTipText = tooltip
            accessibleContext.accessibleName = tooltip
            margin = JBUI.insets(2, 6)
            isContentAreaFilled = true
            isBorderPainted = true
            isFocusable = true
            addActionListener { action() }
        }

    private fun showRefreshMenu(source: Component) {
        JPopupMenu().apply {
            val current = settings.autoRefreshSeconds
            // A header states the current setting; the list below changes it.
            add(JMenuItem(if (current == 0) "自动刷新：关闭" else "自动刷新：${current}s").apply {
                isEnabled = false
            })
            addSeparator()
            REFRESH_INTERVALS.forEach { seconds ->
                val label = if (seconds == 0) "关闭" else "${seconds}s"
                val checked = if (seconds == current) "\u2713 " else ""
                add(JMenuItem("$checked$label").apply {
                    addActionListener {
                        settings.autoRefreshSeconds = seconds
                        configureAutoRefresh()
                    }
                })
            }
            show(source, 0, source.height)
        }
    }

    private fun refreshDevices(manual: Boolean) {
        // Automatic refreshes are skipped while the tool window is hidden; the initial load
        // runs before the panel has ever been shown, which is why `hasBeenShown` gates it.
        if (disposed || (!manual && !isShowing && hasBeenShown)) return
        if (refreshInFlight) {
            refreshRequested = true
            return
        }
        refreshInFlight = true
        refreshButton.isEnabled = false
        val generation = ++refreshGeneration
        service.listTargets(manual) { result ->
            if (disposed || generation != refreshGeneration) return@listTargets
            refreshInFlight = false
            refreshButton.isEnabled = true
            result.fold(
                onSuccess = { targets ->
                    connected = targets.distinct()
                    connected.filter { it.contains(':') }.forEach {
                        settings.rememberDevice(it)
                        states[it] = Status(DeviceConnectionState.CONNECTED)
                    }
                    states.filterValues { it.state == DeviceConnectionState.CONNECTED }
                        .keys
                        .filter { it !in connected }
                        .forEach { states[it] = Status(DeviceConnectionState.DISCONNECTED) }
                    setScanAvailable(true)
                    updatedLabel.text = "最后更新 ${SimpleDateFormat("HH:mm:ss").format(Date())}"
                    render()
                    loadMissingDetails(groups().connected)
                    if (savedAutoConnectPending) {
                        savedAutoConnectPending = false
                        enqueueAuto(settings.savedDevices().map { it.address }, ConnectionOrigin.AUTO_SAVED)
                    }
                },
                onFailure = { error ->
                    val message = if (error is HdcToolNotFoundException) {
                        setScanAvailable(false)
                        "未找到 hdc 可执行文件。请在「设置 > 工具 > HDC Wi-Fi」中配置。"
                    } else {
                        "刷新失败：${error.message}"
                    }
                    appendConsole(message)
                }
            )
            if (refreshRequested) {
                refreshRequested = false
                refreshDevices(manual = false)
            }
        }
    }

    private fun groups(): DeviceGroups {
        val saved = settings.savedDevices().associateBy { it.address }
        // A target the service already confirmed as connected stays online even if the most
        // recent `list targets` has not caught up yet; otherwise it would briefly appear as
        // "已连接" while sitting under the previously-connected group.
        val onlineAddresses = (
            connected + states.filterValues { it.state == DeviceConnectionState.CONNECTED }.keys
            ).distinct()
        val online = onlineAddresses
            .map { withStatus(saved[it] ?: HdcDevice(it), DeviceConnectionState.CONNECTED) }
            .sortedBy { it.name.lowercase() }
        val previous = saved.values
            .filter { it.address !in onlineAddresses }
            .map { withStatus(it) }
            .sortedBy { it.name.lowercase() }
        val found = available
            .filter { it !in onlineAddresses && it !in saved }
            .map { withStatus(HdcDevice(it)) }
            .sortedBy { it.address }
        return DeviceGroups(online, previous, found)
    }

    private fun withStatus(device: HdcDevice, forced: DeviceConnectionState? = null): HdcDevice {
        val status = states[device.address]
        val value = forced ?: status?.state ?: DeviceConnectionState.DISCONNECTED
        return device.copy(
            connected = value == DeviceConnectionState.CONNECTED,
            state = value,
            failureReason = status?.error
        )
    }

    private fun render(force: Boolean = false) {
        syncAnimationTimer()
        val groups = groups()
        val signature = deviceSignature(groups) +
            ":$scanStatus:$availableExpanded:$connectedExpanded:$previousExpanded"
        if (!force && signature == lastSignature) return
        lastSignature = signature

        applyScanStatus()

        val scroll = deviceScroll.verticalScrollBar.value
        // Rebuilding drops focus, so a keyboard user would have to Tab back to the section
        // header after every keypress. Remember which section held focus and restore it.
        val focusedSection = (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner)
            ?.takeIf { SwingUtilities.isDescendingFrom(it, devicesPanel) }
            ?.let { (it as? JComponent)?.getClientProperty(FOCUS_SECTION_KEY) as? String }
        devicesPanel.removeAll()
        addGroup(
            "网络上的可用设备", groups.available, availableExpanded,
            { availableExpanded = !availableExpanded; render(force = true) },
            if (scanCompleted) emptyScanText() else "点击 Scan for devices 扫描网络中的设备。",
            showForget = false
        )
        addGroup(
            "已连接设备", groups.connected, connectedExpanded,
            { connectedExpanded = !connectedExpanded; render(force = true) },
            "没有已连接的设备。",
            showForget = false
        )
        addGroup(
            "之前连接过的设备", groups.previous, previousExpanded,
            { previousExpanded = !previousExpanded; render(force = true) },
            "没有之前连接过的设备。",
            showForget = true
        )
        devicesPanel.add(Box.createVerticalGlue())
        devicesPanel.revalidate()
        devicesPanel.repaint()
        SwingUtilities.invokeLater {
            deviceScroll.verticalScrollBar.value = scroll
            if (focusedSection != null) {
                findSectionHeader(devicesPanel, focusedSection)?.requestFocusInWindow()
            }
        }
    }

    private fun addGroup(
        title: String,
        items: List<HdcDevice>,
        expanded: Boolean,
        toggle: () -> Unit,
        emptyText: String,
        showForget: Boolean
    ) {
        devicesPanel.add(sectionHeader(title, items.size, expanded, toggle))
        if (!expanded) return
        if (items.isEmpty()) {
            devicesPanel.add(FixedHeightPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(JBLabel(emptyText).apply {
                    border = JBUI.Borders.empty(12, 40)
                    foreground = UIUtil.getContextHelpForeground()
                }, BorderLayout.CENTER)
            })
        } else {
            items.forEach { devicesPanel.add(deviceRow(it, showForget)) }
        }
    }

    /**
     * What a completed scan with no hits actually covered. Claiming "local networks" after a
     * scan that only looked at a custom range would be plainly wrong.
     */
    private fun emptyScanText(): String {
        val saved = settings.savedDevices().isNotEmpty()
        val custom = settings.customScanCidr.isNotBlank()
        return when {
            saved && custom -> "在本地网络、已保存地址和自定义网段中都没有找到设备。"
            custom -> "在本地网络和自定义网段中都没有找到设备。"
            saved -> "在本地网络和已保存地址中都没有找到设备。"
            else -> "在本地网络中没有找到设备。"
        }
    }

    /** Finds a rebuilt section header by its section key, so focus can be restored to it. */
    private fun findSectionHeader(container: Container, title: String): Component? {
        for (component in container.components) {
            if ((component as? JComponent)?.getClientProperty(FOCUS_SECTION_KEY) == title) return component
            if (component is Container) {
                findSectionHeader(component, title)?.let { return it }
            }
        }
        return null
    }

    /** Section header: a heading strip, keyboard-operable so it is not mouse-only. */
    private fun sectionHeader(title: String, count: Int, expanded: Boolean, toggle: () -> Unit): JPanel {
        val text = if (count > 0) "$title ($count)" else title
        val header = SectionHeaderPanel().apply {
            background = UIUtil.getTreeSelectionBackground(true)
            border = JBUI.Borders.empty(5, 10)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
            alignmentX = Component.LEFT_ALIGNMENT
            isFocusable = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = if (expanded) "折叠 $title" else "展开 $title"
            accessibleContext.accessibleName = text
            putClientProperty(FOCUS_SECTION_KEY, title)
        }
        header.add(JBLabel(text), BorderLayout.WEST)
        header.add(JBLabel(if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight), BorderLayout.EAST)
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = toggle()
        })
        val toggleAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = toggle()
        }
        header.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), TOGGLE_ACTION)
        header.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), TOGGLE_ACTION)
        header.actionMap.put(TOGGLE_ACTION, toggleAction)
        // Left collapses and Right expands, matching the tree/accordion convention. Both only act
        // in their own direction, so pressing Right on an expanded header is a no-op rather than
        // a collapse.
        header.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), EXPAND_ACTION)
        header.actionMap.put(EXPAND_ACTION, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (!expanded) toggle()
            }
        })
        header.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), COLLAPSE_ACTION)
        header.actionMap.put(COLLAPSE_ACTION, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (expanded) toggle()
            }
        })
        return header
    }

    private fun deviceRow(device: HdcDevice, showForget: Boolean): JPanel {
        val failureTip = device.failureReason?.let { "连接失败：${it.ifBlank { "未知错误。" }}" }
        val online = device.state == DeviceConnectionState.CONNECTED
        val connecting = device.state == DeviceConnectionState.CONNECTING

        val text = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel(device.name).apply {
                font = font.deriveFont(Font.BOLD)
                toolTipText = device.name
            })
            add(JBLabel(statusText(device.state)).apply {
                foreground = statusColor(device.state)
                toolTipText = failureTip
            })
            add(JBLabel(device.details).apply {
                foreground = UIUtil.getContextHelpForeground()
                toolTipText = device.details
            })
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            // Connect is the primary action; Disconnect must not look like it.
            add(actionButton(
                text = if (online) "Disconnect" else "Connect",
                icon = if (online) AllIcons.Actions.Suspend else AllIcons.Actions.Execute,
                secondary = online
            ) {
                if (online) disconnect(device) else requestConnection(device.address, ConnectionOrigin.MANUAL)
            }.apply { isEnabled = !connecting })

            if (showForget && !online) {
                add(iconButton(AllIcons.General.Remove, "Forget device ${device.address}") { forget(device) }
                    .apply { isEnabled = !connecting })
            }
            add(moreButton(device).apply { isEnabled = !connecting })
        }

        return FixedHeightPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12)
            alignmentX = Component.LEFT_ALIGNMENT
            toolTipText = failureTip
            add(JBLabel(StatusIcon(device.state) { animationFrame }).apply { toolTipText = failureTip }, BorderLayout.WEST)
            add(text, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }
    }

    private fun statusText(state: DeviceConnectionState): String = when (state) {
        DeviceConnectionState.CONNECTED -> "已连接"
        DeviceConnectionState.CONNECTING -> "连接中\u2026"
        DeviceConnectionState.FAILED -> "失败"
        DeviceConnectionState.DISCONNECTED -> "未连接"
    }

    private fun statusColor(state: DeviceConnectionState): Color = when (state) {
        DeviceConnectionState.CONNECTED -> SUCCESS
        DeviceConnectionState.FAILED -> UIUtil.getErrorForeground()
        else -> UIUtil.getContextHelpForeground()
    }

    /**
     * Connect is the primary action; Disconnect is a filled, error-tinted chip so the two can
     * never be confused. The fill is painted here rather than through `background` because a
     * button's background fill depends on the look and feel honouring `isOpaque`, which differs
     * between themes — painting it directly behaves the same everywhere.
     */
    private fun actionButton(text: String, icon: Icon, secondary: Boolean, action: () -> Unit): JButton =
        object : JButton(text, icon) {
            override fun paintComponent(g: Graphics) {
                if (secondary) {
                    val chip = g.create()
                    try {
                        val radius = JBUI.scale(8)
                        // A disabled Disconnect only greys its text unless the fill fades too, which
                        // reads as "still clickable".
                        chip.color = if (isEnabled) {
                            disconnectBackground()
                        } else {
                            blend(UIUtil.getPanelBackground(), disconnectBackground(), DISABLED_CHIP_FADE)
                        }
                        chip.fillRoundRect(0, 0, width, height, radius, radius)
                        val error = UIUtil.getErrorForeground()
                        if (isEnabled && (model.isRollover || model.isPressed)) {
                            chip.color = Color(error.red, error.green, error.blue, if (model.isPressed) 72 else 34)
                            chip.fillRoundRect(0, 0, width, height, radius, radius)
                        }
                    } finally {
                        chip.dispose()
                    }
                }
                super.paintComponent(g)
            }
        }.apply {
            foreground = if (secondary) UIUtil.getLabelForeground() else SUCCESS
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            isFocusPainted = false
            isFocusable = true
            border = JBUI.Borders.empty(3, 10)
            addActionListener { action() }
        }

    /**
     * A solid tint of the theme's error colour over the panel background, so it follows light,
     * dark and high-contrast themes instead of being a fixed colour.
     */
    private fun disconnectBackground(): Color {
        val base = UIUtil.getPanelBackground()
        val light = base.red + base.green + base.blue > 3 * 128
        return blend(base, UIUtil.getErrorForeground(), if (light) 0.16 else 0.34)
    }

    private fun blend(base: Color, tint: Color, ratio: Double): Color = Color(
        (base.red + (tint.red - base.red) * ratio).toInt().coerceIn(0, 255),
        (base.green + (tint.green - base.green) * ratio).toInt().coerceIn(0, 255),
        (base.blue + (tint.blue - base.blue) * ratio).toInt().coerceIn(0, 255)
    )

    private fun moreButton(device: HdcDevice): JButton {
        lateinit var button: JButton
        button = iconButton(AllIcons.Actions.More, "${device.address} 的更多操作") { showMore(device, button) }
        return button
    }

    private fun showConnectDialog() {
        val dialog = ConnectDeviceDialog()
        if (!dialog.showAndGet()) return
        requestConnection("${dialog.host}:${dialog.port}", ConnectionOrigin.MANUAL)
    }

    /**
     * Single entry point for every connection attempt, manual or automatic, so all of them
     * produce the same visible state transitions. Only one attempt runs at a time; the rest
     * queue up, with manual requests jumping ahead of automatic ones.
     */
    private fun requestConnection(address: String, origin: ConnectionOrigin) {
        if (disposed || states[address]?.state == DeviceConnectionState.CONNECTING) return
        if (origin != ConnectionOrigin.MANUAL && address in autoSuppressed) return
        if (connectionInFlight != null) {
            if (origin == ConnectionOrigin.MANUAL) {
                // A manual click must never be silently dropped: replace any queued entry for
                // this target and move it to the front.
                autoQueue.removeAll { it.first == address }
                autoQueue.addFirst(address to origin)
            } else {
                // Never queue the same target twice: a double click must not run two `hdc tconn`s.
                if (autoQueue.any { it.first == address }) return
                autoQueue.addLast(address to origin)
            }
            return
        }
        if (origin == ConnectionOrigin.MANUAL) autoSuppressed.remove(address)
        connectionInFlight = address
        states[address] = Status(DeviceConnectionState.CONNECTING)
        render()

        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull() ?: settings.defaultPort
        service.connect(host, port) connectionResult@{ ok, message ->
            if (disposed) return@connectionResult
            connectionInFlight = null
            if (ok) {
                states[address] = Status(DeviceConnectionState.CONNECTED)
                settings.rememberDevice(address)
                settings.rememberHost(host)
                connected = (connected + address).distinct()
                available = available - address
            } else {
                states[address] = Status(DeviceConnectionState.FAILED, message)
                appendConsole("连接失败：${message.ifBlank { "未知错误。" }}")
            }
            render()
            if (ok) refreshDevices(manual = false)
            runNextAuto()
        }
    }

    private fun enqueueAuto(addresses: List<String>, origin: ConnectionOrigin) {
        addresses.distinct()
            .sorted()
            .filter { it !in autoAttempted && it !in autoSuppressed && it !in connected }
            .forEach {
                autoAttempted.add(it)
                autoQueue.addLast(it to origin)
            }
        runNextAuto()
    }

    private fun runNextAuto() {
        if (connectionInFlight != null) return
        while (autoQueue.isNotEmpty()) {
            val (address, origin) = autoQueue.removeFirst()
            // The user may have disconnected this target after it was queued; that wins.
            if (origin != ConnectionOrigin.MANUAL && address in autoSuppressed) continue
            requestConnection(address, origin)
            return
        }
    }

    private fun disconnect(device: HdcDevice) {
        // An explicit disconnect must not be undone by a queued automatic reconnect.
        autoSuppressed.add(device.address)
        service.disconnect(device.address) disconnectResult@{ ok, message ->
            if (disposed) return@disconnectResult
            if (!ok) appendConsole("断开失败：$message")
            connected = connected - device.address
            states[device.address] = Status(DeviceConnectionState.DISCONNECTED)
            render()
            refreshDevices(manual = false)
        }
    }

    private fun forget(device: HdcDevice) {
        settings.removeDevice(device.address)
        states.remove(device.address)
        render()
    }

    private fun showMore(device: HdcDevice, source: Component) {
        JPopupMenu().apply {
            if (device.state == DeviceConnectionState.CONNECTED) {
                add(JMenuItem("Device tools...").apply {
                    addActionListener { DeviceToolsDialog(device.address, service).show() }
                })
                add(JMenuItem("Disconnect").apply { addActionListener { disconnect(device) } })
                addSeparator()
            }
            add(JMenuItem("Copy address").apply {
                addActionListener {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(device.address), null)
                }
            })
            show(source, 0, source.height)
        }
    }

    private fun startScan() {
        val generation = ++scanGeneration
        appendConsole(
            "开始扫描：默认端口 ${settings.defaultPort}，" +
                "附加端口 [${settings.scanPorts.ifBlank { "无" }}]，" +
                "自定义网段 [${settings.customScanCidr.ifBlank { "无" }}]"
        )
        setScanStatus("已扫描 0/\u2026")
        scanButton.icon = AllIcons.Actions.Suspend
        render()
        scanHandle = service.scanDevices(
            savedAddresses = settings.savedDevices().map { it.address },
            port = settings.defaultPort,
            customCidr = settings.customScanCidr,
            timeoutMs = SCAN_TIMEOUT_MS,
            extraPorts = settings.scanPorts,
            progress = { progress ->
                // Only the status label changes here; a full render per probe would rebuild the
                // device list thousands of times during one scan.
                if (!disposed && generation == scanGeneration) {
                    setScanStatus("已扫描 ${progress.completed}/${progress.total}")
                }
            },
            callback = { result ->
                if (disposed || generation != scanGeneration) return@scanDevices
                scanHandle = null
                scanButton.icon = AllIcons.Actions.Search
                // Restores enabled state and the tooltip for the current hdc availability.
                refreshScanButton()
                result.fold(
                    onSuccess = { scan ->
                        available = scan.addresses
                        // Only a scan that actually ran may let the group claim nothing was found;
                        // a failed scan must not look like an empty network.
                        scanCompleted = true
                        // The scan log goes to the console so a device that is on the network but
                        // missing from the list can be diagnosed: the ranges and ports below say
                        // exactly what was and was not covered.
                        appendConsole(scanSummary(scan, found = scan.addresses.size))
                        // A partial scan (e.g. local ranges unreadable) is worth recording even
                        // when it did find something.
                        scan.description?.let { appendConsole(it) }
                        // The group's own empty text covers the "nothing found" case; only a
                        // positive result needs a transient notice in the toolbar.
                        setScanStatus(
                            if (scan.addresses.isEmpty()) null
                            else "找到 ${scan.addresses.size} 台设备。",
                            autoClearMs = SCAN_NOTICE_MS
                        )
                        render()
                        if (settings.autoConnectDiscovered) {
                            enqueueAuto(scan.addresses, ConnectionOrigin.AUTO_DISCOVERED)
                        }
                    },
                    onFailure = { error ->
                        val message = error.message ?: "无法扫描网络。"
                        appendConsole("扫描未执行：$message")
                        setScanStatus(message, autoClearMs = SCAN_ERROR_MS)
                        render()
                    }
                )
            }
        )
        refreshScanButton()
    }

    private fun cancelScan() {
        scanGeneration++
        scanHandle?.cancel()
        scanHandle = null
        // Deliberately not marking the scan as completed: a cancelled scan must not leave the
        // group claiming nothing was found.
        scanButton.icon = AllIcons.Actions.Search
        refreshScanButton()
        appendConsole("扫描已取消。")
        setScanStatus("扫描已取消。", autoClearMs = SCAN_NOTICE_MS)
        render()
    }

    /**
     * One console line describing what a scan actually covered. Printed for every scan, including
     * ones that found nothing: without the ranges and ports there is no way to tell "no device is
     * out there" apart from "we never looked where the device actually is".
     */
    private fun scanSummary(scan: HdcScanResult, found: Int): String {
        val ranges = if (scan.ranges.isEmpty()) "无本地网段" else scan.ranges.joinToString("、")
        val ports = if (scan.ports.isEmpty()) "无" else scan.ports.joinToString("、")
        return "扫描完成：网段 [$ranges] 端口 [$ports] 共探测 ${scan.probed} 个目标，找到 $found 台设备。"
    }

    /** Shows a scan notice in the toolbar, optionally clearing it again after [autoClearMs]. */
    /**
     * Applies the current scan status to its label. Kept separate from [render] because progress
     * arrives once per probe and rebuilding the whole device list that often would freeze the UI.
     */
    private fun applyScanStatus() {
        scanStatusLabel.text = scanStatus.orEmpty()
        scanStatusLabel.isVisible = scanStatus != null
    }

    private fun setScanStatus(text: String?, autoClearMs: Int? = null) {
        scanStatus = text
        scanStatusTimer?.stop()
        scanStatusTimer = null
        if (text != null && autoClearMs != null) {
            scanStatusTimer = javax.swing.Timer(autoClearMs) {
                scanStatus = null
                applyScanStatus()
            }.apply {
                isRepeats = false
                start()
            }
        }
        applyScanStatus()
    }

    /** Records whether hdc is usable; the scan button is pointless without it. */
    private fun setScanAvailable(available: Boolean) {
        hdcAvailable = available
        refreshScanButton()
    }

    /**
     * Derives the scan button's enabled state, tooltip and accessible name from the two facts
     * that matter. An in-flight scan stays cancellable even if hdc disappears mid-scan.
     */
    private fun refreshScanButton() {
        val scanning = scanHandle != null
        scanButton.isEnabled = hdcAvailable || scanning
        val tooltip = when {
            scanning -> "Cancel scan"
            !hdcAvailable -> "hdc 不可用，请在设置中配置。"
            else -> "Scan for devices"
        }
        scanButton.toolTipText = tooltip
        scanButton.accessibleContext.accessibleName = tooltip
    }

    /** Device details are expensive (one hdc call per property); fetch each target at most once per TTL. */
    private fun loadMissingDetails(devices: List<HdcDevice>) {
        val now = System.currentTimeMillis()
        devices
            .filter { it.name == it.address || it.systemVersion.isBlank() || it.apiVersion.isBlank() }
            .forEach { device ->
                if (now - (detailRequests[device.address] ?: 0L) < DETAIL_TTL_MS) return@forEach
                detailRequests[device.address] = now
                service.deviceInfo(device.address) { info ->
                    if (disposed) return@deviceInfo
                    settings.updateDevice(
                        device.address,
                        info["Model"].orEmpty().ifBlank { info["Product name"].orEmpty() },
                        info["System release"].orEmpty().ifBlank { info["Software version"].orEmpty() },
                        info["API version"].orEmpty()
                    )
                    render()
                }
            }
    }

    private fun configureAutoRefresh() {
        autoTimer?.stop()
        val seconds = settings.autoRefreshSeconds
        val on = seconds > 0
        autoButton.isSelected = on
        // The toolbar itself has to say whether polling is on and how often it fires; the
        // interval used to be visible only inside the drop-down.
        autoButton.text = if (on) "${seconds}s" else "关闭"
        autoButton.foreground = if (on) SUCCESS else UIUtil.getContextHelpForeground()
        autoButton.font = autoButton.font.deriveFont(if (on) Font.BOLD else Font.PLAIN)
        val tip = if (on) {
            "自动刷新已开启：每 ${seconds}s 一次。点击关闭。"
        } else {
            "自动刷新已关闭。点击后每 ${DEFAULT_REFRESH_SECONDS}s 刷新一次。"
        }
        autoButton.toolTipText = tip
        autoButton.accessibleContext.accessibleName = tip
        autoTimer = if (on) {
            javax.swing.Timer(seconds * 1000) { if (isShowing) refreshDevices(manual = false) }.apply { start() }
        } else {
            null
        }
    }

    /** The spinner costs a repaint per frame, so it only runs while something is actually connecting. */
    private fun syncAnimationTimer() {
        val needed = states.values.any { it.state == DeviceConnectionState.CONNECTING }
        if (needed && !animationTimer.isRunning) animationTimer.start()
        if (!needed && animationTimer.isRunning) animationTimer.stop()
    }

    private fun toggleConsole() {
        consoleVisible = !consoleVisible
        consolePanel.isVisible = consoleVisible
        splitPane.dividerSize = if (consoleVisible) JBUI.scale(5) else 0
        splitPane.resetToPreferredSizes()
    }

    private fun appendConsole(text: String) {
        console.append(if (console.text.isEmpty()) text else "\n$text")
        console.caretPosition = console.document.length
    }

    override fun dispose() {
        disposed = true
        refreshGeneration++
        scanGeneration++
        scanHandle?.cancel()
        autoTimer?.stop()
        animationTimer.stop()
        scanStatusTimer?.stop()
        service.removeCommandListener(commandListener)
    }

    /**
     * A panel that never grows taller than its own content. `BoxLayout` hands surplus vertical
     * space to every child that reports room to grow, and a plain `JPanel` reports the largest
     * possible maximum — so one device row used to swallow most of the viewport, stretching its
     * labels and pushing the following section off the bottom.
     */
    private class FixedHeightPanel(layout: LayoutManager) : JPanel(layout) {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /**
     * A collapsible group header. Swing exposes no setter for the accessible role, so it has to
     * be supplied by overriding the context; without this a screen reader announces a plain
     * panel and never mentions that the strip can be activated.
     */
    private class SectionHeaderPanel : JPanel(BorderLayout()) {
        override fun getAccessibleContext(): AccessibleContext {
            var context = accessibleContext
            if (context == null) {
                context = object : AccessibleJPanel() {
                    override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PUSH_BUTTON
                }
                accessibleContext = context
            }
            return context
        }
    }

    /** Theme-aware connection state indicator: shape and text carry the meaning, not colour alone. */
    private class StatusIcon(private val state: DeviceConnectionState, private val frame: () -> Int) : Icon {

        override fun getIconWidth(): Int = JBUI.scale(16)

        override fun getIconHeight(): Int = JBUI.scale(16)

        override fun paintIcon(component: Component?, g: Graphics, x: Int, y: Int) {
            g.color = when (state) {
                DeviceConnectionState.CONNECTED -> SUCCESS
                DeviceConnectionState.FAILED -> UIUtil.getErrorForeground()
                else -> UIUtil.getContextHelpForeground()
            }
            when (state) {
                DeviceConnectionState.CONNECTING -> SPINNERS[frame()].paintIcon(component, g, x, y)
                DeviceConnectionState.DISCONNECTED -> g.drawOval(x + 2, y + 2, iconWidth - 5, iconHeight - 5)
                else -> {
                    g.fillOval(x + 1, y + 1, iconWidth - 2, iconHeight - 2)
                    // Device rows are non-opaque on the devices panel, so the panel background
                    // is the surface showing through; draw the mark in it to read as a cut-out.
                    g.color = UIUtil.getPanelBackground()
                    g.drawString(if (state == DeviceConnectionState.CONNECTED) "\u2713" else "!", x + 3, y + iconHeight - 3)
                }
            }
        }
    }

    companion object {
        private val SUCCESS = JBColor(Color(0x2E7D32), Color(0x63B36B))
        private val SPINNERS = arrayOf(
            AllIcons.Process.Step_1, AllIcons.Process.Step_2, AllIcons.Process.Step_3, AllIcons.Process.Step_4,
            AllIcons.Process.Step_5, AllIcons.Process.Step_6, AllIcons.Process.Step_7, AllIcons.Process.Step_8
        )
        private val REFRESH_INTERVALS = listOf(0, 5, 10, 30, 60)
        private const val TOGGLE_ACTION = "hdc.toggleSection"
        private const val EXPAND_ACTION = "hdc.expandSection"
        private const val COLLAPSE_ACTION = "hdc.collapseSection"

        /** Client property identifying a section header, so focus survives a rebuild. */
        private const val FOCUS_SECTION_KEY = "hdc.sectionKey"
        private const val DEFAULT_REFRESH_SECONDS = 10
        private const val ANIMATION_INTERVAL_MS = 83
        private const val SCAN_TIMEOUT_MS = 300
        private const val SCAN_NOTICE_MS = 4000
        private const val SCAN_ERROR_MS = 8000
        private const val DETAIL_TTL_MS = 60_000L

        /** How much of the Disconnect chip colour survives while the button is disabled. */
        private const val DISABLED_CHIP_FADE = 0.35
    }
}

package com.xq.hdcwifi.toolwindow

import com.xq.hdcwifi.MainSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultButtonModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * UX-CONTRACT §8 v1.7: the three defects found by measuring the user's real-device screenshots.
 *
 * * U1 — the split-pane divider painted as a 5px bright white bar under the dark theme.
 * * U2 — a single device row was stretched to ~412px because `BoxLayout` hands slack to every child
 *   whose `maximumSize` is unbounded (`JPanel`/`JBLabel` default to `Short.MAX_VALUE`).
 * * U5 — the Disconnect button had no background, so it did not read as a distinct action.
 *
 * `HdcMainPanel` needs the IntelliJ application and a Project, so it cannot be constructed here.
 * Each item therefore gets two kinds of evidence: the source shape of the fix (which is what a
 * regression would touch) and, where Swing behaviour is the point, a *mirrored* control that
 * reproduces the production painting/layout logic and shows the consequence — including the negative
 * control that would fail if the fix were reverted. The authoritative visual check is still
 * `./gradlew runIde`.
 */
class PanelRenderingContractTest {

    // ------------------------------------------------------------------ U1: divider

    @Test
    fun `the split divider is painted with theme colours instead of the laf background`() {
        val ui = slice(MainSources.panel, "setUI(object : BasicSplitPaneUI()", "\n        })")
        assertTrue(ui.contains("override fun createDefaultDivider(): BasicSplitPaneDivider = object : BasicSplitPaneDivider(this)"))
        assertTrue(ui.contains("override fun paint(g: Graphics)"))
        assertTrue("the grab strip is filled with the panel colour", ui.contains("g.color = UIUtil.getPanelBackground()"))
        assertTrue(ui.contains("g.fillRect(0, 0, width, height)"))
        assertTrue("a hairline marks the split point", ui.contains("g.color = UIUtil.getBoundsColor()"))
        assertTrue(ui.contains("g.fillRect(0, height / 2, width, 1)"))
        assertFalse("the LAF's SplitPane.background is what painted the white bar", ui.contains("UIManager"))
        assertFalse("no component background is read any more", ui.contains("getBackground()"))
    }

    @Test
    fun `the divider ui keeps the basic behaviour it does not override`() {
        // Dragging is implemented by BasicSplitPaneUI's defaults and BasicSplitPaneDivider's own
        // listeners, not by paint(). Overriding only these two methods keeps installDefaults(),
        // installUI() and the drag listeners inherited — a `paint` override cannot break dragging.
        val ui = slice(MainSources.panel, "setUI(object : BasicSplitPaneUI()", "\n        })")
        assertEquals(
            "only createDefaultDivider and paint may be overridden",
            2, Regex("override fun").findAll(ui).count()
        )
        assertFalse(ui.contains("override fun installDefaults"))
        assertFalse(ui.contains("override fun installUI"))
        assertFalse(ui.contains("mousePressed"))
        // The divider is constructed with this UI, which the parameter type pins at compile time.
        assertTrue(ui.contains("BasicSplitPaneDivider(this)"))
        // The grab area stays 5px wide; only its painting changed.
        assertTrue(MainSources.panel.contains("dividerSize = JBUI.scale(5)"))
        assertTrue(MainSources.panel.contains("isContinuousLayout = true"))
    }

    // ------------------------------------------------------------------ U2: row height

    @Test
    fun `device rows and empty states are fixed height panels`() {
        val panel = MainSources.panel
        val fixed = MainSources.bodyOf(panel, "private class FixedHeightPanel")
        assertTrue(fixed.contains("override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)"))

        val row = MainSources.bodyOf(panel, "private fun deviceRow(")
        assertTrue("the device row must refuse the slack", row.contains("return FixedHeightPanel(BorderLayout(JBUI.scale(8), 0)).apply {"))

        val group = MainSources.bodyOf(panel, "private fun addGroup(")
        assertTrue("the empty-state text must refuse the slack too", group.contains("devicesPanel.add(FixedHeightPanel(BorderLayout()).apply {"))
        assertFalse("a bare JBLabel would be stretched", group.contains("devicesPanel.add(JBLabel(emptyText)"))
        assertTrue(group.contains("items.forEach { devicesPanel.add(deviceRow(it, showForget)) }"))
    }

    @Test
    fun `only the trailing glue may grow inside the device list`() {
        // The failure mode was that any directly added component with a default maximumSize shared
        // the leftover space with the glue. Every direct child is now fixed height or the glue.
        val panel = MainSources.panel
        val region = MainSources.bodyOf(panel, "private fun render(") + MainSources.bodyOf(panel, "private fun addGroup(")
        val heads = Regex("""devicesPanel\.add\(([A-Za-z]+)""").findAll(region).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("sectionHeader", "deviceRow", "FixedHeightPanel", "Box"), heads)
        assertTrue(MainSources.panel.contains("devicesPanel.add(Box.createVerticalGlue())"))
    }

    @Test
    fun `the section header keeps its own fixed maximum height`() {
        // Regression guard: FixedHeightPanel must not have been applied to the 30px header band,
        // whose height is deliberately pinned by the contract.
        val header = MainSources.bodyOf(MainSources.panel, "private fun sectionHeader(")
        assertTrue(header.contains("maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))"))
        assertFalse(header.contains("FixedHeightPanel"))
    }

    @Test
    fun `a fixed height panel refuses the slack that a plain panel swallows`() {
        // Negative control first: the production bug, reproduced with plain Swing.
        val plain = JPanel().apply { preferredSize = Dimension(100, 40) }
        val plainGlue = column(plain)
        assertTrue(
            "a plain JPanel is what swallowed 412px: its default maximumSize is unbounded",
            plain.height > plain.preferredSize.height
        )
        assertTrue("the glue must be the loser while a plain row grows", plainGlue.height < 560)

        // The fix: the mirrored production maximumSize keeps the row at its preferred height and
        // leaves the whole remainder to the trailing glue.
        val fixed = FixedHeightMirror()
        val glue = column(fixed)
        assertEquals(40, fixed.preferredSize.height)
        assertEquals("the row must keep its natural height", 40, fixed.height)
        assertEquals("all remaining space belongs to the glue", 560, glue.height)
    }

    /** A `BoxLayout.Y_AXIS` column of [child] plus the same trailing glue the panel uses. */
    private fun column(child: JComponent): Component {
        val glue = Box.createVerticalGlue()
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(child)
            add(glue)
            setSize(400, 600)
            doLayout()
        }
        return glue
    }

    /** Mirrors `HdcMainPanel.FixedHeightPanel`. */
    private class FixedHeightMirror : JPanel() {
        init {
            preferredSize = Dimension(100, 40)
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    // ------------------------------------------------------------------ U5: disconnect chip

    @Test
    fun `the disconnect chip is self painted with a themed error tint`() {
        val action = MainSources.bodyOf(MainSources.panel, "private fun actionButton(")
        assertTrue(action.contains("object : JButton(text, icon) {"))
        assertTrue(action.contains("override fun paintComponent(g: Graphics)"))
        assertTrue(action.contains("if (secondary) {"))
        assertTrue("the enabled chip is filled at full strength", action.contains("if (isEnabled) {"))
        assertTrue(action.contains("disconnectBackground()"))
        assertTrue(action.contains("chip.fillRoundRect(0, 0, width, height, radius, radius)"))
        assertTrue(
            "hover and press brighten the chip only while it is enabled",
            action.contains("if (isEnabled && (model.isRollover || model.isPressed))")
        )
        assertTrue(action.contains("if (model.isPressed) 72 else 34"))
        assertTrue(
            "the chip must be painted before the label is drawn over it",
            action.indexOf("chip.fillRoundRect") < action.indexOf("super.paintComponent(g)")
        )
        // Painting is LAF-independent on purpose: the fill must not depend on a component background.
        assertTrue(action.contains("isContentAreaFilled = false"))
        assertTrue(action.contains("isBorderPainted = false"))
        assertTrue(action.contains("isOpaque = false"))
        assertTrue(action.contains("border = JBUI.Borders.empty(3, 10)"))
        assertTrue(action.contains("foreground = if (secondary) UIUtil.getLabelForeground() else SUCCESS"))
    }

    @Test
    fun `the disabled disconnect chip fades and never brightens on hover`() {
        // v1.8 (B): a disabled Disconnect only greyed its text, so the full-strength chip still read
        // as clickable. The fill is now faded while disabled and the hover/press brighten is guarded.
        val action = MainSources.bodyOf(MainSources.panel, "private fun actionButton(")
        assertTrue(
            "the disabled chip must be a faded mix of the panel background and the full-strength chip",
            action.contains("blend(UIUtil.getPanelBackground(), disconnectBackground(), DISABLED_CHIP_FADE)")
        )
        assertTrue(
            "the fade ratio must be a named constant",
            MainSources.panel.contains("private const val DISABLED_CHIP_FADE = 0.35")
        )
        assertTrue(
            "the brighten must be behind the isEnabled guard",
            action.contains("if (isEnabled && (model.isRollover || model.isPressed))")
        )
        assertFalse(
            "an unguarded brighten would light a disabled chip up on hover",
            action.contains("if (model.isRollover || model.isPressed)")
        )
        assertFalse(
            "the fill must no longer be unconditional",
            action.contains("chip.color = disconnectBackground()")
        )

        // Arithmetic, recomputed here rather than copied: dark panel #1E1F22 + error #DB5C5C at 0.34
        // is the enabled chip, and the disabled chip is that result blended 0.35 back to the panel.
        val base = Color(0x1E1F22)
        val tint = Color(0xDB5C5C)
        val enabledChip = blend(base, tint, 0.34)
        val disabledChip = blend(base, enabledChip, 0.35)
        assertEquals(Color(0x5E3335), enabledChip)
        assertEquals(Color(0x342628), disabledChip)
        assertNotEquals("a disabled chip must not look like an enabled one", disabledChip, enabledChip)
        assertNotEquals("the fade must stay distinguishable from the background", disabledChip, base)

        // Pixel-level mirror: the enabled ring is full strength, the disabled ring is the fade.
        val y = 16
        val enabled = render(chip("Disconnect", secondary = true, base = base, tint = tint), base)
        val disabled = render(chip("Disconnect", secondary = true, base = base, tint = tint, enabled = false), base)
        assertEquals("the enabled chip must be tinted with the error blend", enabledChip, Color(enabled.getRGB(1, y)))
        assertEquals("the disabled chip must be faded", disabledChip, Color(disabled.getRGB(1, y)))
        assertNotEquals(Color(enabled.getRGB(1, y)), Color(disabled.getRGB(1, y)))

        // Hover on a disabled chip stays faded: the production mirror ignores the rollover.
        val disabledHover = render(
            chip("Disconnect", secondary = true, base = base, tint = tint, enabled = false, rollover = true), base
        )
        assertEquals(
            "a disabled chip must not light up on hover",
            disabledChip, Color(disabledHover.getRGB(1, y))
        )
        // Negative control: the same paint without the `isEnabled &&` guard does change the pixel, so
        // the assertion above is only meaningful because of the guard.
        val unguardedHover = render(
            chip(
                "Disconnect", secondary = true, base = base, tint = tint,
                enabled = false, guardEnabledCheck = false, rollover = true
            ),
            base
        )
        assertNotEquals(
            "without the isEnabled guard the disabled chip would brighten (regression control)",
            disabledChip, Color(unguardedHover.getRGB(1, y))
        )
    }

    @Test
    fun `the disconnect tint follows the theme instead of a fixed colour`() {
        val tint = MainSources.bodyOf(MainSources.panel, "private fun disconnectBackground()")
        assertTrue(tint.contains("val base = UIUtil.getPanelBackground()"))
        assertTrue(tint.contains("UIUtil.getErrorForeground()"))
        assertTrue("the ratio is chosen per theme brightness", tint.contains("if (light) 0.16 else 0.34"))

        val blend = MainSources.bodyOf(MainSources.panel, "private fun blend(")
        assertTrue(blend.contains("(base.red + (tint.red - base.red) * ratio).toInt().coerceIn(0, 255)"))
        assertTrue(blend.contains("(base.green + (tint.green - base.green) * ratio).toInt().coerceIn(0, 255)"))
        assertTrue(blend.contains("(base.blue + (tint.blue - base.blue) * ratio).toInt().coerceIn(0, 255)"))
    }

    @Test
    fun `the disconnect chip paints a ring where the connect button stays flat`() {
        // The probe the fix was validated with: dark panel #1E1F22 + error #DB5C5C at 0.34 = #5E3335.
        val base = Color(0x1E1F22)
        val tint = Color(0xDB5C5C)
        assertEquals(Color(0x5E3335), blend(base, tint, 0.34))
        assertNotEquals("the light theme uses a different mix", blend(base, tint, 0.16), blend(base, tint, 0.34))

        val disconnect = render(chip("Disconnect", secondary = true, base = base, tint = tint), base)
        val connect = render(chip("Connect", secondary = false, base = base, tint = tint), base)
        val y = disconnect.height / 2
        val chipRing = Color(disconnect.getRGB(1, y))
        val flatRing = Color(connect.getRGB(1, y))

        assertEquals("the disconnect chip must be tinted with the error blend", blend(base, tint, 0.34), chipRing)
        assertEquals("the connect button must stay flat", base, flatRing)
        assertNotEquals("the two actions must not look alike", chipRing, flatRing)
        assertNotEquals("the chip must differ from the panel background", chipRing, base)
        assertEquals("the chip must be opaque", 255, (disconnect.getRGB(1, y) ushr 24) and 0xFF)
    }

    @Test
    fun `the chip painting does not disturb the row action buttons`() {
        // The self-painted subclass must not touch the action/enabled wiring of the other buttons.
        val panel = MainSources.panel
        assertEquals(3, Regex("isEnabled = !connecting").findAll(panel).count())
        assertTrue(panel.contains("moreButton(device).apply { isEnabled = !connecting }"))
        assertTrue(panel.contains("""iconButton(AllIcons.General.Remove, "Forget device ${'$'}{device.address}") { forget(device) }"""))
        val more = MainSources.bodyOf(panel, "private fun moreButton(")
        assertTrue(more.contains("iconButton(AllIcons.Actions.More,"))
    }

    /**
     * Mirrors the secondary branch of `HdcMainPanel.actionButton`'s `paintComponent`, including the
     * v1.8 disabled fade and the `isEnabled` guard around the hover brighten. `guardEnabledCheck`
     * and `rollover` exist so the disabled-hover test can build its own regression control.
     */
    private fun chip(
        text: String,
        secondary: Boolean,
        base: Color,
        tint: Color,
        enabled: Boolean = true,
        guardEnabledCheck: Boolean = true,
        rollover: Boolean = false
    ): JButton =
        object : JButton(text) {
            override fun paintComponent(g: Graphics) {
                if (secondary) {
                    val chip = g.create()
                    try {
                        val radius = 8
                        chip.color = if (isEnabled) {
                            blend(base, tint, 0.34)
                        } else {
                            blend(base, blend(base, tint, 0.34), 0.35)
                        }
                        chip.fillRoundRect(0, 0, width, height, radius, radius)
                        val brighten = if (guardEnabledCheck) {
                            isEnabled && (model.isRollover || model.isPressed)
                        } else {
                            model.isRollover || model.isPressed
                        }
                        if (brighten) {
                            chip.color = Color(tint.red, tint.green, tint.blue, if (model.isPressed) 72 else 34)
                            chip.fillRoundRect(0, 0, width, height, radius, radius)
                        }
                    } finally {
                        chip.dispose()
                    }
                }
                super.paintComponent(g)
            }
        }.apply {
            // `DefaultButtonModel.setRollover(true)` is a no-op while the button is disabled - the
            // model refuses to enter rollover when it is not enabled - so a disabled chip cannot be
            // put into hover through the setter. The mirror therefore reports the forced state from
            // the model itself; otherwise both the guarded and the unguarded run would paint the
            // plain disabled chip and neither the "stays faded on hover" assertion nor its negative
            // control would test anything.
            model = object : DefaultButtonModel() {
                override fun isRollover(): Boolean = rollover
            }
            isEnabled = enabled
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            isFocusPainted = false
            border = EmptyBorder(3, 10, 3, 10)
            size = Dimension(120, 32)
        }

    /** Mirrors `HdcMainPanel.blend`. */
    private fun blend(base: Color, tint: Color, ratio: Double): Color = Color(
        (base.red + (tint.red - base.red) * ratio).toInt().coerceIn(0, 255),
        (base.green + (tint.green - base.green) * ratio).toInt().coerceIn(0, 255),
        (base.blue + (tint.blue - base.blue) * ratio).toInt().coerceIn(0, 255)
    )

    private fun render(button: JButton, background: Color): BufferedImage {
        val image = BufferedImage(button.width, button.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = background
            graphics.fillRect(0, 0, image.width, image.height)
            button.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun slice(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        check(start >= 0) { "not found in source: $from" }
        val end = source.indexOf(to, start)
        check(end > start) { "not found after '$from': $to" }
        return source.substring(start, end)
    }
}

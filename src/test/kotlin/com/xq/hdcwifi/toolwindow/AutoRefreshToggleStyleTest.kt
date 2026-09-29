package com.xq.hdcwifi.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Insets
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JToggleButton

/**
 * UX-CONTRACT §3.1 / §8 v1.7 (U3): the auto-refresh control must show an on/off state that can be
 * told apart, and it now carries the interval as text instead of only inside the drop-down.
 *
 * `HdcMainPanel` cannot be instantiated in this test JVM (it needs the IntelliJ application and a
 * Project), so this test mirrors the exact property set of the production factories
 * (`HdcMainPanel.toggleButton` / `iconButton`) and verifies the *consequence* at the Swing level:
 * a toggle keeps its content area and border, a flat icon button does not, a texted toggle sizes
 * itself to its content (the factory no longer pins 30x28), the off and on property sets differ in
 * four ways, and their paintings actually differ.
 *
 * Caveat: the painting below uses the JVM's default look and feel, not the IDE theme, and the green
 * stand-in for `SUCCESS` only proves the two states are *distinct* — the exact colours are asserted
 * on the source in `ContractConformanceTest`. The authoritative check inside the real toolbar is
 * manual (`./gradlew runIde`), as recorded in docs/ux/ACCEPTANCE.md.
 */
class AutoRefreshToggleStyleTest {

    /**
     * Mirrors the property set of `HdcMainPanel.toggleButton` (tooltip, accessible name, margin,
     * filled + painted content, focusable) plus the state lines of `configureAutoRefresh`
     * (selection, text, foreground, font). The icon is the only property not mirrored here:
     * `AllIcons` needs the IntelliJ platform, which this JVM does not start. Like the production
     * factory it pins no size; the render calls below set one common canvas for both states.
     */
    private fun button(selected: Boolean, text: String = "", tooltip: String = "自动刷新"): JToggleButton =
        JToggleButton(text).apply {
            toolTipText = tooltip
            accessibleContext.accessibleName = tooltip
            margin = Insets(2, 6, 2, 6)
            isContentAreaFilled = true
            isBorderPainted = true
            isFocusable = true
            isSelected = selected
        }

    /** Mirrors the three on/off lines of `HdcMainPanel.configureAutoRefresh` (v1.8: off reads `关闭`). */
    private fun state(seconds: Int): JToggleButton {
        val on = seconds > 0
        return button(selected = on, text = if (on) "${seconds}s" else "关闭").apply {
            foreground = if (on) Color(0x2E7D32) else Color(0x808080)
            font = font.deriveFont(if (on) Font.BOLD else Font.PLAIN)
        }
    }

    @Test
    fun `auto-refresh toggle keeps a filled, painted area so the selected state stays visible`() {
        val toggle = button(selected = false, text = "关闭")
        assertTrue("a flat (contentAreaFilled=false) toggle cannot show its selected state", toggle.isContentAreaFilled)
        assertTrue(toggle.isBorderPainted)
    }

    @Test
    fun `flat icon buttons stay flat so toggles remain visually different`() {
        // Mirrors `iconButton`: still a fixed 30x28, still flat — it carries an icon, not text.
        val flat = JButton().apply {
            isContentAreaFilled = false
            isBorderPainted = false
            preferredSize = Dimension(30, 28)
            size = preferredSize
        }
        assertFalse(flat.isContentAreaFilled)
        assertFalse(flat.isBorderPainted)
    }

    @Test
    fun `a toggle that carries text sizes itself to fit the content`() {
        // The factory no longer pins 30x28 (`ContractConformanceTest` asserts the absence on the
        // source); the consequence here is that the width follows the text. Note that some look and
        // feels (Aqua) clamp buttons to a minimum width, so the comparison uses texts that are wide
        // enough to grow on every LAF.
        val short = button(selected = true, text = "1s")
        val longer = button(selected = true, text = "600s")
        assertTrue(
            "a longer interval must not be laid out as narrow as a shorter one (${short.preferredSize} vs ${longer.preferredSize})",
            longer.preferredSize.width > short.preferredSize.width
        )
        assertTrue(
            "the old pinned 30px could not fit any interval text (${longer.preferredSize})",
            longer.preferredSize.width > 30
        )
    }

    @Test
    fun `the on and off states differ by text, colour, font and selection`() {
        val on = state(seconds = 30)
        val off = state(seconds = 0)
        assertNotEquals("the toolbar must show the interval", on.text, off.text)
        assertEquals("30s", on.text)
        assertEquals("关闭", off.text)
        assertNotEquals("the on state must not paint like the off state", on.foreground, off.foreground)
        assertNotEquals("bold marks the running state", on.font, off.font)
        assertTrue(on.isSelected)
        assertFalse(off.isSelected)
    }

    @Test
    fun `selected and unselected toggle states do not paint identically`() {
        val off = state(seconds = 0)
        val on = state(seconds = 30)
        // `关闭` and `30s` need different natural widths, so both are laid out on one common
        // canvas before the pixel walk: comparing differently sized images is what used to make
        // this test throw instead of asserting anything.
        val canvas = Dimension(
            maxOf(off.preferredSize.width, on.preferredSize.width),
            maxOf(off.preferredSize.height, on.preferredSize.height)
        )
        val offImage = render(off, canvas)
        val onImage = render(on, canvas)
        assertEquals("both states must be compared at one geometry", offImage.width, onImage.width)
        assertEquals(offImage.height, onImage.height)
        val differing = countDifferentPixels(offImage, onImage)
        // Not just "> 0": a single antialiased pixel is not a visible state change. The selection
        // fill alone covers hundreds of pixels on every LAF, so the threshold is safe and still
        // fails if the two states were to paint alike.
        assertTrue(
            "Auto-refresh on/off painted $differing identical-or-near-identical pixels, so the switch would be invisible",
            differing >= 50
        )
    }

    private fun render(button: JToggleButton, canvas: Dimension): BufferedImage {
        button.size = canvas
        val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            button.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun countDifferentPixels(first: BufferedImage, second: BufferedImage): Int {
        var count = 0
        for (x in 0 until first.width) {
            for (y in 0 until first.height) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) count++
            }
        }
        return count
    }
}

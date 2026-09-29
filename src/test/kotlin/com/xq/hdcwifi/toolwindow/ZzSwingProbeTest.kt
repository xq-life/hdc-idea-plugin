package com.xq.hdcwifi.toolwindow

import org.junit.Test
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Insets
import java.awt.image.BufferedImage
import javax.swing.DefaultButtonModel
import javax.swing.JButton
import javax.swing.JToggleButton
import javax.swing.border.EmptyBorder

class ZzSwingProbeTest {

    private fun toggle(text: String, selected: Boolean, flat: Boolean = false): JToggleButton = JToggleButton(text).apply {
        isContentAreaFilled = !flat
        isBorderPainted = !flat
        margin = Insets(2, 6, 2, 6)
        size = preferredSize
        isSelected = selected
    }

    private fun render(b: javax.swing.AbstractButton): BufferedImage {
        val img = BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try { b.paint(g) } finally { g.dispose() }
        return img
    }

    private fun diff(a: BufferedImage, b: BufferedImage): Int {
        var n = 0
        for (x in 0 until minOf(a.width, b.width)) for (y in 0 until minOf(a.height, b.height)) if (a.getRGB(x, y) != b.getRGB(x, y)) n++
        return n
    }

    @Test
    fun probeToggleSelectionPaint() {
        val off = toggle("30s", false)
        val on = toggle("30s", true)
        println("PROBE same-text toggle off=${off.width}x${off.height} on=${on.width}x${on.height} differing=${diff(render(off), render(on))}")
        val flatOff = toggle("30s", false, flat = true)
        val flatOn = toggle("30s", true, flat = true)
        println("PROBE flat toggle differing=${diff(render(flatOff), render(flatOn))}")

        // sample a text-free column near the left edge
        val o = render(off); val n = render(on)
        val samples = (0 until o.height).map { y -> Color(o.getRGB(1, y)) to Color(n.getRGB(1, y)) }.filter { it.first != it.second }
        println("PROBE column x=1 differing rows=${samples.size} of ${o.height}; e.g. ${samples.firstOrNull()}")
    }

    private fun chip(secondary: Boolean, enabled: Boolean, guard: Boolean, forceRollover: Boolean): JButton =
        object : JButton("Disconnect") {
            override fun paintComponent(g: Graphics) {
                if (secondary) {
                    val chip = g.create()
                    try {
                        val base = Color(0x1E1F22)
                        val tint = Color(0xDB5C5C)
                        chip.color = if (isEnabled) blend(base, tint, 0.34) else blend(base, blend(base, tint, 0.34), 0.35)
                        chip.fillRoundRect(0, 0, width, height, 8, 8)
                        val rollover = model.isRollover
                        val brighten = if (guard) isEnabled && (model.isRollover || model.isPressed) else (model.isRollover || model.isPressed)
                        println("PROBE chip enabled=$isEnabled guard=$guard modelRollover=$rollover brighten=$brighten")
                        if (brighten) {
                            chip.color = Color(tint.red, tint.green, tint.blue, if (model.isPressed) 72 else 34)
                            chip.fillRoundRect(0, 0, width, height, 8, 8)
                        }
                    } finally { chip.dispose() }
                }
                super.paintComponent(g)
            }
        }.apply {
            model = object : DefaultButtonModel() { override fun isRollover(): Boolean = forceRollover }
            isEnabled = enabled
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            isFocusPainted = false
            border = EmptyBorder(3, 10, 3, 10)
            size = Dimension(120, 32)
        }

    @Test
    fun probeChip() {
        val disabledHover = render(chip(true, false, true, true))
        val unguardedHover = render(chip(true, false, false, true))
        println("PROBE disabled+forcedRollover guarded  px(1,16)=${Color(disabledHover.getRGB(1, 16))}")
        println("PROBE disabled+forcedRollover unguarded px(1,16)=${Color(unguardedHover.getRGB(1, 16))}")
    }

    private fun blend(base: Color, tint: Color, ratio: Double): Color = Color(
        (base.red + (tint.red - base.red) * ratio).toInt().coerceIn(0, 255),
        (base.green + (tint.green - base.green) * ratio).toInt().coerceIn(0, 255),
        (base.blue + (tint.blue - base.blue) * ratio).toInt().coerceIn(0, 255)
    )
}

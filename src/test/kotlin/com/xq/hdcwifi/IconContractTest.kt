package com.xq.hdcwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.awt.Color
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Contract for the four traced SVG assets the user supplied as a bitmap:
 *
 * * `icons/hdc.svg` + `icons/hdc_dark.svg` - the tool window icon, loaded by `Icons.HDC`;
 * * `META-INF/pluginIcon.svg` + `META-INF/pluginIcon_dark.svg` - the plugin-list logo, discovered
 *   by the platform from that exact path (verified against `PluginLogo.getPluginIconFileName`,
 *   which only appends `_dark` for the dark variant).
 *
 * The point of asserting the files themselves is that the IntelliJ SVG renderer is stricter than a
 * browser: it does not support embedded bitmaps, and a white plate would show up as a bright square
 * under Darcula. The `_dark` variants are checked to be real recolours, not copies, because a copy
 * would render a dark logo on a dark background.
 *
 * `Icons.kt` cannot be instantiated here (`IconLoader` needs the IntelliJ application), so the
 * loading path is asserted on the source and the assets on disk.
 */
class IconContractTest {

    private val resources = File(System.getProperty("user.dir"), "src/main/resources")

    private val light = File(resources, "icons/hdc.svg")
    private val dark = File(resources, "icons/hdc_dark.svg")
    private val pluginLight = File(resources, "META-INF/pluginIcon.svg")
    private val pluginDark = File(resources, "META-INF/pluginIcon_dark.svg")

    private val all = listOf(light, dark, pluginLight, pluginDark)

    // ---- 1./2. presence, well-formedness, size ------------------------------------------------

    @Test
    fun `all four traced icons exist and are standalone svg documents`() {
        all.forEach { file ->
            val root = document(file).documentElement
            assertEquals("${file.name} root element", "svg", root.tagName)
            assertTrue(
                "${file.path} must declare the SVG namespace",
                file.readText().contains("""xmlns="http://www.w3.org/2000/svg"""")
            )
            assertTrue("${file.path} must carry a viewBox", root.getAttribute("viewBox").isNotBlank())
            listOf("width", "height").forEach { attribute ->
                val value = root.getAttribute(attribute)
                assertTrue(
                    "${file.path} $attribute must be numeric, was '$value'",
                    value.toDoubleOrNull() != null
                )
            }
        }
    }

    @Test
    fun `the tool window icon is 13px and the plugin logo is 40px`() {
        assertEquals(13.0, number(light, "width"), 0.0)
        assertEquals(13.0, number(light, "height"), 0.0)
        assertEquals(13.0, number(dark, "width"), 0.0)
        assertEquals(13.0, number(dark, "height"), 0.0)
        assertEquals(40.0, number(pluginLight, "width"), 0.0)
        assertEquals(40.0, number(pluginLight, "height"), 0.0)
        assertEquals(40.0, number(pluginDark, "width"), 0.0)
        assertEquals(40.0, number(pluginDark, "height"), 0.0)
    }

    // ---- 3. no embedded bitmap ----------------------------------------------------------------

    @Test
    fun `no icon embeds a bitmap the platform renderer cannot draw`() {
        all.forEach { file ->
            val root = document(file).documentElement
            assertTrue(
                "${file.path} must not use an <image> element",
                elements(root).none { it.localName == "image" || it.tagName == "image" }
            )
            val text = file.readText().lowercase()
            assertFalse("${file.path} must not embed base64", text.contains("base64"))
            assertFalse("${file.path} must not use a data: URI", text.contains("data:"))
        }
    }

    // ---- 4. no white plate --------------------------------------------------------------------

    @Test
    fun `no icon paints a pure white fill that would glow under the dark theme`() {
        all.forEach { file ->
            val root = document(file).documentElement
            elements(root).forEach { element ->
                listOf("fill", "stroke").forEach { attribute ->
                    val value = element.getAttribute(attribute)
                    if (value.isNotEmpty()) {
                        assertFalse(
                            "${file.path} paints white (${element.tagName} $attribute=\"$value\")",
                            isWhite(value)
                        )
                    }
                }
                val style = element.getAttribute("style")
                assertFalse(
                    "${file.path} paints white through its style attribute ('$style')",
                    WHITE_IN_STYLE.containsMatchIn(style)
                )
            }
        }
    }

    // ---- 5. the dark variants are real, brighter variants -------------------------------------

    @Test
    fun `the dark variants are recolours of the same shapes, never darker`() {
        listOf(light to dark, pluginLight to pluginDark).forEach { (lightFile, darkFile) ->
            val lightFills = fills(lightFile)
            val darkFills = fills(darkFile)
            assertEquals(
                "${darkFile.name} must recolour the same paths as ${lightFile.name}",
                lightFills.size,
                darkFills.size
            )
            assertNotEquals(
                "${darkFile.name} must not be a verbatim copy of ${lightFile.name}",
                lightFills,
                darkFills
            )
            lightFills.zip(darkFills).forEachIndexed { index, (lightColor, darkColor) ->
                assertTrue(
                    "${darkFile.name} path #$index ($darkColor) must be at least as bright as " +
                        "${lightFile.name} ($lightColor) in every channel",
                    darkColor.red >= lightColor.red &&
                        darkColor.green >= lightColor.green &&
                        darkColor.blue >= lightColor.blue
                )
            }
            assertTrue(
                "${darkFile.name} must be brighter overall",
                darkFills.sumOf { it.red + it.green + it.blue } >
                    lightFills.sumOf { it.red + it.green + it.blue }
            )
        }
    }

    // ---- 6. the wiring still points at the traced asset ---------------------------------------

    @Test
    fun `the tool window still loads the traced icon and only through Icons`() {
        val pluginXml = document(File(resources, "META-INF/plugin.xml")).documentElement
        val toolWindows = elements(pluginXml).filter { it.tagName == "toolWindow" }
        assertEquals("exactly one tool window is declared", 1, toolWindows.size)
        assertEquals(
            "the tool window icon must keep resolving through Icons.HDC",
            "com.xq.hdcwifi.Icons.HDC",
            toolWindows.single().getAttribute("icon")
        )

        val icons = MainSources.file("Icons.kt").readText()
        assertTrue(icons.contains("val HDC: Icon"))
        assertTrue(icons.contains("""IconLoader.getIcon("/icons/hdc.svg", Icons::class.java)"""))
        // The plugin logo is discovered by the platform from META-INF/pluginIcon[_dark].svg; it must
        // not be wired through plugin.xml, which has no such extension point.
        assertFalse(
            "plugin.xml must not reference the auto-discovered plugin logo",
            File(resources, "META-INF/plugin.xml").readText().contains("pluginIcon")
        )
    }

    // ---- 7. the drawing itself ------------------------------------------------------------------

    @Test
    fun `every icon is drawn with non-empty even-odd paths`() {
        all.forEach { file ->
            val paths = elements(document(file).documentElement).filter { it.localName == "path" }
            assertTrue("${file.path} must contain at least one <path>", paths.isNotEmpty())
            paths.forEachIndexed { index, path ->
                assertTrue(
                    "${file.path} path #$index must have a non-empty d",
                    path.getAttribute("d").isNotBlank()
                )
                assertEquals(
                    "${file.path} path #$index must be filled even-odd",
                    "evenodd",
                    path.getAttribute("fill-rule")
                )
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun document(file: File): Document {
        assertTrue("missing icon file: ${file.path}", file.isFile)
        assertTrue("empty icon file: ${file.path}", file.length() > 0)
        return try {
            FACTORY.newDocumentBuilder().parse(file)
        } catch (e: Exception) {
            throw AssertionError("${file.path} is not well-formed XML: ${e.message}", e)
        }
    }

    private fun elements(root: Element): List<Element> {
        val nodes = root.getElementsByTagName("*")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun number(file: File, attribute: String): Double {
        val value = document(file).documentElement.getAttribute(attribute)
        return value.toDoubleOrNull() ?: error("${file.path} $attribute is not numeric: '$value'")
    }

    private fun fills(file: File): List<Color> =
        elements(document(file).documentElement)
            .filter { it.localName == "path" }
            .map { path ->
                val fill = path.getAttribute("fill")
                assertTrue("${file.path} path must declare a fill", fill.isNotEmpty())
                Color.decode(fill)
            }

    private fun isWhite(value: String): Boolean {
        val normalized = value.trim().lowercase().removePrefix("#")
        return normalized in WHITE
    }

    private companion object {
        val FACTORY: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }

        val WHITE = setOf("fff", "ffffff", "white", "rgb(255,255,255)", "rgb(255, 255, 255)")

        val WHITE_IN_STYLE = Regex("fill\\s*:\\s*(#fff\\b|#ffffff\\b|white\\b|rgb\\(255,\\s*255,\\s*255\\))", RegexOption.IGNORE_CASE)
    }
}

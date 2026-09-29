package com.xq.hdcwifi

import java.io.File

/**
 * Read-only access to the production sources for conformance tests.
 *
 * Some contract items are properties of the source itself (annotation placement, absence of removed
 * literals, button styling) and cannot be observed from a plain JUnit test JVM: the IntelliJ
 * application is not initialised there (`ApplicationManager.getApplication() == null`), so no plugin
 * service, panel or project can be constructed. These helpers keep such checks executable and
 * reproducible instead of leaving them as prose.
 */
object MainSources {

    val root: File = File(System.getProperty("user.dir"), "src/main/kotlin/com/xq/hdcwifi")

    init {
        // Fail loudly instead of letting every source assertion pass vacuously.
        check(root.isDirectory) { "main source root not found: $root (user.dir=${System.getProperty("user.dir")})" }
    }

    fun file(relative: String): File = File(root, relative).also {
        check(it.isFile) { "main source not found: $it (user.dir=${System.getProperty("user.dir")})" }
    }

    fun all(): List<File> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    val panel: String get() = file("toolwindow/HdcMainPanel.kt").readText()
    val service: String get() = file("service/HdcService.kt").readText()
    val settings: String get() = file("settings/HdcSettings.kt").readText()
    val settingsConfigurable: String get() = file("settings/HdcSettingsConfigurable.kt").readText()

    /**
     * Slice one function out of a source file: from its signature up to the next top-level member.
     * Good enough to assert "this function does/does not do X" without a real parser.
     */
    fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "signature not found in source: $signature" }
        val candidates = listOf(
            source.indexOf("\n    private fun ", start + signature.length),
            source.indexOf("\n    override fun ", start + signature.length),
            source.indexOf("\n    /**", start + signature.length),
            source.indexOf("\n    private class ", start + signature.length)
        ).filter { it > start }
        val end = candidates.minOrNull() ?: source.length
        return source.substring(start, end)
    }
}

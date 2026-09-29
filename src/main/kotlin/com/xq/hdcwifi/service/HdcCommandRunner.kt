package com.xq.hdcwifi.service

import java.util.concurrent.TimeUnit

data class HdcResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0 && !outputIndicatesFailure(output)
}

internal fun outputIndicatesFailure(output: String): Boolean {
    val lower = output.lowercase()
    return lower.lineSequence().any { line ->
        val text = line.trim()
        text.startsWith("[fail]") ||
            text.contains("unknown operation command") ||
            text.contains("not match target") ||
            text.contains("no target available") ||
            text.startsWith("error:")
    }
}

object HdcCommandRunner {

    private const val JOIN_MS = 2_000L

    @Volatile
    private var cachedPath: String? = null

    @Volatile
    private var cacheKey: String = ""

    /** Invalidate the resolved-path cache (call after settings change). */
    @Synchronized
    fun resetCache() {
        cachedPath = null
        cacheKey = ""
    }

    @Throws(HdcToolNotFoundException::class)
    @Synchronized
    fun resolveExecutable(): String {
        val configured = com.xq.hdcwifi.settings.HdcSettings.instance.hdcPath
        if (configured != cacheKey || cachedPath == null) {
            cachedPath = HdcPathResolver.resolve()
            cacheKey = configured
        }
        return cachedPath ?: throw HdcToolNotFoundException(
            "HDC tool not found. Set the hdc path in Settings > Tools > HDC Wi-Fi."
        )
    }

    /**
     * Run an hdc command and capture merged stdout/stderr.
     * Output is drained on a daemon thread: hdc can leave the pipe open, so reading
     * on the calling thread would make the timeout unreachable.
     */
    fun run(args: List<String>, timeoutSeconds: Long = 30): HdcResult {
        val executable = resolveExecutable()
        val process = ProcessBuilder(listOf(executable) + args)
            .redirectErrorStream(true)
            .start()
        val buffer = StringBuffer()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(buffer) { buffer.append(line).append('\n') }
                }
            }
        }, "hdc-output-reader").apply { isDaemon = true }
        reader.start()

        try {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(JOIN_MS, TimeUnit.MILLISECONDS)
                reader.join(JOIN_MS)
                val partial = synchronized(buffer) { buffer.toString().trim() }
                val timeout = "[hdc command timed out after ${timeoutSeconds}s]"
                return HdcResult(-1, if (partial.isEmpty()) timeout else "$partial\n$timeout")
            }
            reader.join(JOIN_MS)
            return HdcResult(process.exitValue(), synchronized(buffer) { buffer.toString() })
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /** Start a long-running hdc process; caller owns the returned HdcStream. */
    fun startStream(args: List<String>): HdcStream {
        val executable = resolveExecutable()
        val process = ProcessBuilder(listOf(executable) + args)
            .redirectErrorStream(true)
            .start()
        return HdcStream(process)
    }
}

package com.xq.hdcwifi.service

import java.util.concurrent.TimeUnit

/** Handle for a running hdc process producing lines on stdout. */
class HdcStream internal constructor(private val process: Process) {

    @Volatile
    var active: Boolean = true
        private set

    /** Pump output lines on a daemon thread; callbacks run on the caller thread. */
    fun readLines(onLine: (String) -> Unit, onExit: (Int) -> Unit) {
        val thread = Thread({
            var code = -1
            var semanticFailure = false
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!active) break
                        if (outputIndicatesFailure(line)) semanticFailure = true
                        onLine(line)
                    }
                }
                code = if (active) {
                    if (process.waitFor(5, TimeUnit.SECONDS)) {
                        if (semanticFailure) -1 else process.exitValue()
                    } else {
                        process.destroyForcibly()
                        -1
                    }
                } else {
                    -1
                }
            } catch (_: Exception) {
                // process destroyed or stream closed
            } finally {
                active = false
                onExit(code)
            }
        }, "hdc-stream")
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        active = false
        try {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (_: Exception) {
            process.destroyForcibly()
        }
    }
}

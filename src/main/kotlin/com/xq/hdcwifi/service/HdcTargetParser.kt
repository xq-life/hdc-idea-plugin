package com.xq.hdcwifi.service

/**
 * Parses `hdc list targets` output. Typical forms:
 *   FMR0223C21202498
 *   192.168.1.20:5555
 *   [Empty]
 */
object HdcTargetParser {

    fun parse(output: String): List<String> {
        return output.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            // hdc prints [Empty] when nothing is attached; [Fail]/[Info] lines are not targets
            .filter { line -> !line.startsWith("[") }
            // some hdc builds append a status column: "serial\t\tdevice"
            .map { line -> line.substringBefore("\t").trim() }
            .filter { line -> line.isNotEmpty() }
            .distinct()
            .toList()
    }
}

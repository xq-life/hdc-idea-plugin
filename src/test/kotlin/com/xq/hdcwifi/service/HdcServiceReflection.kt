package com.xq.hdcwifi.service

import java.lang.reflect.InvocationTargetException
import java.net.Inet4Address

/**
 * Reflective access to `HdcService`'s private scan helpers.
 *
 * The IntelliJ application is not started in this test JVM, so `scanDevices` cannot deliver its
 * callbacks (they go through `ApplicationManager.invokeLater`). The helpers themselves are pure
 * though, and the production sources must not be modified just to make them testable, so they are
 * reached reflectively. The lookup asserts the exact parameter count and fails loudly if a helper is
 * renamed or its signature changes, instead of silently skipping the checks.
 */
internal object HdcServiceReflection {

    /**
     * Mirror of the private `HdcService.CandidateSet` (v1.9: candidates also carry the ranges and
     * ports that were actually probed, so the scan log can state its own coverage).
     */
    data class CandidateSet(
        val targets: List<Pair<String, Int>>,
        val warning: String?,
        val ranges: List<String>,
        val ports: List<Int>
    )

    fun call(service: HdcService, name: String, vararg args: Any?): Any? {
        val method = HdcService::class.java.declaredMethods
            .singleOrNull { it.name == name && it.parameterCount == args.size }
            ?: error("HdcService.$name with ${args.size} parameter(s) is gone; update the reflective contract test")
        method.isAccessible = true
        return try {
            method.invoke(service, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun expandCidr(service: HdcService, cidr: String): List<String> =
        call(service, "expandCidr", cidr) as List<String>

    /**
     * Endpoints only; most tests do not care about the warning. [extraPorts] mirrors the production
     * parameter added in v1.9 and defaults to blank, so call sites that only exercise the older
     * contract keep their meaning without a second overload to keep in sync.
     */
    fun candidateTargets(
        service: HdcService,
        savedAddresses: List<String>,
        defaultPort: Int,
        customCidr: String,
        extraPorts: String = ""
    ): List<Pair<String, Int>> =
        candidateSet(service, savedAddresses, defaultPort, customCidr, extraPorts).targets

    /**
     * Full result. The production type is private, so its components are read by name; a rename or a
     * missing component makes this fail loudly instead of silently returning a default.
     */
    @Suppress("UNCHECKED_CAST")
    fun candidateSet(
        service: HdcService,
        savedAddresses: List<String>,
        defaultPort: Int,
        customCidr: String,
        extraPorts: String = ""
    ): CandidateSet {
        val raw = call(service, "candidateTargets", savedAddresses, defaultPort, customCidr, extraPorts)
            ?: error("HdcService.candidateTargets returned null; it must return a candidate set")
        fun component(name: String): Any? {
            val field = raw.javaClass.declaredFields.singleOrNull { it.name == name }
                ?: error("HdcService.candidateTargets result has no `$name`; update the reflective contract test")
            field.isAccessible = true
            return field.get(raw)
        }
        fun <T> required(name: String, expected: String): T =
            component(name) as? T ?: error("HdcService.candidateTargets.$name is not $expected")
        return CandidateSet(
            targets = required("targets", "a List<Pair<String, Int>>"),
            warning = component("warning") as String?,
            ranges = required("ranges", "a List<String>"),
            ports = required("ports", "a List<Int>")
        )
    }

    /** `HdcService.networkCidr`: masks [address] with [prefixLength] into a canonical network. */
    fun networkCidr(service: HdcService, address: Inet4Address, prefixLength: Int): String? =
        call(service, "networkCidr", address, prefixLength) as String?

    /** `HdcService.networkPorts`: the port set probed on every host of a scanned range. */
    @Suppress("UNCHECKED_CAST")
    fun networkPorts(service: HdcService, defaultPort: Int, extraPorts: String, savedPorts: Set<Int>): List<Int> =
        call(service, "networkPorts", defaultPort, extraPorts, savedPorts) as List<Int>

    /** User-facing operating system summary built from raw HDC properties. */
    fun displaySystemName(service: HdcService, info: Map<String, String>): String =
        call(service, "displaySystemName", info) as String

    /** `null` means the machine's interfaces could not be read at all (round-4 semantics). */
    @Suppress("UNCHECKED_CAST")
    fun localNetworkCidrs(service: HdcService): List<String>? =
        call(service, "localNetworkCidrs") as List<String>?
}

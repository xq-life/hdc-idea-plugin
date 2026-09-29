package com.xq.hdcwifi.service

import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.net.Inet4Address

class ZzProbeTest {

    private fun call(service: HdcService, name: String, vararg args: Any?): Any? {
        val m = HdcService::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        return try { m.invoke(service, *args) } catch (e: InvocationTargetException) { throw e.targetException }
    }

    @Test
    fun probe() {
        val service = HdcService()
        try {
            val local = HdcServiceReflection.localNetworkCidrs(service)
            println("PROBE localNetworkCidrs = $local")
            local?.forEach { cidr ->
                val expanded = runCatching { HdcServiceReflection.expandCidr(service, cidr) }
                println("PROBE expand($cidr) -> ${expanded.getOrNull()?.size ?: expanded.exceptionOrNull()}")
            }
            val set = call(service, "candidateTargets", emptyList<String>(), 5555, "", "")!!
            fun comp(name: String): Any? {
                val f = set.javaClass.declaredFields.single { it.name == name }
                f.isAccessible = true
                return f.get(set)
            }
            println("PROBE candidateTargets(no saved, no custom) targets=${(comp("targets") as List<*>).size} warning=${comp("warning")} ranges=${comp("ranges")} ports=${comp("ports")}")

            val addr = java.net.InetAddress.getByName("172.16.1.213") as Inet4Address
            for (prefix in listOf(0, 8, 16, 22, 23, 24, 30, 31, 32)) {
                println("PROBE networkCidr($addr, $prefix) = ${call(service, "networkCidr", addr, prefix)}")
            }
            println("PROBE networkPorts(5555, '38343, 99999,abc;5555', {6000,8710}) = ${call(service, "networkPorts", 5555, "38343, 99999,abc;5555", setOf(6000, 8710))}")
            println("PROBE networkPorts cap = ${call(service, "networkPorts", 1, "2 3 4 5 6 7 8", setOf(9, 10))}")
            println("PROBE candidateTargets(saved 10.0.0.9:6000) = ${(call(service, "candidateTargets", listOf("10.0.0.9:6000"), 5555, "", "")!!.javaClass.getDeclaredField("targets").also { it.isAccessible = true }.get(call(service, "candidateTargets", listOf("10.0.0.9:6000"), 5555, "", "")) as List<*>)}")
        } finally {
            service.dispose()
        }
    }
}

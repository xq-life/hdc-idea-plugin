package com.xq.hdcwifi.service

import com.xq.hdcwifi.MainSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * UX-CONTRACT §8/v1.1(3): the custom CIDR must support IPv4 prefixes beyond /24, cap the usable
 * address count at 4096, and report invalid/oversized input with an explicit error instead of
 * silently returning an empty scan.
 *
 * v1.9 adds two scan-scope contracts: the network prefix is derived from the interface's real
 * netmask (not hardcoded /24) and every range host is probed on the whole known port set (default,
 * configured extras, previously connected ports) rather than the default port alone.
 *
 * `expandCidr`, `candidateTargets`, `networkCidr`, `networkPorts` and `localNetworkCidrs` are private
 * and the production sources must not be touched, so they are reached through [HdcServiceReflection]
 * (which fails loudly if a signature changes). The v1.9 masking defect (`joinToString`'s second
 * positional parameter is `prefix`, so ranges were emitted as `/23172.16.0.0`) is exactly why these
 * checks call the production method instead of a re-implementation: a hand-written mirror of the same
 * logic printed the correct string while the shipped code did not.
 */
class HdcScanCidrTest {

    private val service = HdcService()

    @After
    fun tearDown() {
        service.dispose()
    }

    private fun expandCidr(cidr: String): List<String> = HdcServiceReflection.expandCidr(service, cidr)

    private fun candidateTargets(saved: List<String>, defaultPort: Int = 5555, cidr: String = "", extraPorts: String = "") =
        HdcServiceReflection.candidateTargets(service, saved, defaultPort, cidr, extraPorts)

    private fun networkPorts(defaultPort: Int, extraPorts: String, savedPorts: Set<Int>): List<Int> =
        HdcServiceReflection.networkPorts(service, defaultPort, extraPorts, savedPorts)

    private fun networkCidr(host: String, prefixLength: Int): String? =
        HdcServiceReflection.networkCidr(service, InetAddress.getByName(host) as Inet4Address, prefixLength)

    @Test
    fun `expands a 24 network to all usable hosts`() {
        val hosts = expandCidr("192.168.1.0/24")
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        // network and broadcast addresses are excluded
        assertFalse(hosts.contains("192.168.1.0"))
        assertFalse(hosts.contains("192.168.1.255"))
    }

    @Test
    fun `masks a host address down to its network`() {
        val hosts = expandCidr("192.168.1.77/24")
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
    }

    @Test
    fun `supports prefixes other than 24`() {
        // The v1.2 revision record says only /24 used to work and every other prefix silently
        // returned an empty list; this is the regression guard for that defect.
        assertEquals(
            listOf("10.9.4.1", "10.9.4.2", "10.9.4.3", "10.9.4.4", "10.9.4.5", "10.9.4.6"),
            expandCidr("10.9.4.0/29")
        )
        assertEquals(listOf("192.168.1.1", "192.168.1.2"), expandCidr("192.168.1.0/30"))
        assertEquals(listOf("192.168.1.0", "192.168.1.1"), expandCidr("192.168.1.0/31"))
        assertEquals(listOf("192.168.1.7"), expandCidr("192.168.1.7/32"))

        val slash22 = expandCidr("10.9.4.0/22")
        assertEquals(1022, slash22.size)
        assertEquals("10.9.4.1", slash22.first())
        assertEquals("10.9.7.254", slash22.last())
        assertTrue(slash22.isNotEmpty())
    }

    @Test
    fun `accepts exactly 4096 addresses and rejects anything larger`() {
        val slash20 = expandCidr("10.0.0.0/20")
        assertEquals(4094, slash20.size)
        assertEquals("10.0.0.1", slash20.first())
        assertEquals("10.0.15.254", slash20.last())

        listOf("10.0.0.0/19", "0.0.0.0/0").forEach { cidr ->
            val error = assertThrows(IllegalArgumentException::class.java) { expandCidr(cidr) }
            assertEquals("Cannot scan $cidr: use an IPv4 network with at most 4096 addresses.", error.message)
        }
    }

    @Test
    fun `rejects malformed and IPv6 networks with the contract message`() {
        val invalid = listOf(
            "192.168.1.0/33", "192.168.1.256/24", "192.168.1.0", "abc", "", "1.2.3.4/-1",
            "::1/64", "2001:db8::1/64", "fe80::/10"
        )
        invalid.forEach { cidr ->
            val error = assertThrows(IllegalArgumentException::class.java) { expandCidr(cidr) }
            assertEquals("Cannot scan $cidr: use an IPv4 network with at most 4096 addresses.", error.message)
        }
    }

    // ---- v1.9 (2.1): the prefix comes from the interface's real netmask ---------------------------

    @Test
    fun `a network cidr is derived from the real netmask and stays expandable`() {
        // Regression guard for the defect where every interface was flattened to a /24 built from the
        // first three octets: a /23 interface then only had its upper half scanned, so a device on the
        // lower half could never be found. The masked prefix must survive verbatim, in the `a.b.c.d/n`
        // order that `expandCidr` parses — a CIDR that is only *nearly* right is dead coverage.
        assertEquals("172.16.0.0/23", networkCidr("172.16.1.213", 23))
        assertEquals("172.16.0.0/22", networkCidr("172.16.1.213", 22))
        assertEquals("172.16.1.0/24", networkCidr("172.16.1.213", 24))
        assertEquals("172.16.0.0/16", networkCidr("172.16.1.213", 16))
        assertEquals("172.16.1.212/30", networkCidr("172.16.1.213", 30))
        assertEquals("172.16.1.213/32", networkCidr("172.16.1.213", 32))
        assertEquals("0.0.0.0/0", networkCidr("172.16.1.213", 0))
        assertEquals("192.168.0.0/22", networkCidr("192.168.2.22", 22))
        // a host already sitting on a boundary must not be shifted
        assertEquals("10.20.0.0/16", networkCidr("10.20.0.0", 16))

        // Every result is the exact `a.b.c.d/n` shape `expandCidr` parses. This pattern is what a
        // prefix/postfix mix-up breaks, so it guards the whole family of formatting mistakes.
        val shape = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/\d{1,2}$""")
        (0..32).forEach { prefix ->
            val cidr = networkCidr("172.16.1.213", prefix)
            assertNotNull("prefix /$prefix must produce a network", cidr)
            assertTrue("network is not a.b.c.d/prefix: $cidr", shape.matches(cidr!!))
            assertTrue("the prefix must be preserved: $cidr", cidr.endsWith("/$prefix"))
        }

        // and each in-cap prefix round-trips through the expander that the scan actually uses
        listOf(23 to 510, 22 to 1022, 24 to 254, 30 to 2, 32 to 1).forEach { (prefix, size) ->
            val cidr = networkCidr("172.16.1.213", prefix)!!
            assertEquals("$cidr must expand to $size usable hosts", size, expandCidr(cidr).size)
        }
        // the wide prefixes are legal networks that the existing 4096-address cap rejects
        listOf(16, 0).forEach { prefix ->
            val cidr = networkCidr("172.16.1.213", prefix)!!
            assertTrue(shape.matches(cidr))
            assertThrows("a /$prefix is larger than the scan cap", IllegalArgumentException::class.java) { expandCidr(cidr) }
        }
    }

    @Test
    fun `a 23 prefix is not flattened to a 24`() {
        // The single most valuable counterexample: the old implementation always emitted `/24` and
        // dropped the last octet, so 172.16.1.213 became 172.16.1.0/24 and 172.16.0.240 was invisible.
        val cidr = networkCidr("172.16.1.213", 23)
        assertTrue("the real prefix must be kept: $cidr", cidr!!.endsWith("/23"))
        assertTrue("the masked network is the lower half of the /23: $cidr", cidr.startsWith("172.16.0.0/"))
        assertFalse("a /24 flattening would exclude 172.16.0.240: $cidr", cidr.endsWith("/24"))
        val hosts = expandCidr(cidr)
        assertTrue("the device the user reported must be inside the expanded range", hosts.contains("172.16.0.240"))
        assertTrue("the gateway half of the /23 must be covered too", hosts.contains("172.16.1.213"))
    }

    @Test
    fun `a link local or loopback address never becomes a scanned range`() {
        // `localNetworkCidrs` filters on `isLoopback`/`isLoopbackAddress`/`isLinkLocalAddress`
        // *before* masking, so those filters must be present around the masking call.
        val local = MainSources.bodyOf(MainSources.service, "private fun localNetworkCidrs(")
        listOf("!networkInterface.isUp", "networkInterface.isLoopback", "address.isLoopbackAddress", "address.isLinkLocalAddress")
            .forEach { assertTrue("missing interface filter: $it", local.contains(it)) }
        assertTrue("a skipped address must not yield a CIDR", local.contains("return@mapNotNull null"))
        assertTrue("duplicates must be collapsed", local.contains(".distinct()"))
        assertTrue("the network comes from the interface's own prefix length", local.contains("interfaceAddress.networkPrefixLength"))

        // and the machine-wide result really is loopback/link-local free and well formed
        val cidrs = HdcServiceReflection.localNetworkCidrs(service)
        assertNotNull("interface enumeration failed on this machine", cidrs)
        val shape = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/\d{1,2}$""")
        cidrs!!.forEach { cidr ->
            assertTrue("a returned range is not a.b.c.d/prefix: $cidr", shape.matches(cidr))
            assertFalse("loopback range leaked: $cidr", cidr.startsWith("127."))
            assertFalse("link-local range leaked: $cidr", cidr.startsWith("169.254."))
            // the strict half: every advertised range must be one the scan can actually expand —
            // an advertised-but-unexpandable range is silently dropped by `candidateTargets`, i.e.
            // exactly the "device is on the network but was never probed" failure mode.
            val expanded = runCatching { expandCidr(cidr) }
            assertTrue(
                "$cidr is advertised but cannot be expanded: ${expanded.exceptionOrNull()}",
                expanded.getOrNull()?.isNotEmpty() == true
            )
        }
    }

    // ---- v1.9 (2.2): ranges are probed on the whole known port set --------------------------------

    @Test
    fun `the port set combines the default, the configured extras and the saved ports in order`() {
        // comma, space and semicolon all separate; the default comes first, the saved ports last.
        assertEquals(
            listOf(5555, 38343, 6001, 6002, 6003, 8710),
            networkPorts(5555, "38343,6001 6002;6003", setOf(8710))
        )
        // the result is de-duplicated while keeping its order
        assertEquals(listOf(5555, 6000), networkPorts(5555, "6000 5555;6000", setOf(6000)))
        // unusable tokens are ignored instead of producing a port 0 / 65536 / NaN
        assertEquals(listOf(5555, 6000), networkPorts(5555, "0,65536,-1,abc,6000,", emptySet()))
        assertEquals(listOf(5555), networkPorts(5555, "", emptySet()))
        assertEquals(listOf(5555), networkPorts(5555, "   ", emptySet()))
        // a default port outside the range is normalised rather than emitted raw
        assertEquals(listOf(1), networkPorts(0, "", emptySet()))
        assertEquals(listOf(65535), networkPorts(99999, "", emptySet()))
    }

    @Test
    fun `the port set is capped and the cap is the named constant`() {
        assertEquals(6, networkPorts(1, "2 3 4 5 6 7 8", setOf(9, 10)).size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), networkPorts(1, "2 3 4 5 6 7 8", setOf(9, 10)))
        assertTrue(MainSources.service.contains("private const val MAX_SCAN_PORTS = 6"))
        assertTrue(
            "the cap must be applied, not just declared",
            MainSources.bodyOf(MainSources.service, "private fun networkPorts(").contains("return ports.take(MAX_SCAN_PORTS)")
        )
    }

    @Test
    fun `every host of a derived range is probed on the whole port set`() {
        // The wireless-debugging defect: a device open on a random high port was invisible because a
        // range host only ever got the default port.
        val set = HdcServiceReflection.candidateSet(
            service,
            listOf("198.51.100.10:6000"),
            5555,
            "10.99.0.0/30",
            extraPorts = "38343"
        )
        assertEquals(listOf(5555, 38343, 6000), set.ports)
        listOf(
            "10.99.0.1" to 5555, "10.99.0.1" to 38343, "10.99.0.1" to 6000,
            "10.99.0.2" to 5555, "10.99.0.2" to 38343, "10.99.0.2" to 6000,
            // the saved address keeps its own port
            "198.51.100.10" to 6000
        ).forEach { assertTrue("missing candidate $it", set.targets.contains(it)) }
        assertFalse("the saved port must not be replaced by the default", set.targets.contains("198.51.100.10" to 5555))
        // `candidateTargets` always adds the machine's own ranges too, so the cartesian product is
        // asserted on the custom /30's own slice: two hosts × three ports, nothing more, nothing less.
        assertEquals("the range got the full port set", 6, set.targets.count { it.first.startsWith("10.99.0.") })
    }

    @Test
    fun `a saved address keeps its own port even when a range covers the same host`() {
        // Host 10.99.0.1 is both saved (port 6000) and inside the range: it must be probed on 6000.
        val targets = candidateTargets(listOf("10.99.0.1:6000"), cidr = "10.99.0.0/29")
        assertTrue(targets.contains("10.99.0.1" to 6000))
        assertTrue("the range host is also probed on the default port", targets.contains("10.99.0.1" to 5555))
        assertFalse("the default port must never replace the saved port", targets.count { it.first == "10.99.0.1" } < 2)
    }

    // ---- pre-existing candidate contract ---------------------------------------------------------

    @Test
    fun `candidate targets always include saved addresses and custom cidr hosts`() {
        val targets = candidateTargets(listOf("198.51.100.10:6000"), cidr = "10.99.0.0/30")
        assertTrue("saved address must be a candidate, on its own port", targets.contains("198.51.100.10" to 6000))
        assertTrue(targets.contains("10.99.0.1" to 5555))
        assertTrue(targets.contains("10.99.0.2" to 5555))
        // .0 is never produced by any expansion (network address of a /30 and of a /24 alike)
        assertFalse("network address is not probed", targets.any { it.first == "10.99.0.0" })
    }

    @Test
    fun `candidate targets are de-duplicated by host and port`() {
        val targets = candidateTargets(listOf("198.51.100.10:6000", "198.51.100.10:6000", "198.51.100.10:6000"))
        assertEquals(1, targets.count { it == "198.51.100.10" to 6000 })
        assertEquals(targets.distinct(), targets)
    }

    @Test
    fun `a saved address on the default port is not duplicated by a derived range`() {
        // The host also appears in a custom CIDR: (host, port) is what de-duplicates, so the CIDR
        // copy merges into the saved one because both use the default port.
        val targets = candidateTargets(listOf("10.99.0.1:5555"), cidr = "10.99.0.0/29")
        assertEquals(1, targets.count { it == "10.99.0.1" to 5555 })
    }

    @Test
    fun `an oversized or invalid custom cidr aborts the whole scan instead of being ignored`() {
        assertThrows(IllegalArgumentException::class.java) { candidateTargets(emptyList(), cidr = "10.0.0.0/8") }
        assertThrows(IllegalArgumentException::class.java) { candidateTargets(emptyList(), cidr = "not-a-cidr") }
    }

    @Test
    fun `an unreadable interface list becomes a warning and not an empty result`() {
        // R3 (round 4): candidateTargets must be able to say *why* it found no local ranges instead
        // of pretending the machine has none. On this machine the interfaces are readable.
        val here = HdcServiceReflection.candidateSet(service, listOf("198.51.100.10:6000"), 5555, "")
        val again = HdcServiceReflection.candidateSet(service, listOf("198.51.100.10:6000"), 5555, "")
        assertTrue("the saved address is still a candidate", here.targets.contains("198.51.100.10" to 6000))
        assertEquals("readable interfaces must not warn", null, here.warning)
        // The mask-derived ranges must be reported (not the raw interface list) and must have produced
        // candidates: an empty target list here would mean the machine's own network is never scanned.
        assertTrue("the covered ranges must be reported", here.ranges.isNotEmpty())
        assertTrue("the machine has at least one expandable local range: ${here.ranges}", again.targets.any { it.first != "198.51.100.10" })

        // and the warning is derived from the null return, not from an empty list
        assertTrue(MainSources.service.contains("if (local == null) INTERFACE_ERROR else null"))
        assertTrue(HdcService.INTERFACE_ERROR.startsWith("Unable to read local network interfaces."))
    }

    // ---- v1.9 (2.4): scan concurrency and progress throttling ------------------------------------

    @Test
    fun `the concurrency cap is the internal constant the scan executor uses`() {
        // UX-CONTRACT §8/v1.1(1) wants a verifiable constant; `internal` makes it readable here.
        // v1.9 raised it to 64 so a 3000+ candidate scan stays inside a usable wall-clock time.
        assertEquals(64, HdcService.SCAN_CONCURRENCY)
        assertTrue(MainSources.service.contains("Executors.newFixedThreadPool(SCAN_CONCURRENCY)"))
        assertTrue(MainSources.service.contains("Semaphore(SCAN_CONCURRENCY)"))
    }

    @Test
    fun `scan progress is throttled by time instead of reported once per probe`() {
        // A 3064-target scan used to queue 3064 EDT tasks just for progress, which stuttered the UI.
        val service = MainSources.service
        assertTrue(service.contains("private const val PROGRESS_INTERVAL_MS = 200L"))
        assertTrue(
            "the throttle needs a timestamp to compare against",
            service.contains("val lastProgressAt = java.util.concurrent.atomic.AtomicLong(0)")
        )

        val scan = MainSources.bodyOf(service, "fun scanDevices(")
        assertTrue(
            "the due condition must combine the final probe with the time window",
            scan.contains("val due = done == candidates.size || now - lastProgressAt.get() >= PROGRESS_INTERVAL_MS")
        )
        val due = scan.indexOf("if (due && !cancelled.get())")
        val report = scan.indexOf("invokeLater { progress(HdcScanProgress(done, candidates.size)) }")
        val nextBlock = scan.indexOf("if (done == candidates.size && !cancelled.get())")
        assertTrue("the progress event must exist", report >= 0)
        assertTrue("progress must sit behind the throttle", due in 0 until report)
        assertTrue("and it must precede the completion branch", nextBlock < 0 || report < nextBlock)
        assertEquals("exactly one progress emission point", 1, Regex("invokeLater \\{ progress\\(").findAll(scan).count())
        assertFalse("progress must not be emitted unconditionally per probe", scan.contains("invokeLater { progress(HdcScanProgress(completed.get()"))
    }
}

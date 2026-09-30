package com.xq.hdcwifi.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.xq.hdcwifi.HdcNotifications
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket

/**
 * Application-level facade over the hdc CLI. All blocking work runs on a
 * daemon executor; callbacks are delivered on the EDT.
 */
data class HdcScanProgress(val completed: Int, val total: Int)

/** [description] carries a caveat about the scan (for example an unreadable local range), or null. */
data class HdcScanResult(
    val addresses: List<String>,
    val description: String? = null,
    /** The network ranges that were actually covered, for the console log. */
    val ranges: List<String> = emptyList(),
    /** The ports probed on every host of those ranges, for the console log. */
    val ports: List<Int> = emptyList(),
    /** How many host:port pairs were probed in total. */
    val probed: Int = 0
)

class HdcScanHandle internal constructor(private val cancelled: AtomicBoolean, private val futures: List<Future<*>>) {
    fun cancel() {
        cancelled.set(true)
        futures.forEach { it.cancel(true) }
    }
    val isCancelled: Boolean get() = cancelled.get()
}

@Service
class HdcService : Disposable {

    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "hdc-worker").apply { isDaemon = true }
    }

    /**
     * Scanning gets its own fixed pool. Submitting thousands of probes to the cached pool
     * above would create one thread per probe and could exhaust native threads, besides
     * starving the hdc commands that share it.
     */
    private val scanExecutor: ExecutorService = Executors.newFixedThreadPool(SCAN_CONCURRENCY) { runnable ->
        Thread(runnable, "hdc-scan").apply { isDaemon = true }
    }

    private val activeStreams = CopyOnWriteArrayList<HdcStream>()
    private val streamLock = Any()
    private var streamsStopping = false
    private val commandListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val deviceInfoCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val deviceInfoCallbacks = java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<(Map<String, String>) -> Unit>>()

    fun addCommandListener(listener: (String) -> Unit) {
        commandListeners.add(listener)
    }

    fun removeCommandListener(listener: (String) -> Unit) {
        commandListeners.remove(listener)
    }

    fun submit(task: () -> Unit) {
        executor.submit {
            try {
                task()
            } catch (e: Exception) {
                invokeLater { HdcNotifications.error("Unexpected error: ${e.message ?: e}") }
            }
        }
    }

    /** Discover TCP endpoints on saved hosts and local networks. No settings are changed. */
    fun scanDevices(savedAddresses: List<String>, port: Int, customCidr: String = "", timeoutMs: Int = 350,
                    extraPorts: String = "",
                    progress: (HdcScanProgress) -> Unit, callback: (Result<HdcScanResult>) -> Unit): HdcScanHandle {
        val cancelled = AtomicBoolean(false)
        val candidates: List<Pair<String, Int>>
        val warning: String?
        val ranges: List<String>
        val ports: List<Int>
        try {
            val resolved = candidateTargets(savedAddresses, port, customCidr, extraPorts)
            candidates = resolved.targets
            warning = resolved.warning
            ranges = resolved.ranges
            ports = resolved.ports
        } catch (error: IllegalArgumentException) {
            invokeLater { callback(Result.failure(error)) }
            return HdcScanHandle(cancelled, emptyList())
        }
        if (candidates.isEmpty()) {
            val message = warning
                ?: "No network addresses are available to scan. Add a saved device or set a custom network range."
            invokeLater { callback(Result.failure(IllegalStateException(message))) }
            return HdcScanHandle(cancelled, emptyList())
        }
        val found = java.util.Collections.synchronizedList(mutableListOf<String>())
        val semaphore = Semaphore(SCAN_CONCURRENCY)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val lastProgressAt = java.util.concurrent.atomic.AtomicLong(0)
        val futures = candidates.map { (host, candidatePort) ->
            scanExecutor.submit {
                if (cancelled.get()) return@submit
                var acquired = false
                try {
                    semaphore.acquire()
                    acquired = true
                    if (cancelled.get()) return@submit
                    Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(host, candidatePort), timeoutMs)
                        if (!cancelled.get()) found.add("$host:$candidatePort")
                    }
                } catch (_: Exception) {
                    // A refused or timed-out probe is an expected scan result.
                } finally {
                    if (acquired) semaphore.release()
                    val done = completed.incrementAndGet()
                    // Progress is throttled by time: one event per probe would queue thousands of
                    // EDT tasks for a whole-subnet scan and make the UI stutter.
                    val now = System.currentTimeMillis()
                    val due = done == candidates.size || now - lastProgressAt.get() >= PROGRESS_INTERVAL_MS
                    if (due && !cancelled.get()) {
                        lastProgressAt.set(now)
                        invokeLater { progress(HdcScanProgress(done, candidates.size)) }
                    }
                    if (done == candidates.size && !cancelled.get()) invokeLater {
                        // A scan that found nothing because the local interfaces could not be
                        // read must not be reported as "nothing is out there".
                        if (warning != null && found.isEmpty()) {
                            callback(Result.failure(IllegalStateException(warning)))
                        } else {
                            callback(Result.success(HdcScanResult(found.distinct().sorted(), warning, ranges, ports, candidates.size)))
                        }
                    }
                }
            }
        }
        return HdcScanHandle(cancelled, futures)
    }

    /**
     * Candidate endpoints, deduplicated by `host:port`. A saved address keeps its own port —
     * it may deliberately live on a non-default one — while anything derived from a network
     * range uses the default port.
     */
    private fun candidateTargets(savedAddresses: List<String>, defaultPort: Int, customCidr: String,
                                 extraPorts: String): CandidateSet {
        val targets = linkedSetOf<Pair<String, Int>>()
        val savedPorts = linkedSetOf<Int>()
        savedAddresses.forEach { address ->
            val host = address.substringBeforeLast(':').trim()
            if (host.isEmpty()) return@forEach
            val savedPort = address.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
            savedPorts.add(savedPort)
            targets.add(host to savedPort)
        }
        // Network ranges are probed on every port we know about: the configured default, whatever
        // the user listed, and the ports of devices that were connected before. Wireless debugging
        // hands out a random high port, so probing the default alone misses those devices entirely.
        val ports = networkPorts(defaultPort, extraPorts, savedPorts)
        // A malformed user-supplied CIDR is reported to the user, but the machine's own ranges
        // are best-effort: one odd interface must not fail the whole scan.
        if (customCidr.isNotBlank()) {
            expandCidr(customCidr.trim()).forEach { host -> ports.forEach { targets.add(host to it) } }
        }
        val local = localNetworkCidrs()
        local?.forEach { cidr ->
            runCatching { expandCidr(cidr) }.getOrNull()?.forEach { host ->
                ports.forEach { targets.add(host to it) }
            }
        }
        return CandidateSet(
            targets.toList(),
            if (local == null) INTERFACE_ERROR else null,
            local.orEmpty(),
            ports
        )
    }

    /** Ports to probe on every host of a scanned network range, in a stable order. */
    private fun networkPorts(defaultPort: Int, extraPorts: String, savedPorts: Set<Int>): List<Int> {
        val ports = linkedSetOf(defaultPort.coerceIn(1, 65535))
        extraPorts.split(',', ' ', ';').forEach { token ->
            token.trim().toIntOrNull()?.takeIf { it in 1..65535 }?.let { ports.add(it) }
        }
        ports.addAll(savedPorts)
        return ports.take(MAX_SCAN_PORTS)
    }

    private data class CandidateSet(
        val targets: List<Pair<String, Int>>,
        val warning: String?,
        val ranges: List<String> = emptyList(),
        val ports: List<Int> = emptyList()
    )
    /**
     * The network each usable interface actually sits on, taken from its netmask.
     *
     * This used to be hardcoded to the first three octets (`/24`), which silently narrowed every
     * real network: on a `/23` interface only the upper half was scanned, so a device on the
     * lower half could never be found no matter how often the user pressed Scan.
     */
    private fun localNetworkCidrs(): List<String>? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { networkInterface ->
            if (!networkInterface.isUp || networkInterface.isLoopback) return@flatMap emptyList()
            networkInterface.interfaceAddresses.mapNotNull { interfaceAddress ->
                val address = interfaceAddress.address
                if (address !is Inet4Address) return@mapNotNull null
                if (address.isLoopbackAddress || address.isLinkLocalAddress) return@mapNotNull null
                networkCidr(address, interfaceAddress.networkPrefixLength.toInt())
            }
        }.distinct()
    }.getOrNull()

    /** Masks [address] with [prefixLength] to produce a canonical `a.b.c.d/n` network. */
    private fun networkCidr(address: Inet4Address, prefixLength: Int): String? {
        val octets = address.address
        if (octets.size != 4 || prefixLength !in 0..32) return null
        val value = octets.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        val mask = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        val network = value and mask
        // The suffix is appended rather than handed to `joinToString`: its second positional
        // parameter is `prefix`, so `joinToString(".", "/$prefixLength")` emitted
        // "/23172.16.0.0" — an unparseable range that silently scanned nothing at all.
        val host = listOf(network shr 24, network shr 16, network shr 8, network)
            .joinToString(".") { (it and 0xFF).toString() }
        return "$host/$prefixLength"
    }

    private fun expandCidr(cidr: String): List<String> {
        val parts = cidr.split('/', limit = 2)
        val octets = parts.getOrNull(0)?.split('.')?.map { it.toIntOrNull() }
        val prefix = parts.getOrNull(1)?.toIntOrNull()
        if (octets == null || octets.size != 4 || octets.any { it == null || it !in 0..255 } || prefix !in 0..32) {
            throw IllegalArgumentException("Cannot scan $cidr: use an IPv4 network with at most 4096 addresses.")
        }
        val count = 1L shl (32 - prefix!!)
        if (count > MAX_SCAN_ADDRESSES) {
            throw IllegalArgumentException("Cannot scan $cidr: use an IPv4 network with at most 4096 addresses.")
        }
        val raw = octets.map { it!! }.fold(0L) { value, octet -> (value shl 8) or octet.toLong() }
        val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val network = raw and mask
        val first = if (count > 2) 1L else 0L
        val lastExclusive = if (count > 2) count - 1 else count
        return (first until lastExclusive).map { offset ->
            val value = network + offset
            listOf(value shr 24, value shr 16, value shr 8, value).joinToString(".") { (it and 255).toString() }
        }
    }

    fun listTargets(logCommand: Boolean = false, callback: (Result<List<String>>) -> Unit) {
        submit {
            val result = runCatching {
                if (logCommand) runLogged(listOf("list", "targets"), TIMEOUT_LIST)
                else HdcCommandRunner.run(listOf("list", "targets"), TIMEOUT_LIST)
            }
            invokeLater {
                result.fold(
                    onSuccess = { res ->
                        if (res.ok) {
                            callback(Result.success(HdcTargetParser.parse(res.output)))
                        } else {
                            callback(Result.failure(IllegalStateException(res.output.trim().ifEmpty { "hdc list targets failed (exit ${res.exitCode})" })))
                        }
                    },
                    onFailure = { callback(Result.failure(it)) }
                )
            }
        }
    }

    fun connect(host: String, port: Int, callback: (Boolean, String) -> Unit) {
        submit {
            val address = "$host:$port"
            val result = runCatching {
                var connection = runLogged(listOf("tconn", address), TIMEOUT_CONNECT)
                var online = waitForOnlineTarget(address)
                if (connectionNeedsRecovery(connection.exitCode, connection.output, online)) {
                    publishCommand("[Info] HDC connection state is stale. Removing this target and retrying...")
                    runLogged(listOf("tconn", address, "-remove"), TIMEOUT_LIST)
                    connection = runLogged(listOf("tconn", address), TIMEOUT_CONNECT)
                    online = waitForOnlineTarget(address)
                    if (connectionNeedsRecovery(connection.exitCode, connection.output, online)) {
                        publishCommand("[Info] Target recovery failed. Restarting the HDC server and retrying once...")
                        stopAllStreams()
                        runLogged(listOf("kill"), TIMEOUT_LIST)
                        connection = runLogged(listOf("tconn", address), TIMEOUT_CONNECT)
                        online = waitForOnlineTarget(address)
                    }
                }
                connection to online
            }
            invokeLater {
                result.fold(
                    onSuccess = { (res, online) ->
                        val raw = res.output.trim().ifEmpty { "exit ${res.exitCode}" }
                        val out = if (online && !connectAccepted(res.exitCode, res.output)) {
                            "[Info] Target $address is online."
                        } else raw
                        val message = if (online) out else "$out\nTarget $address is not present in the online target list."
                        callback(online, message)
                    },
                    onFailure = { callback(false, it.message ?: it.toString()) }
                )
            }
        }
    }

    fun disconnect(target: String, callback: (Boolean, String) -> Unit) {
        submit {
            val result = runCatching { runLogged(listOf("tconn", target, "-remove"), TIMEOUT_LIST) }
            invokeLater {
                result.fold(
                    onSuccess = { res ->
                        val out = res.output.trim()
                        callback(disconnectAccepted(res.exitCode, out), out.ifEmpty { "exit ${res.exitCode}" })
                    },
                    onFailure = { callback(false, it.message ?: it.toString()) }
                )
            }
        }
    }

    fun killServer(callback: () -> Unit) {
        stopAllStreams()
        submit {
            runCatching { runLogged(listOf("kill"), TIMEOUT_LIST) }
            invokeLater(callback)
        }
    }

    fun shell(target: String?, command: String, callback: (HdcResult) -> Unit) {
        submit {
            val args = buildList {
                if (target != null) {
                    add("-t"); add(target)
                }
                add("shell"); add(command)
            }
            val result = runCatching { runLogged(args, TIMEOUT_SHELL) }
            invokeLater {
                callback(result.getOrElse { HdcResult(-1, it.message ?: it.toString()) })
            }
        }
    }

    /** Fetch common HarmonyOS system parameters for the Info tab. */
    fun deviceInfo(target: String, forceRefresh: Boolean = false, callback: (Map<String, String>) -> Unit) {
        if (forceRefresh) deviceInfoCache.remove(target)
        deviceInfoCache[target]?.let {
            invokeLater { callback(it) }
            return
        }
        val newCallbacks = CopyOnWriteArrayList<(Map<String, String>) -> Unit>()
        val callbacks = deviceInfoCallbacks.putIfAbsent(target, newCallbacks) ?: newCallbacks
        callbacks.add(callback)
        if (callbacks !== newCallbacks) return
        val params = linkedMapOf(
            "Device type" to "const.product.devicetype",
            "Product name" to "const.product.name",
            "Model" to "const.product.model",
            "Brand" to "const.product.brand",
            "Manufacturer" to "const.product.manufacturer",
            "Software version" to "const.product.software.version",
            "System release" to "const.build.version.release",
            "API version" to "const.ohos.apiversion",
            "Build display id" to "const.build.display.id"
        )
        submit {
            val info = linkedMapOf<String, String>()
            info["Target"] = target
            for ((label, key) in params) {
                val value = runCatching {
                    val res = HdcCommandRunner.run(listOf("-t", target, "shell", "param get $key"), TIMEOUT_INFO)
                    val text = res.output.trim()
                    val unusable = text.isEmpty() || text.equals("empty", ignoreCase = true) ||
                        text.contains("fail", ignoreCase = true) || text.startsWith("[")
                    if (res.ok && !unusable) text else "-"
                }.getOrDefault("-")
                info[label] = value
            }
            val system = displaySystemName(info)
            val ordered = linkedMapOf<String, String>()
            ordered["Target"] = target
            ordered["System"] = system
            info.filterKeys { it != "Target" }.forEach { (key, value) -> ordered[key] = value }
            deviceInfoCache[target] = ordered
            val waiting = deviceInfoCallbacks.remove(target).orEmpty()
            invokeLater { waiting.forEach { it(ordered) } }
        }
    }

    private fun displaySystemName(info: Map<String, String>): String {
        val software = info["Software version"].orEmpty()
        val release = info["System release"].orEmpty().takeUnless { it == "-" }.orEmpty()
        val harmonyVersion = Regex("(?<![A-Za-z0-9])([2-9]\\d*(?:\\.\\d+){1,3})(?:\\(|$)")
            .find(software)?.groupValues?.get(1)
        return when {
            harmonyVersion != null -> "HarmonyOS $harmonyVersion"
            software.contains("OpenHarmony", ignoreCase = true) ->
                listOf("OpenHarmony", release).filter { it.isNotBlank() }.joinToString(" ")
            release.isNotBlank() -> "HarmonyOS $release"
            else -> "HarmonyOS"
        }
    }

    /** Stream `shell hilog` for a target (or server-wide when target is null). */
    fun startHilog(target: String?, onLine: (String) -> Unit, onExit: (Int) -> Unit): HdcStream? {
        val args = buildList {
            if (target != null) {
                add("-t"); add(target)
            }
            add("shell"); add("hilog")
        }
        return startStream(args, onLine, onExit)
    }

    fun startFileSend(target: String, local: String, remote: String, onLine: (String) -> Unit, onExit: (Int) -> Unit): HdcStream? {
        return startStream(listOf("-t", target, "file", "send", local, remote), onLine, onExit)
    }

    fun startFileRecv(target: String, remote: String, local: String, onLine: (String) -> Unit, onExit: (Int) -> Unit): HdcStream? {
        return startStream(listOf("-t", target, "file", "recv", remote, local), onLine, onExit)
    }

    private fun startStream(args: List<String>, onLine: (String) -> Unit, onExit: (Int) -> Unit): HdcStream? {
        publishCommand("> hdc ${args.joinToString(" ")}")
        val stream = try {
            HdcCommandRunner.startStream(args)
        } catch (e: Exception) {
            invokeLater { HdcNotifications.error("Cannot start hdc: ${e.message ?: e}") }
            return null
        }
        synchronized(streamLock) {
            if (streamsStopping) {
                stream.stop()
                return null
            }
            activeStreams.add(stream)
        }
        stream.readLines(
            onLine = { line -> invokeLater { onLine(line) } },
            onExit = { code ->
                synchronized(streamLock) { activeStreams.remove(stream) }
                publishCommand("[process exited with code $code]")
                invokeLater { onExit(code) }
            }
        )
        return stream
    }

    fun stopAllStreams() {
        val streams = synchronized(streamLock) {
            streamsStopping = true
            activeStreams.toList().also { activeStreams.clear() }
        }
        try {
            streams.forEach { it.stop() }
        } finally {
            synchronized(streamLock) { streamsStopping = false }
        }
    }

    private fun runLogged(args: List<String>, timeoutSeconds: Long): HdcResult {
        publishCommand("> hdc ${args.joinToString(" ")}")
        val result = HdcCommandRunner.run(args, timeoutSeconds)
        val output = result.output.trimEnd()
        if (output.isNotEmpty()) publishCommand(output)
        if (!result.ok) publishCommand("[exit ${result.exitCode}]")
        return result
    }

    private fun waitForOnlineTarget(address: String): Boolean {
        repeat(ONLINE_CHECK_ATTEMPTS) { attempt ->
            val result = HdcCommandRunner.run(listOf("list", "targets"), TIMEOUT_LIST)
            if (result.ok && address in HdcTargetParser.parse(result.output)) return true
            if (attempt < ONLINE_CHECK_ATTEMPTS - 1) Thread.sleep(ONLINE_CHECK_DELAY_MS)
        }
        return false
    }

    private fun publishCommand(text: String) {
        invokeLater { commandListeners.forEach { it(text) } }
    }

    override fun dispose() {
        stopAllStreams()
        commandListeners.clear()
        executor.shutdownNow()
        scanExecutor.shutdownNow()
    }

    companion object {
        private const val TIMEOUT_LIST = 20L

        /**
         * hdc tconn prints "Connect ok!" on success, "[Fail]..." on failure, and may
         * report an existing link with wording that is not a real error.
         */
        fun connectAccepted(exitCode: Int, output: String): Boolean {
            val lower = output.lowercase()
            if (exitCode != 0 || lower.contains("fail") || lower.contains("error") || lower.contains("not connected")) {
                return false
            }
            return lower.contains("ok") || lower.contains("already") || lower.contains("connected")
        }

        fun connectionNeedsRecovery(exitCode: Int, output: String, online: Boolean): Boolean {
            if (online) return false
            val lower = output.lowercase()
            return (exitCode == -1 && lower.contains("timed out")) ||
                (connectAccepted(exitCode, output) &&
                    (lower.contains("repeat operation") || lower.contains("already") || lower.contains("connected")))
        }

        fun disconnectAccepted(exitCode: Int, output: String): Boolean {
            return exitCode == 0 && !outputIndicatesFailure(output)
        }

        private const val MAX_SCAN_ADDRESSES = 4096L

        /**
         * Upper bound on the number of distinct ports probed on every host of a scanned range.
         * Each extra port multiplies the probe count, so the list is capped rather than trusted.
         */
        private const val MAX_SCAN_PORTS = 6

        /** Shown when the machine's own interfaces cannot be enumerated at all. */
        internal const val INTERFACE_ERROR =
            "Unable to read local network interfaces. Set a custom network range in Settings to scan anyway."

        /** Upper bound on concurrent TCP probes during a scan. */
        internal const val SCAN_CONCURRENCY = 64

        /** Minimum gap between progress events, so a whole-subnet scan cannot flood the EDT. */
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val TIMEOUT_CONNECT = 20L
        private const val TIMEOUT_SHELL = 60L
        private const val TIMEOUT_INFO = 15L
        private const val ONLINE_CHECK_ATTEMPTS = 4
        private const val ONLINE_CHECK_DELAY_MS = 250L

        val instance: HdcService
            get() = service()

        private fun invokeLater(action: () -> Unit) {
            if (ApplicationManager.getApplication().isDispatchThread) {
                action()
            } else {
                ApplicationManager.getApplication().invokeLater(action)
            }
        }
    }
}

package com.xq.hdcwifi.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.xq.hdcwifi.model.HdcDevice

@Service
@State(name = "HdcWifiSettings", storages = [Storage("hdc-wifi.xml")])
class HdcSettings : PersistentStateComponent<HdcSettings.State> {

    class State {
        var hdcPath: String = ""
        var defaultPort: Int = DEFAULT_PORT
        var autoRefreshSeconds: Int = 0
        var autoConnectDiscovered: Boolean = false
        var autoConnectSaved: Boolean = false
        var customScanCidr: String = ""
        var scanPorts: String = ""
        var recentHosts: MutableList<String> = mutableListOf()
        var devices: MutableList<SavedDeviceState> = mutableListOf()
    }

    class SavedDeviceState {
        var address: String = ""
        var name: String = ""
        var systemVersion: String = ""
        var apiVersion: String = ""
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        if (myState.devices.isEmpty() && myState.recentHosts.isNotEmpty()) {
            myState.recentHosts.forEach { host -> rememberDevice(withDefaultPort(host)) }
        }
    }

    /** Explicit path configured by the user; blank means auto-detect. */
    var hdcPath: String
        get() = myState.hdcPath
        set(value) {
            myState.hdcPath = value.trim()
        }

    var defaultPort: Int
        get() = myState.defaultPort.coerceIn(1, 65535)
        set(value) {
            myState.defaultPort = value
        }

    var autoRefreshSeconds: Int
        get() = myState.autoRefreshSeconds.coerceIn(0, 3600)
        set(value) {
            myState.autoRefreshSeconds = value.coerceIn(0, 3600)
        }

    var autoConnectDiscovered: Boolean
        get() = myState.autoConnectDiscovered
        set(value) { myState.autoConnectDiscovered = value }

    var autoConnectSaved: Boolean
        get() = myState.autoConnectSaved
        set(value) { myState.autoConnectSaved = value }

    var customScanCidr: String
        get() = myState.customScanCidr
        set(value) { myState.customScanCidr = value.trim() }

    /**
     * Extra TCP ports to probe across every scanned host, comma or space separated. Wireless
     * debugging often hands out a random high port, so the default port alone cannot find a
     * device that has never been connected before. Ports remembered from previously connected
     * devices are added automatically on top of this.
     */
    var scanPorts: String
        get() = myState.scanPorts
        set(value) { myState.scanPorts = value.trim() }

    fun recentHosts(): List<String> = myState.recentHosts.toList()

    fun rememberHost(host: String) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        myState.recentHosts.remove(trimmed)
        myState.recentHosts.add(0, trimmed)
        while (myState.recentHosts.size > MAX_RECENT_HOSTS) {
            myState.recentHosts.removeAt(myState.recentHosts.size - 1)
        }
    }

    fun savedDevices(): List<HdcDevice> {
        return myState.devices.filter { it.address.isNotBlank() }.map { it.toDevice() }
    }

    fun rememberDevice(address: String) {
        val normalized = address.trim()
        if (normalized.isEmpty() || myState.devices.any { it.address == normalized }) return
        myState.devices.add(SavedDeviceState().apply {
            this.address = normalized
            name = normalized.substringBefore(':')
        })
    }

    fun updateDevice(address: String, name: String, systemVersion: String, apiVersion: String) {
        myState.devices.firstOrNull { it.address == address }?.apply {
            if (name.isNotBlank() && name != "-") this.name = name
            if (systemVersion.isNotBlank() && systemVersion != "-") this.systemVersion = systemVersion
            if (apiVersion.isNotBlank() && apiVersion != "-") this.apiVersion = apiVersion
        }
    }

    fun removeDevice(address: String) {
        myState.devices.removeIf { it.address == address }
        val host = address.substringBefore(':')
        myState.recentHosts.removeIf { it == host }
    }

    private fun SavedDeviceState.toDevice(): HdcDevice = HdcDevice(
        address = address,
        name = name.ifBlank { address.substringBefore(':') },
        systemVersion = systemVersion,
        apiVersion = apiVersion
    )

    private fun withDefaultPort(host: String): String {
        return if (host.contains(':')) host else "$host:$defaultPort"
    }

    companion object {
        const val DEFAULT_PORT = 5555
        private const val MAX_RECENT_HOSTS = 10

        val instance: HdcSettings
            get() = service()
    }
}

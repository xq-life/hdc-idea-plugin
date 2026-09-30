package com.xq.hdcwifi.model

enum class DeviceConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class HdcDevice(
    val address: String,
    val name: String = address,
    val systemVersion: String = "",
    val apiVersion: String = "",
    val connected: Boolean = false,
    val state: DeviceConnectionState = if (connected) DeviceConnectionState.CONNECTED else DeviceConnectionState.DISCONNECTED,
    val failureReason: String? = null
) {
    val details: String
        get() = when {
            systemVersion.isNotBlank() && apiVersion.isNotBlank() -> "$systemVersion (API $apiVersion) - $address"
            systemVersion.isNotBlank() -> "$systemVersion - $address"
            apiVersion.isNotBlank() -> "API $apiVersion - $address"
            else -> "HarmonyOS 设备 - $address"
        }

    val isConnected: Boolean get() = state == DeviceConnectionState.CONNECTED
}

data class DeviceGroups(
    val connected: List<HdcDevice>,
    val previous: List<HdcDevice>,
    val available: List<HdcDevice> = emptyList()
)

fun groupDevices(saved: List<HdcDevice>, connectedTargets: Collection<String>): DeviceGroups {
    val targets = connectedTargets.toSet()
    val savedByAddress = saved.associateBy { it.address }
    val connected = targets.map { address ->
        (savedByAddress[address] ?: HdcDevice(address)).copy(
            connected = true, state = DeviceConnectionState.CONNECTED, failureReason = null
        )
    }.sortedBy { it.name.lowercase() }
    val previous = saved.filterNot { it.address in targets }
        .map { it.copy(connected = false, state = if (it.state == DeviceConnectionState.FAILED) it.state else DeviceConnectionState.DISCONNECTED) }
        .sortedBy { it.name.lowercase() }
    return DeviceGroups(connected, previous)
}

fun HdcDevice.withState(newState: DeviceConnectionState, reason: String? = null): HdcDevice = copy(
    connected = newState == DeviceConnectionState.CONNECTED,
    state = newState,
    failureReason = reason
)

/**
 * Identity of what the device panel would render, used to skip rebuilding it when nothing
 * changed. Group membership is part of the identity (a device moving between groups is a
 * visible change), and field separators keep neighbouring values from being confused with
 * one another — concatenating raw fields would let different layouts produce equal strings.
 */
fun deviceSignature(groups: DeviceGroups): String = listOf(
    groups.connected,
    groups.previous,
    groups.available
).joinToString(GROUP_SEPARATOR) { devices ->
    devices.joinToString(FIELD_SEPARATOR) { device ->
        listOf(
            device.address,
            device.name,
            device.details,
            device.state.name,
            // A missing reason and an empty one render differently (no tooltip vs
            // "Unknown error."), so they must not collapse into the same signature.
            if (device.failureReason == null) NO_REASON else "$HAS_REASON${device.failureReason}"
        ).joinToString(FIELD_SEPARATOR)
    }
}

private const val GROUP_SEPARATOR = "\u0000G\u0000"
private const val FIELD_SEPARATOR = "\u0000F\u0000"
private const val NO_REASON = "\u0000none\u0000"
private const val HAS_REASON = "\u0000some\u0000"

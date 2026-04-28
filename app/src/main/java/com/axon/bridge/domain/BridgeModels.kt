package com.axon.bridge.domain

import kotlinx.serialization.Serializable

@Serializable
enum class BridgeRole {
    Source,
    Sink
}

enum class BridgeConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Error
}

data class PermissionStatus(
    val label: String,
    val value: String,
    val granted: Boolean
)

data class DeviceInfo(
    val manufacturer: String = "",
    val model: String = "",
    val displayName: String = ""
)

data class HomeState(
    val role: BridgeRole = BridgeRole.Sink,
    val connectionState: BridgeConnectionState = BridgeConnectionState.Disconnected,
    val serverIp: String = "",
    val localIp: String = "",
    val deviceInfo: DeviceInfo = DeviceInfo(),
    val peerDeviceName: String = "",
    val activeTargetIp: String = "",
    val lastEventTimeMillis: Long = 0L,
    val isBridgeRunning: Boolean = false,
    val permissions: List<PermissionStatus> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val smsThreads: List<SmsThread> = emptyList(),
    val errorMessage: String? = null
)

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

enum class BridgeTransportMode {
    Lan,
    Ntfy
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
    val transportMode: BridgeTransportMode = BridgeTransportMode.Lan,
    val ntfySettings: NtfySettings = NtfySettings(),
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
    val callLogs: List<CallLogEntry> = emptyList(),
    val activeCall: NotificationPayload? = null,
    val activeMedia: MediaPayload? = null,
    val activeMediaUpdatedAtElapsed: Long = 0L,
    val isScanningReceivers: Boolean = false,
    val discoveredReceivers: List<DiscoveredReceiver> = emptyList(),
    val errorMessage: String? = null
)

data class NtfySettings(
    val serverUrl: String = "",
    val pairId: String = "",
    val pairSecret: String = "",
    val username: String = "",
    val password: String = "",
    val topicPrefix: String = "axon"
) {
    val senderToReceiverTopic: String
        get() = topicName("to-receiver")

    val receiverToSenderTopic: String
        get() = topicName("to-sender")

    private fun topicName(suffix: String): String {
        val normalizedPrefix = topicPrefix.toNtfyTopicSegment().ifBlank { "axon" }
        val normalizedPairId = pairId.toNtfyTopicSegment()
        return listOf(normalizedPrefix, normalizedPairId, suffix)
            .filter { it.isNotBlank() }
            .joinToString("-")
    }
}

fun String.toNtfyTopicSegment(): String {
    return trim()
        .lowercase()
        .map { character ->
            when {
                character.isLetterOrDigit() -> character
                character == '-' || character == '_' -> character
                else -> '-'
            }
        }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-', '_')
        .take(48)
}

@kotlinx.serialization.Serializable
data class CallLogEntry(
    val id: String,
    val caller: String,
    val message: String,
    val originDevice: String,
    val startedAt: Long,
    val updatedAt: Long,
    val state: CallState
)

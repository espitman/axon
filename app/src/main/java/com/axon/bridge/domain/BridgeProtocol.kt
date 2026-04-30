package com.axon.bridge.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BridgeMessage(
    val type: BridgeMessageType,
    val payload: NotificationPayload? = null,
    val hello: HelloPayload? = null,
    val media: MediaPayload? = null,
    val command: MediaCommandPayload? = null,
    val callCommand: CallCommandPayload? = null
)

@Serializable
enum class BridgeMessageType {
    @SerialName("HELLO")
    Hello,

    @SerialName("NOTIFICATION_EVENT")
    NotificationEvent,

    @SerialName("PING")
    Ping,

    @SerialName("ACK")
    Ack,

    @SerialName("MEDIA_UPDATE")
    MediaUpdate,

    @SerialName("MEDIA_COMMAND")
    MediaCommand,

    @SerialName("MEDIA_CLEAR")
    MediaClear,

    @SerialName("CALL_COMMAND")
    CallCommand
}

@Serializable
data class HelloPayload(
    val role: BridgeRole,
    val deviceName: String,
    val appVersion: String = "0.1.0"
)

@Serializable
data class DiscoveryResponse(
    val app: String = "Axon",
    val role: BridgeRole = BridgeRole.Sink,
    val deviceName: String,
    val port: Int
)

@Serializable
data class NotificationPayload(
    val id: String,
    val category: NotificationCategory,
    val originDevice: String,
    val title: String,
    val message: String,
    val packageName: String,
    val postedTime: Long,
    val callState: CallState? = null
)

@Serializable
data class MediaPayload(
    val title: String,
    val artist: String,
    val album: String = "",
    val duration: Long,
    val position: Long,
    val playbackSpeed: Float,
    val isPlaying: Boolean,
    val lastPositionUpdateTime: Long,
    val packageName: String,
    val artworkBase64: String? = null
)

@Serializable
data class MediaCommandPayload(
    val action: MediaCommandAction
)

@Serializable
data class CallCommandPayload(
    val action: CallCommandAction
)

@Serializable
enum class CallCommandAction {
    @SerialName("REJECT")
    Reject
}

@Serializable
enum class MediaCommandAction {
    @SerialName("PLAY")
    Play,

    @SerialName("PAUSE")
    Pause,

    @SerialName("SKIP_TO_NEXT")
    SkipToNext,

    @SerialName("SKIP_TO_PREVIOUS")
    SkipToPrevious
}

@Serializable
enum class NotificationCategory {
    @SerialName("SMS")
    Sms,

    @SerialName("CALL")
    Call
}

@Serializable
enum class CallState {
    @SerialName("RINGING")
    Ringing,

    @SerialName("IN_CALL")
    InCall,

    @SerialName("ENDED")
    Ended
}

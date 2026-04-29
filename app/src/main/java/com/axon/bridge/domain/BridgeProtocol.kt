package com.axon.bridge.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BridgeMessage(
    val type: BridgeMessageType,
    val payload: NotificationPayload? = null,
    val hello: HelloPayload? = null
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
    Ack
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
    val postedTime: Long
)

@Serializable
enum class NotificationCategory {
    @SerialName("SMS")
    Sms,

    @SerialName("CALL")
    Call
}

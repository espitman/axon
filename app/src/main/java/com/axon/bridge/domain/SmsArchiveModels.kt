package com.axon.bridge.domain

import kotlinx.serialization.Serializable

@Serializable
data class SmsArchiveMessage(
    val id: String,
    val threadId: String,
    val sender: String,
    val body: String,
    val originDevice: String,
    val receivedAt: Long,
    val unread: Boolean = true
)

data class SmsThread(
    val id: String,
    val sender: String,
    val lastMessage: String,
    val lastReceivedAt: Long,
    val unreadCount: Int,
    val messageCount: Int
)

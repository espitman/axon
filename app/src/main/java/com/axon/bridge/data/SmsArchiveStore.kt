package com.axon.bridge.data

import android.content.Context
import com.axon.bridge.domain.NotificationCategory
import com.axon.bridge.domain.NotificationPayload
import com.axon.bridge.domain.SmsArchiveMessage
import com.axon.bridge.domain.SmsThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale

object SmsArchiveStore {
    private const val PREFS_NAME = "sms_archive"
    private const val KEY_MESSAGES = "messages"
    private const val MAX_MESSAGES = 500

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutableMessages = MutableStateFlow<List<SmsArchiveMessage>>(emptyList())
    private var initialized = false

    val messages: StateFlow<List<SmsArchiveMessage>> = mutableMessages

    fun init(context: Context) {
        if (initialized) return
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MESSAGES, null)
        mutableMessages.value = raw?.let { saved ->
            runCatching {
                json.decodeFromString(ListSerializer(SmsArchiveMessage.serializer()), saved)
            }.getOrDefault(emptyList())
        }.orEmpty()
        initialized = true
    }

    fun add(context: Context, payload: NotificationPayload) {
        if (payload.category != NotificationCategory.Sms) return
        init(context)
        val sender = payload.title.ifBlank { "Unknown sender" }
        val body = payload.message.trim()
        if (body.isBlank()) return

        val message = SmsArchiveMessage(
            id = payload.id,
            threadId = sender.threadId(),
            sender = sender,
            body = body,
            originDevice = payload.originDevice,
            receivedAt = payload.postedTime
        )
        val next = (listOf(message) + mutableMessages.value.filterNot { it.id == message.id })
            .sortedByDescending { it.receivedAt }
            .take(MAX_MESSAGES)
        mutableMessages.value = next
        save(context, next)
    }

    fun markThreadRead(context: Context, threadId: String) {
        init(context)
        val next = mutableMessages.value.map { message ->
            if (message.threadId == threadId) message.copy(unread = false) else message
        }
        mutableMessages.value = next
        save(context, next)
    }

    fun threads(): List<SmsThread> {
        return mutableMessages.value
            .groupBy { it.threadId }
            .mapNotNull { (threadId, messages) ->
                val sorted = messages.sortedByDescending { it.receivedAt }
                val latest = sorted.firstOrNull() ?: return@mapNotNull null
                SmsThread(
                    id = threadId,
                    sender = latest.sender,
                    lastMessage = latest.body,
                    lastReceivedAt = latest.receivedAt,
                    unreadCount = sorted.count { it.unread },
                    messageCount = sorted.size
                )
            }
            .sortedByDescending { it.lastReceivedAt }
    }

    fun threadMessages(threadId: String): List<SmsArchiveMessage> {
        return mutableMessages.value
            .filter { it.threadId == threadId }
            .sortedBy { it.receivedAt }
    }

    private fun save(context: Context, messages: List<SmsArchiveMessage>) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MESSAGES, json.encodeToString(ListSerializer(SmsArchiveMessage.serializer()), messages))
            .apply()
    }

    private fun String.threadId(): String {
        return trim().lowercase(Locale.ROOT).ifBlank { "unknown" }
    }
}

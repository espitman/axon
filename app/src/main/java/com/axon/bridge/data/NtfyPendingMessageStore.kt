package com.axon.bridge.data

import android.content.Context
import com.axon.bridge.domain.BridgeMessage
import com.axon.bridge.domain.BridgeRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class NtfyPendingMessageStore(context: Context) {
    private val preferences = context.getSharedPreferences("axon_ntfy_pending", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun load(): List<NtfyPendingMessage> {
        val saved = preferences.getString(KEY_PENDING, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(NtfyPendingMessage.serializer()), saved)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(messages: List<NtfyPendingMessage>) {
        preferences.edit()
            .putString(
                KEY_PENDING,
                json.encodeToString(
                    ListSerializer(NtfyPendingMessage.serializer()),
                    messages.takeLast(MAX_STORED_MESSAGES)
                )
            )
            .apply()
    }

    companion object {
        private const val KEY_PENDING = "pending_messages"
        private const val MAX_STORED_MESSAGES = 64
    }
}

@Serializable
data class NtfyPendingMessage(
    val message: BridgeMessage,
    val topic: String,
    val targetRole: BridgeRole,
    val queuedAt: Long = System.currentTimeMillis()
)

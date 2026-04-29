package com.axon.bridge.data

import android.content.Context
import com.axon.bridge.domain.CallLogEntry
import com.axon.bridge.domain.CallState
import com.axon.bridge.domain.NotificationCategory
import com.axon.bridge.domain.NotificationPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object CallAlertStore {
    private const val PREFS_NAME = "call_archive"
    private const val KEY_CALLS = "calls"
    private const val MAX_CALLS = 100

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutableActiveCall = MutableStateFlow<NotificationPayload?>(null)
    private val mutableCalls = MutableStateFlow<List<CallLogEntry>>(emptyList())
    private var initialized = false

    val activeCall: StateFlow<NotificationPayload?> = mutableActiveCall
    val calls: StateFlow<List<CallLogEntry>> = mutableCalls

    fun init(context: Context) {
        if (initialized) return
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CALLS, null)
        mutableCalls.value = raw?.let { saved ->
            runCatching {
                json.decodeFromString(ListSerializer(CallLogEntry.serializer()), saved)
            }.getOrDefault(emptyList())
        }.orEmpty()
        initialized = true
    }

    fun update(context: Context, payload: NotificationPayload) {
        if (payload.category != NotificationCategory.Call) return
        init(context)
        val state = payload.callState ?: CallState.Ringing
        val current = mutableCalls.value.firstOrNull { it.id == payload.id }
        val entry = CallLogEntry(
            id = payload.id,
            caller = payload.title.ifBlank { "Unknown caller" },
            message = payload.message.ifBlank { state.label },
            originDevice = payload.originDevice,
            startedAt = current?.startedAt ?: payload.postedTime,
            updatedAt = payload.postedTime,
            state = state
        )
        val next = (listOf(entry) + mutableCalls.value.filterNot { it.id == payload.id })
            .sortedByDescending { it.updatedAt }
            .take(MAX_CALLS)
        mutableCalls.value = next
        save(context, next)

        mutableActiveCall.value = if (state == CallState.Ended) {
            null
        } else {
            payload
        }
    }

    fun clearActive() {
        mutableActiveCall.value = null
    }

    fun delete(context: Context, callId: String) {
        init(context)
        val next = mutableCalls.value.filterNot { it.id == callId }
        mutableCalls.value = next
        save(context, next)
        if (mutableActiveCall.value?.id == callId) {
            mutableActiveCall.value = null
        }
    }

    fun clear(context: Context) {
        init(context)
        mutableCalls.value = emptyList()
        mutableActiveCall.value = null
        save(context, emptyList())
    }

    private fun save(context: Context, calls: List<CallLogEntry>) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CALLS, json.encodeToString(ListSerializer(CallLogEntry.serializer()), calls))
            .apply()
    }

    private val CallState.label: String
        get() = when (this) {
            CallState.Ringing -> "Incoming call"
            CallState.InCall -> "In call"
            CallState.Ended -> "Call ended"
        }
}

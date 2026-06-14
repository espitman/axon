package com.axon.bridge.data

import android.content.Context
import com.axon.bridge.domain.BridgeRole
import com.axon.bridge.domain.BridgeTransportMode
import com.axon.bridge.domain.NtfySettings
import java.util.UUID

class AxonSettings(context: Context) {
    private val preferences = context.getSharedPreferences("axon_settings", Context.MODE_PRIVATE)

    var role: BridgeRole
        get() = runCatching {
            BridgeRole.valueOf(preferences.getString(KEY_ROLE, BridgeRole.Sink.name) ?: BridgeRole.Sink.name)
        }.getOrDefault(BridgeRole.Sink)
        set(value) {
            preferences.edit().putString(KEY_ROLE, value.name).apply()
        }

    var serverIp: String
        get() = preferences.getString(KEY_SERVER_IP, "") ?: ""
        set(value) {
            preferences.edit().putString(KEY_SERVER_IP, value.trim()).apply()
        }

    var transportMode: BridgeTransportMode
        get() = runCatching {
            BridgeTransportMode.valueOf(
                preferences.getString(KEY_TRANSPORT_MODE, BridgeTransportMode.Lan.name)
                    ?: BridgeTransportMode.Lan.name
            )
        }.getOrDefault(BridgeTransportMode.Lan)
        set(value) {
            preferences.edit().putString(KEY_TRANSPORT_MODE, value.name).apply()
        }

    var ntfyServerUrl: String
        get() = preferences.getString(KEY_NTFY_SERVER_URL, DEFAULT_NTFY_SERVER_URL) ?: DEFAULT_NTFY_SERVER_URL
        set(value) {
            preferences.edit().putString(KEY_NTFY_SERVER_URL, value.trim()).apply()
        }

    var ntfyPairId: String
        get() {
            val savedPairId = preferences.getString(KEY_NTFY_PAIR_ID, "")?.trim().orEmpty()
            if (savedPairId.isNotBlank()) return savedPairId
            val generatedPairId = generatePairId()
            preferences.edit().putString(KEY_NTFY_PAIR_ID, generatedPairId).apply()
            return generatedPairId
        }
        set(value) {
            preferences.edit().putString(KEY_NTFY_PAIR_ID, value.trim()).apply()
        }

    var ntfyUsername: String
        get() = preferences.getString(KEY_NTFY_USERNAME, "") ?: ""
        set(value) {
            preferences.edit().putString(KEY_NTFY_USERNAME, value.trim()).apply()
        }

    var ntfyPassword: String
        get() = preferences.getString(KEY_NTFY_PASSWORD, "") ?: ""
        set(value) {
            preferences.edit().putString(KEY_NTFY_PASSWORD, value).apply()
        }

    var ntfyTopicPrefix: String
        get() = preferences.getString(KEY_NTFY_TOPIC_PREFIX, DEFAULT_NTFY_TOPIC_PREFIX) ?: DEFAULT_NTFY_TOPIC_PREFIX
        set(value) {
            preferences.edit().putString(KEY_NTFY_TOPIC_PREFIX, value.trim()).apply()
        }

    val deviceId: String
        get() {
            val savedDeviceId = preferences.getString(KEY_DEVICE_ID, "")?.trim().orEmpty()
            if (savedDeviceId.isNotBlank()) return savedDeviceId
            val generatedDeviceId = UUID.randomUUID().toString()
            preferences.edit().putString(KEY_DEVICE_ID, generatedDeviceId).apply()
            return generatedDeviceId
        }

    var ntfySettings: NtfySettings
        get() = NtfySettings(
            serverUrl = ntfyServerUrl,
            pairId = ntfyPairId,
            username = ntfyUsername,
            password = ntfyPassword,
            topicPrefix = ntfyTopicPrefix
        )
        set(value) {
            preferences.edit()
                .putString(KEY_NTFY_SERVER_URL, value.serverUrl.trim())
                .putString(KEY_NTFY_PAIR_ID, value.pairId.trim())
                .putString(KEY_NTFY_USERNAME, value.username.trim())
                .putString(KEY_NTFY_PASSWORD, value.password)
                .putString(KEY_NTFY_TOPIC_PREFIX, value.topicPrefix.trim())
                .apply()
        }

    companion object {
        const val DEFAULT_NTFY_SERVER_URL = "https://axon-ntfy.liara.run"
        const val DEFAULT_NTFY_TOPIC_PREFIX = "axon"
        private const val KEY_ROLE = "role"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_TRANSPORT_MODE = "transport_mode"
        private const val KEY_NTFY_SERVER_URL = "ntfy_server_url"
        private const val KEY_NTFY_PAIR_ID = "ntfy_pair_id"
        private const val KEY_NTFY_USERNAME = "ntfy_username"
        private const val KEY_NTFY_PASSWORD = "ntfy_password"
        private const val KEY_NTFY_TOPIC_PREFIX = "ntfy_topic_prefix"
        private const val KEY_DEVICE_ID = "device_id"

        fun generatePairId(): String {
            return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .take(12)
        }
    }
}

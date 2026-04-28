package com.axon.bridge.data

import android.content.Context
import com.axon.bridge.domain.BridgeRole

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

    companion object {
        private const val KEY_ROLE = "role"
        private const val KEY_SERVER_IP = "server_ip"
    }
}

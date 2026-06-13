package com.axon.bridge.data

import android.content.Context
import com.axon.bridge.domain.BridgeRole
import com.axon.bridge.domain.BridgeTransportMode

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

    companion object {
        private const val KEY_ROLE = "role"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_TRANSPORT_MODE = "transport_mode"
    }
}

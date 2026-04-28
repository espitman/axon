package com.axon.bridge.data

import android.os.Build
import com.axon.bridge.domain.DeviceInfo
import java.util.Locale

class DeviceInfoProvider {
    fun currentDevice(): DeviceInfo {
        val manufacturer = Build.MANUFACTURER.cleanDevicePart()
        val model = Build.MODEL.cleanDevicePart()
        val displayName = when {
            manufacturer.isBlank() && model.isBlank() -> "Android device"
            manufacturer.isBlank() -> model
            model.isBlank() -> manufacturer
            model.contains(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }

        return DeviceInfo(
            manufacturer = manufacturer,
            model = model,
            displayName = displayName
        )
    }

    private fun String?.cleanDevicePart(): String {
        return this.orEmpty()
            .trim()
            .replace(Regex("\\s+"), " ")
            .replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
            }
    }
}

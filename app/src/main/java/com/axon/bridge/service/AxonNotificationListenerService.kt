package com.axon.bridge.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.axon.bridge.data.DeviceInfoProvider
import com.axon.bridge.data.DiagnosticsLog
import com.axon.bridge.data.NotificationEventBus
import com.axon.bridge.domain.NotificationCategory
import com.axon.bridge.domain.NotificationPayload
import kotlin.math.absoluteValue

class AxonNotificationListenerService : NotificationListenerService() {
    private val deviceInfoProvider = DeviceInfoProvider()

    override fun onListenerConnected() {
        super.onListenerConnected()
        DiagnosticsLog.add("Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DiagnosticsLog.add("Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        DiagnosticsLog.add("Posted notification: ${sbn.packageName}")
        val category = sbn.packageName.toNotificationCategory() ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: ""

        if (title.isBlank() && message.isBlank()) {
            DiagnosticsLog.add("Skipped mirrored ${category.name}: empty title/message")
            return
        }

        val stableId = "${sbn.packageName}|$title|$category".hashCode().absoluteValue.toString()
        DiagnosticsLog.add("Queued mirrored ${category.name}: ${title.ifBlank { "(no title)" }}")
        NotificationEventBus.publish(
            NotificationPayload(
                id = stableId,
                category = category,
                originDevice = deviceInfoProvider.currentDevice().displayName,
                title = title.ifBlank { category.name },
                message = message,
                packageName = sbn.packageName,
                postedTime = sbn.postTime
            )
        )
    }

    private fun String.toNotificationCategory(): NotificationCategory? {
        return when (this) {
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.miui.mms",
            "com.xiaomi.mms" -> NotificationCategory.Sms
            "com.google.android.dialer",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.google.android.apps.tachyon" -> NotificationCategory.Call
            else -> null
        }
    }
}

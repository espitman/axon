package com.axon.bridge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.axon.bridge.CallActivity
import com.axon.bridge.MainActivity
import com.axon.bridge.R
import com.axon.bridge.data.DiagnosticsLog
import com.axon.bridge.domain.NotificationCategory
import com.axon.bridge.domain.NotificationPayload
import kotlin.math.absoluteValue

class MirroredNotificationManager(
    private val context: Context
) {
    fun show(payload: NotificationPayload) {
        if (!canPostNotifications()) {
            DiagnosticsLog.add("Cannot show mirrored notification: app notifications denied")
            return
        }
        createChannel()

        val categoryLabel = when (payload.category) {
            NotificationCategory.Sms -> "SMS"
            NotificationCategory.Call -> "Call"
        }
        val notificationId = notificationId(payload)
        val targetIntent = if (payload.category == NotificationCategory.Call) {
            CallActivity.intent(context, payload, notificationId)
        } else {
            Intent(context, MainActivity::class.java)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fullScreenIntent = if (payload.category == NotificationCategory.Call) {
            PendingIntent.getActivity(
                context,
                notificationId + 1,
                CallActivity.intent(context, payload, notificationId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }
        val channelId = if (payload.category == NotificationCategory.Call) CALL_CHANNEL_ID else CHANNEL_ID
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_axon_mark)
            .setContentTitle(payload.title)
            .setContentText(payload.message.ifBlank { "Mirrored $categoryLabel from ${payload.originDevice}" })
            .setSubText(payload.originDevice)
            .setContentIntent(contentIntent)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(payload.message.ifBlank { "Mirrored $categoryLabel from ${payload.originDevice}" })
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(
                if (payload.category == NotificationCategory.Call) {
                    NotificationCompat.CATEGORY_CALL
                } else {
                    NotificationCompat.CATEGORY_MESSAGE
                }
            )
            .setLocalOnly(false)
            .setOngoing(payload.category == NotificationCategory.Call)
            .setAutoCancel(payload.category != NotificationCategory.Call)
            .setWhen(payload.postedTime)
            .setShowWhen(true)
            .extend(NotificationCompat.WearableExtender())
            .apply {
                if (fullScreenIntent != null) {
                    setFullScreenIntent(fullScreenIntent, true)
                }
            }
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }.onSuccess {
            DiagnosticsLog.add("Displayed mirrored ${payload.category.name}: ${payload.title}")
        }.onFailure { error ->
            DiagnosticsLog.add("Display failed: ${error.message ?: error::class.simpleName}")
        }
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mirrored Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications mirrored from the sender device."
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setShowBadge(true)
        }
        val callChannel = NotificationChannel(
            CALL_CHANNEL_ID,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Full-screen call alerts mirrored from the sender device."
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(channel)
            createNotificationChannel(callChannel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "mirrored_alerts_wear"
        private const val CALL_CHANNEL_ID = "mirrored_incoming_calls"

        fun notificationId(payload: NotificationPayload): Int {
            return "${payload.packageName}|${payload.title}".hashCode().absoluteValue
        }
    }
}

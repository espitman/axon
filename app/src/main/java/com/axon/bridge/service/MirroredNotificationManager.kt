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
        val notificationId = "${payload.packageName}|${payload.title}".hashCode().absoluteValue
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
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
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setLocalOnly(false)
            .setAutoCancel(true)
            .setWhen(payload.postedTime)
            .setShowWhen(true)
            .extend(NotificationCompat.WearableExtender())
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
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mirrored_alerts_wear"
    }
}

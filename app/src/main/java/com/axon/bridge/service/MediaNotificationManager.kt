package com.axon.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.os.Build
import androidx.core.content.ContextCompat
import com.axon.bridge.MainActivity
import com.axon.bridge.R
import com.axon.bridge.data.DiagnosticsLog
import com.axon.bridge.domain.MediaCommandAction
import com.axon.bridge.domain.MediaPayload

class MediaNotificationManager(
    private val context: Context
) {
    fun show(payload: MediaPayload, token: MediaSession.Token) {
        if (!canPostNotifications()) {
            DiagnosticsLog.add("Cannot show media notification: app notifications denied")
            return
        }
        createChannel()

        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseAction = if (payload.isPlaying) {
            notificationAction(MediaCommandAction.Pause, "Pause")
        } else {
            notificationAction(MediaCommandAction.Play, "Play")
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_axon_mark)
            .setContentTitle(payload.title.ifBlank { "Unknown track" })
            .setContentText(payload.artist.ifBlank { payload.packageName })
            .setSubText("Axon media")
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(payload.isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(notificationAction(MediaCommandAction.SkipToPrevious, "Previous"))
            .addAction(playPauseAction)
            .addAction(notificationAction(MediaCommandAction.SkipToNext, "Next"))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }.onSuccess {
            DiagnosticsLog.add("Displayed media notification: ${payload.title.ifBlank { "Unknown track" }}")
        }.onFailure { error ->
            DiagnosticsLog.add("Media notification failed: ${error.message ?: error::class.simpleName}")
        }
    }

    fun cancel() {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun notificationAction(
        action: MediaCommandAction,
        title: String
    ): Notification.Action {
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(
            R.drawable.ic_axon_mark,
            title,
            PendingIntent.getService(
                context,
                action.ordinal + REQUEST_ACTION_BASE,
                Intent(context, BridgeService::class.java)
                    .setAction(BridgeService.ACTION_MEDIA_COMMAND)
                    .putExtra(BridgeService.EXTRA_MEDIA_COMMAND, action.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        ).build()
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
            "Axon Media",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media mirrored from the sender device."
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "axon_media"
        const val NOTIFICATION_ID = 20_001
        const val REQUEST_CONTENT = 20_101
        const val REQUEST_ACTION_BASE = 20_200
    }
}

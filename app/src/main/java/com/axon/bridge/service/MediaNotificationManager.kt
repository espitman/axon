package com.axon.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import com.axon.bridge.MainActivity
import com.axon.bridge.R
import com.axon.bridge.data.DiagnosticsLog
import com.axon.bridge.domain.MediaCommandAction
import com.axon.bridge.domain.MediaPayload

class MediaNotificationManager(
    private val context: Context
) {
    fun show(payload: MediaPayload, token: MediaSessionCompat.Token) {
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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_axon_mark)
            .setContentTitle(payload.title.ifBlank { "Unknown track" })
            .setContentText(payload.artist.ifBlank { payload.packageName })
            .setSubText("Axon media")
            .setContentIntent(contentIntent)
            .setLargeIcon(payload.decodeArtwork())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(payload.isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(false)
            .addAction(notificationAction(MediaCommandAction.SkipToPrevious, "Previous"))
            .addAction(playPauseAction)
            .addAction(notificationAction(MediaCommandAction.SkipToNext, "Next"))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.onSuccess {
            DiagnosticsLog.add("Displayed media notification: ${payload.title.ifBlank { "Unknown track" }}")
        }.onFailure { error ->
            DiagnosticsLog.add("Media notification failed: ${error.message ?: error::class.simpleName}")
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun notificationAction(
        action: MediaCommandAction,
        title: String
    ): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            R.drawable.ic_axon_mark,
            title,
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                action.playbackStateAction()
            )
        ).build()
    }

    private fun MediaCommandAction.playbackStateAction(): Long {
        return when (this) {
            MediaCommandAction.Play -> PlaybackStateCompat.ACTION_PLAY
            MediaCommandAction.Pause -> PlaybackStateCompat.ACTION_PAUSE
            MediaCommandAction.SkipToNext -> PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            MediaCommandAction.SkipToPrevious -> PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
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
            "Axon Media",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media mirrored from the sender device."
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun MediaPayload.decodeArtwork(): Bitmap? {
        val encoded = artworkBase64 ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private companion object {
        const val CHANNEL_ID = "axon_media"
        const val NOTIFICATION_ID = 20_001
        const val REQUEST_CONTENT = 20_101
    }
}

package com.axon.bridge.data

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Base64
import android.view.KeyEvent
import androidx.media.session.MediaButtonReceiver
import com.axon.bridge.MainActivity
import com.axon.bridge.domain.MediaCommandAction
import com.axon.bridge.domain.MediaCommandPayload
import com.axon.bridge.domain.MediaPayload
import com.axon.bridge.service.AxonMediaButtonReceiver

class ShadowMediaSession(
    context: Context,
    private val onCommand: (MediaCommandPayload) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val playbackAnchor = SilentPlaybackAnchor()
    private var session: MediaSessionCompat? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var lastSignature: String? = null
    private var lastIsPlaying = false

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            DiagnosticsLog.add("Shadow media button event received")
            val event = mediaButtonEvent.keyEvent() ?: return false
            if (event.action != KeyEvent.ACTION_DOWN) return true
            return when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> sendCommand(MediaCommandAction.Play)
                KeyEvent.KEYCODE_MEDIA_PAUSE -> sendCommand(MediaCommandAction.Pause)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    sendCommand(if (lastIsPlaying) MediaCommandAction.Pause else MediaCommandAction.Play)
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> sendCommand(MediaCommandAction.SkipToNext)
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> sendCommand(MediaCommandAction.SkipToPrevious)
                else -> false
            }
        }

        override fun onCommand(command: String, args: Bundle?, cb: ResultReceiver?) {
            DiagnosticsLog.add("Shadow media custom command: $command")
            super.onCommand(command, args, cb)
        }

        override fun onPlay() {
            sendCommand(MediaCommandAction.Play)
        }

        override fun onPause() {
            sendCommand(MediaCommandAction.Pause)
        }

        override fun onSkipToNext() {
            sendCommand(MediaCommandAction.SkipToNext)
        }

        override fun onSkipToPrevious() {
            sendCommand(MediaCommandAction.SkipToPrevious)
        }
    }

    fun start() {
        if (session != null) return
        session = MediaSessionCompat(
            appContext,
            SESSION_TAG,
            ComponentName(appContext, AxonMediaButtonReceiver::class.java),
            mediaButtonReceiverIntent()
        ).apply {
            setCallback(callback)
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setMediaButtonReceiver(mediaButtonReceiverIntent())
            setSessionActivity(sessionActivityIntent())
            setPlaybackToLocal(AudioManager.STREAM_MUSIC)
            setPlaybackState(buildStoppedState())
        }
        activeCompatSession = session
        DiagnosticsLog.add("Shadow media session ready")
    }

    fun update(payload: MediaPayload) {
        start()
        val signature = payload.signature()
        if (signature == lastSignature) return
        lastSignature = signature
        lastIsPlaying = payload.isPlaying

        session?.apply {
            setMetadata(payload.toMetadata())
            setPlaybackState(payload.toPlaybackState())
            isActive = true
        }
        playbackAnchor.start()
        requestMediaFocus()
        DiagnosticsLog.add("Shadow media updated: ${payload.title.ifBlank { "Unknown track" }}")
    }

    fun token(): MediaSessionCompat.Token? {
        return session?.sessionToken
    }

    fun stop() {
        session?.apply {
            setPlaybackState(buildStoppedState())
            isActive = false
        }
        playbackAnchor.stop()
        abandonMediaFocus()
        lastSignature = null
    }

    fun release() {
        stop()
        session?.release()
        activeCompatSession = null
        playbackAnchor.release()
        session = null
    }

    private fun sendCommand(action: MediaCommandAction): Boolean {
        DiagnosticsLog.add("Shadow media command: ${action.name}")
        onCommand(MediaCommandPayload(action))
        return true
    }

    private fun requestMediaFocus() {
        if (hasAudioFocus) return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    DiagnosticsLog.add("Shadow media audio focus changed: $change")
                }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { change -> DiagnosticsLog.add("Shadow media audio focus changed: $change") },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        DiagnosticsLog.add("Shadow media audio focus: ${if (hasAudioFocus) "granted" else "denied"}")
    }

    private fun abandonMediaFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
        hasAudioFocus = false
        DiagnosticsLog.add("Shadow media audio focus abandoned")
    }

    private fun MediaPayload.toMetadata(): MediaMetadataCompat {
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration.coerceAtLeast(0L))
            .apply {
                decodeArtwork()?.let { bitmap ->
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                }
            }
            .build()
    }

    private fun MediaPayload.toPlaybackState(): PlaybackStateCompat {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val speed = if (isPlaying && playbackSpeed == 0f) 1f else playbackSpeed
        return PlaybackStateCompat.Builder()
            .setActions(SUPPORTED_ACTIONS)
            .setState(
                state,
                position.coerceAtLeast(0L),
                speed,
                SystemClock.elapsedRealtime()
            )
            .build()
    }

    private fun buildStoppedState(): PlaybackStateCompat {
        return PlaybackStateCompat.Builder()
            .setActions(SUPPORTED_ACTIONS)
            .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
            .build()
    }

    private fun MediaPayload.signature(): String {
        return listOf(
            title,
            artist,
            album,
            duration,
            position,
            playbackSpeed,
            isPlaying,
            packageName,
            artworkBase64?.hashCode()
        ).joinToString("|")
    }

    private fun MediaPayload.decodeArtwork(): Bitmap? {
        val encoded = artworkBase64 ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private fun Intent.keyEvent(): KeyEvent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }

    private fun mediaButtonReceiverIntent(): PendingIntent {
        return PendingIntent.getBroadcast(
            appContext,
            REQUEST_MEDIA_BUTTON,
            Intent(appContext, AxonMediaButtonReceiver::class.java).setAction(Intent.ACTION_MEDIA_BUTTON),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun sessionActivityIntent(): PendingIntent {
        return PendingIntent.getActivity(
            appContext,
            REQUEST_SESSION_ACTIVITY,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val SESSION_TAG = "AxonShadowMediaSession"
        private const val REQUEST_MEDIA_BUTTON = 30_100
        private const val REQUEST_SESSION_ACTIVITY = 30_101
        private const val SUPPORTED_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        private var activeCompatSession: MediaSessionCompat? = null

        fun handleMediaButtonIntent(intent: Intent): Boolean {
            val currentSession = activeCompatSession ?: return false
            val event = MediaButtonReceiver.handleIntent(currentSession, intent)
            return event != null
        }
    }
}

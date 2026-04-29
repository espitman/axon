package com.axon.bridge.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.util.Base64
import com.axon.bridge.domain.MediaCommandAction
import com.axon.bridge.domain.MediaCommandPayload
import com.axon.bridge.domain.MediaPayload

class ShadowMediaSession(
    context: Context,
    private val onCommand: (MediaCommandPayload) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var lastSignature: String? = null
    private var lastIsPlaying = false

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val event = mediaButtonIntent.keyEvent() ?: return false
            if (event.action != KeyEvent.ACTION_DOWN) return true
            return when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    sendCommand(MediaCommandAction.Play)
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    sendCommand(MediaCommandAction.Pause)
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    sendCommand(if (lastIsPlaying) MediaCommandAction.Pause else MediaCommandAction.Play)
                    true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    sendCommand(MediaCommandAction.SkipToNext)
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    sendCommand(MediaCommandAction.SkipToPrevious)
                    true
                }
                else -> false
            }
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
        session = MediaSession(appContext, SESSION_TAG).apply {
            setCallback(callback, mainHandler)
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackState(buildStoppedState())
        }
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
        DiagnosticsLog.add("Shadow media updated: ${payload.title.ifBlank { "Unknown track" }}")
    }

    fun token(): MediaSession.Token? {
        return session?.sessionToken
    }

    fun stop() {
        session?.apply {
            setPlaybackState(buildStoppedState())
            isActive = false
        }
        lastSignature = null
    }

    fun release() {
        stop()
        session?.release()
        session = null
    }

    private fun sendCommand(action: MediaCommandAction) {
        DiagnosticsLog.add("Shadow media command: ${action.name}")
        onCommand(MediaCommandPayload(action))
    }

    private fun MediaPayload.toMetadata(): MediaMetadata {
        return MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.coerceAtLeast(0L))
            .apply {
                decodeArtwork()?.let { bitmap ->
                    putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
                    putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                }
            }
            .build()
    }

    private fun MediaPayload.toPlaybackState(): PlaybackState {
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val speed = if (isPlaying && playbackSpeed == 0f) 1f else playbackSpeed
        return PlaybackState.Builder()
            .setActions(SUPPORTED_ACTIONS)
            .setState(
                state,
                position.coerceAtLeast(0L),
                speed,
                SystemClock.elapsedRealtime()
            )
            .build()
    }

    private fun buildStoppedState(): PlaybackState {
        return PlaybackState.Builder()
            .setActions(SUPPORTED_ACTIONS)
            .setState(PlaybackState.STATE_STOPPED, 0L, 0f)
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

    private companion object {
        const val SESSION_TAG = "AxonShadowMediaSession"
        const val SUPPORTED_ACTIONS =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
    }
}

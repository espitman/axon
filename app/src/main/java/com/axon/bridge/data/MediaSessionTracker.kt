package com.axon.bridge.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.axon.bridge.domain.MediaCommandAction
import com.axon.bridge.domain.MediaCommandPayload
import com.axon.bridge.domain.MediaPayload
import com.axon.bridge.service.AxonNotificationListenerService
import java.io.ByteArrayOutputStream

class MediaSessionTracker(
    context: Context,
    private val onMediaChanged: (MediaPayload) -> Unit = {},
    private val onMediaCleared: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val mediaSessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val notificationListener = ComponentName(appContext, AxonNotificationListenerService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeController: MediaController? = null
    private var lastPayloadSignature: String? = null
    private var wasMediaActive = false
    private var isStarted = false

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        selectActiveController(controllers.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishCurrentMedia("metadata")
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publishCurrentMedia("state")
        }

        override fun onSessionDestroyed() {
            DiagnosticsLog.add("Media session ended")
            clearActiveController()
            publishMediaClear("session destroyed")
            refreshActiveSessions()
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        DiagnosticsLog.add("Media tracker starting")
        runCatching {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                notificationListener,
                mainHandler
            )
            refreshActiveSessions()
        }.onFailure { error ->
            isStarted = false
            DiagnosticsLog.add("Media tracker unavailable: ${error.message ?: error::class.simpleName}")
        }
    }

    fun stop() {
        if (!isStarted && activeController == null) return
        isStarted = false
        runCatching {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        clearActiveController()
        DiagnosticsLog.add("Media tracker stopped")
    }

    fun dispatchCommand(command: MediaCommandPayload) {
        val controls = activeController?.transportControls
        if (controls == null) {
            DiagnosticsLog.add("Media command skipped: no active session")
            return
        }

        runCatching {
            when (command.action) {
                MediaCommandAction.Play -> controls.play()
                MediaCommandAction.Pause -> controls.pause()
                MediaCommandAction.SkipToNext -> controls.skipToNext()
                MediaCommandAction.SkipToPrevious -> controls.skipToPrevious()
            }
            DiagnosticsLog.add("Media command dispatched: ${command.action.name}")
        }.onFailure { error ->
            DiagnosticsLog.add("Media command failed: ${error.message ?: error::class.simpleName}")
        }
    }

    private fun refreshActiveSessions() {
        runCatching {
            selectActiveController(mediaSessionManager.getActiveSessions(notificationListener))
        }.onFailure { error ->
            DiagnosticsLog.add("Media sessions unavailable: ${error.message ?: error::class.simpleName}")
        }
    }

    private fun selectActiveController(controllers: List<MediaController>) {
        val selected = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()

        if (selected?.sessionToken == activeController?.sessionToken) {
            publishCurrentMedia("refresh")
            return
        }

        clearActiveController()
        activeController = selected
        if (selected == null) {
            DiagnosticsLog.add("No active media session")
            publishMediaClear("no active session")
            return
        }

        DiagnosticsLog.add("Media session selected: ${selected.packageName}")
        selected.registerCallback(controllerCallback, mainHandler)
        publishCurrentMedia("session")
    }

    private fun clearActiveController() {
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        lastPayloadSignature = null
    }

    private fun publishCurrentMedia(reason: String) {
        val controller = activeController ?: return
        val payload = controller.toPayload() ?: run {
            publishMediaClear("metadata unavailable")
            return
        }
        val signature = listOf(
            payload.packageName,
            payload.title,
            payload.artist,
            payload.album,
            payload.duration,
            payload.position,
            payload.playbackSpeed,
            payload.isPlaying,
            payload.artworkBase64?.hashCode()
        ).joinToString("|")

        if (signature == lastPayloadSignature) return
        lastPayloadSignature = signature
        wasMediaActive = true
        DiagnosticsLog.add(
            "Media $reason: ${payload.title.ifBlank { "Unknown track" }} " +
                if (payload.isPlaying) "(playing)" else "(paused)"
        )
        onMediaChanged(payload)
    }

    private fun publishMediaClear(reason: String) {
        if (!wasMediaActive) return
        wasMediaActive = false
        lastPayloadSignature = null
        DiagnosticsLog.add("Media cleared: $reason")
        onMediaCleared()
    }

    private fun MediaController.toPayload(): MediaPayload? {
        val metadata = metadata ?: return null
        val state = playbackState
        val title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString().orEmpty()
        val artist = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString().orEmpty()
        val album = metadata.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString().orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val position = state?.position?.coerceAtLeast(0L) ?: 0L
        val speed = state?.playbackSpeed ?: 0f
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val updateTime = state?.lastPositionUpdateTime?.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        val artworkBase64 = metadata.extractArtworkBase64()

        return MediaPayload(
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            position = position,
            playbackSpeed = speed,
            isPlaying = isPlaying,
            lastPositionUpdateTime = updateTime,
            packageName = packageName,
            artworkBase64 = artworkBase64
        )
    }

    private fun MediaMetadata.extractArtworkBase64(): String? {
        val bitmap = getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null

        return runCatching {
            val scaled = bitmap.scaleToMax(ARTWORK_MAX_SIZE)
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, ARTWORK_QUALITY, output)
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun Bitmap.scaleToMax(maxSize: Int): Bitmap {
        val largestSide = maxOf(width, height)
        if (largestSide <= maxSize) return this
        val scale = maxSize.toFloat() / largestSide.toFloat()
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private companion object {
        const val ARTWORK_MAX_SIZE = 384
        const val ARTWORK_QUALITY = 82
    }
}

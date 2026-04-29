package com.axon.bridge.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class SilentPlaybackAnchor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioTrack: AudioTrack? = null

    fun start() {
        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            DiagnosticsLog.add("Silent media anchor failed: buffer $minBufferSize")
            return
        }

        val bufferSize = max(minBufferSize, SILENCE_BUFFER_BYTES * 2)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
        }.onFailure { error ->
            DiagnosticsLog.add("Silent media anchor failed: ${error.message ?: error::class.simpleName}")
        }.getOrNull() ?: return

        audioTrack = track
        track.setVolume(0f)
        track.play()
        scope.launch {
            val silence = ByteArray(SILENCE_BUFFER_BYTES)
            while (isActive && audioTrack === track) {
                val written = runCatching {
                    track.write(silence, 0, silence.size)
                }.getOrDefault(0)
                if (written <= 0) delay(20)
            }
        }
        DiagnosticsLog.add("Silent media anchor started")
    }

    fun stop() {
        val track = audioTrack ?: return
        audioTrack = null
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
        DiagnosticsLog.add("Silent media anchor stopped")
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val SILENCE_BUFFER_BYTES = 4_096
    }
}

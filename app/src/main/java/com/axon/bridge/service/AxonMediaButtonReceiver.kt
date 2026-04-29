package com.axon.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import com.axon.bridge.data.DiagnosticsLog
import com.axon.bridge.data.MediaBridgeBus
import com.axon.bridge.data.ShadowMediaSession
import com.axon.bridge.domain.MediaCommandAction
import com.axon.bridge.domain.MediaCommandPayload

class AxonMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        if (ShadowMediaSession.handleMediaButtonIntent(intent)) {
            DiagnosticsLog.add("Media button broadcast routed to compat session")
            return
        }
        val event = intent.keyEvent() ?: run {
            DiagnosticsLog.add("Media button broadcast skipped: missing key event")
            return
        }
        if (event.action != KeyEvent.ACTION_DOWN) return

        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> MediaCommandAction.Play
            KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaCommandAction.Pause
            KeyEvent.KEYCODE_MEDIA_NEXT -> MediaCommandAction.SkipToNext
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaCommandAction.SkipToPrevious
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (BridgeService.activeMedia.value?.isPlaying == true) {
                    MediaCommandAction.Pause
                } else {
                    MediaCommandAction.Play
                }
            }
            else -> {
                DiagnosticsLog.add("Media button broadcast skipped: key ${event.keyCode}")
                return
            }
        }

        DiagnosticsLog.add("Media button broadcast command: ${action.name}")
        MediaBridgeBus.publishCommand(MediaCommandPayload(action))
    }

    private fun Intent.keyEvent(): KeyEvent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }
}

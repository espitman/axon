package com.axon.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.axon.bridge.R
import com.axon.bridge.data.BridgeTransport
import com.axon.bridge.data.CallAlertStore
import com.axon.bridge.data.DeviceInfoProvider
import com.axon.bridge.data.DiagnosticsLog
import com.axon.bridge.data.MediaBridgeBus
import com.axon.bridge.data.MediaSessionTracker
import com.axon.bridge.data.ShadowMediaSession
import com.axon.bridge.data.SmsArchiveStore
import com.axon.bridge.domain.MediaCommandAction
import com.axon.bridge.domain.MediaCommandPayload
import com.axon.bridge.domain.MediaPayload
import com.axon.bridge.domain.NotificationCategory
import com.axon.bridge.domain.BridgeConnectionState
import com.axon.bridge.domain.BridgeRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob

class BridgeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var transport: BridgeTransport
    private lateinit var mirroredNotificationManager: MirroredNotificationManager
    private lateinit var mediaNotificationManager: MediaNotificationManager
    private lateinit var mediaSessionTracker: MediaSessionTracker
    private lateinit var shadowMediaSession: ShadowMediaSession
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        SmsArchiveStore.init(this)
        mirroredNotificationManager = MirroredNotificationManager(this)
        mediaNotificationManager = MediaNotificationManager(this)
        mediaSessionTracker = MediaSessionTracker(
            context = this,
            onMediaChanged = { payload ->
                publishMedia(payload)
                MediaBridgeBus.publishUpdate(payload)
            },
            onMediaCleared = {
                clearMedia()
                MediaBridgeBus.publishClear()
            }
        )
        shadowMediaSession = ShadowMediaSession(
            context = this,
            onCommand = MediaBridgeBus::publishCommand
        )
        transport = BridgeTransport(
            scope = serviceScope,
            deviceInfoProvider = DeviceInfoProvider(),
            onStateChanged = ::updateState,
            onPeerChanged = { peerName ->
                mutablePeerDeviceName.value = peerName
                sendStateChangedBroadcast()
            },
            onEventTransferred = {
                mutableLastEventTimeMillis.value = System.currentTimeMillis()
                sendStateChangedBroadcast()
            },
            onNotificationReceived = { payload ->
                if (payload.category == NotificationCategory.Sms) {
                    SmsArchiveStore.add(this, payload)
                } else if (payload.category == NotificationCategory.Call) {
                    CallAlertStore.show(payload)
                }
                mirroredNotificationManager.show(payload)
            },
            onMediaUpdateReceived = { payload ->
                publishMedia(payload)
                shadowMediaSession.update(payload)
                shadowMediaSession.token()?.let { token ->
                    mediaNotificationManager.show(payload, token)
                }
            },
            onMediaCommandReceived = { command ->
                mediaSessionTracker.dispatchCommand(command)
            },
            onMediaCleared = {
                clearMedia()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBridge()
                return START_NOT_STICKY
            }
            ACTION_MEDIA_COMMAND -> {
                handleMediaCommand(intent)
                return START_STICKY
            }
            ACTION_MEDIA_TOGGLE -> {
                handleMediaToggle()
                return START_STICKY
            }
            Intent.ACTION_MEDIA_BUTTON -> {
                handleMediaButton(intent)
                return START_STICKY
            }
            else -> startBridge(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startBridge(intent: Intent?) {
        val role = runCatching {
            BridgeRole.valueOf(intent?.getStringExtra(EXTRA_ROLE).orEmpty())
        }.getOrDefault(BridgeRole.Sink)
        val serverIp = intent?.getStringExtra(EXTRA_SERVER_IP).orEmpty().trim()
        mutableActiveTargetIp.value = serverIp

        updateState(BridgeConnectionState.Connecting, null)
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification(role, serverIp))
        mediaSessionTracker.stop()
        shadowMediaSession.stop()

        when (role) {
            BridgeRole.Sink -> {
                shadowMediaSession.start()
                transport.startServer(host = "0.0.0.0")
            }
            BridgeRole.Source -> {
                mediaSessionTracker.start()
                transport.startClient(serverIp)
            }
        }
    }

    private fun stopBridge() {
        mediaSessionTracker.stop()
        shadowMediaSession.stop()
        mediaNotificationManager.cancel()
        clearMedia()
        transport.stop()
        releaseWakeLock()
        updateState(BridgeConnectionState.Disconnected, null, running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(role: BridgeRole, serverIp: String): Notification {
        val detail = when (role) {
            BridgeRole.Sink -> "Receiver mode active on this device"
            BridgeRole.Source -> if (serverIp.isBlank()) "Sender mode waiting for receiver IP" else "Sender mode targeting $serverIp"
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_axon_mark)
            .setContentTitle("Axon bridge is active")
            .setContentText(detail)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun handleMediaCommand(intent: Intent) {
        val action = runCatching {
            MediaCommandAction.valueOf(intent.getStringExtra(EXTRA_MEDIA_COMMAND).orEmpty())
        }.getOrNull()

        if (action == null) {
            DiagnosticsLog.add("Media notification command skipped: unknown action")
            return
        }

        DiagnosticsLog.add("Media notification command: $action")
        MediaBridgeBus.publishCommand(MediaCommandPayload(action))
    }

    private fun handleMediaToggle() {
        val action = if (mutableActiveMedia.value?.isPlaying == true) {
            MediaCommandAction.Pause
        } else {
            MediaCommandAction.Play
        }
        DiagnosticsLog.add("Media toggle command: $action")
        MediaBridgeBus.publishCommand(MediaCommandPayload(action))
    }

    private fun handleMediaButton(intent: Intent) {
        val event = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        } ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return

        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> MediaCommandAction.Play
            KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaCommandAction.Pause
            KeyEvent.KEYCODE_MEDIA_NEXT -> MediaCommandAction.SkipToNext
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaCommandAction.SkipToPrevious
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (mutableActiveMedia.value?.isPlaying == true) MediaCommandAction.Pause else MediaCommandAction.Play
            }
            else -> return
        }
        DiagnosticsLog.add("Media button command: $action")
        MediaBridgeBus.publishCommand(MediaCommandPayload(action))
    }

    private fun publishMedia(payload: MediaPayload) {
        mutableActiveMedia.value = payload
        mutableActiveMediaUpdatedAtElapsed.value = SystemClock.elapsedRealtime()
        sendStateChangedBroadcast()
    }

    private fun clearMedia() {
        mutableActiveMedia.value = null
        mutableActiveMediaUpdatedAtElapsed.value = 0L
        shadowMediaSession.stop()
        mediaNotificationManager.cancel()
        sendStateChangedBroadcast()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Axon Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the Axon bridge service running."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Axon::BridgeWakeLock")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun updateState(
        state: BridgeConnectionState,
        errorMessage: String?,
        running: Boolean = true
    ) {
        publishState(state, running, errorMessage)
        sendStateChangedBroadcast()
    }

    private fun sendStateChangedBroadcast() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    override fun onDestroy() {
        mediaSessionTracker.stop()
        shadowMediaSession.release()
        mediaNotificationManager.cancel()
        clearMedia()
        transport.stop()
        releaseWakeLock()
        serviceScope.cancel()
        publishState(BridgeConnectionState.Disconnected, false)
        mutablePeerDeviceName.value = ""
        sendStateChangedBroadcast()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.axon.bridge.action.START"
        const val ACTION_STOP = "com.axon.bridge.action.STOP"
        const val ACTION_STATE_CHANGED = "com.axon.bridge.action.STATE_CHANGED"
        const val ACTION_MEDIA_COMMAND = "com.axon.bridge.action.MEDIA_COMMAND"
        const val ACTION_MEDIA_TOGGLE = "com.axon.bridge.action.MEDIA_TOGGLE"
        const val EXTRA_ROLE = "extra_role"
        const val EXTRA_SERVER_IP = "extra_server_ip"
        const val EXTRA_MEDIA_COMMAND = "extra_media_command"

        private const val CHANNEL_ID = "axon_bridge"
        private const val NOTIFICATION_ID = 10_001

        private val mutableConnectionState = MutableStateFlow(BridgeConnectionState.Disconnected)
        private val mutableIsRunning = MutableStateFlow(false)
        private val mutableErrorMessage = MutableStateFlow<String?>(null)
        private val mutablePeerDeviceName = MutableStateFlow("")
        private val mutableActiveTargetIp = MutableStateFlow("")
        private val mutableLastEventTimeMillis = MutableStateFlow(0L)
        private val mutableActiveMedia = MutableStateFlow<MediaPayload?>(null)
        private val mutableActiveMediaUpdatedAtElapsed = MutableStateFlow(0L)

        val connectionState: StateFlow<BridgeConnectionState> = mutableConnectionState
        val isRunning: StateFlow<Boolean> = mutableIsRunning
        val errorMessage: StateFlow<String?> = mutableErrorMessage
        val peerDeviceName: StateFlow<String> = mutablePeerDeviceName
        val activeTargetIp: StateFlow<String> = mutableActiveTargetIp
        val lastEventTimeMillis: StateFlow<Long> = mutableLastEventTimeMillis
        val activeMedia: StateFlow<MediaPayload?> = mutableActiveMedia
        val activeMediaUpdatedAtElapsed: StateFlow<Long> = mutableActiveMediaUpdatedAtElapsed

        fun publishState(
            state: BridgeConnectionState,
            running: Boolean,
            errorMessage: String? = null
        ) {
            mutableConnectionState.value = state
            mutableIsRunning.value = running
            mutableErrorMessage.value = errorMessage
            if (!running) {
                mutablePeerDeviceName.value = ""
                mutableActiveTargetIp.value = ""
                mutableActiveMedia.value = null
                mutableActiveMediaUpdatedAtElapsed.value = 0L
            }
        }
    }
}

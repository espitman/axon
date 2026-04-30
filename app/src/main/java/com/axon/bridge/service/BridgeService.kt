package com.axon.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.axon.bridge.CallActivity
import com.axon.bridge.R
import com.axon.bridge.data.AxonSettings
import com.axon.bridge.data.BridgeTransport
import com.axon.bridge.data.CallAlertStore
import com.axon.bridge.data.DeviceInfoProvider
import com.axon.bridge.data.DiagnosticsLog
import com.axon.bridge.data.MediaBridgeBus
import com.axon.bridge.data.MediaSessionTracker
import com.axon.bridge.data.NetworkInfoProvider
import com.axon.bridge.data.ReceiverDiscoveryScanner
import com.axon.bridge.data.ShadowMediaSession
import com.axon.bridge.data.SmsArchiveStore
import com.axon.bridge.domain.CallState
import com.axon.bridge.domain.MediaCommandAction
import com.axon.bridge.domain.MediaCommandPayload
import com.axon.bridge.domain.MediaPayload
import com.axon.bridge.domain.NotificationPayload
import com.axon.bridge.domain.NotificationCategory
import com.axon.bridge.domain.BridgeConnectionState
import com.axon.bridge.domain.BridgeRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob

class BridgeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var transport: BridgeTransport
    private lateinit var mirroredNotificationManager: MirroredNotificationManager
    private lateinit var mediaNotificationManager: MediaNotificationManager
    private lateinit var mediaSessionTracker: MediaSessionTracker
    private lateinit var shadowMediaSession: ShadowMediaSession
    private lateinit var settings: AxonSettings
    private lateinit var networkInfoProvider: NetworkInfoProvider
    private lateinit var receiverDiscoveryScanner: ReceiverDiscoveryScanner
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered = false
    private var autoDiscoveryJob: Job? = null
    private var currentRole = BridgeRole.Sink
    private var lastObservedNetwork: Network? = null
    private var lastObservedLocalIp = ""
    private var wakeLock: PowerManager.WakeLock? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkMaybeChanged("Wi-Fi available", network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                handleNetworkMaybeChanged("Wi-Fi capabilities changed", network)
            }
        }

        override fun onLost(network: Network) {
            handleNetworkMaybeChanged("Wi-Fi lost", network)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        SmsArchiveStore.init(this)
        CallAlertStore.init(this)
        settings = AxonSettings(this)
        networkInfoProvider = NetworkInfoProvider(this)
        receiverDiscoveryScanner = ReceiverDiscoveryScanner(this)
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
                handleNotificationPayload(payload)
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
        currentRole = role
        mutableActiveTargetIp.value = serverIp

        updateState(BridgeConnectionState.Connecting, null)
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification(role, serverIp))
        mediaSessionTracker.stop()
        shadowMediaSession.stop()

        when (role) {
            BridgeRole.Sink -> {
                stopAutoDiscovery()
                unregisterNetworkCallback()
                shadowMediaSession.start()
                transport.startServer(host = "0.0.0.0")
            }
            BridgeRole.Source -> {
                registerNetworkCallback()
                mediaSessionTracker.start()
                if (serverIp.isBlank()) {
                    startAutoDiscovery("Receiver IP missing")
                } else {
                    transport.startClient(serverIp)
                }
            }
        }
    }

    private fun stopBridge() {
        stopAutoDiscovery()
        unregisterNetworkCallback()
        mediaSessionTracker.stop()
        shadowMediaSession.stop()
        mediaNotificationManager.cancel()
        CallAlertStore.clearActive()
        clearMedia()
        transport.stop()
        releaseWakeLock()
        updateState(BridgeConnectionState.Disconnected, null, running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = manager
        lastObservedNetwork = manager.activeNetwork
        lastObservedLocalIp = networkInfoProvider.localIpAddress()
        runCatching {
            manager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
            DiagnosticsLog.add("Auto discovery watching Wi-Fi changes")
        }.onFailure { error ->
            DiagnosticsLog.add("Wi-Fi watcher failed: ${error.message ?: error::class.simpleName}")
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        runCatching {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }
        networkCallbackRegistered = false
        connectivityManager = null
        lastObservedNetwork = null
        lastObservedLocalIp = ""
    }

    private fun handleNetworkMaybeChanged(reason: String, network: Network) {
        if (currentRole != BridgeRole.Source || !mutableIsRunning.value) return
        if (!activeNetworkIsWifi()) return
        val localIp = networkInfoProvider.localIpAddress()
        if (localIp.isBlank()) return
        val networkChanged = network != lastObservedNetwork
        val ipChanged = localIp != lastObservedLocalIp
        if (!networkChanged && !ipChanged && mutableActiveTargetIp.value.isNotBlank()) return
        lastObservedNetwork = network
        lastObservedLocalIp = localIp
        startAutoDiscovery(reason)
    }

    private fun activeNetworkIsWifi(): Boolean {
        val manager = connectivityManager ?: getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun startAutoDiscovery(reason: String) {
        if (currentRole != BridgeRole.Source) return
        if (autoDiscoveryJob?.isActive == true) {
            DiagnosticsLog.add("Auto discovery already running")
            return
        }
        autoDiscoveryJob = serviceScope.launch {
            val deadline = SystemClock.elapsedRealtime() + AUTO_DISCOVERY_WINDOW_MS
            var attempt = 1
            updateState(BridgeConnectionState.Connecting, "Scanning for receiver on Wi-Fi")
            while (isActive && currentRole == BridgeRole.Source && SystemClock.elapsedRealtime() <= deadline) {
                DiagnosticsLog.add("Auto discovery attempt $attempt: $reason")
                val receivers = receiverDiscoveryScanner.scan()
                val receiver = receivers.firstOrNull()
                if (receiver != null) {
                    settings.serverIp = receiver.ip
                    mutableActiveTargetIp.value = receiver.ip
                    DiagnosticsLog.add("Auto discovery found ${receiver.deviceName}: ${receiver.ip}")
                    startForeground(NOTIFICATION_ID, buildNotification(BridgeRole.Source, receiver.ip))
                    transport.startClient(receiver.ip, receiver.port)
                    sendStateChangedBroadcast()
                    return@launch
                }

                attempt += 1
                if (SystemClock.elapsedRealtime() + AUTO_DISCOVERY_RETRY_DELAY_MS > deadline) break
                DiagnosticsLog.add("Auto discovery found no receiver; retrying")
                delay(AUTO_DISCOVERY_RETRY_DELAY_MS)
            }

            DiagnosticsLog.add("Auto discovery timed out after 2 minutes")
            if (currentRole == BridgeRole.Source && mutableConnectionState.value != BridgeConnectionState.Connected) {
                updateState(BridgeConnectionState.Error, "No receiver found on this Wi-Fi")
            }
        }
    }

    private fun stopAutoDiscovery() {
        autoDiscoveryJob?.cancel()
        autoDiscoveryJob = null
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

    private fun handleNotificationPayload(payload: NotificationPayload) {
        when (payload.category) {
            NotificationCategory.Sms -> {
                SmsArchiveStore.add(this, payload)
                mirroredNotificationManager.show(payload)
            }
            NotificationCategory.Call -> {
                CallAlertStore.update(this, payload)
                if (payload.callState == CallState.Ended) {
                    mirroredNotificationManager.cancel(payload)
                    DiagnosticsLog.add("Cleared mirrored call: ${payload.title}")
                    return
                }
                mirroredNotificationManager.show(payload)
                if (payload.callState == null || payload.callState == CallState.Ringing) {
                    launchCallActivity(payload)
                }
            }
        }
    }

    private fun launchCallActivity(payload: NotificationPayload) {
        runCatching {
            startActivity(
                CallActivity.intent(
                    context = this,
                    payload = payload,
                    notificationId = MirroredNotificationManager.notificationId(payload)
                )
            )
        }.onSuccess {
            DiagnosticsLog.add("Opened mirrored call screen: ${payload.title}")
        }.onFailure { error ->
            DiagnosticsLog.add("Call screen launch failed: ${error.message ?: error::class.simpleName}")
        }
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
        stopAutoDiscovery()
        unregisterNetworkCallback()
        mediaSessionTracker.stop()
        shadowMediaSession.release()
        mediaNotificationManager.cancel()
        CallAlertStore.clearActive()
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
        private const val AUTO_DISCOVERY_WINDOW_MS = 120_000L
        private const val AUTO_DISCOVERY_RETRY_DELAY_MS = 10_000L

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

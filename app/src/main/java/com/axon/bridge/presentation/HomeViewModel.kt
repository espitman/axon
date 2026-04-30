package com.axon.bridge.presentation

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.axon.bridge.data.AxonSettings
import com.axon.bridge.data.BridgeCommandBus
import com.axon.bridge.data.CallBridgeBus
import com.axon.bridge.data.CallAlertStore
import com.axon.bridge.data.DeviceInfoProvider
import com.axon.bridge.data.DiagnosticsLog
import com.axon.bridge.data.MediaBridgeBus
import com.axon.bridge.data.NetworkInfoProvider
import com.axon.bridge.data.ReceiverDiscoveryScanner
import com.axon.bridge.data.SmsArchiveStore
import com.axon.bridge.domain.BridgeConnectionState
import com.axon.bridge.domain.BridgeRole
import com.axon.bridge.domain.CallCommandAction
import com.axon.bridge.domain.CallCommandPayload
import com.axon.bridge.domain.DiscoveredReceiver
import com.axon.bridge.domain.HomeState
import com.axon.bridge.domain.MediaCommandAction
import com.axon.bridge.domain.MediaCommandPayload
import com.axon.bridge.domain.PermissionStatus
import com.axon.bridge.domain.SmsArchiveMessage
import com.axon.bridge.service.BridgeService
import com.axon.bridge.service.AxonNotificationListenerService
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val settings = AxonSettings(appContext)
    private val deviceInfoProvider = DeviceInfoProvider()
    private val networkInfoProvider = NetworkInfoProvider(appContext)
    private val receiverDiscoveryScanner = ReceiverDiscoveryScanner(appContext)
    private var scanJob: Job? = null
    private var isScanningReceivers = false
    private var discoveredReceivers: List<DiscoveredReceiver> = emptyList()

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val bridgeStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BridgeService.ACTION_STATE_CHANGED) return
            refresh()
        }
    }

    init {
        SmsArchiveStore.init(appContext)
        CallAlertStore.init(appContext)
        DiagnosticsLog.onEntryAdded = { refresh() }
        ContextCompat.registerReceiver(
            appContext,
            bridgeStateReceiver,
            IntentFilter(BridgeService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        viewModelScope.launch {
            SmsArchiveStore.messages.collect {
                refresh()
            }
        }
        viewModelScope.launch {
            CallAlertStore.activeCall.collect {
                refresh()
            }
        }
        viewModelScope.launch {
            CallAlertStore.calls.collect {
                refresh()
            }
        }
    }

    fun selectRole(role: BridgeRole) {
        settings.role = role
        refresh()
    }

    fun updateServerIp(serverIp: String) {
        settings.serverIp = serverIp.trim()
        if (settings.role == BridgeRole.Source && BridgeService.isRunning.value) {
            appContext.stopService(Intent(appContext, BridgeService::class.java))
            BridgeService.publishState(
                BridgeConnectionState.Disconnected,
                false,
                "Receiver IP changed. Start bridge again."
            )
        }
        refresh()
    }

    fun resetServerIp() {
        settings.serverIp = ""
        if (settings.role == BridgeRole.Source && BridgeService.isRunning.value) {
            appContext.stopService(Intent(appContext, BridgeService::class.java))
            BridgeService.publishState(
                BridgeConnectionState.Disconnected,
                false,
                "Receiver IP reset. Enter it again."
            )
        }
        refresh()
    }

    fun toggleBridge() {
        if (BridgeService.isRunning.value) {
            appContext.stopService(Intent(appContext, BridgeService::class.java))
            BridgeService.publishState(BridgeConnectionState.Disconnected, false)
        } else {
            val intent = Intent(appContext, BridgeService::class.java).apply {
                action = BridgeService.ACTION_START
                putExtra(BridgeService.EXTRA_ROLE, settings.role.name)
                putExtra(BridgeService.EXTRA_SERVER_IP, settings.serverIp)
            }
            ContextCompat.startForegroundService(appContext, intent)
        }
        refresh()
    }

    fun openPermissionSettings() {
        DiagnosticsLog.add("Opening notification listener settings")
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startSettingsIntent(intent)
    }

    fun restartNotificationListener() {
        DiagnosticsLog.add("Notification listener rebind requested")
        NotificationListenerService.requestRebind(
            ComponentName(appContext, AxonNotificationListenerService::class.java)
        )
        refresh()
    }

    fun openAppNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${appContext.packageName}")
            }
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startSettingsIntent(intent)
    }

    fun openFullScreenCallSettings() {
        DiagnosticsLog.add("Opening full-screen call alert settings")
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${appContext.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                if (Build.VERSION.SDK_INT >= 26) {
                    putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                } else {
                    data = Uri.parse("package:${appContext.packageName}")
                }
            }
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startSettingsIntent(intent)
    }

    fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startSettingsIntent(intent)
    }

    fun refresh() {
        _state.value = buildState()
    }

    fun pingPeer() {
        BridgeCommandBus.ping()
        refresh()
    }

    fun sendMediaCommand(action: MediaCommandAction) {
        MediaBridgeBus.publishCommand(MediaCommandPayload(action))
        DiagnosticsLog.add("Media control requested: ${action.name}")
        refresh()
    }

    fun clearDiagnostics() {
        DiagnosticsLog.clear()
        refresh()
    }

    fun messagesForThread(threadId: String): List<SmsArchiveMessage> {
        return SmsArchiveStore.threadMessages(threadId)
    }

    fun markThreadRead(threadId: String) {
        SmsArchiveStore.markThreadRead(appContext, threadId)
        refresh()
    }

    fun deleteSmsMessage(messageId: String) {
        SmsArchiveStore.deleteMessage(appContext, messageId)
        DiagnosticsLog.add("Deleted SMS message")
        refresh()
    }

    fun deleteSmsMessages(messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        SmsArchiveStore.deleteMessages(appContext, messageIds)
        DiagnosticsLog.add("Deleted ${messageIds.size} SMS message(s)")
        refresh()
    }

    fun deleteSmsThread(threadId: String) {
        SmsArchiveStore.deleteThread(appContext, threadId)
        DiagnosticsLog.add("Deleted SMS thread")
        refresh()
    }

    fun deleteSmsThreads(threadIds: Set<String>) {
        if (threadIds.isEmpty()) return
        SmsArchiveStore.deleteThreads(appContext, threadIds)
        DiagnosticsLog.add("Deleted ${threadIds.size} SMS thread(s)")
        refresh()
    }

    fun dismissActiveCall() {
        CallAlertStore.clearActive()
        refresh()
    }

    fun rejectActiveCall() {
        CallBridgeBus.publishCommand(CallCommandPayload(CallCommandAction.Reject))
        DiagnosticsLog.add("Call reject command requested")
        CallAlertStore.clearActive()
        refresh()
    }

    fun deleteCallLog(callId: String) {
        CallAlertStore.delete(appContext, callId)
        DiagnosticsLog.add("Deleted call log")
        refresh()
    }

    fun deleteCallLogs(callIds: Set<String>) {
        if (callIds.isEmpty()) return
        CallAlertStore.delete(appContext, callIds)
        DiagnosticsLog.add("Deleted ${callIds.size} call log(s)")
        refresh()
    }

    fun clearCallLogs() {
        CallAlertStore.clear(appContext)
        DiagnosticsLog.add("Cleared call logs")
        refresh()
    }

    fun scanReceivers() {
        if (isScanningReceivers) return
        scanJob?.cancel()
        isScanningReceivers = true
        discoveredReceivers = emptyList()
        refresh()
        scanJob = viewModelScope.launch {
            discoveredReceivers = receiverDiscoveryScanner.scan()
            isScanningReceivers = false
            refresh()
        }
    }

    fun clearReceiverScan() {
        scanJob?.cancel()
        scanJob = null
        isScanningReceivers = false
        discoveredReceivers = emptyList()
        refresh()
    }

    fun selectDiscoveredReceiver(receiver: DiscoveredReceiver, startBridge: Boolean = true) {
        updateServerIp(receiver.ip)
        clearReceiverScan()
        if (startBridge && !BridgeService.isRunning.value) {
            toggleBridge()
        }
    }

    private fun buildState(): HomeState {
        val role = settings.role
        val localIp = networkInfoProvider.localIpAddress()
        return HomeState(
            role = role,
            connectionState = BridgeService.connectionState.value,
            serverIp = settings.serverIp,
            localIp = localIp,
            deviceInfo = deviceInfoProvider.currentDevice(),
            peerDeviceName = BridgeService.peerDeviceName.value,
            activeTargetIp = BridgeService.activeTargetIp.value,
            lastEventTimeMillis = BridgeService.lastEventTimeMillis.value,
            isBridgeRunning = BridgeService.isRunning.value,
            permissions = permissionStatuses(role),
            diagnostics = DiagnosticsLog.entries.value,
            smsThreads = SmsArchiveStore.threads(),
            callLogs = CallAlertStore.calls.value,
            activeCall = CallAlertStore.activeCall.value,
            activeMedia = BridgeService.activeMedia.value,
            activeMediaUpdatedAtElapsed = BridgeService.activeMediaUpdatedAtElapsed.value,
            isScanningReceivers = isScanningReceivers,
            discoveredReceivers = discoveredReceivers,
            errorMessage = BridgeService.errorMessage.value
        )
    }

    private fun permissionStatuses(role: BridgeRole): List<PermissionStatus> {
        val notificationAccess = isNotificationListenerEnabled()
        val postNotificationsGranted = Build.VERSION.SDK_INT < 33 ||
            appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val receiveSmsGranted = appContext.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val readContactsGranted = appContext.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val readPhoneStateGranted = appContext.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val readCallLogGranted = appContext.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val answerPhoneCallsGranted = appContext.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        val batteryOptimized = isIgnoringBatteryOptimizations()
        val fullScreenCallGranted = canUseFullScreenCallAlerts()

        val notificationAccessStatus = when {
            role == BridgeRole.Sink -> PermissionStatus("Notification access", "Sender only", true)
            notificationAccess -> PermissionStatus("Notification access", "Granted", true)
            else -> PermissionStatus("Notification access", "Needs review", false)
        }
        val fullScreenCallStatus = when {
            role == BridgeRole.Source -> PermissionStatus("Full-screen calls", "Receiver only", true)
            fullScreenCallGranted -> PermissionStatus("Full-screen calls", "Granted", true)
            else -> PermissionStatus("Full-screen calls", "Needs approval", false)
        }

        return listOf(
            notificationAccessStatus,
            PermissionStatus("SMS receiver", if (receiveSmsGranted) "Granted" else "Denied", receiveSmsGranted),
            PermissionStatus("Call receiver", if (readPhoneStateGranted) "Granted" else "Denied", readPhoneStateGranted),
            PermissionStatus("Call details", if (readCallLogGranted) "Granted" else "Denied", readCallLogGranted),
            PermissionStatus("Call control", if (answerPhoneCallsGranted) "Granted" else "Denied", answerPhoneCallsGranted),
            PermissionStatus("Contacts lookup", if (readContactsGranted) "Granted" else "Denied", readContactsGranted),
            PermissionStatus("App notifications", if (postNotificationsGranted) "Granted" else "Denied", postNotificationsGranted),
            fullScreenCallStatus,
            PermissionStatus("Battery protection", if (batteryOptimized) "Granted" else "Restricted", batteryOptimized),
            PermissionStatus("Device role", role.displayLabel(), true)
        )
    }

    private fun BridgeRole.displayLabel(): String {
        return when (this) {
            BridgeRole.Source -> "Sender"
            BridgeRole.Sink -> "Receiver"
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = android.provider.Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        return enabledListeners.contains(appContext.packageName, ignoreCase = true)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
    }

    private fun canUseFullScreenCallAlerts(): Boolean {
        return Build.VERSION.SDK_INT < 34 ||
            appContext.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
    }

    fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startSettingsIntent(intent)
    }

    private fun startSettingsIntent(intent: Intent) {
        try {
            appContext.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            appContext.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCleared() {
        scanJob?.cancel()
        DiagnosticsLog.onEntryAdded = null
        appContext.unregisterReceiver(bridgeStateReceiver)
        super.onCleared()
    }
}

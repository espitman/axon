package com.axon.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import com.axon.bridge.data.AxonSettings
import com.axon.bridge.data.ContactNameResolver
import com.axon.bridge.data.DeviceInfoProvider
import com.axon.bridge.data.DiagnosticsLog
import com.axon.bridge.data.NotificationEventBus
import com.axon.bridge.domain.BridgeRole
import com.axon.bridge.domain.NotificationCategory
import com.axon.bridge.domain.NotificationPayload
import kotlin.math.absoluteValue

class SmsBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) {
            DiagnosticsLog.add("SMS broadcast received: empty")
            return
        }

        val sender = messages.firstOrNull()?.displayOriginatingAddress.orEmpty()
        val senderName = ContactNameResolver.lookup(context, sender)
        val displaySender = senderName ?: sender.ifBlank { "SMS" }
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }.trim()
        DiagnosticsLog.add("SMS broadcast received: ${displaySender.ifBlank { "unknown sender" }}")

        if (body.isBlank()) {
            DiagnosticsLog.add("Skipped SMS broadcast: empty body")
            return
        }
        val settings = AxonSettings(context)
        if (settings.role != BridgeRole.Source) {
            DiagnosticsLog.add("SMS not sent: Axon role is Receiver")
            return
        }
        ensureSenderBridgeRunning(context, settings)

        val postedTime = messages.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
        val stableId = "$sender|$body|$postedTime".hashCode().absoluteValue.toString()
        val queued = NotificationEventBus.publish(
            NotificationPayload(
                id = stableId,
                category = NotificationCategory.Sms,
                originDevice = DeviceInfoProvider().currentDevice().displayName,
                title = displaySender,
                message = body,
                packageName = "android.provider.Telephony.SMS_RECEIVED",
                postedTime = postedTime
            )
        )
        DiagnosticsLog.add(
            if (queued) "Queued SMS broadcast: $displaySender" else "SMS queue failed: $displaySender"
        )
    }

    private fun ensureSenderBridgeRunning(context: Context, settings: AxonSettings) {
        if (BridgeService.isRunning.value) return
        DiagnosticsLog.add("Starting Sender bridge for SMS")
        val serviceIntent = Intent(context, BridgeService::class.java).apply {
            action = BridgeService.ACTION_START
            putExtra(BridgeService.EXTRA_ROLE, BridgeRole.Source.name)
            putExtra(BridgeService.EXTRA_SERVER_IP, settings.serverIp)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}

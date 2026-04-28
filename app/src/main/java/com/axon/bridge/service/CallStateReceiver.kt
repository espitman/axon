package com.axon.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.axon.bridge.data.ContactNameResolver
import com.axon.bridge.data.DeviceInfoProvider
import com.axon.bridge.data.DiagnosticsLog
import com.axon.bridge.data.NotificationEventBus
import com.axon.bridge.domain.NotificationCategory
import com.axon.bridge.domain.NotificationPayload
import kotlin.math.absoluteValue

class CallStateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE).orEmpty()
        val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        DiagnosticsLog.add("Call state received: ${state.ifBlank { "unknown" }}")

        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val contactName = ContactNameResolver.lookup(context, rawNumber)
        val caller = contactName ?: rawNumber.ifBlank { "Incoming call" }
        val now = System.currentTimeMillis()
        val stableId = "call|$rawNumber|$now".hashCode().absoluteValue.toString()

        NotificationEventBus.publish(
            NotificationPayload(
                id = stableId,
                category = NotificationCategory.Call,
                originDevice = DeviceInfoProvider().currentDevice().displayName,
                title = caller,
                message = "Incoming call",
                packageName = "android.intent.action.PHONE_STATE",
                postedTime = now
            )
        )
        DiagnosticsLog.add("Queued call broadcast: $caller")
    }
}

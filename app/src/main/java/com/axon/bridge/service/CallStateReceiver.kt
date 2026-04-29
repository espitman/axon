package com.axon.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.axon.bridge.data.ContactNameResolver
import com.axon.bridge.data.DeviceInfoProvider
import com.axon.bridge.data.DiagnosticsLog
import com.axon.bridge.data.NotificationEventBus
import com.axon.bridge.domain.CallState
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

        val now = System.currentTimeMillis()
        val callState = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> CallState.Ringing
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallState.InCall
            TelephonyManager.EXTRA_STATE_IDLE -> CallState.Ended
            else -> return
        }

        if (callState != CallState.Ended) {
            val contactName = ContactNameResolver.lookup(context, rawNumber)
            activeCaller = contactName ?: rawNumber.ifBlank {
                if (callState == CallState.Ringing) "Incoming call" else "Phone call"
            }
            activeNumber = rawNumber
            if (activeCallId == null) {
                activeCallId = "call|${rawNumber.ifBlank { "unknown" }}|$now"
                    .hashCode()
                    .absoluteValue
                    .toString()
                activeStartedAt = now
            }
        } else if (activeCallId == null) {
            DiagnosticsLog.add("Call idle skipped: no active call")
            return
        }

        val stableId = activeCallId ?: return
        val caller = activeCaller.ifBlank { "Phone call" }
        val postedTime = if (callState == CallState.Ringing) activeStartedAt else now

        NotificationEventBus.publish(
            NotificationPayload(
                id = stableId,
                category = NotificationCategory.Call,
                originDevice = DeviceInfoProvider().currentDevice().displayName,
                title = caller,
                message = callState.message,
                packageName = "android.intent.action.PHONE_STATE",
                postedTime = postedTime,
                callState = callState
            )
        )
        DiagnosticsLog.add("Queued call ${callState.name}: $caller")

        if (callState == CallState.Ended) {
            activeCallId = null
            activeCaller = ""
            activeNumber = ""
            activeStartedAt = 0L
        }
    }

    private val CallState.message: String
        get() = when (this) {
            CallState.Ringing -> "Incoming call"
            CallState.InCall -> "In call"
            CallState.Ended -> "Call ended"
        }

    private companion object {
        var activeCallId: String? = null
        var activeCaller: String = ""
        var activeNumber: String = ""
        var activeStartedAt: Long = 0L
    }
}

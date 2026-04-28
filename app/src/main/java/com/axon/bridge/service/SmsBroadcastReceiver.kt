package com.axon.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.axon.bridge.data.DeviceInfoProvider
import com.axon.bridge.data.DiagnosticsLog
import com.axon.bridge.data.NotificationEventBus
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
        val senderName = context.lookupContactName(sender)
        val displaySender = senderName ?: sender.ifBlank { "SMS" }
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }.trim()
        DiagnosticsLog.add("SMS broadcast received: ${displaySender.ifBlank { "unknown sender" }}")

        if (body.isBlank()) {
            DiagnosticsLog.add("Skipped SMS broadcast: empty body")
            return
        }

        val postedTime = messages.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
        val stableId = "$sender|$body|$postedTime".hashCode().absoluteValue.toString()
        NotificationEventBus.publish(
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
        DiagnosticsLog.add("Queued SMS broadcast: $displaySender")
    }

    private fun Context.lookupContactName(phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            DiagnosticsLog.add("Contact lookup skipped: permission denied")
            return null
        }

        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        return runCatching {
            contentResolver.query(lookupUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else {
                    null
                }
            }
        }.onFailure { error ->
            DiagnosticsLog.add("Contact lookup failed: ${error.message ?: error::class.simpleName}")
        }.getOrNull()
    }
}

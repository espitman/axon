package com.axon.bridge.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactNameResolver {
    fun lookup(context: Context, phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        val granted = ContextCompat.checkSelfPermission(
            context,
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
            context.contentResolver.query(lookupUri, projection, null, null, null)?.use { cursor ->
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

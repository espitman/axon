package com.axon.bridge.data

import com.axon.bridge.domain.NotificationPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object CallAlertStore {
    private val mutableActiveCall = MutableStateFlow<NotificationPayload?>(null)
    val activeCall: StateFlow<NotificationPayload?> = mutableActiveCall

    fun show(payload: NotificationPayload) {
        mutableActiveCall.value = payload
    }

    fun clear() {
        mutableActiveCall.value = null
    }
}

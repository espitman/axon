package com.axon.bridge.data

import com.axon.bridge.domain.NotificationPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object NotificationEventBus {
    private val mutableEvents = MutableSharedFlow<NotificationPayload>(
        extraBufferCapacity = 64
    )

    val events = mutableEvents.asSharedFlow()

    fun publish(payload: NotificationPayload) {
        mutableEvents.tryEmit(payload)
    }
}

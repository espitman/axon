package com.axon.bridge.data

import com.axon.bridge.domain.NotificationPayload
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object NotificationEventBus {
    private val mutableEvents = MutableSharedFlow<NotificationPayload>(
        replay = 8,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events = mutableEvents.asSharedFlow()

    fun publish(payload: NotificationPayload): Boolean {
        return mutableEvents.tryEmit(payload)
    }
}

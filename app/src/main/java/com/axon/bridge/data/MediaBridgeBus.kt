package com.axon.bridge.data

import com.axon.bridge.domain.MediaCommandPayload
import com.axon.bridge.domain.MediaPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object MediaBridgeBus {
    private val mutableUpdates = MutableSharedFlow<MediaPayload>(
        extraBufferCapacity = 32
    )
    private val mutableCommands = MutableSharedFlow<MediaCommandPayload>(
        extraBufferCapacity = 16
    )
    private val mutableClears = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8
    )

    val updates = mutableUpdates.asSharedFlow()
    val commands = mutableCommands.asSharedFlow()
    val clears = mutableClears.asSharedFlow()

    fun publishUpdate(payload: MediaPayload) {
        mutableUpdates.tryEmit(payload)
    }

    fun publishCommand(payload: MediaCommandPayload) {
        mutableCommands.tryEmit(payload)
    }

    fun publishClear() {
        mutableClears.tryEmit(Unit)
    }
}

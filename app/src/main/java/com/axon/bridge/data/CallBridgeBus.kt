package com.axon.bridge.data

import com.axon.bridge.domain.CallCommandPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object CallBridgeBus {
    private val mutableCommands = MutableSharedFlow<CallCommandPayload>(
        extraBufferCapacity = 16
    )

    val commands = mutableCommands.asSharedFlow()

    fun publishCommand(payload: CallCommandPayload) {
        mutableCommands.tryEmit(payload)
    }
}

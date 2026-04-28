package com.axon.bridge.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object BridgeCommandBus {
    private val mutablePings = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val pings = mutablePings.asSharedFlow()

    fun ping() {
        DiagnosticsLog.add("Ping requested")
        mutablePings.tryEmit(Unit)
    }
}

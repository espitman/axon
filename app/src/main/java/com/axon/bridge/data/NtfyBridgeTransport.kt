package com.axon.bridge.data

import com.axon.bridge.domain.BridgeConnectionState

class NtfyBridgeTransport(
    private val onStateChanged: (BridgeConnectionState, String?) -> Unit
) : BridgeTransport {

    override fun startServer(host: String, port: Int) {
        reportNotImplemented()
    }

    override fun startClient(serverIp: String, port: Int) {
        reportNotImplemented()
    }

    override fun stop() = Unit

    private fun reportNotImplemented() {
        DiagnosticsLog.add("ntfy transport selected but not implemented yet")
        onStateChanged(BridgeConnectionState.Error, "ntfy transport is not implemented yet")
    }
}

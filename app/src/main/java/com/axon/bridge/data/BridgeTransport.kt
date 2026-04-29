package com.axon.bridge.data

import com.axon.bridge.domain.BridgeConnectionState
import com.axon.bridge.domain.BridgeMessage
import com.axon.bridge.domain.BridgeMessageType
import com.axon.bridge.domain.BridgeRole
import com.axon.bridge.domain.DiscoveryResponse
import com.axon.bridge.domain.HelloPayload
import com.axon.bridge.domain.NotificationPayload
import io.ktor.http.ContentType
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Proxy

class BridgeTransport(
    private val scope: CoroutineScope,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val onStateChanged: (BridgeConnectionState, String?) -> Unit,
    private val onPeerChanged: (String) -> Unit,
    private val onEventTransferred: () -> Unit,
    private val onNotificationReceived: (NotificationPayload) -> Unit
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var server: ApplicationEngine? = null
    private var client: HttpClient? = null
    private var clientJob: Job? = null

    fun startServer(host: String = "0.0.0.0", port: Int = DEFAULT_PORT) {
        stop()
        DiagnosticsLog.add("Receiver server starting on $host:$port")
        onStateChanged(BridgeConnectionState.Connecting, null)
        server = embeddedServer(CIO, host = host, port = port) {
            install(ServerWebSockets) {
                pingPeriodMillis = 30_000
                timeoutMillis = 15_000
            }
            routing {
                get(DISCOVERY_PATH) {
                    call.respondText(
                        text = json.encodeToString(
                            DiscoveryResponse(
                                deviceName = deviceInfoProvider.currentDevice().displayName,
                                port = port
                            )
                        ),
                        contentType = ContentType.Application.Json
                    )
                }
                webSocket(BRIDGE_PATH) {
                    DiagnosticsLog.add("Sender connected")
                    onStateChanged(BridgeConnectionState.Connected, null)
                    sendHello(BridgeRole.Sink)
                    val pingJob = launchPingSender()
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                handleIncomingText(frame.readText())
                            }
                        }
                    } finally {
                        pingJob.cancelAndJoin()
                        if (server != null) {
                            onStateChanged(BridgeConnectionState.Connecting, "Waiting for sender device")
                        } else {
                            onStateChanged(BridgeConnectionState.Disconnected, null)
                        }
                    }
                }
            }
        }.start(wait = false)
        onStateChanged(BridgeConnectionState.Connecting, "Waiting for sender device")
    }

    fun startClient(serverIp: String, port: Int = DEFAULT_PORT) {
        stop()
        val normalizedIp = serverIp.trim()
        if (normalizedIp.isBlank()) {
            DiagnosticsLog.add("Sender missing receiver IP")
            onStateChanged(BridgeConnectionState.Error, "Receiver IP is required")
            return
        }
        DiagnosticsLog.add("Sender connecting to $normalizedIp:$port")

        client = HttpClient(OkHttp) {
            engine {
                config {
                    proxy(Proxy.NO_PROXY)
                }
            }
            install(WebSockets) {
                pingInterval = 30_000
            }
        }
        clientJob = scope.launch {
            var backoffMs = 2_000L
            while (isActive) {
                onStateChanged(BridgeConnectionState.Connecting, null)
                try {
                    client?.webSocket(
                        method = HttpMethod.Get,
                        host = normalizedIp,
                        port = port,
                        path = BRIDGE_PATH
                    ) {
                        backoffMs = 2_000L
                        onStateChanged(BridgeConnectionState.Connected, null)
                        sendHello(BridgeRole.Source)
                        coroutineScope {
                            val outgoingJob = launch {
                                NotificationEventBus.events.collect { payload ->
                                    val message = BridgeMessage(
                                        type = BridgeMessageType.NotificationEvent,
                                        payload = payload
                                    )
                                    send(Frame.Text(json.encodeToString(message)))
                                    DiagnosticsLog.add("Sent ${payload.category.name}: ${payload.title}")
                                    onEventTransferred()
                                }
                            }
                            val pingJob = launchPingSender()
                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        handleIncomingText(frame.readText())
                                    }
                                }
                            } finally {
                                outgoingJob.cancelAndJoin()
                                pingJob.cancelAndJoin()
                            }
                        }
                    }
                    onStateChanged(BridgeConnectionState.Disconnected, null)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    DiagnosticsLog.add("Connection error: ${error.message ?: error::class.simpleName}")
                    onStateChanged(BridgeConnectionState.Error, error.message ?: "Connection failed")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    fun stop() {
        clientJob?.cancel()
        clientJob = null
        client?.close()
        client = null
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
        server = null
    }

    private suspend fun io.ktor.websocket.WebSocketSession.sendHello(role: BridgeRole) {
        val message = BridgeMessage(
            type = BridgeMessageType.Hello,
            hello = HelloPayload(
                role = role,
                deviceName = deviceInfoProvider.currentDevice().displayName
            )
        )
        send(Frame.Text(json.encodeToString(message)))
    }

    private suspend fun io.ktor.websocket.WebSocketSession.handleIncomingText(text: String) {
        val message = runCatching { json.decodeFromString<BridgeMessage>(text) }.getOrNull() ?: return
        when (message.type) {
            BridgeMessageType.Hello -> {
                message.hello?.deviceName?.takeIf { it.isNotBlank() }?.let(onPeerChanged)
                DiagnosticsLog.add("Peer hello: ${message.hello?.deviceName.orEmpty()}")
                send(Frame.Text(json.encodeToString(BridgeMessage(type = BridgeMessageType.Ack))))
            }
            BridgeMessageType.NotificationEvent -> {
                message.payload?.let { payload ->
                    DiagnosticsLog.add("Received ${payload.category.name}: ${payload.title}")
                    onEventTransferred()
                    onNotificationReceived(payload)
                }
            }
            BridgeMessageType.Ping -> {
                DiagnosticsLog.add("Ping received")
                send(Frame.Text(json.encodeToString(BridgeMessage(type = BridgeMessageType.Ack))))
            }
            BridgeMessageType.Ack -> {
                DiagnosticsLog.add("Ping acknowledged")
            }
        }
    }

    private fun io.ktor.websocket.WebSocketSession.launchPingSender(): Job {
        return scope.launch {
            BridgeCommandBus.pings.collect {
                send(Frame.Text(json.encodeToString(BridgeMessage(type = BridgeMessageType.Ping))))
                DiagnosticsLog.add("Ping sent")
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val BRIDGE_PATH = "/bridge"
        const val DISCOVERY_PATH = "/discovery"
    }
}

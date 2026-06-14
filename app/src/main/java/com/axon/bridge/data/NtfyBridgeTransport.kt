package com.axon.bridge.data

import android.util.Base64
import com.axon.bridge.domain.BridgeConnectionState
import com.axon.bridge.domain.BridgeMessage
import com.axon.bridge.domain.BridgeMessageType
import com.axon.bridge.domain.BridgeRole
import com.axon.bridge.domain.MediaPayload
import com.axon.bridge.domain.NtfySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class NtfyBridgeTransport(
    private val scope: CoroutineScope,
    private val ntfySettings: NtfySettings,
    localDeviceId: String,
    private val localRole: BridgeRole,
    private val onStateChanged: (BridgeConnectionState, String?) -> Unit,
    private val onEventTransferred: () -> Unit,
    private val onNotificationReceived: (com.axon.bridge.domain.NotificationPayload) -> Unit,
    private val onMediaUpdateReceived: (MediaPayload) -> Unit,
    private val onMediaCommandReceived: (com.axon.bridge.domain.MediaCommandPayload) -> Unit,
    private val onMediaCleared: () -> Unit,
    private val onCallCommandReceived: (com.axon.bridge.domain.CallCommandPayload) -> Unit
) : BridgeTransport {
    private val relayEnvelopeCodec = RelayEnvelopeCodec(
        pairId = ntfySettings.pairId,
        localDeviceId = localDeviceId,
        localRole = localRole
    )
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val pendingMessages = ArrayDeque<OutboundRelayMessage>()
    private val jobs = mutableListOf<Job>()
    private val flushMutex = Mutex()
    @Volatile
    private var activeSubscription: HttpURLConnection? = null

    override fun startServer(host: String, port: Int) {
        stop()
        if (!validateSettings()) return
        DiagnosticsLog.add("ntfy Receiver subscribing to ${ntfySettings.senderToReceiverTopic}")
        onStateChanged(BridgeConnectionState.Connecting, "Connecting to ntfy relay")
        jobs += scope.launch {
            subscribeLoop(topic = ntfySettings.senderToReceiverTopic)
        }
        jobs += scope.launch {
            MediaBridgeBus.commands.collect { payload ->
                enqueue(
                    BridgeMessage(
                        type = BridgeMessageType.MediaCommand,
                        command = payload
                    ),
                    topic = ntfySettings.receiverToSenderTopic,
                    targetRole = BridgeRole.Source
                )
                flushPending()
            }
        }
        jobs += scope.launch {
            CallBridgeBus.commands.collect { payload ->
                enqueue(
                    BridgeMessage(
                        type = BridgeMessageType.CallCommand,
                        callCommand = payload
                    ),
                    topic = ntfySettings.receiverToSenderTopic,
                    targetRole = BridgeRole.Source
                )
                flushPending()
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(RETRY_FLUSH_INTERVAL_MS)
                flushPending()
            }
        }
    }

    override fun startClient(serverIp: String, port: Int) {
        stop()
        if (!validateSettings()) return
        DiagnosticsLog.add("ntfy Sender ready for ${ntfySettings.senderToReceiverTopic}")
        onStateChanged(BridgeConnectionState.Connecting, "Connecting to ntfy relay")
        jobs += scope.launch {
            subscribeLoop(topic = ntfySettings.receiverToSenderTopic)
        }
        jobs += scope.launch {
            NotificationEventBus.events.collect { payload ->
                enqueue(
                    BridgeMessage(
                        type = BridgeMessageType.NotificationEvent,
                        payload = payload
                    ),
                    topic = ntfySettings.senderToReceiverTopic,
                    targetRole = BridgeRole.Sink
                )
                flushPending()
            }
        }
        jobs += scope.launch {
            MediaBridgeBus.updates.collect { payload ->
                enqueue(
                    mediaUpdateMessage(payload),
                    topic = ntfySettings.senderToReceiverTopic,
                    targetRole = BridgeRole.Sink
                )
                flushPending()
            }
        }
        jobs += scope.launch {
            MediaBridgeBus.clears.collect {
                enqueue(
                    BridgeMessage(type = BridgeMessageType.MediaClear),
                    topic = ntfySettings.senderToReceiverTopic,
                    targetRole = BridgeRole.Sink
                )
                flushPending()
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(RETRY_FLUSH_INTERVAL_MS)
                flushPending()
            }
        }
    }

    override fun stop() {
        val activeJobs = jobs.toList()
        jobs.clear()
        activeJobs.forEach { job -> job.cancel() }
        activeSubscription?.disconnect()
        activeSubscription = null
    }

    private fun validateSettings(): Boolean {
        val error = when {
            ntfySettings.serverUrl.isBlank() -> "ntfy server URL is required"
            ntfySettings.pairId.isBlank() -> "ntfy pair ID is required"
            ntfySettings.username.isBlank() -> "ntfy username is required"
            ntfySettings.password.isBlank() -> "ntfy password or token is required"
            else -> null
        }
        if (error != null) {
            DiagnosticsLog.add(error)
            onStateChanged(BridgeConnectionState.Error, error)
            return false
        }
        return true
    }

    private fun enqueue(
        message: BridgeMessage,
        topic: String,
        targetRole: BridgeRole
    ) {
        synchronized(pendingMessages) {
            pendingMessages.addLast(
                OutboundRelayMessage(
                    message = message,
                    topic = topic,
                    targetRole = targetRole
                )
            )
            while (pendingMessages.size > MAX_PENDING_MESSAGES) {
                pendingMessages.removeFirst()
                DiagnosticsLog.add("ntfy pending queue trimmed")
            }
        }
    }

    private suspend fun flushPending() {
        flushMutex.withLock {
            while (true) {
                val next = synchronized(pendingMessages) {
                    pendingMessages.firstOrNull()
                } ?: return

                val published = publishWithRetry(next)
                if (!published) return

                synchronized(pendingMessages) {
                    if (pendingMessages.firstOrNull() == next) {
                        pendingMessages.removeFirst()
                    }
                }
            }
        }
    }

    private suspend fun publishWithRetry(outbound: OutboundRelayMessage): Boolean {
        repeat(PUBLISH_RETRY_COUNT) { attempt ->
            try {
                publish(outbound)
                onEventTransferred()
                return true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                DiagnosticsLog.add("ntfy publish failed: ${error.message ?: error::class.simpleName}")
                onStateChanged(BridgeConnectionState.Error, "ntfy publish failed")
                delay(1_000L * (attempt + 1))
            }
        }
        return false
    }

    private fun publish(outbound: OutboundRelayMessage) {
        val envelopeText = relayEnvelopeCodec.encode(
            message = outbound.message,
            targetRole = outbound.targetRole
        )
        val connection = openConnection(outbound.topic).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        }
        connection.outputStream.use { output ->
            output.write(envelopeText.toByteArray(Charsets.UTF_8))
        }
        val responseCode = connection.responseCode
        connection.disconnect()
        if (responseCode !in 200..299) {
            error("HTTP $responseCode")
        }
        DiagnosticsLog.add("ntfy published ${outbound.message.type.name}")
        onStateChanged(BridgeConnectionState.Connected, "ntfy relay ready")
    }

    private suspend fun subscribeLoop(topic: String) {
        var backoffMs = 2_000L
        while (scope.isActive) {
            try {
                onStateChanged(BridgeConnectionState.Connecting, "Subscribing to ntfy")
                subscribe(topic)
                backoffMs = 2_000L
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                DiagnosticsLog.add("ntfy subscribe failed: ${error.message ?: error::class.simpleName}")
                onStateChanged(BridgeConnectionState.Error, "ntfy subscribe failed")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun subscribe(topic: String) {
        val connection = openConnection("$topic/json").apply {
            requestMethod = "GET"
            readTimeout = 0
        }
        activeSubscription = connection
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            error("HTTP $responseCode")
        }
        DiagnosticsLog.add("ntfy subscription connected")
        onStateChanged(BridgeConnectionState.Connected, "ntfy relay subscribed")
        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                handleNtfyLine(line)
            }
        }
        connection.disconnect()
        activeSubscription = null
        DiagnosticsLog.add("ntfy subscription disconnected")
        onStateChanged(BridgeConnectionState.Disconnected, null)
    }

    private fun handleNtfyLine(line: String) {
        val event = runCatching { json.decodeFromString<NtfyStreamEvent>(line) }
            .getOrElse { error ->
                DiagnosticsLog.add("ntfy event malformed: ${error.message ?: error::class.simpleName}")
                return
            }
        if (event.event != "message") return
        val messageText = event.message.orEmpty()
        if (messageText.isBlank()) {
            DiagnosticsLog.add("ntfy message ignored: empty payload")
            return
        }
        when (val result = relayEnvelopeCodec.decode(messageText)) {
            is RelayEnvelopeDecodeResult.Accepted -> handleBridgeMessage(result.message)
            is RelayEnvelopeDecodeResult.Ignored -> Unit
            is RelayEnvelopeDecodeResult.Malformed -> Unit
        }
    }

    private fun handleBridgeMessage(message: BridgeMessage) {
        when (message.type) {
            BridgeMessageType.NotificationEvent -> {
                message.payload?.let { payload ->
                    DiagnosticsLog.add("ntfy received ${payload.category.name}: ${payload.title}")
                    onNotificationReceived(payload)
                    onEventTransferred()
                }
            }
            BridgeMessageType.MediaUpdate -> {
                message.media?.let { media ->
                    DiagnosticsLog.add("ntfy media update received: ${media.title}")
                    onMediaUpdateReceived(media)
                    onEventTransferred()
                }
            }
            BridgeMessageType.MediaClear -> {
                DiagnosticsLog.add("ntfy media clear received")
                onMediaCleared()
                onEventTransferred()
            }
            BridgeMessageType.MediaCommand -> {
                message.command?.let { command ->
                    DiagnosticsLog.add("ntfy media command received: ${command.action.name}")
                    onMediaCommandReceived(command)
                    onEventTransferred()
                }
            }
            BridgeMessageType.CallCommand -> {
                message.callCommand?.let { command ->
                    DiagnosticsLog.add("ntfy call command received: ${command.action.name}")
                    onCallCommandReceived(command)
                    onEventTransferred()
                }
            }
            else -> {
                DiagnosticsLog.add("ntfy message ignored: ${message.type.name}")
            }
        }
    }

    private fun mediaUpdateMessage(payload: MediaPayload): BridgeMessage {
        val messageWithArtwork = BridgeMessage(
            type = BridgeMessageType.MediaUpdate,
            media = payload
        )
        val encodedWithArtwork = relayEnvelopeCodec.encode(messageWithArtwork, BridgeRole.Sink)
        val encodedWithArtworkBytes = MediaArtworkPolicy.utf8Size(encodedWithArtwork)
        val artworkBase64Size = MediaArtworkPolicy.artworkBase64Size(payload)
        DiagnosticsLog.add(
            "ntfy media payload size: ${encodedWithArtworkBytes}B, artwork: ${artworkBase64Size} chars"
        )
        if (
            encodedWithArtworkBytes <= MediaArtworkPolicy.NTFY_INLINE_PAYLOAD_LIMIT_BYTES ||
            payload.artworkBase64 == null
        ) {
            return messageWithArtwork
        }
        val messageWithoutArtwork = MediaArtworkPolicy.withoutArtwork(messageWithArtwork)
        val encodedWithoutArtworkBytes = MediaArtworkPolicy.utf8Size(
            relayEnvelopeCodec.encode(messageWithoutArtwork, BridgeRole.Sink)
        )
        DiagnosticsLog.add(
            "ntfy media artwork omitted: ${encodedWithArtworkBytes}B -> ${encodedWithoutArtworkBytes}B"
        )
        return messageWithoutArtwork
    }

    private fun openConnection(path: String): HttpURLConnection {
        val url = "${ntfySettings.serverUrl.trim().trimEnd('/')}/${path.trimStart('/')}"
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            setRequestProperty("Authorization", basicAuthHeader())
        }
    }

    private fun basicAuthHeader(): String {
        val credentials = "${ntfySettings.username}:${ntfySettings.password}"
        val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    @Serializable
    private data class NtfyStreamEvent(
        val id: String? = null,
        val event: String,
        val topic: String? = null,
        val message: String? = null
    )

    private data class OutboundRelayMessage(
        val message: BridgeMessage,
        val topic: String,
        val targetRole: BridgeRole
    )

    companion object {
        private const val MAX_PENDING_MESSAGES = 64
        private const val PUBLISH_RETRY_COUNT = 3
        private const val RETRY_FLUSH_INTERVAL_MS = 15_000L
    }
}

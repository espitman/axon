package com.axon.bridge.data

import com.axon.bridge.domain.BridgeMessage
import com.axon.bridge.domain.BridgeRole
import com.axon.bridge.domain.RelayEnvelope
import com.axon.bridge.domain.toNtfyTopicSegment
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class RelayEnvelopeCodec(
    private val pairId: String,
    private val localDeviceId: String,
    private val localRole: BridgeRole,
    private val dedupeCache: RelayDedupeCache = RelayDedupeCache()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: BridgeMessage, targetRole: BridgeRole): String {
        val envelope = RelayEnvelope(
            messageId = UUID.randomUUID().toString(),
            pairId = normalizedPairId(),
            sourceDeviceId = localDeviceId,
            targetRole = targetRole,
            createdAt = System.currentTimeMillis(),
            messageType = message.type,
            bridgePayload = message
        )
        return json.encodeToString(envelope)
    }

    fun decode(text: String): RelayEnvelopeDecodeResult {
        val envelope = try {
            json.decodeFromString<RelayEnvelope>(text)
        } catch (error: SerializationException) {
            DiagnosticsLog.add("Relay message malformed: ${error.message ?: "invalid JSON"}")
            return RelayEnvelopeDecodeResult.Malformed("Invalid relay JSON")
        } catch (error: IllegalArgumentException) {
            DiagnosticsLog.add("Relay message malformed: ${error.message ?: "invalid payload"}")
            return RelayEnvelopeDecodeResult.Malformed("Invalid relay payload")
        }

        return validate(envelope)
    }

    private fun validate(envelope: RelayEnvelope): RelayEnvelopeDecodeResult {
        if (envelope.schema != RelayEnvelope.RELAY_SCHEMA) {
            DiagnosticsLog.add("Relay message ignored: unsupported schema ${envelope.schema}")
            return RelayEnvelopeDecodeResult.Ignored("Unsupported schema")
        }
        if (envelope.payloadVersion != RelayEnvelope.RELAY_PAYLOAD_VERSION) {
            DiagnosticsLog.add("Relay message ignored: unsupported payload v${envelope.payloadVersion}")
            return RelayEnvelopeDecodeResult.Ignored("Unsupported payload version")
        }
        if (envelope.messageId.isBlank()) {
            DiagnosticsLog.add("Relay message malformed: missing message ID")
            return RelayEnvelopeDecodeResult.Malformed("Missing message ID")
        }
        if (envelope.messageType != envelope.bridgePayload.type) {
            DiagnosticsLog.add("Relay message malformed: envelope type mismatch")
            return RelayEnvelopeDecodeResult.Malformed("Message type mismatch")
        }
        if (envelope.pairId.toNtfyTopicSegment() != normalizedPairId()) {
            DiagnosticsLog.add("Relay message ignored: wrong pair")
            return RelayEnvelopeDecodeResult.Ignored("Wrong pair ID")
        }
        if (envelope.sourceDeviceId == localDeviceId) {
            DiagnosticsLog.add("Relay message ignored: local echo")
            return RelayEnvelopeDecodeResult.Ignored("Local echo")
        }
        if (envelope.targetRole != localRole) {
            DiagnosticsLog.add("Relay message ignored: wrong target role")
            return RelayEnvelopeDecodeResult.Ignored("Wrong target role")
        }
        if (!dedupeCache.markIfNew(envelope.messageId)) {
            DiagnosticsLog.add("Relay message ignored: duplicate ${envelope.messageId.take(8)}")
            return RelayEnvelopeDecodeResult.Ignored("Duplicate message")
        }

        DiagnosticsLog.add("Relay message accepted: ${envelope.messageType.name}")
        return RelayEnvelopeDecodeResult.Accepted(envelope, envelope.bridgePayload)
    }

    private fun normalizedPairId(): String = pairId.toNtfyTopicSegment()
}

class RelayDedupeCache(
    private val maxEntries: Int = 512,
    private val ttlMillis: Long = 6 * 60 * 60 * 1000L
) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun markIfNew(messageId: String, now: Long = System.currentTimeMillis()): Boolean {
        prune(now)
        if (seen.containsKey(messageId)) return false
        seen[messageId] = now
        while (seen.size > maxEntries) {
            val oldestKey = seen.entries.firstOrNull()?.key ?: break
            seen.remove(oldestKey)
        }
        return true
    }

    private fun prune(now: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > ttlMillis) {
                iterator.remove()
            }
        }
    }
}

sealed interface RelayEnvelopeDecodeResult {
    data class Accepted(
        val envelope: RelayEnvelope,
        val message: BridgeMessage
    ) : RelayEnvelopeDecodeResult

    data class Ignored(val reason: String) : RelayEnvelopeDecodeResult
    data class Malformed(val reason: String) : RelayEnvelopeDecodeResult
}

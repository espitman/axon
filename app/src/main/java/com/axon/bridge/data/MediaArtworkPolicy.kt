package com.axon.bridge.data

import com.axon.bridge.domain.BridgeMessage
import com.axon.bridge.domain.MediaPayload

object MediaArtworkPolicy {
    const val NTFY_INLINE_PAYLOAD_LIMIT_BYTES = 3_500

    fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    fun artworkBase64Size(media: MediaPayload): Int = media.artworkBase64?.length ?: 0

    fun withoutArtwork(message: BridgeMessage): BridgeMessage {
        return message.copy(media = message.media?.copy(artworkBase64 = null))
    }
}

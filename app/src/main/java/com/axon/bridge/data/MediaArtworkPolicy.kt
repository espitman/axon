package com.axon.bridge.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.axon.bridge.domain.BridgeMessage
import com.axon.bridge.domain.MediaPayload
import java.io.ByteArrayOutputStream

object MediaArtworkPolicy {
    const val NTFY_INLINE_PAYLOAD_LIMIT_BYTES = 3_500

    fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    fun artworkBase64Size(media: MediaPayload): Int = media.artworkBase64?.length ?: 0

    fun withoutArtwork(message: BridgeMessage): BridgeMessage {
        return message.copy(media = message.media?.copy(artworkBase64 = null))
    }

    fun compactArtworkCandidates(media: MediaPayload): List<MediaPayload> {
        val encoded = media.artworkBase64 ?: return emptyList()
        val source = runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return emptyList()

        return COMPACT_ARTWORK_STEPS.mapNotNull { step ->
            runCatching {
                val scaled = source.scaleToMax(step.maxSize)
                val output = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, step.quality, output)
                media.copy(
                    artworkBase64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                )
            }.getOrNull()
        }.distinctBy { it.artworkBase64 }
    }

    private fun Bitmap.scaleToMax(maxSize: Int): Bitmap {
        val largestSide = maxOf(width, height)
        if (largestSide <= maxSize) return this
        val scale = maxSize.toFloat() / largestSide.toFloat()
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private data class CompactArtworkStep(
        val maxSize: Int,
        val quality: Int
    )

    private val COMPACT_ARTWORK_STEPS = listOf(
        CompactArtworkStep(maxSize = 192, quality = 64),
        CompactArtworkStep(maxSize = 160, quality = 56),
        CompactArtworkStep(maxSize = 128, quality = 50),
        CompactArtworkStep(maxSize = 96, quality = 44)
    )
}

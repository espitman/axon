package com.axon.bridge.data

import android.util.Base64
import com.axon.bridge.domain.RelayEnvelope
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object RelayCrypto {
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val TAG_BITS = 128
    private const val NONCE_BYTES = 12

    private val secureRandom = SecureRandom()

    fun encrypt(plainText: String, pairSecret: String, aad: ByteArray): EncryptedPayload {
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, secretKey(pairSecret), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            nonceBase64 = Base64.encodeToString(nonce, Base64.NO_WRAP),
            encryptedPayloadBase64 = Base64.encodeToString(cipherText, Base64.NO_WRAP)
        )
    }

    fun decrypt(envelope: RelayEnvelope, pairSecret: String, aad: ByteArray): String {
        val nonce = Base64.decode(envelope.nonceBase64.orEmpty(), Base64.NO_WRAP)
        val cipherText = Base64.decode(envelope.encryptedPayloadBase64.orEmpty(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.DECRYPT_MODE, secretKey(pairSecret), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    fun aadFor(envelope: RelayEnvelope): ByteArray {
        return listOf(
            envelope.schema,
            envelope.messageId,
            envelope.pairId,
            envelope.sourceDeviceId,
            envelope.targetRole.name,
            envelope.createdAt.toString(),
            envelope.messageType.name,
            envelope.payloadVersion.toString(),
            envelope.encryption
        ).joinToString("|").toByteArray(Charsets.UTF_8)
    }

    private fun secretKey(pairSecret: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(pairSecret.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, KEY_ALGORITHM)
    }
}

data class EncryptedPayload(
    val nonceBase64: String,
    val encryptedPayloadBase64: String
)

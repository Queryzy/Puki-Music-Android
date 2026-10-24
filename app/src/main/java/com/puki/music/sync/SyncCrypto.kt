package com.puki.music.sync

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SyncCrypto {

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    fun generateRandomSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun deriveKeys(syncSecret: String): Triple<ByteArray, ByteArray, String> {
        val secretBytes = hexToBytes(syncSecret.trim())
        val kEnc = computeHmacBytes(secretBytes, "puki_encryption_v1")
        val kAuth = computeHmacBytes(secretBytes, "puki_auth_v1")
        val authVerifier = kAuth.joinToString("") { "%02x".format(it) }
        return Triple(kEnc, kAuth, authVerifier)
    }

    fun encryptPayload(plaintext: String, kEnc: ByteArray): String {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(kEnc, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintextBytes)

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    fun decryptPayload(cipherBase64: String, kEnc: ByteArray): String {
        val combined = Base64.getDecoder().decode(cipherBase64.trim())
        if (combined.size < GCM_IV_LENGTH + 16) {
            throw IllegalArgumentException("Invalid encrypted payload size")
        }

        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)

        val ciphertextLength = combined.size - GCM_IV_LENGTH
        val ciphertext = ByteArray(ciphertextLength)
        System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertextLength)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(kEnc, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val decryptedBytes = cipher.doFinal(ciphertext)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    fun computeHmacHex(key: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val bytes = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun computeHmacBytes(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
    }

    fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(text: String): String {
        return sha256Hex(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun solvePoW(challenge: String, difficulty: Int): String {
        var nonce = 0L
        val prefix = "0".repeat(difficulty)
        while (true) {
            val hash = sha256Hex("$challenge:$nonce")
            if (hash.startsWith(prefix)) {
                return nonce.toString()
            }
            nonce++
        }
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 != 0) "0$hex" else hex
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

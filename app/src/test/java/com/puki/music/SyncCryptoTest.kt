package com.puki.music

import com.puki.music.sync.SyncCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCryptoTest {

    @Test
    fun testEncryptionDecryptionLifecycle() {
        val secret = SyncCrypto.generateRandomSecret()
        assertEquals(64, secret.length)

        val (kEnc, kAuth, authVerifier) = SyncCrypto.deriveKeys(secret)
        assertEquals(32, kEnc.size)
        assertEquals(32, kAuth.size)
        assertEquals(64, authVerifier.length)

        val plaintext = "{\"query\":\"Yoasobi - Racing Into The Night\",\"timestamp\":1729688400000}"
        val ciphertext = SyncCrypto.encryptPayload(plaintext, kEnc)
        assertTrue(ciphertext.isNotBlank())

        val decrypted = SyncCrypto.decryptPayload(ciphertext, kEnc)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testHmacVerification() {
        val key = SyncCrypto.hexToBytes("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        val message = "test-vault:1729688400000:{}"
        val hmac = SyncCrypto.computeHmacHex(key, message)
        assertEquals(64, hmac.length)
    }

    @Test
    fun testProofOfWorkSolver() {
        val challenge = "puki_test_challenge_123"
        val difficulty = 2
        val nonce = SyncCrypto.solvePoW(challenge, difficulty)
        val hash = SyncCrypto.sha256Hex("$challenge:$nonce")
        assertTrue(hash.startsWith("00"))
    }
}

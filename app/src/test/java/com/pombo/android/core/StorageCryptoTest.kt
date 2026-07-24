package com.pombo.android.core

import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing tests for the per-file binary seal. They exercise
 * encryptBinaryWithKey/decryptBinaryWithKey with a directly-constructed key so
 * they run on the JVM (no android.util.Base64, which PBKDF2's key cache would
 * otherwise touch). The PBKDF2 derivation itself is verified by interop parity
 * with the message crypto (PomboCrypto.encryptString) already in production.
 */
class StorageCryptoTest {

    private fun key(seed: Int) = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")

    @Test
    fun `seal is iv(12) plus ciphertext plus 16-byte tag`() {
        val k = key(0)
        val data = ByteArray(1000) { it.toByte() }
        val sealed = PomboCrypto.encryptBinaryWithKey(data, k)
        // 12-byte IV prefix + ciphertext (== plaintext length for GCM) + 16-byte tag.
        assertEquals(12 + data.size + 16, sealed.size)
    }

    @Test
    fun `round-trips a chunk`() {
        val k = key(7)
        val data = ByteArray(240 * 1024) { (it * 31).toByte() }
        val sealed = PomboCrypto.encryptBinaryWithKey(data, k)
        assertArrayEquals(data, PomboCrypto.decryptBinaryWithKey(sealed, k))
    }

    @Test
    fun `round-trips empty data`() {
        val k = key(1)
        val sealed = PomboCrypto.encryptBinaryWithKey(ByteArray(0), k)
        assertEquals(12 + 16, sealed.size)
        assertArrayEquals(ByteArray(0), PomboCrypto.decryptBinaryWithKey(sealed, k))
    }

    @Test
    fun `each seal uses a fresh random iv but both open`() {
        val k = key(2)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val a = PomboCrypto.encryptBinaryWithKey(data, k)
        val b = PomboCrypto.encryptBinaryWithKey(data, k)
        assertFalse("different IV -> different ciphertext", a.contentEquals(b))
        assertArrayEquals(data, PomboCrypto.decryptBinaryWithKey(a, k))
        assertArrayEquals(data, PomboCrypto.decryptBinaryWithKey(b, k))
    }

    @Test
    fun `a wrong key fails the gcm tag`() {
        val sealed = PomboCrypto.encryptBinaryWithKey(byteArrayOf(9, 9, 9), key(3))
        assertThrows(AEADBadTagException::class.java) {
            PomboCrypto.decryptBinaryWithKey(sealed, key(4))
        }
    }

    @Test
    fun `decrypt rejects a payload too short to hold iv and tag`() {
        assertThrows(IllegalArgumentException::class.java) {
            PomboCrypto.decryptBinaryWithKey(ByteArray(20), key(0))
        }
    }

    @Test
    fun `a corrupted ciphertext fails the gcm tag`() {
        val k = key(5)
        val sealed = PomboCrypto.encryptBinaryWithKey(byteArrayOf(1, 2, 3, 4), k)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertTrue(sealed.isNotEmpty())
        assertThrows(AEADBadTagException::class.java) {
            PomboCrypto.decryptBinaryWithKey(sealed, k)
        }
    }
}

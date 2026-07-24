package com.pombo.android.core

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password channel encryption — byte-compatible with the Pombo web crypto.js:
 *   key    = PBKDF2-HMAC-SHA256(password, salt16, 310000 iters) -> AES-256
 *   wire   = base64( salt[16] || iv[12] || ciphertext+tag GCM[16] )
 * The value published in a password channel is just this base64 string (a
 * a JSON string on the wire, instead of the plaintext object).
 */
object PomboCrypto {

    const val PBKDF2_ITERATIONS = 310_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    // LRU cache of derived keys (PBKDF2 310k is costly) — like the web (max 10).
    private val keyCache = object : LinkedHashMap<String, SecretKeySpec>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SecretKeySpec>) = size > 10
    }

    // The lock guards only the cache: the 310k-iteration derivation itself runs
    // outside it so concurrent decrypts of a history page can use every core.
    // (A method-level @Synchronized serialized them all — page decrypts are the
    // one place this runs in parallel, and salts are per-message, so two
    // threads deriving the same key at once is practically impossible anyway.)
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val cacheKey = password + "|" + Base64.encodeToString(salt, Base64.NO_WRAP)
        synchronized(keyCache) { keyCache[cacheKey] }?.let { return it }
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val key = SecretKeySpec(bytes, "AES")
        synchronized(keyCache) { keyCache[cacheKey] = key }
        return key
    }

    /** JSON/texto → base64(salt||iv||ct) — igual a cryptoManager.encrypt. */
    fun encryptString(plaintext: String, password: String): String {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = salt + iv + ct
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /** base64(salt||iv||ct) -> text; throws on wrong password (GCM tag fails). */
    fun decryptString(encoded: String, password: String): String {
        val combined = Base64.decode(encoded, Base64.DEFAULT)
        require(combined.size > SALT_LEN + IV_LEN) { "encrypted payload too short" }
        val salt = combined.copyOfRange(0, SALT_LEN)
        val iv = combined.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val ct = combined.copyOfRange(SALT_LEN + IV_LEN, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /** 8 random bytes -> 16 hex (suffix of a new channel stream ID). */
    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes).also { random.nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    // ===== Per-file binary sealing (storage-node file transport) =====
    //
    // Byte-compatible with web crypto.js encryptBinaryWithKey/decryptBinaryWithKey:
    //   wire = iv[12] || AES-256-GCM ciphertext+tag   (NO salt embedded — the
    //   salt travels ONCE per file in the announce's `encSalt`).
    //
    // This is native (not in the bridge, unlike the mesh per-piece crypto)
    // precisely because the storage transport derives ONE key per FILE: a single
    // 310k-iteration PBKDF2 (~1s in Bouncy Castle, cached on password+salt so
    // every chunk after the first hits the cache), then per-chunk AES-GCM which
    // is hardware-fast. Shuttling 240 KB chunks through the WebView to seal them
    // would cost far more than the derivation it would save.

    /** Random 16-byte salt for a new file's per-file key (goes in `encSalt`). */
    fun generateSalt(): ByteArray = ByteArray(SALT_LEN).also { random.nextBytes(it) }

    /**
     * Derive the AES-256 key for a file from its password and per-file salt.
     * Cached on (password, salt), so calling it once per chunk still runs PBKDF2
     * only once per file. Reuse the returned key across the file's chunks.
     */
    fun deriveKeyWithSalt(password: String, salt: ByteArray): SecretKeySpec = deriveKey(password, salt)

    /** Seal a chunk with a pre-derived key -> `iv[12] || ciphertext+tag`. Random IV per call. */
    fun encryptBinaryWithKey(data: ByteArray, key: SecretKeySpec): ByteArray {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(data)
    }

    /** Open a chunk sealed by [encryptBinaryWithKey]; throws on a wrong key (GCM tag fails). */
    fun decryptBinaryWithKey(sealed: ByteArray, key: SecretKeySpec): ByteArray {
        // Minimum valid seal is iv(12) + GCM tag(16) = 28 bytes (empty plaintext).
        require(sealed.size >= IV_LEN + TAG_BITS / 8) { "sealed payload too short" }
        val iv = sealed.copyOfRange(0, IV_LEN)
        val ct = sealed.copyOfRange(IV_LEN, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}

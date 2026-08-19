package com.pombo.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with the web's epochKeyCrypto.js, locked by fixed vectors generated
 * with the web's own module (2026-08-17, N-A Android port). If any constant in
 * [EpochKeyCrypto] changes — HKDF salt, tag domain, envelope kind — these MUST
 * be regenerated with the web, never edited by hand.
 */
class EpochKeyCryptoTest {

    // ---- Web-generated vectors ----
    private val requestPriv = "0x1111111111111111111111111111111111111111111111111111111111111111"
    private val requestPub = "0x034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa"
    private val epochKey = "0x2222222222222222222222222222222222222222222222222222222222222222"
    private val webKeyHash = "0x9f72ea0cf49536e3c66c787f705186df9a4378083753ae9536d65b3ad7fcddc4"
    private val keyId = "3.abc123"
    private val webTag = "0xa2264056edba3e166a90d55fa63d3f051ad3abc9fd5d1517f1743395329ce52b"

    /** A KEY_WRAP produced by the web for [requestPub] — Android must open it. */
    private val webWrapped = JSONObject()
        .put("epk", "0x03d6a30adf7fc9d849d384a44cb1b49937464dc8476f0f70c9ec63e0476d996d37")
        .put("iv", "W81TBdaOGd2g51Hn")
        .put("ct", "vrZZL1W2Qq/qUgvYMZ9TwYLcRdveu6DXoVZMsGVeBgCaKQDvwMeDSgkgJNpUiKGX")

    /** A -1 message envelope encrypted by the web with [epochKey]. */
    private val webEnvelope = JSONObject()
        .put("e", "epoch-aes-gcm")
        .put("k", keyId)
        .put("ct", "pR4XrQI1GcQsL1FP29ejbSCL/MPTIqDe6eCQDvRUs6gu45CqzLJrfzgOhdh/LMItTOf1BLX0LddnoR5SjVfWYz2GxCRPlZ0C9ne3d5PdMUG0fjF+umzPfnPokffw8A==")
        .put("iv", "0BXQXxNNkmOtHpyQ")

    @Test
    fun `keyHash matches the web`() {
        assertEquals(webKeyHash, EpochKeyCrypto.computeKeyHash(epochKey))
    }

    @Test
    fun `wrap tag matches the web and is pubkey case-insensitive`() {
        assertEquals(webTag, EpochKeyCrypto.computeWrapTag(requestPub, keyId))
        assertEquals(webTag, EpochKeyCrypto.computeWrapTag(requestPub.uppercase().replaceFirst("0X", "0x"), keyId))
        assertNotEquals(webTag, EpochKeyCrypto.computeWrapTag(requestPub, "4.def456"))
    }

    @Test
    fun `opens a web-produced KEY_WRAP to the exact epoch key`() {
        assertEquals(epochKey, EpochKeyCrypto.unwrapEpochKey(webWrapped, requestPriv))
    }

    @Test
    fun `decrypts a web-produced message envelope`() {
        val plain = EpochKeyCrypto.decryptWithEpochKey(webEnvelope, epochKey)
        assertEquals("text", plain.optString("type"))
        assertEquals("deadbeef", plain.optString("id"))
        assertEquals("olá época", plain.optString("text"))
        assertEquals(1755400000000L, plain.optLong("timestamp"))
    }

    @Test
    fun `android wrap round-trips and a wrong request key fails`() {
        val (priv, pub) = EpochKeyCrypto.generateRequestKeypair()
        val key = EpochKeyCrypto.generateEpochKey()
        val wrapped = EpochKeyCrypto.wrapEpochKey(key, pub)
        assertEquals(key, EpochKeyCrypto.unwrapEpochKey(wrapped, priv))

        val (otherPriv, _) = EpochKeyCrypto.generateRequestKeypair()
        try {
            EpochKeyCrypto.unwrapEpochKey(wrapped, otherPriv)
            throw AssertionError("unwrap with the wrong key must fail")
        } catch (e: Exception) { /* expected: GCM tag failure */ }
    }

    @Test
    fun `android message encryption round-trips under its own envelope`() {
        val key = EpochKeyCrypto.generateEpochKey()
        val payload = JSONObject().put("type", "text").put("id", "abc").put("text", "olá")
        val env = EpochKeyCrypto.encryptWithEpochKey(payload, key, "1.aaaa")
        assertTrue(EpochKeyCrypto.isEpochEnvelope(env))
        assertEquals("1.aaaa", env.optString("k"))
        val plain = EpochKeyCrypto.decryptWithEpochKey(env, key)
        assertEquals("olá", plain.optString("text"))
        assertFalse(env.toString().contains("olá"))
    }

    @Test
    fun `keyHash detects a wrap of the wrong key (anti-poisoning)`() {
        val (priv, pub) = EpochKeyCrypto.generateRequestKeypair()
        val malicious = EpochKeyCrypto.generateEpochKey()
        val unwrapped = EpochKeyCrypto.unwrapEpochKey(EpochKeyCrypto.wrapEpochKey(malicious, pub), priv)
        assertNotEquals(webKeyHash, EpochKeyCrypto.computeKeyHash(unwrapped))
    }

    @Test
    fun `envelope detection rejects non-envelopes`() {
        assertFalse(EpochKeyCrypto.isEpochEnvelope(JSONObject().put("type", "text")))
        assertFalse(EpochKeyCrypto.isEpochEnvelope(JSONObject().put("e", "aes-256-gcm").put("ct", "x").put("iv", "y")))
    }

    // ---- Binary envelope (0x04, MEDIA_DATA frames and storage chunks) ----

    /** Sealed by the web's sealBinaryWithEpochKey with [epochKey] and [keyId]. */
    private val webSealedBinary = java.util.Base64.getDecoder().decode(
        "BAgzLmFiYzEyM5NAbRHzYjvd9oywAgICL5NA6blY8sNRd4pMk5IHnvn+LyOi5vvoNAxMiJol3YvezYNNd0hejQ==")

    @Test
    fun `opens a web-sealed binary envelope`() {
        assertTrue(EpochKeyCrypto.isBinaryEpochEnvelope(webSealedBinary))
        val parsed = EpochKeyCrypto.parseBinaryEpochEnvelope(webSealedBinary)!!
        assertEquals(keyId, parsed.kid)
        val plain = EpochKeyCrypto.decryptBinaryWithEpochKey(parsed, epochKey)
        assertEquals("olá peça binária época", String(plain, Charsets.UTF_8))
    }

    @Test
    fun `android binary seal round-trips and a wrong key fails`() {
        val key = EpochKeyCrypto.generateEpochKey()
        val frame = ByteArray(1024) { (it % 251).toByte() }
        val sealed = EpochKeyCrypto.sealBinaryWithEpochKey(frame, key, "1.aaaa")
        assertEquals(0x04, sealed[0].toInt())
        val parsed = EpochKeyCrypto.parseBinaryEpochEnvelope(sealed)!!
        assertEquals("1.aaaa", parsed.kid)
        assertTrue(frame.contentEquals(EpochKeyCrypto.decryptBinaryWithEpochKey(parsed, key)))

        val other = EpochKeyCrypto.generateEpochKey()
        try {
            EpochKeyCrypto.decryptBinaryWithEpochKey(parsed, other)
            throw AssertionError("decrypt with the wrong key must fail")
        } catch (e: Exception) { /* expected: GCM tag failure */ }
    }

    @Test
    fun `binary envelope detection leaves the other frame types untouched`() {
        // 0x01/0x03 media frames and 0x02 sealed-sender envelopes must never
        // be mistaken for an epoch envelope.
        for (lead in byteArrayOf(0x01, 0x02, 0x03)) {
            assertFalse(EpochKeyCrypto.isBinaryEpochEnvelope(byteArrayOf(lead, 0, 0)))
        }
    }

    @Test
    fun `malformed binary envelopes parse to null`() {
        assertEquals(null, EpochKeyCrypto.parseBinaryEpochEnvelope(byteArrayOf(0x04)))
        assertEquals(null, EpochKeyCrypto.parseBinaryEpochEnvelope(byteArrayOf(0x04, 0)))
        // kidLen pointing past the end of a truncated frame
        assertEquals(null, EpochKeyCrypto.parseBinaryEpochEnvelope(byteArrayOf(0x04, 200.toByte(), 1, 2, 3)))
    }
}

package com.pombo.android.core

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.json.JSONObject

/**
 * Epoch-key crypto for the keys stream (-4) — byte-exact port of the web's
 * epochKeyCrypto.js (N-A, UNIFIED_IMPLEMENTATION_PLAN.md §5.4, D12–D14).
 *
 * Native channel content on -1/-2 is encrypted with a channel-wide EPOCH KEY,
 * versioned by `kid`. The key travels wrapped via ECIES to a fresh per-request
 * keypair (D12) — the same secp256k1 ECDH + HKDF + AES-256-GCM construction as
 * [SealedSenderCrypto], with its own HKDF salt for domain separation.
 *
 *   wrap key = HKDF-SHA256( x-coord(ECDH(a, B)),
 *                           salt="pombo-keywrap-v1", info="aes-256-gcm" )
 *   tag      = sha256(utf8("POMBO_WRAP_TAG_V1|" + lowercase(pubkey) + "|" + keyId))
 *   keyHash  = sha256(epoch key bytes)
 *
 * Parity is locked by [EpochKeyCryptoTest]'s fixed vectors, generated with the
 * web's own module — do not change any constant here without regenerating them.
 */
object EpochKeyCrypto {

    private val CURVE = CustomNamedCurves.getByName("secp256k1")
    private val N: BigInteger = CURVE.n
    private val random = SecureRandom()

    private const val KEYWRAP_HKDF_SALT = "pombo-keywrap-v1"
    private const val WRAP_TAG_DOMAIN = "POMBO_WRAP_TAG_V1"
    const val ENVELOPE_KIND = "epoch-aes-gcm"

    /** Fresh 256-bit epoch key, 0x-prefixed hex. */
    fun generateEpochKey(): String = "0x" + ByteArray(32).also { random.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    /** sha256 of the raw key bytes — the announce's trust anchor. 0x-prefixed hex. */
    fun computeKeyHash(epochKeyHex: String): String =
        "0x" + sha256(SealedSenderCrypto.hexToBytes(epochKeyHex)).toHexString()

    /**
     * Fresh secp256k1 keypair for one KEY_REQUEST (D12): (privHex, compressed
     * pubHex). Memory-only at the call sites — never persisted, never reused.
     */
    fun generateRequestKeypair(): Pair<String, String> {
        while (true) {
            val bytes = ByteArray(32).also { random.nextBytes(it) }
            val k = BigInteger(1, bytes)
            if (k.signum() <= 0 || k >= N) continue
            val pub = CURVE.g.multiply(k).normalize().getEncoded(true)
            return "0x" + bytes.toHexString() to "0x" + pub.toHexString()
        }
    }

    /**
     * Wrap address tag over a canonical string — computed over the throwaway
     * request pubkey, so it carries no membership information (D12).
     */
    fun computeWrapTag(requestPubkeyHex: String, keyId: String): String {
        val canonical = "$WRAP_TAG_DOMAIN|${requestPubkeyHex.lowercase()}|$keyId"
        return "0x" + sha256(canonical.toByteArray(Charsets.UTF_8)).toHexString()
    }

    /**
     * Seal an epoch key to a request pubkey (ECIES). Returns the wire fields:
     * { epk: hex, iv: base64, ct: base64 }.
     */
    fun wrapEpochKey(epochKeyHex: String, requestPubkeyHex: String): JSONObject {
        val (ephPriv, ephPub) = generateRequestKeypair()
        val shared = SealedSenderCrypto.ecdhX(ephPriv, requestPubkeyHex)
            ?: throw IllegalStateException("ECDH failed for wrap")
        val aes = SealedSenderCrypto.hkdfSha256(
            shared, KEYWRAP_HKDF_SALT.toByteArray(), "aes-256-gcm".toByteArray())

        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(SealedSenderCrypto.hexToBytes(epochKeyHex))

        return JSONObject()
            .put("epk", ephPub)
            .put("iv", java.util.Base64.getEncoder().encodeToString(iv))
            .put("ct", java.util.Base64.getEncoder().encodeToString(ct))
    }

    /**
     * Open a wrap addressed to our request keypair. Returns the epoch key hex.
     * @throws on any mismatch — the caller treats a failed unwrap as a drop.
     */
    fun unwrapEpochKey(wrapped: JSONObject, requestPrivateKeyHex: String): String {
        val epk = wrapped.getString("epk")
        val shared = SealedSenderCrypto.ecdhX(requestPrivateKeyHex, epk)
            ?: throw IllegalStateException("ECDH failed for unwrap")
        val aes = SealedSenderCrypto.hkdfSha256(
            shared, KEYWRAP_HKDF_SALT.toByteArray(), "aes-256-gcm".toByteArray())

        val iv = java.util.Base64.getDecoder().decode(wrapped.getString("iv"))
        val ct = java.util.Base64.getDecoder().decode(wrapped.getString("ct"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(ct)
        require(plain.size == 32) { "Unwrapped epoch key has wrong length: ${plain.size}" }
        return "0x" + plain.toHexString()
    }

    /**
     * Encrypt a channel payload with an epoch key. Returns the full wire
     * envelope: { e: "epoch-aes-gcm", k: kid, ct: base64, iv: base64 }.
     */
    fun encryptWithEpochKey(payload: JSONObject, epochKeyHex: String, kid: String): JSONObject {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(SealedSenderCrypto.hexToBytes(epochKeyHex), "AES"),
            GCMParameterSpec(128, iv)
        )
        val ct = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("e", ENVELOPE_KIND)
            .put("k", kid)
            .put("ct", java.util.Base64.getEncoder().encodeToString(ct))
            .put("iv", java.util.Base64.getEncoder().encodeToString(iv))
    }

    /** Decrypt an epoch envelope. @throws on a wrong key (GCM tag fails). */
    fun decryptWithEpochKey(envelope: JSONObject, epochKeyHex: String): JSONObject {
        val iv = java.util.Base64.getDecoder().decode(envelope.getString("iv"))
        val ct = java.util.Base64.getDecoder().decode(envelope.getString("ct"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(SealedSenderCrypto.hexToBytes(epochKeyHex), "AES"),
            GCMParameterSpec(128, iv)
        )
        return JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8))
    }

    /** true when a parsed payload is an epoch-encrypted envelope. */
    fun isEpochEnvelope(data: JSONObject): Boolean =
        data.optString("e") == ENVELOPE_KIND &&
            data.optString("k").isNotEmpty() &&
            data.optString("ct").isNotEmpty() &&
            data.optString("iv").isNotEmpty()

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}

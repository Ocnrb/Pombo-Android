package com.pombo.android.core

import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Invite links — mirror of channels.js generateInviteLink / parseInviteLink.
 *
 * The token is `base64url(iv).base64url(ciphertext).base64url(key)`: a random
 * AES-GCM key encrypts the payload and then travels inside the token itself,
 * so anyone holding the link can decrypt it — it keeps the channel id and
 * password out of plaintext URLs and referrer logs. Keep the shape
 * byte-identical or links stop working across web and Android.
 *
 * Payload uses one-letter keys to keep the QR code small (web comment).
 */
object InviteToken {

    private const val IV_LEN = 12
    private const val KEY_LEN = 32
    private const val TAG_BITS = 128

    /** Web: `${origin}${pathname}#/invite/${token}` on the deployed app. */
    const val BASE_URL = "https://app.pombo.cc/"

    data class Invite(
        val streamId: String,
        val name: String?,
        val type: String?,
        val password: String?
    )

    fun generate(streamId: String, name: String?, type: String?, password: String?): String {
        val payload = JSONObject()
            .put("s", streamId)
            .put("n", name ?: JSONObject.NULL)
            .put("t", type ?: JSONObject.NULL)
        if (!password.isNullOrEmpty()) payload.put("p", password)

        val rnd = SecureRandom()
        val keyBytes = ByteArray(KEY_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
        }
        // WebCrypto appends the GCM tag to the ciphertext, which is exactly what
        // javax.crypto does too — so the two halves stay interchangeable.
        val ciphertext = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))

        return listOf(iv, ciphertext, keyBytes).joinToString(".") { b64UrlEncode(it) }
    }

    fun link(streamId: String, name: String?, type: String?, password: String?): String =
        "$BASE_URL#/invite/${generate(streamId, name, type, password)}"

    /** Returns null on any malformed or undecryptable token, like the web. */
    fun parse(tokenRaw: String): Invite? = try {
        // Accept a full link or a bare token — users paste both.
        val token = tokenRaw.trim().substringAfterLast("#/invite/").substringBefore('?')
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid invite token format" }

        val iv = b64UrlDecode(parts[0])
        val ciphertext = b64UrlDecode(parts[1])
        val keyBytes = b64UrlDecode(parts[2])
        require(iv.size == IV_LEN && keyBytes.size == KEY_LEN) { "Invalid invite token length" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
        }
        val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        val streamId = json.optStringOrNull("s") ?: return null
        Invite(
            streamId = streamId,
            name = json.optStringOrNull("n"),
            type = json.optStringOrNull("t"),
            password = json.optStringOrNull("p")
        )
    } catch (e: Exception) {
        null
    }

    /** Web label map in inviteHandler.getChannelTypeLabel. */
    fun typeLabel(type: String?): String = when (type) {
        "public" -> "Public Channel"
        "password" -> "Password Protected"
        "native" -> "Native Encryption"
        else -> type?.ifEmpty { null } ?: "Unknown"
    }

    // btoa + `+/` → `-_` + strip padding, matching bytesToBase64Url.
    private fun b64UrlEncode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )

    private fun b64UrlDecode(value: String): ByteArray =
        android.util.Base64.decode(value, android.util.Base64.URL_SAFE)

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").ifEmpty { null }
    }
}

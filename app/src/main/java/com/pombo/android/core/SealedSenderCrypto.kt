package com.pombo.android.core

import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import org.json.JSONObject

/**
 * Native open for sealed-sender v2 DM envelopes — the dead-app push path only.
 *
 * Everywhere else the bridge (WebView + ethers) does this; FCM can wake a dead
 * process where no WebView exists and starting one costs seconds of the
 * execution window. This is a byte-exact port of web dmCrypto.open():
 *
 *   AES    = HKDF-SHA256( x-coord(ECDH(myStaticPriv, epk)),
 *                         salt="pombo-dm-sealed-v2", info="aes-256-gcm" )
 *   digest = keccak256(utf8("POMBO_DM_BIND_V2|" + lowercase(myAddress) + "|" + epk))
 *   sender = ecrecover(digest, inner.p)
 *
 * Parity is locked by [SealedSenderCryptoTest]'s fixed vectors, generated with
 * the web's own ethers — do not change any constant here without regenerating
 * them. Failures return null, never throw: a row that does not open is not
 * ours (or tampered), which on this path just means a generic notification.
 */
object SealedSenderCrypto {

    private val CURVE = CustomNamedCurves.getByName("secp256k1")
    private val N: BigInteger = CURVE.n

    /** @return (sender lowercase, inner message without `p`), or null. */
    fun open(envelope: JSONObject, myPrivateKeyHex: String, myAddress: String): Pair<String, JSONObject>? {
        return try {
            if (envelope.optInt("v") != 2 || envelope.optString("e") != "aes-256-gcm") return null
            val epk = envelope.optString("epk").ifEmpty { return null }

            val shared = ecdhX(myPrivateKeyHex, epk) ?: return null
            val aesKey = hkdfSha256(shared, "pombo-dm-sealed-v2".toByteArray(), "aes-256-gcm".toByteArray())

            // java.util, not android.util: identical on device (minSdk ≥ 26)
            // and real in JVM unit tests, where the android.util stub throws.
            val iv = java.util.Base64.getDecoder().decode(envelope.optString("iv"))
            val ct = java.util.Base64.getDecoder().decode(envelope.optString("ct"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
            val inner = JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8))

            val proof = inner.optString("p").ifEmpty { return null }
            val digest = keccak256(
                "POMBO_DM_BIND_V2|${myAddress.lowercase()}|$epk".toByteArray(Charsets.UTF_8)
            )
            val sender = ecrecover(digest, hexToBytes(proof)) ?: return null
            inner.remove("p")
            sender to inner
        } catch (e: Exception) {
            null
        }
    }

    /** x-coordinate (32 bytes) of privKey × pubKey — ethers computeSharedSecret bytes 1..33. */
    internal fun ecdhX(privHex: String, pubCompressedHex: String): ByteArray? {
        val priv = BigInteger(1, hexToBytes(privHex))
        if (priv.signum() <= 0 || priv >= N) return null
        val point = CURVE.curve.decodePoint(hexToBytes(pubCompressedHex)).multiply(priv).normalize()
        if (point.isInfinity) return null
        return point.affineXCoord.encoded   // fixed 32-byte big-endian
    }

    /** RFC 5869, single expand round (L=32 ≤ hash length), matching WebCrypto HKDF. */
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01)
        return mac.doFinal()
    }

    fun keccak256(data: ByteArray): ByteArray {
        val d = KeccakDigest(256)
        d.update(data, 0, data.size)
        val out = ByteArray(32)
        d.doFinal(out, 0)
        return out
    }

    /**
     * Standard public-key recovery (SEC 1 §4.1.6) from a 65-byte r‖s‖v
     * signature over `msgHash` — what ethers.recoverAddress does. Returns the
     * lowercase 0x address, or null when the signature does not recover.
     */
    private fun ecrecover(msgHash: ByteArray, sig65: ByteArray): String? {
        if (sig65.size != 65) return null
        val r = BigInteger(1, sig65.copyOfRange(0, 32))
        val s = BigInteger(1, sig65.copyOfRange(32, 64))
        val v = sig65[64].toInt() and 0xff
        val recId = if (v >= 27) v - 27 else v
        if (recId !in 0..1 || r.signum() <= 0 || s.signum() <= 0 || r >= N || s >= N) return null

        // R = point with x = r (recId's parity selects y); e = hash as integer
        val x = r   // recId 2/3 (x overflow) never occurs for real signatures
        val prefix = if (recId and 1 == 1) 0x03 else 0x02
        val xBytes = x.toByteArray().let {
            if (it.size == 33 && it[0].toInt() == 0) it.copyOfRange(1, 33)
            else ByteArray(32 - it.size) + it
        }
        val rPoint: ECPoint = try {
            CURVE.curve.decodePoint(byteArrayOf(prefix.toByte()) + xBytes)
        } catch (e: Exception) { return null }

        val e = BigInteger(1, msgHash)
        // Q = r^-1 (s·R − e·G)
        val rInv = r.modInverse(N)
        val q = org.bouncycastle.math.ec.ECAlgorithms.sumOfTwoMultiplies(
            CURVE.g, rInv.multiply(N.subtract(e.mod(N))).mod(N),
            rPoint, rInv.multiply(s).mod(N)
        ).normalize()
        if (q.isInfinity) return null

        val uncompressed = q.getEncoded(false)   // 0x04 ‖ X ‖ Y
        val addr = keccak256(uncompressed.copyOfRange(1, 65)).copyOfRange(12, 32)
        return "0x" + addr.joinToString("") { "%02x".format(it) }
    }

    internal fun hexToBytes(hex: String): ByteArray {
        val h = hex.removePrefix("0x")
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

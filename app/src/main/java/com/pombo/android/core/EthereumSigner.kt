package com.pombo.android.core

import java.math.BigInteger
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECAlgorithms

/**
 * Native Ethereum signing — the primitive that lets the identity key leave the
 * WebView (docs/private_key_in_webview.md, Phase A).
 *
 * Byte-parity contract with ethers `SigningKey.sign(digest).serialized`:
 * RFC 6979 deterministic k (HMAC-SHA256), low-s normalized (EIP-2), 65-byte
 * r‖s‖v output with v = 27 + recovery id. [hashMessage] is ethers
 * `hashMessage` over bytes — the same EIP-191 prefix the Streamr SDK's
 * ECDSA_SECP256K1_EVM applies, so [signMessage] covers both. Parity is locked
 * by [EthereumSignerTest]'s vectors, generated with the web's own ethers — do
 * not change behavior here without regenerating them.
 *
 * [signDigest] must never be reachable from the bridge with a caller-supplied
 * digest: an arbitrary-digest oracle signs transactions without ChainGuard
 * (the "no opaque digests" rule in the plan). Bridge-facing endpoints build
 * their preimages in Kotlin and come through [signMessage] or the transaction
 * path.
 */
object EthereumSigner {

    private val CURVE = CustomNamedCurves.getByName("secp256k1")
    private val DOMAIN = ECDomainParameters(CURVE.curve, CURVE.g, CURVE.n, CURVE.h)
    private val N: BigInteger = CURVE.n
    private val HALF_N: BigInteger = N.shiftRight(1)

    private val MESSAGE_MAGIC = "Ethereum Signed Message:\n".toByteArray(Charsets.US_ASCII)

    /** 65-byte r‖s‖v signature over a 32-byte digest. Kotlin-internal use only — see class doc. */
    fun signDigest(digest: ByteArray, privateKeyHex: String): ByteArray {
        require(digest.size == 32) { "digest must be 32 bytes" }
        val priv = BigInteger(1, SealedSenderCrypto.hexToBytes(privateKeyHex))
        require(priv.signum() > 0 && priv < N) { "private key out of range" }

        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(priv, DOMAIN))
        val rs = signer.generateSignature(digest)
        val r = rs[0]
        // Low-s: ethers (noble-curves) always emits the canonical half.
        val s = if (rs[1] > HALF_N) N.subtract(rs[1]) else rs[1]

        val myPub = CURVE.g.multiply(priv).normalize().getEncoded(true)
        val recId = (0..1).firstOrNull { recoversTo(digest, r, s, it, myPub) }
            ?: throw IllegalStateException("no recovery id reproduces the signing key")

        return fixed32(r) + fixed32(s) + byteArrayOf((27 + recId).toByte())
    }

    /** keccak256(EIP-191 magic + decimal byte length + payload) — ethers hashMessage over bytes. */
    fun hashMessage(payload: ByteArray): ByteArray {
        val len = payload.size.toString().toByteArray(Charsets.US_ASCII)
        return SealedSenderCrypto.keccak256(MESSAGE_MAGIC + len + payload)
    }

    /** Personal-message signature — the safe bridge-facing form (prefixed preimages cannot be transactions). */
    fun signMessage(payload: ByteArray, privateKeyHex: String): ByteArray =
        signDigest(hashMessage(payload), privateKeyHex)

    /** Compressed public key (33 bytes) as 0x hex — what ethers SigningKey.compressedPublicKey returns. */
    fun compressedPublicKey(privateKeyHex: String): String {
        val priv = BigInteger(1, SealedSenderCrypto.hexToBytes(privateKeyHex))
        require(priv.signum() > 0 && priv < N) { "private key out of range" }
        return toHex(CURVE.g.multiply(priv).normalize().getEncoded(true))
    }

    /** Lowercase 0x account address for the key. */
    fun address(privateKeyHex: String): String {
        val priv = BigInteger(1, SealedSenderCrypto.hexToBytes(privateKeyHex))
        require(priv.signum() > 0 && priv < N) { "private key out of range" }
        val uncompressed = CURVE.g.multiply(priv).normalize().getEncoded(false)
        val hash = SealedSenderCrypto.keccak256(uncompressed.copyOfRange(1, 65))
        return toHex(hash.copyOfRange(12, 32))
    }

    fun toHex(bytes: ByteArray): String =
        "0x" + bytes.joinToString("") { "%02x".format(it) }

    /** EIP-55 checksummed form of a 0x address — what ethers prints as Wallet.address. */
    fun checksumAddress(address: String): String {
        val lower = address.removePrefix("0x").lowercase()
        val hash = SealedSenderCrypto.keccak256(lower.toByteArray(Charsets.US_ASCII))
        val out = StringBuilder("0x")
        lower.forEachIndexed { i, c ->
            val nibble = (hash[i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0x0f
            out.append(if (c in 'a'..'f' && nibble >= 8) c.uppercaseChar() else c)
        }
        return out.toString()
    }

    /** SEC 1 §4.1.6 recovery: does (r, s, recId) over `digest` recover `expectedCompressed`? */
    private fun recoversTo(
        digest: ByteArray, r: BigInteger, s: BigInteger, recId: Int, expectedCompressed: ByteArray
    ): Boolean {
        val prefix = if (recId and 1 == 1) 0x03 else 0x02
        val rPoint = try {
            CURVE.curve.decodePoint(byteArrayOf(prefix.toByte()) + fixed32(r))
        } catch (e: Exception) { return false }
        val e = BigInteger(1, digest)
        val rInv = r.modInverse(N)
        val q = ECAlgorithms.sumOfTwoMultiplies(
            CURVE.g, rInv.multiply(N.subtract(e.mod(N))).mod(N),
            rPoint, rInv.multiply(s).mod(N)
        ).normalize()
        if (q.isInfinity) return false
        return q.getEncoded(true).contentEquals(expectedCompressed)
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}

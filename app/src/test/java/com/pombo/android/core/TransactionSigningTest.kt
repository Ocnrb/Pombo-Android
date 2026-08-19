package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity lock for the transaction path of the signing oracle. The vector is
 * an EIP-1559 Polygon transaction built with the web's own ethers:
 * `Transaction.from(...).unsignedSerialized`, `unsignedHash`, and
 * `SigningKey.sign(unsignedHash).serialized`.
 */
class TransactionSigningTest {

    private val pk = "0x8f2a559490d8e9bb4e0e7b53e1c6e4c2b1a0d9c8b7a6958473625140fedcba98"
    private val unsignedSerialized =
        "0x02ef8189078506fc23ac00850df847580083033450941563915e194d8cfba1943570603f7606a31155088084a9059cbbc0"
    private val unsignedHash = "0x287ad78434d09c3f3776fbcd8263685016c0eb96bd06d555401581969c417d42"
    private val signature =
        "0x4a371b21798f15f27b4ca3a8a9c98396f2dbe069998bde51841d266c620749bc" +
            "0134fe10bd59527fce2b35d44ef14df1d96548cb0e97467118b7638db9e672851b"

    @Test
    fun `unsigned EIP-1559 envelope validates, hashes and signs like ethers`() {
        val bytes = SealedSenderCrypto.hexToBytes(unsignedSerialized)
        assertTrue(Rlp.isTransactionEnvelope(bytes))
        assertEquals(unsignedHash, EthereumSigner.toHex(SealedSenderCrypto.keccak256(bytes)))
        assertEquals(
            signature,
            EthereumSigner.toHex(
                EthereumSigner.signDigest(SealedSenderCrypto.keccak256(bytes), pk)
            )
        )
    }

    @Test
    fun `non-transaction payloads are rejected by the shape check`() {
        // A Streamr message payload is not an RLP list.
        assertFalse(Rlp.isTransactionEnvelope("olá pombo".toByteArray(Charsets.UTF_8)))
        // A bare 32-byte digest is not an envelope either.
        assertFalse(Rlp.isTransactionEnvelope(ByteArray(32) { 0x11 }))
        // Truncating the vector must fail the whole-buffer requirement.
        val bytes = SealedSenderCrypto.hexToBytes(unsignedSerialized)
        assertFalse(Rlp.isTransactionEnvelope(bytes.copyOfRange(0, bytes.size - 2)))
        // Trailing garbage after a valid list must fail too.
        assertFalse(Rlp.isTransactionEnvelope(bytes + 0x00))
    }
}

package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Parity lock against web publisherProof.js: the vector proof was generated
 * with the web's own ethers for a fixed ephemeral key and account key.
 */
class ChannelIdentitiesTest {

    private val accountPk = "0x8f2a559490d8e9bb4e0e7b53e1c6e4c2b1a0d9c8b7a6958473625140fedcba98"
    private val accountAddress = "0x54B2da155Df6C56BBAC68A232a0BF28A735164FC"

    @Test
    fun `proof matches the web vector for a fixed ephemeral key`() {
        val ephemeralPk = "0x3333333333333333333333333333333333333333333333333333333333333333"
        val publisherId = EthereumSigner.checksumAddress(EthereumSigner.address(ephemeralPk))
        assertEquals("0x5CbDd86a2FA8Dc4bDdd8a8f69dBa48572EeC07FB", publisherId)

        val digest = SealedSenderCrypto.keccak256(
            "POMBO_PUB_V1|${publisherId.lowercase()}".toByteArray(Charsets.UTF_8)
        )
        assertEquals(
            "0x25810a51b8d4badffd4a30c66658a024e190a273543af765d5bd75d20fa7469a",
            EthereumSigner.toHex(digest)
        )
        assertEquals(
            "0x9f6cd72351ed7a5433ea98390f1c2e06f65e6b3b759bb59340e45f6fe99c33f6" +
                "5c33f10a24df12bcd67ed2a3dd8eab12f58194cb0100f6826ca9442a06337fac1c",
            EthereumSigner.toHex(EthereumSigner.signDigest(digest, accountPk))
        )
    }

    @Test
    fun `entries are cached per base channel, rotated per account, dropped on leave`() {
        val a = ChannelIdentities.entryFor("0xabc/chan-1", accountAddress, accountPk)
        // -1/-2/-3 share the identity; a different channel gets its own.
        assertEquals(a, ChannelIdentities.entryFor("0xabc/chan-2", accountAddress, accountPk))
        assertNotEquals(a, ChannelIdentities.entryFor("0xdef/other-1", accountAddress, accountPk))

        // Another account must never reuse a stale pseudonym entry.
        val otherPk = "0x2222222222222222222222222222222222222222222222222222222222222222"
        val b = ChannelIdentities.entryFor("0xabc/chan-1", "0x1563915e194D8CfBA1943570603F7606A3115508", otherPk)
        assertNotEquals(a.identityPk, b.identityPk)

        ChannelIdentities.drop("0xabc/chan-3")
        val c = ChannelIdentities.entryFor("0xabc/chan-1", accountAddress, accountPk)
        assertNotEquals(b.identityPk, c.identityPk)
    }
}

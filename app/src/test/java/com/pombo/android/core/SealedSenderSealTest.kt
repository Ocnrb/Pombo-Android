package com.pombo.android.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Byte-parity lock for the SEAL side, against vectors generated with the
 * web's own primitives (ethers ECDH + HKDF-SHA256 + AES-256-GCM —
 * tests/vectors/gen_seal_vectors.mjs). The open side is locked by
 * [SealedSenderCryptoTest]; together they close the loop: what this seals,
 * the web opens, and vice versa.
 */
class SealedSenderSealTest {

    private val senderPk = "0x8f2a559490d8e9bb4e0e7b53e1c6e4c2b1a0d9c8b7a6958473625140fedcba98"
    private val senderAddress = "0x54b2da155df6c56bbac68a232a0bf28a735164fc"
    private val recipientPk = "0x2222222222222222222222222222222222222222222222222222222222222222"
    private val recipientAddress = "0x1563915e194D8CfBA1943570603F7606A3115508"
    private val recipientPub = "0x02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27"
    private val ephemeralPk = "0x3333333333333333333333333333333333333333333333333333333333333333"
    private val epk = "0x023c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1"
    private val iv12 = ByteArray(12) { 0x07 }

    @Test
    fun `sealed key, pair key, bind digest and proof match the web vectors`() {
        assertEquals(
            "0x3b19f340e34a5c513ba1bd4caa52544246ca05d1b003b7fb69541ab684affb86",
            EthereumSigner.toHex(SealedSenderCrypto.sealedKey(ephemeralPk, recipientPub)!!)
        )
        assertEquals(
            "0x05c7f21900a4e82456cdd211435860cb79b441be39007c9764e776500973ce4a",
            EthereumSigner.toHex(SealedSenderCrypto.pairKey(recipientPk, EthereumSigner.compressedPublicKey(senderPk))!!)
        )
        assertEquals(
            "0xfae27e3bfdbef40e9a7c7f65bf00f88c8d254888a7486c720e9b004a2adb8505",
            EthereumSigner.toHex(SealedSenderCrypto.bindDigest(recipientAddress, epk))
        )
        assertEquals(
            "0x2ea1ae8da5dad6436cf02ef0c547a9d370d50ed721ea79df64833ceb229f0403" +
                "0db0d7ebe7faa8eb35a0f9196c787c0702bdd775134cf5c32d19c8822e82b8cc1b",
            EthereumSigner.toHex(
                EthereumSigner.signDigest(SealedSenderCrypto.bindDigest(recipientAddress, epk), senderPk)
            )
        )
    }

    @Test
    fun `binary wire matches the web vector byte for byte`() {
        val sealer = SealedSenderCrypto.BinarySealer(
            ephemeralPk, epk,
            SealedSenderCrypto.sealedKey(ephemeralPk, recipientPub)!!,
            EthereumSigner.signDigest(SealedSenderCrypto.bindDigest(recipientAddress, epk), senderPk)
        )
        val payload = SealedSenderCrypto.hexToBytes("0x0001020304fafbfcfdfeff")
        val wire = SealedSenderCrypto.sealBinary(sealer, payload, iv12)
        assertEquals(
            "0x02023c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1" +
                "0707070707070707070707078a482ee5e60f39a784e2b90c41e7f8cb9ca9fbb6c70b" +
                "96dcc301be6e2b22b7dbe06927e72016681270e165fa71994c820ab98244ea7ddaad" +
                "642e299fceb80fb73c9c5d8f4cd6ff7fe142700b3f34e1de36cb1f8323d36e4f26faf3fb",
            EthereumSigner.toHex(wire)
        )

        val opened = SealedSenderCrypto.openBinary(wire, recipientPk, recipientAddress)
        assertNotNull(opened)
        assertEquals(senderAddress, opened!!.first)
        assertArrayEquals(payload, opened.second)
    }

    @Test
    fun `chunk row matches the web vector and pairOpen round-trips`() {
        val key = SealedSenderCrypto.pairKey(recipientPk, EthereumSigner.compressedPublicKey(senderPk))!!
        val payload = "pombo chunk vector".toByteArray(Charsets.UTF_8)
        val row = SealedSenderCrypto.pairSeal(payload, key, iv12)
        assertEquals(
            "0x0707070707070707070707077c8f8603e8e44782d9c0768c6e328f78b1f311a512722266205e51483d52375c9c89",
            EthereumSigner.toHex(row)
        )
        // The pair key is symmetric — the sender derives the same key from the
        // opposite ends and must read its own row back.
        val senderSide = SealedSenderCrypto.pairKey(senderPk, recipientPub)!!
        assertArrayEquals(payload, SealedSenderCrypto.pairOpen(row, senderSide))
    }

    @Test
    fun `sealed JSON envelope opens through the vector-locked open`() {
        val message = JSONObject().put("type", "text").put("text", "olá selo nativo")
        val envelope = SealedSenderCrypto.seal(
            message, senderPk, recipientAddress, recipientPub, ephemeralPk, iv12
        )
        assertEquals(2, envelope.getInt("v"))
        assertEquals(epk, envelope.getString("epk"))

        val opened = SealedSenderCrypto.open(envelope, recipientPk, recipientAddress)
        assertNotNull("native seal must open with the web-locked open", opened)
        assertEquals(senderAddress, opened!!.first)
        assertEquals("olá selo nativo", opened.second.getString("text"))
        assertEquals(false, opened.second.has("p"))
    }

    @Test
    fun `random-path seal round-trips and binds to the recipient`() {
        val message = JSONObject().put("type", "text").put("text", "efémera aleatória")
        val (envelope, ephemeral) = SealedSenderCrypto.seal(message, senderPk, recipientAddress, recipientPub)
        assertEquals(EthereumSigner.compressedPublicKey(ephemeral), envelope.getString("epk"))

        val opened = SealedSenderCrypto.open(envelope, recipientPk, recipientAddress)
        assertEquals(senderAddress, opened!!.first)

        // A proof bound to this recipient must not attribute to the sender
        // when replayed against another inbox.
        val other = SealedSenderCrypto.open(
            envelope, recipientPk, "0x1111111111111111111111111111111111111111"
        )
        assertNotEquals(senderAddress, other?.first)
    }

    @Test
    fun `wrong key or truncated wire returns null`() {
        val sealer = SealedSenderCrypto.binarySealer(senderPk, recipientAddress, recipientPub)
        val wire = SealedSenderCrypto.sealBinary(sealer, ByteArray(10))
        assertNull(SealedSenderCrypto.openBinary(wire, senderPk, recipientAddress))
        assertNull(SealedSenderCrypto.openBinary(wire.copyOfRange(0, 50), recipientPk, recipientAddress))
        assertNull(SealedSenderCrypto.pairOpen(ByteArray(5), ByteArray(32)))
    }
}

package com.pombo.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Byte-parity lock against the web implementation. The vector below was
 * generated with the WEB'S OWN ethers (deterministic keys, fixed IV) — see the
 * port brief. If any constant in [SealedSenderCrypto] drifts (salt, info,
 * digest prefix, x-coord slice, recovery), this fails; and unlike most tests
 * in this project, here green DOES prove the thing that matters, because the
 * whole surface is a pure function of the vector.
 */
class SealedSenderCryptoTest {

    private val myPrivateKey = "0x2222222222222222222222222222222222222222222222222222222222222222"
    private val myAddress = "0x1563915e194D8CfBA1943570603F7606A3115508"
    private val expectedSender = "0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a"

    private fun vectorEnvelope() = JSONObject()
        .put("v", 2)
        .put("epk", "0x023c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1")
        .put(
            "ct",
            "38vwSnn335zedqPOs8I3fdkal1GC2Ns2xLWyvTmPh+7Y6pM8ptn1zXYjqIcl1QCxPmZgV58FTVh5UYd7" +
                "0w+PShWsbLp74zW3KIe/wGRZ1pjXUqEzUQDkX96yx5h6bl5A9N789i7lZY2XM4uax9P5Tm0V8x+l" +
                "Q4ASwj1X4KgEJfbxRzdBD0W8aAFh73pyAkYtfUgUN1eIgOGrCDfyBOitgcrBmJNASu4JroZqUomB" +
                "llGK6ToBevBHMZSimDbvlQnojy+yBuAx1N/wS2FBiARunohxYFGgU1WYwVPxql8QoZl7Woecek" +
                "GW6oMx/9I8Ib76RQ/6fH087Q=="
        )
        .put("iv", "BwcHBwcHBwcHBwcH")
        .put("e", "aes-256-gcm")

    @Test
    fun `opens the web-generated vector and recovers the sender`() {
        val opened = SealedSenderCrypto.open(vectorEnvelope(), myPrivateKey, myAddress)
        assertNotNull("vector must open", opened)
        val (sender, message) = opened!!
        assertEquals(expectedSender, sender)
        assertEquals("olá vetor", message.optString("text"))
        assertEquals("text", message.optString("type"))
        assertEquals("Web", message.optString("senderName"))
        assertEquals(false, message.has("p"))   // proof stripped after recovery
    }

    @Test
    fun `wrong recipient address recovers a different, meaningless sender`() {
        // The digest is rebuilt with the CALLER's address — a proof bound to
        // someone else must not name the real sender (bind protection).
        val opened = SealedSenderCrypto.open(
            vectorEnvelope(), myPrivateKey, "0x1111111111111111111111111111111111111111"
        )
        assertNotNull(opened)
        assertNotEquals(expectedSender, opened!!.first)
    }

    @Test
    fun `wrong private key does not open`() {
        assertNull(
            SealedSenderCrypto.open(
                vectorEnvelope(),
                "0x4444444444444444444444444444444444444444444444444444444444444444",
                myAddress
            )
        )
    }

    @Test
    fun `non-envelope and v1 payloads return null`() {
        assertNull(SealedSenderCrypto.open(JSONObject().put("type", "text"), myPrivateKey, myAddress))
        assertNull(
            SealedSenderCrypto.open(
                JSONObject().put("ct", "AA==").put("iv", "AA==").put("e", "aes-256-gcm"),
                myPrivateKey, myAddress
            )
        )
    }
}

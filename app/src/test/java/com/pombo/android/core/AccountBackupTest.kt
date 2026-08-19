package com.pombo.android.core

import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Parity lock against the web backup container. The vector file below was
 * built with the WEB'S OWN primitives (ethers encryptKeystoreJson + node
 * scrypt + AES-256-GCM — tests/vectors/gen_backup_vectors.mjs, N=4096 for
 * test speed; real files carry their own params). The export test asserts
 * our keystore equals the ethers-written one field by field — which is the
 * definition of "the web can open it".
 */
class AccountBackupTest {

    private fun open(text: String): () -> java.io.ByteArrayInputStream =
        { java.io.ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) }

    private val password = "pombo-backup-vector"
    private val privateKey = "0x8f2a559490d8e9bb4e0e7b53e1c6e4c2b1a0d9c8b7a6958473625140fedcba98"
    private val address = "0x54B2da155Df6C56BBAC68A232a0BF28A735164FC"

    private val vectorBackup = """
    {
      "format": "pombo-account-backup",
      "version": 1,
      "exportedAt": "2026-08-19T00:00:00.000Z",
      "keystore": {
        "address": "54b2da155df6c56bbac68a232a0bf28a735164fc",
        "id": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
        "version": 3,
        "Crypto": {
          "cipher": "aes-128-ctr",
          "cipherparams": { "iv": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" },
          "ciphertext": "5696bd27c98551d466f92c02aef0f771cedb525af09f0118024e97932047f49f",
          "kdf": "scrypt",
          "kdfparams": {
            "salt": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "n": 4096, "dklen": 32, "p": 1, "r": 8
          },
          "mac": "d3f78eac79c3cf1834c69eb5616d7ac3ad23b75035421a561fefb97d8f8c99b8"
        }
      },
      "encryptedData": {
        "kdf": "scrypt",
        "kdfparams": {
          "n": 4096, "r": 8, "p": 1, "dklen": 32,
          "salt": "0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        },
        "cipher": "aes-256-gcm",
        "cipherparams": { "iv": "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee" },
        "ciphertext": "0x3b8637d26f66908e40a7c8912ad744d4d2f3a3a9e3a2814d713b1b2971f7337ceb637402af2da45e464ba21738aec85938835ebbda56bbc4a5f5067328f2c662f17b5d1e2d4322665a87cc164e50b75d34f6e02b9e2605b2f2fb44d4034d430fdaf715f9be8defc117ea75bbc1777f35eb1b505f527e519b91dedad170e113926f6191f09146b67ad340fc1a1e042e713c865cadce0e8697494040ebed1d4894d2a3e218c5674cb3f695ef0755dfb20c7ad344ffe6230a91891ebadfc6fe2847aad96f2f0450ff3bd90c5c8c3b274041c91549e9513641acbb552d212d58d9a1111e9a76d92b90777609af9a493f1589c30bbdce14f816477a46f1f48d2975ad51bf8d877b5f54161dfc9fb1ff122afc516e5a84f1b3f3b617fddc6c90eed4e77eace286e5c7777bd6858360cf256d878348"
      }
    }
    """.trimIndent()

    @Test
    fun `imports the web-generated backup`() {
        val imported = AccountBackup.import(open(vectorBackup), password)
        assertEquals(privateKey, imported.privateKey)
        assertEquals(address, imported.address)
        assertEquals(1, imported.payload.getInt("version"))
        assertEquals("vector", imported.payload.getJSONObject("data").getString("username"))
        assertEquals(
            "Vector Channel",
            imported.payload.getJSONObject("data").getJSONArray("channels").getJSONObject(0).getString("name")
        )
        assertEquals(
            "img1",
            imported.payload.getJSONArray("imageBlobs").getJSONObject(0).getString("imageId")
        )
    }

    @Test
    fun `wrong password is rejected by the keystore MAC`() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountBackup.import(open(vectorBackup), "wrong-password")
        }
    }

    @Test
    fun `export reproduces the ethers keystore byte for byte`() {
        // Same payload the generator sealed — recovered via our own import.
        val payload = AccountBackup.import(open(vectorBackup), password).payload
        val out = ByteArrayOutputStream()
        AccountBackup.export(
            out, privateKey, password, payload,
            ByteArray(32) { 0xaa.toByte() },
            ByteArray(16) { 0xbb.toByte() },
            ByteArray(16) { 0xcc.toByte() },
            ByteArray(32) { 0xdd.toByte() },
            ByteArray(16) { 0xee.toByte() },
            4096
        )
        val produced = JSONObject(out.toString("UTF-8"))
        val expectedKs = JSONObject(vectorBackup).getJSONObject("keystore")
        val producedKs = produced.getJSONObject("keystore")

        assertEquals(expectedKs.getString("address"), producedKs.getString("address"))
        assertEquals(expectedKs.getString("id"), producedKs.getString("id"))
        val e = expectedKs.getJSONObject("Crypto")
        val p = producedKs.getJSONObject("Crypto")
        assertEquals(e.getString("ciphertext"), p.getString("ciphertext"))
        assertEquals(e.getString("mac"), p.getString("mac"))
        assertEquals(e.getJSONObject("kdfparams").toString(), p.getJSONObject("kdfparams").toString())
        assertEquals(e.getJSONObject("cipherparams").getString("iv"), p.getJSONObject("cipherparams").getString("iv"))

        // Round trip: what we export, we import — key, address and payload.
        val back = AccountBackup.import(open(out.toString("UTF-8")), password)
        assertEquals(privateKey, back.privateKey)
        assertEquals(address, back.address)
        assertEquals("vector", back.payload.getJSONObject("data").getString("username"))
    }

    @Test
    fun `random-parameter round trip`() {
        val payload = JSONObject().put("version", 1).put("data", JSONObject().put("username", "aleatório"))
        val out = ByteArrayOutputStream()
        AccountBackup.export(
            out, privateKey, "outra-password", payload,
            ByteArray(32) { 1 }, ByteArray(16) { 2 }, ByteArray(16) { 3 },
            ByteArray(32) { 4 }, ByteArray(16) { 5 }, 1024
        )
        val back = AccountBackup.import(open(out.toString("UTF-8")), "outra-password")
        assertEquals(privateKey, back.privateKey)
        assertEquals("aleatório", back.payload.getJSONObject("data").getString("username"))
    }
}

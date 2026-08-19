package com.pombo.android.core

import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.SCrypt
import org.json.JSONObject

/**
 * Native `pombo-account-backup` v1 — same container the web writes (ethers
 * Keystore V3 for the key + the app state scrypt-encrypted under
 * AES-256-GCM), so files restore on either client with the same password.
 * Parity locked by [AccountBackupTest] against a file built with the web's
 * own primitives (tests/vectors/gen_backup_vectors.mjs).
 *
 * Native ON PURPOSE, not a bridge call: a backup with image blobs is tens of
 * MB, and pushing it through the bridge base64-encodes the whole JSON into a
 * single allocation — importBackup died with a 107MB OutOfMemoryError doing
 * exactly that. Here the file never crosses the bridge, the private key never
 * enters the WebView (Phase B of docs/private_key_in_webview.md), and restore
 * works with no bridge connection at all — guest mode included.
 *
 * Memory: scrypt at the interop cost (N=131072, r=8) needs a ~134MB working
 * array, and the payload exists as tree + string + bytes while sealing. Both
 * fit only if they never coexist — large references are dropped before each
 * scrypt run; keep it that way.
 */
object AccountBackup {

    data class Imported(val privateKey: String, val address: String, val payload: JSONObject)

    private const val N_INTEROP = 131072
    private const val R = 8
    private const val P = 1
    private const val DKLEN = 32

    /**
     * Takes the stream, not the text: an 80MB file held by a caller (or a
     * parameter) through the two 134MB scrypt runs is exactly the allocation
     * stack that OOMs. Here the raw text dies at parse, the tree dies after
     * field extraction, and only the ~payload-sized ciphertext survives into
     * the kdf.
     */
    fun import(open: () -> java.io.InputStream, password: String): Imported {
        var root: JSONObject? = open().use { JSONObject(it.reader(Charsets.UTF_8).readText()) }
        if (root!!.optString("format") != "pombo-account-backup" || root.optInt("version") != 1) {
            throw IllegalArgumentException("Invalid backup format. Expected pombo-account-backup v1")
        }
        val keystore = root.getJSONObject("keystore")

        val enc = root.getJSONObject("encryptedData")
        val kdfparams = enc.getJSONObject("kdfparams")
        require(enc.optString("kdf") == "scrypt") { "Unsupported data kdf: ${enc.optString("kdf")}" }
        require(enc.optString("cipher") == "aes-256-gcm") { "Unsupported data cipher" }
        val n = kdfparams.getInt("n")
        val r = kdfparams.getInt("r")
        val p = kdfparams.getInt("p")
        val dklen = kdfparams.getInt("dklen")
        val salt = hex(kdfparams.getString("salt"))
        val iv = hex(enc.getJSONObject("cipherparams").getString("iv"))
        var ctHex: String? = enc.getString("ciphertext")

        // The tree holds the ~2x-payload ciphertext string; free it before the
        // byte copy and the scrypt working array stack up.
        root = null
        var ct: ByteArray? = hex(ctHex!!)
        ctHex = null

        val privateKey = decryptKeystore(keystore, password)
        // Checksummed, like the ethers Wallet the bridge used to return.
        val address = EthereumSigner.checksumAddress(EthereumSigner.address(privateKey))

        val dk = SCrypt.generate(password.toByteArray(Charsets.UTF_8), salt, n, r, p, dklen)
        val payloadBytes = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dk, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(ct)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to decrypt backup data")
        } finally {
            ct = null
        }

        return Imported(privateKey, address, JSONObject(String(payloadBytes, Charsets.UTF_8)))
    }

    /** Keystore V3 (ethers Wallet.encrypt): scrypt + MAC check + AES-128-CTR. */
    private fun decryptKeystore(keystore: JSONObject, password: String): String {
        val crypto = keystore.optJSONObject("Crypto") ?: keystore.optJSONObject("crypto")
            ?: throw IllegalArgumentException("Keystore has no crypto section")
        require(keystore.optInt("version") == 3) { "Unsupported keystore version" }
        require(crypto.optString("cipher") == "aes-128-ctr") { "Unsupported keystore cipher" }
        require(crypto.optString("kdf") == "scrypt") { "Unsupported keystore kdf" }

        val kdfparams = crypto.getJSONObject("kdfparams")
        val dk = SCrypt.generate(
            password.toByteArray(Charsets.UTF_8),
            hex(kdfparams.getString("salt")),
            kdfparams.getInt("n"), kdfparams.getInt("r"), kdfparams.getInt("p"),
            kdfparams.getInt("dklen")
        )
        val ct = hex(crypto.getString("ciphertext"))

        val mac = SealedSenderCrypto.keccak256(dk.copyOfRange(16, 32) + ct)
        if (!EthereumSigner.toHex(mac).removePrefix("0x")
                .equals(crypto.getString("mac").removePrefix("0x"), ignoreCase = true)
        ) {
            throw IllegalArgumentException("Incorrect password or corrupted backup")
        }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(dk.copyOfRange(0, 16), "AES"),
            IvParameterSpec(hex(crypto.getJSONObject("cipherparams").getString("iv")))
        )
        return EthereumSigner.toHex(cipher.doFinal(ct))
    }

    fun export(out: OutputStream, privateKeyHex: String, password: String, payload: JSONObject) {
        val random = java.security.SecureRandom()
        fun rand(size: Int) = ByteArray(size).also { random.nextBytes(it) }
        export(out, privateKeyHex, password, payload, rand(32), rand(16), rand(16), rand(32), rand(16), N_INTEROP)
    }

    /** Deterministic form — vector tests inject salts, IVs, uuid and scrypt cost. */
    internal fun export(
        out: OutputStream,
        privateKeyHex: String,
        password: String,
        payload: JSONObject,
        ksSalt: ByteArray,
        ksIv: ByteArray,
        uuidBytes: ByteArray,
        dataSalt: ByteArray,
        dataIv: ByteArray,
        n: Int
    ) {
        // Both scrypt runs happen before the payload is flattened, so the
        // 134MB working array never coexists with the payload string.
        val ksDk = SCrypt.generate(password.toByteArray(Charsets.UTF_8), ksSalt, n, R, P, DKLEN)
        val dataDk = SCrypt.generate(password.toByteArray(Charsets.UTF_8), dataSalt, n, R, P, DKLEN)

        val pkBytes = SealedSenderCrypto.hexToBytes(privateKeyHex)
        val ksCipher = Cipher.getInstance("AES/CTR/NoPadding")
        ksCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ksDk.copyOfRange(0, 16), "AES"), IvParameterSpec(ksIv))
        val ksCt = ksCipher.doFinal(pkBytes)
        val ksMac = SealedSenderCrypto.keccak256(ksDk.copyOfRange(16, 32) + ksCt)

        val keystore = JSONObject()
            .put("address", EthereumSigner.address(privateKeyHex).removePrefix("0x"))
            .put("id", uuidV4(uuidBytes))
            .put("version", 3)
            .put(
                "Crypto", JSONObject()
                    .put("cipher", "aes-128-ctr")
                    .put("cipherparams", JSONObject().put("iv", bareHex(ksIv)))
                    .put("ciphertext", bareHex(ksCt))
                    .put("kdf", "scrypt")
                    .put(
                        "kdfparams", JSONObject()
                            .put("salt", bareHex(ksSalt))
                            .put("n", n).put("dklen", DKLEN).put("p", P).put("r", R)
                    )
                    .put("mac", bareHex(ksMac))
            )

        var pt: ByteArray? = payload.toString().toByteArray(Charsets.UTF_8)
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataDk, "AES"), GCMParameterSpec(128, dataIv))
        val ct = gcm.doFinal(pt)
        pt = null

        val head = JSONObject()
            .put("format", "pombo-account-backup")
            .put("version", 1)
            .put("exportedAt", payload.opt("exportedAt"))
            .put("keystore", keystore)
            .put(
                "encryptedData", JSONObject()
                    .put("kdf", "scrypt")
                    .put(
                        "kdfparams", JSONObject()
                            .put("n", n).put("r", R).put("p", P).put("dklen", DKLEN)
                            .put("salt", "0x" + bareHex(dataSalt))
                    )
                    .put("cipher", "aes-256-gcm")
                    .put("cipherparams", JSONObject().put("iv", "0x" + bareHex(dataIv)))
                    .put("ciphertext", "0x__CT__")
            ).toString()

        // The ciphertext is ~2x the payload as hex — stream it in chunks
        // instead of ever holding the full string.
        val marker = "\"0x__CT__\""
        val cut = head.indexOf(marker)
        out.write(head.substring(0, cut).toByteArray(Charsets.UTF_8))
        out.write("\"0x".toByteArray(Charsets.UTF_8))
        val chunk = 64 * 1024
        var off = 0
        while (off < ct.size) {
            val end = minOf(off + chunk, ct.size)
            out.write(bareHex(ct.copyOfRange(off, end)).toByteArray(Charsets.US_ASCII))
            off = end
        }
        out.write("\"".toByteArray(Charsets.UTF_8))
        out.write(head.substring(cut + marker.length).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** RFC 4122 v4 formatting over injected bytes — what ethers does with its uuid option. */
    private fun uuidV4(bytes: ByteArray): String {
        require(bytes.size == 16)
        val b = bytes.copyOf()
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = bareHex(b)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    private fun bareHex(bytes: ByteArray): String = EthereumSigner.toHex(bytes).removePrefix("0x")

    private fun hex(s: String): ByteArray = SealedSenderCrypto.hexToBytes(s)
}

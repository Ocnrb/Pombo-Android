package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-parity lock against the web implementation. Every vector below was
 * generated with the WEB'S OWN ethers (6.16.0) — scratchpad script over
 * `SigningKey.sign` and `hashMessage`. Like [SealedSenderCryptoTest], green
 * here proves the thing that matters: the whole surface is a pure function of
 * the vectors, covering RFC 6979 k, low-s normalization and the recovery byte.
 */
class EthereumSignerTest {

    private val k1 = "0x2222222222222222222222222222222222222222222222222222222222222222"
    private val k2 = "0x4444444444444444444444444444444444444444444444444444444444444444"
    private val k3 = "0x8f2a559490d8e9bb4e0e7b53e1c6e4c2b1a0d9c8b7a6958473625140fedcba98"

    private val digests = listOf(
        "0x1111111111111111111111111111111111111111111111111111111111111111",
        "0xfefefefefefefefefefefefefefefefefefefefefefefefefefefefefefefefe",
        "0x79f9c5a9336a19c94ab5ef25cfc7f00b886c5e3a0bc66386455934bbbaaa13e1",
        "0x19289eee2924ac8011d15d7de4be6c1644f726ef6deea006fca815099c39c25c"
    )

    private val digestSignatures = mapOf(
        k1 to listOf(
            "0xb9f0bb08640d3c1c00761cdd0121209268f6fd3816bc98b9e6f3cc77bf82b69812ac7a61788a0fdc0e19180f14c945a8e1088a27d92a74dce81c0981fb6447441b",
            "0xc156163987ecafcecef1527ec7ece56c3578efe9d35015722273a0e928551af4147107ee8caa2bfbc3229d9b76a2b11e6c12eb08e20b4cf7a6cb76f8172c15411c",
            "0x3a19c3586e61c30d02c9f04a7403d4c84f1a765a1e3e3a85533ab916011040d80a20338342cd5b8e79c4fc13bb0468b17458347a47e2ee0f57059d75654eb4da1c",
            "0x82a76b7fca93e0dce886970afa2c002421f3c8894186fc089a71a55e142bdb79288f974bd73e29e10a0ab6b06e9e8e4e82096e7d551d2f03b46a93f406246f9f1b"
        ),
        k2 to listOf(
            "0x7f49675aec086da9ff762ed045130a231b9274e9f6b3032f9f73fb5d45ab4ff746b40e990f6b403cc3c3dc23b4b9cbe9093029fd6d721d0e2b19c8d391e02bdb1b",
            "0xf52bfb66cb2882911fa6f74b0a9167b5142d0f4c0c170f45779462ba2f116da546d7110ef4f4254e777350dbb18a258b857642f4aa960e5c250f0d4d2b4013d81c",
            "0xcd48bfd1a335bf40c72d4a51cd930fe2ae52e5c3e7310c1ce769e2b004a05f3f6552c7e40295f99935401460005c875406f6955ef49c671d40a721bc0de26e031b",
            "0x5b3b1c3480e7e7f15c1f47061f52c5da4f43741d3ddadcc3bdda7961a9a3160543253b69895f546a9ddcddd4c92ddc91e3b1bfa202467e499e1e4a0afde2b1bd1b"
        ),
        k3 to listOf(
            "0x68a11b6c98279dbe018a3c64baa755bb0c6d519814045ae2c06f112c78139af37dbeb15ef4bbad16f175041c5aa02095480337f0795ae6badcc72ea31bceba0c1c",
            "0x3110944f068f7428ba08d78651b157e2e98a753a03590d336f6e7059c81d73a72fd88fb0adbac6a303312dcfdba3c8fa2b8aac2d26ad621bb3979e5bc140f7e51b",
            "0xb425db87aadab51f15678c1b5c302bde97f766364a74f97ecca8b65afd8ac1a108dc515ff569408aa9833f56f2e5ce0beb7b7105bdcb928a228c357fad48bf4a1c",
            "0xa7ab15f7cad769ee51d8452f7d133a0ca9a8a14317f3daf5de7a28b18567cab4108ac0407dddb18d13aa245be5f7c373fcd6afe75ef038124e0b8b01bf3ec2ef1b"
        )
    )

    // Payloads: utf8, empty, binary, and a 1000-byte Streamr-sized piece. The
    // big payload is rebuilt by the generator's own formula and guarded by its
    // expected hash, so the test file stays readable.
    private fun bigPayload() = ByteArray(1000) { ((it * 7 + 3) and 0xff).toByte() }

    private val payloads = listOf(
        "olá pombo — assina isto".toByteArray(Charsets.UTF_8),
        ByteArray(0),
        SealedSenderCrypto.hexToBytes("0x009f9296ff01"),
        ByteArray(0) // placeholder; index 3 uses bigPayload()
    )

    private val messageHashes = listOf(
        "0xb359fb3dd744cfd24b1e3265ef59ee266ee4b5fb1e5af98c3fd003e50f725638",
        "0x5f35dce98ba4fba25530a026ed80b2cecdaa31091ba4958b99b52ea1d068adad",
        "0xc38265214d38ad97205e8bc700f2df647dd7dcd27baa1491b0ee76ee215fed4e",
        "0x28c0a19d104ff3e95da5fa8b26b00c679f7d306135a1a68d3db4f0289cba328e"
    )

    private val messageSignatures = mapOf(
        k1 to listOf(
            "0x6cc52c2cae0b2836070173831deed16022356a017fc92d56d7c7d1d3e65dfb210e56cdbe5be2f0d14692b66b6c06cd875b6ea2ba1e1a7f6c3aa31aefc2f9a3bb1b",
            "0xe9f9984ba234bd311c89021a2e94f7c772c8e46e8596fcb41908c2f55de91b0d5a79cb6ff7cef949095e22f735643bc7eee1c92a875d8a5df8cf4cb07f4d75bc1b",
            "0x057cfbf21bc3a6fcc5a6ccfb7451ee4b233ba1641ec298ad6a5ad6498ea35fa36aac612ba39332dea14c0be50483db4ede8745a97b206d4806d7efb826ad995e1c",
            "0xee4217871cffcc75068b4a204a9c999938a29878e3eae8dc51a860d5d38039f7331e507308a73d6d100c263365c5054d321c16f3c4f5a1dfc19aadfdf9d28e8f1b"
        ),
        k2 to listOf(
            "0x4627c7a78caa438dc330f0c31c3b0f9e04454f973dbe93bdc7afb316dd85f8f404da9c6cd88c393f16776f50bbe0ec3569a4dcdb01ce6e2ce2c78a50918856821b",
            "0xdb9f1b4271454594acc009c0ff3589c1dbc1f7b900b21edf3f8bb419a35aa01903258bfb0a533968219f63caf87a5411647ddd6a1c5fdd0860dbbf22cedf04781c",
            "0xc634205b9aed2febd8cbed48c5e70b85d870c10f39d7034835855771fd82a0fd40b656321b08ba5f0a6843beb64fb58d5e8d71376cdbc55107dd8d62666f33ef1b",
            "0x38ea18888098398fd627c854eea646c664d44b30959fd13a22f3169a3681dd960bdc9f5d6e21616adff5dbbdc2ba7db90f5f57c6c661b27895f8b033c76e89171b"
        ),
        k3 to listOf(
            "0x4a135c1d5ea3f08bfdc5c37d22f8e98288f4e27975d03d72adfbf20bbb6f87a02cb836a71b171fc14436c5709fa7915bac685f6dcca401b84e24aad7536d38ab1c",
            "0x6c3f97b7c075b8d7d51382a37edeaee1bcfed5d424f4c4075170121dc91c016333007a0c8ff820bd96ab71a69aae8328ce05e4eef35ce672310421bef0131be01c",
            "0x80981d9542dcd7081dc75b43182063741b71ce9ac2696fb682a5d67a411e616b1f1ab5a137f15431dbadaafa33dd6c39ae4cc730b83e91fb27af03d990da7cd81b",
            "0xb99f82478fd5d20be7420a0497ad38f2506142d68fedaba205f33a92463401a147743d02d6ba5391dec00322653e069ec875cbfe5c679dd9fe9492ffbb90c7801c"
        )
    )

    private fun payload(i: Int): ByteArray = if (i == 3) bigPayload() else payloads[i]

    @Test
    fun `digest signatures match ethers SigningKey_sign serialized`() {
        for ((key, expected) in digestSignatures) {
            digests.forEachIndexed { i, digest ->
                val sig = EthereumSigner.signDigest(SealedSenderCrypto.hexToBytes(digest), key)
                assertEquals("key=$key digest=$digest", expected[i], EthereumSigner.toHex(sig))
            }
        }
    }

    @Test
    fun `hashMessage matches ethers hashMessage over bytes`() {
        messageHashes.forEachIndexed { i, expected ->
            assertEquals(expected, EthereumSigner.toHex(EthereumSigner.hashMessage(payload(i))))
        }
    }

    @Test
    fun `message signatures match ethers over the EIP-191 preimage`() {
        for ((key, expected) in messageSignatures) {
            messageHashes.indices.forEach { i ->
                val sig = EthereumSigner.signMessage(payload(i), key)
                assertEquals("key=$key payload=$i", expected[i], EthereumSigner.toHex(sig))
            }
        }
    }

    @Test
    fun `compressed public keys and addresses match ethers`() {
        assertEquals(
            "0x02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27",
            EthereumSigner.compressedPublicKey(k1)
        )
        assertEquals(
            "0x032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991",
            EthereumSigner.compressedPublicKey(k2)
        )
        assertEquals(
            "0x0326250b70672afc0651967c0552716af5dc759302b13b4a2007eb81a34cfe78b6",
            EthereumSigner.compressedPublicKey(k3)
        )
        assertEquals("0x1563915e194d8cfba1943570603f7606a3115508", EthereumSigner.address(k1))
        assertEquals("0x7564105e977516c53be337314c7e53838967bdac", EthereumSigner.address(k2))
        assertEquals("0x54b2da155df6c56bbac68a232a0bf28a735164fc", EthereumSigner.address(k3))
    }
}

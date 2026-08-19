package com.pombo.android.core

/**
 * Channel pseudonyms, minted natively — the account key signs the POMBO_PUB_V1
 * proof in Kotlin and the bridge page only ever receives the finished entry
 * (docs/private_key_in_webview.md, Phase B). Byte-parity with the web's
 * publisherProof.js is locked by [ChannelIdentitiesTest].
 *
 * ONE KEY PER CHANNEL, not per message or per account — the pseudonym's job is
 * to stop a network observer from stitching a social graph, and two channels
 * must not be tied together by publisher. All three streams (-1/-2/-3) share
 * the entry.
 *
 * NEVER PERSISTED: in memory only. Entries are dropped on channel leave, and
 * an entry minted under another account is ignored (each remembers who signed
 * its proof), so an account switch can never reuse a stale pseudonym.
 */
object ChannelIdentities {

    data class Entry(
        val account: String,
        val identityPk: String,
        /** Checksummed, like the web's `new Wallet(pk).address`. */
        val publisherId: String,
        /** 65-byte serialized signature over keccak("POMBO_PUB_V1|" + lowercase(publisherId)). */
        val proof: String
    )

    private val entries = HashMap<String, Entry>()

    /** All three streams of a channel (-1/-2/-3) share one identity. */
    fun baseChannelId(streamId: String): String = streamId.replace(Regex("-[123]$"), "")

    @Synchronized
    fun entryFor(streamId: String, accountAddress: String, accountPrivateKey: String): Entry {
        val key = baseChannelId(streamId)
        entries[key]?.takeIf { it.account.equals(accountAddress, ignoreCase = true) }?.let { return it }
        val identityPk = SealedSenderCrypto.generateEphemeralKey()
        val publisherId = EthereumSigner.checksumAddress(EthereumSigner.address(identityPk))
        val digest = SealedSenderCrypto.keccak256(
            "POMBO_PUB_V1|${publisherId.lowercase()}".toByteArray(Charsets.UTF_8)
        )
        val entry = Entry(
            accountAddress, identityPk, publisherId,
            EthereumSigner.toHex(EthereumSigner.signDigest(digest, accountPrivateKey))
        )
        entries[key] = entry
        return entry
    }

    /** Rotation on genuine leave — never on a view switch (peers mid-transfer know the publisher). */
    @Synchronized
    fun drop(streamId: String) {
        entries.remove(baseChannelId(streamId))
    }
}

package com.pombo.android.core

/**
 * Shape check for the transaction-signing oracle (docs/private_key_in_webview.md,
 * "Oracle API rule"): the endpoint only signs bytes that parse as an Ethereum
 * transaction envelope, so it cannot be used as a raw-keccak signing oracle
 * for arbitrary payloads. ChainGuard remains the actual spend gate — this is
 * domain separation, not authorization.
 */
object Rlp {

    /**
     * True for an EIP-2718 typed envelope (0x01/0x02 ‖ RLP list) or a legacy
     * transaction (bare RLP list), where the list is well-formed, consumes
     * every byte, and has a plausible field count (6..12 — unsigned legacy up
     * to signed EIP-1559).
     */
    fun isTransactionEnvelope(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        val type = bytes[0].toInt() and 0xff
        val body = when {
            type == 0x01 || type == 0x02 -> bytes.copyOfRange(1, bytes.size)
            type >= 0xc0 -> bytes
            else -> return false
        }
        return try {
            val items = topLevelListItems(body)
            items in 6..12
        } catch (e: Exception) {
            false
        }
    }

    /** Item count of a top-level RLP list that must span the whole buffer. */
    private fun topLevelListItems(b: ByteArray): Int {
        val first = b[0].toInt() and 0xff
        var offset: Int
        var length: Int
        when {
            first in 0xc0..0xf7 -> { offset = 1; length = first - 0xc0 }
            first in 0xf8..0xff -> {
                val lenOfLen = first - 0xf7
                if (1 + lenOfLen > b.size) throw IllegalArgumentException("truncated")
                length = 0
                for (i in 1..lenOfLen) length = (length shl 8) or (b[i].toInt() and 0xff)
                offset = 1 + lenOfLen
            }
            else -> throw IllegalArgumentException("not a list")
        }
        if (offset + length != b.size) throw IllegalArgumentException("trailing bytes")
        var pos = offset
        var count = 0
        while (pos < b.size) {
            pos += itemLength(b, pos)
            count++
        }
        if (pos != b.size) throw IllegalArgumentException("overrun")
        return count
    }

    /** Total encoded length (header + payload) of the RLP item at [pos]. */
    private fun itemLength(b: ByteArray, pos: Int): Int {
        val first = b[pos].toInt() and 0xff
        return when {
            first < 0x80 -> 1
            first in 0x80..0xb7 -> 1 + (first - 0x80)
            first in 0xb8..0xbf -> {
                val lenOfLen = first - 0xb7
                var len = 0
                for (i in 1..lenOfLen) len = (len shl 8) or (b[pos + i].toInt() and 0xff)
                1 + lenOfLen + len
            }
            first in 0xc0..0xf7 -> 1 + (first - 0xc0)
            else -> {
                val lenOfLen = first - 0xf7
                var len = 0
                for (i in 1..lenOfLen) len = (len shl 8) or (b[pos + i].toInt() and 0xff)
                1 + lenOfLen + len
            }
        }
    }
}

package com.pombo.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

/**
 * Cross-implementation tests for the -2/P2 piece format.
 *
 * The expected bytes here were NOT written by hand: they are the output of the
 * web's own `encodeFilePiece` (src/js/media.js) run over these inputs. A test
 * that only round-trips Kotlin against itself would pass just as happily with
 * the byte order reversed — and the failure would surface as corrupt files
 * between an Android seeder and a web leecher, long after the fact.
 */
class MediaWireTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    /** fileId, pieceIndex, payload hex, full encoded hex — all from the web. */
    private val webVectors = listOf(
        Triple("550e8400-e29b-41d4-a716-446655440000", 0, "deadbeef") to
            "0135353065383430302d653239622d343164342d613731362d3434363635353434303030" +
            "3000000000deadbeef",
        Triple("550e8400-e29b-41d4-a716-446655440000", 1, "00") to
            "0135353065383430302d653239622d343164342d613731362d3434363635353434303030" +
            "300000000100",
        Triple("f47ac10b-58cc-4372-a567-0e02b2c3d479", 255, "ff007f80") to
            "0166343761633130622d353863632d343337322d613536372d3065303262326333643437" +
            "39000000ffff007f80",
        Triple("f47ac10b-58cc-4372-a567-0e02b2c3d479", 256, "") to
            "0166343761633130622d353863632d343337322d613536372d3065303262326333643437" +
            "3900000100",
        Triple("00000000-0000-0000-0000-000000000000", 65535, "010203") to
            "0130303030303030302d303030302d303030302d303030302d3030303030303030303030" +
            "300000ffff010203",
        Triple("00000000-0000-0000-0000-000000000000", 16777216, "09") to
            "0130303030303030302d303030302d303030302d303030302d3030303030303030303030" +
            "300100000009"
    )

    @Test
    fun `encodes exactly what the web encoder produces`() {
        for ((input, expected) in webVectors) {
            val (fileId, index, payloadHex) = input
            val actual = MediaWire.encodeFilePiece(fileId, index, unhex(payloadHex))
            assertEquals("piece $index of $fileId", expected, hex(actual))
        }
    }

    @Test
    fun `decodes bytes produced by the web encoder`() {
        for ((input, encoded) in webVectors) {
            val (fileId, index, payloadHex) = input
            val piece = MediaWire.decodeBinaryMedia(unhex(encoded))
            requireNotNull(piece) { "web-encoded piece $index failed to decode" }
            assertEquals(fileId, piece.fileId)
            assertEquals(index, piece.pieceIndex)
            assertArrayEquals(unhex(payloadHex), piece.data)
        }
    }

    /**
     * A full-size piece. Asserting on a SHA-256 of the web's encoding keeps the
     * fixture readable while still catching a single flipped byte anywhere in
     * the 240 KB — including in the header, which the small vectors above would
     * also catch, and in the payload copy, which they would not.
     */
    @Test
    fun `encodes a full 240 KB piece identically to the web`() {
        val payload = ByteArray(240 * 1024) { ((it * 31 + 7) and 0xff).toByte() }
        val encoded = MediaWire.encodeFilePiece(
            "550e8400-e29b-41d4-a716-446655440000", 12345, payload
        )
        assertEquals(245801, encoded.size)
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        assertEquals(
            "9fe8722c1971d894648d514f831d7063d72bf96042e669c991550cb3f56c59c4",
            hex(digest)
        )
    }

    @Test
    fun `round-trips a full piece`() {
        val payload = ByteArray(240 * 1024) { ((it * 17 + 3) and 0xff).toByte() }
        val piece = MediaWire.decodeBinaryMedia(
            MediaWire.encodeFilePiece("550e8400-e29b-41d4-a716-446655440000", 7, payload)
        )
        requireNotNull(piece)
        assertEquals(7, piece.pieceIndex)
        assertArrayEquals(payload, piece.data)
    }

    // ---- rejection: this runs on every message that lands on the partition ----

    @Test
    fun `rejects payloads that are not pieces`() {
        assertNull("null", MediaWire.decodeBinaryMedia(null))
        assertNull("empty", MediaWire.decodeBinaryMedia(ByteArray(0)))
        // Unknown leading byte — a future message type from a newer client.
        val unknown = MediaWire.encodeFilePiece(
            "550e8400-e29b-41d4-a716-446655440000", 0, byteArrayOf(1)
        ).also { it[0] = 0x02 }
        assertNull("unknown type", MediaWire.decodeBinaryMedia(unknown))
    }

    @Test
    fun `rejects a buffer too short to hold a header`() {
        // One byte short of a complete header: the index read would run off the
        // end, and a lenient decoder would hand back a piece with garbage state.
        val truncated = ByteArray(MediaWire.PIECE_HEADER_BYTES - 1)
        truncated[0] = MediaWire.TYPE_FILE_PIECE
        assertNull(MediaWire.decodeBinaryMedia(truncated))
    }

    @Test
    fun `accepts a header with no payload`() {
        // Exactly a header is legal — a zero-length piece is degenerate but well
        // formed, and must not be confused with the truncated case above.
        val encoded = MediaWire.encodeFilePiece(
            "550e8400-e29b-41d4-a716-446655440000", 3, ByteArray(0)
        )
        assertEquals(MediaWire.PIECE_HEADER_BYTES, encoded.size)
        val piece = MediaWire.decodeBinaryMedia(encoded)
        requireNotNull(piece)
        assertEquals(0, piece.data.size)
    }

    /**
     * The index is a uint32 on the wire but an Int in Kotlin. Naive assembly
     * sign-extends anything above 2^31 into a negative index, which would then
     * be used to address a piece array.
     */
    @Test
    fun `rejects an index that does not fit a positive Int`() {
        val buf = MediaWire.encodeFilePiece(
            "550e8400-e29b-41d4-a716-446655440000", 1, byteArrayOf(1)
        )
        buf[37] = 0xff.toByte()
        buf[38] = 0xff.toByte()
        buf[39] = 0xff.toByte()
        buf[40] = 0xff.toByte()
        assertNull(MediaWire.decodeBinaryMedia(buf))
    }

    @Test
    fun `decodes the largest index that does fit`() {
        val buf = MediaWire.encodeFilePiece(
            "550e8400-e29b-41d4-a716-446655440000", Int.MAX_VALUE, byteArrayOf(1)
        )
        assertEquals(Int.MAX_VALUE, MediaWire.decodeBinaryMedia(buf)?.pieceIndex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses to encode a file id that is not 36 bytes`() {
        // Silently truncating (as the web's slice(0,36) would) puts a short id
        // in a fixed-width field and shifts nothing — but a LONGER id would be
        // cut, and both sides would then disagree about which file it is.
        MediaWire.encodeFilePiece("too-short", 0, byteArrayOf(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses to encode a negative index`() {
        MediaWire.encodeFilePiece("550e8400-e29b-41d4-a716-446655440000", -1, byteArrayOf(1))
    }
}

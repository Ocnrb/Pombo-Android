package com.pombo.android.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageWireTest {

    // ================= packChunkPayload (exact bytes) =================

    @Test
    fun `pack lays out metaLen, meta, totalChunks, chunkIndex, data big-endian`() {
        val meta = byteArrayOf(1, 2, 3)
        val data = byteArrayOf(9, 9)
        val packed = StorageWire.packChunkPayload(meta, totalChunks = 5, chunkIndex = 259, data = data)
        // 0x103 == 259, both counts are big-endian uint32.
        val expected = byteArrayOf(
            0, 0, 0, 3,   // metaLen = 3
            1, 2, 3,      // meta
            0, 0, 0, 5,   // totalChunks = 5
            0, 0, 1, 3,   // chunkIndex = 259
            9, 9          // data
        )
        assertArrayEquals(expected, packed)
    }

    @Test
    fun `pack handles empty data`() {
        val packed = StorageWire.packChunkPayload(byteArrayOf(7), totalChunks = 1, chunkIndex = 0, data = ByteArray(0))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 7, 0, 0, 0, 1, 0, 0, 0, 0), packed)
    }

    // ================= round-trip through real meta JSON =================

    private fun metaBytes(transferId: String) = StorageWire.chunkMetaJson(
        fileName = "movie.mp4",
        fileType = "video/mp4",
        originalSize = 12_345_678L,
        compressedSize = 12_345_678L,
        compression = "none",
        transferId = transferId,
        timestamp = 1_700_000_000_000L
    )

    @Test
    fun `chunkMetaJson is valid binary_file_chunked v2 with the transferId`() {
        val o = JSONObject(String(metaBytes("abc123"), Charsets.UTF_8))
        assertEquals("binary_file_chunked", o.getString("type"))
        assertEquals(2, o.getInt("version"))
        assertEquals("abc123", o.getString("transferId"))
        assertEquals("movie.mp4", o.getString("fileName"))
    }

    @Test
    fun `pack then unpack recovers transferId, counts and data`() {
        val tid = "0123456789abcdef0123456789abcdef"
        val data = ByteArray(1000) { (it % 251).toByte() }
        val packed = StorageWire.packChunkPayload(metaBytes(tid), totalChunks = 42, chunkIndex = 41, data = data)
        val parsed = StorageWire.unpackChunkPayload(packed)!!
        assertEquals(tid, parsed.transferId)
        assertEquals(42, parsed.totalChunks)
        assertEquals(41, parsed.chunkIndex)
        assertArrayEquals(data, parsed.chunkData)
    }

    // ================= unpack rejects foreign / malformed =================

    @Test
    fun `unpack rejects null and too-short buffers`() {
        assertNull(StorageWire.unpackChunkPayload(null))
        assertNull(StorageWire.unpackChunkPayload(ByteArray(0)))
        assertNull(StorageWire.unpackChunkPayload(byteArrayOf(0, 0, 0, 1))) // warm-up ping style
    }

    @Test
    fun `unpack rejects an out-of-range metaLen`() {
        // metaLen claims 9999 bytes in a 12-byte buffer.
        val bad = byteArrayOf(0, 0, 0x27.toByte(), 0x0f.toByte(), 1, 2, 3, 4, 5, 6, 7, 8)
        assertNull(StorageWire.unpackChunkPayload(bad))
    }

    @Test
    fun `unpack rejects a wrong type or version`() {
        val wrongType = JSONObject().put("type", "something_else").put("version", 2)
            .put("transferId", "x").toString().toByteArray(Charsets.UTF_8)
        assertNull(StorageWire.unpackChunkPayload(
            StorageWire.packChunkPayload(wrongType, 1, 0, byteArrayOf(1))))

        val wrongVer = JSONObject().put("type", "binary_file_chunked").put("version", 1)
            .put("transferId", "x").toString().toByteArray(Charsets.UTF_8)
        assertNull(StorageWire.unpackChunkPayload(
            StorageWire.packChunkPayload(wrongVer, 1, 0, byteArrayOf(1))))
    }

    @Test
    fun `unpack rejects a truncated payload missing the trailing counts`() {
        val meta = metaBytes("x")
        // Only metaLen + meta, no room for totalChunks/chunkIndex.
        val truncated = ByteArray(4 + meta.size)
        truncated[3] = meta.size.toByte()
        meta.copyInto(truncated, 4)
        assertNull(StorageWire.unpackChunkPayload(truncated))
    }
}

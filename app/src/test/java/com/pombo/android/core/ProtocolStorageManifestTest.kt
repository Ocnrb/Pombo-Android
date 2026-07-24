package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-for-byte parity of [Protocol.canonicalStorageFileManifestData] with the
 * web identity.js createStorageFileManifestHash. The two expected strings are
 * ground truth captured from Node's JSON.stringify over the exact same object
 * (see the generator in the storage port history) — a divergence in key order,
 * number formatting, null handling, or string escaping would break signature
 * verification silently in the field, so it must fail here instead.
 */
class ProtocolStorageManifestTest {

    @Test
    fun `full password-channel announce matches the web JSON exactly`() {
        val expected =
            """{"protocol":"POMBO","version":1,"type":"storage_file_announce","id":"0123456789abcdef0123456789abcdef","sender":"0xabcdef0000000000000000000000000000000001","timestamp":1700000000000,"channelId":"0xabc/my_channel-1","transferId":"aaaabbbbccccddddeeeeffff00001111","fileName":"a/b \"c\".mp4","fileType":"video/mp4","originalSize":12345678,"compressedSize":12000000,"compression":"deflate","totalChunks":51,"chunkDataSize":245400,"chunkPartitions":9,"firstChunkPartition":2,"firstChunkTs":1700000000123,"lastChunkTs":1700000009999,"storedChunks":51,"encSalt":"c2FsdHNhbHRzYWx0c2FsdA=="}"""

        val actual = Protocol.canonicalStorageFileManifestData(
            id = "0123456789abcdef0123456789abcdef",
            sender = "0xABCDef0000000000000000000000000000000001", // lowercased by the function
            timestamp = 1_700_000_000_000L,
            channelId = "0xabc/my_channel-1",
            transferId = "aaaabbbbccccddddeeeeffff00001111",
            fileName = "a/b \"c\".mp4",
            fileType = "video/mp4",
            originalSize = 12_345_678L,
            compressedSize = 12_000_000L,
            compression = "deflate",
            totalChunks = 51,
            chunkDataSize = 245_400,
            chunkPartitions = 9,
            firstChunkPartition = 2,
            firstChunkTs = 1_700_000_000_123L,
            lastChunkTs = 1_700_000_009_999L,
            storedChunks = 51,
            encSalt = "c2FsdHNhbHRzYWx0c2FsdA=="
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `public channel with null storedChunks and encSalt matches the web JSON`() {
        val expected =
            """{"protocol":"POMBO","version":1,"type":"storage_file_announce","id":"ffffffffffffffffffffffffffffffff","sender":"0x0000000000000000000000000000000000000002","timestamp":1700000000001,"channelId":"0xdef/pub-1","transferId":"11112222333344445555666677778888","fileName":"readme.txt","fileType":"text/plain","originalSize":4096,"compressedSize":2048,"compression":"deflate","totalChunks":1,"chunkDataSize":245400,"chunkPartitions":9,"firstChunkPartition":2,"firstChunkTs":1700000000050,"lastChunkTs":1700000000060,"storedChunks":null,"encSalt":null}"""

        val actual = Protocol.canonicalStorageFileManifestData(
            id = "ffffffffffffffffffffffffffffffff",
            sender = "0x0000000000000000000000000000000000000002",
            timestamp = 1_700_000_000_001L,
            channelId = "0xdef/pub-1",
            transferId = "11112222333344445555666677778888",
            fileName = "readme.txt",
            fileType = "text/plain",
            originalSize = 4096L,
            compressedSize = 2048L,
            compression = "deflate",
            totalChunks = 1,
            chunkDataSize = 245_400,
            chunkPartitions = 9,
            firstChunkPartition = 2,
            firstChunkTs = 1_700_000_000_050L,
            lastChunkTs = 1_700_000_000_060L,
            storedChunks = null,
            encSalt = null
        )
        assertEquals(expected, actual)
    }
}

package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {

    /**
     * Byte-for-byte parity with the web's identity.js createFileManifestHash
     * input. The expected string was produced by running the web's own
     * JSON.stringify construction in Node with these inputs — if the Kotlin
     * canonical drifts by a single byte, signatures stop verifying across
     * clients, so this asserts the whole string, not properties of it.
     */
    @Test
    fun `file manifest canonical matches the web byte for byte`() {
        val canonical = Protocol.canonicalFileManifestData(
            id = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            sender = "0xAbCdEf0123456789abcdef0123456789ABCDEF01",
            timestamp = 1784800000000L,
            channelId = "0xae340e799e8151f6a4999d245e466197aa217667/d678b10e68aa874e-1",
            fileId = "3220aba1-f9c2-4cac-a787-77b7592c3722",
            fileName = "relatório \"final\" <v2>.pdf",
            fileSize = 51598024L,
            fileType = "application/pdf",
            pieceCount = 2,
            pieceHashes = listOf("aa".repeat(32), "bb".repeat(32))
        )
        val expected = "{\"protocol\":\"POMBO\",\"version\":2,\"type\":\"file_announce\"," +
            "\"id\":\"a1b2c3d4e5f60718293a4b5c6d7e8f90\"," +
            "\"sender\":\"0xabcdef0123456789abcdef0123456789abcdef01\"," +
            "\"timestamp\":1784800000000," +
            "\"channelId\":\"0xae340e799e8151f6a4999d245e466197aa217667/d678b10e68aa874e-1\"," +
            "\"fileId\":\"3220aba1-f9c2-4cac-a787-77b7592c3722\"," +
            "\"fileName\":\"relatório \\\"final\\\" <v2>.pdf\"," +
            "\"fileSize\":51598024," +
            "\"fileType\":\"application/pdf\"," +
            "\"pieceCount\":2," +
            "\"pieceHashes\":[\"${"aa".repeat(32)}\",\"${"bb".repeat(32)}\"]}"
        assertEquals(expected, canonical)
    }

    @Test
    fun `sender address is lowercased in the canonical`() {
        val canonical = Protocol.canonicalFileManifestData(
            id = "x", sender = "0xABC", timestamp = 1L, channelId = "c",
            fileId = "f", fileName = "n", fileSize = 1L, fileType = "t",
            pieceCount = 1, pieceHashes = listOf("h")
        )
        assert(canonical.contains("\"sender\":\"0xabc\""))
    }

    @Test
    fun `control characters in the file name escape like JS`() {
        val canonical = Protocol.canonicalFileManifestData(
            id = "x", sender = "0xa", timestamp = 1L, channelId = "c",
            fileId = "f", fileName = "line\nbreak\ttab", fileSize = 1L, fileType = "t",
            pieceCount = 1, pieceHashes = emptyList()
        )
        assert(canonical.contains("\"fileName\":\"line\\nbreak\\ttab\""))
        assert(canonical.contains("\"pieceHashes\":[]"))
    }
}

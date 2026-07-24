package com.pombo.android.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageDeflateTest {

    private fun deflate(data: ByteArray, level: Int = StorageDeflate.LEVEL): ByteArray {
        val out = ByteArrayOutputStream()
        StorageDeflate.deflate(ByteArrayInputStream(data), out, level)
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        StorageDeflate.inflate(ByteArrayInputStream(data), out)
        return out.toByteArray()
    }

    @Test
    fun `deflate output is zlib format`() {
        // zlib header first byte is 0x78 (CMF); level 1 -> 0x7801.
        assertEquals(0x78.toByte(), deflate(ByteArray(500) { it.toByte() })[0])
    }

    @Test
    fun `round-trips text`() {
        val data = "The quick brown fox jumps over the lazy dog. ".repeat(20).toByteArray()
        assertArrayEquals(data, inflate(deflate(data)))
    }

    @Test
    fun `round-trips arbitrary bytes and reports compressed size`() {
        val data = ByteArray(200_000) { ((it * 7) xor (it shr 3)).toByte() }
        val out = ByteArrayOutputStream()
        val n = StorageDeflate.deflate(ByteArrayInputStream(data), out)
        assertEquals(out.size().toLong(), n)
        assertArrayEquals(data, inflate(out.toByteArray()))
    }

    @Test
    fun `round-trips empty input`() {
        assertArrayEquals(ByteArray(0), inflate(deflate(ByteArray(0))))
    }

    @Test
    fun `inflates a web (Node zlib) deflate stream`() {
        // zlib.deflateSync("The quick brown fox jumps over the lazy dog. ".repeat(20), {level:1})
        val nodeHex = "78010bc94855282ccd4cce56482aca2fcf5348cbaf50c82acd2d2856c82f4b2d5228" +
            "c94855c849acaa5448c94fd7530819553c1a1aa36983da39050047a5431c"
        val expected = "The quick brown fox jumps over the lazy dog. ".repeat(20).toByteArray()
        assertArrayEquals(expected, inflate(StorageHttp.hexToBytes(nodeHex)))
    }
}

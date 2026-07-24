package com.pombo.android.core

import java.io.Reader
import java.io.StringReader
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageHttpTest {

    /** Forces the parser across buffer boundaries: one character per read. */
    private class OneAtATimeReader(s: String) : Reader() {
        private val inner = StringReader(s)
        override fun read(cbuf: CharArray, off: Int, len: Int): Int =
            if (len == 0) 0 else inner.read(cbuf, off, 1)
        override fun close() = inner.close()
    }

    private fun parse(s: String, reader: Reader = StringReader(s)): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        StorageHttp.parseStorageArray(reader) { out.add(it) }
        return out
    }

    // ================= hexToBytes =================

    @Test
    fun `hexToBytes decodes lower, upper and digit nibbles`() {
        assertArrayEquals(
            byteArrayOf(0x00, 0xff.toByte(), 0x0a, 0xA0.toByte(), 0x39),
            StorageHttp.hexToBytes("00FF0aA039")
        )
    }

    @Test
    fun `hexToBytes on empty is empty`() {
        assertEquals(0, StorageHttp.hexToBytes("").size)
    }

    @Test
    fun `hexToBytes round-trips arbitrary bytes`() {
        val bytes = ByteArray(256) { it.toByte() }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        assertArrayEquals(bytes, StorageHttp.hexToBytes(hex))
    }

    // ================= parseStorageArray =================

    @Test
    fun `empty array emits nothing`() {
        assertEquals(0, parse("[]").size)
    }

    @Test
    fun `whitespace-only body is tolerated as empty`() {
        assertEquals(0, parse("   \n\t ").size)
    }

    @Test
    fun `emits each row object with fields intact`() {
        val rows = parse("""[{"timestamp":10,"publisherId":"0xAbC"},{"timestamp":20,"publisherId":"0xdef"}]""")
        assertEquals(2, rows.size)
        assertEquals(10L, rows[0].getLong("timestamp"))
        assertEquals("0xAbC", rows[0].getString("publisherId"))
        assertEquals(20L, rows[1].getLong("timestamp"))
    }

    @Test
    fun `braces and brackets inside a string do not corrupt depth`() {
        val rows = parse("""[{"content":"}{[]}}","timestamp":5}]""")
        assertEquals(1, rows.size)
        assertEquals("}{[]}}", rows[0].getString("content"))
        assertEquals(5L, rows[0].getLong("timestamp"))
    }

    @Test
    fun `escaped quotes inside a string are handled`() {
        val rows = parse("""[{"a":"he said \"hi\" }","timestamp":1}]""")
        assertEquals(1, rows.size)
        assertEquals("he said \"hi\" }", rows[0].getString("a"))
    }

    @Test
    fun `parsing survives one-char-at-a-time reads across boundaries`() {
        val json = """[{"timestamp":1,"content":"ab}cd"},{"timestamp":2},{"timestamp":3}]"""
        val rows = parse(json, OneAtATimeReader(json))
        assertEquals(3, rows.size)
        assertEquals(1L, rows[0].getLong("timestamp"))
        assertEquals("ab}cd", rows[0].getString("content"))
        assertEquals(3L, rows[2].getLong("timestamp"))
    }

    @Test
    fun `newlines and spaces between rows are ignored`() {
        val rows = parse("[\n  {\"timestamp\":1},\n  {\"timestamp\":2}\n]")
        assertEquals(2, rows.size)
    }

    @Test
    fun `a large binary-style row parses as one object`() {
        val hex = "ab".repeat(240 * 1024) // ~240KB hex content, one row
        val rows = parse("""[{"timestamp":99,"contentType":1,"content":"$hex"}]""")
        assertEquals(1, rows.size)
        assertEquals(99L, rows[0].getLong("timestamp"))
        assertEquals(1, rows[0].getInt("contentType"))
        // The content maps to bytes exactly as toRow would.
        assertTrue(StorageHttp.hexToBytes(rows[0].getString("content")).size == 240 * 1024)
    }

    @Test
    fun `malformed non-array body throws`() {
        assertThrows(IllegalStateException::class.java) { parse("not json at all") }
    }

    // ================= streamStorageRows (fast byte-level content reader) =================

    private fun rows(json: String): List<StorageHttp.Row> {
        val out = ArrayList<StorageHttp.Row>()
        StorageHttp.streamStorageRows(java.io.ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))) { out.add(it) }
        return out
    }

    @Test
    fun `streamStorageRows decodes hex content and fields`() {
        val r = rows("""[{"timestamp":10,"publisherId":"0xAbC","content":"00ff10a0","contentType":1}]""")
        assertEquals(1, r.size)
        assertEquals(10L, r[0].timestamp)
        assertEquals("0xAbC", r[0].publisherId)
        assertArrayEquals(byteArrayOf(0x00, 0xff.toByte(), 0x10, 0xA0.toByte()), r[0].content)
    }

    @Test
    fun `streamStorageRows nulls content when contentType is not 1`() {
        val r = rows("""[{"timestamp":1,"content":"deadbeef","contentType":0}]""")
        assertEquals(1, r.size)
        assertNull(r[0].content)
    }

    @Test
    fun `streamStorageRows works regardless of content or contentType order`() {
        assertArrayEquals(byteArrayOf(0xaa.toByte()), rows("""[{"content":"aa","contentType":1}]""")[0].content)
        assertArrayEquals(byteArrayOf(0xaa.toByte()), rows("""[{"contentType":1,"content":"aa"}]""")[0].content)
    }

    @Test
    fun `streamStorageRows skips extra scalar, nested and null fields`() {
        val json = """[{"streamId":"a/b-1","streamPartition":2,"timestamp":5,"content":"cc",""" +
            """"contentType":1,"publisherId":null,"note":"he said \"hi\"","meta":{"a":1,"b":[1,2,{"c":3}]},"signature":"0xdead"}]"""
        val r = rows(json)
        assertEquals(1, r.size)
        assertEquals(5L, r[0].timestamp)
        assertNull(r[0].publisherId)
        assertArrayEquals(byteArrayOf(0xcc.toByte()), r[0].content)
    }

    @Test
    fun `streamStorageRows handles multiple rows and empty or whitespace bodies`() {
        val r = rows("""[{"timestamp":1,"content":"01","contentType":1},{"timestamp":2,"content":"02","contentType":1}]""")
        assertEquals(2, r.size)
        assertEquals(2L, r[1].timestamp)
        assertEquals(0, rows("[]").size)
        assertEquals(0, rows("   \n ").size)
    }

    @Test
    fun `streamStorageRows decodes a large content across buffer refills`() {
        // 200 KB of hex -> 100 KB of bytes, spanning several 64 KB ByteFeed reads.
        val hex = "ab".repeat(100 * 1024)
        val r = rows("""[{"timestamp":7,"contentType":1,"content":"$hex"}]""")
        assertEquals(1, r.size)
        assertEquals(100 * 1024, r[0].content!!.size)
        assertEquals(0xab.toByte(), r[0].content!![0])
        assertEquals(0xab.toByte(), r[0].content!![100 * 1024 - 1])
    }
}

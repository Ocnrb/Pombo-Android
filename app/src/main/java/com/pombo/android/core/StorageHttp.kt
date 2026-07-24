package com.pombo.android.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Direct HTTP reads against a storage node — native mirror of the web's
 * `directFetchRange`/`directFetchRangeMeta`/`directFetchLast` in storageMedia.js.
 *
 * These run natively (java.net, the app's existing HTTP stack — see GraphApi)
 * rather than through the WebView: shuttling tens of MB of chunk data across the
 * bridge for a plain HTTPS GET would be absurd. The DM path and any HTTP failure
 * fall back to SDK resend (through the bridge), handled by the caller.
 *
 * The node's range endpoint streams a top-level JSON array of message rows; a
 * single window can be tens of MB, so the body is parsed INCREMENTALLY
 * ([parseStorageArray]) and each row is emitted as it completes — waiting for the
 * whole body would freeze progress and hold the file in memory. Binary contents
 * arrive hex-encoded with `contentType: 1` and are decoded by [hexToBytes].
 */
object StorageHttp {

    /**
     * Per-read inactivity timeout (SocketTimeout if no bytes arrive for this
     * long) — the web's `directFetchTimeoutMs`. A single window can be tens of MB
     * over a slow link, so this is generous on purpose.
     */
    const val READ_TIMEOUT_MS = 120_000
    private const val CONNECT_TIMEOUT_MS = 20_000

    /** A decoded storage row. [content] is the hex-decoded bytes for a binary row (contentType 1), else null. */
    data class Row(val content: ByteArray?, val timestamp: Long, val publisherId: String?)

    /** Metadata-only row (no payload) from a `format=metadata` read. */
    data class MetaRow(val timestamp: Long, val publisherId: String?)

    /** Raised on HTTP 4xx/5xx so callers can tell a 400 (no metadata format) apart. */
    class HttpStatusException(val code: Int) : Exception("HTTP $code")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun rangeUrl(base: String, sid: String, partition: Int, fromT: Long, toT: Long, meta: Boolean): String {
        val b = base.trimEnd('/')
        val fmt = if (meta) "&format=metadata" else ""
        return "$b/streams/${enc(sid)}/data/partitions/$partition/range?fromTimestamp=$fromT&toTimestamp=$toT$fmt"
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

    /**
     * Streaming range read. Emits each row via [onRow] as it is parsed (return
     * value ignored — backpressure is the caller's job on the write side).
     * Binary rows (`contentType == 1`) arrive with [Row.content] hex-decoded.
     * @return the number of rows emitted.
     * @throws HttpStatusException on a non-2xx response.
     */
    suspend fun directFetchRange(
        base: String, sid: String, partition: Int, fromT: Long, toT: Long,
        onRow: (Row) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val conn = open(rangeUrl(base, sid, partition, fromT, toT, meta = false))
        try {
            val code = conn.responseCode
            if (code / 100 != 2) throw HttpStatusException(code)
            conn.inputStream.use { input -> streamStorageRows(input, onRow) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Metadata-only range read (Pombo-patched nodes): row metadata without the
     * payload hex — a verify sweep costs KBs instead of ~2x the file. Vanilla
     * nodes answer HTTP 400; the caller marks the node and falls back to a full read.
     * @throws HttpStatusException (code 400 on vanilla nodes).
     */
    suspend fun directFetchRangeMeta(
        base: String, sid: String, partition: Int, fromT: Long, toT: Long
    ): List<MetaRow> = withContext(Dispatchers.IO) {
        val conn = open(rangeUrl(base, sid, partition, fromT, toT, meta = true))
        try {
            val code = conn.responseCode
            if (code / 100 != 2) throw HttpStatusException(code)
            val out = ArrayList<MetaRow>()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                parseStorageArray(reader) { obj ->
                    out.add(MetaRow(obj.optLong("timestamp"), obj.optStringOrNull("publisherId")))
                }
            }
            out
        } finally {
            conn.disconnect()
        }
    }

    /** Last [count] rows on a partition (used to probe visibility). */
    suspend fun directFetchLast(
        base: String, sid: String, partition: Int, count: Int, onRow: (Row) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val b = base.trimEnd('/')
        val conn = open("$b/streams/${enc(sid)}/data/partitions/$partition/last?count=$count")
        try {
            val code = conn.responseCode
            if (code / 100 != 2) throw HttpStatusException(code)
            conn.inputStream.use { input -> streamStorageRows(input, onRow) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Incremental parser for a storage node's top-level JSON array of row
     * objects. Emits each row object via [onObject] the moment it closes, then
     * drops it from the buffer, so peak memory is ~one row (not the whole body).
     * A depth/string state machine — a plain [JSONObject] over the whole body
     * would buffer tens of MB. Port of the web scanner in `directFetchRange`.
     *
     * Tolerant of a non-array body only if it is empty/whitespace; malformed
     * non-empty input throws.
     * @return number of objects emitted.
     */
    fun parseStorageArray(reader: Reader, onObject: (JSONObject) -> Unit): Int {
        val buf = StringBuilder()
        var scan = 0
        var depth = 0
        var objStart = -1
        var inStr = false
        var esc = false
        var sawArray = false
        var n = 0

        val chunk = CharArray(1 shl 16)
        while (true) {
            val read = reader.read(chunk)
            if (read < 0) break
            buf.append(chunk, 0, read)
            while (scan < buf.length) {
                val ch = buf[scan]
                if (inStr) {
                    when {
                        esc -> esc = false
                        ch == '\\' -> esc = true
                        ch == '"' -> inStr = false
                    }
                    scan++
                    continue
                }
                if (depth == 0) {
                    when (ch) {
                        '{' -> { objStart = scan; depth = 1 }
                        '[' -> sawArray = true
                        ']', ',', ' ', '\n', '\r', '\t' -> { /* between rows */ }
                        else -> throw IllegalStateException("unexpected endpoint response")
                    }
                    scan++
                    continue
                }
                when (ch) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val obj = JSONObject(buf.substring(objStart, scan + 1))
                            n++
                            onObject(obj)
                            // Drop the consumed prefix so the buffer stays ~one row.
                            buf.delete(0, scan + 1)
                            scan = -1
                            objStart = -1
                        }
                    }
                }
                scan++
            }
        }
        if (!sawArray && n == 0 && buf.toString().trim().isNotEmpty()) {
            throw IllegalStateException("unexpected endpoint response")
        }
        return n
    }

    // ================= fast byte-level row reader (content path) =================

    /** Buffered single-byte cursor over an [InputStream]; [cur] is -1 at EOF. */
    private class ByteFeed(input: InputStream) {
        private val src = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)
        private val buf = ByteArray(1 shl 16)
        private var p = 0
        private var lim = 0
        var cur = 0
            private set
        fun advance() {
            if (p >= lim) {
                lim = src.read(buf); p = 0
                if (lim <= 0) { cur = -1; return }
            }
            cur = buf[p++].toInt() and 0xff
        }
        fun skipWs() {
            while (cur == ' '.code || cur == '\n'.code || cur == '\r'.code || cur == '\t'.code) advance()
        }
    }

    private fun hexVal(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }

    /** Reads a JSON string value (cur at the opening quote), feeding each raw byte to [consume]; leaves cur past the closing quote. */
    private inline fun readStringBytes(f: ByteFeed, consume: (Int) -> Unit) {
        f.advance() // past opening quote
        while (f.cur != -1 && f.cur != '"'.code) {
            if (f.cur == '\\'.code) { f.advance(); if (f.cur == -1) break; consume(f.cur); f.advance(); continue }
            consume(f.cur); f.advance()
        }
        if (f.cur == '"'.code) f.advance()
    }

    private fun readStringInto(f: ByteFeed, out: ByteArray): Int {
        var len = 0
        readStringBytes(f) { if (len < out.size) out[len++] = it.toByte() }
        return len
    }

    private fun readStringValue(f: ByteFeed): String {
        val sb = StringBuilder()
        readStringBytes(f) { sb.append(it.toChar()) }
        return sb.toString()
    }

    /** Reads a hex JSON string value straight into bytes (no intermediate string). */
    private fun readHexValue(f: ByteFeed): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        var hi = -1
        readStringBytes(f) {
            val v = hexVal(it)
            if (v >= 0) { if (hi < 0) hi = v else { out.write((hi shl 4) or v); hi = -1 } }
        }
        return out.toByteArray()
    }

    private fun readLongValue(f: ByteFeed): Long {
        var v = 0L; var neg = false
        if (f.cur == '-'.code) { neg = true; f.advance() }
        while (f.cur in '0'.code..'9'.code) { v = v * 10 + (f.cur - '0'.code); f.advance() }
        // Tolerate a fractional/exponent tail (timestamps are integers, but be safe).
        while (f.cur == '.'.code || f.cur == 'e'.code || f.cur == 'E'.code || f.cur == '+'.code || f.cur == '-'.code || f.cur in '0'.code..'9'.code) f.advance()
        return if (neg) -v else v
    }

    private fun skipValue(f: ByteFeed) {
        when (f.cur) {
            '"'.code -> readStringBytes(f) { }
            '{'.code, '['.code -> {
                var depth = 0; var inStr = false
                while (f.cur != -1) {
                    val c = f.cur
                    if (inStr) {
                        if (c == '\\'.code) { f.advance(); if (f.cur != -1) f.advance(); continue }
                        if (c == '"'.code) inStr = false
                    } else {
                        if (c == '"'.code) inStr = true
                        else if (c == '{'.code || c == '['.code) depth++
                        else if (c == '}'.code || c == ']'.code) { depth--; if (depth == 0) { f.advance(); return } }
                    }
                    f.advance()
                }
            }
            else -> while (f.cur != -1 && f.cur != ','.code && f.cur != '}'.code && f.cur != ']'.code &&
                f.cur != ' '.code && f.cur != '\n'.code && f.cur != '\r'.code && f.cur != '\t'.code) f.advance()
        }
    }

    private fun keyIs(key: ByteArray, len: Int, name: String): Boolean {
        if (len != name.length) return false
        for (i in 0 until len) if ((key[i].toInt() and 0xff) != name[i].code) return false
        return true
    }

    /**
     * Fast byte-level streaming reader for a storage node's top-level JSON array
     * of row objects — the CONTENT path (directFetchRange/directFetchLast). It
     * decodes the hex `content` field DIRECTLY into bytes as it reads, never
     * materialising the ~480 KB hex string per chunk nor a [JSONObject]. The
     * generic [parseStorageArray] (char scan + `JSONObject` parse + [hexToBytes] =
     * ~3 passes over the hex per row) capped downloads at a few MB/s; this keeps
     * the parse off the critical path so the network stays the limit.
     *
     * Only timestamp / publisherId / contentType / content are extracted; every
     * other field is skipped. content is hex-decoded and only kept when
     * `contentType == 1` (our chunk rows always are), matching the web's gate;
     * a foreign non-hex row yields garbage that the caller's unpack step discards.
     * Tolerates an empty/whitespace body; malformed non-empty input throws.
     * @return number of rows emitted.
     */
    fun streamStorageRows(input: InputStream, onRow: (Row) -> Unit): Int {
        val f = ByteFeed(input)
        f.advance()
        f.skipWs()
        if (f.cur == -1) return 0
        if (f.cur == '['.code) { f.advance(); f.skipWs() } else if (f.cur != '{'.code) {
            throw IllegalStateException("unexpected endpoint response")
        }
        val key = ByteArray(64)
        var n = 0
        while (f.cur != -1 && f.cur != ']'.code) {
            if (f.cur == ','.code) { f.advance(); f.skipWs(); continue }
            if (f.cur != '{'.code) throw IllegalStateException("unexpected endpoint response")
            f.advance(); f.skipWs() // past '{'
            var ts = 0L
            var pub: String? = null
            var content: ByteArray? = null
            var contentType = 1
            while (f.cur != -1 && f.cur != '}'.code) {
                if (f.cur == ','.code) { f.advance(); f.skipWs(); continue }
                if (f.cur != '"'.code) throw IllegalStateException("unexpected endpoint response")
                val kl = readStringInto(f, key)
                f.skipWs()
                if (f.cur != ':'.code) throw IllegalStateException("unexpected endpoint response")
                f.advance(); f.skipWs()
                when {
                    keyIs(key, kl, "content") -> if (f.cur == '"'.code) content = readHexValue(f) else skipValue(f)
                    keyIs(key, kl, "timestamp") -> ts = readLongValue(f)
                    keyIs(key, kl, "contentType") -> contentType = readLongValue(f).toInt()
                    keyIs(key, kl, "publisherId") -> if (f.cur == '"'.code) pub = readStringValue(f) else skipValue(f)
                    else -> skipValue(f)
                }
                f.skipWs()
            }
            if (f.cur == '}'.code) f.advance()
            f.skipWs()
            onRow(Row(if (contentType == 1) content else null, ts, pub))
            n++
        }
        return n
    }

    /**
     * Fast hex -> bytes (storage-node HTTP returns binary contents hex-encoded).
     * Port of the web `hexToU8` bit trick, which handles 0-9/a-f/A-F without a
     * table. An odd-length or non-hex string yields garbage bytes — callers wrap
     * this in runCatching and skip a row that fails to unpack anyway.
     */
    fun hexToBytes(hex: String): ByteArray {
        val n = hex.length / 2
        val out = ByteArray(n)
        var j = 0
        for (i in 0 until n) {
            val hi = hex[j].code
            val lo = hex[j + 1].code
            val b = (((hi and 0xf) + ((hi shr 6) * 9)) shl 4) or ((lo and 0xf) + ((lo shr 6) * 9))
            out[i] = b.toByte()
            j += 2
        }
        return out
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key)
}

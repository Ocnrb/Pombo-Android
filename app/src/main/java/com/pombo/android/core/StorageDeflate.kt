package com.pombo.android.core

import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Streaming compression for the storage-file transport.
 *
 * The web compresses with `CompressionStream('deflate')`, which is the **zlib**
 * format (2-byte header + Adler-32 trailer, NOT raw deflate). `Deflater`/
 * `Inflater` with the default `nowrap = false` produce and consume that same
 * zlib format, so an Android upload decompresses on the web and vice-versa.
 *
 * Level 1 (BEST_SPEED) on upload — the brief's choice: near-raw throughput, and
 * the payoff of higher levels is small for the compressible types that reach
 * here (media/archives are skipped entirely by shouldCompress).
 */
object StorageDeflate {

    /** Deflater.BEST_SPEED. Wire-compatible with any zlib inflater regardless of level. */
    const val LEVEL = Deflater.BEST_SPEED

    private const val BUF = 1 shl 16

    /** ~8 MB inflate batches: one tiny write per inflate emission is what made the web's extract slower than its download. */
    private const val INFLATE_BATCH = 8 * 1024 * 1024

    private class CountingOutputStream(out: OutputStream, val onCount: (Long) -> Unit) : FilterOutputStream(out) {
        var count = 0L
            private set
        override fun write(b: Int) { out.write(b); count++; onCount(count) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len; onCount(count) }
    }

    /**
     * Deflate [input] into [out] (zlib format). Closes [out] when done (it wraps
     * a staging file). [onProgress] reports UNCOMPRESSED bytes consumed;
     * [onCommitted] reports cumulative COMPRESSED bytes written (so a pipelined
     * publisher can consume the compressed prefix while the rest still deflates).
     * For [onCommitted] to reflect readable bytes, [out] must NOT be buffered
     * above the file (pass a raw FileOutputStream).
     * @return the compressed byte count.
     */
    fun deflate(
        input: InputStream,
        out: OutputStream,
        level: Int = LEVEL,
        onCommitted: (Long) -> Unit = {},
        onProgress: (Long) -> Unit = {}
    ): Long {
        val counting = CountingOutputStream(out, onCommitted)
        val deflater = Deflater(level, false) // false = zlib wrapper, matching the web
        try {
            DeflaterOutputStream(counting, deflater, BUF).use { dos ->
                val buf = ByteArray(BUF)
                var total = 0L
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    if (read > 0) {
                        dos.write(buf, 0, read)
                        total += read
                        onProgress(total)
                    }
                }
            }
        } finally {
            deflater.end()
        }
        return counting.count
    }

    /**
     * Inflate [input] (zlib format) into [out], writing in ~8 MB batches.
     * Does NOT close [out] (the caller owns the destination). [onProgress]
     * reports UNCOMPRESSED bytes produced.
     */
    fun inflate(input: InputStream, out: OutputStream, onProgress: (Long) -> Unit = {}) {
        val inflater = Inflater(false)
        try {
            val iis = InflaterInputStream(input, inflater, BUF)
            val buf = ByteArray(INFLATE_BATCH)
            var total = 0L
            while (true) {
                val read = iis.read(buf)
                if (read < 0) break
                if (read > 0) {
                    out.write(buf, 0, read)
                    total += read
                    onProgress(total)
                }
            }
        } finally {
            inflater.end()
        }
    }
}

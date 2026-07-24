package com.pombo.android.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * On-disk storage for one file being transferred.
 *
 * DELIBERATE DIVERGENCE FROM THE WEB. The web keeps every piece as its own
 * IndexedDB row keyed `[fileId, pieceIndex]` and concatenates them into a Blob
 * when the last one lands. Here a piece is written straight into a SPARSE FILE
 * at `index * PIECE_SIZE`, so:
 *
 *   - assembly is free — the file already IS the assembled result, and the
 *     "assemble" step is a rename rather than a second full-size copy;
 *   - a 500 MB transfer never needs 500 MB of memory to finish, which on a
 *     phone is the difference between working and being killed;
 *   - resume is automatic, because the bytes are already where they belong;
 *   - seeding reads out of the same file, so a completed download can serve
 *     without duplicating anything.
 *
 * Completion is tracked in a side bitmap file rather than inferred from the
 * data: a sparse file reads as zeros where nothing was written, which is
 * indistinguishable from a piece that is legitimately all zeros.
 *
 * All methods are safe to call from several threads — pieces arrive on the
 * WebView binder thread and a seeder may be reading while a leecher writes.
 * A single RandomAccessFile cannot be shared across a seek+write pair without
 * that lock.
 */
class PieceStore private constructor(
    val fileId: String,
    val fileSize: Long,
    val pieceCount: Int,
    private val dataFile: File,
    private val bitsFile: File
) : Closeable {

    private val lock = Any()
    private var data: RandomAccessFile? = RandomAccessFile(dataFile, "rw")
    private var bits: RandomAccessFile? = RandomAccessFile(bitsFile, "rw")

    /** In-memory mirror of the bitmap, so `has`/`completed` never touch disk. */
    private val present = ByteArray(bitmapBytes(pieceCount))

    init {
        synchronized(lock) {
            data!!.setLength(fileSize)
            val existing = bits!!
            if (existing.length() < present.size) {
                existing.setLength(present.size.toLong())
            } else {
                existing.seek(0)
                existing.readFully(present)
            }
        }
    }

    fun hasPiece(index: Int): Boolean {
        if (index < 0 || index >= pieceCount) return false
        synchronized(lock) { return present[index / 8].toInt() and (1 shl (index % 8)) != 0 }
    }

    /** Number of pieces already on disk. */
    fun completedPieces(): Int {
        synchronized(lock) { return present.sumOf { Integer.bitCount(it.toInt() and 0xff) } }
    }

    /**
     * Bytes already stored. Derived from the piece count rather than by walking
     * every index: this is read on each arriving piece to drive the progress
     * bar, and a per-piece scan holding the lock made it O(n) per piece — O(n²)
     * over a transfer, for a number that is exactly computable.
     *
     * Every piece is PIECE_SIZE except the last, which may be short.
     */
    fun bytesOnDisk(): Long {
        val complete = completedPieces()
        if (complete == 0) return 0
        val lastIndex = pieceCount - 1
        return if (hasPiece(lastIndex)) {
            (complete - 1).toLong() * MediaConfig.PIECE_SIZE +
                MediaConfig.pieceLength(fileSize, lastIndex)
        } else {
            complete.toLong() * MediaConfig.PIECE_SIZE
        }
    }

    fun isComplete(): Boolean = completedPieces() == pieceCount

    /** Indices still missing, in order — the work list a download draws from. */
    fun missingPieces(): List<Int> = (0 until pieceCount).filter { !hasPiece(it) }

    /**
     * Verifies [bytes] against [expectedHashHex] and writes it at its offset.
     *
     * Verification happens here, before the write, so a failing piece is never
     * written. Returns false on a length or hash mismatch, which the caller
     * turns into a strike against the seeder.
     */
    fun writePiece(index: Int, bytes: ByteArray, expectedHashHex: String?): Boolean {
        if (index < 0 || index >= pieceCount) return false
        val expectedLength = MediaConfig.pieceLength(fileSize, index)
        // A wrong length is not just an integrity failure — writing it would
        // shift nothing (the offset is fixed) but would leave a short piece
        // padded with stale bytes, or overrun into the next piece's space.
        if (bytes.size != expectedLength) return false
        if (expectedHashHex != null && !sha256Hex(bytes).equals(expectedHashHex, ignoreCase = true)) {
            return false
        }

        synchronized(lock) {
            val d = data ?: return false
            val b = bits ?: return false
            d.seek(index.toLong() * MediaConfig.PIECE_SIZE)
            d.write(bytes)
            // The bitmap is updated only AFTER the data is down. The other order
            // would let a crash between the two leave a piece marked present
            // whose bytes were never written — silent corruption that survives
            // every later integrity check, because nothing re-hashes what it
            // believes it already has.
            val byteIndex = index / 8
            present[byteIndex] = (present[byteIndex].toInt() or (1 shl (index % 8))).toByte()
            b.seek(byteIndex.toLong())
            b.write(present[byteIndex].toInt())
        }
        return true
    }

    /** Piece [index] for serving, or null if this device does not hold it. */
    fun readPiece(index: Int): ByteArray? {
        if (!hasPiece(index)) return null
        val length = MediaConfig.pieceLength(fileSize, index)
        if (length == 0) return null
        val out = ByteArray(length)
        synchronized(lock) {
            val d = data ?: return null
            d.seek(index.toLong() * MediaConfig.PIECE_SIZE)
            d.readFully(out)
        }
        return out
    }

    /**
     * Streams the completed file to [sink] WITHOUT consuming it.
     *
     * This is what saving uses, rather than [finish]: a rename would move the
     * only copy away and stop this device seeding a file it just finished.
     * Streamed rather than read into memory so a 500 MB file does not have to
     * fit in RAM to be saved.
     */
    @Throws(IOException::class)
    fun exportTo(sink: java.io.OutputStream) {
        if (!isComplete()) {
            throw IOException(
                "refusing to export $fileId: ${completedPieces()}/$pieceCount pieces present"
            )
        }
        val buffer = ByteArray(64 * 1024)
        var position = 0L
        while (position < fileSize) {
            val want = minOf(buffer.size.toLong(), fileSize - position).toInt()
            synchronized(lock) {
                val d = data ?: throw IOException("store is closed")
                d.seek(position)
                d.readFully(buffer, 0, want)
            }
            sink.write(buffer, 0, want)
            position += want
        }
        sink.flush()
    }

    /**
     * Promotes the completed download to [destination] and drops the bitmap.
     *
     * Refuses to publish an incomplete file: without this a transfer that lost
     * its last piece would hand over a plausible-looking file, sparse-filled
     * with zeros exactly where the missing pieces were.
     */
    @Throws(IOException::class)
    fun finish(destination: File): File {
        if (!isComplete()) {
            throw IOException(
                "refusing to finish $fileId: ${completedPieces()}/$pieceCount pieces present"
            )
        }
        close()
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()
        if (!dataFile.renameTo(destination)) {
            // Different filesystem (rename cannot cross one) — fall back to a copy.
            dataFile.copyTo(destination, overwrite = true)
            dataFile.delete()
        }
        bitsFile.delete()
        return destination
    }

    /** Abandons the transfer and reclaims its disk. */
    fun discard() {
        close()
        dataFile.delete()
        bitsFile.delete()
    }

    override fun close() {
        synchronized(lock) {
            try { data?.close() } catch (e: IOException) { /* closing anyway */ }
            try { bits?.close() } catch (e: IOException) { /* closing anyway */ }
            data = null
            bits = null
        }
    }

    companion object {

        private fun bitmapBytes(pieceCount: Int) = (pieceCount + 7) / 8

        /**
         * Opens (or resumes) storage for a transfer.
         *
         * [dir] must live under `filesDir`, never `cacheDir`: the system may
         * evict a cache directory at any moment, and a half-evicted transfer
         * would resume against a file whose bitmap claims pieces that are gone.
         */
        @Throws(IOException::class)
        fun open(dir: File, fileId: String, fileSize: Long): PieceStore {
            require(fileSize > 0) { "fileSize must be positive, was $fileSize" }
            // A file id names two files on disk, so anything that could escape
            // the directory or collide has to be rejected rather than sanitised
            // — a sanitised id would no longer match the one on the wire.
            require(fileId.isNotEmpty() && fileId.all { it.isLetterOrDigit() || it == '-' }) {
                "unsafe fileId: $fileId"
            }
            dir.mkdirs()
            return PieceStore(
                fileId = fileId,
                fileSize = fileSize,
                pieceCount = MediaConfig.pieceCount(fileSize),
                dataFile = File(dir, "$fileId.part"),
                bitsFile = File(dir, "$fileId.bits")
            )
        }

        /** Removes a transfer's files from disk. For expired seeds — never call
         *  while a store for this fileId is open. */
        fun delete(dir: File, fileId: String) {
            File(dir, "$fileId.part").delete()
            File(dir, "$fileId.bits").delete()
        }

        /** Lowercase hex SHA-256 — the form the web's pieceHashes use. */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val out = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xff
                out.append("0123456789abcdef"[v ushr 4])
                out.append("0123456789abcdef"[v and 0x0f])
            }
            return out.toString()
        }
    }
}

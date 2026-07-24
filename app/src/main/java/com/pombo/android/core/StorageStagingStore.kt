package com.pombo.android.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Sparse-file + bitmap staging for a storage-transport download — the same
 * pattern as the mesh [PieceStore], parameterized for storage chunks.
 *
 * Differences from [PieceStore]:
 *  - chunk size is [chunkDataSize] (the announce's `chunkDataSize`), not the
 *    fixed mesh PIECE_SIZE, and the staged length is the file's COMPRESSED size;
 *  - there is NO per-chunk hash: integrity comes from AES-GCM on sealed channels
 *    (a tampered chunk fails to open) — public channels carry no integrity, same
 *    as the web. So [writeChunk] verifies only the LENGTH, never a digest.
 *
 * The staged file holds the compressed bytes (chunk i at `i * chunkDataSize`);
 * the caller inflates it to the final file on completion when `compression` is
 * set. Completion lives in a side bitmap so a sparse zero region is never
 * mistaken for a legitimately-zero chunk, and the bitmap is written AFTER the
 * data so a crash between them cannot mark a chunk present whose bytes are gone.
 */
class StorageStagingStore private constructor(
    val transferId: String,
    /** Compressed (staged) size — the size of the file on disk. */
    val stagedSize: Long,
    val chunkDataSize: Int,
    val totalChunks: Int,
    private val dataFile: File,
    private val bitsFile: File
) : Closeable {

    private val lock = Any()
    private var data: RandomAccessFile? = RandomAccessFile(dataFile, "rw")
    private var bits: RandomAccessFile? = RandomAccessFile(bitsFile, "rw")
    private val present = ByteArray((totalChunks + 7) / 8)

    init {
        synchronized(lock) {
            data!!.setLength(stagedSize)
            val b = bits!!
            if (b.length() < present.size) b.setLength(present.size.toLong())
            else { b.seek(0); b.readFully(present) }
        }
    }

    /** Expected length of chunk [index]: chunkDataSize, or a short final chunk. */
    fun chunkLength(index: Int): Int {
        val start = index.toLong() * chunkDataSize
        if (start >= stagedSize) return 0
        return minOf(chunkDataSize.toLong(), stagedSize - start).toInt()
    }

    fun hasChunk(index: Int): Boolean {
        if (index < 0 || index >= totalChunks) return false
        synchronized(lock) { return present[index / 8].toInt() and (1 shl (index % 8)) != 0 }
    }

    fun completedChunks(): Int {
        synchronized(lock) { return present.sumOf { Integer.bitCount(it.toInt() and 0xff) } }
    }

    fun isComplete(): Boolean = completedChunks() == totalChunks

    fun missingChunks(): List<Int> = (0 until totalChunks).filter { !hasChunk(it) }

    /**
     * Writes [bytes] at chunk [index]'s offset. Returns false on a wrong length
     * (which would overrun the next chunk or leave stale padding) — the caller
     * skips the row. Bitmap updated only after the data is down.
     */
    fun writeChunk(index: Int, bytes: ByteArray): Boolean {
        if (index < 0 || index >= totalChunks) return false
        if (bytes.size != chunkLength(index)) return false
        synchronized(lock) {
            val d = data ?: return false
            val b = bits ?: return false
            d.seek(index.toLong() * chunkDataSize)
            d.write(bytes)
            val byteIndex = index / 8
            present[byteIndex] = (present[byteIndex].toInt() or (1 shl (index % 8))).toByte()
            b.seek(byteIndex.toLong())
            b.write(present[byteIndex].toInt())
        }
        return true
    }

    /** Streams the staged (compressed) bytes to [sink] without consuming them. */
    @Throws(IOException::class)
    fun exportTo(sink: OutputStream) {
        if (!isComplete()) throw IOException("refusing to export $transferId: ${completedChunks()}/$totalChunks chunks")
        val buf = ByteArray(64 * 1024)
        var pos = 0L
        while (pos < stagedSize) {
            val want = minOf(buf.size.toLong(), stagedSize - pos).toInt()
            synchronized(lock) {
                val d = data ?: throw IOException("store is closed")
                d.seek(pos); d.readFully(buf, 0, want)
            }
            sink.write(buf, 0, want)
            pos += want
        }
        sink.flush()
    }

    /** The staged file itself (compression=none → this IS the download). */
    fun stagedFile(): File = dataFile

    fun discard() {
        close()
        dataFile.delete()
        bitsFile.delete()
    }

    override fun close() {
        synchronized(lock) {
            try { data?.close() } catch (e: IOException) { /* closing anyway */ }
            try { bits?.close() } catch (e: IOException) { /* closing anyway */ }
            data = null; bits = null
        }
    }

    companion object {
        /**
         * Opens (or resumes) staging for a transfer. [dir] must be under filesDir.
         * A resumed store whose file/params changed is the caller's problem to
         * detect (compare stagedSize) — here we just honour the on-disk bitmap.
         */
        @Throws(IOException::class)
        fun open(dir: File, transferId: String, stagedSize: Long, chunkDataSize: Int, totalChunks: Int): StorageStagingStore {
            require(stagedSize > 0) { "stagedSize must be positive, was $stagedSize" }
            require(chunkDataSize > 0) { "chunkDataSize must be positive, was $chunkDataSize" }
            require(totalChunks > 0) { "totalChunks must be positive, was $totalChunks" }
            require(transferId.isNotEmpty() && transferId.all { it.isLetterOrDigit() || it == '-' }) { "unsafe transferId: $transferId" }
            dir.mkdirs()
            return StorageStagingStore(
                transferId, stagedSize, chunkDataSize, totalChunks,
                File(dir, "psf_$transferId.spart"),
                File(dir, "psf_$transferId.sbits")
            )
        }

        fun delete(dir: File, transferId: String) {
            File(dir, "psf_$transferId.spart").delete()
            File(dir, "psf_$transferId.sbits").delete()
        }
    }
}

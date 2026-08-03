package com.pombo.android.core

import org.json.JSONObject

/**
 * Chunk wire format for Persistent File Sharing (storage-node transport) —
 * byte-compatible with the web (storageMedia.js packChunkPayload /
 * unpackChunkPayload, the `binary_file_chunked` v2 payload).
 *
 * Packed payload, BEFORE sealing:
 *   `[4B metaLen BE][metaLen bytes meta JSON][4B totalChunks BE][4B chunkIndex BE][data]`
 *
 * The meta JSON is the SAME object in every chunk of a transfer. Sealed channels
 * wrap the WHOLE packed payload ([12B iv][ct+tag]) so transferId/index leak
 * nothing — see [PomboCrypto.encryptBinaryWithKey]. Big-endian throughout,
 * matching the web's `DataView.setUint32(..., false)`.
 *
 * Only [chunkMetaJson]'s `transferId` is load-bearing on the receive side (it is
 * matched against the announce); every other field is informational — the real
 * fileName/compression/sizes come from the announce, not the chunk meta.
 */
object StorageWire {

    const val CHUNK_TYPE = "binary_file_chunked"
    const val CHUNK_VERSION = 2

    /** 240 KB — the browser SCTP maxMessageSize ceiling; kept for web interop. */
    const val MAX_WIRE_CHUNK = 240 * 1024

    /** [12B iv] + 16B GCM tag — sealing overhead that comes OUT of the chunk budget. */
    const val SEAL_OVERHEAD = 28

    /**
     * Build the per-transfer chunk meta JSON bytes. Field order is irrelevant for
     * interop (the receiver JSON-parses it; nothing hashes it), unlike the signed
     * announce. `compressedSize` here is the worst-case estimate when compression
     * is pipelined — the true value travels in the announce.
     */
    fun chunkMetaJson(
        fileName: String,
        fileType: String,
        originalSize: Long,
        compressedSize: Long,
        compression: String,
        transferId: String,
        timestamp: Long
    ): ByteArray {
        val o = JSONObject()
        o.put("type", CHUNK_TYPE)
        o.put("version", CHUNK_VERSION)
        o.put("fileName", fileName)
        o.put("fileType", fileType)
        o.put("originalSize", originalSize)
        o.put("compressedSize", compressedSize)
        o.put("compression", compression)
        o.put("transferId", transferId)
        o.put("timestamp", timestamp)
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Pack a chunk payload:
     * `[4B metaLen][meta][4B totalChunks][4B chunkIndex][data]`.
     */
    fun packChunkPayload(metaBytes: ByteArray, totalChunks: Int, chunkIndex: Int, data: ByteArray): ByteArray {
        require(totalChunks >= 0) { "totalChunks must not be negative, was $totalChunks" }
        require(chunkIndex >= 0) { "chunkIndex must not be negative, was $chunkIndex" }
        val out = ByteArray(4 + metaBytes.size + 4 + 4 + data.size)
        var o = 0
        putU32BE(out, o, metaBytes.size); o += 4
        metaBytes.copyInto(out, o); o += metaBytes.size
        putU32BE(out, o, totalChunks); o += 4
        putU32BE(out, o, chunkIndex); o += 4
        data.copyInto(out, o)
        return out
    }

    /** A decoded chunk. Only the fields the receive side needs (rest is in the announce). */
    data class ParsedChunk(
        val transferId: String,
        val totalChunks: Int,
        val chunkIndex: Int,
        val chunkData: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedChunk) return false
            return transferId == other.transferId &&
                totalChunks == other.totalChunks &&
                chunkIndex == other.chunkIndex &&
                chunkData.contentEquals(other.chunkData)
        }

        override fun hashCode(): Int {
            var result = transferId.hashCode()
            result = 31 * result + totalChunks
            result = 31 * result + chunkIndex
            result = 31 * result + chunkData.contentHashCode()
            return result
        }
    }

    /**
     * Unpack a chunk payload, or null for anything that is not a well-formed
     * `binary_file_chunked` v2 payload (warm-up pings, foreign messages, a still
     * sealed buffer). Never throws — this runs on every row read from storage.
     */
    fun unpackChunkPayload(bin: ByteArray?): ParsedChunk? {
        if (bin == null || bin.size < 8) return null
        return try {
            val metaLenL = getU32BE(bin, 0)
            // metaLen must fit inside the buffer with room for the two trailing
            // uint32s; <= 0 or overflowing is a foreign/short payload.
            if (metaLenL <= 0 || metaLenL > bin.size - 4) return null
            val metaLen = metaLenL.toInt() // safe: bounded by bin.size above
            if (bin.size < 4 + metaLen + 8) return null
            val meta = JSONObject(String(bin, 4, metaLen, Charsets.UTF_8))
            if (meta.optString("type") != CHUNK_TYPE || meta.optInt("version", -1) != CHUNK_VERSION) return null
            val transferId = meta.optString("transferId", "")
            if (transferId.isEmpty()) return null

            val totalChunks = getU32BE(bin, 4 + metaLen)
            val chunkIndex = getU32BE(bin, 4 + metaLen + 4)
            // Rebuilt as Long guards against a uint32 above 2^31 decoding negative.
            if (totalChunks > Int.MAX_VALUE || chunkIndex > Int.MAX_VALUE) return null

            val data = bin.copyOfRange(4 + metaLen + 8, bin.size)
            ParsedChunk(transferId, totalChunks.toInt(), chunkIndex.toInt(), data)
        } catch (e: Exception) {
            null
        }
    }

    private fun putU32BE(out: ByteArray, at: Int, value: Int) {
        out[at] = (value ushr 24).toByte()
        out[at + 1] = (value ushr 16).toByte()
        out[at + 2] = (value ushr 8).toByte()
        out[at + 3] = value.toByte()
    }

    /** Reads a big-endian uint32 as a Long (so the top bit never sign-extends). */
    private fun getU32BE(buf: ByteArray, at: Int): Long =
        (buf[at].toLong() and 0xff shl 24) or
            (buf[at + 1].toLong() and 0xff shl 16) or
            (buf[at + 2].toLong() and 0xff shl 8) or
            (buf[at + 3].toLong() and 0xff)
}

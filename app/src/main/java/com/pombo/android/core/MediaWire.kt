package com.pombo.android.core

/**
 * Wire format for P2P file transfer — byte-compatible with the web
 * (media.js encodeFilePiece / decodeFilePiece and the media signal shapes).
 *
 * Two carriers, both on the ephemeral stream (-2), which has no storage:
 *   -2/P1 [EPH_MEDIA_SIGNALS]  JSON coordination: who has what, send me piece N
 *   -2/P2 [EPH_MEDIA_DATA]     raw bytes: the pieces themselves
 *
 * The split is not cosmetic. Signals are small and must keep flowing while a
 * transfer saturates the data partition; sharing one partition would put a
 * piece_request behind a 240 KB piece in the same publish queue.
 */
object MediaWire {

    /** First byte of every binary payload on -2/P2 (web BINARY_MSG_TYPE). */
    const val TYPE_FILE_PIECE: Byte = 0x01

    /**
     * A file id is a UUID string, so its UTF-8 form is exactly 36 bytes and the
     * header can be fixed-width. The web slices to 36 when encoding and reads
     * back 36 when decoding, with no length prefix — anything else desynchronises
     * the piece index and the payload.
     */
    const val FILE_ID_BYTES = 36

    /** 1 type + 36 file id + 4 piece index. Payload starts here. */
    const val PIECE_HEADER_BYTES = 1 + FILE_ID_BYTES + 4

    // Signal types on -2/P1. Values are wire constants shared with the web.
    const val SIGNAL_SOURCE_REQUEST = "source_request"
    const val SIGNAL_SOURCE_ANNOUNCE = "source_announce"
    const val SIGNAL_PIECE_REQUEST = "piece_request"

    /** A decoded piece off the wire. */
    data class FilePiece(val fileId: String, val pieceIndex: Int, val data: ByteArray) {
        // ByteArray uses identity equality, which would make two structurally
        // identical pieces compare unequal and quietly break any test or set
        // that relies on it.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FilePiece) return false
            return fileId == other.fileId &&
                pieceIndex == other.pieceIndex &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = fileId.hashCode()
            result = 31 * result + pieceIndex
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /**
     * Encodes a piece for -2/P2:
     * `[1B 0x01][36B fileId UTF-8][4B pieceIndex uint32 big-endian][N bytes data]`
     *
     * Big-endian because that is what the web's DataView.setUint32(…, false)
     * writes — the `false` there means "not little-endian".
     */
    fun encodeFilePiece(fileId: String, pieceIndex: Int, data: ByteArray): ByteArray {
        val idBytes = fileId.toByteArray(Charsets.UTF_8)
        require(idBytes.size == FILE_ID_BYTES) {
            "fileId must be $FILE_ID_BYTES UTF-8 bytes, was ${idBytes.size}"
        }
        require(pieceIndex >= 0) { "pieceIndex must not be negative, was $pieceIndex" }

        val out = ByteArray(PIECE_HEADER_BYTES + data.size)
        out[0] = TYPE_FILE_PIECE
        idBytes.copyInto(out, 1)
        out[37] = (pieceIndex ushr 24).toByte()
        out[38] = (pieceIndex ushr 16).toByte()
        out[39] = (pieceIndex ushr 8).toByte()
        out[40] = pieceIndex.toByte()
        data.copyInto(out, PIECE_HEADER_BYTES)
        return out
    }

    /**
     * Decodes a binary payload from -2/P2, or null if it is not a piece we
     * understand — an unknown leading byte, or a buffer too short to hold a
     * header. Returns null rather than throwing because this runs on every
     * message arriving on the partition, including from clients newer than us.
     */
    fun decodeBinaryMedia(buf: ByteArray?): FilePiece? {
        if (buf == null || buf.size < PIECE_HEADER_BYTES) return null
        if (buf[0] != TYPE_FILE_PIECE) return null

        val fileId = String(buf, 1, FILE_ID_BYTES, Charsets.UTF_8)
        // Rebuilt via Long so the top bit does not sign-extend: a uint32 index
        // above 2^31 would otherwise decode negative. Real files never reach
        // that many pieces, but a corrupt header can claim it.
        val pieceIndex = (
            (buf[37].toLong() and 0xff shl 24) or
                (buf[38].toLong() and 0xff shl 16) or
                (buf[39].toLong() and 0xff shl 8) or
                (buf[40].toLong() and 0xff)
            )
        if (pieceIndex > Int.MAX_VALUE) return null

        val data = buf.copyOfRange(PIECE_HEADER_BYTES, buf.size)
        return FilePiece(fileId, pieceIndex.toInt(), data)
    }
}

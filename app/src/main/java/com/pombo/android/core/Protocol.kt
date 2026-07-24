package com.pombo.android.core

import java.security.SecureRandom

/**
 * Canonical construction of Pombo messages — byte-compatible with the web
 * (identity.js): the signed hash is keccak256(utf8(canonical JSON)) and the JSON
 * canonical form must be IDENTICAL to JS JSON.stringify (key order and
 * escaping). So the string is built by hand, not with org.json (which
 * would escape '/' in '</' sequences).
 */
object Protocol {

    private val random = SecureRandom()

    /** 16 random bytes in hex — same as identityManager.generateMessageId(). */
    fun generateMessageId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Replica of identityManager.createMessageHash: the *content* to hash.
     * O bridge aplica keccak256 + personal_sign a esta string.
     * Fixed key order: protocol, version, id, text, sender, timestamp, channelId.
     */
    fun canonicalMessageData(id: String, text: String, sender: String, timestamp: Long, channelId: String): String {
        return buildString {
            append("{\"protocol\":\"POMBO\",\"version\":1,")
            append("\"id\":").append(jsString(id)).append(',')
            append("\"text\":").append(jsString(text)).append(',')
            append("\"sender\":").append(jsString(sender.lowercase())).append(',')
            append("\"timestamp\":").append(timestamp).append(',')
            append("\"channelId\":").append(jsString(channelId))
            append('}')
        }
    }

    /**
     * Canonical data for a chunked-image manifest (identity.js v2 hash).
     * Key order must be identical to the web's JSON.stringify.
     */
    fun canonicalImageManifestData(
        id: String, imageId: String, sender: String, timestamp: Long, channelId: String,
        originalMime: String, finalMime: String, finalSizeBytes: Int, chunkCount: Int,
        chunkHashes: List<String>, assembledSha256: String, qualityUsed: Double?,
        // These were hardcoded to false/null, which only worked for messages we
        // sent ourselves. A web client that preserved the original (PNG/GIF) or
        // converted the format signs the real values, so verification of those
        // manifests failed and the message showed a false "invalid signature".
        preservedOriginal: Boolean = false,
        convertedTo: String? = null
    ): String {
        val hashesArr = chunkHashes.joinToString(",") { jsString(it) }
        val quality = if (qualityUsed != null && qualityUsed.isFinite())
            // Number(qualityUsed.toFixed(3)) — drop trailing zeros like JS Number
            jsNumber(Math.round(qualityUsed * 1000) / 1000.0)
        else "null"
        return buildString {
            append("{\"protocol\":\"POMBO\",\"version\":2,\"type\":\"image\",\"transport\":\"chunked\",")
            append("\"id\":").append(jsString(id)).append(',')
            append("\"imageId\":").append(jsString(imageId)).append(',')
            append("\"sender\":").append(jsString(sender.lowercase())).append(',')
            append("\"timestamp\":").append(timestamp).append(',')
            append("\"channelId\":").append(jsString(channelId)).append(',')
            append("\"originalMime\":").append(jsString(originalMime)).append(',')
            append("\"finalMime\":").append(jsString(finalMime)).append(',')
            append("\"finalSizeBytes\":").append(finalSizeBytes).append(',')
            append("\"chunkCount\":").append(chunkCount).append(',')
            append("\"chunkHashes\":[").append(hashesArr).append("],")
            append("\"assembledSha256\":").append(jsString(assembledSha256)).append(',')
            append("\"preservedOriginal\":").append(if (preservedOriginal) "true" else "false").append(',')
            append("\"convertedTo\":").append(if (convertedTo == null) "null" else jsString(convertedTo)).append(',')
            append("\"qualityUsed\":").append(quality)
            append('}')
        }
    }

    /**
     * Canonical data for a P2P file manifest (identity.js createFileManifestHash).
     * Key order must be identical to the web's JSON.stringify. The piece hashes
     * are inside the signed data on purpose: every downloader verifies pieces
     * against them, so a signature that did not cover them would authenticate
     * nothing about the file.
     */
    fun canonicalFileManifestData(
        id: String, sender: String, timestamp: Long, channelId: String,
        fileId: String, fileName: String, fileSize: Long, fileType: String,
        pieceCount: Int, pieceHashes: List<String>
    ): String {
        val hashesArr = pieceHashes.joinToString(",") { jsString(it) }
        return buildString {
            append("{\"protocol\":\"POMBO\",\"version\":2,\"type\":\"file_announce\",")
            append("\"id\":").append(jsString(id)).append(',')
            append("\"sender\":").append(jsString(sender.lowercase())).append(',')
            append("\"timestamp\":").append(timestamp).append(',')
            append("\"channelId\":").append(jsString(channelId)).append(',')
            append("\"fileId\":").append(jsString(fileId)).append(',')
            append("\"fileName\":").append(jsString(fileName)).append(',')
            append("\"fileSize\":").append(fileSize).append(',')
            append("\"fileType\":").append(jsString(fileType)).append(',')
            append("\"pieceCount\":").append(pieceCount).append(',')
            append("\"pieceHashes\":[").append(hashesArr).append("]}")
        }
    }

    /**
     * Canonical data for a storage-file (storage_file_announce) manifest —
     * identity.js createStorageFileManifestHash. Key order must be IDENTICAL to
     * the web's JSON.stringify. Unlike the mesh manifest there are no piece
     * hashes (integrity comes from AES-GCM on sealed channels); transferId +
     * sizes + chunk layout + encSalt are signed so a forged announce cannot
     * redirect or mis-decrypt a download.
     *
     * Every metadata field the web renders with `?? null` is nullable here and
     * serializes to the literal `null` when absent (never omitted), so the two
     * ends hash the same bytes.
     */
    fun canonicalStorageFileManifestData(
        id: String, sender: String, timestamp: Long, channelId: String,
        transferId: String?, fileName: String?, fileType: String?,
        originalSize: Long?, compressedSize: Long?, compression: String?,
        totalChunks: Int?, chunkDataSize: Int?, chunkPartitions: Int?,
        firstChunkPartition: Int?, firstChunkTs: Long?, lastChunkTs: Long?,
        storedChunks: Int?, encSalt: String?
    ): String {
        return buildString {
            append("{\"protocol\":\"POMBO\",\"version\":1,\"type\":\"storage_file_announce\",")
            append("\"id\":").append(jsString(id)).append(',')
            append("\"sender\":").append(jsString(sender.lowercase())).append(',')
            append("\"timestamp\":").append(timestamp).append(',')
            append("\"channelId\":").append(jsString(channelId)).append(',')
            append("\"transferId\":").append(jsStringOrNull(transferId)).append(',')
            append("\"fileName\":").append(jsStringOrNull(fileName)).append(',')
            append("\"fileType\":").append(jsStringOrNull(fileType)).append(',')
            append("\"originalSize\":").append(originalSize?.toString() ?: "null").append(',')
            append("\"compressedSize\":").append(compressedSize?.toString() ?: "null").append(',')
            append("\"compression\":").append(jsStringOrNull(compression)).append(',')
            append("\"totalChunks\":").append(totalChunks?.toString() ?: "null").append(',')
            append("\"chunkDataSize\":").append(chunkDataSize?.toString() ?: "null").append(',')
            append("\"chunkPartitions\":").append(chunkPartitions?.toString() ?: "null").append(',')
            append("\"firstChunkPartition\":").append(firstChunkPartition?.toString() ?: "null").append(',')
            append("\"firstChunkTs\":").append(firstChunkTs?.toString() ?: "null").append(',')
            append("\"lastChunkTs\":").append(lastChunkTs?.toString() ?: "null").append(',')
            append("\"storedChunks\":").append(storedChunks?.toString() ?: "null").append(',')
            append("\"encSalt\":").append(jsStringOrNull(encSalt))
            append('}')
        }
    }

    private fun jsStringOrNull(s: String?): String = if (s == null) "null" else jsString(s)

    /** Renders a number like JS: integers without decimals, else minimal decimals. */
    private fun jsNumber(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /**
     * String escaping identical to JS JSON.stringify: escapes `"` and `\`,
     * controlo <0x20 como \b \t \n \f \r ou \u00XX. NÃO escapa '/' nem
     * non-ASCII characters (neither does JS).
     */
    fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\u000C' -> sb.append("\\f")
                '\r' -> sb.append("\\r")
                else -> {
                    if (ch < ' ') sb.append("\\u%04x".format(ch.code))
                    else sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

package com.pombo.android.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Image preparation for the chunked-image transport (v2), mirroring the web's
 * media.js decision tree:
 *
 *   1. already under the limit  → send untouched (preservedOriginal = true).
 *      This is what keeps PNG transparency and GIF animation intact;
 *   2. GIF over the limit       → refuse (cannot compress without losing frames);
 *   3. JPEG over the limit      → JPEG quality ladder, then WebP fallback;
 *   4. PNG/WebP over the limit  → WebP ladder.
 *
 * Chunking then splits whatever came out into payloads that stay under the
 * per-message ceiling.
 */
object MediaEncoder {

    private const val MAX_ASSEMBLED = 1 * 1024 * 1024        // config.media.imageMaxAssembledBytes
    private const val GIF_MAX_ASSEMBLED = 5 * 1024 * 1024    // config.media.imageGifMaxAssembledBytes
    /** Plain channels: 140KB raw → ~187KB base64 + JSON envelope < 220KB. */
    const val CHUNK_RAW = 140 * 1024

    /**
     * Sealed transports (DM ECDH envelope, password channels): the chunk JSON
     * is encrypted and base64'd AGAIN, a ×4/3 on top of the first — 140KB raw
     * came out at ~250KB on the wire, past the 220KB ceiling the web asserts
     * (media.js assertStoredImagePayloadFits). 96KB raw → ~128KB base64 →
     * ~129KB JSON → AES → ~172KB base64 envelope, with margin to spare.
     */
    const val CHUNK_RAW_SEALED = 96 * 1024

    /** config.media.imageResolutionScales — aligned with the web's ladder. */
    private val RESOLUTION_SCALES = listOf(1.0, 0.85, 0.72, 0.6, 0.5, 0.4)

    /** Web media.js ALLOWED_IMAGE_TYPES — anything else is refused up front
     *  instead of being silently re-encoded as JPEG. */
    private val ALLOWED_MIMES = setOf(
        "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif"
    )

    data class Encoded(
        val bytes: ByteArray,
        val mime: String,
        /** Null when the original was sent untouched, like the web. */
        val quality: Double?,
        val originalMime: String,
        val preservedOriginal: Boolean,
        val convertedTo: String?,
        val chunks: List<ByteArray>,
        val chunkHashes: List<String>,
        val assembledSha256: String
    )

    class UnsupportedImage(message: String) : Exception(message)

    private fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    /**
     * Framing of a square crop, web getSquareCropFrame: zoom 1 is the minimum
     * cover fit, centerX/centerY are in SOURCE-image pixels. The same math
     * drives the interactive preview and the final 512² encode so they stay
     * pixel-accurate at any viewport size.
     */
    data class CropFrame(
        val zoom: Float,
        val scale: Float,
        val centerX: Float,
        val centerY: Float,
        val minCenterX: Float,
        val maxCenterX: Float,
        val minCenterY: Float,
        val maxCenterY: Float,
        val drawWidth: Float,
        val drawHeight: Float,
        val offsetX: Float,
        val offsetY: Float
    )

    fun squareCropFrame(
        imageWidth: Int,
        imageHeight: Int,
        viewportSize: Float,
        zoom: Float = 1f,
        centerX: Float? = null,
        centerY: Float? = null
    ): CropFrame {
        require(imageWidth > 0 && imageHeight > 0 && viewportSize > 0f) { "Invalid crop frame dimensions" }
        val safeZoom = maxOf(0.5f, if (zoom.isFinite()) zoom else 1f)
        val scale = viewportSize / minOf(imageWidth, imageHeight) * safeZoom
        val halfViewportInImage = viewportSize / (2f * scale)

        val minCenterX = minOf(halfViewportInImage, imageWidth - halfViewportInImage)
        val maxCenterX = maxOf(halfViewportInImage, imageWidth - halfViewportInImage)
        val minCenterY = minOf(halfViewportInImage, imageHeight - halfViewportInImage)
        val maxCenterY = maxOf(halfViewportInImage, imageHeight - halfViewportInImage)

        val cx = (centerX ?: imageWidth / 2f).coerceIn(minCenterX, maxCenterX)
        val cy = (centerY ?: imageHeight / 2f).coerceIn(minCenterY, maxCenterY)

        return CropFrame(
            zoom = safeZoom,
            scale = scale,
            centerX = cx,
            centerY = cy,
            minCenterX = minCenterX,
            maxCenterX = maxCenterX,
            minCenterY = minCenterY,
            maxCenterY = maxCenterY,
            drawWidth = imageWidth * scale,
            drawHeight = imageHeight * scale,
            offsetX = viewportSize / 2f - cx * scale,
            offsetY = viewportSize / 2f - cy * scale
        )
    }

    /**
     * Renders the chosen framing into the square upload, web renderSquareCrop:
     * 512² JPEG q85, or PNG when the source has alpha to preserve. JPEG gets a
     * white baseline first so source transparency doesn't turn black.
     */
    fun renderSquareCrop(
        src: Bitmap,
        zoom: Float,
        centerX: Float,
        centerY: Float,
        preserveAlpha: Boolean,
        size: Int = 512,
        quality: Int = 85
    ): Pair<ByteArray, String> {
        val frame = squareCropFrame(src.width, src.height, size.toFloat(), zoom, centerX, centerY)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        if (!preserveAlpha) canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(
            src, null,
            android.graphics.RectF(
                frame.offsetX, frame.offsetY,
                frame.offsetX + frame.drawWidth, frame.offsetY + frame.drawHeight
            ),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        val bytes = ByteArrayOutputStream()
        return if (preserveAlpha) {
            out.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            bytes.toByteArray() to "image/png"
        } else {
            out.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
            bytes.toByteArray() to "image/jpeg"
        }
    }

    /**
     * Decodes a picked photo for the crop dialog: downsampled so a camera
     * original doesn't blow the heap, and rotated per EXIF — browsers apply
     * orientation automatically, BitmapFactory does not.
     */
    fun decodeForCrop(input: ByteArray, maxSide: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(input, 0, input.size, opts) ?: return null
        val rotation = try {
            val exif = android.media.ExifInterface(input.inputStream())
            when (exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        if (rotation == 0f) return bmp
        val m = android.graphics.Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /**
     * Prepares an image for sending. [originalMime] comes from the picker so we
     * can honour the format, exactly as the web does with `file.type`.
     */
    fun encode(
        input: ByteArray,
        originalMime: String = "image/jpeg",
        chunkRawBytes: Int = CHUNK_RAW
    ): Encoded? {
        val mime = originalMime.lowercase()
        if (mime !in ALLOWED_MIMES) {
            throw UnsupportedImage("Unsupported image type: $mime")
        }
        val isGif = mime == "image/gif"
        val limit = if (isGif) GIF_MAX_ASSEMBLED else MAX_ASSEMBLED

        // 1. Fits already: never re-encode. Preserves alpha and animation.
        if (input.size <= limit) {
            return finish(input, mime, null, mime, preservedOriginal = true, convertedTo = null, chunkRawBytes)
        }

        if (isGif) {
            throw UnsupportedImage(
                "GIF image exceeds ${GIF_MAX_ASSEMBLED / (1024 * 1024)}MB and cannot be compressed without losing animation"
            )
        }

        val src = BitmapFactory.decodeByteArray(input, 0, input.size) ?: return null
        // Quality floors from the web ladder (config.js: initial 1.0, min 0.68
        // for both codecs) — Android's old floor of 40 produced visibly worse
        // images than the same photo sent from the web.
        val encoded = when (mime) {
            "image/jpeg", "image/jpg" ->
                compressToTarget(src, Bitmap.CompressFormat.JPEG, "image/jpeg", 100, 68)
                    ?: compressToTarget(src, webpFormat(), "image/webp", 100, 68)
            "image/webp" -> compressToTarget(src, webpFormat(), "image/webp", 100, 68)
            // PNG over the limit goes to WebP, which still supports alpha.
            else -> compressToTarget(src, webpFormat(), "image/webp", 100, 68)
        } ?: throw UnsupportedImage("Failed to fit $mime image under ${limit / (1024 * 1024)}MB")

        return finish(
            encoded.first, encoded.second, encoded.third, mime,
            preservedOriginal = false,
            convertedTo = if (encoded.second != mime) encoded.second else null,
            chunkRawBytes = chunkRawBytes
        )
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    /**
     * Walks the resolution ladder, and at each scale the quality ladder, taking
     * the first output that fits — the web's compressImageToTarget.
     */
    private fun compressToTarget(
        src: Bitmap,
        format: Bitmap.CompressFormat,
        mime: String,
        initialQuality: Int,
        minQuality: Int
    ): Triple<ByteArray, String, Double>? {
        for (scale in RESOLUTION_SCALES) {
            // Ladder over the NATURAL size, like the web's encodeImageToBlob —
            // the old 1280×720 pre-clamp shipped visibly smaller images than
            // the same photo sent from a browser (the box only exists in the
            // web's legacy inline path).
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            val bmp = if (w != src.width || h != src.height)
                Bitmap.createScaledBitmap(src, w, h, true) else src

            var quality = initialQuality
            while (quality >= minQuality) {
                val out = ByteArrayOutputStream()
                bmp.compress(format, quality, out)
                val bytes = out.toByteArray()
                if (bytes.size <= MAX_ASSEMBLED) {
                    return Triple(bytes, mime, quality / 100.0)
                }
                quality -= 8
            }
        }
        return null
    }

    private fun finish(
        bytes: ByteArray,
        finalMime: String,
        quality: Double?,
        originalMime: String,
        preservedOriginal: Boolean,
        convertedTo: String?,
        chunkRawBytes: Int = CHUNK_RAW
    ): Encoded {
        val chunks = ArrayList<ByteArray>()
        val hashes = ArrayList<String>()
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + chunkRawBytes, bytes.size)
            val piece = bytes.copyOfRange(off, end)
            chunks.add(piece)
            hashes.add(sha256Hex(piece))
            off = end
        }
        return Encoded(
            bytes = bytes,
            mime = finalMime,
            quality = quality,
            originalMime = originalMime,
            preservedOriginal = preservedOriginal,
            convertedTo = convertedTo,
            chunks = chunks,
            chunkHashes = hashes,
            assembledSha256 = sha256Hex(bytes)
        )
    }
}

package com.pombo.android.core

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.pombo.android.ui.PomboAvatar

/**
 * The large icon for message notifications: the sender's ENS avatar when one
 * is cached, otherwise the same deterministic generated avatar the UI shows —
 * so a notification is recognisable at a glance instead of text-only.
 *
 * Kept out of [com.pombo.android.ui.Avatar] because this also runs in the FCM
 * service, where no composition (and no shared UI loader) exists.
 */
object NotificationAvatar {

    @Volatile private var loader: ImageLoader? = null

    private fun loader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components { add(SvgDecoder.Factory()) }
            .build()
            .also { loader = it }
    }

    suspend fun bitmapFor(context: Context, address: String, ensAvatarUrl: String?): Bitmap? {
        // ENS avatar through the SAME Coil pipeline the UI uses — a manual
        // HttpURLConnection + BitmapFactory pass fails on cross-protocol
        // redirects and on SVG/WebP avatars, which is exactly what made the
        // generated avatar show up instead of the ENS one.
        ensAvatarUrl?.let { url -> decode(context, url)?.let { return matte(it) } }
        // borderRadius 0.5 = the round avatar the app uses everywhere. Already
        // an opaque disc, so it needs no matting.
        val svg = PomboAvatar.svg(address, size = 128, borderRadius = 0.5).toByteArray()
        return decode(context, svg)
    }

    /**
     * Android circle-crops the notification's large icon. A logo-style avatar —
     * a shape on a transparent field, which is what most ENS avatars are — then
     * shows the shade's dark background through the transparency and loses its
     * extremities to the crop. Those get a white disc behind them and are
     * scaled to sit INSIDE the circle.
     *
     * Artwork that is already opaque edge to edge (a photo, a full-bleed
     * illustration) is meant to fill the circle, so it is passed through
     * untouched — matting it would just add a white ring.
     */
    private fun matte(src: Bitmap): Bitmap {
        if (!hasTransparency(src)) return src
        val size = 256
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        // 0.72 of the diameter: the largest square that fits a circle is 0.707,
        // and a logo's own corners are usually empty, so this fills the disc
        // without touching the crop.
        val box = size * 0.72f
        val scale = minOf(box / src.width, box / src.height)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        canvas.drawBitmap(
            src,
            android.graphics.Rect(0, 0, src.width, src.height),
            android.graphics.Rect((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    /** Sampled, not exhaustive — this runs on the notification path. */
    private fun hasTransparency(bmp: Bitmap): Boolean {
        if (!bmp.hasAlpha()) return false
        var clear = 0
        var seen = 0
        val step = maxOf(1, minOf(bmp.width, bmp.height) / 32)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (android.graphics.Color.alpha(bmp.getPixel(x, y)) < 16) clear++
                seen++
                x += step
            }
            y += step
        }
        return seen > 0 && clear * 100 / seen > 5
    }

    private suspend fun decode(context: Context, data: Any): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(256)
            .allowHardware(false)
            .build()
        loader(context).execute(request).drawable?.toBitmap()
    }.getOrNull()
}

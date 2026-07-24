package com.pombo.android.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest

/**
 * One loader for every avatar in the app.
 *
 * This used to be built inside the composable with `remember`, which looks
 * harmless but is not: a LazyColumn disposes an item when it scrolls out of
 * view and recomposes it on the way back, so each pass built a NEW loader with
 * a NEW empty memory cache. Nothing was ever reused — every avatar re-decoded
 * on every appearance, which is why they flashed the fallback colour and only
 * settled once scrolling stopped.
 */
private object AvatarLoader {
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .components { add(SvgDecoder.Factory()) }
            .memoryCache {
                MemoryCache.Builder(context.applicationContext)
                    // Avatars are small and re-shown constantly while scrolling.
                    .maxSizePercent(0.15)
                    .build()
            }
            .build()
            .also { instance = it }
    }
}

/**
 * Deterministic SVG avatar per address (same output as the web), replaced by
 * the ENS avatar when the address has one.
 */
@Composable
fun Avatar(
    address: String?,
    size: Dp = 40.dp,
    cornerRadiusFraction: Double = 0.2,
    modifier: Modifier = Modifier,
    /** ENS avatar record. When set it replaces the generated avatar, like the web. */
    ensAvatarUrl: String? = null
) {
    val context = LocalContext.current
    val loader = AvatarLoader.get(context)
    // Generated at a fixed 256px: crisp on any density (the old 96px was
    // upscaled and looked rough on dense screens). Deriving the raster size
    // from screen density instead would make the output device-dependent
    // rather than identical across devices.
    val svgBytes = remember(address, cornerRadiusFraction) {
        PomboAvatar.svg(address, size = 256, borderRadius = cornerRadiusFraction).toByteArray()
    }
    val cornerDp = size * cornerRadiusFraction.toFloat()
    // Deterministic flat fallback: if SVG decoding ever fails, the slot still
    // shows the address's colour instead of a hole in the layout.
    val fallback = remember(address) { PomboAvatar.fallbackColor(address) }

    val request = remember(address, ensAvatarUrl, cornerRadiusFraction) {
        ImageRequest.Builder(context)
            .data(ensAvatarUrl ?: svgBytes)
            // A ByteArray compares by identity, so a regenerated SVG never hit
            // the cache even with a shared loader. Key on the address instead,
            // which is what actually identifies the image.
            .memoryCacheKey(ensAvatarUrl ?: "pombo-avatar:$address:$cornerRadiusFraction")
            .diskCachePolicy(if (ensAvatarUrl != null) CachePolicy.ENABLED else CachePolicy.DISABLED)
            // No fade: a cached avatar should appear with the row, not animate
            // in behind it every time the row scrolls back on screen.
            .crossfade(false)
            .build()
    }

    Box(
        modifier.size(size).clip(RoundedCornerShape(cornerDp)).background(fallback)
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            imageLoader = loader,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(size)
        )
    }
}

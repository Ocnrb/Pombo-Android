package com.pombo.android.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/**
 * Real backdrop blur for dialog windows — the web's `backdrop-blur-md` on modal
 * overlays (#channel-settings-modal, #dm-inbox-setup-modal, …).
 *
 * Android 12+ can blur whatever is behind a window, which is exactly the
 * browser's backdrop-filter semantics. Call this inside a Dialog's content.
 * On older versions the overlay scrim alone carries the separation.
 */
@Composable
fun DialogBackdropBlur(radiusDp: Int = 24) {
    val view = LocalView.current
    SideEffect {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@SideEffect
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?: return@SideEffect
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply {
            blurBehindRadius = (radiusDp * view.resources.displayMetrics.density).toInt()
        }
    }
}

/**
 * Glass panel matching the web's `.pill-nav-inner` / `.pinned-banner`:
 * white 6% fill, white 8% border, given radius.
 *
 * NOTE: the web gets its frost from `backdrop-filter: blur(20px)`, which
 * Compose cannot do for in-layout surfaces — only whole windows can be blurred
 * (see [DialogBackdropBlur]). Pre-blur devices and in-layout surfaces therefore
 * use a denser base so scrolled content behind stays unreadable rather than
 * showing through as noise.
 */
@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    opaqueBase: Boolean = false,
    fillAlpha: Float = 0.06f,
    borderAlpha: Float = 0.08f,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier
            .then(
                if (opaqueBase) Modifier.background(Color(0xFF0E0E10).copy(alpha = 0.88f), shape)
                else Modifier
            )
            .background(Color.White.copy(alpha = fillAlpha), shape)
            .border(1.dp, Color.White.copy(alpha = borderAlpha), shape),
        content = content
    )
}

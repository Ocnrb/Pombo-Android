package com.pombo.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Message context menu, matching the web's `#message-context-menu`.
 *
 * Material3's `DropdownMenu` was wrong on three counts: it anchors to the
 * composable rather than the finger, it applies its own generous item padding
 * (48dp minimum touch targets), and it has no icon slot. The result was a tall,
 * airy menu that always appeared above the bubble instead of under the thumb.
 *
 * This is a bare `Popup` positioned at the touch point and clamped to the
 * window with the same 10px margin the web uses, styled from
 * index.html:2540 — `bg-[#16161b] border border-white/10 rounded-xl shadow-xl
 * py-1 min-w-[180px]`.
 */
@Composable
fun PomboContextMenu(
    /** Where the finger went down, in window coordinates. */
    touchOffset: IntOffset,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val marginPx = with(LocalDensity.current) { 10.dp.roundToPx() }
    val positionProvider = remember(touchOffset, marginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                // Web showContextMenu: place the top-left at the pointer, then
                // pull it back inside the viewport rather than letting it clip.
                val x = touchOffset.x
                    .coerceAtMost(windowSize.width - popupContentSize.width - marginPx)
                    .coerceAtLeast(marginPx)
                val y = touchOffset.y
                    .coerceAtMost(windowSize.height - popupContentSize.height - marginPx)
                    .coerceAtLeast(marginPx)
                return IntOffset(x, y)
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier
                // A Popup hands its content the whole window as the max width,
                // so items that fillMaxWidth would stretch the menu edge to
                // edge. Sizing to the widest item reproduces the web's
                // `min-w-[180px]` on an otherwise fit-content box.
                .width(IntrinsicSize.Max)
                .widthIn(min = 180.dp)
                .shadow(16.dp, RoundedCornerShape(12.dp))
                .background(Color(0xFF16161B), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                // `py-1`
                .padding(vertical = 4.dp),
            content = content
        )
    }
}

/** `.context-menu-item`: px-4 py-2, text-sm, 16px icon, gap-2. */
@Composable
fun ContextMenuItem(
    label: String,
    icon: ImageVector,
    /** Icon tint; the web colours it per action (sky, emerald, amber, red). */
    iconTint: Color = Color.White.copy(alpha = 0.60f),
    /** Label colour; destructive entries are red-400. */
    labelColor: Color = Color.White.copy(alpha = 0.90f),
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        Text(label, color = labelColor, fontSize = 14.sp)
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick
)

/** `<hr class="border-white/[0.06] my-1">` */
@Composable
fun ContextMenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.06f))
    )
}

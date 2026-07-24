package com.pombo.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Toast kinds and colours from the web (.toast.success/.error/.info/.warning). */
enum class ToastKind(val color: Color) {
    SUCCESS(Color(0xFF4ADE80)),
    ERROR(Color(0xFFF87171)),
    INFO(Color(0xFF60A5FA)),
    WARNING(Color(0xFFFBBF24)),
    // components.css:1366 `.toast.loading .toast-icon { color: #ff5c00 }` —
    // this was blue, copied from INFO.
    LOADING(Color(0xFFFF5C00))
}

/**
 * The app's signature easing: `cubic-bezier(0.16, 1, 0.3, 1)` (expo-out), used
 * by every toast animation in components.css.
 */
val PomboExpoOut = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** Step ring for long on-chain operations (web .toast-progress-ring). */
data class ToastProgress(val current: Int, val total: Int, val label: String)

data class Toast(
    val id: Long,
    val message: String,
    val kind: ToastKind,
    /** Web default is 3000ms; loading toasts stay until dismissed. */
    val durationMs: Long = 3000L,
    val subtitle: String? = null,
    val progress: ToastProgress? = null
)

/**
 * Toast stack — web #toast-container: top 29dp, centred, max 380dp wide,
 * 12dp gap, panels #16161B with a 2dp progress bar in the variant colour.
 *
 * Swipe sideways to dismiss. A long-running (loading) toast cannot be thrown
 * away — swiping parks it against the edge as a tab that restores on tap, so
 * the operation stays reachable.
 */
@Composable
fun ToastHost(toasts: List<Toast>, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    Column(
        // Mobile `.toast-container { left: 0.5rem; right: 0.5rem; max-width:
        // calc(100% - 1rem) }` — nearly full width, not the desktop 380px box.
        modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        toasts.forEach { toast ->
            androidx.compose.runtime.key(toast.id) {
                ToastItem(toast, onDismiss = { onDismiss(toast.id) })
            }
        }
    }
}

@Composable
private fun ToastItem(toast: Toast, onDismiss: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var docked by remember { mutableStateOf(false) }
    val persistent = toast.kind == ToastKind.LOADING

    LaunchedEffect(toast.id) {
        visible = true
        if (!persistent) {
            kotlinx.coroutines.delay(toast.durationMs)
            visible = false
            kotlinx.coroutines.delay(200)
            onDismiss()
        }
    }

    if (docked) {
        // Parked tab: half a pill peeking from the right edge.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Row(
                Modifier
                    .background(Color(0xFF16161B), RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .border(
                        1.dp, Color.White.copy(alpha = 0.08f),
                        RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
                    )
                    .clickable { docked = false; offsetX = 0f }
                    .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    color = toast.kind.color, strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        return
    }

    // Countdown bar mirrors the web's .toast-progress, which is explicitly
    // `linear`. tween()'s default FastOutSlowIn made the bar stall at both
    // ends, so a 3s countdown looked stuck rather than counting down.
    val barProgress by animateFloatAsState(
        targetValue = if (visible && !persistent) 0f else 1f,
        animationSpec = tween(
            durationMillis = toast.durationMs.toInt(),
            easing = androidx.compose.animation.core.LinearEasing
        ),
        label = "toast-progress"
    )

    // @keyframes toastSlideIn/Out: a 16px vertical shift over 300/250ms on the
    // expo-out curve — not a full-height spring slide, and the exit does move.
    val slidePx = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = tween(300, easing = PomboExpoOut)
        ) { -slidePx } + fadeIn(tween(300, easing = PomboExpoOut)),
        exit = slideOutVertically(
            animationSpec = tween(250, easing = PomboExpoOut)
        ) { -slidePx } + fadeOut(tween(250, easing = PomboExpoOut))
    ) {
        Box(
            Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                // The 380px cap is desktop-only; mobile lets the panel run to
                // the container's near-full width.
                .fillMaxWidth()
                // .toast { box-shadow: 0 8px 32px rgba(0,0,0,0.4), … } — on a
                // near-black background the shadow is the only thing lifting
                // the toast off the page.
                .shadow(12.dp, RoundedCornerShape(12.dp), clip = false)
                .background(Color(0xFF16161B), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .pointerInput(toast.id) {
                    // Raw pixels made the dismiss distance density-dependent:
                    // the same flick that dismissed on a low-density screen
                    // barely moved the toast on a high-density one.
                    val dismissThresholdPx = 90.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(offsetX) > dismissThresholdPx) {
                                // Persistent work parks instead of disappearing.
                                if (persistent) { docked = true } else { visible = false; onDismiss() }
                            } else offsetX = 0f
                        },
                        onHorizontalDrag = { _, delta -> offsetX += delta }
                    )
                }
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (toast.kind == ToastKind.LOADING && toast.progress == null) {
                    CircularProgressIndicator(
                        color = toast.kind.color, strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                } else if (toast.kind != ToastKind.LOADING) {
                    Icon(
                        when (toast.kind) {
                            ToastKind.SUCCESS -> Icons.Filled.CheckCircle
                            ToastKind.ERROR -> Icons.Filled.ErrorOutline
                            ToastKind.WARNING -> Icons.Filled.WarningAmber
                            else -> Icons.Filled.Info
                        },
                        contentDescription = null,
                        tint = toast.kind.color,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        toast.message,
                        color = Color.White.copy(alpha = 0.90f),
                        fontSize = 13.sp, lineHeight = 18.sp
                    )
                    toast.subtitle?.let {
                        Text(it, color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp)
                    }
                }

                toast.progress?.let { p ->
                    Spacer(Modifier.width(12.dp))
                    StepRing(p, toast.kind.color)
                }
            }

            if (!persistent) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(barProgress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(toast.kind.color)
                )
            }
        }
    }
}

/**
 * Web .toast-progress-ring: 28px ring, track white/8, fill #ff5c00, both
 * stroke 3 with a round cap, plus the phase label underneath.
 */
@Composable
private fun StepRing(progress: ToastProgress, @Suppress("UNUSED_PARAMETER") accent: Color) {
    val fraction = if (progress.total <= 0) 0f
        else (progress.current.toFloat() / progress.total).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(400),
        label = "toast-ring"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(28.dp).rotate(-90f)) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = Color(0xFFFF5C00),
                startAngle = 0f, sweepAngle = 360f * animated, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Text(
            progress.label,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 10.sp, lineHeight = 12.sp
        )
    }
}

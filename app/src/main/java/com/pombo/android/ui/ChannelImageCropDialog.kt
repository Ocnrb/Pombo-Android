package com.pombo.android.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pombo.android.core.MediaEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Channel-image crop dialog, ported from the web's
 * #channel-image-confirm-modal: circular 280px stage you drag to frame,
 * pinch or a ±100 slider for zoom (2^(percent/100), so 0.5×–2×), Reset, and
 * an Upload that renders the exact same framing at 512².
 */
@Composable
fun ChannelImageCropDialog(
    uri: Uri,
    onCancel: () -> Unit,
    onConfirm: (bytes: ByteArray, mime: String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var preserveAlpha by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var centerX by remember { mutableFloatStateOf(0f) }
    var centerY by remember { mutableFloatStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    var stagePx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(uri) {
        val decoded = withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext null
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                MediaEncoder.decodeForCrop(bytes)?.let { it to mime }
            } catch (e: Exception) { null }
        }
        if (decoded == null) {
            loadFailed = true
        } else {
            // Web loadImageForCrop: everything but JPEG keeps its alpha and
            // uploads as PNG.
            preserveAlpha = decoded.second != "image/jpeg" && decoded.second != "image/jpg"
            centerX = decoded.first.width / 2f
            centerY = decoded.first.height / 2f
            bitmap = decoded.first
        }
    }

    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
    val sliderPercent = ln(zoom.toDouble()) / ln(2.0) * 100.0

    Dialog(onDismissRequest = { if (!saving) onCancel() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text("Update channel image", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                // Crop stage: circular like the web's rounded-full viewport.
                Box(
                    Modifier
                        .widthIn(max = 280.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .align(Alignment.CenterHorizontally)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape)
                        .onSizeChanged { stagePx = it.width.toFloat() }
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val bmp = bitmap ?: return@detectTransformGestures
                                if (stagePx <= 0f) return@detectTransformGestures
                                val next = MediaEncoder.squareCropFrame(
                                    bmp.width, bmp.height, stagePx,
                                    (zoom * gestureZoom).coerceIn(0.5f, 2f), centerX, centerY
                                )
                                zoom = next.zoom
                                // Web: center -= delta / scale, then clamp.
                                centerX = (next.centerX - pan.x / next.scale)
                                    .coerceIn(next.minCenterX, next.maxCenterX)
                                centerY = (next.centerY - pan.y / next.scale)
                                    .coerceIn(next.minCenterY, next.maxCenterY)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (imageBitmap != null) {
                        val bmp = bitmap!!
                        Canvas(Modifier.aspectRatio(1f).fillMaxWidth()) {
                            val frame = MediaEncoder.squareCropFrame(
                                bmp.width, bmp.height, size.width, zoom, centerX, centerY
                            )
                            drawImage(
                                imageBitmap,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(bmp.width, bmp.height),
                                dstOffset = IntOffset(frame.offsetX.roundToInt(), frame.offsetY.roundToInt()),
                                dstSize = IntSize(frame.drawWidth.roundToInt(), frame.drawHeight.roundToInt()),
                                filterQuality = FilterQuality.Medium
                            )
                        }
                    } else {
                        Text(
                            if (loadFailed) "Failed to load image" else "Loading…",
                            color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Drag to choose the framing and use zoom to control how tightly " +
                            "the image is cropped. Upload stays square at 512×512.",
                        color = Color.White.copy(alpha = 0.50f), fontSize = 11.sp,
                        lineHeight = 15.sp, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .clickable(enabled = bitmap != null) {
                                val bmp = bitmap ?: return@clickable
                                zoom = 1f
                                centerX = bmp.width / 2f
                                centerY = bmp.height / 2f
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Reset", color = Color.White.copy(alpha = 0.50f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Zoom", color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp)
                    Slider(
                        value = sliderPercent.toFloat(),
                        onValueChange = { percent ->
                            val bmp = bitmap ?: return@Slider
                            if (stagePx <= 0f) return@Slider
                            val next = MediaEncoder.squareCropFrame(
                                bmp.width, bmp.height, stagePx,
                                2.0.pow(percent / 100.0).toFloat(), centerX, centerY
                            )
                            zoom = next.zoom
                            centerX = next.centerX
                            centerY = next.centerY
                        },
                        valueRange = -100f..100f,
                        enabled = bitmap != null,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White.copy(alpha = 0.40f),
                            inactiveTrackColor = Color.White.copy(alpha = 0.10f)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${sliderPercent.roundToInt()}%",
                        color = Color.White.copy(alpha = 0.50f), fontSize = 11.sp,
                        textAlign = TextAlign.End, modifier = Modifier.width(40.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickable(enabled = !saving) { onCancel() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .background(
                            if (bitmap != null && !saving) Color.White else Color.White.copy(alpha = 0.30f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable(enabled = bitmap != null && !saving) {
                            val bmp = bitmap ?: return@clickable
                            saving = true
                            scope.launch {
                                val rendered = withContext(Dispatchers.Default) {
                                    try {
                                        MediaEncoder.renderSquareCrop(bmp, zoom, centerX, centerY, preserveAlpha)
                                    } catch (e: Exception) { null }
                                }
                                saving = false
                                if (rendered != null) onConfirm(rendered.first, rendered.second)
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (saving) "Preparing…" else "Upload",
                        color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

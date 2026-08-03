package com.pombo.android.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pombo.android.ui.theme.PomboColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * How many of the newest pictures the grid holds. It is a shortcut, not a
 * gallery — anything older is one tap away behind Browse…, which opens the
 * system picker over the full library.
 */
private const val RECENT_LIMIT = 120

/** Grid geometry: three columns, hairline gutters, like every messenger tray. */
private const val GRID_COLUMNS = 3
private val GRID_GUTTER = 2.dp

/**
 * The attach → Image tray: a grid of recent photos that opens on a camera tile.
 *
 * The system photo picker (PickVisualMedia) cannot do this. It is a separate,
 * closed Activity and there is no API to place anything inside it, so a camera
 * entry point that sits *in the grid* — the way Telegram, WhatsApp and Signal
 * all do it — means drawing the grid ourselves from MediaStore.
 *
 * That trade is the reason this file exists: it buys the camera tile at the
 * cost of a photo permission the app never needed before. So refusal is a
 * first-class state, not an error — the tray still works with no permission at
 * all, offering the camera (which needs none) and Browse… into the system
 * picker (which needs none either). Only the thumbnails are gated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePickerSheet(
    onPick: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    var granted by remember { mutableStateOf(hasPhotoAccess(context)) }
    // Distinguishes "the permission dialog is still up" from "the user said
    // no", so the explanatory line does not flash on the way in.
    var answered by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var noCameraApp by remember { mutableStateOf(false) }

    /** Animate the tray away first, then act — abrupt on a pick otherwise. */
    fun close(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then(); onDismiss() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Read the result from the system rather than the returned map: on
        // Android 14 a partial grant answers _VISUAL_USER_SELECTED and leaves
        // READ_MEDIA_IMAGES false, which still counts as access.
        granted = hasPhotoAccess(context)
        answered = true
    }
    val cameraLauncher = rememberLauncherForActivityResult(GrantingTakePicture()) { ok ->
        val file = cameraCaptureFile(context)
        // `ok` alone is not enough: some OEM camera apps report RESULT_OK
        // without ever writing the output. The file is deleted before every
        // launch, so a non-empty one here can only be this capture.
        if (ok && file.length() > 0) close { onPick(cameraCaptureUri(context)) } else close()
    }
    val systemPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) close { onPick(uri) } }
    // Launched for result purely to be told when the user comes back: settings
    // returns no result, but the callback still fires, and the permission may
    // well have been switched on while we were away.
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { granted = hasPhotoAccess(context) }

    // Asking the moment the tray opens is the honest moment: the user has just
    // said they want to attach a picture.
    LaunchedEffect(Unit) {
        if (granted) answered = true else permissionLauncher.launch(photoPermissions())
    }
    LaunchedEffect(granted) {
        if (granted) photos = recentImages(context, RECENT_LIMIT)
    }

    fun launchCamera() {
        val file = cameraCaptureFile(context)
        file.parentFile?.mkdirs()
        // Clear the target so a camera app that returns OK without writing
        // cannot make us re-send the previous capture.
        file.delete()
        try {
            cameraLauncher.launch(cameraCaptureUri(context))
        } catch (e: android.content.ActivityNotFoundException) {
            // A Toast would be invisible here — the sheet is its own window.
            noCameraApp = true
        }
    }

    fun browse() = systemPicker.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PomboColors.SurfaceHigh,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.20f))
        }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Send photo",
                color = PomboColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            // Only alongside a grid. "Browse" means "look past what is shown
            // here" — past the 120 most recent, or past the subset a partial
            // grant exposes. With no permission nothing is shown, so it would
            // be a second, quieter copy of the Gallery tile below.
            if (granted) {
                Text(
                    "Browse…",
                    color = PomboColors.Accent, fontSize = 14.sp,
                    modifier = Modifier
                        .clickableNoRipple { browse() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }

        if (noCameraApp) {
            Text(
                "No camera app found on this device.",
                color = PomboColors.Danger, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (granted) {
            // heightIn, not weight: a lazy grid measured against the sheet's
            // unbounded content height would crash, and capping it also gives
            // the tray its half-screen resting shape.
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
            LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER),
                verticalArrangement = Arrangement.spacedBy(GRID_GUTTER),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .padding(horizontal = GRID_GUTTER)
            ) {
                item(key = "camera") { CameraTile(onClick = ::launchCamera) }
                items(photos, key = { it.toString() }) { uri ->
                    PhotoTile(uri) { close { onPick(uri) } }
                }
            }
            if (photos.isEmpty()) {
                Text(
                    "No pictures here yet.",
                    color = PomboColors.TextDim, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        } else {
            // No thumbnails to show, so the two actions that need no permission
            // become the tray itself.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CameraTile(onClick = ::launchCamera, modifier = Modifier.weight(1f))
                Box(Modifier.weight(1f)) {
                    TileFrame(onClick = ::browse) {
                        Icon(
                            Icons.Outlined.PhotoLibrary, contentDescription = null,
                            tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(0.dp))
                        Text("Gallery", color = PomboColors.TextDim, fontSize = 12.sp)
                    }
                }
            }
            if (answered) {
                val canAskAgain = context.canRequestPhotoAccess()
                Text(
                    if (canAskAgain) "Allow photo access to pick from recent pictures here."
                    else "Photo access is off. Turn it on in Settings to see recent pictures here.",
                    color = PomboColors.TextDim, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                )
                Text(
                    if (canAskAgain) "Allow access" else "Open settings",
                    color = PomboColors.Accent, fontSize = 14.sp,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clickableNoRipple {
                            if (canAskAgain) permissionLauncher.launch(photoPermissions())
                            else settingsLauncher.launch(appSettingsIntent(context))
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CameraTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        TileFrame(onClick = onClick) {
            Icon(
                Icons.Outlined.PhotoCamera, contentDescription = "Take photo",
                tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(28.dp)
            )
            Text("Camera", color = PomboColors.TextDim, fontSize = 12.sp)
        }
    }
}

/** The square, its fill and its rule — shared by the camera and Gallery tiles. */
@Composable
private fun TileFrame(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, PomboColors.Border, RoundedCornerShape(4.dp))
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) { content() }
    }
}

@Composable
private fun PhotoTile(uri: Uri, onClick: () -> Unit) {
    val context = LocalContext.current
    AsyncImage(
        // Decoded at thumbnail scale: 120 full-resolution bitmaps would blow
        // the heap long before the grid finished scrolling.
        model = ImageRequest.Builder(context).data(uri).size(256).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .clickableNoRipple(onClick)
    )
}

/**
 * TakePicture, but the camera app can actually write to the target.
 *
 * The stock contract builds nothing but
 * `Intent(ACTION_IMAGE_CAPTURE).putExtra(EXTRA_OUTPUT, uri)`, and a Uri passed
 * as an *extra* is not covered by the intent's grant flags — those only reach
 * `data` and `clipData`. The framework normally rescues this by migrating
 * EXTRA_OUTPUT into ClipData on the way out of the process, but that is ROM
 * behaviour to depend on; doing it here makes the grant explicit and identical
 * everywhere.
 */
private class GrantingTakePicture : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input).apply {
            clipData = ClipData.newRawUri("", input)
            addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
}

/**
 * A fixed name, deliberately: the Uri can then be rebuilt from nothing after
 * the camera app pushes this process out of memory, so the result survives
 * without any saved state. Safe to reuse because every launch deletes it first.
 */
private fun cameraCaptureFile(context: Context): File =
    File(File(context.cacheDir, "camera"), "capture.jpg")

private fun cameraCaptureUri(context: Context): Uri = FileProvider.getUriForFile(
    context, "${context.packageName}.fileprovider", cameraCaptureFile(context)
)

private fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Any one of them is enough — on Android 14 partial access grants only one. */
private fun hasPhotoAccess(context: Context): Boolean = photoPermissions().any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * Whether asking again would still put a dialog on screen.
 *
 * Android stops showing the permission prompt once the user has refused twice:
 * every request after that returns denied immediately, with no UI at all. A
 * retry button would then be dead on touch, so the tray needs to know and send
 * the user to the app's settings page instead.
 *
 * Only meaningful after a request has actually been made — before the first
 * one this reads false too, for a different reason.
 */
private fun Context.canRequestPhotoAccess(): Boolean {
    val activity = findActivity() ?: return false
    return photoPermissions().any {
        ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private fun appSettingsIntent(context: Context) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null)
)

/**
 * Newest images first. Under a partial grant MediaStore silently narrows this
 * to the pictures the user picked, so no branch is needed for that case.
 */
private suspend fun recentImages(context: Context, limit: Int): List<Uri> =
    withContext(Dispatchers.IO) {
        val out = ArrayList<Uri>(limit)
        // No SQL LIMIT in the sort order: it is unsupported from Android 11 on.
        // The cursor fills its window lazily, so stopping early costs the same.
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (out.size < limit && cursor.moveToNext()) {
                out += ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn)
                )
            }
        }
        out
    }

/** Web surfaces have no ripple; matches the helper the screens already use. */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick
)

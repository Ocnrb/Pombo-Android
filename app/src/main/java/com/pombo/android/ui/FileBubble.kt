package com.pombo.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pombo.android.core.MediaController
import com.pombo.android.core.StorageMedia

/**
 * What a file bubble needs from the app, provided once at the top of the tree.
 */
interface FileTransfers {
    fun progressFor(fileId: String): MediaController.Progress?
    fun uploadsFor(fileId: String): MediaController.UploadStats?
    fun isSeeding(fileId: String): Boolean
    fun onDownload(messageId: String)
    fun onSave(fileId: String, fileName: String)
}

val LocalFileTransfers = staticCompositionLocalOf<FileTransfers?> { null }

/** Storage-transport (Persistent File Sharing) transfer state for a bubble. */
interface StorageTransfers {
    fun uploadFor(transferId: String): StorageMedia.UploadProgress?
    fun downloadFor(transferId: String): StorageMedia.DownloadProgress?
    /** True once the downloaded file is on disk, ready to save. */
    fun completedFor(transferId: String): Boolean
    fun onDownload(messageId: String)
    fun onSave(transferId: String, fileName: String)
}

val LocalStorageTransfers = staticCompositionLocalOf<StorageTransfers?> { null }

// ---- shared design constants (mirror the web's unified file-card) ----
private val CARD_WIDTH = 300.dp                 // FIXED — never varies with stats content
private val CARD_SHAPE = RoundedCornerShape(12.dp)
private val CARD_BG = Color.White.copy(alpha = 0.05f)
private val GREEN = Color(0xFF4ADE80)
private val BLUE = Color(0xFF3B82F6)

/** 1.2 MB, 340 KB — the web's formatFileSize. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Rate as the web shows it, or empty while it is not measurable. */
fun formatSpeed(bytesPerSecond: Long?): String =
    if (bytesPerSecond == null || bytesPerSecond <= 0) "" else "${formatBytes(bytesPerSecond)}/s"

/** "45s" / "1m 10s" / "1h 05m" — the web's formatETA. */
fun formatETA(sec: Long?): String {
    if (sec == null || sec < 0) return ""
    if (sec < 60) return "${sec}s"
    val m = sec / 60; val s = sec % 60
    if (m < 60) return "${m}m ${s.toString().padStart(2, '0')}s"
    val h = m / 60
    return "${h}h ${(m % 60).toString().padStart(2, '0')}m"
}

/**
 * The single 40×40 action slot, placed on the AVATAR side (left for received
 * messages, right for own) exactly like the web `.file-card-action`.
 */
@Composable
private fun FileCardAction(icon: ImageVector, tint: Color, bgAlpha: Float, onClick: (() -> Unit)?) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = bgAlpha))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** The uppercase transport pill ("mesh"/"storage"). Shared by the bubble and the Active Transfers list. */
@Composable
fun TransportBadge(label: String) {
    Text(
        label.uppercase(),
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 9.sp,
        maxLines = 1,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** A thin bottom progress bar, [pct] in 0..1, [color] the fill. */
@Composable
private fun ProgressBar(pct: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.White.copy(alpha = 0.06f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(pct.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color)
        )
    }
}

/**
 * Mesh P2P file card. Fixed 300 dp, one action on the avatar side, ONE line of
 * stats (the web keeps it single-line for this transport), thin bar while a
 * download runs. Green floppy once the bytes are here (name saves too).
 */
@Composable
fun FileBubbleContent(
    file: MediaController.FileMetadata,
    messageId: String,
    mine: Boolean,
    textColor: Color
) {
    val transfers = LocalFileTransfers.current
    val progress = transfers?.progressFor(file.fileId)
    val upload = transfers?.uploadsFor(file.fileId)
    val downloading = progress != null && !progress.done && progress.failure == null
    val localFile = progress?.done == true || transfers?.isSeeding(file.fileId) == true
    val canDownload = file.pieceHashes.size == file.pieceCount && file.pieceCount > 0
    val pct = if (downloading && progress != null && progress.total > 0)
        (progress.received.toFloat() / progress.total).coerceIn(0f, 1f) else 0f

    val totalSize = formatBytes(file.fileSize)
    val detail = when {
        downloading && progress != null -> {
            val speed = formatSpeed(progress.bytesPerSecond)
            "${formatBytes(progress.received)} of $totalSize" + if (speed.isEmpty()) "" else " · $speed"
        }
        !canDownload && !localFile -> "$totalSize · not on this device"
        else -> totalSize
    }

    val action: (@Composable () -> Unit)? = when {
        localFile -> ({ FileCardAction(Icons.Filled.Save, GREEN.copy(alpha = 0.7f), 0.06f) { transfers?.onSave(file.fileId, file.fileName) } })
        downloading -> ({ FileCardAction(Icons.Filled.ArrowDownward, Color.White.copy(alpha = 0.25f), 0.04f, null) })
        canDownload -> ({ FileCardAction(Icons.Filled.ArrowDownward, Color.White.copy(alpha = 0.6f), 0.06f) { transfers?.onDownload(messageId) } })
        else -> null
    }

    Column(Modifier.width(CARD_WIDTH).clip(CARD_SHAPE).background(CARD_BG)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!mine && action != null) action()
            Column(Modifier.weight(1f)) {
                Text(
                    file.fileName,
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (localFile) Modifier.clickable { transfers?.onSave(file.fileId, file.fileName) } else Modifier
                )
                Row(
                    Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TransportBadge("mesh")
                    Text(
                        detail, color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    if (localFile && upload != null) {
                        Icon(Icons.Filled.ArrowUpward, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(10.dp))
                        Text(formatSpeed(upload.bytesPerSecond).ifEmpty { "0 B/s" }, color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp, maxLines = 1)
                        Icon(Icons.Filled.Person, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(10.dp))
                        Text("${upload.leechers}", color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp, maxLines = 1)
                    }
                }
                if (localFile) Text("seeding...", color = GREEN.copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                progress?.failure?.let {
                    Text(it, color = Color(0xFFE05252), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (mine && action != null) action()
        }
        if (downloading) ProgressBar(pct, BLUE)
    }
}

/**
 * Persistent File Sharing (storage-node) file card. Same shell as the mesh card
 * — fixed 300 dp, one action on the avatar side — but the stats are TWO lines
 * (line 1: "38% · 42 of 113 MB", line 2: "6.2 MB/s · ~1m 10s left"). Purple bar.
 */
@Composable
fun StorageFileBubbleContent(
    file: StorageMedia.StorageFileMetadata,
    messageId: String,
    mine: Boolean,
    textColor: Color
) {
    val transfers = LocalStorageTransfers.current
    val up = transfers?.uploadFor(file.transferId)
    val dl = transfers?.downloadFor(file.transferId)
    val completed = transfers?.completedFor(file.transferId) == true

    val uploading = up != null && up.stage != "done" && up.error == null
    val downloading = dl?.status == "downloading"
    val active = uploading || downloading
    val ready = completed
    val totalSize = formatBytes(file.originalSize)

    // Two-line stats (mirror formatStorageUpload/DownloadDetail).
    val upErr = up?.error
    val dlErr = dl?.error
    val (line1, line2) = when {
        upErr != null -> upErr to ""
        dlErr != null -> dlErr to ""
        uploading && up != null -> storageUploadDetail(up)
        downloading && dl != null -> storageDownloadDetail(dl)
        else -> totalSize to ""
    }
    val isError = upErr != null || dlErr != null

    val pct = when {
        uploading && up != null && up.percent in 0..100 -> up.percent / 100f
        downloading && dl != null && dl.total > 0 -> (dl.received.toFloat() / dl.total).coerceIn(0f, 1f)
        else -> 0f
    }

    val action: (@Composable () -> Unit)? = when {
        ready -> ({ FileCardAction(Icons.Filled.Save, GREEN.copy(alpha = 0.7f), 0.06f) { transfers?.onSave(file.transferId, file.fileName) } })
        uploading -> ({ FileCardAction(Icons.Filled.ArrowUpward, Color.White.copy(alpha = 0.25f), 0.04f, null) })
        downloading -> ({ FileCardAction(Icons.Filled.ArrowDownward, Color.White.copy(alpha = 0.25f), 0.04f, null) })
        !mine -> ({ FileCardAction(Icons.Filled.ArrowDownward, Color.White.copy(alpha = 0.6f), 0.06f) { transfers?.onDownload(messageId) } })
        else -> null
    }

    val incomplete = file.storedChunks != null && file.totalChunks != null && file.storedChunks < file.totalChunks

    Column(Modifier.width(CARD_WIDTH).clip(CARD_SHAPE).background(CARD_BG)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!mine && action != null) action()
            Column(Modifier.weight(1f)) {
                Text(
                    file.fileName,
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (ready) Modifier.clickable { transfers?.onSave(file.transferId, file.fileName) } else Modifier
                )
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Top) {
                    TransportBadge("storage")
                    Column(Modifier.weight(1f)) {
                        Text(
                            line1,
                            color = if (isError) Color(0xFFE05252) else Color.White.copy(alpha = if (active) 0.55f else 0.35f),
                            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (line2.isNotEmpty()) Text(
                            line2, color = Color.White.copy(alpha = if (active) 0.45f else 0.30f),
                            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (incomplete) Text(
                    "⚠ incomplete on storage (${file.storedChunks}/${file.totalChunks})",
                    color = Color(0xFFFBBF24).copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (mine && action != null) action()
        }
        if (active) ProgressBar(pct, Color(0xFF8B5CF6))
    }
}

/** line1 / line2 for a storage upload (web formatStorageUploadDetail). */
private fun storageUploadDetail(up: StorageMedia.UploadProgress): Pair<String, String> {
    if (up.stage != "sending") return (up.phase.ifEmpty { "Uploading…" }) to ""
    val sent = formatBytes(up.bytesSent)
    val line1 = "${up.percent}% · " + (up.totalBytes?.let { "$sent of ${formatBytes(it)}" } ?: sent)
    val sub = ArrayList<String>()
    formatSpeed(up.instBps ?: up.avgBps).let { if (it.isNotEmpty()) sub.add(it) }
    formatETA(up.etaSec).let { if (it.isNotEmpty()) sub.add("~$it left") }
    if (up.processingPct < 100) sub.add("processing ${up.processingPct}%")
    return line1 to sub.joinToString(" · ")
}

/** line1 / line2 for a storage download (web formatStorageDownloadDetail). */
private fun storageDownloadDetail(dl: StorageMedia.DownloadProgress): Pair<String, String> {
    if (dl.phase != null) return dl.phase to ""
    val transferred = if (dl.total > 0) formatBytes(dl.received.toLong() * dl.fileSize / dl.total) else "0 B"
    val line1 = "${dl.percent}% · $transferred of ${formatBytes(dl.fileSize)}"
    val sub = ArrayList<String>()
    formatSpeed(dl.bytesPerSec).let { if (it.isNotEmpty()) sub.add(it) }
    formatETA(dl.etaSec).let { if (it.isNotEmpty()) sub.add("~$it left") }
    return line1 to sub.joinToString(" · ")
}

package com.pombo.android.ui

import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage

/**
 * Inline players for YouTube links in messages, mirroring the web's
 * `embedYouTubeLinks` (src/js/ui/utils.js:124): every YouTube link gets a
 * 16:9 player card under the text — same 400px width cap, 12px radius,
 * 8px top margin and near-black backdrop as the web's `.youtube-embed`.
 *
 * The web drops the iframe in immediately; a live WebView per message would
 * wreck LazyColumn scrolling, so the card starts as the video's own thumbnail
 * with a play badge — visually the same as an unstarted iframe — and only
 * builds the WebView (youtube-nocookie, like the web) when tapped.
 */

/** Settings "YouTube Embeds" toggle, provided app-wide (default ON, like the web). */
val LocalYouTubeEmbedsEnabled = staticCompositionLocalOf { true }

// The same URL alternatives the web recognises: watch?v=, embed/, shorts/,
// v/ and youtu.be, each followed by an 11-char video id.
private val YOUTUBE_URL = Regex(
    """https?://(?:www\.)?(?:youtube\.com/(?:watch\?v=|embed/|shorts/|v/)|youtu\.be/)([A-Za-z0-9_-]{11})""",
    RegexOption.IGNORE_CASE
)

/** Video ids of every YouTube link in the text, in order (no dedup, like the web). */
fun youtubeVideoIds(text: String): List<String> =
    YOUTUBE_URL.findAll(text).map { it.groupValues[1] }.toList()

@Composable
fun YouTubeEmbed(videoId: String) {
    // The player opens as an ACTIVITY IN ITS OWN PROCESS. Any WebView that
    // shares a process with the bridge's headless one plays video as sound
    // over a black picture — inline, and even in a Dialog window; the same
    // iframe in a bridge-free process renders normally (proven by
    // isolation). See YtProbeActivity.
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier
            .padding(top = 8.dp)
            .widthIn(max = 400.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    ) {
        run {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF09090B))
            ) {
            AsyncImage(
                model = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                contentDescription = "YouTube video",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // YouTube's rounded play badge, so the card reads as a video
            // before any player exists.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 58.dp, height = 40.dp)
                    .background(Color.Black.copy(alpha = 0.70f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow, contentDescription = "Play",
                    tint = Color.White, modifier = Modifier.size(28.dp)
                )
            }
            Box(Modifier.matchParentSize().clickable {
                context.startActivity(
                    android.content.Intent(context, com.pombo.android.YtProbeActivity::class.java)
                        .putExtra("videoId", videoId)
                )
            })
            }
        }
    }
}

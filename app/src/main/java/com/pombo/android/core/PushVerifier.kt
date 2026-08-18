package com.pombo.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Confirms a wake signal actually corresponds to a new message before anything
 * is shown — the same job `verifyChannel` does in sw.js.
 *
 * A push only carries a 1-byte tag, so a matching tag is not evidence: many
 * channels share it. The only way to know is to ask the storage node for the
 * stream's last message and compare against the watermark we already notified
 * about. Anything not newer is a false positive and is dropped silently.
 */
object PushVerifier {

    /** sw.js DEFAULT_STORAGE_ENDPOINTS — tried in order until one answers. */
    private val ENDPOINTS = listOf(
        "https://blob-storage-streamr.online",
        "https://vps2.blob-storage-streamr.online"
    )

    private const val API_PATH = "/streams/%s/data/partitions/%d/last?count=1"
    private const val TIMEOUT_MS = 5_000

    data class Result(
        val hasNew: Boolean,
        val timestamp: Long = 0L,
        val content: JSONObject? = null,
        val publisherId: String? = null
    )

    suspend fun verify(entry: PushRegistry.Entry): Result = withContext(Dispatchers.IO) {
        for (endpoint in ENDPOINTS) {
            try {
                val url = endpoint + API_PATH.format(
                    URLEncoder.encode(entry.streamId, "UTF-8"), 0
                )
                val body = fetch(url) ?: continue
                val last = lastMessage(body) ?: continue

                val timestamp = last.optLong("timestamp", 0L)
                val hasNew = timestamp > entry.lastTimestamp
                return@withContext Result(
                    hasNew = hasNew,
                    timestamp = timestamp,
                    content = if (hasNew) last.optJSONObject("content") else null,
                    publisherId = if (hasNew) last.optStringOrNull("publisherId") else null
                )
            } catch (e: Exception) {
                // Try the next endpoint; a single node being down is normal.
            }
        }
        // Every endpoint failed: stay silent rather than guess. Showing a
        // notification here would mean showing one for every colliding tag.
        Result(hasNew = false)
    }

    private fun fetch(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The endpoint answers either with an array or a single object depending on
     * the node build, so both shapes are accepted.
     */
    private fun lastMessage(body: String): JSONObject? {
        val trimmed = body.trim()
        return try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                if (arr.length() == 0) null else arr.optJSONObject(arr.length() - 1)
            } else {
                JSONObject(trimmed)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Notification body, matching sw.js getMessagePreview: encrypted channels
     * never leak content, public channels show the text.
     */
    fun preview(type: String, content: JSONObject?): String {
        if (type == "dm" || type == "native" || type == "gated" || type == "dm-inbox") return "New direct message"
        if (type == "private") return "New encrypted message"
        if (content == null) return "New message"
        return when (content.optString("type")) {
            "text" -> {
                val text = content.optString("text")
                if (text.length > 100) text.take(100) + "..." else text.ifEmpty { "New message" }
            }
            "image" -> "📷 Image"
            // sw.js:318-336 covers the remaining content types too.
            "file" -> "📎 " + content.optString("fileName").ifEmpty { "File" }
            "audio" -> "🎵 Audio message"
            "video", "video_announce" -> "🎬 Video"
            "gif" -> "🎞️ GIF"
            "reaction" -> "Reacted with " + content.optString("emoji").ifEmpty { "👍" }
            else -> "New message"
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").ifEmpty { null }
    }
}

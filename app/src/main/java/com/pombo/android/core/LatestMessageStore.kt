package com.pombo.android.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Last-message previews for the channel list and Explore cards — the web's
 * ChannelLatestMessageManager, which persists them so a cold start paints
 * previews immediately instead of waiting on a resend per channel.
 */
class LatestMessageStore(context: Context) {

    /**
     * [sender] is the already-formatted fallback label (You / senderName / short
     * address). [senderAddress] is the raw address so the list can resolve an ENS
     * name AT RENDER TIME — ENS usually resolves after the preview is stored, and
     * a label baked in at fetch time would stay a raw address forever.
     */
    data class Preview(
        val sender: String,
        val text: String,
        val ts: Long,
        val senderAddress: String = ""
    )

    private val _previews = MutableStateFlow<Map<String, Preview>>(emptyMap())
    val previews: StateFlow<Map<String, Preview>> = _previews.asStateFlow()

    // Persistent, like the web's IndexedDB store — cacheDir gets purged.
    private val file = File(context.filesDir, "channel-previews.json")

    /**
     * In-flight de-duplication, matching the web manager: the sidebar and
     * Explore both ask for the same channel, and without this each surface
     * fires its own resend.
     */
    private val inflight = HashMap<String, kotlinx.coroutines.CompletableDeferred<Preview?>>()

    suspend fun dedup(streamId: String, fetch: suspend () -> Preview?): Preview? {
        val existing = synchronized(inflight) { inflight[streamId] }
        if (existing != null) return existing.await()
        val deferred = kotlinx.coroutines.CompletableDeferred<Preview?>()
        synchronized(inflight) { inflight[streamId] = deferred }
        return try {
            val result = fetch()
            if (result != null) put(streamId, result)
            deferred.complete(result)
            result
        } catch (e: Exception) {
            deferred.complete(null)
            null
        } finally {
            synchronized(inflight) { inflight.remove(streamId) }
        }
    }

    suspend fun warmUp() = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext
            val root = JSONObject(file.readText())
            val loaded = HashMap<String, Preview>()
            root.keys().forEach { key ->
                val o = root.optJSONObject(key) ?: return@forEach
                loaded[key] = Preview(
                    o.optString("sender"), o.optString("text"), o.optLong("ts"), o.optString("senderAddress")
                )
            }
            if (loaded.isNotEmpty()) _previews.value = loaded
        } catch (e: Exception) { /* corrupt cache — start empty */ }
    }

    fun cached(streamId: String): Preview? = _previews.value[streamId]

    suspend fun put(streamId: String, preview: Preview) {
        // Never let an older message overwrite a newer one (the live path in
        // channels.js always wins over a resend that lands late).
        val existing = _previews.value[streamId]
        if (existing != null && existing.ts > preview.ts) return
        _previews.value = _previews.value + (streamId to preview)
        persist()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject()
            _previews.value.forEach { (k, v) ->
                root.put(k, JSONObject().put("sender", v.sender).put("text", v.text)
                    .put("ts", v.ts).put("senderAddress", v.senderAddress))
            }
            // Write-then-rename: a truncate-in-place interrupted mid-write
            // (process death, low battery kill) left a corrupt file behind.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) file.writeText(root.toString())
        } catch (e: Exception) {
            android.util.Log.w("PomboStore", "preview persist failed: ${e.message}")
        }
    }
}

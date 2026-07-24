package com.pombo.android.core

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Channel images, mirroring the web's ChannelImageManager.
 *
 * The web keeps them in an IndexedDB store keyed by adminStreamId and
 * invalidated by the payload hash, so a cold start renders instantly and only
 * refetches what actually changed. This is the same contract on disk:
 *
 *  - memory map first, so every surface (chat header, list, Explore, settings)
 *    reads the same entry with no extra work;
 *  - stale-while-revalidate: a cached image is returned immediately and a
 *    refresh runs in the background;
 *  - inflight de-duplication: concurrent requests for one channel share a
 *    single network round-trip.
 */
class ChannelImageStore(context: Context) {

    /** adminStreamId -> decoded image bytes. */
    private val _images = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val images: StateFlow<Map<String, ByteArray>> = _images.asStateFlow()

    private val hashes = HashMap<String, String>()
    // Payload ts of what we hold, in-memory only (disk entries restart at 0).
    // Lets fetches drop a LAGGING storage read: right after a publish the
    // storage node still serves the previous image, and writing that back
    // here used to regress the fresh optimistic entry.
    private val timestamps = HashMap<String, Long>()
    private val inflight = HashMap<String, CompletableDeferred<ByteArray?>>()
    // filesDir, not cacheDir: the web keeps these in IndexedDB, which the
    // browser does not evict. Android purges cacheDir aggressively (installd
    // was wiping this between launches), which made every start refetch.
    private val dir = File(context.filesDir, "channel-images").apply { mkdirs() }

    private fun keyOf(adminStreamId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(adminStreamId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    /** Loads whatever is on disk into memory — call once at startup. */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        val loaded = HashMap<String, ByteArray>()
        dir.listFiles()?.forEach { f ->
            try {
                // <key>.<hash>.<len>.img — the id mapping lives in the sidecar.
                val meta = File(dir, f.name + ".id")
                if (!meta.exists()) return@forEach
                val adminId = meta.readText()
                loaded[adminId] = f.readBytes()
                hashes[adminId] = f.name.substringAfter('.').substringBefore('.')
            } catch (e: Exception) { /* corrupt entry — ignore */ }
        }
        if (loaded.isNotEmpty()) _images.value = _images.value + loaded
    }

    fun cached(adminStreamId: String): ByteArray? = _images.value[adminStreamId]
    fun cachedHash(adminStreamId: String): String? = hashes[adminStreamId]

    /** Payload ts of the entry we hold; 0 when unknown (e.g. loaded from disk). */
    fun cachedTs(adminStreamId: String): Long = timestamps[adminStreamId] ?: 0L

    /** True when the payload hash matches what we already hold. */
    fun isFresh(adminStreamId: String, hash: String): Boolean = hashes[adminStreamId] == hash

    suspend fun put(adminStreamId: String, hash: String, bytes: ByteArray, ts: Long = 0L) {
        hashes[adminStreamId] = hash
        if (ts > 0L) timestamps[adminStreamId] = ts
        _images.value = _images.value + (adminStreamId to bytes)
        withContext(Dispatchers.IO) {
            try {
                dir.listFiles()?.filter { it.name.startsWith(keyOf(adminStreamId) + ".") }?.forEach { it.delete() }
                val f = File(dir, "${keyOf(adminStreamId)}.$hash.img")
                f.writeBytes(bytes)
                File(dir, f.name + ".id").writeText(adminStreamId)
            } catch (e: Exception) { /* cache is best-effort */ }
        }
    }

    /**
     * Runs [fetch] at most once per channel at a time. Callers that arrive
     * while a fetch is in flight wait for that same result.
     */
    suspend fun dedup(adminStreamId: String, fetch: suspend () -> ByteArray?): ByteArray? {
        val existing = synchronized(inflight) { inflight[adminStreamId] }
        if (existing != null) return existing.await()
        val deferred = CompletableDeferred<ByteArray?>()
        synchronized(inflight) { inflight[adminStreamId] = deferred }
        return try {
            val result = fetch()
            deferred.complete(result)
            result
        } catch (e: Exception) {
            deferred.complete(null)
            null
        } finally {
            synchronized(inflight) { inflight.remove(adminStreamId) }
        }
    }
}

package com.pombo.android.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local ledger of images this account sent (web: secureStorage image ledger in
 * IndexedDB, driven by saveImageToLedger / getUnsyncedImages / markImageSynced).
 *
 * Two jobs: keep sent images available after the stream's retention window has
 * passed, and give blob sync something to push so the same image shows up on a
 * user's other devices.
 *
 * Payloads travel as data URLs because that is what the web stores and pushes;
 * keeping the same representation is what makes the two clients interoperable.
 */
class ImageBlobStore(context: Context) {

    // filesDir, not cacheDir: Android evicts cacheDir aggressively and these
    // are the only remaining copy once storage retention drops the chunks.
    private val dir = File(context.filesDir, "image-blobs").apply { mkdirs() }
    private val ledgerFile = File(context.filesDir, "image-ledger.json")

    data class Record(val imageId: String, val streamId: String, val synced: Boolean)

    private val ledger = LinkedHashMap<String, Record>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = runCatching { ledgerFile.readText() }.getOrNull() ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("imageId")
                if (id.isEmpty()) continue
                ledger[id] = Record(id, o.optString("streamId"), o.optBoolean("synced", false))
            }
        }.onFailure {
            // Silent-empty masked corruption for good; at least say it happened.
            android.util.Log.w("PomboStore", "image ledger unreadable, starting empty: ${it.message}")
        }
    }

    @Synchronized
    private fun persist() {
        val arr = JSONArray()
        ledger.values.forEach {
            arr.put(
                JSONObject()
                    .put("imageId", it.imageId)
                    .put("streamId", it.streamId)
                    .put("synced", it.synced)
            )
        }
        // Write-then-rename: truncate-in-place plus a mid-write process death
        // equalled a zeroed ledger and orphaned every blob on disk.
        runCatching {
            val tmp = File(ledgerFile.parentFile, ledgerFile.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(ledgerFile)) ledgerFile.writeText(arr.toString())
        }.onFailure {
            android.util.Log.w("PomboStore", "image ledger persist failed: ${it.message}")
        }
    }

    private fun blobFile(imageId: String) = File(dir, imageId.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    /** Stores a blob. Returns false when it was already present. */
    suspend fun save(imageId: String, streamId: String, dataUrl: String, synced: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            ensureLoaded()
            if (ledger.containsKey(imageId) && blobFile(imageId).exists()) return@withContext false
            runCatching { blobFile(imageId).writeText(dataUrl) }.getOrElse { return@withContext false }
            synchronized(this@ImageBlobStore) {
                ledger[imageId] = Record(imageId, streamId, synced)
                evictOverCap()
            }
            persist()
            true
        }

    /**
     * Bounded like the web ledger (500 records): least-recently-used first,
     * but synced records before unsynced ones — evicting a blob that never
     * reached the sync stream would lose it for every other device, while a
     * synced one can be pulled back.
     */
    private fun evictOverCap() {
        if (ledger.size <= MAX_RECORDS) return
        val evictable = ledger.values.filter { it.synced } + ledger.values.filter { !it.synced }
        for (victim in evictable) {
            if (ledger.size <= MAX_RECORDS) break
            ledger.remove(victim.imageId)
            runCatching { blobFile(victim.imageId).delete() }
        }
    }

    suspend fun load(imageId: String): String? = withContext(Dispatchers.IO) {
        ensureLoaded()
        val data = runCatching { blobFile(imageId).readText() }.getOrNull()
        if (data != null) {
            // Reinsertion moves the record to the tail of the LinkedHashMap —
            // that order is what evictOverCap and the persisted ledger use as
            // recency, so reads keep a blob alive (LRU).
            synchronized(this@ImageBlobStore) {
                ledger.remove(imageId)?.let { ledger[imageId] = it }
            }
        }
        data
    }

    /**
     * Drops every blob belonging to a stream — leave/block must not keep the
     * conversation's images on disk (web: clearImagesForStream).
     */
    suspend fun clearForStream(streamId: String): Unit = withContext(Dispatchers.IO) {
        if (streamId.isEmpty()) return@withContext
        synchronized(this@ImageBlobStore) {
            ensureLoaded()
            val victims = ledger.values.filter { it.streamId == streamId }
            victims.forEach {
                ledger.remove(it.imageId)
                runCatching { blobFile(it.imageId).delete() }
            }
        }
        persist()
    }

    @Synchronized
    fun unsynced(): List<Record> {
        ensureLoaded()
        return ledger.values.filter { !it.synced }
    }

    /** Every ledger record, for account backup (web: exportImageBlobs). */
    @Synchronized
    fun allRecords(): List<Record> {
        ensureLoaded()
        return ledger.values.toList()
    }

    @Synchronized
    fun markSynced(imageId: String) {
        ensureLoaded()
        val rec = ledger[imageId] ?: return
        if (rec.synced) return
        ledger[imageId] = rec.copy(synced = true)
        persist()
    }

    /** Drops a record whose blob no longer reads back (web: _deleteLedgerRecord). */
    @Synchronized
    fun forget(imageId: String) {
        ensureLoaded()
        ledger.remove(imageId)
        runCatching { blobFile(imageId).delete() }
        persist()
    }

    companion object {
        /** Web secureStorage image-ledger cap. */
        private const val MAX_RECORDS = 500

        fun toDataUrl(bytes: ByteArray, mime: String): String =
            "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

        /** Returns bytes and mime, or null when the string is not a data URL. */
        fun fromDataUrl(dataUrl: String): Pair<ByteArray, String>? {
            if (!dataUrl.startsWith("data:")) return null
            val comma = dataUrl.indexOf(',').takeIf { it > 0 } ?: return null
            val header = dataUrl.substring(5, comma)
            val mime = header.substringBefore(';').ifEmpty { "image/jpeg" }
            val bytes = runCatching {
                android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT)
            }.getOrNull() ?: return null
            return bytes to mime
        }
    }
}

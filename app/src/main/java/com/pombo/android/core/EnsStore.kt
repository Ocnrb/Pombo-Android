package com.pombo.android.core

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * ENS names and avatars — the contract from the web's identity.js:
 *
 *  - persisted (the web writes `pombo_ens_<addr>` / `pombo_ens_avatar_<addr>`
 *    to localStorage, so a reload resolves nothing);
 *  - positive results cached for 24h, negatives for 15 minutes, so a transient
 *    RPC failure retries soon instead of sticking for the whole session;
 *  - in-flight de-duplication that SHARES the result: concurrent callers await
 *    the same lookup rather than skipping it.
 */
class EnsStore(context: Context) {

    private class Entry(val value: String?, val at: Long)

    private val names = HashMap<String, Entry>()
    private val avatars = HashMap<String, Entry>()
    private val nameInflight = HashMap<String, CompletableDeferred<String?>>()
    private val avatarInflight = HashMap<String, CompletableDeferred<String?>>()

    /** Resolved names by lowercase address, for the UI. */
    private val _resolved = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolved: StateFlow<Map<String, String>> = _resolved.asStateFlow()

    private val _avatarUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val avatarUrls: StateFlow<Map<String, String>> = _avatarUrls.asStateFlow()

    private val file = File(context.filesDir, "ens-cache.json")

    suspend fun warmUp() = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext
            val root = JSONObject(file.readText())
            val loadedNames = HashMap<String, String>()
            val loadedAvatars = HashMap<String, String>()
            root.keys().forEach { addr ->
                val o = root.optJSONObject(addr) ?: return@forEach
                val at = o.optLong("at")
                // Separate timestamps: the file used to hold ONE `at` (the max
                // of both), so a failed avatar inherited the name's freshness
                // on every load — and the sync slice refreshes name timestamps
                // constantly, so the avatar's 15-minute negative cache never
                // expired. Legacy files without `avatarAt`: keep a POSITIVE
                // avatar fresh (no refetch burst), but treat a stored null as
                // already stale — its inherited timestamp is exactly the bug.
                val name = if (o.isNull("name")) null else o.optString("name").ifEmpty { null }
                val avatar = if (o.isNull("avatar")) null else o.optString("avatar").ifEmpty { null }
                val avatarAt = o.optLong("avatarAt", if (avatar != null) at else 0L)
                names[addr] = Entry(name, at)
                avatars[addr] = Entry(avatar, avatarAt)
                name?.let { loadedNames[addr] = it }
                avatar?.let { loadedAvatars[addr] = it }
            }
            if (loadedNames.isNotEmpty()) _resolved.value = loadedNames
            if (loadedAvatars.isNotEmpty()) _avatarUrls.value = loadedAvatars
        } catch (e: Exception) { /* corrupt cache — start empty */ }
    }

    private fun fresh(entry: Entry?): Boolean {
        if (entry == null) return false
        val ttl = if (entry.value != null) POSITIVE_TTL_MS else NEGATIVE_TTL_MS
        return System.currentTimeMillis() - entry.at < ttl
    }

    fun cachedName(address: String): String? = names[address.lowercase()]?.value
    fun cachedAvatar(address: String): String? = avatars[address.lowercase()]?.value

    /**
     * Returns the ENS name, resolving through [lookup] only when the cache is
     * cold or stale. Concurrent callers share one lookup.
     */
    suspend fun name(address: String, lookup: suspend () -> String?): String? {
        val key = address.lowercase()
        names[key]?.let { if (fresh(it)) return it.value }

        val existing = synchronized(nameInflight) { nameInflight[key] }
        if (existing != null) return existing.await()
        val deferred = CompletableDeferred<String?>()
        synchronized(nameInflight) { nameInflight[key] = deferred }
        return try {
            val value = lookup()
            names[key] = Entry(value, System.currentTimeMillis())
            if (value != null) _resolved.value = _resolved.value + (key to value)
            persist()
            deferred.complete(value)
            value
        } catch (e: Exception) {
            names[key] = Entry(null, System.currentTimeMillis())
            deferred.complete(null)
            null
        } finally {
            synchronized(nameInflight) { nameInflight.remove(key) }
        }
    }

    /** Same contract for the avatar text record. */
    suspend fun avatar(address: String, lookup: suspend () -> String?): String? {
        val key = address.lowercase()
        avatars[key]?.let { if (fresh(it)) return it.value }

        val existing = synchronized(avatarInflight) { avatarInflight[key] }
        if (existing != null) return existing.await()
        val deferred = CompletableDeferred<String?>()
        synchronized(avatarInflight) { avatarInflight[key] = deferred }
        return try {
            val value = lookup()
            avatars[key] = Entry(value, System.currentTimeMillis())
            if (value != null) _avatarUrls.value = _avatarUrls.value + (key to value)
            persist()
            deferred.complete(value)
            value
        } catch (e: Exception) {
            avatars[key] = Entry(null, System.currentTimeMillis())
            deferred.complete(null)
            null
        } finally {
            synchronized(avatarInflight) { avatarInflight.remove(key) }
        }
    }

    /**
     * The web's `ensCache` sync slice: `{ address: { name, timestamp } }`.
     * Positive resolutions only — negatives expire in 15 minutes and would
     * just bloat every payload.
     */
    fun exportSyncSlice(): JSONObject {
        val out = JSONObject()
        names.forEach { (addr, e) ->
            e.value?.let { out.put(addr, JSONObject().put("name", it).put("timestamp", e.at)) }
        }
        return out
    }

    /**
     * Adopts remote resolutions newer than the local entry (the web merge is
     * a per-key union). Returns whether anything changed; the caller persists.
     */
    fun importSyncSlice(slice: JSONObject): Boolean {
        var changed = false
        slice.keys().forEach { addr ->
            val o = slice.optJSONObject(addr) ?: return@forEach
            val name = if (o.isNull("name")) null else o.optString("name").ifEmpty { null }
            val ts = o.optLong("timestamp")
            val key = addr.lowercase()
            val local = names[key]
            if (local == null || local.at < ts) {
                names[key] = Entry(name, ts)
                if (name != null) _resolved.value = _resolved.value + (key to name)
                changed = true
            }
        }
        return changed
    }

    suspend fun persistNow() = persist()

    private suspend fun persist() = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject()
            (names.keys + avatars.keys).toSet().forEach { addr ->
                val n = names[addr]
                val a = avatars[addr]
                root.put(addr, JSONObject()
                    .put("name", n?.value ?: JSONObject.NULL)
                    .put("avatar", a?.value ?: JSONObject.NULL)
                    .put("at", n?.at ?: 0L)
                    .put("avatarAt", a?.at ?: 0L))
            }
            file.writeText(root.toString())
        } catch (e: Exception) { /* cache is best-effort */ }
    }

    private companion object {
        // config.js identity.ensCacheDurationMs / ensNullCacheDurationMs
        const val POSITIVE_TTL_MS = 24 * 60 * 60 * 1000L
        const val NEGATIVE_TTL_MS = 15 * 60 * 1000L
    }
}

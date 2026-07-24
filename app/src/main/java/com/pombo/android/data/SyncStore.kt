package com.pombo.android.data

import android.content.Context
import org.json.JSONArray

/**
 * Sync bookkeeping (web: the `_appliedTs` / dirty-flag localStorage keys).
 *
 * Applied timestamps are the guard against re-merging a snapshot we already
 * applied — storage replicas can serve an older view, and without this a stale
 * read would overwrite newer local state on every pull.
 */
class SyncStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pombo_sync", Context.MODE_PRIVATE)

    @Volatile var scopeAddress: String? = null

    private fun key(name: String) =
        if (scopeAddress.isNullOrEmpty()) name else "${name}_${scopeAddress!!.lowercase()}"

    var dirty: Boolean
        get() = prefs.getBoolean(key("dirty"), false)
        set(v) = prefs.edit().putBoolean(key("dirty"), v).apply()

    var lastSyncTs: Long
        get() = prefs.getLong(key("last_sync_ts"), 0L)
        set(v) = prefs.edit().putLong(key("last_sync_ts"), v).apply()

    fun appliedTs(): Set<Long> = try {
        val raw = prefs.getString(key("applied_ts"), null) ?: return emptySet()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.optLong(it) }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    fun recordApplied(tsList: List<Long>) {
        if (tsList.isEmpty()) return
        // Bounded: only the most recent matter, and the list is compared against
        // at most `maxPayloads` fetched snapshots.
        val merged = (appliedTs() + tsList).sorted().takeLast(MAX_APPLIED)
        prefs.edit().putString(key("applied_ts"), JSONArray(merged).toString()).apply()
    }

    fun clear() {
        prefs.edit()
            .remove(key("applied_ts"))
            .remove(key("dirty"))
            .remove(key("last_sync_ts"))
            .apply()
    }

    private companion object {
        /** Web keeps 300 applied timestamps; 50 left a thinner margin against
         *  a storage replica serving a deep page of old snapshots. */
        const val MAX_APPLIED = 300
    }
}

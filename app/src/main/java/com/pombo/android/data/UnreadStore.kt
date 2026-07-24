package com.pombo.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Per-channel unread counts for the channel list badge (web `updateUnreadCount`).
 *
 * The web derives the count on the fly by comparing every held message against
 * a per-channel "last access" timestamp. It can do that because it keeps the
 * full message array for every channel in memory; this client only holds
 * messages for the channel that is open, so the count is instead accumulated as
 * messages arrive and cleared when the channel is opened.
 *
 * The visible behaviour matches for everything that happens while the app is
 * running. It differs in one case: messages that arrived while the app was dead
 * are not counted until that channel is opened, because nothing observed them.
 * Push notifications cover that gap, which is why this is a reasonable trade
 * rather than a hole.
 *
 * Kept in plain prefs, not encrypted: a count of unread messages per stream id
 * is not sensitive, and the ids are already visible in the channel list.
 */
class UnreadStore(context: Context) {

    private val prefs = context.getSharedPreferences("pombo_unread", Context.MODE_PRIVATE)

    /** Whose counts these are — badges are per account. */
    @Volatile
    var scopeAddress: String? = null
        set(value) {
            field = value
            _counts.value = load()
        }

    private val _counts = MutableStateFlow(load())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    private fun key() =
        if (scopeAddress.isNullOrEmpty()) "counts" else "counts_${scopeAddress!!.lowercase()}"

    private fun load(): Map<String, Int> = try {
        val o = JSONObject(prefs.getString(key(), null) ?: "{}")
        o.keys().asSequence().associateWith { o.optInt(it) }.filterValues { it > 0 }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun persist(map: Map<String, Int>) {
        val o = JSONObject()
        map.forEach { (k, v) -> if (v > 0) o.put(k, v) }
        prefs.edit().putString(key(), o.toString()).apply()
    }

    fun increment(streamId: String) = add(streamId, 1)

    // Mutators are @Synchronized: increments arrive on the bridge binder
    // thread while clear()/retainOnly() run on main, and the read-modify-write
    // over _counts.value lost whichever update raced (M-S7).
    @Synchronized
    fun add(streamId: String, n: Int) {
        if (n <= 0) return
        val next = _counts.value.toMutableMap()
        next[streamId] = (next[streamId] ?: 0) + n
        _counts.value = next
        persist(next)
    }

    // ==================== activity watermarks ====================

    /**
     * Newest message timestamp already accounted for, per channel — the web's
     * `channelActivity.lastMessageTime`. The activity scan counts only what is
     * newer than this, so re-scanning the same window never double-counts.
     */
    private fun watermarkKey() =
        if (scopeAddress.isNullOrEmpty()) "watermarks" else "watermarks_${scopeAddress!!.lowercase()}"

    private fun loadWatermarks(): MutableMap<String, Long> = try {
        val o = JSONObject(prefs.getString(watermarkKey(), null) ?: "{}")
        o.keys().asSequence().associateWithTo(HashMap()) { o.optLong(it) }
    } catch (e: Exception) {
        HashMap()
    }

    fun watermark(streamId: String): Long = loadWatermarks()[streamId] ?: 0L

    @Synchronized
    fun setWatermark(streamId: String, ts: Long) {
        val map = loadWatermarks()
        if ((map[streamId] ?: 0L) >= ts) return
        map[streamId] = ts
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(watermarkKey(), o.toString()).apply()
    }

    /**
     * Baseline for channels never scanned before: `now`, so the first pass does
     * not report the entire stored history as unread. Mirrors the web's
     * `initializeActivityState`, which seeds from last-access or the clock.
     */
    @Synchronized
    fun seedWatermarks(streamIds: Collection<String>, now: Long = System.currentTimeMillis()) {
        val map = loadWatermarks()
        var changed = false
        streamIds.forEach { if (map[it] == null) { map[it] = now; changed = true } }
        if (!changed) return
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(watermarkKey(), o.toString()).apply()
    }

    /** Called when the channel is opened — the web's "mark as read". */
    @Synchronized
    fun clear(streamId: String) {
        // Opening the channel means everything up to now has been seen, so the
        // watermark moves too. Without this the next activity scan re-counts
        // every message that was on screen a moment ago, because they are still
        // newer than a watermark left behind at the last scan.
        setWatermark(streamId, System.currentTimeMillis())
        if (_counts.value[streamId] == null) return
        val next = _counts.value.toMutableMap().apply { remove(streamId) }
        _counts.value = next
        persist(next)
    }

    /** Drops counts for channels that no longer exist (left, blocked, deleted). */
    @Synchronized
    fun retainOnly(streamIds: Set<String>) {
        val next = _counts.value.filterKeys { it in streamIds }
        if (next.size != _counts.value.size) {
            _counts.value = next
            persist(next)
        }
    }
}

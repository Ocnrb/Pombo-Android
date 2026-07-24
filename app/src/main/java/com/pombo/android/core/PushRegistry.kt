package com.pombo.android.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local map of notification tag -> channel, and the last message timestamp we
 * have already notified about.
 *
 * This is the Android counterpart of the service worker's IndexedDB `channels`
 * store (sw.js), and it exists for one reason: the push carries *only* a 1-byte
 * tag. With 256 possible tags many channels collide by design — that collision
 * is what buys K-anonymity from the relay — so most pushes that arrive are for
 * somebody else's channel. Without this table there is no way to tell.
 */
class PushRegistry(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pombo_push", Context.MODE_PRIVATE)

    data class Entry(
        val streamId: String,
        val tag: String,
        val type: String,
        val name: String,
        val lastTimestamp: Long
    )

    @Volatile var scopeAddress: String? = null

    private fun key(): String =
        if (scopeAddress.isNullOrEmpty()) KEY else "${KEY}_${scopeAddress!!.lowercase()}"

    fun all(): List<Entry> = try {
        val arr = JSONArray(prefs.getString(key(), null) ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val streamId = o.optString("streamId")
            if (streamId.isEmpty()) return@mapNotNull null
            Entry(
                streamId = streamId,
                tag = o.optString("tag"),
                type = o.optString("type", "public"),
                name = o.optString("name", streamId),
                lastTimestamp = o.optLong("lastTimestamp", 0L)
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Tag lookup is the first filter: an unknown tag is noise, not our channel. */
    fun byTag(tag: String): Entry? = all().firstOrNull { it.tag.equals(tag, ignoreCase = true) }

    fun isSubscribed(streamId: String): Boolean = all().any { it.streamId == streamId }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach {
            arr.put(
                JSONObject()
                    .put("streamId", it.streamId)
                    .put("tag", it.tag)
                    .put("type", it.type)
                    .put("name", it.name)
                    .put("lastTimestamp", it.lastTimestamp)
            )
        }
        prefs.edit().putString(key(), arr.toString()).apply()
    }

    fun add(entry: Entry) {
        save(all().filterNot { it.streamId == entry.streamId } + entry)
    }

    fun remove(streamId: String) {
        save(all().filterNot { it.streamId == streamId })
    }

    /** Advances the watermark so the same message never notifies twice. */
    fun updateLastSeen(streamId: String, timestamp: Long) {
        val entries = all().map {
            if (it.streamId == streamId && timestamp > it.lastTimestamp) {
                it.copy(lastTimestamp = timestamp)
            } else it
        }
        save(entries)
    }

    private companion object {
        const val KEY = "push_channels"
    }
}

package com.pombo.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reactions this account sent that no resend can ever return (web:
 * secureStorage sentReactions). A DM reaction is published to the PEER's
 * inbox, and inbox reads are owner-only — your own reactions never come back;
 * write-only channels are never resubscribed at all. Persisted so they
 * survive restarts, and exported as the `sentReactions` sync slice so other
 * devices see them too.
 *
 * Shape mirrors the web byte-for-byte:
 * `{ streamId: { messageId: { emoji: [userAddresses] } } }`.
 */
class SentReactionsStore(context: Context) {

    private val prefs by lazy {
        com.pombo.android.core.SecurePrefs.create(context, "pombo_sent_reactions")
    }

    @Volatile var scopeAddress: String? = null
    @Volatile var memoryOnly: Boolean = false

    private fun key(): String = "sent_reactions_" + (scopeAddress?.lowercase() ?: "none")

    private fun readAll(): JSONObject =
        runCatching { JSONObject(prefs.getString(key(), null) ?: "{}") }.getOrElse { JSONObject() }

    private fun writeAll(all: JSONObject) {
        prefs.edit().putString(key(), all.toString()).apply()
    }

    /** Mirrors web addSentReaction: dedup on add, prune empty maps on remove. */
    @Synchronized
    fun record(streamId: String, messageId: String, emoji: String, user: String, add: Boolean) {
        if (memoryOnly) return
        val all = readAll()
        val stream = all.optJSONObject(streamId) ?: JSONObject().also { all.put(streamId, it) }
        val msg = stream.optJSONObject(messageId) ?: JSONObject().also { stream.put(messageId, it) }
        val users = msg.optJSONArray(emoji) ?: JSONArray()
        val list = (0 until users.length()).mapNotNull { users.optString(it).ifEmpty { null } }
        if (add) {
            if (list.none { it.equals(user, ignoreCase = true) }) {
                msg.put(emoji, JSONArray(list + user))
            }
        } else {
            val kept = list.filterNot { it.equals(user, ignoreCase = true) }
            if (kept.isEmpty()) msg.remove(emoji) else msg.put(emoji, JSONArray(kept))
            if (msg.length() == 0) stream.remove(messageId)
            if (stream.length() == 0) all.remove(streamId)
        }
        writeAll(all)
    }

    /** `{ messageId: { emoji: [users] } }` for one conversation, or null. */
    fun forStream(streamId: String): JSONObject? {
        if (memoryOnly) return null
        return readAll().optJSONObject(streamId)
    }

    fun exportAll(): JSONObject = if (memoryOnly) JSONObject() else readAll()

    /** Replaces with a merged slice — post-SyncMerge it is a superset union. */
    @Synchronized
    fun importAll(slice: JSONObject) {
        if (memoryOnly) return
        writeAll(slice)
    }
}

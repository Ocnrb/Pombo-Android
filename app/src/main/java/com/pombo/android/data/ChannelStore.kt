package com.pombo.android.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence of the channel list in EncryptedSharedPreferences (the role of
 * the web secureStorage for pombo_channels). Insertion order preserved.
 */
class ChannelStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        com.pombo.android.core.SecurePrefs.create(context, "pombo_channels")
    }

    /**
     * Whose channels these are. The web scopes secure storage by address
     * (CONFIG.storageKeys.secure) so each account sees only its own channels,
     * and guest sessions are memory-only — `initAsGuest` starts from an empty
     * cache and `saveToStorage` returns early. Same contract here.
     */
    @Volatile var scopeAddress: String? = null
    @Volatile var memoryOnly: Boolean = false

    private fun key(): String =
        if (scopeAddress.isNullOrEmpty()) KEY_CHANNELS
        else "${KEY_CHANNELS}_${scopeAddress!!.lowercase()}"

    fun load(): List<Channel> {
        // Guests never see persisted channels — not even a previous account's.
        if (memoryOnly) return emptyList()
        val raw = prefs.getString(key(), null)
            // One-time migration from the pre-scoping global key.
            ?: prefs.getString(KEY_CHANNELS, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try { Channel.fromJson(arr.getJSONObject(i)) } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(channels: List<Channel>) {
        if (memoryOnly) return
        val arr = JSONArray()
        channels.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(key(), arr.toString()).apply()
    }

    /**
     * Leave tombstones, `messageStreamId -> timestamp` (web: channelsLeftAt).
     * Sync merges channels as a last-write-wins set; without a tombstone a
     * pull of an older snapshot would silently re-add a channel the user left.
     */
    fun leftAtJson(): JSONObject = try {
        JSONObject(prefs.getString(leftAtKey(), null) ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    fun saveLeftAt(o: JSONObject) {
        if (memoryOnly) return
        prefs.edit().putString(leftAtKey(), o.toString()).apply()
    }

    fun markLeft(messageStreamId: String, ts: Long = System.currentTimeMillis()) {
        saveLeftAt(leftAtJson().put(messageStreamId, ts))
    }

    private fun leftAtKey(): String =
        if (scopeAddress.isNullOrEmpty()) KEY_LEFT_AT
        else "${KEY_LEFT_AT}_${scopeAddress!!.lowercase()}"

    /**
     * User's manual channel order (web: secureStorage channelOrder) — a list of
     * messageStreamIds. The channel list is sorted by this; ids not present sort
     * to the end in their natural order.
     */
    fun loadOrder(): List<String> = try {
        val raw = prefs.getString(orderKey(), null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
    } catch (e: Exception) { emptyList() }

    fun saveOrder(order: List<String>) {
        if (memoryOnly) return
        prefs.edit().putString(orderKey(), JSONArray(order).toString()).apply()
    }

    private fun orderKey(): String =
        if (scopeAddress.isNullOrEmpty()) KEY_ORDER
        else "${KEY_ORDER}_${scopeAddress!!.lowercase()}"

    private companion object {
        const val KEY_CHANNELS = "channels"
        const val KEY_LEFT_AT = "channels_left_at"
        const val KEY_ORDER = "channels_order"
    }
}

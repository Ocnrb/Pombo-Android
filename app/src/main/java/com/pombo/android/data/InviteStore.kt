package com.pombo.android.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pending channel invites (web: notifications.js save/loadPendingInvites via
 * secureStorage). Without this an invite that arrives while the app is closed
 * is gone for good — P3 is a live subscription, nothing replays it.
 *
 * Scoped per account for the same reason the web clears on load: otherwise
 * account A's invites get re-saved under account B's key.
 */
class InviteStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        com.pombo.android.core.SecurePrefs.create(context, "pombo_invites")
    }

    @Volatile var scopeAddress: String? = null

    private fun key(): String =
        if (scopeAddress.isNullOrEmpty()) KEY else "${KEY}_${scopeAddress!!.lowercase()}"

    fun load(): List<StoredInvite> {
        if (scopeAddress.isNullOrEmpty()) return emptyList()
        return parseInvites(prefs.getString(key(), null))
    }

    fun save(invites: List<StoredInvite>) {
        if (scopeAddress.isNullOrEmpty()) return
        prefs.edit().putString(key(), serializeInvites(invites)).apply()
    }

    private fun parseInvites(raw: String?): List<StoredInvite> {
        if (raw == null) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                StoredInvite(
                    inviteId = o.optString("inviteId").ifEmpty { return@mapNotNull null },
                    from = o.optString("from"),
                    streamId = o.optString("streamId").ifEmpty { return@mapNotNull null },
                    name = o.optString("name"),
                    type = o.optString("type").ifEmpty { "public" },
                    password = if (o.isNull("password")) null else o.optString("password").ifEmpty { null }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeInvites(invites: List<StoredInvite>): String {
        val arr = JSONArray()
        invites.forEach {
            arr.put(
                JSONObject()
                    .put("inviteId", it.inviteId)
                    .put("from", it.from)
                    .put("streamId", it.streamId)
                    .put("name", it.name)
                    .put("type", it.type)
                    .put("password", it.password ?: JSONObject.NULL)
            )
        }
        return arr.toString()
    }

    /**
     * Invites the user already answered (accepted or dismissed). P3 replays on
     * every connect now, so without this ledger a dismissed invite would come
     * back from storage each time. Bounded to the most recent 200 ids.
     */
    fun isDismissed(inviteId: String): Boolean {
        if (scopeAddress.isNullOrEmpty()) return false
        return dismissedIds().contains(inviteId)
    }

    fun markDismissed(inviteId: String) {
        if (scopeAddress.isNullOrEmpty()) return
        val ids = dismissedIds().toMutableList()
        ids.remove(inviteId)
        ids.add(inviteId)
        while (ids.size > 200) ids.removeAt(0)
        prefs.edit().putString(dismissedKey(), JSONArray(ids).toString()).apply()
    }

    private fun dismissedIds(): List<String> = try {
        val arr = JSONArray(prefs.getString(dismissedKey(), null) ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }
    } catch (e: Exception) {
        emptyList()
    }

    private fun dismissedKey(): String = "${DISMISSED}_${scopeAddress!!.lowercase()}"

    /**
     * Full records of dismissed invites, newest dismissal first — the bell's
     * "All" view renders these so a mis-tapped dismiss stays acceptable.
     * Separate from the id ledger above: the ledger remembers the last 200
     * ids purely for replay suppression, this keeps the last
     * [MAX_DISMISSED_RECORDS] complete, renderable invites.
     */
    fun dismissedInvites(): List<StoredInvite> {
        if (scopeAddress.isNullOrEmpty()) return emptyList()
        return parseInvites(prefs.getString(dismissedFullKey(), null))
    }

    fun recordDismissed(invite: StoredInvite) {
        if (scopeAddress.isNullOrEmpty()) return
        val list = dismissedInvites().filterNot { it.inviteId == invite.inviteId }.toMutableList()
        list.add(0, invite)
        while (list.size > MAX_DISMISSED_RECORDS) list.removeAt(list.size - 1)
        prefs.edit().putString(dismissedFullKey(), serializeInvites(list)).apply()
    }

    /** Accepting an invite from the "All" view consumes its dismissed record. */
    fun removeDismissedRecord(inviteId: String) {
        if (scopeAddress.isNullOrEmpty()) return
        val list = dismissedInvites().filterNot { it.inviteId == inviteId }
        prefs.edit().putString(dismissedFullKey(), serializeInvites(list)).apply()
    }

    private fun dismissedFullKey(): String = "${DISMISSED_FULL}_${scopeAddress!!.lowercase()}"

    data class StoredInvite(
        val inviteId: String,
        val from: String,
        val streamId: String,
        val name: String,
        val type: String,
        val password: String?
    )

    private companion object {
        const val KEY = "pending_invites"
        const val DISMISSED = "dismissed_invites"
        const val DISMISSED_FULL = "dismissed_invites_full"
        const val MAX_DISMISSED_RECORDS = 50
    }
}

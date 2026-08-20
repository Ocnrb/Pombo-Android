package com.pombo.android.data

import android.content.Context
import com.pombo.android.core.SecurePrefs
import org.json.JSONObject

/**
 * Persisted epoch keys per channel (web: secureStorage epochKeys slice).
 *
 * Epoch keys are CHANNEL keys, not ephemeral identities — persisting them is
 * what avoids a KEY_REQUEST round-trip on every session (D14). Encrypted at
 * rest via EncryptedSharedPreferences.
 *
 * Value per messageStreamId: {"epochs":{keyId:{"keyHex","keyHash","epoch"}},
 * "currentEpoch": n} — same shape the web persists.
 */
class EpochKeyStore(context: Context) {

    private val prefs = SecurePrefs.create(context, "pombo_epoch_keys")

    /**
     * Whose epoch keys these are — same contract as [com.pombo.android.data.ChannelStore]:
     * scoped by address so one account's channel keys never surface under
     * another, and guest sessions are memory-only (a guest starts with no
     * key state and leaves none behind).
     */
    @Volatile var scopeAddress: String? = null
    @Volatile var memoryOnly: Boolean = false

    private fun key(messageStreamId: String): String =
        if (scopeAddress.isNullOrEmpty()) messageStreamId
        else "${scopeAddress!!.lowercase()}_$messageStreamId"

    fun load(messageStreamId: String): JSONObject? {
        if (memoryOnly) return null
        val raw = prefs.getString(key(messageStreamId), null)
            // One-time migration from the pre-scoping global key.
            ?: prefs.getString(messageStreamId, null)
            ?: return null
        return try { JSONObject(raw) } catch (e: Exception) { null }
    }

    fun save(messageStreamId: String, data: JSONObject) {
        if (memoryOnly) return
        prefs.edit().putString(key(messageStreamId), data.toString()).apply()
    }

    fun clear(messageStreamId: String) {
        prefs.edit().remove(key(messageStreamId)).apply()
    }
}

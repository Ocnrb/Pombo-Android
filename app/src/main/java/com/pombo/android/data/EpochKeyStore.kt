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

    fun load(messageStreamId: String): JSONObject? =
        prefs.getString(messageStreamId, null)?.let {
            try { JSONObject(it) } catch (e: Exception) { null }
        }

    fun save(messageStreamId: String, data: JSONObject) {
        prefs.edit().putString(messageStreamId, data.toString()).apply()
    }

    fun clear(messageStreamId: String) {
        prefs.edit().remove(messageStreamId).apply()
    }
}

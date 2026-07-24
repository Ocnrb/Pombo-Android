package com.pombo.android.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A trusted contact, mirroring the web schema exactly
 * (identity.js addTrustedContact): the same JSON shape is used for local
 * persistence and the `trustedContacts` sync slice, so nothing the web wrote
 * (notes, timestamps) is lost when this device wins the slice merge. The web
 * drops entries whose `address` is not a string, so that field is mandatory.
 */
data class Contact(
    val address: String,
    val nickname: String?,
    val notes: String? = null,
    val addedAt: Long? = null,
    val addedBy: String? = null,
    val updatedAt: Long? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("address", address)
        .put("nickname", nickname ?: JSONObject.NULL)
        .put("notes", notes ?: JSONObject.NULL)
        .put("addedAt", addedAt ?: JSONObject.NULL)
        .put("addedBy", addedBy ?: JSONObject.NULL)
        .put("updatedAt", updatedAt ?: JSONObject.NULL)

    companion object {
        /**
         * Accepts both the web shape (`nickname`) and the legacy Android one
         * (`name`), covering pre-existing local prefs and old sync snapshots.
         */
        fun fromJson(o: JSONObject) = Contact(
            address = o.getString("address"),
            nickname = o.stringOrNull("nickname") ?: o.stringOrNull("name"),
            notes = o.stringOrNull("notes"),
            addedAt = o.longOrNull("addedAt"),
            addedBy = o.stringOrNull("addedBy"),
            updatedAt = o.longOrNull("updatedAt")
        )

        private fun JSONObject.stringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).ifEmpty { null }

        private fun JSONObject.longOrNull(key: String): Long? =
            if (isNull(key)) null else optLong(key).takeIf { it != 0L }
    }
}

/** Persists trusted contacts in EncryptedSharedPreferences (per-device). */
class ContactsStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        com.pombo.android.core.SecurePrefs.create(context, "pombo_contacts")
    }

    /** Per-account scoping + guest memory-only, same as ChannelStore. */
    @Volatile var scopeAddress: String? = null
    @Volatile var memoryOnly: Boolean = false

    private fun key(): String =
        if (scopeAddress.isNullOrEmpty()) KEY else "${KEY}_${scopeAddress!!.lowercase()}"

    fun load(): List<Contact> {
        if (memoryOnly) return emptyList()
        val raw = prefs.getString(key(), null)
            ?: prefs.getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try { Contact.fromJson(arr.getJSONObject(i)) } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun save(contacts: List<Contact>) {
        if (memoryOnly) return
        val arr = JSONArray()
        contacts.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(key(), arr.toString()).apply()
    }

    private companion object { const val KEY = "contacts" }
}

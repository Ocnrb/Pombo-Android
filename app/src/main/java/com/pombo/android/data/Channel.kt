package com.pombo.android.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted channel — same shape as the web saveChannels (channels.js), so that
 * cross-device sync trivial in Phase 6. Messages/reactions/adminState
 * are never persisted (rebuilt from the network).
 */
data class Channel(
    val messageStreamId: String,
    val ephemeralStreamId: String,
    val adminStreamId: String,
    val name: String,
    val type: String,                 // 'public' | 'password' | 'native'
    val createdAt: Long = System.currentTimeMillis(),
    val createdBy: String? = null,
    val joinedAt: Long? = null,
    val password: String? = null,
    val members: List<String> = emptyList(),
    val storageEnabled: Boolean = false,
    /** Which storage node holds the history: 'streamr' (Pombo cluster) or 'custom'. */
    val storageProvider: String = "streamr",
    /** Retention in days, as accepted by the storage node. Null when unknown. */
    val storageDays: Int? = null,
    val exposure: String = "hidden",  // 'visible' | 'hidden'
    val description: String = "",
    val language: String = "",
    val category: String = "",
    /**
     * Local-only grouping ('personal' | 'community'), never published on-chain.
     * The web defaults it to 'personal' for closed channels and null otherwise.
     */
    val classification: String? = null,
    val readOnly: Boolean = false,
    val writeOnly: Boolean = false,
    val peerAddress: String? = null,  // DM only: the peer's address
    /**
     * When this device last edited the channel metadata. The Graph indexes with
     * a lag, so a refresh whose `updatedAt` predates this would revert a rename
     * the admin just made (web: `channel.metaUpdatedAt`).
     */
    val metaUpdatedAt: Long? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("peerAddress", peerAddress ?: JSONObject.NULL)
        .put("messageStreamId", messageStreamId)
        .put("ephemeralStreamId", ephemeralStreamId)
        .put("adminStreamId", adminStreamId)
        .put("name", name)
        .put("type", type)
        .put("createdAt", createdAt)
        .put("createdBy", createdBy ?: JSONObject.NULL)
        .put("joinedAt", joinedAt ?: JSONObject.NULL)
        .put("password", password ?: JSONObject.NULL)
        .put("members", JSONArray(members))
        .put("storageEnabled", storageEnabled)
        .put("storageProvider", storageProvider)
        .put("storageDays", storageDays ?: JSONObject.NULL)
        .put("exposure", exposure)
        .put("description", description)
        .put("language", language)
        .put("category", category)
        .put("classification", classification ?: JSONObject.NULL)
        .put("readOnly", readOnly)
        .put("writeOnly", writeOnly)
        .put("metaUpdatedAt", metaUpdatedAt ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): Channel {
            val members = mutableListOf<String>()
            o.optJSONArray("members")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i)?.let { members.add(it) }
            }
            return Channel(
                messageStreamId = o.getString("messageStreamId"),
                ephemeralStreamId = o.optString("ephemeralStreamId"),
                adminStreamId = o.optString("adminStreamId"),
                name = o.optString("name", "channel"),
                type = o.optString("type", "public"),
                createdAt = o.optLong("createdAt", 0L),
                // isNull first: Android's optString yields the literal "null" for JSON null.
                createdBy = if (o.isNull("createdBy")) null else o.optString("createdBy").ifEmpty { null },
                joinedAt = if (o.isNull("joinedAt")) null else o.optLong("joinedAt"),
                password = if (o.isNull("password")) null else o.optString("password").ifEmpty { null },
                members = members,
                storageEnabled = o.optBoolean("storageEnabled", false),
                storageProvider = o.optString("storageProvider", "streamr").ifEmpty { "streamr" },
                storageDays = if (o.isNull("storageDays")) null else o.optInt("storageDays"),
                exposure = o.optString("exposure", "hidden"),
                description = o.optString("description", ""),
                language = o.optString("language", ""),
                category = o.optString("category", ""),
                classification = if (o.isNull("classification")) null
                    else o.optString("classification").ifEmpty { null },
                readOnly = o.optBoolean("readOnly", false),
                writeOnly = o.optBoolean("writeOnly", false),
                peerAddress = if (o.isNull("peerAddress")) null else o.optString("peerAddress").ifEmpty { null },
                metaUpdatedAt = if (o.isNull("metaUpdatedAt")) null else o.optLong("metaUpdatedAt")
            )
        }
    }
}

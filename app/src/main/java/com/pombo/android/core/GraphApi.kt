package com.pombo.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Graph (Streamr subgraph on Arbitrum One) — mirror of the web src/js/graph.js.
 *
 * This is how channels are actually discovered: `curation/explore.json` only
 * pins/hides stream IDs, the list itself comes from here. Also the source of a
 * stream's owner and Pombo metadata without touching the SDK.
 */
object GraphApi {

    /**
     * Shared fallback key — the same one the web ships in its bundle, so it is
     * already public. It is rate-limited and billed to the Pombo account, hence
     * the user override below (web: Settings → API → The Graph API Key).
     */
    private const val DEFAULT_API_KEY = "f56ddaf00b5cbe1eeb1bc003072b5422"
    private const val SUBGRAPH_ID = "EGWFdhhiWypDuz22Uy7b3F69E9MEkyfU9iAQMttkH5Rj"

    /** CONFIG.graph.cacheDurationMs */
    private const val CACHE_MS = 30_000L

    /** Set from the user's settings; null falls back to the shared key. */
    @Volatile var userApiKey: String? = null

    private val apiKey: String
        get() = userApiKey?.trim()?.ifEmpty { null } ?: DEFAULT_API_KEY

    private val endpoint: String
        get() = "https://gateway.thegraph.com/api/$apiKey/subgraphs/id/$SUBGRAPH_ID"

    private class Entry(val data: JSONObject, val at: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    /** Channel as published in the stream's Pombo metadata (abbreviated keys). */
    data class ChannelInfo(
        val streamId: String,
        val name: String?,
        val displayName: String,
        val type: String,
        val description: String,
        val language: String,
        val category: String,
        val readOnly: Boolean,
        val exposure: String,
        val createdAt: Long,
        val updatedAt: Long,
        /** Streamr guarantees the address prefixing the stream ID owns it. */
        val createdBy: String
    )

    private suspend fun query(cacheKey: String, gql: String): JSONObject? {
        cache[cacheKey]?.let { if (System.currentTimeMillis() - it.at < CACHE_MS) return it.data }
        val data = withContext(Dispatchers.IO) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(JSONObject().put("query", gql).toString().toByteArray()) }
                if (conn.responseCode != 200) return@withContext null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                // GraphQL reports failures in `errors`, not the HTTP status.
                if (root.has("errors")) null else root.optJSONObject("data")
            } catch (e: Exception) {
                null
            }
        } ?: return null
        cache[cacheKey] = Entry(data, System.currentTimeMillis())
        return data
    }

    /**
     * Pombo metadata lives JSON-encoded inside the stream's `description`,
     * which is itself inside the stream's JSON metadata.
     */
    private fun pomboMeta(metadata: String?): JSONObject? = try {
        val outer = JSONObject(metadata ?: "{}")
        JSONObject(outer.optString("description", "{}"))
    } catch (e: Exception) {
        null
    }

    /**
     * `optString` returns the literal string "null" for a JSON null, which then
     * happily flows into the UI as a channel named "null". Every metadata read
     * here goes through this.
     */
    private fun JSONObject.str(key: String, fallback: String = ""): String =
        if (isNull(key)) fallback else optString(key, fallback)

    private fun toChannelInfo(streamId: String, meta: JSONObject, createdAt: Long, updatedAt: Long): ChannelInfo {
        val name = meta.str("n").ifEmpty { null }
        return ChannelInfo(
            streamId = streamId,
            name = name,
            displayName = name
                ?: streamId.substringAfter('/', "").substringBefore('_').ifEmpty { "Unknown" },
            type = meta.str("t").ifEmpty { "public" },
            description = meta.str("d"),
            language = meta.str("l"),
            category = meta.str("c"),
            readOnly = meta.optBoolean("r", false),
            exposure = meta.str("e", "hidden"),
            createdAt = meta.optLong("ts", 0L).takeIf { it > 0 } ?: (createdAt * 1000),
            updatedAt = updatedAt * 1000,
            createdBy = streamId.substringBefore('/')
        )
    }

    /**
     * Public channels for the Explore view: streams with a public permission
     * whose metadata mentions pombo. Only `exposure: visible` ones are listed.
     */
    suspend fun getPublicPomboChannels(first: Int = 50, skip: Int = 0): List<ChannelInfo> {
        val gql = """
            query GetPublicPomboChannels {
                streams(
                    first: $first,
                    skip: $skip,
                    orderBy: updatedAt,
                    orderDirection: desc,
                    where: {
                        permissions_: { userAddress: "0x0000000000000000000000000000000000000000" },
                        metadata_contains: "pombo"
                    },
                    subgraphError: allow
                ) { id metadata createdAt updatedAt }
            }
        """.trimIndent()
        val streams = query("pombo_public:$first:$skip", gql)?.optJSONArray("streams") ?: return emptyList()
        val out = mutableListOf<ChannelInfo>()
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            val id = s.optString("id")
            // Ephemeral streams share the metadata but are not channels.
            if (id.isEmpty() || id.endsWith(StreamConstants.SUFFIX_EPHEMERAL)) continue
            val meta = pomboMeta(s.optString("metadata")) ?: continue
            if (meta.optString("a") != "pombo" || meta.optString("e") != "visible") continue
            out.add(toChannelInfo(id, meta, s.optLong("createdAt"), s.optLong("updatedAt")))
        }
        return out
    }

    /** Pombo metadata for a single stream, or null when it isn't a Pombo channel. */
    suspend fun getChannelInfo(streamId: String): ChannelInfo? {
        val gql = """
            query GetChannelInfo {
                stream(id: "$streamId") { id metadata createdAt updatedAt }
            }
        """.trimIndent()
        val stream = query("channel_info:$streamId", gql)?.optJSONObject("stream") ?: return null
        val meta = pomboMeta(stream.optString("metadata")) ?: return null
        if (meta.optString("a") != "pombo") return null
        return toChannelInfo(streamId, meta, stream.optLong("createdAt"), stream.optLong("updatedAt"))
    }

    /** Drops cached queries so a permission change is visible immediately. */
    fun clearCache() = cache.clear()

    /**
     * Addresses with an explicit permission on a stream — the membership list
     * of a Closed channel (web: graphAPI.getStreamMembers). Public assignments
     * and expired grants are filtered out.
     */
    suspend fun streamMembers(streamId: String): List<String> {
        val gql = """
            query GetStreamPermissions {
                streamPermissions(where: { stream: "$streamId" }, first: 200) {
                    userAddress canGrant canEdit canDelete
                    publishExpiration subscribeExpiration
                }
            }
        """.trimIndent()
        val perms = query("members:$streamId", gql)?.optJSONArray("streamPermissions") ?: return emptyList()
        val now = System.currentTimeMillis() / 1000
        val out = mutableListOf<String>()
        for (i in 0 until perms.length()) {
            val p = perms.optJSONObject(i) ?: continue
            val addr = p.optString("userAddress")
            if (addr.isEmpty() || addr == ZERO_ADDRESS) continue
            // null expiration means unlimited, matching the web's check.
            val pubOk = p.isNull("publishExpiration") || p.optLong("publishExpiration") > now
            val subOk = p.isNull("subscribeExpiration") || p.optLong("subscribeExpiration") > now
            if (pubOk || subOk) out.add(addr)
        }
        return out.distinct()
    }

    const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

    /** One address's on-chain permissions on a stream (web: streamPermissions). */
    data class StreamPermission(
        val userAddress: String,
        val canGrant: Boolean,
        val canEdit: Boolean,
        val canDelete: Boolean,
        /** Derived from the expirations, like the web (null = unlimited). */
        val canSubscribe: Boolean,
        val canPublish: Boolean
    ) {
        val isPublic: Boolean get() = userAddress.isEmpty() || userAddress.equals(ZERO_ADDRESS, true)
    }

    /**
     * The full permission matrix for a stream (web: graphAPI.getStreamPermissions),
     * used by the Members panel's Stream Permissions list. Unlike [streamMembers]
     * this keeps the PUBLIC (zero-address) row and every flag.
     */
    suspend fun getStreamPermissions(streamId: String): List<StreamPermission> {
        val gql = """
            query GetStreamPermissions {
                streamPermissions(first: 100, where: { stream: "$streamId" }, subgraphError: allow) {
                    userAddress canEdit canDelete canGrant
                    publishExpiration subscribeExpiration
                }
            }
        """.trimIndent()
        val perms = query("permissions:$streamId", gql)?.optJSONArray("streamPermissions") ?: return emptyList()
        val now = System.currentTimeMillis() / 1000
        val out = mutableListOf<StreamPermission>()
        for (i in 0 until perms.length()) {
            val p = perms.optJSONObject(i) ?: continue
            out.add(
                StreamPermission(
                    userAddress = p.optString("userAddress"),
                    canGrant = p.optBoolean("canGrant"),
                    canEdit = p.optBoolean("canEdit"),
                    canDelete = p.optBoolean("canDelete"),
                    canSubscribe = p.isNull("subscribeExpiration") || p.optLong("subscribeExpiration") > now,
                    canPublish = p.isNull("publishExpiration") || p.optLong("publishExpiration") > now
                )
            )
        }
        // Revoking zeroes the on-chain entry but The Graph keeps it in the
        // index (Streamr SDK carries the same workaround with a cleanup TODO).
        // An address with no effective permission is an ex-member, not a row —
        // the PUBLIC row is kept regardless.
        return out.filter {
            it.isPublic || it.canSubscribe || it.canPublish || it.canGrant || it.canEdit || it.canDelete
        }
    }

    /**
     * Stream owner: the member holding grant+edit+delete. Falls back to the
     * stream ID prefix, which Streamr enforces at creation time.
     */
    suspend fun getStreamOwner(streamId: String): String? {
        val gql = """
            query GetStreamPermissions {
                streamPermissions(where: { stream: "$streamId" }, first: 100) {
                    userAddress canGrant canEdit canDelete
                }
            }
        """.trimIndent()
        val perms = query("perms:$streamId", gql)?.optJSONArray("streamPermissions")
            ?: return streamId.substringBefore('/').ifEmpty { null }
        for (i in 0 until perms.length()) {
            val p = perms.optJSONObject(i) ?: continue
            if (p.optBoolean("canGrant") && p.optBoolean("canEdit") && p.optBoolean("canDelete")) {
                p.optString("userAddress").ifEmpty { null }?.let { return it }
            }
        }
        return streamId.substringBefore('/').ifEmpty { null }
    }
}

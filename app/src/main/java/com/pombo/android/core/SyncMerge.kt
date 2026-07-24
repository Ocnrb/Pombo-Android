package com.pombo.android.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-device state merge — port of the web's syncMerge.js.
 *
 * Everything here operates on the raw payload object rather than a Kotlin
 * model, and that is deliberate: the web syncs slices this client does not
 * manage (`sentMessages`, `sentReactions`, `blockedPeers`, `dmLeftAt`). If
 * Android rebuilt the payload from its own state it would push back a snapshot
 * with those slices missing and wipe them on every other device. Unknown keys
 * survive a round trip untouched.
 */
object SyncMerge {

    /** Web CONFIG.dm.maxSentMessages. */
    private const val MAX_SENT_MESSAGES = 200

    /** Slices resolved by `sliceTs` latest-wins. */
    private val TIMESTAMPED_SLICES = listOf(
        "blockedPeers", "dmLeftAt", "trustedContacts", "username", "graphApiKey"
    )

    fun mergeState(base: JSONObject?, incoming: JSONObject?): JSONObject {
        val out = JSONObject()

        // Carry over any slice neither side models here, so a future web field
        // is not silently dropped by this client.
        (base ?: JSONObject()).keys().forEach { k -> out.put(k, (base ?: JSONObject()).get(k)) }
        (incoming ?: JSONObject()).keys().forEach { k ->
            if (!out.has(k)) out.put(k, (incoming ?: JSONObject()).get(k))
        }

        val (channels, leftAt) = mergeChannels(
            base?.optJSONArray("channels"),
            incoming?.optJSONArray("channels"),
            base?.optJSONObject("channelsLeftAt"),
            incoming?.optJSONObject("channelsLeftAt")
        )
        out.put("channels", channels)
        out.put("channelsLeftAt", leftAt)

        out.put(
            "sentMessages",
            mergeSentMessages(base?.optJSONObject("sentMessages"), incoming?.optJSONObject("sentMessages"))
        )
        out.put(
            "sentReactions",
            mergeSentReactions(base?.optJSONObject("sentReactions"), incoming?.optJSONObject("sentReactions"))
        )

        val sliceTs = JSONObject()
        TIMESTAMPED_SLICES.forEach { key ->
            // graphApiKey keeps the web's legacy rule: with no timestamps on
            // either side, a null incoming key must not delete a configured one.
            if (key == "graphApiKey" &&
                base?.optJSONObject("sliceTs")?.optLong("graphApiKey", 0L) ?: 0L == 0L &&
                incoming?.optJSONObject("sliceTs")?.optLong("graphApiKey", 0L) ?: 0L == 0L
            ) {
                val v = incoming?.optStringOrNull("graphApiKey") ?: base?.optStringOrNull("graphApiKey")
                out.put("graphApiKey", v ?: JSONObject.NULL)
                sliceTs.put("graphApiKey", 0L)
                return@forEach
            }
            val picked = pickSlice(key, base, incoming, sliceTs)
            out.put(key, picked ?: JSONObject.NULL)
        }
        out.put("sliceTs", sliceTs)

        // ensCache is a plain shallow merge, incoming wins per key.
        val ens = JSONObject()
        base?.optJSONObject("ensCache")?.let { b -> b.keys().forEach { ens.put(it, b.get(it)) } }
        incoming?.optJSONObject("ensCache")?.let { i -> i.keys().forEach { ens.put(it, i.get(it)) } }
        out.put("ensCache", ens)

        return out
    }

    fun mergeSeries(base: JSONObject?, payloads: List<JSONObject>): JSONObject {
        var merged = base ?: JSONObject()
        payloads.forEach { merged = mergeState(merged, it) }
        return merged
    }

    /**
     * LWW-element-set over channels. Membership is decided by comparing the
     * join timestamp against the leave tombstone; the most recent action wins
     * and a tie goes to Join. Snapshot replacement would let an older remote
     * snapshot delete a channel this device just joined.
     */
    private fun mergeChannels(
        baseChannels: JSONArray?,
        incomingChannels: JSONArray?,
        baseLeftAt: JSONObject?,
        incomingLeftAt: JSONObject?
    ): Pair<JSONArray, JSONObject> {
        val leftAt = JSONObject()
        listOfNotNull(baseLeftAt, incomingLeftAt).forEach { src ->
            src.keys().forEach { id ->
                val ts = src.optLong(id, -1L)
                if (ts >= 0 && (!leftAt.has(id) || ts > leftAt.optLong(id))) leftAt.put(id, ts)
            }
        }

        val byId = LinkedHashMap<String, JSONObject>()
        baseChannels?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                c.optStringOrNull("messageStreamId")?.let { byId[it] = c }
            }
        }
        incomingChannels?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optStringOrNull("messageStreamId") ?: continue
                val existing = byId[id]
                // Incoming wins ties: the newer snapshot carries fresher metadata.
                if (existing == null || joinTs(c) >= joinTs(existing)) byId[id] = c
            }
        }

        val channels = JSONArray()
        byId.forEach { (id, channel) ->
            val left = if (leftAt.has(id)) leftAt.optLong(id) else null
            if (left != null && left > joinTs(channel)) return@forEach
            channels.put(channel)
            // A re-join supersedes the tombstone; drop it so the map stays small.
            if (left != null) leftAt.remove(id)
        }
        return channels to leftAt
    }

    private fun joinTs(channel: JSONObject): Long {
        val joined = channel.optLong("joinedAt", 0L)
        return if (joined > 0L) joined else channel.optLong("createdAt", 0L)
    }

    /** Exposed so the export can union the local DM slice with the sync base. */
    fun mergeSentMessages(local: JSONObject?, remote: JSONObject?): JSONObject {
        val result = JSONObject()
        local?.keys()?.forEach { streamId ->
            result.put(streamId, local.optJSONArray(streamId) ?: JSONArray())
        }
        remote?.keys()?.forEach { streamId ->
            val remoteMsgs = remote.optJSONArray(streamId) ?: return@forEach
            val existingArr = result.optJSONArray(streamId)
            if (existingArr == null) {
                result.put(streamId, remoteMsgs)
                return@forEach
            }
            val byId = LinkedHashMap<String, JSONObject>()
            for (i in 0 until existingArr.length()) {
                val m = existingArr.optJSONObject(i) ?: continue
                m.optStringOrNull("id")?.let { byId[it] = m }
            }
            for (i in 0 until remoteMsgs.length()) {
                val m = remoteMsgs.optJSONObject(i) ?: continue
                val id = m.optStringOrNull("id") ?: continue
                val existing = byId[id]
                if (existing == null) {
                    byId[id] = m
                } else if (m.optString("type") == "image" &&
                    !m.isNull("imageData") && existing.isNull("imageData")
                ) {
                    // Blob sync carries image bytes separately; fill the gap.
                    existing.put("imageData", m.get("imageData"))
                }
            }
            val sorted = byId.values.sortedBy { it.optLong("timestamp", 0L) }
                .takeLast(MAX_SENT_MESSAGES)
            result.put(streamId, JSONArray(sorted))
        }
        return result
    }

    /** Exposed for seeding SentReactionsStore from the stored sync base. */
    fun mergeSentReactions(local: JSONObject?, remote: JSONObject?): JSONObject {
        val result = JSONObject()
        val streamIds = LinkedHashSet<String>()
        local?.keys()?.forEach { streamIds.add(it) }
        remote?.keys()?.forEach { streamIds.add(it) }

        streamIds.forEach { streamId ->
            val localStream = local?.optJSONObject(streamId) ?: JSONObject()
            val remoteStream = remote?.optJSONObject(streamId) ?: JSONObject()
            val merged = JSONObject()
            val ids = LinkedHashSet<String>()
            localStream.keys().forEach { ids.add(it) }
            remoteStream.keys().forEach { ids.add(it) }
            ids.forEach { messageId ->
                if (remoteStream.has(messageId)) {
                    // Remote wins, but an emptied map means "removed".
                    val r = remoteStream.optJSONObject(messageId)
                    if (r != null && r.length() > 0) merged.put(messageId, r)
                } else {
                    localStream.opt(messageId)?.let { merged.put(messageId, it) }
                }
            }
            if (merged.length() > 0) result.put(streamId, merged)
        }
        return result
    }

    private fun pickSlice(key: String, base: JSONObject?, incoming: JSONObject?, sliceTs: JSONObject): Any? {
        val baseHas = base?.has(key) == true
        val incomingHas = incoming?.has(key) == true
        val baseTs = base?.optJSONObject("sliceTs")?.optLong(key, 0L) ?: 0L
        val incomingTs = incoming?.optJSONObject("sliceTs")?.optLong(key, 0L) ?: 0L

        if (incomingHas && (!baseHas || incomingTs >= baseTs)) {
            sliceTs.put(key, incomingTs)
            return incoming!!.opt(key)
        }
        if (baseHas) {
            sliceTs.put(key, baseTs)
            return base!!.opt(key)
        }
        sliceTs.put(key, 0L)
        return when (key) {
            "blockedPeers" -> JSONArray()
            "dmLeftAt", "trustedContacts" -> JSONObject()
            else -> null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").ifEmpty { null }
    }
}

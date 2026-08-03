package com.pombo.android

import android.util.Log
import com.pombo.android.bridge.PomboBridge
import com.pombo.android.core.StreamConstants
import com.pombo.android.core.SyncMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-device state sync — port of the web's syncManager.js.
 *
 * State is published to the owner's own DM inbox on partition 1, encrypted to
 * self (ECDH against my own public key), so only this account can read it. The
 * inbox grants public PUBLISH but reads are owner-only, which is what makes it
 * a usable private mailbox.
 *
 * Payloads are versioned `{type:'sync', v:1, ts, data}` and merged
 * chronologically by [SyncMerge]; every applied `ts` is remembered so a stale
 * replica read cannot re-merge an old snapshot over newer state.
 */
class SyncManager(
    private val bridge: PomboBridge,
    private val scope: CoroutineScope,
    private val store: com.pombo.android.data.SyncStore,
    private val blobStore: com.pombo.android.core.ImageBlobStore,
    private val myAddress: () -> String?,
    private val isGuest: () -> Boolean,
    /** Snapshot of this device's state, in the web's payload shape. */
    private val exportLocal: () -> JSONObject,
    /** Applies merged state back into the local stores. */
    private val importMerged: (JSONObject) -> Unit,
    /**
     * Settings → Account. Read on every trigger rather than captured, so
     * changing the mode (or switching account, since the setting is scoped)
     * takes effect without rebuilding this manager.
     */
    private val syncMode: () -> com.pombo.android.data.SyncMode =
        { com.pombo.android.data.SyncMode.AUTOMATIC },
    /** Fired after a blob pull imports images, with their ids — lets the open
     *  channel swap "Loading image" placeholders for the pixels right away. */
    private val onBlobsImported: (List<String>) -> Unit = {}
) {

    private val TAG = "PomboSync"

    /** Web default: 15s of quiet before an auto-push. */
    private val autoPushDelayMs = 15_000L
    private val maxPayloads = 10

    /** Floor between lifecycle-triggered syncs (foreground churn is the enemy). */
    private val autoSyncMinIntervalMs = 5 * 60_000L
    @Volatile private var lastAutoSyncAt = 0L

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastSyncTs = MutableStateFlow(store.lastSyncTs)
    val lastSyncTs: StateFlow<Long> = _lastSyncTs.asStateFlow()

    private var autoPushJob: Job? = null
    private var autoPushRetries = 0
    private var pushQueued = false

    private fun inboxId(): String? = myAddress()?.lowercase()?.let { "$it/Pombo-DM-1" }

    /**
     * Marks local state dirty and schedules a push. Coalescing matters: every
     * message send and settings tweak would otherwise be its own publish.
     */
    fun scheduleAutoPush(delayMs: Long = autoPushDelayMs) {
        if (isGuest()) return
        // Dirty is recorded whatever the mode: "Manual only" defers the publish,
        // it does not discard the change. The next "Sync devices" still carries it.
        store.dirty = true
        if (syncMode() == com.pombo.android.data.SyncMode.MANUAL_ONLY) return
        autoPushJob?.cancel()
        autoPushJob = scope.launch {
            delay(delayMs)
            try {
                if (pushSync() != null) autoPushRetries = 0
            } catch (e: Exception) {
                autoPushRetries++
                // Web: exponential backoff capped at five minutes.
                val retry = minOf(autoPushDelayMs * (1L shl autoPushRetries), 300_000L)
                scheduleAutoPush(retry)
            }
        }
    }

    fun cancelAutoPush() {
        autoPushJob?.cancel()
        autoPushJob = null
        autoPushRetries = 0
        pushQueued = false
    }

    /** Publishes the local snapshot. Returns the payload timestamp, or null if skipped. */
    suspend fun pushSync(): Long? {
        if (isGuest()) return null
        if (_syncing.value) { pushQueued = true; return null }
        val inbox = inboxId() ?: return null
        if (!inboxExists()) return null

        _syncing.value = true
        try {
            val ts = System.currentTimeMillis()
            val payload = JSONObject()
                .put("type", "sync")
                .put("v", 1)
                .put("ts", ts)
                .put("data", exportLocal())

            // Sealed-to-self v2 under a throwaway publisher (web pushSync):
            // only this account's static key opens it, and the proof inside
            // recovers to this wallet — which the pull verifies.
            val pk = bridge.call("getMyPublicKey").getString("publicKey")
            bridge.call("dmSealPublish", JSONObject()
                .put("streamId", inbox)
                .put("partition", StreamConstants.P_SYNC)
                .put("recipientAddress", myAddress()!!.lowercase())
                .put("recipientPublicKey", pk)
                .put("message", payload), 60_000)

            // Our own snapshot is by definition already applied locally.
            store.recordApplied(listOf(ts))
            store.lastSyncTs = ts
            _lastSyncTs.value = ts
            if (autoPushJob == null && !pushQueued) store.dirty = false
            return ts
        } finally {
            _syncing.value = false
            if (pushQueued) { pushQueued = false; scheduleAutoPush(0L) }
        }
    }

    /** Fetches remote snapshots, merges them into local state. Returns payloads applied. */
    suspend fun pullSync(): Int {
        if (isGuest()) return 0
        if (_syncing.value) return 0
        val inbox = inboxId() ?: return 0
        val me = myAddress()?.lowercase() ?: return 0
        if (!inboxExists()) return 0

        _syncing.value = true
        try {
            var payloads = fetchPayloads(inbox, me)
            val applied = store.appliedTs()
            var fresh = payloads.filter { it.optLong("ts") !in applied }

            if (fresh.isEmpty() && payloads.isNotEmpty()) {
                val newestFetched = payloads.maxOf { it.optLong("ts") }
                val newestApplied = applied.maxOrNull() ?: 0L
                // Provably stale read — storage replicas are round-robined, so
                // one retry usually lands on the other replica.
                if (newestFetched < newestApplied) {
                    payloads = fetchPayloads(inbox, me)
                    fresh = payloads.filter { it.optLong("ts") !in applied }
                }
            }
            if (fresh.isEmpty()) return 0

            val ordered = fresh.sortedBy { it.optLong("ts") }
            val merged = SyncMerge.mergeSeries(
                exportLocal(),
                ordered.mapNotNull { it.optJSONObject("data") }
            )
            importMerged(merged)
            store.recordApplied(ordered.map { it.optLong("ts") })
            _lastSyncTs.value = ordered.last().optLong("ts")
            store.lastSyncTs = _lastSyncTs.value
            return ordered.size
        } finally {
            _syncing.value = false
        }
    }

    /**
     * Manual sync (the "Sync devices" button). Push-first like the web's
     * smartSync — the other devices must see this one's tombstones before a
     * pull can re-merge what was just left behind. Every phase runs even if an
     * earlier one failed (a dead pull must not silence the push flush); the
     * first error is rethrown at the end so the UI still reports failure.
     */
    suspend fun fullSync(): Int {
        var phaseError: Exception? = null
        try { pushSync() } catch (e: Exception) { phaseError = e; Log.w(TAG, "fullSync push failed: ${e.message}") }
        val applied = try { pullSync() } catch (e: Exception) { phaseError = phaseError ?: e; Log.w(TAG, "fullSync pull failed: ${e.message}"); 0 }
        try { pullImageBlobs() } catch (e: Exception) { phaseError = phaseError ?: e; Log.w(TAG, "fullSync blob pull failed: ${e.message}") }
        try { pushImageBlobs() } catch (e: Exception) { phaseError = phaseError ?: e; Log.w(TAG, "fullSync blob push failed: ${e.message}") }
        Log.d(TAG, "fullSync done: applied=$applied error=${phaseError?.message ?: "none"}")
        phaseError?.let { throw it }
        return applied
    }

    /**
     * Lifecycle-triggered sync (bridge connect, app foreground). Unlike the
     * manual [fullSync] this is built to be cheap enough to run unprompted:
     * - throttled to one run per [autoSyncMinIntervalMs] unless forced
     *   (connect is forced: it is rare and is exactly when state is stalest);
     * - push runs only when local state is actually dirty — but always BEFORE
     *   the pull, so a pending leave/block cannot be resurrected by the merge;
     * - blob pull (the expensive resend) always runs on a forced sync (once
     *   per connect); foreground ticks only run it when the state pull applied
     *   something new — a blob pushed by a device whose state was already in
     *   step would otherwise never arrive until the next manual sync. Blob
     *   push rides along whenever the local ledger holds unsynced images,
     *   a check that costs nothing.
     * Phases are isolated: this never throws, it reports and moves on.
     */
    suspend fun autoSync(force: Boolean = false): Int {
        if (isGuest()) return 0
        // The one gate that covers every unprompted path — the connect run, the
        // foreground tick, and anything added later. Only [fullSync], which the
        // "Sync devices" button calls, bypasses it.
        if (syncMode() == com.pombo.android.data.SyncMode.MANUAL_ONLY) {
            Log.d(TAG, "autoSync skipped (manual only)")
            return 0
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastAutoSyncAt < autoSyncMinIntervalMs) {
            Log.d(TAG, "autoSync skipped (throttled)")
            return 0
        }
        if (_syncing.value) return 0
        lastAutoSyncAt = now
        if (store.dirty) {
            // The debounced auto-push would only republish the same snapshot.
            cancelAutoPush()
            try { pushSync() } catch (e: Exception) { Log.w(TAG, "autoSync push failed (dirty kept): ${e.message}") }
        }
        val applied = try { pullSync() } catch (e: Exception) { Log.w(TAG, "autoSync pull failed: ${e.message}"); 0 }
        try { pushImageBlobs() } catch (e: Exception) { Log.w(TAG, "autoSync blob push failed: ${e.message}") }
        if (force || applied > 0) {
            try { pullImageBlobs() } catch (e: Exception) { Log.w(TAG, "autoSync blob pull failed: ${e.message}") }
        }
        Log.d(TAG, "autoSync done: force=$force applied=$applied")
        return applied
    }

    /**
     * Immediate flush of pending local changes — app going to background. The
     * 15s debounce dies with the process; the web does the same forcePushNow on
     * pagehide/visibilitychange. On failure `dirty` stays set, so the next
     * [autoSync] on connect finishes the job.
     */
    fun flushIfDirty() {
        if (isGuest() || !store.dirty) return
        // Backgrounding is still an unprompted publish. `dirty` survives, so the
        // next manual run carries the change.
        if (syncMode() == com.pombo.android.data.SyncMode.MANUAL_ONLY) return
        cancelAutoPush()
        scope.launch {
            try { pushSync() } catch (e: Exception) { /* dirty survives for the next run */ }
        }
    }

    // ==================== image blobs (partition 2) ====================

    /**
     * Chunk budget mirrors the chat-image protocol so each encrypted envelope
     * stays under the media payload limit (web: imageChunkInitialRawBytes).
     */
    private val blobChunkChars = 150 * 1024

    /** Publishes locally-held images that no device has synced yet. */
    suspend fun pushImageBlobs() {
        if (isGuest() || _syncing.value) return
        val inbox = inboxId() ?: return
        val pending = blobStore.unsynced()
        if (pending.isEmpty()) return
        if (!inboxExists()) return

        val pk = bridge.call("getMyPublicKey").getString("publicKey")
        val meAddr = myAddress()?.lowercase() ?: return

        // Sealed-to-self v2 under a throwaway publisher, one seal per message.
        suspend fun publish(payload: JSONObject) {
            bridge.call("dmSealPublish", JSONObject()
                .put("streamId", inbox)
                .put("partition", StreamConstants.P_SYNC_BLOBS)
                .put("recipientAddress", meAddr)
                .put("recipientPublicKey", pk)
                .put("message", payload), 60_000)
        }

        for (record in pending) {
            val data = blobStore.load(record.imageId)
            if (data.isNullOrEmpty()) {
                // The blob is gone but the ledger still lists it; drop the entry
                // rather than retrying it on every sync forever.
                blobStore.forget(record.imageId)
                continue
            }
            try {
                if (data.length <= blobChunkChars) {
                    publish(JSONObject()
                        .put("type", "sync_blob").put("v", 2)
                        .put("ts", System.currentTimeMillis())
                        .put("imageId", record.imageId)
                        .put("streamId", record.streamId)
                        .put("data", data))
                } else {
                    val chunkCount = (data.length + blobChunkChars - 1) / blobChunkChars
                    for (i in 0 until chunkCount) {
                        val slice = data.substring(
                            i * blobChunkChars,
                            minOf((i + 1) * blobChunkChars, data.length)
                        )
                        publish(JSONObject()
                            .put("type", "sync_blob_chunk").put("v", 2)
                            .put("ts", System.currentTimeMillis())
                            .put("imageId", record.imageId)
                            .put("streamId", record.streamId)
                            .put("chunkIndex", i)
                            .put("chunkCount", chunkCount)
                            .put("data", slice))
                    }
                    publish(JSONObject()
                        .put("type", "sync_blob_manifest").put("v", 2)
                        .put("ts", System.currentTimeMillis())
                        .put("imageId", record.imageId)
                        .put("streamId", record.streamId)
                        .put("chunkCount", chunkCount)
                        .put("totalLength", data.length))
                }
                blobStore.markSynced(record.imageId)
            } catch (e: Exception) {
                // Leave it unsynced so the next run retries this one blob.
                Log.w(TAG, "blob push failed for ${record.imageId}: ${e.message}")
            }
        }
        Log.d(TAG, "blob push: ${pending.size} pending processed")
    }

    /**
     * Imports blobs other devices pushed. Two passes, because a resend does not
     * guarantee chronological order and chunks published back-to-back can share
     * a millisecond — resolving manifests inline would miss chunks that arrive
     * after their own manifest.
     */
    suspend fun pullImageBlobs(): Int {
        if (isGuest() || _syncing.value) return 0
        val inbox = inboxId() ?: return 0
        val me = myAddress()?.lowercase() ?: return 0
        if (!inboxExists()) return 0

        val res = bridge.call("resend", JSONObject()
            .put("streamId", inbox)
            .put("partition", StreamConstants.P_SYNC_BLOBS)
            // Chunked images burn one message per ~150KB, so a small window
            // would cut off older chunks and leave assembly permanently short.
            .put("last", 200), 120_000)
        val arr = res.optJSONArray("messages") ?: JSONArray()

        val singles = mutableListOf<JSONObject>()
        val manifests = mutableListOf<JSONObject>()
        val chunks = HashMap<String, MutableMap<Int, String>>()
        val chunkStream = HashMap<String, String>()

        // Same rule as fetchPayloads: opening proves addressed-to-me, the
        // proof-recovered sender == me proves it is OURS.
        val opened = openAllSealed(arr)
        for (i in 0 until arr.length()) {
            val o = opened?.takeIf { !it.isNull(i) }?.optJSONObject(i) ?: continue
            if (!o.optString("sender").equals(me, ignoreCase = true)) continue
            val payload = o.optJSONObject("message") ?: continue
            if (payload.optInt("v") != 2) continue
            when (payload.optString("type")) {
                "sync_blob" -> singles.add(payload)
                "sync_blob_manifest" -> manifests.add(payload)
                "sync_blob_chunk" -> {
                    val id = payload.optString("imageId")
                    if (id.isEmpty()) continue
                    chunks.getOrPut(id) { HashMap() }[payload.optInt("chunkIndex")] = payload.optString("data")
                    payload.optString("streamId").takeIf { it.isNotEmpty() }?.let { chunkStream[id] = it }
                }
            }
        }

        val importedIds = mutableListOf<String>()
        var incomplete = 0
        for (p in singles) {
            val id = p.optString("imageId")
            if (id.isEmpty()) continue
            if (blobStore.save(id, p.optString("streamId"), p.optString("data"), synced = true)) importedIds.add(id)
        }
        for (m in manifests) {
            val id = m.optString("imageId")
            if (id.isEmpty()) continue
            val parts = chunks.remove(id) ?: continue
            val expected = m.optInt("chunkCount")
            if (parts.size != expected) { incomplete++; continue }  // wait for a later pull
            val assembled = buildString {
                for (i in 0 until expected) append(parts[i] ?: return@buildString)
            }
            val total = m.optInt("totalLength")
            if (total > 0 && assembled.length != total) { incomplete++; continue }
            if (blobStore.save(id, m.optString("streamId").ifEmpty { chunkStream[id] ?: "" }, assembled, synced = true)) {
                importedIds.add(id)
            }
        }
        Log.d(TAG, "blob pull: ${arr.length()} message(s), imported=${importedIds.size}, incomplete=$incomplete")
        if (importedIds.isNotEmpty()) {
            // A placeholder on screen ("Loading image") whose blob just landed
            // would otherwise stay a placeholder until the chat is reopened.
            onBlobsImported(importedIds)
        }
        return importedIds.size
    }

    private suspend fun fetchPayloads(inbox: String, me: String): List<JSONObject> {
        val res = bridge.call("resend", JSONObject()
            .put("streamId", inbox)
            .put("partition", StreamConstants.P_SYNC)
            .put("last", maxPayloads), 60_000)
        val arr = res.optJSONArray("messages") ?: JSONArray()
        // The old "publisherId == me" gate is trap 1 of the migration: sealed
        // pushes ride a throwaway publisher, so that filter rejected every one
        // of our own payloads. Opening decides ownership instead — but opening
        // alone only proves the payload was ADDRESSED to us (anyone can seal
        // to a public key), so the proof-recovered sender must ALSO be this
        // wallet, or a stranger could inject state into our merge. (The web
        // currently skips that second check — flagged to be fixed there.)
        val opened = openAllSealed(arr)
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = opened?.takeIf { !it.isNull(i) }?.optJSONObject(i) ?: continue
            if (!o.optString("sender").equals(me, ignoreCase = true)) continue
            val payload = o.optJSONObject("message") ?: continue
            if (payload.optString("type") == "sync" && payload.optInt("v") == 1) out.add(payload)
        }
        return out
    }

    /** One dmOpenBatch over a resend page; null-per-entry for anything not v2-sealed or not ours. */
    private suspend fun openAllSealed(arr: JSONArray): JSONArray? {
        val items = JSONArray()
        for (i in 0 until arr.length()) {
            val content = arr.optJSONObject(i)?.opt("content")
            items.put(
                if (content is JSONObject && content.optInt("v") == 2 && content.has("epk")) content
                else JSONObject.NULL
            )
        }
        return try {
            bridge.call("dmOpenBatch", JSONObject().put("items", items), 60_000)
                .optJSONArray("results")
        } catch (e: Exception) { null }
    }

    private suspend fun inboxExists(): Boolean = try {
        val me = myAddress() ?: return false
        bridge.call("getPeerPublicKey", JSONObject().put("address", me))
            .optString("publicKey").let { it.isNotEmpty() && it != "null" }
    } catch (e: Exception) {
        false
    }
}

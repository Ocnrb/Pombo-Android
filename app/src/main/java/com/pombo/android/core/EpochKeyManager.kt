package com.pombo.android.core

import android.util.Log
import com.pombo.android.data.EpochKeyStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Protocol state machine for the keys stream (-4) — mirror of the web's
 * epochKeyManager.js (N-A; UNIFIED_IMPLEMENTATION_PLAN.md §5.4, D12–D14).
 *
 *   KEY_ANNOUNCE  admin only. v1 admin set = the stream's NAMESPACE address —
 *                 the one address that could have created the stream; never
 *                 `createdBy`, which travels in invites and is spoofable.
 *                 Conflict rule (D13): higher epoch > older transport
 *                 timestamp > lower publisher address.
 *   KEY_REQUEST   fresh per-request keypair (D12), memory-only. Answered only
 *                 when seen LIVE — replaying stored requests would re-answer
 *                 months of already-served ones on every open.
 *   KEY_WRAP      any member holding the key answers (k-of-n). Adopted ONLY
 *                 when sha256(key) equals the announced keyHash — a malicious
 *                 wrapper wastes bandwidth, never poisons a key.
 *
 * Transport (publish as the ACCOUNT with encryptionType NONE, resend of -4)
 * is injected — this class holds protocol state only.
 */
class EpochKeyManager(
    private val store: EpochKeyStore,
    /** For rank-delayed answers (N-B) — never delay inline in a handler. */
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val myAddress: () -> String?,
    private val publishKeys: suspend (keysStreamId: String, data: JSONObject) -> Unit,
    private val resendKeys: suspend (keysStreamId: String) -> List<Entry>,
    /** Fired on adoption — the channel layer re-pulls history so parked messages open. */
    private val onKeyAdopted: (messageStreamId: String, keyId: String) -> Unit,
    /**
     * Gated channels (N-C): the CURRENT gate check, consulted before wrapping
     * a key for a requester — one cached eth_call, FAIL-CLOSED. Ungated
     * channels return true. This is where the write-cut for ex-members lives:
     * the sticky envelope signature keeps their history valid, this keeps new
     * epochs out of their hands.
     */
    private val checkGateAccess: suspend (messageStreamId: String, requester: String) -> Boolean =
        { _, _ -> true },
    /**
     * D14 per-mode history scope (N-D): true when this channel's gate hands
     * out ONLY the current epoch — PAID gates, where a subscription buys the
     * future, never the channel's past. The wiring is FAIL-CLOSED: a gated
     * channel whose mode cannot be read answers current-only (missing old
     * epochs get re-requested once the RPC heals; leaked ones cannot be
     * taken back). Ungated channels return false.
     */
    private val currentEpochOnly: suspend (messageStreamId: String) -> Boolean =
        { _ -> false }
) {
    data class Entry(val data: JSONObject, val publisherId: String?, val timestamp: Long)

    private class EpochEntry(val keyHex: String, val keyHash: String, val epoch: Int)
    private class Announce(
        val keyId: String, val keyHash: String,
        val publisher: String, val timestamp: Long,
        /** Epoch validity start — the kid-freshness rule's anchor (N-C). */
        val validFrom: Long
    )

    private class ChannelState {
        val epochs = HashMap<String, EpochEntry>()        // keyId -> entry
        val announces = HashMap<Int, Announce>()           // epoch -> announce
        /**
         * epoch -> NEWEST valid announce timestamp seen. The conflict rule
         * (D13) deliberately keeps the OLDEST announce, so the TTL
         * re-announce check cannot read [announces] — it would republish on
         * every open. Freshness lives here instead.
         */
        val announceFreshness = HashMap<Int, Long>()
        var currentEpoch = 0
        var pendingRequest: PendingRequest? = null         // memory only (D12)
        var loaded = false
        var lastMissingRefresh = 0L
        /** Consecutive unanswered requests — drives the retry backoff. */
        var requestAttempts = 0
        /**
         * requestId -> keyIds of wraps OBSERVED (live or history) — the N-B
         * suppression signal: stay silent for epochs someone already covered.
         * LinkedHashMap for insertion-order eviction.
         */
        val seenWraps = LinkedHashMap<String, MutableSet<String>>()
        /**
         * Addresses seen authoring a KEY_REQUEST (live or -4 history). Every
         * reader of a gated channel must request keys, so this enumerates
         * members for TOKEN/NFT/PAID gates where join()/pay() bypasses the
         * owner — the N-D no-indexer decision.
         */
        val seenRequesters = LinkedHashSet<String>()
        /** N-D weekly rotation timer (gated channels, admin-side). */
        var rotationJob: kotlinx.coroutines.Job? = null
    }

    private class PendingRequest(
        val requestId: String, val privateKey: String,
        val publicKey: String, val sentAt: Long
    )

    private val state = HashMap<String, ChannelState>()
    private val mutex = Mutex()

    companion object {
        private const val TAG = "PomboEpochKeys"
        private const val REQUEST_MIN_INTERVAL_MS = 60_000L
        /**
         * A KEY_REQUEST from a cold node can miss every live subscriber (plan
         * §7.2 R2: join registers interest, broadcast can still shout into an
         * empty room). Waiting the full minute to retry turned that loss into
         * a 60s blank channel — measured live. Retry fast while the topology
         * warms up, then back off.
         */
        private const val REQUEST_RETRY_FAST_MS = 10_000L
        private const val REQUEST_FAST_ATTEMPTS = 4
        /** Laps of the caller's 10s retry loop while a channel waits for keys. */
        const val RETRY_LOOP_ATTEMPTS = 6
        // N-B anti-stampede (§7.10): rank × step = wait before checking
        // whether a lower rank already covered the request.
        private const val RANK_STEP_MS = 2_000L
        private const val RANK_MAX = 8
        // Stored requests older than this are dead — the requester's
        // ephemeral pair lives in memory only.
        private const val REQUEST_ANSWER_WINDOW_MS = 10 * 60 * 1000L
        private const val SEEN_WRAPS_MAX = 100
        /** Post-rotation window in which the previous epoch's kid stays
         *  acceptable on LIVE gated traffic (web: kidFreshnessToleranceMs). */
        private const val KID_FRESHNESS_TOLERANCE_MS = 10 * 60 * 1000L
        // N-D (§7.12): gated channels rotate on a cadence — selling the asset
        // or a lapsed subscription only cuts reads at the NEXT rotation, so
        // without a schedule the cut never lands. Weekly for every gate mode.
        private const val ROTATION_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L
        private const val ROTATION_RETRY_MS = 60 * 60 * 1000L
        private const val SEEN_REQUESTERS_MAX = 500
        private val ADDR_RE = Regex("^0x[0-9a-f]{40}$")
    }

    // ---- Admin set (D13): namespace address only ----

    private fun adminOf(messageStreamId: String): String? =
        messageStreamId.substringBefore('/').lowercase().takeIf { it.length == 42 }

    fun isOwnAdmin(messageStreamId: String): Boolean =
        myAddress()?.lowercase() == adminOf(messageStreamId)

    // ---- Lifecycle ----

    private fun getState(messageStreamId: String): ChannelState =
        state.getOrPut(messageStreamId) { ChannelState() }

    private fun loadPersisted(messageStreamId: String, s: ChannelState) {
        val persisted = store.load(messageStreamId) ?: return
        persisted.optJSONObject("epochs")?.let { epochs ->
            for (keyId in epochs.keys()) {
                if (s.epochs.containsKey(keyId)) continue
                val e = epochs.optJSONObject(keyId) ?: continue
                s.epochs[keyId] = EpochEntry(
                    e.optString("keyHex"), e.optString("keyHash"), e.optInt("epoch"))
            }
        }
        // Announces persist too (they are public data): with them AND the keys
        // a restarted session encrypts/decrypts IMMEDIATELY — no -4 resend on
        // the open's critical path. The background refresh still reconciles.
        persisted.optJSONObject("announces")?.let { anns ->
            for (epochStr in anns.keys()) {
                val epoch = epochStr.toIntOrNull() ?: continue
                if (s.announces.containsKey(epoch)) continue
                val a = anns.optJSONObject(epochStr) ?: continue
                s.announces[epoch] = Announce(
                    a.optString("keyId"), a.optString("keyHash"),
                    a.optString("publisher"), a.optLong("timestamp"),
                    a.optLong("validFrom", a.optLong("timestamp")))
            }
        }
        val cur = persisted.optInt("currentEpoch", 0)
        if (cur > s.currentEpoch) s.currentEpoch = cur
        val maxAnnounced = s.announces.keys.maxOrNull() ?: 0
        if (maxAnnounced > s.currentEpoch) s.currentEpoch = maxAnnounced
    }

    private fun persist(messageStreamId: String, s: ChannelState) {
        val epochs = JSONObject()
        for ((keyId, e) in s.epochs) {
            epochs.put(keyId, JSONObject()
                .put("keyHex", e.keyHex).put("keyHash", e.keyHash).put("epoch", e.epoch))
        }
        val announces = JSONObject()
        for ((epoch, a) in s.announces) {
            announces.put(epoch.toString(), JSONObject()
                .put("keyId", a.keyId).put("keyHash", a.keyHash)
                .put("publisher", a.publisher).put("timestamp", a.timestamp)
                .put("validFrom", a.validFrom))
        }
        store.save(messageStreamId, JSONObject()
            .put("epochs", epochs).put("announces", announces).put("currentEpoch", s.currentEpoch))
    }

    /** Drop runtime + persisted state (channel left/deleted). */
    suspend fun forgetChannel(messageStreamId: String) = mutex.withLock {
        state.remove(messageStreamId)?.rotationJob?.cancel()
        store.clear(messageStreamId)
    }

    /**
     * Bring a native channel's key state up to date: pull announces from -4
     * storage, bootstrap/re-announce as admin, or request missing keys as a
     * member. Call on channel open, before the -1 history pull.
     */
    suspend fun ensureChannelKeys(
        messageStreamId: String,
        keysStreamId: String,
        /** Channel retention (drives the announce TTL re-announce). */
        retentionDays: Int = 180,
        /**
         * Whether a keyless admin may MINT epoch 1. Only true on a virgin
         * channel (no other members): a fresh admin device minting on an
         * established channel would fork it, and it cannot recover from
         * member wraps either — without an announce there is no keyHash to
         * verify them against.
         */
        allowMint: Boolean = true,
        /** Rank domain for the anti-stampede queue (channel member count). */
        memberCount: Int = 4,
        /** Gated channels arm the weekly rotation (admin-side, N-D). */
        gated: Boolean = false
    ) {
        val entries = try { resendKeys(keysStreamId) } catch (e: Exception) { emptyList() }
        var toRequest = false
        var toBootstrap = false
        var toReannounce = false
        val storedRequests = mutableListOf<Entry>()
        var haveKeys = false
        mutex.withLock {
            val s = getState(messageStreamId)
            if (!s.loaded) { loadPersisted(messageStreamId, s); s.loaded = true }
            var changed = false
            for (entry in entries) {
                when (entry.data.optString("t")) {
                    StreamConstants.KEY_ANNOUNCE -> {
                        if (applyAnnounceLocked(messageStreamId, s, entry.data, entry.publisherId, entry.timestamp)) {
                            changed = true
                        }
                    }
                    // Coverage bookkeeping: a stored wrap means someone
                    // already answered — its epochs need no re-answering
                    StreamConstants.KEY_WRAP -> {
                        val rid = entry.data.optString("requestId")
                        val kid = entry.data.optString("keyId")
                        if (rid.isNotEmpty() && kid.isNotEmpty()) recordSeenWrapLocked(s, rid, kid)
                    }
                    StreamConstants.KEY_REQUEST -> {
                        storedRequests.add(entry)
                        recordRequesterLocked(s, entry.publisherId)
                    }
                }
            }
            if (changed) persist(messageStreamId, s)
            haveKeys = s.epochs.isNotEmpty()
            toBootstrap = s.announces.isEmpty() && isOwnAdmin(messageStreamId)
            toRequest = s.announces.isNotEmpty() && missingEpochsLocked(s).isNotEmpty()
            if (!toBootstrap && isOwnAdmin(messageStreamId)) {
                val cur = s.announces[s.currentEpoch]
                if (cur != null && s.epochs.containsKey(cur.keyId)) {
                    // freshest == 0: the announce exists only in OUR persisted
                    // state — storage lost it (or the resend failed; a
                    // duplicate republish is harmless). Else: nearing TTL.
                    val freshest = s.announceFreshness[s.currentEpoch] ?: 0L
                    val retentionMs = retentionDays.toLong() * 86_400_000L
                    toReannounce = freshest == 0L ||
                        System.currentTimeMillis() - freshest > (retentionMs * 0.8).toLong()
                }
            }
        }
        if (toBootstrap) bootstrapOrReannounce(messageStreamId, keysStreamId, allowMint)
        else if (toReannounce) reannounceCurrent(messageStreamId, keysStreamId)
        if (toRequest) sendKeyRequest(messageStreamId, keysStreamId)
        else if (!toBootstrap && !isOwnAdmin(messageStreamId)) {
            mutex.withLock {
                if (getState(messageStreamId).announces.isEmpty()) {
                    Log.i(TAG, "no announce yet on ${keysStreamId.takeLast(30)} — waiting for the admin")
                }
            }
        }

        // N-B: answer RECENT stored requests no wrap covers yet — a member
        // arriving later serves whoever is still waiting, so requester and
        // key-holder no longer have to coincide in time.
        if (haveKeys) {
            val me = myAddress()?.lowercase()
            val now = System.currentTimeMillis()
            for (entry in storedRequests) {
                if (entry.publisherId?.lowercase() == me) continue
                if (now - entry.timestamp > REQUEST_ANSWER_WINDOW_MS) continue
                val requestId = entry.data.optString("requestId")
                val pubkey = entry.data.optString("pubkey")
                if (requestId.isEmpty() || pubkey.isEmpty()) continue
                scheduleAnswer(
                    messageStreamId, keysStreamId,
                    requestId, pubkey, entry.data.optInt("fromEpoch", 1),
                    rankFor(requestId, memberCount) * RANK_STEP_MS,
                    requester = entry.publisherId
                )
            }
        }

        if (gated && isOwnAdmin(messageStreamId)) {
            mutex.withLock {
                armRotationLocked(messageStreamId, keysStreamId, getState(messageStreamId))
            }
        }
    }

    /**
     * Arm the weekly rotation timer (N-D, gated channels, admin-side). The
     * timer checks the CURRENT epoch's age on fire — a manual rotation (ban)
     * in between simply makes the check a no-op and the timer re-arm. If the
     * admin is offline past the due time, the epoch lives longer and the next
     * channel open rotates. Caller holds the lock.
     */
    private fun armRotationLocked(messageStreamId: String, keysStreamId: String, s: ChannelState) {
        if (s.rotationJob?.isActive == true) return
        val born = s.announces[s.currentEpoch]?.validFrom ?: return
        val dueIn = (born + ROTATION_INTERVAL_MS - System.currentTimeMillis())
            .coerceIn(0L, ROTATION_INTERVAL_MS) + 1_000L
        s.rotationJob = scope.launch {
            delay(dueIn)
            try {
                val due = mutex.withLock {
                    val st = state[messageStreamId] ?: return@launch   // left/deleted
                    val cur = st.announces[st.currentEpoch]
                    cur != null && System.currentTimeMillis() - cur.validFrom >= ROTATION_INTERVAL_MS
                }
                if (due && isOwnAdmin(messageStreamId)) {
                    rotateEpoch(messageStreamId, keysStreamId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "scheduled rotation failed (retrying later): ${e.message}")
                delay(ROTATION_RETRY_MS)
            }
            mutex.withLock {
                state[messageStreamId]?.let { st ->
                    st.rotationJob = null
                    armRotationLocked(messageStreamId, keysStreamId, st)
                }
            }
        }
    }

    /** Caller holds the lock. */
    private fun recordRequesterLocked(s: ChannelState, publisherId: String?) {
        val addr = publisherId?.lowercase() ?: return
        if (!ADDR_RE.matches(addr)) return
        if (s.seenRequesters.size >= SEEN_REQUESTERS_MAX && addr !in s.seenRequesters) return
        s.seenRequesters.add(addr)
    }

    /**
     * Member candidates observed on -4 (KEY_REQUEST authors) — the candidate
     * source for enumerating TOKEN/NFT/PAID gate members without an indexer
     * (N-D). Misses whoever paid/joined but never opened the channel.
     */
    suspend fun seenRequesters(messageStreamId: String): List<String> = mutex.withLock {
        state[messageStreamId]?.seenRequesters?.toList() ?: emptyList()
    }

    /**
     * TTL-aware re-announce (same pattern as the -3 artifacts' ttlRepublish).
     * CONSISTENT BY CONSTRUCTION across the admin's devices: only ever
     * republishes the PERSISTED keyId/keyHash — two devices doing it
     * concurrently emit identical content. Minting happens nowhere here.
     */
    private suspend fun reannounceCurrent(messageStreamId: String, keysStreamId: String) {
        var announce: JSONObject? = null
        mutex.withLock {
            val s = getState(messageStreamId)
            val cur = s.announces[s.currentEpoch] ?: return
            val entry = s.epochs[cur.keyId] ?: return
            announce = JSONObject()
                .put("t", StreamConstants.KEY_ANNOUNCE)
                .put("epoch", s.currentEpoch)
                .put("keyId", cur.keyId)
                .put("keyHash", entry.keyHash)
                .put("validFrom", System.currentTimeMillis())
            s.announceFreshness[s.currentEpoch] = System.currentTimeMillis()
        }
        publishKeys(keysStreamId, announce ?: return)
        Log.i(TAG, "re-announced current epoch (missing/aging in storage) on ${keysStreamId.takeLast(30)}")
    }

    private fun missingEpochsLocked(s: ChannelState): List<Int> {
        val adopted = s.epochs.values.map { it.epoch }.toSet()
        return s.announces.keys.filter { it !in adopted }
    }

    /**
     * Admin with no announce in storage: re-announce the highest persisted
     * epoch (announce lost — storage race at create time, or aged past
     * retention; idempotent self-heal), or mint epoch 1 on a virgin channel.
     */
    private suspend fun bootstrapOrReannounce(
        messageStreamId: String, keysStreamId: String, allowMint: Boolean
    ) {
        var announce: JSONObject? = null
        var adopt: Triple<String, String, Int>? = null   // keyId, keyHex, epoch — null on re-announce
        var keyHash = ""
        mutex.withLock {
            val s = getState(messageStreamId)
            val existing = s.epochs.entries.maxByOrNull { it.value.epoch }
            if (existing != null) {
                keyHash = existing.value.keyHash
                announce = JSONObject()
                    .put("t", StreamConstants.KEY_ANNOUNCE)
                    .put("epoch", existing.value.epoch)
                    .put("keyId", existing.key)
                    .put("keyHash", keyHash)
                    .put("validFrom", System.currentTimeMillis())
            } else if (!allowMint) {
                Log.w(TAG, "admin device holds NO epoch keys on an established channel " +
                    "${messageStreamId.takeLast(30)} — NOT minting (would fork the channel). " +
                    "Recover the keys on the original device.")
                return
            } else {
                val keyHex = EpochKeyCrypto.generateEpochKey()
                keyHash = EpochKeyCrypto.computeKeyHash(keyHex)
                val keyId = "1.${PomboCrypto.randomHex(6)}"
                announce = JSONObject()
                    .put("t", StreamConstants.KEY_ANNOUNCE)
                    .put("epoch", 1)
                    .put("keyId", keyId)
                    .put("keyHash", keyHash)
                    .put("validFrom", System.currentTimeMillis())
                adopt = Triple(keyId, keyHex, 1)
            }
        }
        val ann = announce ?: return
        publishKeys(keysStreamId, ann)
        mutex.withLock {
            val s = getState(messageStreamId)
            applyAnnounceLocked(messageStreamId, s, ann, myAddress(), ann.optLong("validFrom"))
            adopt?.let { adoptLocked(messageStreamId, s, it.first, it.second, keyHash, it.third) }
            persist(messageStreamId, s)
        }
        val adopted = adopt
        if (adopted != null) {
            Log.i(TAG, "bootstrapped epoch 1 on ${keysStreamId.takeLast(30)}")
            onKeyAdopted(messageStreamId, adopted.first)
        } else {
            Log.i(TAG, "re-announced existing epoch on ${keysStreamId.takeLast(30)} (announce was missing from storage)")
        }
    }

    /**
     * Admin action: rotate to a new epoch (member removal). Old epochs stay
     * readable for members (D14); the removed member loses the future only.
     */
    suspend fun rotateEpoch(messageStreamId: String, keysStreamId: String): Int {
        check(isOwnAdmin(messageStreamId)) { "only the channel admin can announce a new epoch" }
        val keyHex = EpochKeyCrypto.generateEpochKey()
        val keyHash = EpochKeyCrypto.computeKeyHash(keyHex)
        var epoch = 0
        var keyId = ""
        mutex.withLock {
            val s = getState(messageStreamId)
            if (!s.loaded) { loadPersisted(messageStreamId, s); s.loaded = true }
            epoch = maxOf(s.currentEpoch, s.announces.keys.maxOrNull() ?: 0) + 1
            keyId = "$epoch.${PomboCrypto.randomHex(6)}"
        }
        val announce = JSONObject()
            .put("t", StreamConstants.KEY_ANNOUNCE)
            .put("epoch", epoch)
            .put("keyId", keyId)
            .put("keyHash", keyHash)
            .put("validFrom", System.currentTimeMillis())
        publishKeys(keysStreamId, announce)
        mutex.withLock {
            val s = getState(messageStreamId)
            applyAnnounceLocked(messageStreamId, s, announce, myAddress(), announce.optLong("validFrom"))
            adoptLocked(messageStreamId, s, keyId, keyHex, keyHash, epoch)
        }
        onKeyAdopted(messageStreamId, keyId)
        Log.i(TAG, "rotated to epoch $epoch on ${keysStreamId.takeLast(30)}")
        return epoch
    }

    // ---- Protocol handlers (live -4 subscription) ----

    suspend fun handleKeysMessage(
        messageStreamId: String, keysStreamId: String,
        data: JSONObject, publisherId: String?, timestamp: Long,
        /** Rank domain for the anti-stampede queue (channel member count). */
        memberCount: Int = 4
    ) {
        when (data.optString("t")) {
            StreamConstants.KEY_ANNOUNCE -> {
                val needRequest = mutex.withLock {
                    val s = getState(messageStreamId)
                    if (!s.loaded) { loadPersisted(messageStreamId, s); s.loaded = true }
                    val changed = applyAnnounceLocked(messageStreamId, s, data, publisherId, timestamp)
                    if (changed) persist(messageStreamId, s)
                    changed && missingEpochsLocked(s).isNotEmpty()
                }
                // Pull model: a live announce for an epoch we lack triggers a request
                if (needRequest) sendKeyRequest(messageStreamId, keysStreamId)
            }
            StreamConstants.KEY_REQUEST -> handleRequest(messageStreamId, keysStreamId, data, publisherId, memberCount)
            StreamConstants.KEY_WRAP -> handleWrap(messageStreamId, data)
        }
    }

    /** D13 conflict rule. Returns true when state changed. Caller holds the lock. */
    private fun applyAnnounceLocked(
        messageStreamId: String, s: ChannelState,
        data: JSONObject, publisherId: String?, timestamp: Long
    ): Boolean {
        val admin = adminOf(messageStreamId)
        if (publisherId == null || publisherId.lowercase() != admin) {
            Log.w(TAG, "KEY_ANNOUNCE from non-admin REJECTED: $publisherId on ${messageStreamId.takeLast(30)}")
            return false
        }
        val epoch = data.optInt("epoch", 0)
        val keyId = data.optString("keyId")
        val keyHash = data.optString("keyHash").lowercase()
        if (epoch < 1 || keyId.isEmpty() || keyHash.isEmpty()) return false

        // Freshness bookkeeping for the TTL re-announce — tracks the NEWEST
        // valid copy even when the conflict rule below keeps an older one
        if (timestamp > (s.announceFreshness[epoch] ?: 0L)) {
            s.announceFreshness[epoch] = timestamp
        }

        val incoming = Announce(
            keyId, keyHash, publisherId.lowercase(), timestamp,
            data.optLong("validFrom", timestamp))
        val existing = s.announces[epoch]
        if (existing != null) {
            // Same epoch: older timestamp wins; tie -> lower publisher address
            val keep = existing.timestamp < incoming.timestamp ||
                (existing.timestamp == incoming.timestamp && existing.publisher <= incoming.publisher)
            if (keep) return false
        }
        s.announces[epoch] = incoming
        if (epoch > s.currentEpoch) s.currentEpoch = epoch
        return true
    }

    /**
     * A live KEY_REQUEST: schedule an answer behind the anti-stampede rank
     * (§7.10, N-B). Anything we hear on -4 already passed the stream's
     * on-chain permission check.
     */
    private suspend fun handleRequest(
        messageStreamId: String, keysStreamId: String,
        data: JSONObject, publisherId: String?, memberCount: Int
    ) {
        mutex.withLock { recordRequesterLocked(getState(messageStreamId), publisherId) }
        if (publisherId?.lowercase() == myAddress()?.lowercase()) return  // our own
        val pubkey = data.optString("pubkey").ifEmpty { return }
        val requestId = data.optString("requestId").ifEmpty { return }
        val haveKeys = mutex.withLock { getState(messageStreamId).epochs.isNotEmpty() }
        if (!haveKeys) return
        scheduleAnswer(
            messageStreamId, keysStreamId,
            requestId, pubkey, data.optInt("fromEpoch", 1),
            rankFor(requestId, memberCount) * RANK_STEP_MS,
            requester = publisherId
        )
    }

    /**
     * Anti-stampede rank (§7.10): hash(identity ‖ requestId) mod N orders
     * members deterministically WITHOUT coordination. Rank 0 answers now;
     * each higher rank waits a step and stays silent for covered epochs.
     */
    private fun rankFor(requestId: String, memberCount: Int): Int {
        val n = memberCount.coerceIn(1, RANK_MAX)
        val me = myAddress()?.lowercase() ?: ""
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$me|$requestId".toByteArray(Charsets.UTF_8))
        return (digest[0].toInt() and 0xff) % n
    }

    /** Fire-and-forget: NEVER delay inline — the -4 handler must keep flowing,
     *  or the wraps whose arrival should silence us would queue behind us. */
    private fun scheduleAnswer(
        messageStreamId: String, keysStreamId: String,
        requestId: String, pubkey: String, fromEpoch: Int, delayMs: Long,
        requester: String?
    ) {
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            try {
                answerRequest(messageStreamId, keysStreamId, requestId, pubkey, fromEpoch, requester)
            } catch (e: Exception) {
                Log.w(TAG, "scheduled answer failed: ${e.message}")
            }
        }
    }

    /**
     * Answer with wraps for every epoch we hold from `fromEpoch` on (D14)
     * MINUS the epochs an observed wrap already covers — the N-B suppression
     * that turns thirty identical envelopes into one.
     */
    private suspend fun answerRequest(
        messageStreamId: String, keysStreamId: String,
        requestId: String, pubkey: String, fromEpoch: Int,
        requester: String?
    ) {
        // Gated (N-C): the epoch key only goes to whoever passes the CURRENT
        // gate. Fail-closed inside checkGateAccess — RPC trouble means no
        // wrap from us; the requester's retry finds a healthier responder.
        if (requester == null || !checkGateAccess(messageStreamId, requester)) {
            if (requester != null) {
                Log.i(TAG, "KEY_REQUEST from $requester refused by gate on ${messageStreamId.takeLast(20)}")
            }
            return
        }
        // D14: a PAID gate hands out ONLY the current epoch — the history
        // scope of a subscription is the future (fail-closed in the wiring).
        val paidOnly = currentEpochOnly(messageStreamId)
        val toWrap = mutex.withLock {
            val s = getState(messageStreamId)
            val covered = s.seenWraps[requestId] ?: emptySet<String>()
            s.epochs
                .filterKeys { it !in covered }
                .filterValues { it.epoch >= fromEpoch && (!paidOnly || it.epoch == s.currentEpoch) }
                .map { (keyId, e) -> Triple(keyId, e.keyHex, e.epoch) }
        }
        if (toWrap.isEmpty()) return
        var sent = 0
        for ((keyId, keyHex, epoch) in toWrap) {
            try {
                val wrapped = EpochKeyCrypto.wrapEpochKey(keyHex, pubkey)
                publishKeys(keysStreamId, JSONObject()
                    .put("t", StreamConstants.KEY_WRAP)
                    .put("requestId", requestId)
                    .put("keyId", keyId)
                    .put("epoch", epoch)
                    .put("tag", EpochKeyCrypto.computeWrapTag(pubkey, keyId))
                    .put("epk", wrapped.getString("epk"))
                    .put("iv", wrapped.getString("iv"))
                    .put("ct", wrapped.getString("ct")))
                mutex.withLock { recordSeenWrapLocked(getState(messageStreamId), requestId, keyId) }
                sent += 1
            } catch (e: Exception) {
                Log.w(TAG, "failed to wrap $keyId for request $requestId: ${e.message}")
            }
        }
        if (sent > 0) Log.d(TAG, "answered request $requestId with $sent wrap(s)")
    }

    /** Caller holds the lock. */
    private fun recordSeenWrapLocked(s: ChannelState, requestId: String, keyId: String) {
        val set = s.seenWraps.getOrPut(requestId) {
            if (s.seenWraps.size >= SEEN_WRAPS_MAX) {
                s.seenWraps.keys.firstOrNull()?.let { s.seenWraps.remove(it) }
            }
            mutableSetOf()
        }
        set.add(keyId)
    }

    /**
     * A wrap for our pending request: O(1) tag check, unwrap, verify against
     * the announced keyHash, adopt. Verification failure is a warn and a drop.
     */
    private suspend fun handleWrap(messageStreamId: String, data: JSONObject) {
        var adoptedKeyId: String? = null
        mutex.withLock {
            val s = getState(messageStreamId)
            // Suppression bookkeeping FIRST, for every well-formed wrap — this
            // is what lets higher-rank answerers stay silent (N-B)
            val rid = data.optString("requestId")
            val kid = data.optString("keyId")
            if (rid.isNotEmpty() && kid.isNotEmpty()) recordSeenWrapLocked(s, rid, kid)
            val pending = s.pendingRequest ?: return
            if (data.optString("requestId") != pending.requestId) return
            val keyId = data.optString("keyId").ifEmpty { return }
            if (s.epochs.containsKey(keyId)) return                    // already adopted

            val expectedTag = EpochKeyCrypto.computeWrapTag(pending.publicKey, keyId)
            if (!data.optString("tag").equals(expectedTag, ignoreCase = true)) return

            val epoch = data.optInt("epoch", 0)
            val announce = s.announces[epoch]
            if (announce == null || announce.keyId != keyId) {
                Log.w(TAG, "wrap for unannounced key ignored: $keyId")
                return
            }
            val keyHex = try {
                EpochKeyCrypto.unwrapEpochKey(data, pending.privateKey)
            } catch (e: Exception) {
                Log.w(TAG, "wrap failed to open: ${e.message}"); return
            }
            if (!EpochKeyCrypto.computeKeyHash(keyHex).equals(announce.keyHash, ignoreCase = true)) {
                Log.w(TAG, "wrap REJECTED — keyHash mismatch for $keyId (malicious or corrupted)")
                return
            }
            adoptLocked(messageStreamId, s, keyId, keyHex, announce.keyHash, epoch)
            adoptedKeyId = keyId
        }
        adoptedKeyId?.let {
            Log.i(TAG, "adopted key $it on ${messageStreamId.takeLast(30)}")
            onKeyAdopted(messageStreamId, it)
        }
    }

    /** Publish a KEY_REQUEST under a fresh request keypair (D12), rate-limited. */
    private suspend fun sendKeyRequest(messageStreamId: String, keysStreamId: String) {
        var request: JSONObject? = null
        mutex.withLock {
            val s = getState(messageStreamId)
            val pending = s.pendingRequest
            val interval = if (s.requestAttempts < REQUEST_FAST_ATTEMPTS)
                REQUEST_RETRY_FAST_MS else REQUEST_MIN_INTERVAL_MS
            if (pending != null && System.currentTimeMillis() - pending.sentAt < interval) return
            val missing = missingEpochsLocked(s)
            if (missing.isEmpty()) return
            val (priv, pub) = EpochKeyCrypto.generateRequestKeypair()
            val requestId = PomboCrypto.randomHex(16)
            s.pendingRequest = PendingRequest(requestId, priv, pub, System.currentTimeMillis())
            s.requestAttempts += 1
            request = JSONObject()
                .put("t", StreamConstants.KEY_REQUEST)
                .put("requestId", requestId)
                .put("pubkey", pub)
                .put("fromEpoch", missing.min())
        }
        publishKeys(keysStreamId, request ?: return)
        Log.i(TAG, "requested missing epochs on ${keysStreamId.takeLast(30)}")
    }

    /**
     * Requester-side retry while the first requests may have been lost to a
     * cold topology: re-send (respecting the fast backoff) until the current
     * key lands or the attempts move to the slow interval. Call after a
     * blocking ensure that left the channel without keys.
     */
    suspend fun retryRequestIfWaiting(messageStreamId: String, keysStreamId: String): Boolean {
        val waiting = mutex.withLock {
            val s = state[messageStreamId] ?: return false
            missingEpochsLocked(s).isNotEmpty()
        }
        if (waiting) sendKeyRequest(messageStreamId, keysStreamId)
        return waiting
    }

    private fun adoptLocked(
        messageStreamId: String, s: ChannelState,
        keyId: String, keyHex: String, keyHash: String, epoch: Int
    ) {
        s.epochs[keyId] = EpochEntry(keyHex, keyHash, epoch)
        if (epoch > s.currentEpoch) s.currentEpoch = epoch
        s.requestAttempts = 0   // future rotations start on the fast retry again
        persist(messageStreamId, s)
    }

    /**
     * Load persisted state without any network work — the fast path a channel
     * open uses to decide whether the blocking ensure is needed at all.
     */
    suspend fun loadPersistedState(messageStreamId: String) = mutex.withLock {
        val s = getState(messageStreamId)
        if (!s.loaded) { loadPersisted(messageStreamId, s); s.loaded = true }
    }

    // ---- Content encryption hooks (channel layer) ----

    /**
     * Encrypt a payload with the CURRENT epoch key, or null while waiting for
     * one — the caller fails closed (never plaintext on a native channel).
     */
    suspend fun encryptCurrent(messageStreamId: String, payload: JSONObject): JSONObject? {
        return mutex.withLock {
            val s = state[messageStreamId] ?: return null
            if (s.currentEpoch == 0) return null
            val announce = s.announces[s.currentEpoch] ?: return null
            val entry = s.epochs[announce.keyId] ?: return null
            EpochKeyCrypto.encryptWithEpochKey(payload, entry.keyHex, announce.keyId)
        }
    }

    /**
     * Open an epoch envelope, or null when we do not (yet) hold the `kid` —
     * NOT an error (§7.9): the missing key triggers a rate-limited refresh
     * and the caller skips; storage-backed messages come back via the
     * refresh fired on adoption.
     *
     * Kid freshness (N-C, `gated` only — native's on-chain revocation cuts
     * harder): sticky signatures mean an ex-member still authors valid
     * envelopes, so old epoch keys must stop being ACCEPTABLE. Live traffic
     * must use the current epoch (previous tolerated 10 min post-rotation);
     * history must use the kid in force at the message timestamp (highest
     * validFrom <= ts). A violation returns null WITHOUT the missing-kid
     * refresh — it is stale-key spam, not a missing key. Accepted residual:
     * forged timestamps can backdate into old windows (Q11, v0-accept).
     */
    suspend fun tryDecrypt(
        messageStreamId: String, keysStreamId: String, envelope: JSONObject,
        gated: Boolean = false, live: Boolean = true, timestamp: Long = 0L
    ): JSONObject? {
        val kid = envelope.optString("k")
        val lookup = mutex.withLock {
            val s = state[messageStreamId]
            val entry = s?.epochs?.get(kid)
            if (entry == null) null else {
                val fresh = !gated || kidIsFreshLocked(s, kid, entry.epoch, live, timestamp)
                Pair(entry.keyHex, fresh)
            }
        }
        if (lookup == null) {
            noteMissingKid(messageStreamId, keysStreamId)
            return null
        }
        if (!lookup.second) {
            Log.w(TAG, "kid freshness violation (kid $kid, live=$live) — dropping")
            return null
        }
        return try {
            EpochKeyCrypto.decryptWithEpochKey(envelope, lookup.first)
        } catch (e: Exception) {
            Log.w(TAG, "epoch envelope failed to open (kid $kid): ${e.message}")
            null
        }
    }

    /** The current epoch key with its kid — for sealers that capture one key
     *  per transfer (storage chunks), like a message captures one at send. */
    data class CurrentKey(val kid: String, val keyHex: String)

    /** The current key, or null while waiting for one (caller fails closed). */
    suspend fun currentKey(messageStreamId: String): CurrentKey? {
        return mutex.withLock {
            val s = state[messageStreamId] ?: return null
            if (s.currentEpoch == 0) return null
            val announce = s.announces[s.currentEpoch] ?: return null
            if (!s.epochs.containsKey(announce.keyId)) return null
            CurrentKey(announce.keyId, s.epochs[announce.keyId]!!.keyHex)
        }
    }

    /**
     * Seal a binary frame (media piece) with the CURRENT epoch key, or null
     * while waiting for one — the caller fails closed, exactly like
     * [encryptCurrent] for JSON payloads.
     */
    suspend fun sealBinaryCurrent(messageStreamId: String, bytes: ByteArray): ByteArray? {
        return mutex.withLock {
            val s = state[messageStreamId] ?: return null
            if (s.currentEpoch == 0) return null
            val announce = s.announces[s.currentEpoch] ?: return null
            val entry = s.epochs[announce.keyId] ?: return null
            EpochKeyCrypto.sealBinaryWithEpochKey(bytes, entry.keyHex, announce.keyId)
        }
    }

    /**
     * Open an epoch binary envelope (0x04) — same policy as [tryDecrypt]:
     * unknown kid → rate-limited refresh + null; gated freshness violation →
     * null without a refresh (stale-key spam, not a missing key).
     */
    suspend fun tryOpenBinary(
        messageStreamId: String, keysStreamId: String, bytes: ByteArray,
        gated: Boolean = false, live: Boolean = true, timestamp: Long = 0L
    ): ByteArray? {
        val parsed = EpochKeyCrypto.parseBinaryEpochEnvelope(bytes) ?: return null
        val lookup = mutex.withLock {
            val s = state[messageStreamId]
            val entry = s?.epochs?.get(parsed.kid)
            if (entry == null) null else {
                val fresh = !gated || kidIsFreshLocked(s, parsed.kid, entry.epoch, live, timestamp)
                Pair(entry.keyHex, fresh)
            }
        }
        if (lookup == null) {
            noteMissingKid(messageStreamId, keysStreamId)
            return null
        }
        if (!lookup.second) {
            Log.w(TAG, "kid freshness violation (kid ${parsed.kid}, live=$live) — dropping")
            return null
        }
        return try {
            EpochKeyCrypto.decryptBinaryWithEpochKey(parsed, lookup.first)
        } catch (e: Exception) {
            Log.w(TAG, "epoch binary envelope failed to open (kid ${parsed.kid}): ${e.message}")
            null
        }
    }

    /** The kid-freshness rule (caller holds the lock). True = acceptable. */
    private fun kidIsFreshLocked(
        s: ChannelState, kid: String, kidEpoch: Int, live: Boolean, timestamp: Long
    ): Boolean {
        val current = s.announces[s.currentEpoch] ?: return true  // no anchor — cannot judge
        if (kid == current.keyId) return true                     // current epoch always fine
        if (live) {
            // Previous epoch tolerated briefly after a rotation (messages in
            // flight, slow adopters); anything older is stale-key spam.
            return kidEpoch == s.currentEpoch - 1 &&
                System.currentTimeMillis() - current.validFrom < KID_FRESHNESS_TOLERANCE_MS
        }
        // History: the epoch in force at the message timestamp.
        if (timestamp <= 0L) return false
        var epochInForce = 0
        var bestValidFrom = Long.MIN_VALUE
        for ((epoch, announce) in s.announces) {
            if (announce.validFrom <= timestamp && announce.validFrom > bestValidFrom) {
                bestValidFrom = announce.validFrom
                epochInForce = epoch
            }
        }
        return kidEpoch == epochInForce
    }

    /** Unknown kid seen: refresh key state at most once per interval. */
    private suspend fun noteMissingKid(messageStreamId: String, keysStreamId: String) {
        val refresh = mutex.withLock {
            val s = getState(messageStreamId)
            val now = System.currentTimeMillis()
            if (now - s.lastMissingRefresh < REQUEST_MIN_INTERVAL_MS) false
            else { s.lastMissingRefresh = now; true }
        }
        if (refresh) {
            // An unknown kid means the channel already HAS epoch traffic — by
            // definition not virgin, so a keyless admin must never mint here.
            try { ensureChannelKeys(messageStreamId, keysStreamId, allowMint = false) } catch (e: Exception) {
                Log.w(TAG, "refresh after missing kid failed: ${e.message}")
            }
        }
    }

    /** Does the channel hold a usable current key? (UI/waiting state) */
    suspend fun hasCurrentKey(messageStreamId: String): Boolean {
        return mutex.withLock {
            val s = state[messageStreamId] ?: return false
            val announce = s.announces[s.currentEpoch] ?: return false
            s.epochs.containsKey(announce.keyId)
        }
    }
}

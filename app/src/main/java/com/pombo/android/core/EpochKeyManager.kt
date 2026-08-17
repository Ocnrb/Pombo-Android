package com.pombo.android.core

import android.util.Log
import com.pombo.android.data.EpochKeyStore
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
    private val myAddress: () -> String?,
    private val publishKeys: suspend (keysStreamId: String, data: JSONObject) -> Unit,
    private val resendKeys: suspend (keysStreamId: String) -> List<Entry>,
    /** Fired on adoption — the channel layer re-pulls history so parked messages open. */
    private val onKeyAdopted: (messageStreamId: String, keyId: String) -> Unit
) {
    data class Entry(val data: JSONObject, val publisherId: String?, val timestamp: Long)

    private class EpochEntry(val keyHex: String, val keyHash: String, val epoch: Int)
    private class Announce(
        val keyId: String, val keyHash: String,
        val publisher: String, val timestamp: Long
    )

    private class ChannelState {
        val epochs = HashMap<String, EpochEntry>()        // keyId -> entry
        val announces = HashMap<Int, Announce>()           // epoch -> announce
        var currentEpoch = 0
        var pendingRequest: PendingRequest? = null         // memory only (D12)
        var loaded = false
        var lastMissingRefresh = 0L
        /** Consecutive unanswered requests — drives the retry backoff. */
        var requestAttempts = 0
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
                    a.optString("publisher"), a.optLong("timestamp"))
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
                .put("publisher", a.publisher).put("timestamp", a.timestamp))
        }
        store.save(messageStreamId, JSONObject()
            .put("epochs", epochs).put("announces", announces).put("currentEpoch", s.currentEpoch))
    }

    /** Drop runtime + persisted state (channel left/deleted). */
    suspend fun forgetChannel(messageStreamId: String) = mutex.withLock {
        state.remove(messageStreamId)
        store.clear(messageStreamId)
    }

    /**
     * Bring a native channel's key state up to date: pull announces from -4
     * storage, bootstrap/re-announce as admin, or request missing keys as a
     * member. Call on channel open, before the -1 history pull.
     */
    suspend fun ensureChannelKeys(messageStreamId: String, keysStreamId: String) {
        val entries = try { resendKeys(keysStreamId) } catch (e: Exception) { emptyList() }
        var toRequest = false
        var toBootstrap = false
        mutex.withLock {
            val s = getState(messageStreamId)
            if (!s.loaded) { loadPersisted(messageStreamId, s); s.loaded = true }
            var changed = false
            for (entry in entries) {
                if (entry.data.optString("t") == StreamConstants.KEY_ANNOUNCE) {
                    if (applyAnnounceLocked(messageStreamId, s, entry.data, entry.publisherId, entry.timestamp)) {
                        changed = true
                    }
                }
                // Requests are never answered from history; wraps from history
                // are addressed to request keys that no longer exist.
            }
            if (changed) persist(messageStreamId, s)
            toBootstrap = s.announces.isEmpty() && isOwnAdmin(messageStreamId)
            toRequest = s.announces.isNotEmpty() && missingEpochsLocked(s).isNotEmpty()
        }
        if (toBootstrap) bootstrapOrReannounce(messageStreamId, keysStreamId)
        else if (toRequest) sendKeyRequest(messageStreamId, keysStreamId)
        else if (!isOwnAdmin(messageStreamId)) {
            mutex.withLock {
                if (getState(messageStreamId).announces.isEmpty()) {
                    Log.i(TAG, "no announce yet on ${keysStreamId.takeLast(30)} — waiting for the admin")
                }
            }
        }
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
    private suspend fun bootstrapOrReannounce(messageStreamId: String, keysStreamId: String) {
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
        data: JSONObject, publisherId: String?, timestamp: Long
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
            StreamConstants.KEY_REQUEST -> handleRequest(messageStreamId, keysStreamId, data, publisherId)
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

        val incoming = Announce(keyId, keyHash, publisherId.lowercase(), timestamp)
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
     * Answer a live KEY_REQUEST with wraps for every epoch we hold from
     * `fromEpoch` on (D14 native policy: all retained epochs). Anything we
     * hear on -4 already passed the stream's on-chain permission check.
     */
    private suspend fun handleRequest(
        messageStreamId: String, keysStreamId: String, data: JSONObject, publisherId: String?
    ) {
        if (publisherId?.lowercase() == myAddress()?.lowercase()) return  // our own
        val pubkey = data.optString("pubkey").ifEmpty { return }
        val requestId = data.optString("requestId").ifEmpty { return }
        val fromEpoch = data.optInt("fromEpoch", 1)

        val toWrap = mutex.withLock {
            getState(messageStreamId).epochs
                .filterValues { it.epoch >= fromEpoch }
                .map { (keyId, e) -> Triple(keyId, e.keyHex, e.epoch) }
        }
        if (toWrap.isEmpty()) return
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
            } catch (e: Exception) {
                Log.w(TAG, "failed to wrap $keyId for request $requestId: ${e.message}")
            }
        }
        Log.d(TAG, "answered request $requestId with ${toWrap.size} wrap(s)")
    }

    /**
     * A wrap for our pending request: O(1) tag check, unwrap, verify against
     * the announced keyHash, adopt. Verification failure is a warn and a drop.
     */
    private suspend fun handleWrap(messageStreamId: String, data: JSONObject) {
        var adoptedKeyId: String? = null
        mutex.withLock {
            val s = getState(messageStreamId)
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
     */
    suspend fun tryDecrypt(
        messageStreamId: String, keysStreamId: String, envelope: JSONObject
    ): JSONObject? {
        val kid = envelope.optString("k")
        val keyHex = mutex.withLock {
            state[messageStreamId]?.epochs?.get(kid)?.keyHex
        }
        if (keyHex == null) {
            noteMissingKid(messageStreamId, keysStreamId)
            return null
        }
        return try {
            EpochKeyCrypto.decryptWithEpochKey(envelope, keyHex)
        } catch (e: Exception) {
            Log.w(TAG, "epoch envelope failed to open (kid $kid): ${e.message}")
            null
        }
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
            try { ensureChannelKeys(messageStreamId, keysStreamId) } catch (e: Exception) {
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

package com.pombo.android

import android.util.Log
import com.pombo.android.bridge.PomboBridge
import com.pombo.android.core.InviteToken
import com.pombo.android.core.PomboCrypto
import com.pombo.android.core.Protocol
import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import com.pombo.android.data.ChannelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Public channel preview for the Explore tab (web: card + tags + last message). */
data class ExploreChannel(
    val messageStreamId: String,
    val name: String,
    val description: String,
    val type: String,
    val language: String = "",
    val category: String = "",
    val lastSender: String = "",
    val lastText: String = "",
    /** Raw address behind [lastSender], so the card can resolve ENS at render. */
    val lastSenderAddress: String = "",
    /** Drives the megaphone on the Explore card, as in the web's readOnlyBadge. */
    val readOnly: Boolean = false,
    /** Gated (N-D): the PomboGate clone from metadata `g` — routes the tap
     *  (TOKEN/NFT with access → preview; PAID / no access → join flow). */
    val gateAddress: String? = null,
    /** Resolved gate mode (drives the Open/Gated/Paid markers). */
    val gateMode: Int? = null,
    /** 3-line access stack on the card: VERB / VALUE / QUALIFIER
     *  ("Subscribe" / "500 POL" / "30 days" · "Hold" / … / "in your wallet"). */
    val gateVerb: String? = null,
    val gateValue: String? = null,
    val gateQualifier: String? = null
)

/** Quoted message carried by a reply (web: msg.replyTo). */
data class ReplyRef(
    val id: String,
    val sender: String,
    val senderName: String?,
    val text: String
)

/** Message ready for the UI. verified: null = not yet verified. */
data class UiMessage(
    val id: String,
    val text: String,
    val sender: String,
    val senderName: String?,
    val timestamp: Long,
    val mine: Boolean,
    val pending: Boolean = false,
    val verified: Boolean? = null,
    /** -1 invalid, 0 valid signature, 1 ENS verified, 2 trusted contact. */
    val trustLevel: Int = 0,
    val edited: Boolean = false,
    /** Reverse-resolved ENS name for the sender, when available. */
    val ensName: String? = null,
    /** ENS avatar URL — replaces the generated avatar when present. */
    val ensAvatar: String? = null,
    val replyTo: ReplyRef? = null,
    // Chunked image (transport 'chunked' v2). imageBytes null while assembling.
    val isImage: Boolean = false,
    val imageId: String? = null,
    val imageBytes: ByteArray? = null,
    val imageMime: String? = null,
    /**
     * P2P shared file (wire type `file_announce`). The bytes are NOT in the
     * message — the announcement only describes the file and its piece hashes;
     * the content is fetched from whoever is seeding it.
     */
    val file: com.pombo.android.core.MediaController.FileMetadata? = null,
    /** Persistent File Sharing announce (wire type `storage_file_announce`). */
    val storageFile: com.pombo.android.core.StorageMedia.StorageFileMetadata? = null
)

/**
 * Channel logic — mirror of the web channels.js over PomboBridge.
 * All wire formats are identical to the Pombo web ones (see spec in the README).
 */
class ChannelManager(
    private val bridge: PomboBridge,
    private val store: ChannelStore,
    private val scope: CoroutineScope,
    private val myAddress: () -> String?,
    private val myUsername: () -> String?,
    /** Persistent caches so cold starts paint before the network answers. */
    private val imageStore: com.pombo.android.core.ChannelImageStore,
    private val previewStore: com.pombo.android.core.LatestMessageStore,
    private val ensStore: com.pombo.android.core.EnsStore,
    private val blobStore: com.pombo.android.core.ImageBlobStore,
    private val sentDmStore: com.pombo.android.data.SentDmStore,
    /** Own DM/write-only reactions — no resend returns them (web sentReactions). */
    private val sentReactionsStore: com.pombo.android.data.SentReactionsStore? = null,
    private val inviteStore: com.pombo.android.data.InviteStore,
    private val unreadStore: com.pombo.android.data.UnreadStore,
    /** Persisted epoch keys for native channels (-4 protocol, N-A). */
    private val epochKeyStore: com.pombo.android.data.EpochKeyStore,
    /**
     * Where partial file transfers live. filesDir, never cacheDir: the system
     * may evict a cache directory at any moment, and a half-evicted transfer
     * would resume against a bitmap claiming pieces that are gone.
     */
    private val transferDir: java.io.File,
    /** Trust level 2 in the web comes from the trusted-contacts list. */
    private val isTrustedContact: (String) -> Boolean = { false },
    /** Blocked peers (web secureStorage.isBlocked) — a synced slice. */
    private val isBlockedPeer: (String) -> Boolean = { false },
    /** Persist a new block; the caller owns the settings store. */
    private val persistBlockedPeer: (String) -> Unit = {}
) {

    /**
     * Epoch keys for native channels (-4, N-A). Transport is injected:
     * protocol messages publish AS THE ACCOUNT with encryptionType NONE via
     * the bridge's publishAsAccount (client.publish would force the SDK's AES
     * + group-key exchange on a members-only stream — the very dependency the
     * epoch protocol replaces), and announce history is a plain -4 resend.
     */
    private val epochKeys = com.pombo.android.core.EpochKeyManager(
        store = epochKeyStore,
        scope = scope,
        myAddress = myAddress,
        publishKeys = { keysStreamId, data ->
            // Gated (N-C): the -4 grant is on the clone, not per member — the
            // account signs, the clone is the on-wire publisher, receivers
            // recover the author from the envelope.
            val gate = channelByStream(keysStreamId)
                ?.takeIf { it.type == "gated" }
                ?.let { it.gateAddress ?: throw IllegalStateException(
                    "Gate address unknown for ${it.messageStreamId} — cannot publish keys") }
            val method = if (gate != null) "publishAsGate" else "publishAsAccount"
            val args = JSONObject()
                .put("streamId", keysStreamId)
                .put("partition", StreamConstants.P_KEY_EXCHANGE)
                .put("content", data)
            gate?.let { args.put("gateAddress", it) }
            bridge.call(method, args)
        },
        resendKeys = { keysStreamId ->
            val entries = mutableListOf<com.pombo.android.core.EpochKeyManager.Entry>()
            val gatedChannel = channelByStream(keysStreamId)?.takeIf { it.type == "gated" }
            try {
                val res = bridge.call("resend", JSONObject()
                    .put("streamId", keysStreamId)
                    .put("partition", StreamConstants.P_KEY_EXCHANGE)
                    .put("last", 1000)
                    .put("recoverSigner", gatedChannel != null), 30_000)
                val arr = res.optJSONArray("messages")
                if (arr != null) for (i in 0 until arr.length()) {
                    val entry = arr.optJSONObject(i) ?: continue
                    val content = entry.opt("content") as? JSONObject ?: continue
                    val meta = entry.optJSONObject("meta") ?: JSONObject()
                    // Gated: the epoch protocol's identity checks (admin set,
                    // own-request skip, gate check) need the AUTHOR — the
                    // envelope signer — never the clone.
                    val publisher = if (gatedChannel != null) {
                        gatedAuthor(gatedChannel, keysStreamId, meta) ?: continue
                    } else meta.optString("publisherId").ifEmpty { null }
                    entries.add(com.pombo.android.core.EpochKeyManager.Entry(
                        content,
                        publisher,
                        meta.optLong("timestamp", 0L)))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // channel switch — propagate, never swallow
            } catch (e: Exception) {
                // No storage attached yet / empty stream — an empty list is
                // the correct cold start ("no announces")
                Log.d(TAG, "keys resend empty (${e.message})")
            }
            entries
        },
        onKeyAdopted = { messageStreamId, _ -> refreshAfterEpochKey(messageStreamId) },
        checkGateAccess = { messageStreamId, requester ->
            val channel = channelByStream(messageStreamId)
            if (channel?.type != "gated") true
            else {
                val gate = channel.gateAddress
                if (gate == null) false // fail-closed: unknown gate wraps nothing
                else try {
                    bridge.call("gateCheckAccess", JSONObject()
                        .put("gate", gate).put("user", requester)).optBoolean("access", false)
                } catch (e: Exception) {
                    Log.w(TAG, "gateCheckAccess failed (fail-closed): ${e.message}")
                    false
                }
            }
        },
        currentEpochOnly = { messageStreamId ->
            val channel = channelByStream(messageStreamId)
            val gate = channel?.takeIf { it.type == "gated" }?.gateAddress
            if (gate == null) false
            else try {
                // gateInfo params are immutable — the bridge caches nothing,
                // but the mode read shares the answerRequest's rare cadence
                bridge.call("gateInfo", JSONObject().put("gate", gate))
                    .optInt("mode", GATE_MODE_PAID) == GATE_MODE_PAID
            } catch (e: Exception) {
                Log.w(TAG, "gateInfo failed, answering current epoch only: ${e.message}")
                true
            }
        }
    )

    private var epochRefreshJob: Job? = null

    /**
     * A key was adopted: messages skipped as "waiting for key" are sitting in
     * storage — re-pull the recent window through the normal handlers (which
     * dedupe by id and re-apply pending overrides). Debounced: adopting N
     * epochs in a burst (join) fires one refresh.
     */
    private fun refreshAfterEpochKey(messageStreamId: String) {
        if (_current.value?.messageStreamId != messageStreamId) return
        _waitingForKeys.value = false
        epochRefreshJob?.cancel()
        epochRefreshJob = scope.launch {
            delay(1_500)
            val channel = _current.value?.takeIf { it.messageStreamId == messageStreamId }
                ?: return@launch
            Log.i(TAG, "epochKeys: refreshing history after key adoption")
            loadHistory(channel, switchGeneration)
        }
    }

    /**
     * P2P file transfers. Owned here rather than beside the channel list
     * because the only questions it answers from outside — "does this channel
     * still need its media partitions?" — are asked during a channel switch,
     * and every subscribe/unsubscribe has to stay inside [channelSwitchMutex].
     */
    val media = com.pombo.android.core.MediaController(
        bridge = bridge,
        scope = scope,
        myAddress = myAddress,
        transferDir = { transferDir },
        transport = object : com.pombo.android.core.MediaController.Transport {
            /**
             * Signals seal in the BRIDGE rather than through [publishContent].
             *
             * The sealing rule is still honoured — the password is passed on
             * every call and the bridge encrypts whenever it gets one, so no
             * call site can forget. What moves is only WHERE the AES work
             * happens, and it has to move: a piece_request goes out per piece,
             * and PBKDF2 through Bouncy Castle costs about a second each. On a
             * 113 MB file that is over eight minutes spent asking.
             */
            override suspend fun publishMediaSignal(
                ephemeralStreamId: String,
                payload: JSONObject,
                password: String?,
                isDm: Boolean
            ) {
                if (isDm) {
                    // DM signals are sealed-sender v2 to the PEER's inbox
                    // ephemeral (web media.js: dmManager.sealAndPublish), one
                    // fresh throwaway publisher per signal. The peer is the
                    // inbox owner — the stream id's prefix. Sealing stays
                    // inside publishContent, the one place that decides how a
                    // payload is encrypted.
                    val peerAddr = ephemeralStreamId.substringBefore('/').lowercase()
                    publishContent(
                        ephemeralStreamId, StreamConstants.EPH_MEDIA_SIGNALS,
                        payload, password = null, dmPeer = peerAddr
                    )
                    return
                }
                // Channel signals ride the channel's ephemeral identity too —
                // "everything that channel sends" (channelIdentity.js): a
                // wallet-published piece_request would undo the pseudonym for
                // the whole transfer. Password sealing stays in the bridge.
                publishChannel(
                    channelByStream(ephemeralStreamId), ephemeralStreamId,
                    StreamConstants.EPH_MEDIA_SIGNALS, payload, password
                )
            }

            /**
             * Pieces bypass publishContent because they are bytes, not JSON:
             * password encryption for them happens inside the bridge, where
             * PBKDF2 runs in BoringSSL instead of Bouncy Castle. DM pieces
             * likewise seal in the bridge, with the pair's ECDH key.
             */
            override suspend fun publishMediaPiece(
                ephemeralStreamId: String,
                bytes: ByteArray,
                password: String?,
                isDm: Boolean
            ) {
                if (isDm) {
                    // Sealed-sender 0x02 envelope, ONE ephemeral key per
                    // transfer (web: sealer cached per (file, stream)). The
                    // fileId is already inside the piece frame
                    // [0x01][fileId:36][idx:4][data], so the sealer is keyed
                    // by (fileId, destination stream) without widening the
                    // Transport interface. The bridge holds the sealer and
                    // publishes under its throwaway identity.
                    val peerAddr = ephemeralStreamId.substringBefore('/').lowercase()
                    val pk = dmMediaPeerKey(ephemeralStreamId)
                        ?: throw IllegalStateException("Peer public key unavailable for DM media")
                    val fileId = if (bytes.size >= 37) String(bytes, 1, 36, Charsets.UTF_8) else ""
                    val sealerKey = "$fileId|$ephemeralStreamId"
                    bridge.call("dmBinarySealerCreate", JSONObject()
                        .put("key", sealerKey)
                        .put("recipientAddress", peerAddr)
                        .put("recipientPublicKey", pk))
                    bridge.publishBinary(
                        ephemeralStreamId,
                        StreamConstants.EPH_MEDIA_DATA,
                        bytes,
                        password = null,
                        dmSealerKey = sealerKey
                    )
                    return
                }
                // Channel pieces carry the inline proof (0x03) and publish
                // under the channel identity — native channels stay on the
                // account (their grants are on-chain per member; the piece
                // stays a legacy 0x01 frame there).
                val ephemeral = !isEpochChannel(channelByStream(ephemeralStreamId))
                bridge.publishBinary(
                    ephemeralStreamId,
                    StreamConstants.EPH_MEDIA_DATA,
                    bytes,
                    password,
                    channelEphemeral = ephemeral
                )
            }
        }
    )

    /**
     * On-chain storage-endpoint resolution for the Persistent File Sharing
     * transport. The bridge does the two SDK calls; the web-safe URL filter and
     * rotation/health live natively in [com.pombo.android.core.StorageEndpoints].
     */
    private val storageEndpoints = com.pombo.android.core.StorageEndpoints(
        fetcher = { streamId ->
            val res = bridge.call("resolveStorageEndpoints", JSONObject().put("streamId", streamId))
            val arr = res.optJSONArray("nodes") ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val n = arr.optJSONObject(i) ?: return@mapNotNull null
                val urlsArr = n.optJSONArray("urls") ?: org.json.JSONArray()
                com.pombo.android.core.StorageEndpoints.Node(
                    nodeAddress = n.optString("nodeAddress"),
                    urls = (0 until urlsArr.length()).map { urlsArr.optString(it) }
                )
            }
        }
    )

    /**
     * Persistent File Sharing engine (storage-node transport). Standalone (not
     * owned like [media]) — storage never holds live subscriptions, so nothing
     * ties it to the channel-switch mutex. It reaches the network for the announce
     * through [publishContent], the single sealing point; raw chunk publishes go
     * straight to the bridge with the timestamp it needs for verify.
     */
    val storageMedia = com.pombo.android.core.StorageMedia(
        bridge = bridge,
        endpoints = storageEndpoints,
        scope = scope,
        transferDir = { transferDir },
        transport = object : com.pombo.android.core.StorageMedia.Transport {
            override suspend fun publishAnnounce(
                messageStreamId: String, announce: JSONObject, password: String?, isDm: Boolean
            ): Long {
                if (isDm) {
                    // The announce rides a sealed-sender v2 envelope to the
                    // peer's inbox P0, through the single sealing point, like
                    // every DM message. Returns the publish timestamp.
                    val peer = _current.value?.peerAddress ?: throw IllegalStateException("DM announce: no peer")
                    return publishContent(messageStreamId, StreamConstants.P_MESSAGES, announce, password = null, dmPeer = peer)
                }
                // Regular/password channel: ephemeral identity + proof via the
                // channel rule (account only for native/readOnly).
                return publishChannel(channelByStream(messageStreamId), messageStreamId, StreamConstants.P_MESSAGES, announce, password)
            }

            override fun myAddress(): String? = this@ChannelManager.myAddress()
            override fun username(): String? = this@ChannelManager.myUsername()
        }
    )

    // distinctBy: the stream id keys the channel-list LazyColumn, so ONE
    // duplicate row in the persisted store crashes the app at first paint
    // ("Key was already used") — seen live on a device whose old state had a
    // channel recorded twice. Deduping at load both boots cleanly and heals
    // the store on the next save.
    private val _channels = MutableStateFlow(store.load().distinctBy { it.messageStreamId })

    /** Re-reads the list after the storage scope changes (account switch/guest). */
    fun reloadChannels() {
        scope.launch { channelSwitchMutex.withLock { closeCurrentInternal() } }
        _channels.value = store.load().distinctBy { it.messageStreamId }
        _channelOrder.value = store.loadOrder()
    }
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    /** User's manual channel order (messageStreamIds); the list UI sorts by it. */
    private val _channelOrder = MutableStateFlow(store.loadOrder())
    val channelOrder: StateFlow<List<String>> = _channelOrder.asStateFlow()
    fun setChannelOrder(order: List<String>) {
        store.saveOrder(order)
        _channelOrder.value = order
    }

    private val _current = MutableStateFlow<Channel?>(null)
    val current: StateFlow<Channel?> = _current.asStateFlow()

    /** True while the open channel is a non-persisted preview (web: preview mode). */
    private val _isPreview = MutableStateFlow(false)
    val isPreview: StateFlow<Boolean> = _isPreview.asStateFlow()

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    /** messageId -> emoji -> set of senderIds (in RAM, like the web). */
    private val _reactions = MutableStateFlow<Map<String, Map<String, Set<String>>>>(emptyMap())
    val reactions: StateFlow<Map<String, Map<String, Set<String>>>> = _reactions.asStateFlow()

    private val _onlineCount = MutableStateFlow(0)
    val onlineCount: StateFlow<Int> = _onlineCount.asStateFlow()

    /**
     * False from the moment a channel starts opening until its subscriptions
     * are live. The header shows "Connecting…" in the online-count slot while
     * it is false, so that slot is never empty and the title above it does not
     * jump once presence lands.
     */
    private val _presenceReady = MutableStateFlow(false)
    val presenceReady: StateFlow<Boolean> = _presenceReady.asStateFlow()

    // Moderation (ADMIN_STATE on -3/P0). Latest-wins by rev; owner-authored only.
    data class Pin(val targetId: String, val text: String, val sender: String)
    private val _pins = MutableStateFlow<List<Pin>>(emptyList())
    val pins: StateFlow<List<Pin>> = _pins.asStateFlow()
    private val _hiddenIds = MutableStateFlow<Set<String>>(emptySet())
    val hiddenIds: StateFlow<Set<String>> = _hiddenIds.asStateFlow()
    private val _bannedMembers = MutableStateFlow<Set<String>>(emptySet())
    val bannedMembers: StateFlow<Set<String>> = _bannedMembers.asStateFlow()

    /**
     * Last ADMIN_STATE revision applied, keyed by admin stream — NOT a single
     * counter. Revisions are per channel, so one shared field let a late
     * ADMIN_STATE from the channel the user just left raise the bar for the
     * channel now open: with A at rev 40 arriving after B opened, B's own rev 3
     * was silently discarded for the rest of the session (no pins, no bans, no
     * error), and moderating B would then publish at rev 41 and corrupt the
     * revision sequence for every other participant.
     *
     * Keyed, the value survives across opens, which is also more correct than
     * the old reset-to-zero: revisions only ever move forward for a channel.
     */
    private val adminRevs = HashMap<String, Int>()

    /** Snapshot timestamps beside the revs — the web's latest-wins compares
     *  (rev, ts), so a stale snapshot sharing a rev must not win (M-C2). */
    private val adminTs = HashMap<String, Long>()

    /** Admin streams whose history was scanned at least once this session —
     *  publishing a new rev before that would restart from rev=1 and lose to
     *  every peer holding a higher one (M-C1; web gates on adminLoaded). */
    private val adminLoaded = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Images whose message was deleted (web `deletedImageIds`, media.js:180).
     * Chunks and manifests for these ids are refused on arrival — without the
     * tombstone a paginated resend overlapping the delete resurrected the
     * image. Session-global on purpose: NOT cleared on channel switch, since
     * a deleted image is deleted everywhere.
     */
    private val deletedImageIds = java.util.Collections.synchronizedSet(HashSet<String>())

    // Scroll-up pagination (web: channel.oldestTimestamp / hasMoreHistory / loadingHistory).
    private val _waitingForKeys = MutableStateFlow(false)
    /** Epoch channel open without a usable current key — the UI's "waiting
     *  for channel keys" state, cleared on adoption. */
    val waitingForKeys: StateFlow<Boolean> = _waitingForKeys.asStateFlow()

    private val _hasMoreHistory = MutableStateFlow(false)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()
    private val _loadingHistory = MutableStateFlow(false)
    val loadingHistory: StateFlow<Boolean> = _loadingHistory.asStateFlow()
    /** Oldest content timestamp seen on P0 — the cursor for the next page. */
    @Volatile private var oldestTimestamp: Long = 0L
    /**
     * Identifies the current *viewing session*, not the channel: it advances on
     * every open and every close, so reopening the same channel also invalidates
     * the previous session's in-flight work.
     *
     * This is the web's fence (channels.js `switchGeneration`, bumped in
     * `setCurrentChannel`). Async work captures it before it suspends and calls
     * [stillCurrent] after every resumption, before touching shared state. We
     * already had the counter but only consulted it in the two pagination
     * paths, so a slow open could write channel A's history, pins, presence and
     * loading flags into channel B — which is exactly the contamination seen on
     * fast switches and slow networks.
     */
    @Volatile private var switchGeneration = 0

    /**
     * False once the user has moved on. Any caller that suspended must check
     * this before writing to shared state, and discard its result if it fails.
     */
    private fun stillCurrent(generation: Int) = switchGeneration == generation

    /**
     * The in-flight channel-open coroutine, cancelled when the next open
     * starts. This is an optimisation, not the correctness boundary: the broad
     * `catch (e: Exception)` blocks throughout this class swallow
     * `CancellationException`, so a cancelled open can still run to completion.
     * [stillCurrent] is what actually keeps its writes out.
     */
    private var openJob: Job? = null

    /**
     * Serialises channel transitions. Only the teardown/subscribe half is held
     * under it — see [prepareChannel]; the history loads run outside so a slow
     * resend never blocks the next switch.
     */
    private val channelSwitchMutex = Mutex()
    /** targetId -> (override payload, publisher) for targets not loaded yet. */
    private val pendingOverrides = LinkedHashMap<String, Pair<JSONObject, String?>>()

    /** Ids the author deleted — see [applyOverride]. Reset per channel. */
    private val deletedIds = mutableSetOf<String>()

    /**
     * Who is typing, as an address plus whatever nickname rode along with the
     * signal. Deliberately NOT a pre-rendered label: the display name has to be
     * resolvable through ENS, and only the address can be looked up.
     */
    data class TypingPeer(val address: String, val nickname: String?)

    /**
     * Everyone typing right now, oldest signal first. A busy channel can have
     * several people typing at once, and a single slot meant the last signal
     * erased whoever was already there.
     */
    private val _typingFrom = MutableStateFlow<List<TypingPeer>>(emptyList())
    val typingFrom: StateFlow<List<TypingPeer>> = _typingFrom.asStateFlow()

    /** address -> when their last signal arrived. */
    private val typingSeen = LinkedHashMap<String, Long>()
    private val typingNames = HashMap<String, String?>()

    /** Shared by the DM and channel paths — both also warm the ENS name. */
    private fun markTyping(address: String, nickname: String?) {
        ensureEns(address)
        synchronized(typingSeen) {
            typingSeen[address] = System.currentTimeMillis()
            typingNames[address] = nickname
            publishTyping()
        }
        // One sweeper for everyone: each pass drops whoever went quiet and
        // re-arms only while someone is still typing.
        typingClearJob?.cancel()
        typingClearJob = scope.launch {
            while (true) {
                delay(1000)
                val remaining = synchronized(typingSeen) {
                    val cutoff = System.currentTimeMillis() - TYPING_TTL_MS
                    typingSeen.entries.removeAll { it.value < cutoff }
                    publishTyping()
                    typingSeen.size
                }
                if (remaining == 0) break
            }
        }
    }

    /** Caller holds the [typingSeen] lock. */
    private fun publishTyping() {
        _typingFrom.value = typingSeen.keys.map { TypingPeer(it, typingNames[it]) }
    }

    private fun clearTyping() {
        typingClearJob?.cancel()
        synchronized(typingSeen) {
            typingSeen.clear()
            typingNames.clear()
        }
        _typingFrom.value = emptyList()
    }

    // presence: senderId -> lastActive
    private val online = HashMap<String, Long>()
    private val onlineNames = HashMap<String, String?>()

    /** Who is online, for the web's online-users list (not just the count). */
    data class OnlineUser(val address: String, val nickname: String?)
    private val _onlineUsers = MutableStateFlow<List<OnlineUser>>(emptyList())
    val onlineUsers: StateFlow<List<OnlineUser>> = _onlineUsers.asStateFlow()
    private var presenceJob: Job? = null
    private var typingClearJob: Job? = null
    private var adminPollJob: Job? = null

    /** My own DM ephemeral inbox (`me/Pombo-DM-2`), where a peer's encrypted
     *  typing/presence lands. Set while a DM is open; null otherwise. */
    @Volatile private var myDmEphemeralId: String? = null

    /**
     * Resolved ENS names by lowercase address, published so every surface that
     * shows an address (pins, channel list, contacts, profile) prefers the ENS
     * name. Persistence, TTLs and in-flight dedup live in [EnsStore].
     */
    val ensNames: StateFlow<Map<String, String>> = ensStore.resolved

    /** Requests an ENS lookup for an address seen outside the message list. */
    fun ensureEns(address: String?) {
        if (address.isNullOrEmpty()) return
        if (!Regex("^0x[a-fA-F0-9]{40}$").matches(address)) return
        resolveEnsFor(address)
    }

    /**
     * Resolves the sender's ENS name + avatar and patches the rendered
     * messages. Name and avatar are cached separately (like identity.js), so a
     * failed avatar lookup doesn't get pinned by a successful name lookup.
     */
    private fun resolveEnsFor(address: String) {
        val key = address.lowercase()
        scope.launch {
            val name = ensStore.name(address) {
                try {
                    bridge.call("resolveEns", JSONObject().put("address", address), 20_000)
                        .optStringOrNull("name")
                } catch (e: Exception) {
                    Log.w("PomboEns", "resolveEns bridge call failed for $address: ${e.message}")
                    null
                }
            }
            Log.d("PomboEns", "name ${address.take(10)}… -> $name")
            if (name == null) return@launch

            // First-name rule for DM rooms (ENS > nick > raw address): when
            // the ENS only resolves after the room was created with the
            // address fallback, upgrade it. A name the user or the sender's
            // nick already gave the room is left alone.
            val shortForm = address.take(6) + "…" + address.takeLast(4)
            _channels.value.find {
                it.type == "dm" &&
                    it.peerAddress?.equals(address, ignoreCase = true) == true &&
                    it.name.equals(shortForm, ignoreCase = true)
            }?.let { renameDmForContact(address, name) }

            // Raise the trust level too, not just the name. applyTrustLevel
            // runs once at verification and reads the ENS cache; when the name
            // only resolves afterwards (the usual order in a DM) the message
            // kept trustLevel 0 forever, so it showed the ENS name with the
            // plain "valid signature" tick instead of the ENS badge. 2 is
            // "trusted contact", which outranks ENS and must not be lowered.
            _messages.value = _messages.value.map {
                if (it.sender.equals(address, ignoreCase = true))
                    it.copy(ensName = name, trustLevel = if (it.trustLevel == 2) 2 else 1)
                else it
            }

            // An ENS name may carry an avatar record, which replaces the
            // generated one (web: getCachedENSAvatar in the renderer).
            val avatar = ensStore.avatar(address) {
                try {
                    bridge.call("resolveEnsAvatar", JSONObject().put("name", name), 20_000)
                        .optStringOrNull("url")
                } catch (e: Exception) {
                    Log.w("PomboEns", "resolveEnsAvatar bridge call failed for $name: ${e.message}")
                    null
                }
            }
            Log.d("PomboEns", "avatar $name -> $avatar")
            if (avatar == null) return@launch

            _messages.value = _messages.value.map {
                if (it.sender.equals(address, ignoreCase = true)) it.copy(ensAvatar = avatar) else it
            }
        }
    }

    // DM: my own inbox stream id (once set up) + cache of peer pubkeys.
    @Volatile private var myInboxId: String? = null

    /** Web: settings toggle "Channel Invites" — when off, P3 is not subscribed. */
    @Volatile var inviteNotificationsEnabled: Boolean = true

    /**
     * Web SettingsUI.handleNotificationsToggle: flips the flag and, if the
     * inbox is up, (un)subscribes the notification partition live. When the
     * bridge is down the flag alone is enough — subscribeMyInbox re-reads it
     * on the next connect.
     */
    fun setInviteNotifications(on: Boolean) {
        if (inviteNotificationsEnabled == on) return
        inviteNotificationsEnabled = on
        val inbox = myInboxId ?: return
        scope.launch {
            if (on) {
                subscribeQuiet(inbox, StreamConstants.P_NOTIFICATIONS)
                // Backfill what arrived while muted — divergence from the web,
                // which drops those invites forever.
                catchUpInvites(inbox)
            } else unsubscribeQuiet(inbox, StreamConstants.P_NOTIFICATIONS)
        }
    }
    private val peerPubKeys = HashMap<String, String>()

    // Chunked-image reassembly: imageId -> pending state (manifest + chunks).
    private class PendingImage(var messageId: String) {
        /** Owning stream, so an assembled image can be ledgered like the web does. */
        var streamId: String = ""
        var chunkCount = 0
        var chunkHashes: List<String> = emptyList()
        var assembledSha256 = ""
        var finalSizeBytes = 0
        var finalMime = "image/jpeg"
        var haveManifest = false
        var lastSeen = System.currentTimeMillis()
        val chunks = HashMap<Int, ByteArray>()
        // Targeted recovery bookkeeping (web imageRecovery config).
        var manifestTs = 0L
        var recoveryAttempts = 0
        var recoveryScheduled = false
    }
    private val pendingImages = HashMap<String, PendingImage>()

    // ==================== channel image (ADMIN -3 / P1) ====================

    /** Every known channel image, keyed by admin stream id (shared cache). */
    val channelImages: StateFlow<Map<String, ByteArray>> = imageStore.images

    /** Decoded channel image for the open channel, or null when unset. */
    private val _channelImage = MutableStateFlow<ByteArray?>(null)
    val channelImage: StateFlow<ByteArray?> = _channelImage.asStateFlow()
    @Volatile private var channelImageRev = 0

    /**
     * Fetches a channel image for any channel (not just the open one), used by
     * the list and Explore. Cache-first with a background refresh, one network
     * round-trip per channel thanks to the store's inflight dedup.
     */
    fun ensureChannelImage(adminStreamId: String, password: String? = null) {
        if (adminStreamId.isEmpty()) return
        scope.launch {
            imageStore.dedup(adminStreamId) { fetchChannelImage(adminStreamId, password) }
        }
    }

    /**
     * Resends the latest CHANNEL_IMAGE payload and validates type + owner
     * authority (the admin stream is owned by the address that prefixes it).
     * Returns the parsed payload, or null when absent/invalid.
     */
    private suspend fun resendImagePayload(
        adminStreamId: String,
        password: String?,
        timeoutMs: Long = 30_000
    ): JSONObject? {
        bridge.awaitConnected()
        val gatedChannel = channelByStream(adminStreamId)?.takeIf { it.type == "gated" }
        // recoverSigner unconditionally: an Explore fetch of a visible gated
        // storefront has no channel object, yet its authority check below
        // still needs the envelope signer.
        val res = bridge.call("resend", JSONObject()
            .put("streamId", adminStreamId)
            .put("partition", StreamConstants.ADMIN_CHANNEL_IMAGE)
            .put("last", 1)
            .put("recoverSigner", true), timeoutMs)
        val arr = res.optJSONArray("messages") ?: return null
        if (arr.length() == 0) return null
        val entry = arr.getJSONObject(arr.length() - 1)
        val meta = entry.optJSONObject("meta") ?: JSONObject()
        val contentAny = entry.opt("content")
        var data: JSONObject = when (contentAny) {
            is JSONObject -> contentAny
            is String -> {
                val pwd = password ?: return null
                try { JSONObject(PomboCrypto.decryptString(contentAny, pwd)) } catch (e: Exception) { return null }
            }
            else -> return null
        }
        // Native/gated: CHANNEL_IMAGE arrives as an epoch envelope (N-A)
        if (com.pombo.android.core.EpochKeyCrypto.isEpochEnvelope(data)) {
            val messageStreamId = adminStreamId.replace(Regex("-3$"), StreamConstants.SUFFIX_MESSAGE)
            val keysId = StreamConstants.deriveKeysId(messageStreamId)
            // List/Explore fetch channels that were never OPENED this session
            // — the epoch state only loads on open, so without this the image
            // stays undecryptable everywhere but inside the channel.
            epochKeys.loadPersistedState(messageStreamId)
            data = epochKeys.tryDecrypt(messageStreamId, keysId, data) ?: return null
        }
        if (data.optString("type") != "CHANNEL_IMAGE") return null
        if (gatedChannel != null) {
            // Gated: the transport publisher is the CLONE for everyone —
            // authority is the recovered envelope signer, and gatedAuthor
            // already enforces signer == namespace admin on -3 (D10c: never
            // fall back to the transport publisher).
            gatedAuthor(gatedChannel, adminStreamId, meta) ?: return null
        } else {
            // Unknown channels (Explore) may be gated storefronts: the clone
            // publishes for every member, so a non-owner transport publisher
            // is only acceptable when the envelope SIGNER is the owner —
            // otherwise any member could plant an image on the card.
            val senderId = meta.optString("publisherId").lowercase()
            val owner = adminStreamId.substringBefore('/').lowercase()
            if (senderId.isNotEmpty() && senderId != owner
                && meta.optString("signer").lowercase() != owner) return null
        }
        return data
    }

    /** Returns the decoded bytes and caches them; null when unset/invalid. */
    private suspend fun fetchChannelImage(adminStreamId: String, password: String?): ByteArray? {
        return try {
            val data = resendImagePayload(adminStreamId, password) ?: return null

            val hash = data.optString("hash")
            // Unchanged payload: keep what we have and skip the decode.
            if (hash.isNotEmpty() && imageStore.isFresh(adminStreamId, hash)) {
                return imageStore.cached(adminStreamId)
            }
            // Stale guard: a storage read can lag a just-published image; an
            // older payload must never overwrite the newer entry we hold.
            val ts = data.optLong("ts", 0L)
            if (ts in 1 until imageStore.cachedTs(adminStreamId)) {
                return imageStore.cached(adminStreamId)
            }
            val base64 = data.optString("data").substringAfter(',', "")
            if (base64.isEmpty()) return null
            val actual = java.security.MessageDigest.getInstance("SHA-256")
                .digest(base64.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            if (hash.isNotEmpty() && hash != actual) return null
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            if (bytes.size > IMAGE_MAX_ASSEMBLED_BYTES) return null
            imageStore.put(adminStreamId, hash.ifEmpty { actual }, bytes, ts)
            bytes
        } catch (e: Exception) { null }
    }

    /**
     * Channel image for the open channel: paint whatever is cached first, then
     * refresh in the background (web ChannelImageManager stale-while-revalidate).
     */
    private fun loadChannelImage(channel: Channel) {
        imageStore.cached(channel.adminStreamId)?.let { _channelImage.value = it }
        scope.launch {
            val fresh = imageStore.dedup(channel.adminStreamId) {
                fetchChannelImage(channel.adminStreamId, channel.password)
            }
            if (fresh != null && _current.value?.adminStreamId == channel.adminStreamId) {
                _channelImage.value = fresh
            }
        }
    }

    /**
     * Publishes a new channel image (owner only). [bytes] is the finished
     * 512² crop from the crop dialog (web renderSquareCrop output).
     *
     * Returns true when the storage node confirmed the new payload, false when
     * the publish went through but confirmation never arrived in time — the
     * web's "Published — propagating…" outcome, NOT a failure. A genuinely
     * failed publish throws.
     */
    suspend fun publishChannelImage(bytes: ByteArray, mime: String): Boolean {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can change the image")
        // Base64 + SHA-256 over a 512² image is still main-thread hostile.
        val base64 = withContext(Dispatchers.Default) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
        val hash = withContext(Dispatchers.Default) {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(base64.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        val ts = System.currentTimeMillis()
        val payload = JSONObject()
            .put("type", "CHANNEL_IMAGE")
            .put("v", 1)
            .put("rev", channelImageRev + 1)
            .put("ts", ts)
            .put("createdBy", myAddress())
            .put("encrypted", false)
            .put("mime", mime)
            .put("hash", hash)
            .put("data", "data:$mime;base64,$base64")
        if (isEpochChannel(channel)) {
            // Epoch envelope, same reason as ADMIN_STATE: the SDK's group-key
            // AES on -3 is unreadable cross-device on native channels.
            // Visible channels are STOREFRONTS instead: the image is the
            // marketing and publishes in the CLEAR so non-members (Explore)
            // can render it — only the transport differs.
            val content = if (channel.exposure == "visible") payload
            else epochKeys.encryptCurrent(channel.messageStreamId, payload)
                ?: throw IllegalStateException("No epoch key — cannot publish the channel image yet")
            val args = JSONObject()
                .put("streamId", channel.adminStreamId)
                .put("partition", StreamConstants.ADMIN_CHANNEL_IMAGE)
                .put("content", content)
            // Gated: the clone holds the only grant on -3 — the account key
            // signs, the clone is the on-wire publisher (ERC-1271); receivers
            // enforce admin authority on the recovered signer.
            val method = if (channel.type == "gated") {
                val gate = channel.gateAddress ?: throw IllegalStateException(
                    "Gate address unknown for ${channel.messageStreamId} — cannot publish the channel image")
                args.put("gateAddress", gate)
                "publishAsGate"
            } else "publishAsAccount"
            bridge.call(method, args, 60_000)
        } else {
            bridge.call("publish", JSONObject()
                .put("streamId", channel.adminStreamId)
                .put("partition", StreamConstants.ADMIN_CHANNEL_IMAGE)
                .put("content", payload), 60_000)
        }
        channelImageRev += 1
        _channelImage.value = bytes
        // Publish into the shared cache so list/Explore update without a refetch.
        // Recording ts also arms the stale guard in fetchChannelImage, so a
        // background refresh racing this publish cannot regress the cache.
        imageStore.put(channel.adminStreamId, hash, bytes, ts)

        // Verify it actually persisted, like the web's "Verifying…" step: an
        // optimistic local cache is not proof the storage node kept it. The
        // node takes a few seconds to index a publish, so poll by HASH ONLY —
        // going through fetchChannelImage here used to write the still-old
        // payload back into the store and revert the image everywhere.
        repeat(5) {
            kotlinx.coroutines.delay(3_000)
            val remote = try {
                resendImagePayload(channel.adminStreamId, channel.password, 10_000)?.optString("hash")
            } catch (e: Exception) { null }
            if (remote == hash) return true
        }
        return false
    }

    /** Whether this channel's name/description live in PUBLIC on-chain
     *  metadata — visible channels of any access type. Hidden channels keep
     *  their name off-chain: writing it into the public stream registry
     *  would leak a private channel's name (N-D parity with the web). */
    fun hasPublicMetadata(channel: Channel): Boolean = channel.exposure == "visible"

    /**
     * Renames / re-describes the channel (web: streamr.js updateStreamMetadata).
     * Owner-only. Visible channels: a single on-chain transaction (gas).
     * Hidden channels: LOCAL rename only, propagated by sync like a DM's.
     */
    suspend fun updateChannelMetadata(name: String?, description: String?) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can edit the channel")
        if (hasPublicMetadata(channel)) {
            val args = JSONObject().put("streamId", channel.messageStreamId)
            name?.trim()?.takeIf { it.isNotEmpty() }?.let { args.put("name", it) }
            description?.let { args.put("description", it.trim()) }
            bridge.call("updateStreamMetadata", args, 120_000)
        }

        val updated = channel.copy(
            name = name?.trim()?.ifEmpty { null } ?: channel.name,
            description = description?.trim() ?: channel.description,
            // Stamped so the Graph refresh below does not revert this edit while
            // the subgraph is still catching up.
            metaUpdatedAt = System.currentTimeMillis()
        )
        _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
        store.save(_channels.value)
        _current.value = updated
    }

    /**
     * Pulls channel names/descriptions from on-chain metadata (web:
     * refreshChannelMetadataFromGraph). Two things depend on it: admins renaming
     * a channel propagate to everyone who already joined, and a channel joined
     * by raw stream ID gets its real name instead of the ID segment the join
     * falls back to.
     */
    suspend fun refreshChannelMetadataFromGraph(): Boolean {
        var changed = false
        val updates = mutableMapOf<String, Channel>()
        for (channel in _channels.value) {
            if (channel.type == "dm") continue
            val info = try {
                com.pombo.android.core.GraphApi.getChannelInfo(channel.messageStreamId)
            } catch (e: Exception) {
                null
            } ?: continue
            // Indexing lag: never overwrite a local admin edit with older data.
            if (channel.metaUpdatedAt != null && info.updatedAt <= channel.metaUpdatedAt) continue

            var next = channel
            if (!info.name.isNullOrEmpty() && info.name != channel.name) next = next.copy(name = info.name)
            // Hidden channels keep their description off-chain, so only trust
            // the on-chain one for visible channels.
            if (info.exposure == "visible" && info.description != channel.description) {
                next = next.copy(description = info.description)
            }
            if (next !== channel) { updates[channel.messageStreamId] = next; changed = true }
        }
        if (changed) {
            _channels.value = _channels.value.map { updates[it.messageStreamId] ?: it }
            store.save(_channels.value)
            // Keep the open channel's header in sync with the list.
            _current.value?.let { cur -> updates[cur.messageStreamId]?.let { _current.value = it } }
        }
        return changed
    }

    /**
     * Members of a Closed channel, read from The Graph's permission list
     * (web: graphAPI.getStreamMembers). Owner first, then the rest.
     *
     * Gated (N-D): the Graph's grantee list is exactly the clone — membership
     * lives on the GATE. Candidates: the local cache (owner-minted members)
     * plus the KEY_REQUEST authors seen on -4 (join()/pay() members never
     * pass through the owner, but every reader must request keys). Their
     * CURRENT state comes from the contract; `access` is the mode-aware
     * membership signal (allowlist only means Closed).
     */
    suspend fun channelMembers(): List<String> {
        val channel = _current.value ?: return emptyList()
        if (channel.type == "gated") {
            val gate = channel.gateAddress ?: return channel.members
            val candidates = (channel.members + epochKeys.seenRequesters(channel.messageStreamId))
                .map { it.lowercase() }.distinct()
            return try {
                val res = bridge.call("gateMembers", JSONObject()
                    .put("gate", gate)
                    .put("candidates", JSONArray(candidates)), 60_000)
                val arr = res.optJSONArray("members") ?: return channel.members
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    if (!m.optBoolean("isOwner") && !m.optBoolean("moderator")
                        && !m.optBoolean("access")) continue   // banned/ex-members
                    m.optString("address").ifEmpty { null }?.let { out.add(it) }
                }
                out
            } catch (e: Exception) {
                Log.w(TAG, "gateMembers failed, using local cache: ${e.message}")
                channel.members
            }
        }
        val owner = channelOwner(channel)
        val members = com.pombo.android.core.GraphApi.streamMembers(channel.messageStreamId)
        return (listOfNotNull(owner) + members.filter { !it.equals(owner, ignoreCase = true) }).distinct()
    }

    /**
     * Channel Details access line for the CURRENT channel's gate (N-D):
     * NONE 'Verified Membership' · TOKEN 'Gated · ≥ N SYM' · NFT
     * 'Gated · SYM NFT' · PAID 'N SYM / D days'. Null = not gated or
     * unreadable — the caller keeps its default label.
     */
    suspend fun gateAccessLabel(): String? {
        val channel = _current.value?.takeIf { it.type == "gated" } ?: return null
        val gate = channel.gateAddress ?: return null
        return try {
            val info = bridge.call("gateInfo", JSONObject().put("gate", gate))
            val mode = info.optInt("mode", GATE_MODE_NONE)
            if (mode == GATE_MODE_NONE) return "Verified Membership"
            val meta = bridge.call("gateTokenMeta", JSONObject().put("token", info.optString("token")))
            val symbol = meta.optString("symbol")
            val decimals = if (meta.isNull("decimals")) 0 else meta.optInt("decimals")
            fun fmt(raw: String) = java.math.BigDecimal(raw)
                .movePointLeft(decimals).stripTrailingZeros().toPlainString()
            when (mode) {
                GATE_MODE_TOKEN -> "Gated · Hold ≥ ${fmt(info.optString("minBalance", "0"))} $symbol"
                GATE_MODE_NFT -> "Gated · Hold $symbol NFT"
                GATE_MODE_PAID -> {
                    val days = (info.optString("duration", "0").toLongOrNull() ?: 0L) / 86_400.0
                    val d = if (days == days.toLong().toDouble()) days.toLong().toString()
                        else "%.1f".format(days)
                    // WPOL-priced gates display POL: gatePay auto-wraps, so
                    // plain POL is literally what the subscriber spends.
                    val paySymbol = if (info.optString("token").lowercase() == WRAPPED_NATIVE) "POL" else symbol
                    "Paid · ${fmt(info.optString("price", "0"))} $paySymbol / $d ${if (d == "1") "day" else "days"}"
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    /**
     * Explore card access info (N-D), as the 3-line pricing anatomy the
     * cards render: VERB (Subscribe = recurring payment, Hold = mere
     * possession — the semantic split users must get) / VALUE / QUALIFIER
     * ('in your wallet' says "you pay nothing"). POL for WPOL-priced
     * gates; NONE never lists (null parts); null result = unreadable.
     */
    data class GateCardInfo(
        val mode: Int, val verb: String?, val value: String?, val qualifier: String?
    )

    suspend fun gateCardInfo(gateAddress: String): GateCardInfo? = try {
        val info = bridge.call("gateInfo", JSONObject().put("gate", gateAddress))
        val mode = info.optInt("mode", GATE_MODE_NONE)
        if (mode == GATE_MODE_NONE) GateCardInfo(mode, null, null, null)
        else {
            val meta = bridge.call("gateTokenMeta", JSONObject().put("token", info.optString("token")))
            val symbol = meta.optString("symbol")
            val decimals = if (meta.isNull("decimals")) 0 else meta.optInt("decimals")
            fun fmt(raw: String) = java.math.BigDecimal(raw)
                .movePointLeft(decimals).stripTrailingZeros().toPlainString()
            when (mode) {
                GATE_MODE_TOKEN -> GateCardInfo(
                    mode, "Hold", "${fmt(info.optString("minBalance", "0"))} $symbol", "in your wallet")
                GATE_MODE_NFT -> GateCardInfo(mode, "Hold", "$symbol NFT", "in your wallet")
                GATE_MODE_PAID -> {
                    val days = (info.optString("duration", "0").toLongOrNull() ?: 0L) / 86_400.0
                    val d = if (days == days.toLong().toDouble()) days.toLong().toString()
                        else "%.1f".format(days)
                    val sym = if (info.optString("token").lowercase() == WRAPPED_NATIVE) "POL" else symbol
                    // "per" spells out the recurrence under SUBSCRIBE
                    GateCardInfo(mode, "Subscribe",
                        "${fmt(info.optString("price", "0"))} $sym",
                        if (d == "1") "per day" else "per $d days")
                }
                else -> GateCardInfo(mode, null, null, null)
            }
        }
    } catch (e: Exception) { null }

    /** Gate mode of the CURRENT channel; null = not gated or unreadable. */
    suspend fun currentGateMode(): Int? {
        val channel = _current.value?.takeIf { it.type == "gated" } ?: return null
        val gate = channel.gateAddress ?: return null
        return try {
            bridge.call("gateInfo", JSONObject().put("gate", gate))
                .optInt("mode", -1).takeIf { it >= 0 }
        } catch (e: Exception) { null }
    }

    /** Everything the gate entry screen renders, in one fetch. */
    data class GateEntryInfo(
        val gateAddress: String, val mode: Int, val token: String,
        val minBalance: String, val price: String, val durationSeconds: Long,
        val tokenSymbol: String,
        /** null = ERC-721 collection (no decimals getter) — whole units. */
        val tokenDecimals: Int?,
        /** TOKEN/NFT: the user's raw-unit balance of the gate asset. */
        val balance: String,
        /** PAID: subscription end, unix seconds (0 = never paid). */
        val paidUntil: Long
    )

    suspend fun gateEntryInfo(gateAddress: String): GateEntryInfo {
        val info = bridge.call("gateInfo", JSONObject().put("gate", gateAddress))
        val mode = info.optInt("mode", GATE_MODE_NONE)
        val token = info.optString("token")
        val me = myAddress() ?: throw IllegalStateException("No identity")
        var symbol = ""
        var decimals: Int? = null
        var balance = "0"
        var paidUntil = 0L
        if (mode != GATE_MODE_NONE && token.isNotEmpty()) {
            val meta = bridge.call("gateTokenMeta", JSONObject().put("token", token))
            symbol = meta.optString("symbol")
            decimals = if (meta.isNull("decimals")) null else meta.optInt("decimals")
            if (mode == GATE_MODE_PAID) {
                paidUntil = bridge.call("gatePaidUntil", JSONObject()
                    .put("gate", gateAddress).put("user", me))
                    .optString("paidUntil", "0").toLongOrNull() ?: 0L
            } else {
                balance = bridge.call("gateTokenBalance", JSONObject()
                    .put("token", token).put("user", me)).optString("balance", "0")
            }
        }
        return GateEntryInfo(
            gateAddress.lowercase(), mode, token,
            info.optString("minBalance", "0"), info.optString("price", "0"),
            info.optString("duration", "0").toLongOrNull() ?: 0L,
            symbol, decimals, balance, paidUntil)
    }

    /** Creation-form helper: token metadata (also probes the contract). */
    suspend fun gateTokenMeta(token: String): Pair<String, Int?> {
        val meta = bridge.call("gateTokenMeta", JSONObject().put("token", token))
        return Pair(meta.optString("symbol"),
            if (meta.isNull("decimals")) null else meta.optInt("decimals"))
    }

    /**
     * Creation-form probe: an address without a working balanceOf would mint
     * a gate that fails checkAccess for everyone, forever. Throws on failure.
     */
    suspend fun gateTokenBalance(token: String, user: String? = null): String {
        val who = user ?: myAddress() ?: throw IllegalStateException("No identity")
        return bridge.call("gateTokenBalance", JSONObject()
            .put("token", token).put("user", who)).optString("balance", "0")
    }

    /** Drop the bridge's cached (fail-closed) access verdicts for a gate. */
    suspend fun gateInvalidateAccess(gateAddress: String) {
        runCatching {
            bridge.call("gateInvalidateAccess", JSONObject().put("gate", gateAddress))
        }
    }

    /**
     * PAID gates: pay one subscription period (wrap/approve/pay inside the
     * bridge call — the deny cache for the payer clears with the tx).
     */
    suspend fun gatePay(gateAddress: String) {
        bridge.call("gatePay", JSONObject().put("gate", gateAddress), 600_000)
    }

    /** TOKEN/NFT gates: opt in to sticky membership (everMember). */
    suspend fun gateJoin(gateAddress: String) {
        bridge.call("gateJoin", JSONObject().put("gate", gateAddress), 180_000)
    }

    /**
     * Resolves a member input to a 0x address: passes a raw address through,
     * otherwise treats it as an ENS name and resolves it forward (name→address).
     * Lets the Members panel accept "pombo.eth" as well as a raw address.
     */
    suspend fun resolveMemberInput(input: String): String? {
        val t = input.trim()
        if (Regex("^0x[a-fA-F0-9]{40}$").matches(t)) return t
        if (!t.contains('.')) return null
        return try {
            bridge.call("resolveEnsName", JSONObject().put("name", t), 20_000)
                .optString("address").takeIf { Regex("^0x[a-fA-F0-9]{40}$").matches(it) }
        } catch (e: Exception) { null }
    }

    /**
     * Grants a member access on all three streams — sequential, because
     * parallel on-chain writes from one account collide on the nonce.
     * Admin stream is subscribe-only: members read moderation, owner writes it.
     */
    suspend fun addMember(address: String) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (channel.type != "gated" && !amOwner(channel)) throw IllegalStateException("Only the channel admin can add members")
        // Accept an ENS name or a raw address.
        val addr = resolveMemberInput(address)
            ?: throw IllegalStateException("Invalid address or ENS name")
        if (channel.members.any { it.equals(addr, ignoreCase = true) }) {
            throw IllegalStateException("Address is already a member")
        }

        // Gated (N-C): membership is ONE gate transaction — allow() marks the
        // address allowlisted + everMember. No stream grants: access is proven
        // per-message via ERC-1271.
        if (channel.type == "gated") {
            val gate = channel.gateAddress
                ?: throw IllegalStateException("Gate address unknown (repair pending)")
            bridge.call("gateAllow", JSONObject().put("gate", gate).put("user", addr), 180_000)
            val updated = channel.copy(members = channel.members + addr)
            _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
            store.save(_channels.value)
            _current.value = updated
            return
        }

        // `-1`, `-2` and `-4`: subscribe + publish (messages, presence, and
        // the keys stream needs publish so the member can answer KEY_REQUESTs).
        // `-3` (moderation): subscribe ONLY — a normal member reads the admin
        // state but never writes it; publishing ADMIN_STATE is the owner's alone
        // (web addMember does the same).
        val rw = JSONArray().put(
            JSONObject().put("userId", addr).put("permissions", JSONArray(listOf("subscribe", "publish")))
        )
        val readOnly = JSONArray().put(
            JSONObject().put("userId", addr).put("permissions", JSONArray(listOf("subscribe")))
        )
        setPermissionsRetry(channel.messageStreamId, rw)
        setPermissionsRetry(channel.ephemeralStreamId, rw)
        if (channel.adminStreamId.isNotEmpty()) setPermissionsRetry(channel.adminStreamId, readOnly)
        val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        setPermissionsRetry(keysId, rw)

        com.pombo.android.core.GraphApi.clearCache()
        val updated = channel.copy(members = channel.members + addr)
        _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
        store.save(_channels.value)
        _current.value = updated
    }

    /** Revokes all permissions (web: empty permission array = revoke). */
    suspend fun removeMember(address: String) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (channel.type != "gated" && !amOwner(channel)) throw IllegalStateException("Only the channel admin can remove members")
        val addr = address.trim()
        // The creator owns the streams on-chain; removing them is meaningless
        // and would only strip their explicit grants.
        if (channelOwner(channel)?.equals(addr, ignoreCase = true) == true) {
            throw IllegalStateException("Cannot remove the channel creator")
        }

        // Gated (N-C): removing IS the ban — one gate transaction cuts
        // checkAccess (no new epoch keys), the rotation below cuts reads from
        // here on, and the contract's sticky isValidSignature keeps their
        // history readable for everyone else (Q10). `erased` is a separate,
        // explicit owner choice — never set here.
        if (channel.type == "gated") {
            val gate = channel.gateAddress
                ?: throw IllegalStateException("Gate address unknown (repair pending)")
            bridge.call("gateBan", JSONObject()
                .put("gate", gate).put("user", addr).put("erase", false), 180_000)
            val keysIdGated = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
            val updated = channel.copy(members = channel.members.filterNot { it.equals(addr, ignoreCase = true) })
            _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
            store.save(_channels.value)
            _current.value = updated
            try {
                epochKeys.rotateEpoch(channel.messageStreamId, keysIdGated)
            } catch (e: Exception) {
                Log.w(TAG, "Epoch rotation after gate ban FAILED — banned member can still read new messages until the next rotation: ${e.message}")
            }
            return
        }

        val revoke = JSONArray().put(
            JSONObject().put("userId", addr).put("permissions", JSONArray())
        )
        setPermissionsRetry(channel.messageStreamId, revoke)
        setPermissionsRetry(channel.ephemeralStreamId, revoke)
        if (channel.adminStreamId.isNotEmpty()) setPermissionsRetry(channel.adminStreamId, revoke)
        val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        setPermissionsRetry(keysId, revoke)

        com.pombo.android.core.GraphApi.clearCache()
        val updated = channel.copy(members = channel.members.filterNot { it.equals(addr, ignoreCase = true) })
        _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
        store.save(_channels.value)
        _current.value = updated

        // Rotate the epoch so the removed member cannot read anything published
        // from here on — they keep what they already read; the rotation protects
        // the future, not the past. Failure is surfaced, not fatal.
        try {
            epochKeys.rotateEpoch(channel.messageStreamId, keysId)
        } catch (e: Exception) {
            Log.w(TAG, "Epoch rotation after member removal FAILED — removed member can still read new messages until the next rotation: ${e.message}")
        }
    }

    /**
     * The on-chain permission matrix for the channel's message stream (web:
     * graphAPI.getStreamPermissions), for the Members panel's Stream Permissions
     * list. Owner-only surface, so no permission gate here — the caller shows it.
     */
    suspend fun streamPermissions(): List<com.pombo.android.core.GraphApi.StreamPermission> {
        val channel = _current.value ?: return emptyList()
        return com.pombo.android.core.GraphApi.getStreamPermissions(channel.messageStreamId)
    }

    /**
     * Grants or revokes admin for a member (Members panel "Admin" toggle).
     *
     * ADMIN = TRUSTED CO-OWNER. Streamr's GRANT permission is all-or-nothing:
     * anyone with `canGrant` can set ANY of the five flags (edit, delete,
     * publish, subscribe, grant) for ANY user, including themselves — there is
     * no "manage read/write only" permission on-chain. So an admin can already
     * escalate to full owner. Given that, we grant admin the full non-owner set
     * — subscribe + publish + grant on ALL THREE streams — and only withhold the
     * two flags that define ownership: EDIT and DELETE. Those stay the owner's,
     * which is the sole on-chain line left between admin and owner
     * (isOwner = canGrant && canEdit && canDelete).
     *
     * Revoking returns the member to the normal set: sub+pub on `-1`/`-2`, sub
     * on `-3` (they read moderation state but do not publish it).
     */
    suspend fun setMemberGrant(address: String, canGrant: Boolean) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can change permissions")
        val addr = address.trim()
        // Gated (N-C): "can add members" is the gate's moderator flag — one
        // owner transaction; the contract enforces the rest (mods manage
        // members, never erase history, never touch owner/other mods).
        if (channel.type == "gated") {
            val gate = channel.gateAddress
                ?: throw IllegalStateException("Gate address unknown (repair pending)")
            bridge.call("gateSetModerator", JSONObject()
                .put("gate", gate).put("user", addr).put("enabled", canGrant), 180_000)
            return
        }
        fun assign(perms: List<String>) = JSONArray().put(
            JSONObject().put("userId", addr).put("permissions", JSONArray(perms))
        )
        if (canGrant) {
            // Admin: subscribe + publish + grant on every stream. Never edit/delete.
            val all = listOf("subscribe", "publish", "grant")
            setPermissionsRetry(channel.messageStreamId, assign(all))
            setPermissionsRetry(channel.ephemeralStreamId, assign(all))
            if (channel.adminStreamId.isNotEmpty()) setPermissionsRetry(channel.adminStreamId, assign(all))
        } else {
            // Back to a normal member: sub+pub on -1/-2, sub only on -3.
            val rw = listOf("subscribe", "publish")
            setPermissionsRetry(channel.messageStreamId, assign(rw))
            setPermissionsRetry(channel.ephemeralStreamId, assign(rw))
            if (channel.adminStreamId.isNotEmpty()) setPermissionsRetry(channel.adminStreamId, assign(listOf("subscribe")))
        }
        com.pombo.android.core.GraphApi.clearCache()
    }

    /** Storage nodes actually assigned to the message stream. */
    suspend fun storageNodes(): List<String> = try {
        val channel = _current.value ?: return emptyList()
        val res = bridge.call("getStreamStorageInfo",
            JSONObject().put("streamId", channel.messageStreamId), 30_000)
        val arr = res.optJSONArray("nodes") ?: JSONArray()
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
    } catch (e: Exception) { emptyList() }

    /** The official Pombo storage cluster (web: STREAM_CONFIG.NODE_ADDRESS). */
    val pomboStorageNode: String get() = STORAGE_NODE

    /**
     * A storage node as the channel sees it. `onMessage`/`onAdmin` track the two
     * streams separately so a half-applied assignment shows as "partial" instead
     * of looking healthy (web: getChannelStorageInfo).
     */
    data class StorageNode(val address: String, val onMessage: Boolean, val onAdmin: Boolean) {
        val partial: Boolean get() = !(onMessage && onAdmin)
    }

    data class StorageInfo(
        val enabled: Boolean,
        val nodes: List<StorageNode>,
        /** Message-stream TTL; the admin stream tracks it but is not surfaced. */
        val storageDays: Int?
    )

    private suspend fun streamStorage(streamId: String): Pair<List<String>, Int?> = try {
        val res = bridge.call("getStreamStorageInfo", JSONObject().put("streamId", streamId), 30_000)
        val arr = res.optJSONArray("nodes") ?: JSONArray()
        val nodes = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        nodes to (if (res.isNull("storageDays")) null else res.optInt("storageDays").takeIf { it > 0 })
    } catch (e: Exception) { emptyList<String>() to null }

    suspend fun channelStorageInfo(): StorageInfo {
        val channel = _current.value ?: return StorageInfo(false, emptyList(), null)
        val (msgNodes, days) = streamStorage(channel.messageStreamId)
        val adminNodes = if (channel.adminStreamId.isNotEmpty()) {
            streamStorage(channel.adminStreamId).first
        } else emptyList()

        val byAddr = linkedMapOf<String, StorageNode>()
        msgNodes.forEach { byAddr[it.lowercase()] = StorageNode(it, onMessage = true, onAdmin = false) }
        adminNodes.forEach { n ->
            val k = n.lowercase()
            val existing = byAddr[k]
            byAddr[k] = existing?.copy(onAdmin = true) ?: StorageNode(n, onMessage = false, onAdmin = true)
        }
        val nodes = byAddr.values.toList()
        return StorageInfo(nodes.isNotEmpty(), nodes, days)
    }

    /**
     * Assigns a storage node to both -1 and -3. Sequential, not parallel: two
     * on-chain writes from one account race on the nonce and the second gets
     * REPLACEMENT_UNDERPRICED.
     */
    suspend fun addStorageNode(address: String, storageDays: Int? = null) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can change storage")
        val addr = address.trim()
        require(Regex("^0x[a-fA-F0-9]{40}$").matches(addr)) { "Invalid storage node address" }

        val args = { streamId: String ->
            JSONObject().put("streamId", streamId).put("nodeAddress", addr).apply {
                storageDays?.let { put("storageDays", it) }
            }
        }
        bridge.call("addToStorageNode", args(channel.messageStreamId), 180_000)
        if (channel.adminStreamId.isNotEmpty()) {
            bridge.call("addToStorageNode", args(channel.adminStreamId), 180_000)
        }
        markStorage(channel, enabled = true)
    }

    suspend fun removeStorageNode(address: String) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can change storage")
        val addr = address.trim()
        bridge.call("removeFromStorageNode",
            JSONObject().put("streamId", channel.messageStreamId).put("nodeAddress", addr), 180_000)
        if (channel.adminStreamId.isNotEmpty()) {
            bridge.call("removeFromStorageNode",
                JSONObject().put("streamId", channel.adminStreamId).put("nodeAddress", addr), 180_000)
        }
        markStorage(channel, enabled = channelStorageInfo().enabled)
    }

    /** Retention applies to both streams, same sequencing rationale as above. */
    suspend fun setStorageDays(days: Int) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can change storage")
        require(days >= 1) { "Retention must be a positive number of days" }
        bridge.call("setStorageDays",
            JSONObject().put("streamId", channel.messageStreamId).put("storageDays", days), 180_000)
        if (channel.adminStreamId.isNotEmpty()) {
            bridge.call("setStorageDays",
                JSONObject().put("streamId", channel.adminStreamId).put("storageDays", days), 180_000)
        }
        // Keep the local copy in sync — the TTL republish check on owner open
        // (docs/TTL_REPUBLISH_PLAN.md) compares artifact age against this.
        // Web channels.js setChannelStorageDays does the same.
        val updated = channel.copy(storageDays = days)
        _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
        store.save(_channels.value)
        if (_current.value?.messageStreamId == updated.messageStreamId) _current.value = updated
    }

    private fun markStorage(channel: Channel, enabled: Boolean) {
        if (channel.storageEnabled == enabled) return
        val updated = channel.copy(storageEnabled = enabled)
        _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
        store.save(_channels.value)
        if (_current.value?.messageStreamId == updated.messageStreamId) _current.value = updated
    }

    /** Drops partial assemblies whose chunks stopped arriving (web: TTL sweep). */
    private fun sweepStaleAssemblies() {
        val cutoff = System.currentTimeMillis() - ASSEMBLY_TTL_MS
        pendingImages.entries.removeAll { it.value.lastSeen < cutoff }
    }

    /**
     * Full streamr.js createStream flow: 3 streams serially (nonce),
     * permissions per type, storage on -1/-3, password challenge on -3/P2.
     */
    suspend fun createChannel(
        name: String,
        type: String,                    // 'public' | 'password' | 'native'
        password: String? = null,
        exposure: String = "hidden",
        readOnly: Boolean = false,
        description: String = "",
        language: String = "en",
        category: String = "general",
        /** Closed channels: addresses granted access on-chain at creation. */
        members: List<String> = emptyList(),
        /** Local-only grouping for closed channels ('personal' | 'community'). */
        classification: String? = null,
        /** 'streamr' (Pombo cluster) or 'custom'. */
        storageProvider: String = "streamr",
        /** Only meaningful when storageProvider == 'custom'. */
        customStorageAddress: String? = null,
        /** Retention in days (web slider: 1..365, default 180). */
        storageDays: Int = 180,
        /** Gate mode (N-D): NONE (Closed), TOKEN, NFT or PAID. */
        gateMode: Int = GATE_MODE_NONE,
        /** TOKEN/NFT/PAID: asset or payment token contract (lowercase 0x…). */
        gateToken: String? = null,
        /** TOKEN: raw-unit minimum balance (decimal string — uint256). */
        gateMinBalance: String? = null,
        /** PAID: raw-unit price (decimal string — uint256). */
        gatePrice: String? = null,
        /** PAID: subscription period in SECONDS. */
        gateDuration: Long? = null,
        /** Called once per on-chain step so the UI can drive the progress ring. */
        onProgress: () -> Unit = {}
    ): Channel {
        val addr = myAddress() ?: throw IllegalStateException("No identity")
        require(type != "password" || !password.isNullOrEmpty()) { "Password channel requires a password" }

        // Gated (N-C): the gate clone comes FIRST — its address goes into the
        // -1 metadata and receives every stream grant. One factory tx; the
        // creator becomes the gate owner and is everMember from block one.
        // N-D: the mode and its params come from the caller — the UI already
        // validated them against PomboGate.initialize's per-mode rules (a bad
        // combination reverts InvalidParams after the deploy gas was spent).
        val gateAddress: String? = if (type == "gated") {
            val createArgs = JSONObject().put("mode", gateMode)
            gateToken?.let { createArgs.put("token", it) }
            gateMinBalance?.let { createArgs.put("minBalance", it) }
            gatePrice?.let { createArgs.put("price", it) }
            gateDuration?.let { createArgs.put("duration", it) }
            val res = bridge.call("gateCreate", createArgs, 180_000)
            val gate = res.optString("gate").lowercase().ifEmpty { null }
                ?: throw IllegalStateException("gateCreate returned no clone address")
            Log.i(TAG, "Gate clone created: " + gate)
            onProgress()
            gate
        } else null

        val base = "${addr.lowercase()}/${PomboCrypto.randomHex(8)}"
        val messageStreamId = "$base${StreamConstants.SUFFIX_MESSAGE}"
        val ephemeralStreamId = "$base${StreamConstants.SUFFIX_EPHEMERAL}"
        val adminStreamId = "$base${StreamConstants.SUFFIX_ADMIN}"
        val keysStreamId = "$base${StreamConstants.SUFFIX_KEYS}"
        // Closed channels are never discoverable: exposure is forced to
        // 'hidden' — honouring a caller-supplied 'visible' would publish the
        // channel name and description in PLAINTEXT on-chain metadata for a
        // channel whose entire point is that membership is private. N-D:
        // token/NFT/paid gates are the opposite — storefronts — so only the
        // NONE mode keeps the clamp (mirrors web handleCreate).
        val effectiveExposure =
            if (type == "native" || (type == "gated" && gateMode == GATE_MODE_NONE)) "hidden"
            else exposure
        val visible = effectiveExposure == "visible"

        // Pombo metadata in the description (same short keys AND the same key
        // order as the web: a,v,n,t,e,r,d,l,c,ts — streamr.js:327-339).
        val msgMeta = JSONObject()
            .put("a", "pombo").put("v", "1")
            .put("n", if (visible) name else JSONObject.NULL)
            .put("t", type).put("e", effectiveExposure).put("r", readOnly)
        // Gated: the clone address — the system of record joins and repairs
        // read (web streamr.js metadata g field)
        gateAddress?.let { msgMeta.put("g", it) }
        if (visible) {
            msgMeta.put("d", description).put("l", language).put("c", category)
        }
        msgMeta.put("ts", System.currentTimeMillis())
        val ephMeta = JSONObject().put("a", "pombo").put("v", "1").put("ln", messageStreamId)
        val admMeta = JSONObject().put("a", "pombo").put("v", "1").put("ln", messageStreamId).put("k", "admin")
        val keysMeta = JSONObject().put("a", "pombo").put("v", "1").put("ln", messageStreamId).put("k", "keys")

        // Streams SERIALLY (parallel causes on-chain nonce conflicts) with retries
        createStreamRetry(messageStreamId, msgMeta.toString(), StreamConstants.MSG_PARTITIONS); onProgress()
        createStreamRetry(ephemeralStreamId, ephMeta.toString(), StreamConstants.EPH_PARTITIONS); onProgress()
        createStreamRetry(adminStreamId, admMeta.toString(), StreamConstants.ADMIN_PARTITIONS); onProgress()
        // Keys stream (-4): native only. Lives outside -3 on purpose — any
        // member must publish KEY_REQUEST/KEY_WRAP here, while -3 stays
        // owner-only publish (web streamr.js createStream, N-A).
        if (type == "native" || type == "gated") {
            createStreamRetry(keysStreamId, keysMeta.toString(), StreamConstants.KEYS_PARTITIONS); onProgress()
        }

        val publicRW = JSONArray().put(JSONObject().put("public", true).put("permissions", JSONArray(listOf("subscribe", "publish"))))
        val publicRead = JSONArray().put(JSONObject().put("public", true).put("permissions", JSONArray(listOf("subscribe"))))
        when (type) {
            "public", "password" -> {
                setPermissionsRetry(messageStreamId, if (readOnly) publicRead else publicRW); onProgress()
                setPermissionsRetry(ephemeralStreamId, publicRW); onProgress()   // presence needs public publish
                setPermissionsRetry(adminStreamId, publicRead); onProgress()     // publish is the owner\x27s by ownership
            }
            "native" -> {
                // Closed channel: membership is on-chain. Each address listed at
                // creation gets explicit subscribe+publish, and nothing is public.
                if (members.isNotEmpty()) {
                    val perms = JSONArray()
                    members.forEach { addr ->
                        perms.put(
                            JSONObject()
                                .put("userId", addr)
                                .put("permissions", JSONArray(listOf("subscribe", "publish")))
                        )
                    }
                    setPermissionsRetry(messageStreamId, perms)
                    setPermissionsRetry(ephemeralStreamId, perms)
                    setPermissionsRetry(adminStreamId, JSONArray().apply {
                        members.forEach { addr ->
                            put(
                                JSONObject()
                                    .put("userId", addr)
                                    .put("permissions", JSONArray(listOf("subscribe")))
                            )
                        }
                    })
                    // Keys stream: full member permissions — any member may
                    // answer a KEY_REQUEST with a KEY_WRAP (k-of-n)
                    setPermissionsRetry(keysStreamId, perms)
                }
                onProgress(); onProgress(); onProgress(); onProgress()
            }
            "gated" -> {
                // ONE grantee for every stream: the gate clone (N-C, Q7).
                // Members prove access through the contract (ERC-1271), so
                // membership changes are gate transactions, not stream txs.
                // -3 note: the clone necessarily holds publish there too —
                // owner-only authority moved to ingest (gatedAuthor).
                val clonePerms = JSONArray().put(JSONObject()
                    .put("userId", gateAddress)
                    .put("permissions", JSONArray(listOf("subscribe", "publish"))))
                // Visible gated channels are storefronts: -3 gains public
                // SUBSCRIBE so non-members (Explore) can read the channel
                // image. P0 (ADMIN_STATE) stays an epoch envelope — the
                // public only ever sees ciphertext. Hidden channels keep the
                // clone as the sole grantee.
                val adminPerms = if (visible) JSONArray().apply {
                    put(clonePerms.getJSONObject(0))
                    put(JSONObject().put("public", true)
                        .put("permissions", JSONArray(listOf("subscribe"))))
                } else clonePerms
                setPermissionsRetry(messageStreamId, clonePerms); onProgress()
                setPermissionsRetry(ephemeralStreamId, clonePerms); onProgress()
                setPermissionsRetry(adminStreamId, adminPerms); onProgress()
                setPermissionsRetry(keysStreamId, clonePerms); onProgress()
                // Initial members: ONE gate transaction. Failure is non-fatal
                // (the owner re-adds from the members UI).
                if (members.isNotEmpty()) {
                    try {
                        bridge.call("gateAllowBatch", JSONObject()
                            .put("gate", gateAddress)
                            .put("users", JSONArray(members)), 180_000)
                    } catch (e: Exception) {
                        Log.w(TAG, "gateAllowBatch failed (re-add members later): ${e.message}")
                    }
                }
            }
        }

        // Storage on the -1 and -3 streams (never the ephemeral one).
        // Storage failure is NOT fatal: the web logs and continues so the user
        // still gets a working channel, just without retained history
        // (channels.js:418-420). Letting retry() throw here would abort a
        // creation whose three streams are already paid for on-chain.
        val storageNode = if (storageProvider == "custom" && !customStorageAddress.isNullOrBlank())
            customStorageAddress else STORAGE_NODE
        var storageOk = true
        try { addStorageRetry(messageStreamId, storageNode, storageDays) } catch (e: Exception) {
            storageOk = false
            Log.w(TAG, "Storage on -1 failed; continuing without history: ${e.message}")
        }
        onProgress()
        try { addStorageRetry(adminStreamId, storageNode, storageDays) } catch (e: Exception) {
            Log.w(TAG, "Storage on -3 failed; continuing without admin history: ${e.message}")
        }
        onProgress()
        // -4 MUST have storage: joiners pull KEY_ANNOUNCEs from it, and
        // requests/wraps survive there until the counterpart comes online.
        // (The web shipped without this once — the announce was never retained
        // and joiners could not even ask for the key.)
        if (type == "native" || type == "gated") {
            try { addStorageRetry(keysStreamId, storageNode, storageDays) } catch (e: Exception) {
                Log.w(TAG, "Storage on -4 failed; key exchange limited to live members: ${e.message}")
            }
            onProgress()
        }

        // Password challenge on -3/P2 (payload encrypted with the password)
        if (type == "password" && password != null) {
            try {
                publishPasswordChallenge(adminStreamId, password)
            } catch (e: Exception) {
                Log.w(TAG, "Initial PASSWORD_CHALLENGE failed; retention loop will retry: ${e.message}")
            }
            // Joiners fail closed when the challenge is missing, and storage
            // attachment can race the publish — so keep republishing until a
            // resend actually returns it (web: _ensurePasswordChallengeRetained).
            scope.launch { ensurePasswordChallengeRetained(adminStreamId, password) }
        }

        val channel = Channel(
            messageStreamId = messageStreamId,
            ephemeralStreamId = ephemeralStreamId,
            adminStreamId = adminStreamId,
            keysStreamId = if (type == "native" || type == "gated") keysStreamId else "",
            name = name,
            type = type,
            createdBy = addr,
            joinedAt = System.currentTimeMillis(),
            password = password,
            // Owner first, then the invited addresses — the web seeds the
            // members array at creation (channels.js:458-460) so the Members
            // panel is populated straight away instead of starting empty.
            members = if (type == "native" || type == "gated")
                listOf(addr) + members.filter { !it.equals(addr, ignoreCase = true) }
            else emptyList(),
            storageEnabled = storageOk,
            storageProvider = storageProvider,
            storageDays = storageDays,
            exposure = effectiveExposure,
            description = if (visible) description else "",
            language = if (visible) language else "",
            category = if (visible) category else "",
            classification = classification ?: if (type == "native" || type == "gated") "personal" else null,
            readOnly = readOnly,
            gateAddress = gateAddress
        )
        addChannel(channel)
        return channel
    }

    /** Publishes the password challenge on -3/P2, sealed with the password. */
    private suspend fun publishPasswordChallenge(adminStreamId: String, password: String) {
        val challenge = JSONObject()
            .put("type", "PASSWORD_CHALLENGE").put("v", 1)
            .put("magic", StreamConstants.PASSWORD_CHALLENGE_MAGIC)
            .put("ts", System.currentTimeMillis())
        publishContent(adminStreamId, StreamConstants.ADMIN_PASSWORD_CHALLENGE, challenge, password)
    }

    /**
     * Republish the challenge until the storage node has actually retained it.
     *
     * A single create-time publish is not enough: `addToStorageNode` and the
     * publish race, and if the node was not yet attached the message is simply
     * not kept. Joiners fail closed on a missing challenge, so without this
     * loop a freshly created password channel can be permanently unjoinable —
     * including by its own owner from another device.
     */
    private suspend fun ensurePasswordChallengeRetained(
        adminStreamId: String, password: String,
        maxAttempts: Int = 12, delayMs: Long = 5_000
    ) {
        repeat(maxAttempts) { attempt ->
            delay(delayMs)
            val retained = try {
                verifyPasswordChallenge(adminStreamId, password); true
            } catch (e: Exception) {
                // A wrong-password verdict here would mean we cannot read back
                // what we just wrote; either way the answer is "republish".
                false
            }
            if (retained) {
                Log.i(TAG, "PASSWORD_CHALLENGE retained after ${attempt + 1} cycle(s)")
                return
            }
            try { publishPasswordChallenge(adminStreamId, password) } catch (e: Exception) {
                Log.d(TAG, "PASSWORD_CHALLENGE republish #${attempt + 1} failed: ${e.message}")
            }
        }
        Log.w(TAG, "PASSWORD_CHALLENGE not retained after $maxAttempts attempts — joiners may see CHALLENGE_NOT_FOUND")
    }

    private suspend fun createStreamRetry(id: String, description: String, partitions: Int) = retry(7) {
        bridge.call("createStream", JSONObject()
            .put("id", id).put("description", description).put("partitions", partitions), 120_000)
    }

    private suspend fun setPermissionsRetry(streamId: String, assignments: JSONArray) = retry(7) {
        bridge.call("setPermissions", JSONObject()
            .put("streamId", streamId).put("assignments", assignments), 120_000)
    }

    private suspend fun addStorageRetry(
        streamId: String, nodeAddress: String = STORAGE_NODE, storageDays: Int = 180
    ) = retry(7) {
        bridge.call("addToStorageNode", JSONObject()
            .put("streamId", streamId).put("nodeAddress", nodeAddress)
            .put("storageDays", storageDays), 120_000)
    }

    private suspend fun <T> retry(times: Int, block: suspend () -> T): T {
        var delayMs = 1_000L
        var last: Exception? = null
        repeat(times) {
            try { return block() } catch (e: Exception) {
                last = e
                // Web utils/retry.js shouldRetry: only NETWORK/NONCE failures
                // can turn out differently on a retry. A deterministic revert
                // or an empty wallet used to be retried 7× — a minute of
                // waiting for seven identical failures.
                if (!com.pombo.android.core.ChainErrors.isTransient(e)) throw e
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
        throw last ?: IllegalStateException("retry failed")
    }

    /** channels.js joinChannel flow: permissions -> type -> password (fail-closed). */
    suspend fun joinChannel(input: String, password: String? = null): Channel {
        val messageStreamId = input.trim().let {
            if (it.endsWith(StreamConstants.SUFFIX_MESSAGE)) it else it + StreamConstants.SUFFIX_MESSAGE
        }
        _channels.value.find { it.messageStreamId == messageStreamId }?.let { return it }

        val perms = bridge.call("checkPermissions", JSONObject().put("streamId", messageStreamId))
        var canPublish = perms.optBoolean("canPublish")
        var canSubscribe = perms.optBoolean("canSubscribe")

        // Type/name from metadata (accepts short keys 'a'/'t' and long 'app'/'type')
        var name = messageStreamId.substringAfterLast('/').removeSuffix(StreamConstants.SUFFIX_MESSAGE)
        var type: String? = null
        var exposure = "hidden"
        var descriptionMeta = ""
        var gateAddress: String? = null
        try {
            val info = bridge.call("getStreamInfo", JSONObject().put("streamId", messageStreamId))
            val desc = info.optJSONObject("metadata")?.optString("description") ?: ""
            if (desc.isNotEmpty()) {
                val meta = JSONObject(desc)
                val app = meta.optString("a").ifEmpty { meta.optString("app") }
                if (app == "pombo") {
                    type = meta.optString("t").ifEmpty { meta.optString("type") }.ifEmpty { null }
                    meta.optString("n").ifEmpty { null }?.let { if (it != "null") name = it }
                    exposure = meta.optString("e", "hidden")
                    descriptionMeta = meta.optString("d", "")
                    gateAddress = meta.optString("g").lowercase()
                        .takeIf { Regex("^0x[0-9a-f]{40}$").matches(it) }
                }
            }
        } catch (e: Exception) { /* metadata is optional */ }

        // Gated (N-C): stream permissions belong to the gate clone, never to
        // members — the join gate is the CONTRACT. One cached eth_call.
        if (gateAddress != null) {
            type = "gated"
            val me = myAddress() ?: throw IllegalStateException("No identity")
            val access = bridge.call("gateCheckAccess", JSONObject()
                .put("gate", gateAddress).put("user", me)).optBoolean("access", false)
            if (!access) {
                // Typed for the UI (N-D): the gate entry screen reads the mode
                // on-chain and offers pay()/join() instead of a toast.
                throw GateAccessDenied(gateAddress)
            }
            canPublish = true
            canSubscribe = true
        } else if (!canPublish && !canSubscribe) {
            throw IllegalStateException("No access to this channel (neither subscribe nor publish)")
        }
        val resolvedType = type ?: if (password != null) "password" else "native"

        val adminStreamId = StreamConstants.deriveAdminId(messageStreamId)

        // Verify the password against the challenge (fail-closed, like the web)
        if (resolvedType == "password") {
            if (password.isNullOrEmpty()) throw IllegalStateException("This channel requires a password")
            verifyPasswordChallenge(adminStreamId, password)
        }

        val channel = Channel(
            messageStreamId = messageStreamId,
            ephemeralStreamId = StreamConstants.deriveEphemeralId(messageStreamId),
            adminStreamId = adminStreamId,
            keysStreamId = if (resolvedType == "gated")
                StreamConstants.deriveKeysId(messageStreamId) else "",
            name = name,
            type = resolvedType,
            gateAddress = gateAddress,
            joinedAt = System.currentTimeMillis(),
            password = if (resolvedType == "password") password else null,
            exposure = exposure,
            description = descriptionMeta,
            readOnly = !canPublish && canSubscribe,
            writeOnly = canPublish && !canSubscribe
        )
        addChannel(channel)
        // Owner + members from The Graph, in parallel with the return (web
        // joinChannel:579-626 does the same). Without it a joined native
        // channel's Members panel started empty until its own on-demand
        // fetch, and createdBy stayed at the stream-id fallback.
        if (resolvedType == "native") {
            scope.launch {
                runCatching {
                    val owner = channelOwner(channel)
                    val fetched = com.pombo.android.core.GraphApi.streamMembers(messageStreamId)
                    val ordered = (listOfNotNull(owner) +
                        fetched.filter { !it.equals(owner, ignoreCase = true) }).distinct()
                    if (ordered.isEmpty()) return@runCatching
                    val updated = channel.copy(members = ordered, createdBy = channel.createdBy ?: owner)
                    _channels.value = _channels.value.map {
                        if (it.messageStreamId == messageStreamId) updated else it
                    }
                    store.save(_channels.value)
                    if (_current.value?.messageStreamId == messageStreamId) _current.value = updated
                }
            }
        }
        return channel
    }

    /**
     * Last message of a channel for the list/Explore preview
     * (web: resendLatestContentMessages via ChannelLatestMessageManager).
     */
    /**
     * Display label for a preview's sender — the web's `formatPreviewSender`
     * priority: You -> ENS -> senderName -> short address. When the ENS name is
     * not cached yet the resolution is kicked off here; the list row resolves
     * again at render time (it observes [ensNames]), so the name swaps in without
     * having to re-fetch the preview.
     */
    private fun previewSenderLabel(address: String, senderName: String?): String {
        if (address.isNotEmpty() && address.equals(myAddress(), ignoreCase = true)) return "You"
        ensStore.cachedName(address)?.let { return it }
        if (address.isNotEmpty()) resolveEnsFor(address)
        senderName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (address.length > 10) address.take(6) + "…" else address
    }

    suspend fun fetchLatestPreview(
        messageStreamId: String,
        /** Password channels seal P0 payloads; without it every entry is skipped. */
        password: String? = null
    ): com.pombo.android.core.LatestMessageStore.Preview? {
        return try {
            // Resends need a live client; without this the call fails instantly
            // on a cold start and the preview silently never appears.
            bridge.awaitConnected()
            // P0 carries messages AND reactions AND image chunks, so last:1
            // usually lands on something unrenderable. The web fetches a window
            // (config.channels.latestMessageFetchLast = 10) and picks the
            // newest entry it can actually show.
            val args = JSONObject()
                .put("streamId", messageStreamId)
                .put("partition", StreamConstants.P_MESSAGES)
                .put("last", LATEST_PREVIEW_FETCH)
                .put("budgetMs", 12_000)
            password?.let { args.put("password", it) }
            val res = bridge.call("resend", args, 20_000)
            val arr = res.optJSONArray("messages") ?: return null
            // Password channels seal each payload as a base64 STRING — reading it
            // as a JSONObject skipped every entry, so private channels never got
            // a preview at all. Same batch decrypt the history page uses.
            val decrypted = predecrypt(arr, password)
            var best: com.pombo.android.core.LatestMessageStore.Preview? = null
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val content = decrypted.getOrNull(i) as? JSONObject ?: continue
                val ts = entry.optJSONObject("meta")?.optLong("timestamp") ?: 0L
                // account = proof ? recovered wallet : publisherId — covers all
                // three eras: new messages (proof), legacy (publisherId = the
                // wallet) and reactions (no sender field at all). The legacy
                // payload `sender` only matters when meta carried no publisher.
                val senderAddr = attachAccount(
                    content, entry.optJSONObject("meta")?.optString("publisherId")
                ) ?: content.optString("sender")
                val sender = previewSenderLabel(senderAddr, content.optStringOrNull("senderName"))
                val body = when (content.optString("type")) {
                    "text" -> content.optString("text")
                    "image" -> "[image]"
                    "video_announce" -> "[video]"
                    "file_announce", "storage_file_announce" -> "[file]"
                    // Removals are not previewable (web filters them upstream).
                    "reaction" -> if (content.optString("action") == "remove") null
                        else "reacted with ${content.optString("emoji")}"
                    else -> null
                } ?: continue
                if (best == null || ts > best.ts) {
                    best = com.pombo.android.core.LatestMessageStore.Preview(sender, body, ts, senderAddr)
                }
            }
            best
        } catch (e: Exception) { null }
    }

    /**
     * One resend window over a background channel's P0, feeding BOTH the list
     * preview and the unread badge — the web's `checkChannelActivity`, minus
     * its 30s timer (we scan on app start and on entering Chats instead).
     *
     * No subscription is opened: the web works the same way, keeping a live
     * subscription only for the channel on screen.
     *
     * The two outputs need DIFFERENT filters, which is the easy thing to get
     * wrong here. A reaction is a legitimate preview ("reacted with 🎉") but is
     * not a new message, so it must not raise the badge; `image_chunk` is pure
     * transport and is neither. Web `isContentMessage`: text/message, image
     * with an imageId, video_announce with metadata — never reaction, edit or
     * delete.
     */
    suspend fun scanChannelActivity(channel: Channel) {
        val streamId = channel.messageStreamId
        try {
            bridge.awaitConnected()
            val args = JSONObject()
                .put("streamId", streamId)
                .put("partition", StreamConstants.P_MESSAGES)
                .put("last", LATEST_PREVIEW_FETCH)
                .put("budgetMs", 12_000)
            channel.password?.let { args.put("password", it) }
            val res = bridge.call("resend", args, 20_000)
            val arr = res.optJSONArray("messages") ?: return
            val since = unreadStore.watermark(streamId)
            val me = myAddress()
            // Password channels seal P0 payloads — decrypt before reading, or this
            // scan finds nothing (no preview AND no unread count for them).
            val decrypted = predecrypt(arr, channel.password)

            var best: com.pombo.android.core.LatestMessageStore.Preview? = null
            var newContent = 0
            var maxTs = since

            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val content = decrypted.getOrNull(i) as? JSONObject ?: continue
                val meta = entry.optJSONObject("meta")
                val ts = meta?.optLong("timestamp") ?: 0L
                val type = content.optString("type")

                // Advance the watermark past ANY newer item, content or not, so
                // a window full of reactions cannot make the next scan reread
                // the same entries forever (web does the same).
                if (ts > maxTs) maxTs = ts

                // Same rule as fetchLatestPreview: identity from the proof-
                // resolved account, publisherId fallback for the legacy eras.
                val senderAddr = attachAccount(content, meta?.optString("publisherId"))
                    ?: content.optString("sender")

                // --- preview candidate (reactions allowed) ---
                val body = when (type) {
                    "text" -> content.optString("text")
                    "image" -> "[image]"
                    "video_announce" -> "[video]"
                    "file_announce", "storage_file_announce" -> "[file]"
                    "reaction" -> if (content.optString("action") == "remove") null
                        else "reacted with ${content.optString("emoji")}"
                    else -> null
                }
                if (body != null && (best == null || ts > best.ts)) {
                    best = com.pombo.android.core.LatestMessageStore.Preview(
                        previewSenderLabel(senderAddr, content.optStringOrNull("senderName")), body, ts, senderAddr
                    )
                }

                // --- unread candidate (content only, and not my own) ---
                if (ts <= since) continue
                if (senderAddr.equals(me, ignoreCase = true)) continue
                val isContent = when (type) {
                    "text", "message" -> true
                    "image" -> content.optStringOrNull("imageId") != null
                    "video_announce" -> content.opt("metadata") != null
                    else -> false
                }
                if (isContent) newContent++
            }

            best?.let { previewStore.put(streamId, it) }
            if (newContent > 0) unreadStore.add(streamId, newContent)
            if (maxTs > since) unreadStore.setWatermark(streamId, maxTs)
        } catch (e: Exception) {
            // A failed scan is not worth surfacing: the list keeps whatever it
            // had, and the next pass retries.
        }
    }

    /**
     * Fetches the public metadata of a channel (name/description/type) from the
     * pombo JSON in the stream description — used by the Explore preview cards.
     */
    suspend fun fetchChannelMeta(messageStreamId: String): ExploreChannel {
        var name = messageStreamId.substringAfterLast('/').removeSuffix(StreamConstants.SUFFIX_MESSAGE)
        var type = "public"
        var description = ""
        var language = ""
        var category = ""
        try {
            val info = bridge.call("getStreamInfo", JSONObject().put("streamId", messageStreamId), 20_000)
            val desc = info.optJSONObject("metadata")?.optString("description") ?: ""
            if (desc.isNotEmpty()) {
                val meta = JSONObject(desc)
                val app = meta.optString("a").ifEmpty { meta.optString("app") }
                if (app == "pombo") {
                    meta.optString("n").ifEmpty { null }?.let { if (it != "null") name = it }
                    type = meta.optString("t").ifEmpty { meta.optString("type") }.ifEmpty { "public" }
                    description = meta.optString("d", "")
                    language = meta.optString("l", "")
                    category = meta.optString("c", "")
                }
            }
        } catch (e: Exception) { /* keep fallbacks */ }

        // Last message preview (web shows "sender: text" on the explore card)
        var lastSender = ""
        var lastText = ""
        var lastSenderAddress = ""
        try {
            val res = bridge.call("resend", JSONObject()
                .put("streamId", messageStreamId).put("partition", StreamConstants.P_MESSAGES).put("last", 1), 20_000)
            val arr = res.optJSONArray("messages")
            val content = if (arr != null && arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.opt("content") else null
            val body = if (content is JSONObject) when (content.optString("type")) {
                "text" -> content.optString("text")
                "image" -> "[image]"
                "file_announce", "storage_file_announce" -> "[file]"
                else -> null
            } else null
            if (content is JSONObject && body != null) {
                lastSenderAddress = content.optString("sender")
                lastSender = previewSenderLabel(lastSenderAddress, content.optStringOrNull("senderName"))
                lastText = body
            }
        } catch (e: Exception) { /* preview is best-effort */ }

        return ExploreChannel(
            messageStreamId, name, description, type, language, category,
            lastSender, lastText, lastSenderAddress
        )
    }

    // ==================== moderation (ADMIN_STATE) ====================

    /**
     * Channel owner. Falls back to the address that prefixes the stream ID
     * (`0xowner/path`), which Streamr guarantees — the web does the same when
     * `createdBy` is unknown, e.g. for channels joined from Explore.
     */
    private fun channelOwner(channel: Channel): String? =
        channel.createdBy?.lowercase()
            ?: channel.messageStreamId.substringBefore('/', "").lowercase().ifEmpty { null }

    fun amOwner(channel: Channel): Boolean =
        channelOwner(channel)?.equals(myAddress(), ignoreCase = true) == true

    /**
     * Whether the current account may moderate the open channel: an on-chain
     * DELETE-permission check (web: MessageContextMenuUI.js:211-214), not
     * `amOwner` — `createdBy`/stream-id comparison misses a granted-but-not-
     * creator admin and keeps Pin/Hide/Ban offered to a creator whose
     * permission was since revoked.
     */
    /**
     * What the current account may do on the open channel.
     *
     * The web gates different surfaces on different axes — moderation and the
     * danger zone on DELETE, adding members on GRANT — so a single "am I the
     * owner" boolean cannot express it (ChannelSettingsUI.js:97-201).
     */
    data class ChannelPerms(
        val canPublish: Boolean = false,
        val canGrant: Boolean = false,
        val canEdit: Boolean = false,
        val canDelete: Boolean = false
    )

    private val _perms = MutableStateFlow(ChannelPerms())
    val perms: StateFlow<ChannelPerms> = _perms.asStateFlow()

    /** Convenience for the moderation surfaces, which all key off DELETE. */
    val canModerate: StateFlow<Boolean> = _perms
        .map { it.canDelete }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** streamId -> (address that was checked, verdict). */
    private val permCache = HashMap<String, Pair<String, ChannelPerms>>()

    private fun refreshModerationPermission(channel: Channel, preview: Boolean) {
        val me = myAddress()?.lowercase()
        // A DM has no moderation surface, and a preview is read-only until the
        // user joins — the web zeroes both cases before it even asks.
        if (me == null || preview || channel.type == "dm") {
            _perms.value = ChannelPerms()
            return
        }
        val key = channel.messageStreamId
        // Serve the cache only when it was filled by THIS account: switching
        // accounts must not inherit the previous one's verdict.
        permCache[key]?.let { (addr, cached) ->
            if (addr == me) { _perms.value = cached; return }
        }
        _perms.value = ChannelPerms()
        scope.launch {
            val verdict = try {
                val r = bridge.call("checkPermissions", JSONObject().put("streamId", key), 30_000)
                ChannelPerms(
                    canPublish = r.optBoolean("canPublish", false),
                    canGrant = r.optBoolean("canGrant", false),
                    canEdit = r.optBoolean("canEdit", false),
                    canDelete = r.optBoolean("canDelete", false)
                )
            } catch (e: Exception) {
                // Fail closed: offering actions we cannot perform is worse than
                // hiding actions the user might have — the former fails at
                // publish time with nothing to show for it.
                Log.w(TAG, "Permission check failed for $key: ${e.message}")
                ChannelPerms()
            }
            permCache[key] = me to verdict
            // Only apply if this channel is still the open one — a fast switch
            // must not stamp the previous channel's verdict onto the new one.
            if (_current.value?.messageStreamId == key) _perms.value = verdict
        }
    }

    private suspend fun loadAdminState(channel: Channel, generation: Int) {
        try {
            // Password channels seal ADMIN_STATE too, and applyAdminMessage's
            // fallback opens it with PomboCrypto — Bouncy Castle PBKDF2, ~1s per
            // message on a phone, up to 5 of them, all inside the render gate.
            // Handing the password to the bridge moves that to BoringSSL and
            // overlaps it with the resend.
            val args = JSONObject()
                .put("streamId", channel.adminStreamId)
                .put("partition", StreamConstants.ADMIN_MODERATION)
                .put("last", 5)
            channel.password?.let { args.put("password", it) }
            if (channel.type == "gated") args.put("recoverSigner", true)
            val t0 = System.currentTimeMillis()
            val res = bridge.call("resend", args, 30_000)
            android.util.Log.d("PomboPerf",
                "adminState ${channel.name}: call=${System.currentTimeMillis() - t0}ms " +
                    "n=${res.optJSONArray("messages")?.length() ?: -1}")
            if (!stillCurrent(generation)) return
            val arr = res.optJSONArray("messages") ?: return
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                var content = entry.opt("content")
                val meta = entry.optJSONObject("meta") ?: JSONObject()
                // Gated: -3 authority moved to ingest — the clone holds the
                // publish grant for everyone, so only the envelope SIGNER
                // proves the admin wrote this (gatedAuthor drops the rest).
                if (channel.type == "gated" &&
                    gatedAuthor(channel, channel.adminStreamId, meta) == null) continue
                // Native: ADMIN_STATE arrives as an epoch envelope (N-A)
                if (content is JSONObject &&
                    com.pombo.android.core.EpochKeyCrypto.isEpochEnvelope(content) &&
                    isEpochChannel(channel)
                ) {
                    val keysId = channel.keysStreamId.ifEmpty {
                        StreamConstants.deriveKeysId(channel.messageStreamId)
                    }
                    content = epochKeys.tryDecrypt(channel.messageStreamId, keysId, content) ?: continue
                }
                applyAdminMessage(channel, content, meta, generation)
            }
            // Even an empty history is an answer: the stream holds no
            // snapshot, so rev bookkeeping may start from zero.
            adminLoaded.add(channel.adminStreamId)
        } catch (e: Exception) { /* no admin history */ }
    }

    /**
     * Periodic ADMIN_STATE refresh for the open channel (web adminStatePoller,
     * CONFIG.subscriptions.adminPollIntervalMs). There is no live subscription
     * on -3 — like the web, moderation converges via the on-open load, the
     * admin_invalidate signal on the already-subscribed -2 (instant path) and
     * this poller (safety net). The resend goes straight to the storage node,
     * so no overlay membership is spent on it. Same lifecycle as presence:
     * starts on open, dies on close/switch.
     */
    private fun startAdminPoller(channel: Channel, generation: Int) {
        adminPollJob?.cancel()
        adminPollJob = scope.launch {
            while (isActive) {
                delay(ADMIN_POLL_INTERVAL_MS)
                if (!stillCurrent(generation)) return@launch
                loadAdminState(channel, generation)
            }
        }
    }

    /**
     * TTL-aware republish of the -3 artifacts on owner open (web channels.js
     * _ttlRepublishOnOpen; docs/TTL_REPUBLISH_PLAN.md).
     *
     * The storage node's TTL purge deletes by message timestamp and the -3
     * artifacts are published once, then only read — an unmoderated channel
     * eventually loses its ADMIN_STATE (bans/pins vanish silently), its
     * CHANNEL_IMAGE and its PASSWORD_CHALLENGE (joiners fail closed: channel
     * unjoinable). When the OWNER opens a channel, compare each retained
     * artifact's payload `ts` against the retention and republish anything
     * past TTL_REPUBLISH_AGE_FRACTION of its life. Republishing resets the
     * clock, so this stays quiet for the next ~80% of the TTL.
     *
     * The challenge branch is also the reopen redundancy check the web had
     * inline (create-time publish racing the storage attachment: missing or
     * invalid → republish) — ported here for parity.
     *
     * Must run AFTER loadAdminState (reads adminRevs/adminTs and the
     * moderation flows that publishAdminState snapshots).
     */
    private suspend fun ttlRepublishOnOpen(channel: Channel, generation: Int) {
        if (channel.type == "dm") return
        // Only the owner can publish on -3 (on-chain permissions).
        if (!amOwner(channel)) return
        // NOTE: deliberately NOT gated on channel.storageEnabled — the local
        // flag can be stale (joined channels, pre-flag records) while the
        // stream has storage on-chain. Each branch self-gates: nothing
        // retained → nothing republished (and a missing challenge must
        // republish regardless, the legacy redundancy semantics).
        if (channel.adminStreamId.isEmpty()) return
        val storageDays = channel.storageDays?.takeIf { it > 0 } ?: DEFAULT_RETENTION_DAYS
        fun ageDays(ts: Long) = (System.currentTimeMillis() - ts) / 86_400_000L

        // ADMIN_STATE (-3/P0): republish the current snapshot with rev+1 via
        // the normal publish path (rev bookkeeping + admin_invalidate fan-out
        // included). Only when a snapshot is actually retained — an empty
        // state has nothing to preserve.
        val adminTsValue = adminTs[channel.adminStreamId] ?: 0L
        if (channel.adminStreamId in adminLoaded
            && (adminRevs[channel.adminStreamId] ?: 0) > 0
            && shouldRepublish(adminTsValue, storageDays)
        ) {
            try {
                if (!stillCurrent(generation)) return
                Log.i(TAG, "ADMIN_STATE nearing storage TTL (${ageDays(adminTsValue)}d/${storageDays}d) — owner republishing")
                publishAdminState(channel)
            } catch (e: Exception) {
                Log.w(TAG, "ADMIN_STATE TTL republish failed (will retry next open): ${e.message}")
            }
        }

        // PASSWORD_CHALLENGE (-3/P2): missing OR invalid (legacy redundancy
        // semantics) OR too old → republish a fresh payload. Content is
        // immutable, so a fresh publish is always safe.
        val pwd = channel.password
        if (channel.type == "password" && !pwd.isNullOrEmpty()) {
            try {
                val probe = probePasswordChallenge(channel.adminStreamId, pwd)
                val tooOld = probe.found && probe.valid && shouldRepublish(probe.ts, storageDays)
                when {
                    !probe.found -> Log.i(TAG, "PASSWORD_CHALLENGE not retained on -3/P2 — owner republishing for redundancy")
                    !probe.valid -> Log.w(TAG, "PASSWORD_CHALLENGE did not verify with owner password — republishing")
                    tooOld -> Log.i(TAG, "PASSWORD_CHALLENGE nearing storage TTL (${ageDays(probe.ts)}d/${storageDays}d) — owner republishing")
                }
                if (!probe.found || !probe.valid || tooOld) {
                    if (!stillCurrent(generation)) return
                    // A single publish into a cold partition overlay can be
                    // silently lost (observed in the field: publish returns ok,
                    // storage never sees it). Verify-until-retained, but by
                    // FRESHNESS, not existence — ensurePasswordChallengeRetained
                    // would see the old still-valid challenge and declare
                    // victory, which is exactly the entry we are replacing.
                    val publishedAt = System.currentTimeMillis()
                    publishPasswordChallenge(channel.adminStreamId, pwd)
                    scope.launch {
                        repeat(12) { attempt ->
                            delay(5_000)
                            val check = probePasswordChallenge(channel.adminStreamId, pwd)
                            if (check.found && check.valid && check.ts >= publishedAt) {
                                Log.i(TAG, "PASSWORD_CHALLENGE refresh retained after ${attempt + 1} cycle(s)")
                                return@launch
                            }
                            try { publishPasswordChallenge(channel.adminStreamId, pwd) } catch (e: Exception) {
                                Log.w(TAG, "PASSWORD_CHALLENGE refresh republish #${attempt + 1} failed: ${e.message}")
                            }
                        }
                        Log.w(TAG, "PASSWORD_CHALLENGE refresh not retained after 12 attempts")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "PASSWORD_CHALLENGE TTL check failed (will retry next open): ${e.message}")
            }
        }

        // CHANNEL_IMAGE (-3/P1): republish the RETAINED payload verbatim with
        // rev+1 and a fresh ts. Deliberately re-resent here instead of
        // trusting the image store — the disk cache survives a storage purge,
        // and resurrecting a purged image from local cache is a recovery
        // decision this path does not make (plan §6).
        try {
            val payload = resendImagePayload(channel.adminStreamId, pwd)
            val ts = payload?.optLong("ts", 0L) ?: 0L
            if (payload != null
                && payload.optString("data").isNotEmpty()
                && payload.optString("hash").isNotEmpty()
                && shouldRepublish(ts, storageDays)
            ) {
                val encrypted = payload.optBoolean("encrypted", false)
                if (encrypted && pwd.isNullOrEmpty()) {
                    Log.d(TAG, "CHANNEL_IMAGE TTL republish skipped: encrypted payload without password")
                    return
                }
                val newRev = payload.optInt("rev", 0) + 1
                val fresh = JSONObject(payload.toString())
                    .put("rev", newRev)
                    .put("ts", System.currentTimeMillis())
                if (fresh.optString("createdBy").isEmpty()) fresh.put("createdBy", myAddress())
                if (!stillCurrent(generation)) return
                Log.i(TAG, "CHANNEL_IMAGE nearing storage TTL (${ageDays(ts)}d/${storageDays}d) — owner republishing rev=$newRev")
                publishContent(
                    channel.adminStreamId, StreamConstants.ADMIN_CHANNEL_IMAGE, fresh,
                    if (encrypted) pwd else null
                )
                // Keep the open channel's rev counter ahead of the retained
                // entry so a later image change never publishes a lower rev.
                if (stillCurrent(generation)) channelImageRev = maxOf(channelImageRev, newRev)
            }
        } catch (e: Exception) {
            Log.w(TAG, "CHANNEL_IMAGE TTL republish failed (will retry next open): ${e.message}")
        }
    }

    private fun applyAdminMessage(
        channel: Channel,
        contentAny: Any?,
        meta: JSONObject,
        generation: Int
    ) {
        // Pins, hidden ids and bans are flat flows describing the OPEN channel,
        // so a late arrival for any other channel must not reach them. Same
        // two independent nets as handleContent: the generation fence, plus a
        // counter-independent check that this is the admin stream on screen.
        if (!stillCurrent(generation)) return
        if (channel.adminStreamId != _current.value?.adminStreamId) return
        val data: JSONObject = when (contentAny) {
            is JSONObject -> contentAny
            is String -> {
                val pwd = channel.password ?: return
                try { JSONObject(PomboCrypto.decryptString(contentAny, pwd)) } catch (e: Exception) { return }
            }
            else -> return
        }
        if (data.optString("type") != "ADMIN_STATE") return
        // Owner-authored only. Authority is the ACCOUNT: on the -3 stream the
        // owner always publishes as the wallet (on-chain permission — an
        // ephemeral key can't), so publisherId still works there; but the
        // admin_invalidate snapshot rides the -2 stream, which will publish
        // under an ephemeral key once step 5 lands — there the proof-resolved
        // `account` (stamped by attachAccount before this is called) is the
        // only field that still names the owner. Web checks data.account too.
        // Latest-wins by (rev, ts) — the timestamp breaks rev ties so a stale
        // replica snapshot sharing a rev cannot overwrite a newer one.
        val senderId = data.optString("account")
            .ifEmpty { meta.optString("publisherId") }.lowercase()
        val owner = channelOwner(channel)
        if (owner != null && senderId.isNotEmpty() && senderId != owner) return
        val rev = data.optInt("rev", 0)
        val ts = data.optLong("ts", 0L)
        val curRev = adminRevs[channel.adminStreamId] ?: 0
        val curTs = adminTs[channel.adminStreamId] ?: 0L
        if (rev < curRev || (rev == curRev && ts < curTs)) return
        adminRevs[channel.adminStreamId] = rev
        adminTs[channel.adminStreamId] = ts
        val state = data.optJSONObject("state") ?: return
        state.optJSONArray("hiddenMessageIds")?.let { arr ->
            _hiddenIds.value = (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }.toSet()
        }
        state.optJSONArray("bannedMembers")?.let { arr ->
            _bannedMembers.value = (0 until arr.length()).mapNotNull { arr.optString(it).lowercase().ifEmpty { null } }.toSet()
        }
        state.optJSONArray("pins")?.let { arr ->
            _pins.value = (0 until arr.length()).mapNotNull { idx ->
                val p = arr.optJSONObject(idx) ?: return@mapNotNull null
                val snap = p.optJSONObject("snapshot")
                Pin(p.optString("targetId"), snap?.optString("text") ?: "", snap?.optString("sender") ?: "")
            }
        }
    }

    /** Publishes the full ADMIN_STATE with an incremented rev (owner only). */
    private suspend fun publishAdminState(channel: Channel) {
        val addr = myAddress() ?: return
        // Never compute a rev off an unscanned stream (web gates publish on
        // adminLoaded): moderating fast, before the on-open load finished,
        // published rev=1 over a channel already at rev N — every peer with
        // the higher rev discarded it. Failure proceeds with the stale rev,
        // matching the web's "may publish stale rev" warning path.
        if (channel.adminStreamId !in adminLoaded) {
            runCatching { loadAdminState(channel, switchGeneration) }
        }
        val rev = (adminRevs[channel.adminStreamId] ?: 0) + 1
        val state = JSONObject()
            .put("bannedMembers", JSONArray(_bannedMembers.value.toList()))
            .put("hiddenMessageIds", JSONArray(_hiddenIds.value.toList()))
            .put("pins", JSONArray(_pins.value.map {
                JSONObject().put("targetId", it.targetId).put("pinnedAt", System.currentTimeMillis())
                    .put("snapshot", JSONObject().put("sender", it.sender).put("text", it.text))
            }))
        val msg = JSONObject()
            .put("type", "ADMIN_STATE").put("rev", rev)
            .put("ts", System.currentTimeMillis()).put("createdBy", addr)
            .put("state", state)
        publishForChannel(channel, channel.adminStreamId, StreamConstants.ADMIN_MODERATION, msg)
        // Commit the revision only once it is on the wire. Incrementing up
        // front meant a failed publish — which [moderate] rolls back — still
        // burned a revision, so the next attempt skipped a number.
        adminRevs[channel.adminStreamId] = rev
        adminTs[channel.adminStreamId] = msg.optLong("ts")
        // Low-latency fan-out (web channels.js publishAdminState): nobody —
        // web or Android — subscribes -3 live, so this ephemeral signal with
        // the full snapshot is what makes a ban/pin/hide reach open channels
        // immediately; the 30s pollers are the fallback. Best-effort: the
        // canonical -3 publish above already succeeded.
        try {
            val signal = JSONObject()
                .put("type", "admin_invalidate")
                .put("rev", rev)
                .put("ts", msg.optLong("ts"))
                .put("snapshot", msg)
            publishForChannel(channel, channel.ephemeralStreamId, StreamConstants.EPH_CONTROL, signal)
        } catch (e: Exception) {
            Log.d(TAG, "admin_invalidate publish failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Moderation is applied locally first so the UI reacts instantly, then
     * published as ADMIN_STATE. If the publish fails the optimistic change is
     * rolled back and the error propagates — otherwise this device would show
     * a pin/ban that no one else can see.
     */
    private suspend fun <T> moderate(
        channel: Channel,
        state: MutableStateFlow<T>,
        next: T
    ) {
        val previous = state.value
        state.value = next
        try {
            publishAdminState(channel)
        } catch (e: Exception) {
            state.value = previous
            throw e
        }
    }

    suspend fun hideMessage(messageId: String, hide: Boolean) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can moderate")
        moderate(channel, _hiddenIds, if (hide) _hiddenIds.value + messageId else _hiddenIds.value - messageId)
    }

    suspend fun pinMessage(messageId: String, pin: Boolean) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can moderate")
        val next = if (pin) {
            val msg = _messages.value.find { it.id == messageId }
                ?: throw IllegalStateException("Message not found")
            if (_pins.value.any { it.targetId == messageId }) return
            _pins.value + Pin(messageId, msg.text, msg.sender)
        } else {
            _pins.value.filterNot { it.targetId == messageId }
        }
        moderate(channel, _pins, next)
    }

    suspend fun banMember(address: String, ban: Boolean = true) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can moderate")
        val addr = address.lowercase()
        moderate(
            channel, _bannedMembers,
            if (ban) _bannedMembers.value + addr else _bannedMembers.value - addr
        )
    }

    // ==================== DMs (E2E) ====================

    /**
     * True when my DM inbox stream already exists on-chain (web: dm.js hasInbox).
     * A positive result is cached for the session — streams aren't deleted
     * mid-session — while a negative one is re-checked, so a transient network
     * failure doesn't wrongly hide the DM UI until restart.
     */
    private var inboxExistsCache: String? = null

    suspend fun hasInbox(): Boolean {
        val me = myAddress()?.lowercase() ?: return false
        val inbox = "$me/Pombo-DM-1"
        if (inboxExistsCache == inbox) return true
        return try {
            bridge.call("getStreamInfo", JSONObject().put("streamId", inbox), 20_000)
            inboxExistsCache = inbox
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Wake signal so other clients' push relays notify their subscribers
     * (web: relayManager.sendChannelWakeSignal / sendNativeChannelWakeSignal).
     *
     * The payload carries only a 1-byte K-anonymity tag and a proof of work —
     * never the message, the channel id or the sender — so the relay learns
     * nothing beyond "something happened in one of 256 tag buckets".
     *
     * Fire-and-forget: failing to notify must never fail the send itself.
     */
    private fun sendWakeSignal(channel: Channel) {
        scope.launch {
            try {
                // Web sendWakeSignals: ONLY native channels use the 'native:'
                // tag; public, password AND DMs use the 'channel:' tag (a DM
                // wakes the peer's inbox channel-tag — dm.js:928 →
                // sendChannelWakeSignal). `type != "public"` silently broke
                // Android→web DM and password-channel notifications.
                val native = isEpochChannel(channel)
                val res = bridge.call(
                    "pushWakePayload",
                    JSONObject().put("streamId", channel.messageStreamId).put("native", native),
                    30_000
                )
                // Ephemeral publisher, fresh key per wake (see PushRelayClient):
                // the wake is validated by PoW, not publisher, so under the
                // account every message X sends would stamp X onto the public
                // /push stream — a timing side-channel back onto the sealed
                // DM it just sent. A throwaway key closes it.
                bridge.call("publishAs", JSONObject()
                    .put("streamId", PUSH_STREAM_ID)
                    .put("partition", 0)
                    .put("content", res.getJSONObject("payload")), 30_000)
                android.util.Log.d(
                    "PomboPush",
                    "wake signal sent: type=${channel.type} native=$native " +
                        "tag=${res.getJSONObject("payload").optString("tag")} " +
                        "stream=…${channel.messageStreamId.takeLast(24)}"
                )
            } catch (e: Exception) {
                // No relay, no PoW in time, no network — the message still went,
                // but the silence here is what made missing notifications
                // undiagnosable; say what died.
                android.util.Log.w(
                    "PomboPush",
                    "wake signal FAILED: type=${channel.type} " +
                        "stream=…${channel.messageStreamId.takeLast(24)}: ${e.message}"
                )
            }
        }
    }

    /**
     * Ensures my DM inbox exists on-chain (needs gas) and subscribes to it.
     *
     * Step-by-step like the web's dmManager.createInbox — 2× stream, 2×
     * permissions, storage — so the UI can drive a progress ring and the user
     * can pick the storage node and retention. Streams have FIXED ids, so a
     * re-run after a partial failure resumes via get-or-create instead of
     * dying on "already exists".
     */
    suspend fun setupDmInbox(
        storageProvider: String = "streamr",
        customStorageAddress: String? = null,
        storageDays: Int = 180,
        onProgress: () -> Unit = {}
    ) {
        val me = myAddress()?.lowercase() ?: throw IllegalStateException("No identity")
        val pk = bridge.call("getMyPublicKey").getString("publicKey")
        val messageStreamId = "$me/Pombo-DM-1"
        val ephemeralStreamId = "$me/Pombo-DM-2"
        // Same metadata the web writes (streamr.js: dm-inbox marker + pubkey).
        val meta = JSONObject().put("a", "pombo").put("v", "1").put("t", "dm-inbox").put("pk", pk)
        val ephMeta = JSONObject().put("a", "pombo").put("v", "1").put("ln", messageStreamId)

        getOrCreateStreamRetry(messageStreamId, meta.toString(), StreamConstants.MSG_DM_PARTITIONS); onProgress()
        getOrCreateStreamRetry(ephemeralStreamId, ephMeta.toString(), StreamConstants.EPH_PARTITIONS); onProgress()

        // Public PUBLISH only (owner reads by ownership) — hides the social graph.
        val pubOnly = JSONArray().put(
            JSONObject().put("public", true).put("permissions", JSONArray(listOf("publish")))
        )
        setPermissionsRetry(messageStreamId, pubOnly); onProgress()
        setPermissionsRetry(ephemeralStreamId, pubOnly); onProgress()

        // Storage best-effort, like channel creation: the inbox works without
        // retained history, and the streams are already paid for.
        val storageNode = if (storageProvider == "custom" && !customStorageAddress.isNullOrBlank())
            customStorageAddress else STORAGE_NODE
        try { addStorageRetry(messageStreamId, storageNode, storageDays) } catch (e: Exception) {
            Log.w(TAG, "DM inbox storage failed; continuing without history: ${e.message}")
        }
        onProgress()

        myInboxId = messageStreamId
        // force: the inbox was just created (or repaired), so any earlier
        // "already subscribed" verdict was about a stream that did not exist —
        // web dm.js repairInbox nulls `inboxSubscription` for the same reason.
        subscribeMyInbox(force = true)
    }

    private suspend fun getOrCreateStreamRetry(id: String, description: String, partitions: Int) = retry(7) {
        bridge.call("getOrCreateStream", JSONObject()
            .put("id", id).put("description", description).put("partitions", partitions), 120_000)
    }

    /**
     * Received DMs, per peer, exactly as the web keeps them on `channel.messages`
     * with `_dmReceived`. In memory on purpose: the inbox history is replayed on
     * every connect, so this is a cache of the network, not a second source of
     * truth. Sent messages are the opposite — see [SentDmStore].
     */
    private val dmReceived = HashMap<String, MutableList<UiMessage>>()

    /**
     * Routes one inbox message to its conversation (web: routeInboxMessage).
     *
     * Runs for history and live traffic alike, which is what makes a DM from
     * someone new appear at all: it creates the conversation when there isn't
     * one. Previously anything not belonging to the open chat was dropped.
     */
    private suspend fun routeInboxMessage(
        envelope: Any?,
        /** Transport publisher — a THROWAWAY key on sealed traffic; only ever
         *  an identity for legacy plaintext payloads (back then it was the
         *  wallet). Never used to pick a decryption key: the sender is
         *  unknown until the envelope is open. */
        publisherIdRaw: String?,
        /** Already opened by the page-wide batch in [loadInboxHistory]. */
        preSender: String? = null,
        preData: JSONObject? = null
    ) {
        val me = myAddress()?.lowercase() ?: return
        if (envelope !is JSONObject) return

        // SEALED SENDER INVERTS THE ORDER: open first, identify after. Any
        // lookup keyed by the sender before decryption (peer key choice,
        // block check, conversation state) would key off a throwaway address
        // and silently drop every message — the web hit that five times.
        val sender: String
        val data: JSONObject
        if (preSender != null && preData != null) {
            sender = preSender
            data = preData
        } else if (envelope.optInt("v") == 2 && envelope.has("epk")) {
            // v2 sealed envelope: ECDH with OUR static key + the cleartext
            // epk; the true sender comes from ecrecover of the proof inside,
            // rebuilt against OUR address. What does not open is not ours.
            val opened = try {
                bridge.call("dmOpenBatch", JSONObject()
                    .put("items", JSONArray().put(envelope)))
                    .optJSONArray("results")?.takeIf { !it.isNull(0) }?.optJSONObject(0)
            } catch (e: Exception) { null } ?: return
            sender = opened.optString("sender").lowercase().ifEmpty { return }
            data = opened.optJSONObject("message") ?: return
        } else if (envelope.optString("e") == "aes-256-gcm") {
            // v1 pair-key envelope: pre-migration traffic. Its outer
            // Streamr-native layer is gone (P6), and the web can no longer
            // produce this format — dropped by design, not by accident.
            return
        } else {
            // Legacy plaintext payloads (old unsealed file announces) pass
            // through, attributed to the transport publisher — which was the
            // wallet in that era.
            if (envelope.optString("type").isEmpty()) return
            sender = publisherIdRaw?.lowercase()?.ifEmpty { null } ?: return
            data = envelope
        }

        // My own messages are never in my inbox; if one shows up, it is not mine
        // to render from here (the local sent store owns that half).
        if (sender == me) return
        // The block check now necessarily runs AFTER opening — the sender is
        // simply not knowable before. The ~1ms ECDH spent on a blocked peer's
        // message is the documented cost of sealed sender (brief §5.3).
        if (isBlockedPeer(sender)) return
        // Same stamp as the channel ingest (applyAccount): identity for every
        // downstream reader comes from the proof, never from the wire.
        data.put("account", sender)
        data.put("sender", sender)

        // A conversation the user left stays gone until the peer writes again
        // after the tombstone (web: getDMLeftAt).
        val leftAt = dmLeftAt(sender)
        if (leftAt > 0 && data.optLong("timestamp", 0L) <= leftAt) return
        if (leftAt > 0) clearDmLeftAt(sender)

        // Is this peer's conversation the one on screen? Everything below has to
        // know, because _messages and _reactions describe the OPEN channel: work
        // that paints into them for a background DM lands in whatever channel is
        // actually open. That is how a DM's image turned up inside a Streamr
        // channel — the manifest below painted its bubble unconditionally.
        val onScreen = _current.value?.peerAddress?.lowercase() == sender

        when (data.optString("type")) {
            "edit", "delete" -> {
                if (onScreen) applyOverride(data, sender)
                // Off screen, still rewrite the inbox cache: that cache is what
                // the next open merges from, so skipping it is what made a
                // reopened DM flash the pre-edit text.
                else applyOverrideToInboxCache(sender, data.optString("targetId"), data)
                return
            }
            "reaction" -> {
                // Dropped when off screen rather than written into another
                // channel's reaction map. Reopening the DM replays the inbox
                // history through this same router, which restores it.
                if (onScreen) applyReaction(
                    data.optString("messageId"), data.optString("emoji"), sender,
                    data.optString("action") != "remove"
                )
                return
            }
            // Images ride the same chunked transport as channels, just sealed
            // per-payload. Chunks carry no message id, so they are assembled by
            // imageId and never become conversation entries themselves — and
            // assembly must run even off screen, or the bytes are lost.
            "image_chunk" -> { handleImageChunk(data); return }
        }

        val channel = getOrCreateDmConversation(sender, data.optStringOrNull("senderName"))

        // An image manifest must register the chunk assembly, not just become a
        // bubble — otherwise its chunks arrive with nowhere to go and the
        // placeholder spins forever. Off screen we want the registration and
        // NOT the bubble; the bubble is rebuilt from [dmReceived] on open.
        if (data.optString("type") == "image") {
            handleImageManifest(channel, data, showBubble = onScreen)
        }

        val msg = toUiMessage(data, myAddress() ?: return) ?: return

        val list = synchronized(dmReceived) { dmReceived.getOrPut(sender) { mutableListOf() } }
        synchronized(list) {
            if (list.none { it.id == msg.id }) list.add(msg)
        }
        // Paint immediately when this conversation is the one on screen.
        if (onScreen) {
            mergeMessages(listOf(msg))
            // toUiMessage only reads the ENS CACHE — nothing on the live path
            // asked for a resolution, so a message arriving with the cache
            // cold stayed nameless/avatarless until the chat was reopened
            // (the timeline rebuild is where ensureEns used to run). Cheap
            // when cached: the store early-returns fresh entries and dedups
            // in-flight lookups.
            ensureEns(sender)
        } else {
            previewStore.put(
                channel.messageStreamId,
                com.pombo.android.core.LatestMessageStore.Preview(
                    sender = sender,
                    text = msg.text.ifEmpty { if (msg.file != null || msg.storageFile != null) "[file]" else "[image]" },
                    ts = msg.timestamp
                )
            )
        }
    }

    /** Web CONFIG.dm.inboxHistoryCount — replayed through the router on connect. */
    private val inboxHistoryCount = 100

    private suspend fun loadInboxHistory(inbox: String) {
        try {
            val t0 = System.currentTimeMillis()
            val res = bridge.call("resend", JSONObject()
                .put("streamId", inbox)
                .put("partition", StreamConstants.P_MESSAGES)
                .put("last", inboxHistoryCount), 60_000)
            val tResend = System.currentTimeMillis()
            val arr = res.optJSONArray("messages") ?: return
            val n = arr.length()

            // Open the WHOLE page in one bridge call — no pre-filtering by
            // publisher, which is a throwaway key on sealed traffic. Every v2
            // envelope goes in; the ones that open are ours, the ones that
            // don't come back null and are dropped (opening IS the ownership
            // test). One batch instead of 100 sequential evaluateJavascript
            // hops is what keeps the replay fast.
            val publishers = arrayOfNulls<String>(n)
            val items = JSONArray()
            for (i in 0 until n) {
                val entry = arr.optJSONObject(i)
                publishers[i] = entry?.optJSONObject("meta")?.optString("publisherId")
                    ?.lowercase()?.ifEmpty { null }
                val content = entry?.opt("content")
                items.put(
                    if (content is JSONObject && content.optInt("v") == 2 && content.has("epk")) content
                    else JSONObject.NULL
                )
            }
            val opened = try {
                bridge.call("dmOpenBatch", JSONObject().put("items", items), 60_000)
                    .optJSONArray("results")
            } catch (e: Exception) { null }
            val tDecrypt = System.currentTimeMillis()

            for (i in 0 until n) {
                val entry = arr.optJSONObject(i) ?: continue
                val res = opened?.takeIf { !it.isNull(i) }?.optJSONObject(i)
                routeInboxMessage(
                    entry.opt("content"), publishers[i],
                    preSender = res?.optString("sender")?.lowercase()?.ifEmpty { null },
                    preData = res?.optJSONObject("message")
                )
            }
            android.util.Log.d("PomboPerf",
                "inbox replay: resend=${tResend - t0}ms decrypt=${tDecrypt - tResend}ms " +
                    "route=${System.currentTimeMillis() - tDecrypt}ms n=$n")
        } catch (e: Exception) {
            // No storage on the inbox, or the node is down — live traffic still
            // works, the user just starts without back-history.
        }
    }

    /** Finds the DM channel for a peer, creating it on first contact. */
    private fun getOrCreateDmConversation(peer: String, senderName: String?): Channel {
        val inbox = "${peer.lowercase()}/Pombo-DM-1"
        _channels.value.find { it.messageStreamId == inbox }?.let { return it }
        val channel = Channel(
            messageStreamId = inbox,
            ephemeralStreamId = "${peer.lowercase()}/Pombo-DM-2",
            adminStreamId = "",
            name = resolveDmDisplayName(peer, senderName),
            type = "dm",
            joinedAt = System.currentTimeMillis(),
            peerAddress = peer
        )
        _channels.value = _channels.value + channel
        store.save(_channels.value)
        ensureEns(peer)
        return channel
    }

    /**
     * A DM room's FIRST name: ENS → the sender's self-assigned nick (from the
     * first message) → raw short address. The user can rename it locally
     * afterwards, and contact edits keep the room in step separately.
     */
    private fun resolveDmDisplayName(peer: String, fallback: String?): String =
        ensStore.cachedName(peer)
            ?: fallback?.trim()?.take(50)?.ifEmpty { null }
            ?: (peer.take(6) + "…" + peer.takeLast(4))

    /**
     * The web keeps contacts and DM rooms linked (the room is named after the
     * contact at creation); here the link also survives edits — renaming or
     * removing the contact renames the room, and the new name reaches other
     * devices through the channels slice of sync.
     */
    fun renameDmForContact(peerAddress: String, nickname: String?) {
        val peer = peerAddress.lowercase()
        val ch = _channels.value.find { it.type == "dm" && it.peerAddress?.lowercase() == peer } ?: return
        val name = nickname?.trim()?.ifEmpty { null }
            ?: ensStore.cachedName(peer)
            ?: (peerAddress.take(6) + "…" + peerAddress.takeLast(4))
        if (ch.name == name) return
        _channels.value = _channels.value.map {
            if (it.messageStreamId == ch.messageStreamId) it.copy(name = name) else it
        }
        store.save(_channels.value)
        onLocalStateChanged()
        if (_current.value?.messageStreamId == ch.messageStreamId) {
            _current.value = _current.value?.copy(name = name)
        }
    }

    /** Leave tombstones for DMs (web: dmLeftAt slice, synced across devices). */
    private fun dmLeftAt(peer: String): Long =
        store.leftAtJson().optLong("dm:${peer.lowercase()}", 0L)

    private fun clearDmLeftAt(peer: String) {
        val o = store.leftAtJson()
        o.remove("dm:${peer.lowercase()}")
        store.saveLeftAt(o)
        // The slice changed shape — stamp it so this clear survives the merge.
        onSliceTouched("dmLeftAt")
    }

    /**
     * The inbox this session has already subscribed and replayed. Web parity:
     * dm.js subscribeToInbox() returns early when `inboxSubscription` is set, so
     * the 100-message replay + per-peer key registration happen ONCE — not on
     * every DM open, which is what this used to do (prepareChannel calls in on
     * each open, and loadDmTimeline then replayed the inbox a second time).
     *
     * Cleared by passing `force`: the bridge reconnect rebuilds the JS world, so
     * the live subscription is genuinely gone there. An account switch clears
     * itself, since the inbox id is derived from the address.
     */
    @Volatile private var subscribedInboxId: String? = null
    // Volatile too: loadDmTimeline joins it from a different coroutine than the
    // one subscribeMyInbox assigns it on.
    @Volatile private var inboxSubscribeJob: kotlinx.coroutines.Job? = null

    /** Subscribes to my own inbox (incoming DMs from any peer). */
    fun subscribeMyInbox(force: Boolean = false) {
        val me = myAddress()?.lowercase() ?: return
        val inbox = "$me/Pombo-DM-1"
        myInboxId = inbox
        // Already subscribed to exactly this inbox, or still doing it: the
        // in-flight job is the one callers should wait on, not a second replay.
        if (!force && subscribedInboxId == inbox) return
        inboxSubscribeJob?.cancel()
        subscribedInboxId = inbox
        inboxSubscribeJob = scope.launch {
            // No Streamr-layer key registration any more: everything Pombo
            // publishes on DM streams is EncryptionType.NONE, sealed at the
            // app layer, so the SDK has nothing to decrypt (P6). Traffic from
            // pre-migration clients stays SDK-wrapped and never reaches the
            // callback — accepted, there is no key left to add for it.
            //
            // History BEFORE live, mirroring subscribeWithHistory: every past
            // message goes through the same router, which is what populates
            // conversations the user has not opened yet — and creates ones they
            // never started. Doing this per-conversation on open (as this used
            // to) meant a DM from a new peer was simply dropped.
            loadInboxHistory(inbox)
            subscribeQuiet(inbox, StreamConstants.P_MESSAGES)
            // P3 carries channel invites. The web only subscribes it when
            // invite notifications are not muted; muting is a settings toggle
            // we mirror in AppViewModel.
            if (inviteNotificationsEnabled) {
                subscribeQuiet(inbox, StreamConstants.P_NOTIFICATIONS)
                catchUpInvites(inbox)
            }
        }
    }

    /**
     * Replays recent P3 invites from the inbox's storage node — a deliberate
     * improvement over the web, where an invite sent while no tab was open is
     * lost. Every replayed envelope goes through handleNotification, which
     * drops duplicates, answered invites (dismissed ledger) and joined
     * channels, so re-running this on each connect is idempotent.
     */
    private suspend fun catchUpInvites(inbox: String) {
        val res = try {
            // Undecryptable entries cost a full key-request timeout each while
            // the drain-side recovery registers keys and replays, so give this
            // more room than the preview scans get.
            bridge.call("resend", JSONObject()
                .put("streamId", inbox)
                .put("partition", StreamConstants.P_NOTIFICATIONS)
                .put("last", 20)
                .put("budgetMs", 35_000), 45_000)
        } catch (e: Exception) {
            android.util.Log.d("PomboInvites", "catchUp failed: ${e.message}")
            return
        }
        val arr = res.optJSONArray("messages") ?: return
        android.util.Log.d(
            "PomboInvites",
            "catchUp: ${arr.length()} P3 message(s), partial=${res.optBoolean("partial")}"
        )
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val sender = item.optJSONObject("meta")?.optString("publisherId")
                ?.lowercase()?.ifEmpty { null } ?: continue
            handleNotification(item.opt("content"), sender)
        }
    }

    /** Opens (or creates) a DM conversation with a peer address or ENS name. */
    suspend fun startDm(peerAddressRaw: String, localName: String? = null): Channel {
        val input = peerAddressRaw.trim()
        var roomName = localName
        // The input placeholder promises "0x... or name.eth" — resolve ENS
        // first like the web (dm.js:800-812), and let the typed name become
        // the room name when the caller gave none.
        val peer = if (!input.startsWith("0x") && input.contains('.')) {
            val resolved = resolveMemberInput(input)
                ?: throw IllegalStateException("Could not resolve ENS name: $input")
            if (roomName == null) roomName = input
            resolved
        } else input
        require(Regex("^0x[a-fA-F0-9]{40}$").matches(peer)) { "Invalid Ethereum address" }
        // Web: "Cannot send a DM to yourself". Without the guard this created
        // a permanently broken room — the inbox router drops sender == me.
        if (peer.equals(myAddress(), ignoreCase = true)) {
            throw IllegalStateException("Cannot send a DM to yourself")
        }
        val peerInbox = "${peer.lowercase()}/Pombo-DM-1"
        _channels.value.find { it.messageStreamId == peerInbox }?.let { return it }

        // Peer must have published their inbox (with pk) to receive DMs.
        val pk = bridge.call("getPeerPublicKey", JSONObject().put("address", peer)).optString("publicKey")
        if (pk.isEmpty() || pk == "null") throw IllegalStateException("This user hasn't set up DMs yet")
        peerPubKeys[peer.lowercase()] = pk

        val channel = Channel(
            messageStreamId = peerInbox,
            ephemeralStreamId = "${peer.lowercase()}/Pombo-DM-2",
            adminStreamId = "",
            // Web dm.startDM: explicit local name wins, then the ENS name the
            // user typed, then the contact/ENS resolution names the room.
            name = roomName ?: resolveDmDisplayName(peer.lowercase(), null),
            type = "dm",
            joinedAt = System.currentTimeMillis(),
            peerAddress = peer
        )
        addChannel(channel)
        ensureEns(peer)
        return channel
    }

    private suspend fun peerPubKey(address: String): String? {
        val key = address.lowercase()
        peerPubKeys[key]?.let { return it }
        return try {
            val pk = bridge.call("getPeerPublicKey", JSONObject().put("address", address)).optString("publicKey")
            if (pk.isNotEmpty() && pk != "null") { peerPubKeys[key] = pk; pk } else null
        } catch (e: Exception) { null }
    }

    /**
     * Public key of the peer a DM media publish is addressed to. DM media
     * always targets the PEER's inbox ephemeral ("0xpeer/Pombo-DM-2"), so the
     * peer's address is the stream id's owner prefix — the transport gets no
     * channel object, only the stream it was told to publish on.
     */
    private suspend fun dmMediaPeerKey(ephemeralStreamId: String): String? {
        val owner = ephemeralStreamId.substringBefore('/').lowercase()
        if (!owner.startsWith("0x") || owner.length != 42) return null
        return peerPubKey(owner)
    }

    /**
     * Shareable invite link for a channel (web: generateInviteLink).
     * The password travels inside the token for protected channels — that is
     * the web's contract, and it is why the link must be treated as a secret.
     */
    fun inviteLink(channel: Channel): String =
        InviteToken.link(channel.messageStreamId, channel.name, channel.type, channel.password, channel.gateAddress)

    /**
     * Delivers a CHANNEL_INVITE to a peer's DM inbox on P3 (web:
     * notifications.js sendChannelInvite). Requires the peer to have an inbox —
     * without one there is nowhere to deliver, so this fails loudly rather than
     * pretending it was sent.
     */
    suspend fun sendChannelInvite(recipientRaw: String, channel: Channel) {
        // Accept an ENS name as well as a raw address, like the Members panel
        // (resolveMemberInput passes 0x through and forward-resolves names).
        val recipient = resolveMemberInput(recipientRaw.trim())
            ?: throw IllegalStateException("Invalid address or ENS name")
        val sender = myAddress() ?: throw IllegalStateException("No account connected")
        if (recipient.equals(sender, ignoreCase = true)) {
            throw IllegalStateException("You cannot invite yourself")
        }

        val pk = peerPubKey(recipient) ?: throw IllegalStateException(
            "This user has not enabled DMs yet. They need to open Pombo and create their inbox first."
        )

        val invite = JSONObject()
            .put("type", "CHANNEL_INVITE")
            .put("inviteId", Protocol.generateMessageId())
            .put("timestamp", System.currentTimeMillis())
            .put("from", sender)
            .put(
                "channel", JSONObject()
                    .put("streamId", channel.messageStreamId)
                    .put("name", channel.name)
                    .put("type", channel.type)
                    .put("password", channel.password ?: JSONObject.NULL)
            )

        // Sealed sender v2 under a throwaway publisher (web notifications.js):
        // the inner `from` field survives for display, but the receiver treats
        // the proof-recovered sender as the authoritative identity.
        bridge.call("dmSealPublish", JSONObject()
            .put("streamId", "${recipient.lowercase()}/Pombo-DM-1")
            .put("partition", StreamConstants.P_NOTIFICATIONS)
            .put("recipientAddress", recipient.lowercase())
            .put("recipientPublicKey", pk)
            .put("message", invite), 60_000)
    }

    /** Invites that arrived on my inbox P3 and are still unanswered. */
    private val _pendingInvites = MutableStateFlow<List<PendingInvite>>(emptyList())
    val pendingInvites: StateFlow<List<PendingInvite>> = _pendingInvites.asStateFlow()

    data class PendingInvite(
        val inviteId: String,
        val from: String,
        val streamId: String,
        val name: String,
        val type: String,
        val password: String?
    )

    fun dismissInvite(inviteId: String) {
        _pendingInvites.value = _pendingInvites.value.filterNot { it.inviteId == inviteId }
        // Ledger it: the P3 catch-up replays recent invites from storage on
        // every connect, and an answered one must not resurface.
        inviteStore.markDismissed(inviteId)
        persistInvites()
    }

    /** Restores invites received while the app was closed (P3 has no replay). */
    fun reloadInvites() {
        _pendingInvites.value = inviteStore.load().map {
            PendingInvite(it.inviteId, it.from, it.streamId, it.name, it.type, it.password)
        }
    }

    private fun persistInvites() {
        inviteStore.save(_pendingInvites.value.map {
            com.pombo.android.data.InviteStore.StoredInvite(
                it.inviteId, it.from, it.streamId, it.name, it.type, it.password
            )
        })
    }

    /** Opens and files an incoming P3 notification (open first — sealed sender). */
    private suspend fun handleNotification(envelope: Any?, publisherId: String?) {
        if (envelope !is JSONObject) return
        // Sealed sender: the sender is unknown until the envelope is open, so
        // the block check necessarily moved to AFTER the decrypt (web
        // routeNotification does the same). ~1ms of ECDH per blocked invite
        // is the documented cost.
        if (envelope.optInt("v") != 2 || !envelope.has("epk")) {
            // v1 invites are pre-migration traffic whose outer SDK layer is
            // gone (P6) — nothing to open any more.
            return
        }
        val opened = try {
            bridge.call("dmOpenBatch", JSONObject().put("items", JSONArray().put(envelope)))
                .optJSONArray("results")?.takeIf { !it.isNull(0) }?.optJSONObject(0)
        } catch (e: Exception) { null }
        if (opened == null) {
            android.util.Log.d("PomboInvites", "drop invite: envelope did not open (not for us)")
            return
        }
        // Authoritative sender = the proof inside the ciphertext, recovered
        // against OUR address — never the inner `from` (self-claimed) and
        // never the publisher (a throwaway key).
        val senderId = opened.optString("sender").lowercase().ifEmpty { return }
        if (senderId == myAddress()?.lowercase()) return
        if (isBlockedPeer(senderId)) return
        val decrypted = opened.optJSONObject("message") ?: return
        if (decrypted.optString("type") != "CHANNEL_INVITE") return
        val ch = decrypted.optJSONObject("channel") ?: return
        val streamId = ch.optString("streamId").ifEmpty { return }
        val inviteId = decrypted.optString("inviteId").ifEmpty { streamId }
        if (_pendingInvites.value.any { it.inviteId == inviteId }) return
        if (inviteStore.isDismissed(inviteId)) {
            android.util.Log.d("PomboInvites", "drop invite $inviteId: already answered")
            return
        }
        if (_channels.value.any { it.messageStreamId == streamId }) {
            android.util.Log.d("PomboInvites", "drop invite for $streamId: already a member")
            return
        }
        _pendingInvites.value = _pendingInvites.value + PendingInvite(
            inviteId = inviteId,
            from = senderId,
            streamId = streamId,
            name = ch.optString("name").ifEmpty { streamId.substringAfter('/') },
            type = ch.optString("type").ifEmpty { "public" },
            password = if (ch.isNull("password")) null else ch.optString("password").ifEmpty { null }
        )
        persistInvites()
    }

    /**
     * Builds a DM conversation's timeline (web: dmManager.loadDMTimeline).
     *
     * Two halves that live in different places, and that is the whole point:
     *   received — resent from MY inbox off the storage node, filtered to this
     *              peer (the inbox carries every peer's messages)
     *   sent     — read from local storage, because outgoing DMs were published
     *              to the PEER's inbox and I have no read access there
     *
     * Merged by id, sorted by timestamp. Skipping either half loses half the
     * conversation.
     */
    private suspend fun loadDmTimeline(channel: Channel, generation: Int) {
        val peer = channel.peerAddress?.lowercase() ?: return
        val me = myAddress() ?: return

        // The peer's ENS drives the header and chat-list picture. Nothing else
        // in the DM path asks for it (handleText does, but DMs go through
        // toUiMessage), so without this the ENS avatar never appeared.
        ensureEns(peer)

        // Both halves are already in hand: sent from local storage, received
        // from the inbox history the router replayed on connect. No fetching
        // here — that is exactly the web's loadDMTimeline.
        val sent = sentDmStore.load(channel.messageStreamId).mapNotNull { toUiMessage(it, me) }

        // NO resend here. The inbox replay is a once-per-session job owned by
        // [subscribeMyInbox]; everything it routed is already in [dmReceived],
        // and [applyOverrideToInboxCache] keeps edits and deletes applied to
        // that cache while the conversation is off screen. This is exactly the
        // web's loadDMTimeline (dm.js:1069), which reads sent-storage plus the
        // already-routed received messages and touches the network not at all.
        //
        // Opening a DM before the replay has finished (bridge still connecting)
        // waits for that one job rather than firing a second resend of its own.
        // The caller wraps this in INITIAL_HISTORY_SAFETY_MS, so the join is
        // bounded.
        inboxSubscribeJob?.join()
        if (!stillCurrent(generation)) return
        val receivedNow = synchronized(dmReceived) { dmReceived[peer]?.toList() ?: emptyList() }

        val merged = (sent + receivedNow)
            .distinctBy { it.id }
            .sortedBy { it.timestamp }
        if (merged.isNotEmpty()) mergeMessages(merged)

        // Now that the messages exist, fill any image from the local ledger.
        // Order matters: hydrating before the merge would map over a list that
        // does not hold them yet, which is exactly how sent images came back as
        // blank bubbles after a restart.
        merged.filter { it.isImage && it.imageBytes == null }
            .mapNotNull { it.imageId }
            .distinct()
            .forEach { hydrateFromLedger(it) }
        // Own reactions never come back from the peer's inbox either — overlay
        // the local record (web dm.js loadDMTimeline reads sentReactions the
        // same way). The peer's reactions to me replay through the inbox.
        sentReactionsStore?.forStream(channel.messageStreamId)?.let { stored ->
            stored.keys().forEach { messageId ->
                val perMsg = stored.optJSONObject(messageId) ?: return@forEach
                perMsg.keys().forEach { emoji ->
                    val users = perMsg.optJSONArray(emoji) ?: return@forEach
                    for (i in 0 until users.length()) {
                        users.optString(i).ifEmpty { null }
                            ?.let { applyReaction(messageId, emoji, it.lowercase(), true) }
                    }
                }
            }
        }
        if (!stillCurrent(generation)) return
        applyPendingOverrides()
        // Older history is reachable by windowed search even when this first
        // page came back empty — the peer may simply not have written recently.
        _hasMoreHistory.value = true
    }

    /** Web CONFIG.dm.searchWindowMs — one week per pagination step. */
    private val dmSearchWindowMs = 7L * 24 * 3600 * 1000

    data class DmPage(val loaded: Int, val hasMore: Boolean, val noResultsInWindow: Boolean)

    /**
     * One page of older DM history (web: dmManager.fetchOlderDMMessages).
     *
     * Paginates by TIME, not by count, because the inbox is shared across every
     * peer: a `last: N` fetch would be filled by whichever conversation is
     * busiest and could return nothing for this one. A week-long window returns
     * what this peer actually sent in that range.
     *
     * An empty window is not the end of the conversation — it just means they
     * did not write that week. The caller surfaces that as "Search older"
     * rather than silently scanning backwards, which would fire a chain of slow
     * resends the user never asked for.
     */
    suspend fun loadMoreDmHistory(): DmPage {
        val channel = _current.value ?: return DmPage(0, false, false)
        if (channel.type != "dm") return DmPage(0, false, false)
        val peer = channel.peerAddress?.lowercase() ?: return DmPage(0, false, false)
        val me = myAddress()?.lowercase() ?: return DmPage(0, false, false)
        if (_loadingHistory.value) return DmPage(0, _hasMoreHistory.value, false)

        val before = if (oldestTimestamp > 0L) oldestTimestamp else System.currentTimeMillis()
        val generationAtStart = switchGeneration
        _loadingHistory.value = true
        try {
            val res = bridge.call("resendWindow", JSONObject()
                .put("streamId", "$me/Pombo-DM-1")
                .put("partition", StreamConstants.P_MESSAGES)
                .put("before", before)
                .put("windowMs", dmSearchWindowMs)
                .put("budgetMs", 45_000), 60_000)

            if (!stillCurrent(generationAtStart)) return DmPage(0, false, false)

            val arr = res.optJSONArray("messages") ?: JSONArray()
            val hasMore = res.optBoolean("hasMore", false)
            val existing = _messages.value.map { it.id }.toSet()
            val fresh = mutableListOf<UiMessage>()

            // Filtering by publisher no longer works AT ALL — on sealed
            // traffic it is a throwaway key per message. Open EVERYTHING in
            // the window (one batch, ~1ms per envelope) and keep what turns
            // out to be from this peer; the alternative is not knowing who
            // wrote what (brief §5.3).
            val n = arr.length()
            val items = JSONArray()
            for (i in 0 until n) {
                val content = arr.optJSONObject(i)?.opt("content")
                items.put(
                    if (content is JSONObject && content.optInt("v") == 2 && content.has("epk")) content
                    else JSONObject.NULL
                )
            }
            val openedAll = try {
                bridge.call("dmOpenBatch", JSONObject().put("items", items), 60_000)
                    .optJSONArray("results")
            } catch (e: Exception) { null }

            if (!stillCurrent(generationAtStart)) return DmPage(0, false, false)

            for (i in 0 until n) {
                val entry = arr.optJSONObject(i) ?: continue
                val meta = entry.optJSONObject("meta") ?: JSONObject()
                val opened = openedAll?.takeIf { !it.isNull(i) }?.optJSONObject(i)
                val plain: JSONObject
                val sender: String
                if (opened != null) {
                    val s = opened.optString("sender").lowercase()
                    if (s.isEmpty()) continue
                    sender = s
                    plain = opened.optJSONObject("message") ?: continue
                } else {
                    // Legacy plaintext payloads (old unsealed file announces):
                    // identity is the transport publisher, which was the wallet
                    // in that era. v1 envelopes no longer open (P6) and fall
                    // out here too.
                    val envelope = entry.opt("content") as? JSONObject ?: continue
                    if (envelope.optString("type").isEmpty()) continue
                    sender = meta.optString("publisherId").lowercase()
                    plain = envelope
                }
                // My own messages live in local storage, never in my inbox;
                // and this timeline shows one peer only.
                if (sender == me || sender != peer) continue
                meta.optLong("timestamp", 0L).takeIf { it > 0 }?.let { ts ->
                    if (oldestTimestamp == 0L || ts < oldestTimestamp) oldestTimestamp = ts
                }
                plain.put("account", sender)
                plain.put("sender", sender)
                when (plain.optString("type")) {
                    "text", "image", "file_announce", "storage_file_announce" -> {
                        val id = plain.optString("id")
                        if (id.isNotEmpty() && id !in existing) {
                            toUiMessage(plain, me)?.let { fresh.add(it) }
                        }
                    }
                    "edit", "delete" -> applyOverride(plain, peer)
                }
            }

            if (!stillCurrent(generationAtStart)) return DmPage(0, false, false)
            if (fresh.isNotEmpty()) mergeMessages(fresh)
            applyPendingOverrides()
            _hasMoreHistory.value = hasMore
            return DmPage(
                loaded = fresh.size,
                hasMore = hasMore,
                noResultsInWindow = fresh.isEmpty() && hasMore
            )
        } catch (e: Exception) {
            return DmPage(0, _hasMoreHistory.value, false)
        } finally {
            // Same rule as loadMoreHistory: clearing the flag for a channel the
            // user has left would unlock the new channel's pagination early.
            if (stillCurrent(generationAtStart)) _loadingHistory.value = false
        }
    }

    /** Shared conversion for both halves of a DM timeline. */
    private fun toUiMessage(data: JSONObject, me: String): UiMessage? {
        val id = data.optString("id").ifEmpty { return null }
        val sender = data.optString("sender").ifEmpty { return null }
        // An image manifest is a message too; treating it as text is what drew
        // the empty bubble where the picture should be.
        val isImage = data.optString("type") == "image"
        val imageId = data.optString("imageId").ifEmpty { null }
        // A file announce becomes a file bubble. Two shapes reach here: the
        // full manifest (from the peer's inbox) and our own LEAN record —
        // persisted without pieceHashes on purpose (web parity), so
        // FileMetadata.from refuses it and the bubble is rebuilt by hand.
        // downloadFile guards against ever starting a fetch from a lean one.
        val file = if (data.optString("type") == "file_announce") {
            val md = data.optJSONObject("metadata")
            com.pombo.android.core.MediaController.FileMetadata.from(md) ?: run {
                val fid = md?.optString("fileId").orEmpty()
                val fs = md?.optLong("fileSize", 0L) ?: 0L
                if (fid.isEmpty() || fs <= 0) null
                else com.pombo.android.core.MediaController.FileMetadata(
                    fileId = fid,
                    fileName = md!!.optString("fileName").ifEmpty { fid },
                    fileSize = fs,
                    fileType = md.optString("fileType"),
                    pieceCount = md.optInt("pieceCount", 0),
                    pieceHashes = emptyList()
                )
            }
        } else null
        if (data.optString("type") == "file_announce" && file == null) return null
        // A storage-file announce becomes a storage bubble (Persistent File Sharing).
        val storageFile = if (data.optString("type") == "storage_file_announce")
            com.pombo.android.core.StorageMedia.StorageFileMetadata.from(data.optJSONObject("metadata")) else null
        if (data.optString("type") == "storage_file_announce" && storageFile == null) return null
        // NOTE: hydration deliberately does NOT happen here. This runs before
        // the message reaches _messages, so a hydrate started now would map
        // over a list that does not contain it yet and quietly do nothing.
        // Callers hydrate after merging.
        return UiMessage(
            id = id,
            text = data.optString("text"),
            sender = sender,
            senderName = data.optStringOrNull("senderName"),
            timestamp = data.optLong("timestamp", 0L),
            mine = sender.equals(me, ignoreCase = true),
            isImage = isImage,
            imageId = imageId,
            imageMime = if (isImage) data.optString("finalMime", "image/jpeg") else null,
            file = file,
            storageFile = storageFile,
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender),
            edited = data.optBoolean("_edited", false),
            // DMs are authenticated by the ECDH envelope itself: only the two
            // parties can produce one, so there is no per-message badge.
            verified = true
        )
    }

    /**
     * Shares a video over the P2P swarm (web media.js sendVideo): only the
     * type check is video-specific — everything downstream is [sendFile].
     */
    suspend fun sendVideo(
        fileName: String,
        fileSize: Long,
        mimeType: String,
        openInput: () -> java.io.InputStream
    ) {
        if (mimeType !in com.pombo.android.core.MediaConfig.ALLOWED_VIDEO_TYPES) {
            throw IllegalArgumentException(
                "Invalid video type. Allowed: " +
                    com.pombo.android.core.MediaConfig.ALLOWED_VIDEO_TYPES.joinToString(", ")
            )
        }
        sendFile(fileName, fileSize, mimeType, openInput)
    }

    /**
     * Shares a file in the open channel over the P2P swarm — the Android side
     * of web media.js sendFile: hash piece by piece, sign the manifest (the
     * hashes live INSIDE the signature), announce on -1/P0, then seed.
     *
     * The source is streamed in PIECE_SIZE slices straight into a [PieceStore]
     * (the same sparse file a download would build), so a 500MB send never
     * holds the file in memory and the serving path needs no second read path.
     */
    suspend fun sendFile(
        fileName: String,
        fileSize: Long,
        mimeType: String,
        openInput: () -> java.io.InputStream
    ) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (fileSize <= 0) throw IllegalArgumentException("File is empty")
        if (fileSize > com.pombo.android.core.MediaConfig.MAX_FILE_SIZE)
            throw IllegalArgumentException("File exceeds the 500MB limit")
        val sender = myAddress() ?: throw IllegalStateException("No identity")
        val isDm = channel.type == "dm"
        // DM: resolve the pair's key BEFORE hashing — failing after a 500MB
        // hashing pass over a missing public key would waste all of it.
        val dmPeerPk = if (isDm) {
            val peer = channel.peerAddress ?: throw IllegalStateException("DM has no peer")
            peerPubKey(peer) ?: throw IllegalStateException("Peer public key unavailable")
        } else null

        // The wire requires exactly 36 UTF-8 bytes — a random UUID is exactly that.
        val fileId = java.util.UUID.randomUUID().toString()
        val store = withContext(Dispatchers.IO) {
            com.pombo.android.core.PieceStore.open(transferDir, fileId, fileSize)
        }
        val pieceHashes = ArrayList<String>(com.pombo.android.core.MediaConfig.pieceCount(fileSize))
        try {
            withContext(Dispatchers.IO) {
                openInput().use { input ->
                    val buf = ByteArray(com.pombo.android.core.MediaConfig.PIECE_SIZE)
                    var index = 0
                    var total = 0L
                    while (total < fileSize) {
                        // A content stream may return short reads; a piece
                        // boundary must never move because of one.
                        val want = minOf(buf.size.toLong(), fileSize - total).toInt()
                        var read = 0
                        while (read < want) {
                            val n = input.read(buf, read, want - read)
                            if (n < 0) break
                            read += n
                        }
                        if (read != want) throw java.io.IOException(
                            "file shrank while reading: got $read of $want at piece $index"
                        )
                        val piece = buf.copyOf(read)
                        val hash = com.pombo.android.core.PieceStore.sha256Hex(piece)
                        if (!store.writePiece(index, piece, hash)) {
                            throw java.io.IOException("piece $index write failed")
                        }
                        pieceHashes.add(hash)
                        total += read
                        index++
                    }
                }
            }
        } catch (e: Exception) {
            store.close()
            com.pombo.android.core.PieceStore.delete(transferDir, fileId)
            throw e
        }

        val id = Protocol.generateMessageId()
        val timestamp = System.currentTimeMillis()
        // Unsigned (D6) — identity comes from the proof/envelope; the
        // canonical builders in Protocol.kt survive only to verify history.
        val metadataJson = JSONObject()
            .put("fileId", fileId).put("fileName", fileName)
            .put("fileSize", fileSize).put("fileType", mimeType)
            .put("pieceCount", pieceHashes.size)
            .put("pieceHashes", org.json.JSONArray(pieceHashes))
        val announce = JSONObject()
            .put("type", "file_announce").put("v", 2).put("id", id)
            .put("sender", sender).put("senderName", myUsername() ?: JSONObject.NULL)
            .put("timestamp", timestamp)
            .put("metadata", metadataJson)
            .put("replyTo", JSONObject.NULL)

        val metadata = com.pombo.android.core.MediaController.FileMetadata(
            fileId = fileId, fileName = fileName, fileSize = fileSize,
            fileType = mimeType, pieceCount = pieceHashes.size, pieceHashes = pieceHashes
        )
        // Local echo before the publish, like every other send path.
        mergeMessages(listOf(UiMessage(
            id = id, text = "", sender = sender, senderName = myUsername(),
            timestamp = timestamp, mine = true, pending = true, verified = true,
            file = metadata,
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender)
        )))
        try {
            if (isDm) {
                // Same wire as web DM announces: sealed-sender v2 to the
                // peer's inbox, one throwaway publisher per message.
                val peer = channel.peerAddress
                    ?: throw IllegalStateException("DM conversation has no peer")
                publishTextWithRetry {
                    publishContent(
                        channel.messageStreamId, StreamConstants.P_MESSAGES,
                        announce, password = null, dmPeer = peer
                    )
                }
            } else {
                publishForChannel(channel, channel.messageStreamId, StreamConstants.P_MESSAGES, announce)
            }
        } catch (e: Exception) {
            // The seed store stays on disk: re-sending re-uses nothing today,
            // but a failed announce must not strand a bubble that looks sent.
            _messages.value = _messages.value.filterNot { it.id == id }
            store.close()
            com.pombo.android.core.PieceStore.delete(transferDir, fileId)
            throw e
        }
        confirmMessage(id)
        if (isDm) {
            // Web parity (media.js sendFile DM branch): persist the manifest
            // LEAN — without pieceHashes (a 500MB file's hashes are ~160KB in
            // one sync message) and with the signature dropped alongside them
            // (it covers the hashes, so kept it could never verify again).
            val leanMeta = JSONObject()
                .put("fileId", fileId).put("fileName", fileName)
                .put("fileSize", fileSize).put("fileType", mimeType)
                .put("pieceCount", pieceHashes.size)
            val lean = JSONObject(announce.toString())
                .put("metadata", leanMeta)
                .put("signature", JSONObject.NULL)
            sentDmStore.add(channel.messageStreamId, lean)
            onLocalStateChanged()
        }
        media.seedSentFile(
            metadata = metadata,
            messageStreamId = channel.messageStreamId,
            isDm = isDm,
            password = channel.password,
            store = store
        )
    }

    /** Sends an E2E-encrypted DM to the peer's inbox (partition 0). */
    private suspend fun sendDm(channel: Channel, text: String) {
        val peer = channel.peerAddress ?: return
        val sender = myAddress() ?: return
        val id = Protocol.generateMessageId()
        val timestamp = System.currentTimeMillis()
        // Seed own ENS from the cache like every received bubble does — the
        // sender's own name/avatar were the one case nothing ever filled in
        // live (the timeline rebuild on reopen was what fixed them).
        mergeMessages(listOf(UiMessage(
            id, text, sender, myUsername(), timestamp,
            mine = true, pending = true, verified = true,
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender)
        )))

        // Wire message, post-D6: no app-layer signature, no `sender`, no
        // `channelId`. Identity travels as the proof inside the sealed
        // envelope (`p`, added by the bridge); a signature here would be a
        // third authority covering the same claim, and `sender` in the
        // plaintext could name someone the proof doesn't vouch for.
        val wire = JSONObject()
            .put("type", "text").put("id", id).put("text", text)
            .put("senderName", myUsername() ?: JSONObject.NULL)
            .put("timestamp", timestamp).put("replyTo", JSONObject.NULL)

        publishTextWithRetry {
            publishContent(
                channel.messageStreamId, StreamConstants.P_MESSAGES,
                wire, password = null, dmPeer = peer
            )
        }
        confirmMessage(id)
        // Persist only after the publish succeeded, so a failed send does not
        // leave a message in the history that the peer never received. This is
        // the only copy we will ever have of it — and it keeps `sender`,
        // which the timeline's toUiMessage needs; the field never travels.
        sentDmStore.add(channel.messageStreamId, JSONObject(wire.toString()).put("sender", sender))
        onLocalStateChanged()
        // The DM branch returns out of sendMessage BEFORE its wake-signal
        // call, so no Android DM ever woke the peer's push relay. Web dm.js
        // fires the same signal after the publish: 'channel:' tag over the
        // peer's inbox, which is what their relay row is registered under.
        sendWakeSignal(channel)
    }

    /**
     * A peer's DM typing/presence control (sealed-sender v2 envelope on my
     * `-2`). Opens it with our static key, identifies the peer from the proof,
     * and feeds it into the same presence/typing state channels use, so the
     * DM header's "Online/Offline" and the typing indicator behave identically.
     */
    private fun handleDmControl(contentRaw: String, meta: JSONObject) {
        val envelope = try { JSONObject(contentRaw) } catch (e: Exception) { return }
        if (envelope.optInt("v") != 2 || !envelope.has("epk")) return  // v1: gone with P6
        scope.launch {
            // Open FIRST — the publisher is a throwaway key, so every check
            // (own echo, blocked, left) can only run on the sender the proof
            // recovers to (web routeInboxControl does the same).
            val opened = try {
                bridge.call("dmOpenBatch", JSONObject()
                    .put("items", JSONArray().put(envelope)))
                    .optJSONArray("results")?.takeIf { !it.isNull(0) }?.optJSONObject(0)
            } catch (e: Exception) { null } ?: return@launch
            val sender = opened.optString("sender").lowercase().ifEmpty { return@launch }
            if (sender == myAddress()?.lowercase()) return@launch   // my own echo
            if (isBlockedPeer(sender)) return@launch
            if (dmLeftAt(sender) > 0) return@launch
            val plain = opened.optJSONObject("message") ?: return@launch
            // Only react while THIS peer's DM is the open channel — a stray
            // heartbeat must not light up another conversation.
            if (_current.value?.peerAddress?.lowercase() != sender) return@launch
            when (plain.optString("type")) {
                "presence" -> synchronized(online) {
                    online[sender] = plain.optLong("lastActive", System.currentTimeMillis())
                    onlineNames[sender] = plain.optStringOrNull("nickname")
                    _onlineCount.value = online.size
                    _onlineUsers.value = online.keys.map { OnlineUser(it, onlineNames[it]) }.sortedBy { it.address }
                }
                "typing" -> markTyping(sender, plain.optStringOrNull("nickname"))
            }
        }
    }

    /** Feeds a live inbox arrival through the same router as history. */
    private fun handleIncomingDm(contentRaw: String, meta: JSONObject) {
        val publisherId = meta.optString("publisherId").lowercase().ifEmpty { return }
        val envelope = try { JSONObject(contentRaw) } catch (e: Exception) { return }
        // Sealed envelopes and legacy plaintext payloads (old unsealed file
        // announces) both route; everything else is noise. The router owns
        // the open-first inversion.
        if (envelope.optString("e") != "aes-256-gcm" && envelope.optString("type").isEmpty()) return
        scope.launch { routeInboxMessage(envelope, publisherId) }
    }

    private suspend fun verifyPasswordChallenge(adminStreamId: String, password: String) {
        var lastError: Exception? = null
        repeat(4) { attempt ->
            try {
                val res = bridge.call("resend", JSONObject()
                    .put("streamId", adminStreamId)
                    .put("partition", StreamConstants.ADMIN_PASSWORD_CHALLENGE)
                    .put("last", 1))
                val arr = res.optJSONArray("messages") ?: JSONArray()
                if (arr.length() == 0) throw ChallengeNotFound()
                val content = arr.getJSONObject(arr.length() - 1).get("content")
                if (content !is String) throw WrongPassword()
                val decoded = try {
                    JSONObject(PomboCrypto.decryptString(content, password))
                } catch (e: Exception) {
                    throw WrongPassword()
                }
                if (decoded.optString("type") != "PASSWORD_CHALLENGE" ||
                    decoded.optString("magic") != StreamConstants.PASSWORD_CHALLENGE_MAGIC
                ) throw WrongPassword()
                return  // valid
            } catch (e: WrongPassword) {
                throw IllegalStateException("Incorrect password for this channel")
            } catch (e: ChallengeNotFound) {
                lastError = e
                delay(1500)
            } catch (e: Exception) {
                lastError = e
                delay(1500)
            }
        }
        throw IllegalStateException("Could not verify the channel password (${lastError?.message ?: "challenge not found"})")
    }

    private class WrongPassword : Exception()
    private class ChallengeNotFound : Exception()

    /** Non-throwing challenge probe result (web verifyPasswordChallenge's
     *  `{found, valid, ts}` — streamr.js). `ts` is the payload timestamp of
     *  the retained challenge, 0 when absent/undecryptable. */
    private data class ChallengeProbe(val found: Boolean, val valid: Boolean, val ts: Long)

    /**
     * Single-shot, non-throwing variant of [verifyPasswordChallenge] for the
     * TTL republish check on owner open (docs/TTL_REPUBLISH_PLAN.md). The
     * join flow keeps the strict throwing wrapper (fail-closed, retries);
     * this one only answers "is a valid challenge retained, and how old?".
     */
    private suspend fun probePasswordChallenge(adminStreamId: String, password: String): ChallengeProbe {
        return try {
            val res = bridge.call("resend", JSONObject()
                .put("streamId", adminStreamId)
                .put("partition", StreamConstants.ADMIN_PASSWORD_CHALLENGE)
                .put("last", 1), 30_000)
            val arr = res.optJSONArray("messages") ?: JSONArray()
            if (arr.length() == 0) return ChallengeProbe(found = false, valid = false, ts = 0L)
            val content = arr.getJSONObject(arr.length() - 1).opt("content")
            // Challenge is always published encrypted — a plaintext entry
            // cannot prove password knowledge and counts as invalid.
            if (content !is String) return ChallengeProbe(found = true, valid = false, ts = 0L)
            val decoded = try {
                JSONObject(PomboCrypto.decryptString(content, password))
            } catch (e: Exception) {
                return ChallengeProbe(found = true, valid = false, ts = 0L)
            }
            val ok = decoded.optString("type") == "PASSWORD_CHALLENGE" &&
                decoded.optString("magic") == StreamConstants.PASSWORD_CHALLENGE_MAGIC
            ChallengeProbe(found = true, valid = ok, ts = if (ok) decoded.optLong("ts", 0L) else 0L)
        } catch (e: Exception) {
            // Infra failure counts as "not retained", like the web (its
            // verifyPasswordChallenge swallows resend errors into found:false
            // and the caller republishes). The challenge publish is idempotent
            // and joiners fail closed on a missing one — erring towards a
            // redundant publish is the safe side.
            Log.d(TAG, "probePasswordChallenge resend failed (treating as not retained): ${e.message}")
            ChallengeProbe(found = false, valid = false, ts = 0L)
        }
    }

    fun openChannel(messageStreamId: String) {
        val channel = _channels.value.find { it.messageStreamId == messageStreamId } ?: return
        unreadStore.clear(messageStreamId)
        openInternal(channel, preview = false)
    }

    /**
     * Opens a public channel WITHOUT adding it to the user's list (web:
     * PreviewModeUI). Everything else behaves like a normal open — history,
     * moderation and presence — so the only difference is persistence and the
     * Join affordance in the header.
     */
    fun previewChannel(preview: ExploreChannel) {
        val messageStreamId = preview.messageStreamId
        _channels.value.find { it.messageStreamId == messageStreamId }?.let {
            openChannel(messageStreamId)
            return
        }
        // Gated (TOKEN/NFT holder browsing before committing): the preview
        // channel must carry the FULL gated context — the gate drives the
        // clone transport/subscribe, and the -4 is where the epoch keys come
        // from. Without them the open dies on the SDK's subscribe guard.
        val gated = preview.type == "gated" && preview.gateAddress != null
        openInternal(
            Channel(
                messageStreamId = messageStreamId,
                ephemeralStreamId = StreamConstants.deriveEphemeralId(messageStreamId),
                adminStreamId = StreamConstants.deriveAdminId(messageStreamId),
                keysStreamId = if (gated) StreamConstants.deriveKeysId(messageStreamId) else "",
                name = preview.name.ifEmpty { messageStreamId.substringAfterLast('/') },
                type = preview.type.ifEmpty { "public" },
                gateAddress = if (gated) preview.gateAddress else null,
                // Carry the Explore metadata so the settings sheet isn't blank —
                // anything listed in Explore is by definition exposure=visible.
                description = preview.description,
                language = preview.language,
                category = preview.category,
                exposure = "visible",
                createdBy = messageStreamId.substringBefore('/')
            ),
            preview = true
        )
    }

    /** Gate mode for an arbitrary clone (Explore tap routing); null = unreadable. */
    suspend fun gateModeOf(gateAddress: String): Int? = try {
        bridge.call("gateInfo", JSONObject().put("gate", gateAddress))
            .optInt("mode", -1).takeIf { it >= 0 }
    } catch (e: Exception) { null }

    /** Whether OUR account passes a gate right now (cached, fail-closed). */
    suspend fun gateAccessSelf(gateAddress: String): Boolean {
        val me = myAddress() ?: return false
        return try {
            bridge.call("gateCheckAccess", JSONObject()
                .put("gate", gateAddress).put("user", me)).optBoolean("access", false)
        } catch (e: Exception) { false }
    }

    /**
     * Turns the open preview into a real channel (web: addPreviewToList).
     * Goes through the normal join so permissions, type and metadata are
     * resolved exactly as they would be from the Join dialog.
     */
    suspend fun joinPreview(): Channel? {
        val preview = _current.value?.takeIf { _isPreview.value } ?: return null
        val joined = joinChannel(preview.messageStreamId)
        // The streams are already subscribed — just swap in the stored channel.
        _current.value = joined
        _isPreview.value = false
        return joined
    }

    /**
     * Fills in stream ids a stored channel is missing. Entries persisted by
     * older builds can carry an empty adminStreamId, and every admin read is
     * wrapped in a try/catch — so moderation just silently never applied and
     * hidden messages stayed visible on that device while a freshly opened
     * copy of the same channel looked correct. The web never had this: it
     * derives on every use (`channel.adminStreamId || deriveAdminId(...)`).
     */
    private fun healStreamIds(channel: Channel): Channel {
        if (channel.type == "dm") return channel
        var healed = channel
        if (healed.adminStreamId.isEmpty()) {
            healed = healed.copy(adminStreamId = StreamConstants.deriveAdminId(healed.messageStreamId))
        }
        if (healed.ephemeralStreamId.isEmpty()) {
            healed = healed.copy(
                ephemeralStreamId = healed.messageStreamId
                    .replace(Regex("-1$"), StreamConstants.SUFFIX_EPHEMERAL)
            )
        }
        if (healed.keysStreamId.isEmpty() && (healed.type == "native" || healed.type == "gated")) {
            healed = healed.copy(keysStreamId = StreamConstants.deriveKeysId(healed.messageStreamId))
        }
        if (healed.type == "gated" && healed.gateAddress == null) {
            // Fire-and-forget repair from the stream's on-chain metadata (g).
            // Publishes on this channel fail loudly until it lands.
            val sid = healed.messageStreamId
            scope.launch {
                try {
                    val info = bridge.call("getStreamInfo", JSONObject().put("streamId", sid))
                    val desc = info.optJSONObject("metadata")?.optString("description") ?: ""
                    val g = JSONObject(desc).optString("g").lowercase()
                        .takeIf { Regex("^0x[0-9a-f]{40}$").matches(it) } ?: return@launch
                    _channels.value = _channels.value.map {
                        if (it.messageStreamId == sid) it.copy(gateAddress = g) else it
                    }
                    store.save(_channels.value)
                    if (_current.value?.messageStreamId == sid) {
                        _current.value = _channels.value.find { it.messageStreamId == sid }
                    }
                    Log.i(TAG, "Gate address repaired from metadata: " + g)
                } catch (e: Exception) {
                    Log.w(TAG, "Gate repair failed: ${e.message}")
                }
            }
        }
        if (healed !== channel) {
            _channels.value = _channels.value.map {
                if (it.messageStreamId == healed.messageStreamId) healed else it
            }
            store.save(_channels.value)
        }
        return healed
    }

    private fun openInternal(channelIn: Channel, preview: Boolean) {
        val channel = healStreamIds(channelIn)
        // Paint the cached image synchronously, before any coroutine or network
        // work — otherwise the header waits behind the history resend.
        _channelImage.value = imageStore.cached(channel.adminStreamId)
        // Stop the previous open before starting this one. Without this, both
        // coroutines stayed live on the shared viewModelScope and the loser
        // kept writing into the winner's state.
        openJob?.cancel()
        openJob = scope.launch {
            // The transition — teardown, state reset, subscribe — is serialised
            // (see [prepareChannel]). The history loads below run OUTSIDE that
            // lock on purpose: they can take 30s and must never hold up the next
            // channel switch.
            val generation = prepareChannel(channel, preview)
            if (!stillCurrent(generation)) return@launch
            // Bring back any in-progress upload bubble for this channel before the
            // history loads merge around it (the upload survived the switch).
            restoreStorageUploadBubbles(channel)

            if (channel.type == "dm") {
                _initialLoad.value = true
                withTimeoutOrNull(INITIAL_HISTORY_SAFETY_MS) { loadDmTimeline(channel, generation) }
                if (!stillCurrent(generation)) return@launch
                _initialLoad.value = false
                // Publish my presence heartbeat (encrypted) to the peer's `-2`,
                // same as a channel — this is how the peer sees me online and how
                // "Online/Offline" flips in their header.
                startPresence(channel)
            } else if (!channel.writeOnly) {
                // History: content + overrides
                // Image refresh runs alongside history instead of after it —
                // the cached one is already on screen.
                loadChannelImage(channel)
                // Gate the render until history AND its overrides are applied.
                _initialLoad.value = true
                // Safety net, mirroring the web's INITIAL_HISTORY_SAFETY_MS: a
                // resend against an empty or legacy stream does not reliably
                // signal completion, and waiting on the 60s bridge timeout
                // strands the user on the spinner. Release the gate after 30s
                // and let the loads finish in the background — they publish
                // into the same flows, so late history still lands.
                // Admin state (-3) runs ALONGSIDE the history resends, not after
                // them: it is an independent stream and chaining it made the
                // gate pay a third round trip in series.
                //
                // It stays INSIDE the gate on purpose. The chat filters on
                // `hiddenIds`/`bannedMembers` at render time, so opening the
                // gate before -3 has landed would flash moderated messages for
                // as long as that resend takes. The web accepts that window; we
                // do not, and the cost of closing it is now zero because the
                // fetch overlaps the history anyway.
                // Epoch keys BEFORE the -1 history pull, so the envelopes the
                // resend returns can already be opened (bootstrap as admin, or
                // request as member). FAST PATH: with persisted keys+announces
                // the channel decrypts immediately — the -4 resend leaves the
                // open's critical path and becomes a background reconcile
                // (measured: it added ~0.5-2s to every warm open for nothing).
                if (isEpochChannel(channel) && channel.keysStreamId.isNotEmpty()) {
                    epochKeys.loadPersistedState(channel.messageStreamId)
                    if (epochKeys.hasCurrentKey(channel.messageStreamId)) {
                        android.util.Log.d("PomboPerf", "epochKeys ${channel.name}: warm (persisted), reconcile in background")
                        launch {
                            // After the open's own resends: the WebView JS
                            // thread is single — a concurrent -4 drain here
                            // pushed the P0 history call into the seconds.
                            delay(8_000)
                            if (!stillCurrent(generation)) return@launch
                            try {
                                epochKeys.ensureChannelKeys(
                                    channel.messageStreamId, channel.keysStreamId,
                                    channel.storageDays ?: 180,
                                    allowMint = System.currentTimeMillis() - channel.createdAt < 3_600_000,
                                    memberCount = channel.members.size,
                                    gated = channel.type == "gated")
                            } catch (e: Exception) {
                                Log.d(TAG, "Background epoch reconcile failed: ${e.message}")
                            }
                        }
                    } else {
                        val tEnsure = System.currentTimeMillis()
                        try {
                            epochKeys.ensureChannelKeys(
                                channel.messageStreamId, channel.keysStreamId,
                                channel.storageDays ?: 180,
                                    allowMint = System.currentTimeMillis() - channel.createdAt < 3_600_000,
                                    memberCount = channel.members.size,
                                    gated = channel.type == "gated")
                        } catch (e: Exception) {
                            Log.w(TAG, "Epoch key setup failed (messages will wait for key): ${e.message}")
                        }
                        android.util.Log.d("PomboPerf",
                            "epochKeys ${channel.name}: cold ensure=${System.currentTimeMillis() - tEnsure}ms")
                        _waitingForKeys.value = !epochKeys.hasCurrentKey(channel.messageStreamId)
                        // A first KEY_REQUEST from a cold node can miss every
                        // live subscriber (§7.2 R2) — waiting the full backoff
                        // turned that into a 60s blank channel. Retry fast
                        // while the topology warms; stop as soon as keys land.
                        launch {
                            repeat(com.pombo.android.core.EpochKeyManager.RETRY_LOOP_ATTEMPTS) {
                                delay(10_000)
                                if (!stillCurrent(generation)) return@launch
                                if (epochKeys.hasCurrentKey(channel.messageStreamId)) return@launch
                                try {
                                    epochKeys.retryRequestIfWaiting(channel.messageStreamId, channel.keysStreamId)
                                } catch (e: Exception) { /* next lap retries */ }
                            }
                        }
                    }
                    if (!stillCurrent(generation)) return@launch
                }
                val loads = launch {
                    listOf(
                        launch { loadHistory(channel, generation) },
                        launch { loadAdminState(channel, generation) }
                    ).joinAll()
                }
                withTimeoutOrNull(INITIAL_HISTORY_SAFETY_MS) { loads.join() }
                // Releasing the loading gate and starting presence for a channel
                // the user has left is how A's heartbeat used to replace B's —
                // startPresence cancels presenceJob unconditionally.
                if (!stillCurrent(generation)) return@launch
                _initialLoad.value = false
                startPresence(channel)
                // Re-announce anything we can still serve here. A seeder that
                // says nothing is invisible, so a file this device holds would
                // look dead to anyone who arrived while we were away.
                launch {
                    try {
                        // Rehydrate first: seeds finished in a PREVIOUS session
                        // only exist on disk until this runs, and this is the
                        // one moment we hold the channel's password (the
                        // registry deliberately does not store it).
                        withContext(Dispatchers.IO) {
                            media.restoreSeedsFor(channel.messageStreamId, channel.password)
                        }
                        media.reannounceForChannel(channel.messageStreamId, channel.password)
                    } catch (e: Exception) {
                        Log.w(TAG, "seed re-announce failed: ${e.message}")
                    }
                }
                // Moderation convergence (web adminStatePoller, 30s): -3 has
                // no live subscription, so after the on-open load this poller
                // is what catches anything the admin_invalidate signal missed.
                startAdminPoller(channel, generation)
                // TTL-aware owner republish of the -3 artifacts (web
                // _ttlRepublishOnOpen; docs/TTL_REPUBLISH_PLAN.md). Runs after
                // loadAdminState so adminRevs/adminTs and the moderation flows
                // are populated. Fire-and-forget: must never hold the open.
                launch {
                    try { ttlRepublishOnOpen(channel, generation) } catch (e: Exception) {
                        Log.d(TAG, "TTL republish check failed (will retry next open): ${e.message}")
                    }
                }
            }
            // Presence is live (or, for a write-only channel, will never be):
            // either way the header can stop saying "Connecting…" and show the
            // count. Set outside the branches so no channel type is stranded.
            if (stillCurrent(generation)) _presenceReady.value = true
        }
    }

    /**
     * Teardown + state reset + subscribe, run under [channelSwitchMutex] so a
     * close that is still unwinding cannot interleave with the next open.
     *
     * Without the lock the unsubscribes of the channel being left could resume
     * *after* the next channel had already subscribed — same stream ids, so the
     * close silently tore down the new channel's subscriptions and left it on
     * screen receiving nothing.
     *
     * @return the viewing session this open owns; callers must pass it to every
     *   async loader and re-check it with [stillCurrent] after each suspension.
     */
    private suspend fun prepareChannel(channel: Channel, preview: Boolean): Int =
        channelSwitchMutex.withLock {
            closeCurrentInternal()
            _current.value = channel
            _isPreview.value = preview
            // Web preloadDeletePermission: fire-and-forget so the sheet and the
            // context menu have an answer before the user asks for one.
            refreshModerationPermission(channel, preview)
            _messages.value = emptyList()
            _reactions.value = emptyMap()
            _pins.value = emptyList()
            _hiddenIds.value = emptySet()
            _bannedMembers.value = emptySet()
            val generation = ++switchGeneration
            oldestTimestamp = 0L
            synchronized(this) { pendingOverrides.clear(); deletedIds.clear() }
            _hasMoreHistory.value = false
            _loadingHistory.value = false
            channelImageRev = 0
            synchronized(online) { online.clear(); onlineNames.clear() }
            clearTyping()   // whoever was typing was typing in the OTHER room
            _onlineUsers.value = emptyList()
            _onlineCount.value = 0
            _presenceReady.value = false

            if (channel.type == "dm") {
                // DMs arrive on MY inbox (subscribed globally), not the peer's inbox.
                subscribeMyInbox()
                // Typing and presence for a DM are ECDH-encrypted control on the
                // ephemeral inbox pair: I publish to the PEER's `-2` and receive
                // on MY OWN `-2` (web dmManager.subscribeDMEphemeral). Subscribe
                // it on open, tear it down on close.
                val myEph = "${myAddress()?.lowercase()}/Pombo-DM-2"
                myDmEphemeralId = myEph
                subscribeQuiet(myEph, StreamConstants.EPH_CONTROL)
            } else if (!channel.writeOnly) {
                // Real-time first (do not miss messages during the resend).
                // The admin stream (-3) is deliberately NOT subscribed live —
                // web model: every subscribed partition is its own overlay
                // topology with its own peer connections, and moderation has
                // two cheaper paths that share what is already open: the
                // admin_invalidate snapshot on -2/P0 (instant) and the 30s
                // poller, which resends straight from the storage node.
                val tSub = System.currentTimeMillis()
                subscribeQuiet(channel.messageStreamId, StreamConstants.P_MESSAGES)
                subscribeQuiet(channel.messageStreamId, StreamConstants.P_CONTROL)
                subscribeQuiet(channel.ephemeralStreamId, StreamConstants.EPH_CONTROL)
                // Keys stream (-4): live epoch-key protocol for native channels
                if (isEpochChannel(channel) && channel.keysStreamId.isNotEmpty()) {
                    subscribeQuiet(channel.keysStreamId, StreamConstants.P_KEY_EXCHANGE)
                }
                android.util.Log.d("PomboPerf",
                    "subscribes ${channel.name}: ${System.currentTimeMillis() - tSub}ms")
                // Cancelled mid-transition: undo our own subscribes, since the
                // next open's teardown has already run and will not cover them.
                if (!stillCurrent(generation)) unsubscribeAll(channel)
            }
            generation
        }

    fun closeCurrent() { scope.launch { channelSwitchMutex.withLock { closeCurrentInternal() } } }

    private suspend fun closeCurrentInternal() {
        val channel = _current.value ?: return
        // Closing ends the viewing session too, so work in flight for this
        // channel cannot land after we reopen the very same channel.
        val generation = ++switchGeneration
        presenceJob?.cancel(); presenceJob = null
        adminPollJob?.cancel(); adminPollJob = null
        _current.value = null
        _isPreview.value = false
        unsubscribeAll(channel)
        // On-demand DM ephemeral (my `-2`): drop it when the DM closes.
        //
        // The media partitions need a DIFFERENT question than the channel case
        // in [unsubscribeAll]. There is exactly ONE DM ephemeral stream per
        // user, shared by every conversation, so asking "is this conversation
        // idle?" would tear it down and cut seeding for all the others at once.
        // Hence hasActiveDMTransfers(), which spans conversations.
        //
        // When they do survive, releasing them later is the transfer engine's
        // job — it holds the last-transfer-finished moment. The id is derivable
        // from our own address, so nothing is lost by clearing the field here.
        myDmEphemeralId?.let { eph ->
            unsubscribeQuiet(eph, StreamConstants.EPH_CONTROL)
            if (media.hasActiveDMTransfers()) {
                Log.i(TAG, "keeping DM media partitions alive for $eph")
            } else {
                media.releaseMediaPartitions(eph)
            }
            myDmEphemeralId = null
        }
        // Only wipe if nothing has opened in the meantime. The unsubscribes
        // above suspend, so a close started by the back button could otherwise
        // resume after the next channel had already loaded and blank it.
        if (!stillCurrent(generation)) return
        _messages.value = emptyList()
        _reactions.value = emptyMap()
    }

    /**
     * Leaves a channel's streams behind.
     *
     * The media partitions (-2/P1, P2) survive when the channel still has a
     * download or a seed in flight — mirroring web subscriptionManager's
     * downgradeToBackground. Navigating away is not the same as stopping: on
     * the web an upload keeps serving while the user reads another channel, and
     * without this exception the leecher on the far side just stalls.
     *
     * -1 and -2/P0 (presence, typing) always go: those are only meaningful for
     * the channel on screen.
     */
    private suspend fun unsubscribeAll(channel: Channel) {
        unsubscribeQuiet(channel.messageStreamId, StreamConstants.P_MESSAGES)
        unsubscribeQuiet(channel.messageStreamId, StreamConstants.P_CONTROL)
        unsubscribeQuiet(channel.ephemeralStreamId, StreamConstants.EPH_CONTROL)
        if (isEpochChannel(channel) && channel.keysStreamId.isNotEmpty()) {
            unsubscribeQuiet(channel.keysStreamId, StreamConstants.P_KEY_EXCHANGE)
        }
        if (media.hasActiveTransfers(channel.messageStreamId)) {
            Log.i(TAG, "keeping media partitions alive for ${channel.messageStreamId}")
        } else {
            media.releaseMediaPartitions(channel.ephemeralStreamId)
        }
    }

    /**
     * Re-activates subscriptions after a bridge reconnect.
     *
     * A reconnect rebuilds the JS world, so every subscription is gone — including
     * media partitions that no channel switch ever asked to be dropped. Restoring
     * them here is what stops a network change from silently killing a transfer
     * that is still tracked and still believed to be running.
     */
    fun resubscribeCurrent() {
        val channel = _current.value ?: return
        scope.launch {
            if (!channel.writeOnly) {
                subscribeQuiet(channel.messageStreamId, StreamConstants.P_MESSAGES)
                subscribeQuiet(channel.messageStreamId, StreamConstants.P_CONTROL)
                subscribeQuiet(channel.ephemeralStreamId, StreamConstants.EPH_CONTROL)
                if (isEpochChannel(channel) && channel.keysStreamId.isNotEmpty()) {
                    subscribeQuiet(channel.keysStreamId, StreamConstants.P_KEY_EXCHANGE)
                }
            }
            // Every stream with a live transfer, not just this channel's: the
            // seeding exception exists precisely so transfers outlive the
            // channel being on screen, and those are the ones nothing else
            // would ever bring back.
            for ((eph, password) in media.activeEphemeralStreams()) {
                media.ensureMediaPartitions(eph, password)
            }
        }
    }

    fun removeChannel(messageStreamId: String) {
        scope.launch {
            val leaving = _channels.value.firstOrNull { it.messageStreamId == messageStreamId }
            if (_current.value?.messageStreamId == messageStreamId) closeCurrentInternal()
            _channels.value = _channels.value.filterNot { it.messageStreamId == messageStreamId }
            store.save(_channels.value)
            // Rotate the channel pseudonym on a GENUINE leave (never on a mere
            // view switch — closeCurrent keeps it: peers mid-transfer know the
            // current publisher). Rejoining gets a fresh key, so yesterday's
            // pseudonym cannot be tied to today's. Guard: a leave with a
            // transfer still running keeps the identity (web
            // hasActiveMediaTransfers) — peers are mid-transfer against the
            // publisher they already know; it dies with the session anyway.
            val transfersActive = leaving?.ephemeralStreamId?.let { eph ->
                media.activeEphemeralStreams().containsKey(eph)
            } ?: false
            if (!transfersActive) {
                runCatching { bridge.call("dropChannelIdentity", JSONObject().put("streamId", messageStreamId)) }
            }
            // Native: drop the channel's epoch keys (runtime + persisted)
            if (isEpochChannel(leaving)) {
                runCatching { epochKeys.forgetChannel(messageStreamId) }
            }
            // A DM leave is SOFT (web dmLeftAt): messages older than this stay
            // gone, a new one resurfaces the conversation. Without the
            // tombstone the next inbox history replay recreated the room from
            // old messages the moment the app reconnected. Synced as the
            // web's dmLeftAt slice.
            if (leaving?.type == "dm") {
                leaving.peerAddress?.lowercase()?.let { store.markLeft("dm:$it") }
                onSliceTouched("dmLeftAt")
            }
            // Otherwise the badge count outlives the channel and reappears if
            // the same stream is ever rejoined.
            unreadStore.clear(messageStreamId)
            // The conversation's images have no business staying on disk after
            // the user walks away (web clearImagesForStream on leave).
            blobStore.clearForStream(messageStreamId)
            // Tombstone, so a sync pull cannot resurrect the channel from an
            // older snapshot taken before the user left.
            store.markLeft(messageStreamId)
            onLocalStateChanged()
        }
    }

    /**
     * Leave a DM and permanently ignore the peer (web `leaveChannel(…, {block:true})`).
     *
     * Unlike a soft leave, this writes no `dmLeftAt` timestamp: a soft leave is
     * designed to let a new message resurface the conversation, which is
     * exactly what a block must NOT do. The block is recorded first so a
     * message arriving mid-teardown is already ignored.
     */
    fun blockPeer(messageStreamId: String) {
        scope.launch {
            val channel = _channels.value.firstOrNull { it.messageStreamId == messageStreamId }
            val peer = channel?.peerAddress?.lowercase()
            if (peer != null) {
                persistBlockedPeer(peer)
                // Drop the local half of the conversation too, as the web does.
                sentDmStore.clear(messageStreamId)
                peerPubKeys.remove(peer)
            }
            removeChannel(messageStreamId)
        }
    }

    /**
     * Delete the channel ON-CHAIN: all three streams, then drop it locally.
     *
     * Distinct from [removeChannel], which only forgets the channel on this
     * device and leaves the streams standing so anyone (including you) can
     * rejoin with the stream id. This one is irreversible for everybody.
     *
     * Each stream is deleted separately and failures are collected rather than
     * aborting: a partial delete is a real outcome, and the user needs to know
     * which streams are still out there instead of seeing one generic error.
     */
    suspend fun deleteChannel(messageStreamId: String): List<String> {
        val channel = _channels.value.firstOrNull { it.messageStreamId == messageStreamId }
        // Defensive owner-guard (web channels.js:4225): the UI already hides
        // the action, but a stale composition must not fire on-chain deletes.
        if (channel != null && !amOwner(channel)) {
            throw IllegalStateException("Only the channel owner can delete it")
        }
        val ids = listOfNotNull(
            messageStreamId,
            channel?.ephemeralStreamId,
            channel?.adminStreamId,
            channel?.takeIf { isEpochChannel(it) }?.let {
                it.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(messageStreamId) }
            }
        ).distinct()
        val failed = mutableListOf<String>()
        for (id in ids) {
            try {
                retry(3) { bridge.call("deleteStream", JSONObject().put("streamId", id), 120_000) }
            } catch (e: Exception) {
                Log.w(TAG, "deleteStream failed for $id: ${e.message}")
                failed.add(id)
            }
        }
        // Nothing was deleted at all (no gas, RPC down): keep the channel —
        // forgetting it locally would hide a still fully working channel and
        // read as success. Partial failure still drops it, because a channel
        // whose message stream is gone just fails on every open.
        if (failed.size == ids.size && ids.isNotEmpty()) return failed
        removeChannel(messageStreamId)
        return failed
    }

    /** Replaces the channel list wholesale after a sync merge. */
    fun replaceChannels(channels: List<Channel>) {
        _channels.value = channels
        store.save(channels)
    }

    /** Set by the ViewModel to schedule a debounced sync push. */
    @Volatile var onLocalStateChanged: () -> Unit = {}

    /** Stamps a sync slice's mutation timestamp (ViewModel sliceTouched) —
     *  a slice never stamped always loses the latest-wins merge. */
    @Volatile var onSliceTouched: (String) -> Unit = {}

    /**
     * Raised for a message from someone else. The ViewModel decides whether to
     * post a system notification — it knows whether the app is in front and
     * which conversation is open, which this layer does not.
     */
    @Volatile var onIncomingMessage: (channel: Channel, sender: String, preview: String) -> Unit =
        { _, _, _ -> }

    /**
     * True while the initial resend is still running. The web gates rendering
     * on this (`channel.initialLoadInProgress`) so a message that is about to
     * be deleted or edited by an override later in the same resend is never
     * painted first — without the gate, history flashes deleted messages.
     */
    private val _initialLoad = MutableStateFlow(false)
    val initialLoad: StateFlow<Boolean> = _initialLoad.asStateFlow()

    /** Web: INITIAL_HISTORY_SAFETY_MS in channels.js. */
    private val INITIAL_HISTORY_SAFETY_MS = 30_000L

    /**
     * Decrypts a password page's sealed payloads before the ordered merge.
     *
     * The page goes to the bridge's `pwDecryptBatch` in ONE call: Android's
     * javax.crypto PBKDF2 is Bouncy Castle in pure Java (~1s per message on a
     * phone — a 50-message page measured 7.5s even fanned across all cores),
     * while the WebView's crypto.subtle runs the same derivation in native
     * BoringSSL exactly like the PWA. Every message carries its own random
     * salt (web wire format), so no cache can help — the derivation cost is
     * unavoidable and the only lever is where it runs.
     *
     * If the bridge call fails the page falls back to the in-process parallel
     * decrypt. Failed entries become null and fall through handleContent's
     * type check, exactly as they would have inline.
     */
    private suspend fun predecrypt(arr: JSONArray, password: String?): List<Any?> {
        val n = arr.length()
        val out = arrayOfNulls<Any>(n)
        val sealedIdx = ArrayList<Int>()
        for (i in 0 until n) {
            val content = arr.optJSONObject(i)?.opt("content")
            if (password != null && content is String) sealedIdx.add(i) else out[i] = content
        }
        if (password == null || sealedIdx.isEmpty()) return out.toList()

        val items = JSONArray().also { arr0 ->
            sealedIdx.forEach { arr0.put(arr.optJSONObject(it)!!.opt("content") as String) }
        }
        val plain = try {
            bridge.call("pwDecryptBatch", JSONObject()
                .put("password", password).put("items", items), 60_000)
                .optJSONArray("plain")
        } catch (e: Exception) { null }

        if (plain != null) {
            for (j in sealedIdx.indices) {
                out[sealedIdx[j]] = if (plain.isNull(j)) null else
                    try { JSONObject(plain.getString(j)) } catch (e: Exception) { null }
            }
        } else {
            coroutineScope {
                sealedIdx.map { i ->
                    async(Dispatchers.Default) {
                        out[i] = try {
                            JSONObject(PomboCrypto.decryptString(
                                arr.optJSONObject(i)!!.opt("content") as String, password))
                        } catch (e: Exception) { null }
                    }
                }.forEach { it.await() }
            }
        }
        return out.toList()
    }

    /** A resend page already decrypted: entry `i` of [entries] is [contents]`[i]`. */
    private class HistoryPage(val entries: JSONArray, val contents: List<Any?>)

    /**
     * One partition's resend, decrypted but not yet applied.
     *
     * `budgetMs` sits under the hard call timeout: when key-request waits eat
     * the budget, the bridge returns the PARTIAL page it already has — one
     * offline member no longer renders the whole room empty.
     *
     * Returns null on any failure, so a channel with no storage (or a partition
     * that simply has nothing) just contributes nothing.
     */
    private suspend fun fetchHistoryPage(
        channel: Channel,
        partition: Int,
        last: Int,
        budgetMs: Int,
        timeoutMs: Long
    ): HistoryPage? = try {
        val t0 = System.currentTimeMillis()
        val args = JSONObject()
            .put("streamId", channel.messageStreamId)
            .put("partition", partition)
            .put("last", last)
            .put("budgetMs", budgetMs)
        // Sealed rows are opened INSIDE the drain, so each message's PBKDF2
        // overlaps the network wait for the ones still coming and the ciphertext
        // never makes the extra pwDecryptBatch round trip. [predecrypt] below
        // stays as the fallback for anything that came back still sealed.
        channel.password?.let { args.put("password", it) }
        if (channel.type == "gated") args.put("recoverSigner", true)
        val res = bridge.call("resend", args, timeoutMs)
        val tResend = System.currentTimeMillis()
        val arr = res.optJSONArray("messages")
        if (arr == null) null else {
            val contents = predecrypt(arr, channel.password)
            // Where does a slow room open actually spend its time — network
            // (storage-node resend) or crypto (password page decrypt)?
            // call = the whole bridge round trip; drain/tail come from inside it
            // (drain = network, tail = PBKDF2 with no network left to hide it).
            android.util.Log.d(
                "PomboPerf",
                "history ${channel.name} P$partition: call=${tResend - t0}ms " +
                    "drain=${res.optInt("drainMs")}ms tail=${res.optInt("decryptMs")}ms " +
                    "post=${System.currentTimeMillis() - tResend}ms n=${arr.length()}"
            )
            HistoryPage(arr, contents)
        }
    } catch (e: Exception) { null }

    /**
     * Initial history: content (P0) and overrides (P1).
     *
     * The two resends are FETCHED concurrently — the web fires both without
     * awaiting (streamr.js subscribeWithHistory calls fetchHistoryAsync for each
     * partition), so its gate closes on max(P0, P1) while this used to pay
     * P0 + P1 in series.
     *
     * They are APPLIED in the old order, content before overrides, deliberately:
     * an edit or delete needs its target present, and while [applyOverride]
     * parks orphans in `pendingOverrides` for the sweep at the end, keeping the
     * original order means the common case never has to rely on that.
     */
    private suspend fun loadHistory(channel: Channel, generation: Int) {
        val (content, overrides) = coroutineScope {
            val c = async {
                fetchHistoryPage(
                    channel, StreamConstants.P_MESSAGES,
                    StreamConstants.INITIAL_MESSAGES, 45_000, 60_000
                )
            }
            val o = async {
                fetchHistoryPage(channel, StreamConstants.P_CONTROL, 50, 20_000, 30_000)
            }
            c.await() to o.await()
        }
        // The resends can take tens of seconds on a slow network — long enough
        // for several channel switches. Without this the whole of A's history
        // was appended to whatever channel is now on screen.
        if (!stillCurrent(generation)) return

        if (content != null) {
            // One merge for the whole page instead of one per message.
            batchingMerges {
                for (i in 0 until content.entries.length()) {
                    val entry = content.entries.optJSONObject(i) ?: continue
                    val meta = entry.optJSONObject("meta") ?: JSONObject()
                    trackOldest(meta)
                    handleContent(
                        channel, content.contents[i], meta,
                        historical = true, generation = generation
                    )
                }
            }
            // Like the web, stay optimistic: only a range resend can prove exhaustion.
            _hasMoreHistory.value = true
        }

        if (overrides != null) {
            for (i in 0 until overrides.entries.length()) {
                val entry = overrides.entries.optJSONObject(i) ?: continue
                val meta = entry.optJSONObject("meta") ?: JSONObject()
                handleContent(
                    channel, overrides.contents[i], meta,
                    historical = true, generation = generation
                )
            }
        }
        if (!stillCurrent(generation)) return
        // Unconditional now. The old code returned early when P1 came back
        // without a `messages` array, which skipped this sweep and left any
        // override that had arrived before its target parked forever.
        applyPendingOverrides()
    }

    /** Moves the pagination cursor back. Chunks count too — they carry no id. */
    private fun trackOldest(meta: JSONObject) {
        val ts = meta.optLong("timestamp", 0L)
        if (ts > 0L && (oldestTimestamp == 0L || ts < oldestTimestamp)) oldestTimestamp = ts
    }

    /**
     * Loads the previous page of history (web: channels.js loadMoreHistory).
     * Fetches content (P0) and overrides (P1) older than the current cursor;
     * pagination state is driven by the content partition. Returns how many
     * new messages became visible.
     */
    suspend fun loadMoreHistory(): Int {
        val channel = _current.value ?: return 0
        if (channel.writeOnly || channel.type == "dm") return 0
        if (_loadingHistory.value || !_hasMoreHistory.value) return 0

        // Cursor of Date.now() when history held only reactions/chunks (web parity).
        val before = if (oldestTimestamp > 0L) oldestTimestamp else System.currentTimeMillis()
        val generationAtStart = switchGeneration
        _loadingHistory.value = true
        val countBefore = _messages.value.size
        try {
            var content = resendOlder(channel.messageStreamId, StreamConstants.P_MESSAGES, before, channel.password)
            // Storage nodes sometimes close a range iterator early and answer
            // empty for a range that has data. The web retries [1s,2s,3s]
            // before believing exhaustion (channels.js:3419-3437); concluding
            // on the first empty page orphaned everything older.
            var emptyRetry = 0
            while (emptyRetry < 3 &&
                content != null &&
                (content.optJSONArray("messages")?.length() ?: 0) == 0 &&
                !content.optBoolean("hasMore", false)
            ) {
                delay(1_000L * (emptyRetry + 1))
                if (!stillCurrent(generationAtStart)) return 0
                content = resendOlder(channel.messageStreamId, StreamConstants.P_MESSAGES, before, channel.password)
                emptyRetry++
            }
            val overrides = resendOlder(channel.messageStreamId, StreamConstants.P_CONTROL, before, channel.password)

            // Discard if the user switched channels while we were fetching.
            if (!stillCurrent(generationAtStart)) return 0

            // Content first, then overrides — an edit/delete needs its target
            // present, so each partition gets its own batch rather than one
            // batch around both: the overrides must see the merged content.
            for (partition in listOf(content, overrides)) {
                val arr = partition?.optJSONArray("messages") ?: continue
                val contents = predecrypt(arr, channel.password)
                batchingMerges {
                    for (i in 0 until arr.length()) {
                        val entry = arr.optJSONObject(i) ?: continue
                        val meta = entry.optJSONObject("meta") ?: JSONObject()
                        trackOldest(meta)
                        handleContent(
                            channel, contents[i], meta,
                            historical = true, generation = generationAtStart
                        )
                    }
                }
            }
            // Overrides consumed before their target was paginated in.
            applyPendingOverrides()
            // Keep the flag untouched when the fetch itself failed, so a
            // transient network error doesn't permanently kill pagination.
            if (content != null) _hasMoreHistory.value = content.optBoolean("hasMore", false)
            val added = (_messages.value.size - countBefore).coerceAtLeast(0)
            android.util.Log.d("PomboPerf",
                "loadMore ${channel.name}: +$added hasMore=${_hasMoreHistory.value}")
            return added
        } catch (e: Exception) {
            return 0
        } finally {
            if (switchGeneration == generationAtStart) _loadingHistory.value = false
        }
    }

    private suspend fun resendOlder(
        streamId: String,
        partition: Int,
        before: Long,
        /** Opens sealed rows in the bridge during the drain — see [fetchHistoryPage]. */
        password: String?
    ): JSONObject? = try {
        val args = JSONObject()
            .put("streamId", streamId)
            .put("partition", partition)
            .put("before", before)
            .put("last", StreamConstants.LOAD_MORE_COUNT)
            .put("budgetMs", 45_000)
        password?.let { args.put("password", it) }
        withSignerRecovery(args, streamId)
        bridge.call("resendRange", args, 60_000)
    } catch (e: Exception) { null }

    private suspend fun subscribeQuiet(streamId: String, partition: Int) {
        try {
            val args = JSONObject().put("streamId", streamId).put("partition", partition)
            // Gated (N-C): prove access via the gate contract (no per-member
            // grant), and have the bridge recover the envelope signer — the
            // author — for every message (gatedAuthor consumes it).
            channelByStream(streamId)?.takeIf { it.type == "gated" }?.gateAddress?.let {
                args.put("erc1271Contract", it).put("recoverSigner", true)
            }
            bridge.call("subscribe", args)
        } catch (e: Exception) { /* reported via the bridge status/error */ }
    }

    /** Adds recoverSigner to a resend args object when the stream is gated. */
    private fun withSignerRecovery(args: JSONObject, streamId: String): JSONObject {
        if (channelByStream(streamId)?.type == "gated") args.put("recoverSigner", true)
        return args
    }

    private suspend fun unsubscribeQuiet(streamId: String, partition: Int) {
        try {
            bridge.call("unsubscribe", JSONObject().put("streamId", streamId).put("partition", partition))
        } catch (e: Exception) { }
    }

    suspend fun sendMessage(text: String, replyTo: ReplyRef? = null) {
        val channel = _current.value ?: return
        val sender = myAddress() ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        if (channel.type == "dm") { sendDm(channel, trimmed); return }

        val id = Protocol.generateMessageId()
        val timestamp = System.currentTimeMillis()
        mergeMessages(listOf(UiMessage(
            id, trimmed, sender, myUsername(), timestamp,
            mine = true, pending = true, verified = true, replyTo = replyTo,
            // Same self-ENS seeding as sendDm — own bubbles never resolved.
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender)
        )))

        // No app-layer signature (D6): the Streamr envelope authenticates the
        // publisher and the proof (added by the bridge on ephemeral publishes)
        // authenticates the account behind it. `sender`/`channelId` stay on
        // the OBJECT for local use — the bridge strips them at egress; on the
        // account paths (native/readOnly) they are simply redundant.
        val content = JSONObject()
            .put("type", "text")
            .put("id", id)
            .put("text", trimmed)
            .put("sender", sender)
            .put("senderName", myUsername() ?: JSONObject.NULL)
            .put("timestamp", timestamp)
            .put("replyTo", replyTo?.let {
                JSONObject()
                    .put("id", it.id)
                    .put("sender", it.sender)
                    .put("senderName", it.senderName ?: JSONObject.NULL)
                    .put("text", it.text)
            } ?: JSONObject.NULL)

        // Web publishWithRetry (channels.js:3277): a transient publish failure
        // left the optimistic bubble stuck on `pending` forever.
        publishTextWithRetry {
            publishForChannel(channel, channel.messageStreamId, StreamConstants.P_MESSAGES, content)
        }
        confirmMessage(id)
        // Without this a message sent from Android never wakes anyone: web
        // clients rely on the relay seeing a wake signal on the push stream.
        sendWakeSignal(channel)
    }

    /** Web publishWithRetry: 3 attempts, 2s apart, then the error surfaces. */
    private suspend fun publishTextWithRetry(block: suspend () -> Unit) {
        var last: Exception? = null
        repeat(3) { attempt ->
            try { block(); return } catch (e: Exception) {
                last = e
                if (attempt < 2) delay(2_000)
            }
        }
        throw last ?: IllegalStateException("publish failed")
    }

    /** Sends an image via the chunked transport (manifest + image_chunk on partition 0). */
    suspend fun sendImage(input: ByteArray, originalMime: String) {
        val channel = _current.value ?: return
        val sender = myAddress() ?: return
        // A DM uses the same chunked transport, but every payload is sealed in
        // the ECDH envelope: the peer's inbox accepts public publishes, so an
        // unsealed chunk there would be readable by anyone.
        // A DM image publishes through publishForChannel → sealed sender v2;
        // fail early here if the peer's key is unreachable, before the
        // expensive encode below.
        if (channel.type == "dm") {
            val peer = channel.peerAddress
                ?: throw IllegalStateException("DM conversation has no peer")
            peerPubKey(peer) ?: throw IllegalStateException(
                "Cannot send image: peer public key not available"
            )
        }

        // The heaviest single operation in the app: bitmap decode, then a
        // resolution × quality ladder that re-compresses until the output fits,
        // then SHA-256 per chunk and over the whole image. Never on main.
        // Sealed transports (DM envelope, password) double the base64 cost, so
        // their chunks are cut smaller to stay under the 220KB wire ceiling.
        val sealed = channel.type == "dm" || channel.password != null
        val enc = withContext(Dispatchers.Default) {
            com.pombo.android.core.MediaEncoder.encode(
                input, originalMime,
                if (sealed) com.pombo.android.core.MediaEncoder.CHUNK_RAW_SEALED
                else com.pombo.android.core.MediaEncoder.CHUNK_RAW
            )
        } ?: throw IllegalStateException("Couldn't read image")
        val id = Protocol.generateMessageId()
        val imageId = Protocol.generateMessageId()
        val timestamp = System.currentTimeMillis()

        // Immediate local echo (already assembled).
        mergeMessages(listOf(UiMessage(
            id = id, text = "", sender = sender, senderName = myUsername(),
            timestamp = timestamp, mine = true, isImage = true,
            imageId = imageId, imageBytes = enc.bytes, imageMime = enc.mime, verified = true
        )))

        // Ledger the image before publishing: it is the only copy once storage
        // retention drops the chunks, and blob sync pushes it to my other
        // devices. Marked unsynced so the next sync picks it up.
        blobStore.save(
            imageId, channel.messageStreamId,
            com.pombo.android.core.ImageBlobStore.toDataUrl(enc.bytes, enc.mime),
            synced = false
        )
        onLocalStateChanged()

        // Unsigned manifest (D6) — identity comes from the proof/envelope.
        val manifest = JSONObject()
            .put("type", "image").put("transport", "chunked").put("v", 2)
            .put("id", id).put("imageId", imageId).put("sender", sender)
            .put("senderName", myUsername() ?: JSONObject.NULL)
            .put("timestamp", timestamp)
            .put("originalMime", enc.originalMime).put("finalMime", enc.mime)
            .put("finalSizeBytes", enc.bytes.size).put("chunkCount", enc.chunks.size)
            .put("chunkHashes", JSONArray(enc.chunkHashes))
            .put("assembledSha256", enc.assembledSha256)
            .put("preservedOriginal", enc.preservedOriginal)
            .put("convertedTo", enc.convertedTo ?: JSONObject.NULL)
            .put("qualityUsed", enc.quality ?: JSONObject.NULL)

        // Chunks first, manifest last (web media.js order): the manifest is what
        // tells the receiver how many pieces to expect, so publishing it before
        // them invites an assembly attempt against data that has not landed.
        for ((index, piece) in enc.chunks.withIndex()) {
            val chunkMsg = JSONObject()
                .put("type", "image_chunk").put("v", 2)
                .put("imageId", imageId).put("chunkIndex", index)
                .put("timestamp", System.currentTimeMillis())
                .put("chunkHash", enc.chunkHashes[index])
                .put("data", android.util.Base64.encodeToString(piece, android.util.Base64.NO_WRAP))
            publishForChannel(channel, channel.messageStreamId, StreamConstants.P_MESSAGES, chunkMsg)
        }
        publishForChannel(channel, channel.messageStreamId, StreamConstants.P_MESSAGES, manifest)

        // A DM's sent half lives only here (the peer's inbox is not readable by
        // us). Image bytes stay in the ledger, so the stored entry is metadata
        // only — same split as the web's addSentMessage.
        if (channel.type == "dm") {
            sentDmStore.add(channel.messageStreamId, manifest)
            onLocalStateChanged()
        }
    }

    /** Web dedup key `streamId:messageId:emoji:action` -> last send time. */
    private val recentReactions = HashMap<String, Long>()

    /** channels.js sendReaction format — no signature; authorship = publisherId. */
    suspend fun sendReaction(messageId: String, emoji: String, add: Boolean) {
        val channel = _current.value ?: return
        val me = myAddress()?.lowercase() ?: return
        // Rapid taps published one reaction per tap; the web deduplicates the
        // same (stream, message, emoji, action) for 500ms (channels.js:4053).
        val dedupKey = "${channel.messageStreamId}:$messageId:$emoji:${if (add) "add" else "remove"}"
        val now = System.currentTimeMillis()
        synchronized(recentReactions) {
            val last = recentReactions[dedupKey]
            if (last != null && now - last < 500) return
            recentReactions[dedupKey] = now
            if (recentReactions.size > 64) {
                recentReactions.entries.removeAll { now - it.value > 5_000 }
            }
        }
        val reaction = JSONObject()
            .put("type", "reaction")
            .put("action", if (add) "add" else "remove")
            .put("messageId", messageId)
            .put("emoji", emoji)
            .put("senderName", myUsername() ?: JSONObject.NULL)
            .put("timestamp", System.currentTimeMillis())
        // Local optimistic update
        applyReaction(messageId, emoji, me, add)
        // A DM reaction was going out in plaintext to the peer's inbox — that
        // stream accepts public publishes, so the emoji and the message it
        // points at were readable by anyone. Seal it like every other DM
        // payload (web sendReaction takes the same branch).
        publishForChannel(channel, channel.messageStreamId, StreamConstants.P_MESSAGES, reaction)
        // A DM reaction goes to the PEER's inbox and never comes back from any
        // resend; a write-only channel is never resubscribed at all. The local
        // record is the only copy (web addSentReaction), and it is a sync
        // slice — mark state dirty so other devices receive it.
        if (channel.type == "dm" || channel.writeOnly) {
            sentReactionsStore?.record(channel.messageStreamId, messageId, emoji, me, add)
            onLocalStateChanged()
        }
    }

    /**
     * Where an override goes, and how it is sealed, differs by channel kind.
     *
     * A channel puts overrides on the control partition (P1). A DM cannot: on a
     * DM inbox P1 is the cross-device SYNC partition, so an override published
     * there would land among sync payloads and never reach the peer. DM
     * overrides ride P0 with the messages, sealed in the ECDH envelope.
     */
    private suspend fun publishOverride(channel: Channel, override: JSONObject) {
        // Partition still differs: a DM inbox P1 is the SYNC partition, so DM
        // overrides ride P0 with the messages. Sealing is handled centrally.
        val partition =
            if (channel.type == "dm") StreamConstants.P_MESSAGES else StreamConstants.P_CONTROL
        publishForChannel(channel, channel.messageStreamId, partition, override)
    }

    suspend fun editMessage(targetId: String, newText: String) {
        val channel = _current.value ?: return
        val original = _messages.value.find { it.id == targetId } ?: return
        require(original.mine) { "You can only edit your own messages" }
        val text = newText.trim()
        val timestamp = System.currentTimeMillis()
        val override = JSONObject()
            .put("type", "edit")
            .put("targetId", targetId)
            .put("text", text)
            .put("timestamp", timestamp)

        // Publish BEFORE touching local state (web sendEdit): if the publish
        // fails, showing an edit the peer never received is worse than showing
        // nothing — the two sides would silently disagree about the text.
        publishOverride(channel, override)

        applyEdit(targetId, text)
        // A DM's sent half is only in local storage, so the edit has to land
        // there too or it reverts the next time the chat is opened.
        if (channel.type == "dm") {
            sentDmStore.edit(channel.messageStreamId, targetId, text)
            onLocalStateChanged()
        }
    }

    suspend fun deleteMessage(targetId: String) {
        val channel = _current.value ?: return
        val original = _messages.value.find { it.id == targetId } ?: return
        require(original.mine) { "You can only delete your own messages" }
        val override = JSONObject()
            .put("type", "delete")
            .put("targetId", targetId)
            .put("timestamp", System.currentTimeMillis())

        publishOverride(channel, override)

        deletedIds.add(targetId)
        if (original.isImage) original.imageId?.let { tombstoneImage(it) }
        _messages.value = _messages.value.filterNot { it.id == targetId }
        if (channel.type == "dm") {
            sentDmStore.remove(channel.messageStreamId, targetId)
            onLocalStateChanged()
        }
    }

    // Web InputUI.js sends the typing signal at most every 2s while keys keep
    // coming. Without this floor every keystroke becomes a full Streamr publish
    // (plus an ECDH seal on DMs) queued on the single WebView JS thread, which
    // is enough to make the whole app feel frozen while composing a message.
    @Volatile private var lastTypingSent = 0L

    fun sendTyping() {
        val channel = _current.value ?: return
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 2_000) return
        lastTypingSent = now
        scope.launch {
            try {
                val typing = JSONObject()
                    .put("type", "typing")
                    .put("nickname", myUsername() ?: JSONObject.NULL)
                    .put("timestamp", System.currentTimeMillis())
                publishForChannel(channel, channel.ephemeralStreamId, StreamConstants.EPH_CONTROL, typing)
            } catch (e: Exception) { }
        }
    }

    private suspend fun publishContent(
        streamId: String,
        partition: Int,
        payload: JSONObject,
        password: String?,
        /**
         * Recipient address for a DM. The payload is sealed-sender v2 in the
         * bridge (dmSealPublish): ECDH against a per-message ephemeral key,
         * the identity proof INSIDE the ciphertext, and the publish goes out
         * under that same throwaway key — the wallet address never touches
         * the wire. The ephemeral private key never crosses the bridge.
         */
        dmPeer: String? = null
    ): Long {
        if (dmPeer != null) {
            val pk = peerPubKey(dmPeer)
                ?: throw IllegalStateException("DM publish: peer public key unavailable for $dmPeer")
            val res = bridge.call("dmSealPublish", JSONObject()
                .put("streamId", streamId).put("partition", partition)
                .put("recipientAddress", dmPeer).put("recipientPublicKey", pk)
                .put("message", payload))
            return res.optLong("timestamp", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        }
        val content: Any = when {
            password != null -> PomboCrypto.encryptString(payload.toString(), password)
            else -> payload
        }
        // Returns the network-assigned publish timestamp (the storage-file announce
        // confirm loop needs it); falls back to local time if the bridge had none.
        val res = bridge.call("publish", JSONObject()
            .put("streamId", streamId).put("partition", partition).put("content", content))
        return res.optLong("timestamp", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
    }

    /**
     * The single place that decides how a channel's payload is sealed.
     *
     * Every publish that belongs to a channel goes through here, so the rule
     * lives in one spot instead of being restated (and forgotten) at each call
     * site — which is exactly how DM reactions, typing and presence ended up
     * going out in the clear.
     *
     *   dm       -> ECDH envelope for the peer (their inbox and ephemeral
     *               stream both accept public publishes)
     *   password -> AES with the channel password
     *   public   -> plain
     */
    private suspend fun publishForChannel(
        channel: Channel,
        streamId: String,
        partition: Int,
        payload: JSONObject
    ) {
        if (channel.type == "dm") {
            val peer = channel.peerAddress ?: return
            publishContent(streamId, partition, payload, password = null, dmPeer = peer)
            return
        }
        publishChannel(channel, streamId, partition, payload, channel.password)
    }

    /**
     * Ephemeral-or-account decision for a non-DM channel publish (returns the
     * publish timestamp):
     *
     *   public/password -> the channel's ephemeral identity, proof in the
     *                      payload, local fields stripped — all in the bridge
     *                      (publishAsChannel), which also refuses -3.
     *   native          -> the ACCOUNT. Its grants are per member address
     *                      on-chain; every receiver validates the publisher
     *                      per message, so an ephemeral key would be rejected
     *                      network-wide (plan §8: granting one on-chain would
     *                      link wallet→epk publicly — worse than today).
     *   readOnly -1     -> the ACCOUNT (publish is the owner's by ownership).
     *
     * `channel` may be null (media transport paths look the channel up by
     * stream id; a previewed channel is not in the list) — null means not
     * native, so the ephemeral path applies.
     */
    private suspend fun publishChannel(
        channel: Channel?, streamId: String, partition: Int, payload: JSONObject, password: String?
    ): Long {
        // The admin stream (-3) ALWAYS publishes under the account, on every
        // channel kind: ADMIN_STATE / CHANNEL_IMAGE / password challenge are
        // gated by the owner's on-chain permission, which a throwaway key does
        // not hold — the bridge's publishAsChannel refuses -3 outright. Missing
        // this was the "publishAs refuses the admin stream" crash on every
        // admin moderation (delete/hide/ban/pin) in a public/password channel.
        val isAdminStream = streamId == channel?.adminStreamId ||
            streamId.endsWith(StreamConstants.SUFFIX_ADMIN)
        // Admin stream keeps the legacy account path EXCEPT on native, where
        // -3 rides the epoch layer too: the SDK's group-key AES made a
        // web-published ADMIN_STATE unreadable here (20s key-exchange timeouts
        // per row, moderation never landed). The owner still signs as the
        // account, so the on-chain owner-only publish permission holds.
        if (isAdminStream && !isEpochChannel(channel)) {
            return publishContent(streamId, partition, payload, password)
        }
        if (channel != null && channel.readOnly && streamId == channel.messageStreamId) {
            return publishContent(streamId, partition, payload, password)
        }
        // NATIVE/GATED channels (N-A/N-C): encrypt with the channel's epoch
        // key and go out with encryptionType NONE — the SDK's group-key layer
        // is exactly the per-publisher, publisher-must-be-online dependency
        // the epoch protocol replaces. Native publishes AS THE ACCOUNT (grant
        // per member); gated publishes AS THE GATE CLONE with the account
        // signing (ERC-1271; the clone holds the only grant, -3 included).
        // Fail-closed: no epoch key means NO publish, never a plaintext or
        // SDK-keyed fallback.
        if (isEpochChannel(channel)) {
            channel!!
            val keysId = channel.keysStreamId.ifEmpty {
                StreamConstants.deriveKeysId(channel.messageStreamId)
            }
            val clean = stripLocalFields(payload)
            var envelope = epochKeys.encryptCurrent(channel.messageStreamId, clean)
            if (envelope == null) {
                // Cold open may not have run the bootstrap/request yet — one recovery attempt
                epochKeys.ensureChannelKeys(
                    channel.messageStreamId, keysId,
                    channel.storageDays ?: 180,
                                    allowMint = System.currentTimeMillis() - channel.createdAt < 3_600_000,
                                    memberCount = channel.members.size,
                                    gated = channel.type == "gated")
                envelope = epochKeys.encryptCurrent(channel.messageStreamId, clean)
            }
            if (envelope == null) {
                throw IllegalStateException(
                    "No epoch key for ${channel.messageStreamId} — cannot publish on a ${channel.type} channel without one (waiting for KEY_WRAP)")
            }
            val args = JSONObject()
                .put("streamId", streamId).put("partition", partition).put("content", envelope)
            val method = if (channel.type == "gated") {
                val gate = channel.gateAddress ?: throw IllegalStateException(
                    "Gate address unknown for ${channel.messageStreamId} (repair pending) — cannot publish")
                args.put("gateAddress", gate)
                "publishAsGate"
            } else "publishAsAccount"
            val res = bridge.call(method, args)
            return res.optLong("timestamp", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        }
        val args = JSONObject()
            .put("streamId", streamId).put("partition", partition).put("content", payload)
        password?.let { args.put("password", it) }
        val res = bridge.call("publishAsChannel", args)
        return res.optLong("timestamp", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
    }

    /**
     * Fields that must never reach the wire inside an epoch envelope (web
     * publisherProof.js stripLocalFields): local UI state, and the identity
     * fields D6/D7 removed. `sender` is re-derived by every receiver from the
     * transport publisher at ingest (attachAccount) — the account, on native.
     */
    private fun stripLocalFields(data: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in data.keys()) {
            if (k == "verified" || k == "pending" || k == "_dmSent" ||
                k == "sender" || k == "account" || k == "signature" || k == "channelId") continue
            out.put(k, data.opt(k))
        }
        return out
    }

    /** Channel lookup by ANY of its streams, for transports that only know a stream id. */
    private fun channelByStream(streamId: String): Channel? {
        val match: (Channel) -> Boolean = {
            it.messageStreamId == streamId || it.ephemeralStreamId == streamId ||
                it.adminStreamId == streamId || (it.keysStreamId.isNotEmpty() && it.keysStreamId == streamId)
        }
        // Preview channels live only in _current, never in _channels — the
        // gated paths (epoch ingest, clone transport, gate checks) must still
        // find them or a gated preview silently degrades to ungated handling.
        return _channels.value.find(match) ?: _current.value?.takeIf(match)
    }

    /** Native and gated channels share the epoch-key machinery (N-A/N-C). */
    private fun isEpochChannel(channel: Channel?): Boolean =
        channel?.type == "native" || channel?.type == "gated"

    /**
     * The authenticated AUTHOR of a message on a gated channel, or null = DROP.
     *
     * The on-wire publisher is the gate clone for every member, so authorship
     * is the envelope signer the bridge recovered (`meta.signer`) — the very
     * signature the SDK validated against the gate's isValidSignature. Drops:
     * a foreign transport publisher (permissions are clone-only), an
     * unrecoverable envelope, and — on the admin stream, where the clone
     * necessarily holds the publish grant for everyone — a signer that is not
     * the namespace admin (this check replaces the transport's owner-only
     * enforcement). D10c applies: never fall back to the transport publisher.
     */
    private fun gatedAuthor(channel: Channel, streamId: String, meta: JSONObject): String? {
        val gate = channel.gateAddress ?: return null
        val publisher = meta.optString("publisherId").lowercase()
        if (publisher != gate) return null
        val signer = meta.optString("signer").lowercase().ifEmpty { null } ?: run {
            Log.w(TAG, "gated: unrecoverable envelope on $streamId — dropping")
            return null
        }
        if (streamId.endsWith(StreamConstants.SUFFIX_ADMIN)) {
            val admin = channel.messageStreamId.substringBefore('/').lowercase()
            if (signer != admin) {
                Log.w(TAG, "gated: non-admin $signer on admin stream — dropping")
                return null
            }
        }
        return signer
    }

    private fun startPresence(channel: Channel) {
        presenceJob?.cancel()
        presenceJob = scope.launch {
            while (isActive) {
                try {
                    val presence = JSONObject()
                        .put("type", "presence")
                        .put("nickname", myUsername() ?: JSONObject.NULL)
                        .put("lastActive", System.currentTimeMillis())
                    publishForChannel(channel, channel.ephemeralStreamId, StreamConstants.EPH_CONTROL, presence)
                } catch (e: Exception) { }
                evictStale()
                // A preview heartbeats at the web's slower cadence
                // (config.previewPresenceIntervalMs = 20s) — the browsing user
                // is not in the conversation, 4× the traffic bought nothing.
                delay(if (_isPreview.value) PREVIEW_PRESENCE_INTERVAL_MS else PRESENCE_INTERVAL_MS)
            }
        }
    }

    private fun evictStale() {
        val now = System.currentTimeMillis()
        synchronized(online) {
            online.entries.removeAll { now - it.value > ONLINE_TIMEOUT_MS }
            _onlineCount.value = online.size
        }
    }

    /** Called by the bridge listener (background thread). */
    fun onIncoming(streamId: String, partition: Int, contentRaw: String, metaRaw: String) {
        val meta = try { JSONObject(metaRaw) } catch (e: Exception) { JSONObject() }
        // Incoming DM on my own inbox (encrypted envelope) — route to DM handling.
        if (streamId == myInboxId && partition == StreamConstants.P_MESSAGES) {
            handleIncomingDm(contentRaw, meta)
            return
        }
        // Channel invites arrive on my inbox P3, encrypted to me.
        if (streamId == myInboxId && partition == StreamConstants.P_NOTIFICATIONS) {
            val envelope = try { JSONTokener(contentRaw).nextValue() } catch (e: Exception) { return }
            val sender = meta.optString("publisherId").lowercase().ifEmpty { null }
            scope.launch { handleNotification(envelope, sender) }
            return
        }
        // A peer's DM typing/presence lands on MY DM ephemeral inbox, ECDH-sealed.
        if (streamId == myDmEphemeralId && partition == StreamConstants.EPH_CONTROL) {
            handleDmControl(contentRaw, meta)
            return
        }
        // Epoch-key protocol (-4, native channels). The -4 subscription follows
        // the open channel, so route to it. Publisher/timestamp travel raw:
        // KEY_ANNOUNCE authority is validated by transport publisher (the -4
        // publishes as the ACCOUNT by design) and conflicts order by transport
        // timestamp (D13) — this must NOT go through attachAccount.
        if (StreamConstants.isKeysStream(streamId)) {
            val channel = _current.value?.takeIf { it.keysStreamId == streamId } ?: return
            val content = (try { JSONTokener(contentRaw).nextValue() } catch (e: Exception) { null })
                as? JSONObject ?: return
            // Gated: the clone publishes for everyone — the protocol's
            // identity checks need the envelope signer, not the transport.
            val publisher = if (channel.type == "gated") {
                gatedAuthor(channel, streamId, meta) ?: return
            } else meta.optString("publisherId").ifEmpty { null }
            val ts = meta.optLong("timestamp", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
            scope.launch {
                epochKeys.handleKeysMessage(
                    channel.messageStreamId, streamId, content, publisher, ts,
                    memberCount = channel.members.size)
            }
            return
        }
        // Media coordination (-2/P1). Handled BEFORE the open-channel gate
        // below on purpose: a seeder has to answer a piece request for a file
        // shared in a channel the user navigated away from, and a download has
        // to keep running while they read somewhere else. Gating this on
        // `_current` would make every transfer die on the next channel switch.
        if (partition == StreamConstants.EPH_MEDIA_SIGNALS &&
            StreamConstants.isEphemeralStream(streamId)
        ) {
            val content = try { JSONTokener(contentRaw).nextValue() } catch (e: Exception) { return }
            val publisher = meta.optString("publisherId").lowercase().ifEmpty { null }
            // A signal on OUR OWN DM inbox is a sealed-sender v2 envelope
            // (web dm.js routeInboxMedia): open it with OUR static key — the
            // publisher is a throwaway and picks nothing — identify the peer
            // from the proof, THEN check blocks and hand the plaintext to the
            // media controller, which expects the true account as sender.
            if (content is JSONObject && content.optInt("v") == 2 && content.has("epk")) {
                scope.launch {
                    val opened = try {
                        bridge.call("dmOpenBatch", JSONObject()
                            .put("items", JSONArray().put(content)))
                            .optJSONArray("results")?.takeIf { !it.isNull(0) }?.optJSONObject(0)
                    } catch (e: Exception) { null } ?: return@launch
                    val sender = opened.optString("sender").lowercase().ifEmpty { return@launch }
                    if (isBlockedPeer(sender)) return@launch
                    val plain = opened.optJSONObject("message") ?: return@launch
                    media.onSignal(streamId, plain, sender)
                }
                return
            }
            // v1 envelopes (pre-migration) no longer open — P6.
            if (content is JSONObject && content.optString("e") == "aes-256-gcm") return
            // Native channel signals arrive as epoch envelopes (N-A): open
            // with the channel's epoch key first — unknown kid skips, not
            // errors; the signal re-fires on the sender's next retry.
            if (content is JSONObject && com.pombo.android.core.EpochKeyCrypto.isEpochEnvelope(content)) {
                val ch = channelByStream(streamId)
                if (ch != null && isEpochChannel(ch)) {
                    scope.launch {
                        val keysId = ch.keysStreamId.ifEmpty {
                            StreamConstants.deriveKeysId(ch.messageStreamId)
                        }
                        val plain = epochKeys.tryDecrypt(ch.messageStreamId, keysId, content)
                            ?: return@launch
                        val acct = attachAccount(plain, publisher) ?: publisher
                        media.onSignal(streamId, plain, acct)
                    }
                }
                return
            }
            // Channel signals now arrive under an ephemeral publisher: the
            // seeder/leecher identity the media controller needs is the
            // account the proof recovers to (legacy signals fall back to the
            // publisher, which was the wallet).
            if (content is JSONObject) {
                scope.launch {
                    val acct = attachAccount(content, publisher) ?: publisher
                    media.onSignal(streamId, content, acct)
                }
                return
            }
            media.onSignal(streamId, content, publisher)
            return
        }
        val channel = _current.value ?: return
        // Snapshot the session alongside the channel. This runs on the WebView
        // binder thread and the handlers below are dispatched to main, so by the
        // time they run the user may have switched; the captured generation is
        // what lets them notice. Reading the two fields is not atomic, but the
        // only way that can go wrong is dropping a message we could have kept —
        // it fails closed, which is the right direction.
        val generation = switchGeneration
        val content = try { JSONTokener(contentRaw).nextValue() } catch (e: Exception) { return }
        val isMsgStream = streamId == channel.messageStreamId
        val isEphStream = streamId == channel.ephemeralStreamId
        if (!isMsgStream && !isEphStream) return
        scope.launch {
            handleContent(
                channel, content, meta, historical = false,
                generation = generation, streamId = streamId, partition = partition
            )
        }
    }

    /**
     * Binary media off -2/P2. Same reasoning as the signal path in
     * [onIncoming]: never gated on the open channel, and left on the calling
     * (WebView binder) thread so piece hashing and disk writes stay off main.
     */
    fun onIncomingBinary(streamId: String, partition: Int, data: ByteArray, metaRaw: String) {
        if (partition != StreamConstants.EPH_MEDIA_DATA) return
        if (!StreamConstants.isEphemeralStream(streamId)) return
        val meta = try { JSONObject(metaRaw) } catch (e: Exception) { JSONObject() }
        // `account` is the proof-recovered sender the bridge stamps when it
        // opens a sealed DM piece (0x02).
        val identity = meta.optString("account").lowercase().ifEmpty { null }
            ?: meta.optString("publisherId").lowercase().ifEmpty { null }
        // Channel piece with inline proof (0x03, web FILE_PIECE_SIGNED):
        //   [0x03][proof:65][fileId:36][idx:4][data]
        // The publisher is an ephemeral key; the seeder's identity is the
        // account the proof recovers to. After resolving it, the frame is
        // rewritten as legacy 0x01 so the store/assembly path stays byte-
        // identical. Safe to discriminate on the byte here: both are OUR
        // plaintext frame formats (the 1-in-256 random-byte hazard is the DM
        // v1 ciphertext, which never reaches this path).
        if (data.size >= 1 + 65 + 36 + 4 && data[0].toInt() == 0x03) {
            scope.launch {
                val proofHex = "0x" + data.copyOfRange(1, 66)
                    .joinToString("") { "%02x".format(it) }
                val pub = meta.optString("publisherId").lowercase().ifEmpty { null }
                val account = pub?.let { recoverProofAccount(it, proofHex) } ?: pub
                val legacy = ByteArray(data.size - 65)
                legacy[0] = 0x01
                System.arraycopy(data, 66, legacy, 1, data.size - 66)
                media.onBinary(streamId, legacy, account)
            }
            return
        }
        media.onBinary(streamId, data, identity)
    }

    // ---- Ingest identity (web streamr.js attachAccount + publisherProof.js) ----

    /**
     * (publisherId|proof) -> recovered account, or null when the proof does
     * not recover. Nulls are cached too — a malformed proof must not cost a
     * bridge hop per message. Crude bound, not an LRU: a channel session sees
     * far fewer distinct publishers than this, and an entry costs one bridge
     * round-trip to rebuild. Never invalidated on account switch on purpose:
     * an entry is a pure function of public wire data (ecrecover of a public
     * proof), identical for every viewer, so it cannot go stale.
     */
    private val proofAccounts = HashMap<String, String?>()

    /**
     * THE single place where "who sent this" is decided (web attachAccount):
     *
     *     account = proof ? ecrecover(proof, digest(publisherId)) : publisherId
     *
     * The fallback keeps pre-migration history working — old messages carry no
     * proof, and back then publisherId WAS the wallet, so both formats land in
     * the same identifier space. It is also the failure mode: a proof that
     * does not recover falls back to the ephemeral address, an identity that
     * owns nothing and matches nobody (D10b).
     *
     * `sender` is overwritten as a DERIVED alias of the account — the new wire
     * format does not send it, dozens of call sites still read it, and it must
     * never again come from the wire: a payload beside a proof could otherwise
     * name someone it cannot prove. For legacy messages the overwrite is a
     * no-op (publisherId was the wallet, which is what `sender` already says).
     *
     * DM paths do NOT come through here: there the proof lives inside the
     * sealed envelope and identity is decided after opening it (port step 4).
     */
    private suspend fun attachAccount(data: JSONObject, publisherId: String?): String? {
        var account = publisherId?.lowercase()?.ifEmpty { null }
        val proof = data.optString("proof").ifEmpty { null }
        if (proof != null && account != null) {
            recoverProofAccount(account, proof)?.let { account = it }
        }
        if (account != null) {
            data.put("account", account)
            data.put("sender", account)
        }
        return account
    }

    /**
     * ecrecover of a publisher proof, through the shared (publisherId|proof)
     * cache. Null when the proof does not recover — the caller falls back to
     * the publisherId, D10b's failure mode. A bridge failure is NOT cached
     * (unavailable ≠ invalid), so the next message retries instead of pinning
     * the ephemeral id.
     */
    private suspend fun recoverProofAccount(publisherId: String, proof: String): String? {
        val key = "$publisherId|$proof"
        val cached = synchronized(proofAccounts) {
            if (proofAccounts.containsKey(key)) proofAccounts[key] to true else null to false
        }
        if (cached.second) return cached.first
        val rec = try {
            val res = bridge.call(
                "recoverPublisherProofBatch",
                JSONObject().put("items", JSONArray().put(
                    JSONObject().put("publisherId", publisherId).put("proof", proof)
                ))
            )
            val arr = res.optJSONArray("accounts")
            if (arr == null || arr.isNull(0)) null else arr.optString(0).ifEmpty { null }
        } catch (e: Exception) { return null }
        synchronized(proofAccounts) {
            if (proofAccounts.size >= 500) proofAccounts.clear()
            proofAccounts[key] = rec
        }
        return rec
    }

    private suspend fun handleContent(
        channel: Channel,
        contentAny: Any?,
        meta: JSONObject,
        historical: Boolean,
        generation: Int,
        streamId: String = channel.messageStreamId,
        partition: Int = StreamConstants.P_MESSAGES
    ) {
        // Password channels: the wire is a base64 string — decrypt first
        var data: JSONObject = when (contentAny) {
            is JSONObject -> contentAny
            is String -> {
                val pwd = channel.password ?: return
                try { JSONObject(PomboCrypto.decryptString(contentAny, pwd)) } catch (e: Exception) { return }
            }
            else -> return
        }
        // Native channels: epoch-encrypted envelope (N-A). Unknown kid is NOT
        // an error (§7.9) — skip; storage-backed messages come back via the
        // refresh fired when the key is adopted.
        if (com.pombo.android.core.EpochKeyCrypto.isEpochEnvelope(data)) {
            if (!isEpochChannel(channel)) return
            val keysId = channel.keysStreamId.ifEmpty {
                StreamConstants.deriveKeysId(channel.messageStreamId)
            }
            data = epochKeys.tryDecrypt(
                channel.messageStreamId, keysId, data,
                // Kid freshness (N-C, gated only): live = current epoch (short
                // tolerance), history = the kid in force at the timestamp
                gated = channel.type == "gated",
                live = !historical,
                timestamp = meta.optLong("timestamp", 0L)) ?: return
        }
        // Single gate for every write below — messages, images, reactions and
        // overrides all funnel through here, from both the resend and the live
        // subscription. Nothing reaches the open channel's state past this line
        // unless it belongs to the open channel.
        //
        // Two INDEPENDENT conditions, either of which blocks (this is the web's
        // defence in depth — its app.js dispatcher gates every event on
        // `data.streamId === currentStreamId`, on top of the generation fence):
        //   1. the generation fence — catches stale async work, including the
        //      same channel reopened (streamId would still match, so this is the
        //      only check that covers that case);
        //   2. an identity check against the channel actually on screen right
        //      now — a cheap, counter-independent net, so a bug in the
        //      generation bookkeeping still cannot paint into the wrong channel.
        val forOpenChannel = channel.messageStreamId == _current.value?.messageStreamId
        if (!stillCurrent(generation) || !forOpenChannel) {
            // A live message that landed in the switching window is still news
            // for the channel it was addressed to, so badge it rather than drop
            // it. History replays are not news and must stay silent.
            if (!historical && data.optString("type") == "text") {
                unreadStore.increment(channel.messageStreamId)
                // Move the watermark past it as well, or the next activity scan
                // finds the same message still "newer than last seen" and adds
                // a second point for it.
                val ts = meta.optLong("timestamp").takeIf { it > 0 }
                    ?: data.optLong("timestamp")
                if (ts > 0) unreadStore.setWatermark(channel.messageStreamId, ts)
            }
            return
        }
        // account, not publisherId: with ephemeral publishers the transport
        // identity is a throwaway key, and everything below that names a user
        // — reaction authorship, override authority, presence, typing — must
        // read the account the proof recovers to. For legacy traffic the two
        // are the same address, so nothing changes for old history (D10b).
        // Gated: the transport is the clone for everyone — the author is the
        // envelope signer, resolved by gatedAuthor (drop on failure; D10c).
        val author = if (channel.type == "gated") {
            gatedAuthor(channel, streamId, meta) ?: return
        } else meta.optString("publisherId")
        val account = attachAccount(data, author)

        when (data.optString("type")) {
            "text" -> handleText(channel, data, historical)
            "file_announce" -> handleFileAnnounce(channel, data)
            "storage_file_announce" -> handleStorageFileAnnounce(channel, data)
            "image" -> handleImageManifest(channel, data)
            "image_chunk" -> handleImageChunk(data)
            "reaction" -> {
                val user = account ?: return
                applyReaction(data.optString("messageId"), data.optString("emoji"), user, data.optString("action") == "add")
            }
            "edit", "delete" -> applyOverride(data, account)
            "presence" -> {
                val user = account ?: return
                synchronized(online) {
                    online[user] = data.optLong("lastActive", System.currentTimeMillis())
                    _onlineCount.value = online.size
                    // The web lists who is online, not just how many, so the
                    // nickname that rides along with the heartbeat is kept.
                    onlineNames[user] = data.optStringOrNull("nickname")
                    _onlineUsers.value = online.keys.map {
                        OnlineUser(address = it, nickname = onlineNames[it])
                    }.sortedBy { it.address }
                }
            }
            "typing" -> {
                val me = myAddress()?.lowercase()
                if (account != null && account != me) {
                    markTyping(account, data.optStringOrNull("nickname"))
                }
            }
            // Owner's low-latency moderation signal (web channels.js
            // handleControlMessage): the full ADMIN_STATE snapshot rides the
            // ephemeral stream so members converge without waiting for the
            // next 30s poller tick — with no live -3 subscription, this IS
            // the real-time path. applyAdminMessage re-runs the web's own
            // checks — type, publisher == owner, rev — so the signal cannot
            // inject state a direct -3 publish couldn't.
            "admin_invalidate" -> {
                val snapshot = data.optJSONObject("snapshot") ?: return
                // The authority check inside applyAdminMessage reads the
                // snapshot object, but the proof (and thus the resolved
                // account) lives on the OUTER admin_invalidate payload —
                // carry it over, or an owner publishing under an ephemeral
                // key (step 5) fails the owner check in silence.
                account?.let { snapshot.put("account", it) }
                applyAdminMessage(channel, snapshot, meta, generation)
            }
        }
    }

    // ---- P2P shared file (wire type 'file_announce') ----

    /**
     * Turns a file announcement into a bubble. Nothing is downloaded here: the
     * announcement carries only the description and the piece hashes, and the
     * user decides whether to fetch the content — a channel could hold hundreds
     * of announcements and auto-fetching would be both a data bill and a way to
     * fill someone's storage from a message.
     */
    private fun handleFileAnnounce(channel: Channel, data: JSONObject) {
        val messageId = data.optString("id").ifEmpty { return }
        // Rejected rather than shown as a broken bubble: FileMetadata.from
        // refuses an announcement without one hash per piece, and without those
        // a download could never be verified.
        val metadata = com.pombo.android.core.MediaController.FileMetadata
            .from(data.optJSONObject("metadata")) ?: run {
            Log.w(TAG, "unusable file_announce $messageId — dropped")
            return
        }
        if (_messages.value.any { it.id == messageId }) return

        val sender = data.optString("sender").ifEmpty { return }
        mergeMessages(listOf(UiMessage(
            id = messageId,
            text = "",
            sender = sender,
            senderName = data.optStringOrNull("senderName"),
            timestamp = data.optLong("timestamp", 0L),
            mine = sender.equals(myAddress(), ignoreCase = true),
            file = metadata,
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender)
        )))
        if (!sender.equals(myAddress(), ignoreCase = true)) resolveEnsFor(sender)
        verifyFileAnnounceAsync(data)
    }

    // ---- Persistent File Sharing (wire type 'storage_file_announce') ----

    /** Turns a storage-file announcement into a bubble (download deferred to phase 3). */
    private fun handleStorageFileAnnounce(channel: Channel, data: JSONObject) {
        val messageId = data.optString("id").ifEmpty { return }
        val metadata = com.pombo.android.core.StorageMedia.StorageFileMetadata
            .from(data.optJSONObject("metadata")) ?: run {
            Log.w(TAG, "unusable storage_file_announce $messageId — dropped")
            return
        }
        // Our own echo would double the optimistic bubble; the id guards it.
        if (_messages.value.any { it.id == messageId }) return
        val sender = data.optString("sender").ifEmpty { return }
        mergeMessages(listOf(UiMessage(
            id = messageId,
            text = "",
            sender = sender,
            senderName = data.optStringOrNull("senderName"),
            timestamp = data.optLong("timestamp", 0L),
            mine = sender.equals(myAddress(), ignoreCase = true),
            storageFile = metadata,
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender)
        )))
        if (!sender.equals(myAddress(), ignoreCase = true)) resolveEnsFor(sender)
        verifyStorageAnnounceAsync(data)
    }

    /** File/channel of a storage transfer + (uploads) the optimistic bubble to restore. */
    data class StorageTransferInfo(
        val fileName: String,
        val channelName: String,
        val messageStreamId: String,
        /** Upload-only: re-inserted if the user returns to the channel mid-upload. */
        val uploadBubble: UiMessage? = null
    )

    /**
     * transferId -> [StorageTransferInfo]. Storage transfers survive a channel
     * switch (the engine is standalone), so the Active Transfers list needs the
     * file/channel names even when it is no longer the open channel — the
     * progress snapshots do not carry them.
     */
    private val storageTransferChannel = java.util.concurrent.ConcurrentHashMap<String, StorageTransferInfo>()

    fun storageTransferInfo(transferId: String): StorageTransferInfo? = storageTransferChannel[transferId]

    /** Distinct channel stream ids of the storage transfers currently in flight. */
    fun activeStorageTransferStreams(): Set<String> {
        val tids = storageMedia.uploads.value.filterValues { it.stage != "done" && it.error == null }.keys +
            storageMedia.downloads.value.filterValues { it.status == "downloading" }.keys
        return tids.mapNotNull { storageTransferChannel[it]?.messageStreamId }.toSet()
    }

    /** A human label for a channel/DM (Active Transfers list). */
    private fun channelDisplayName(channel: Channel): String = when {
        channel.name.isNotEmpty() -> channel.name
        channel.type == "dm" -> channel.peerAddress?.let { ensStore.cachedName(it) ?: it.take(8) } ?: "DM"
        else -> channel.messageStreamId.substringAfterLast('/').take(12)
    }

    /**
     * Re-inserts the optimistic bubbles for storage UPLOADS still running in
     * [channel]. A channel switch clears the timeline, but an in-progress upload
     * has not published its announce yet, so nothing else would bring the bubble
     * back until it finishes. Downloads need no restore — their announce reloads
     * from history and the download-progress state drives the bubble.
     */
    private fun restoreStorageUploadBubbles(channel: Channel) {
        val bubbles = storageMedia.uploads.value.keys.mapNotNull { tid ->
            val info = storageTransferChannel[tid] ?: return@mapNotNull null
            if (info.messageStreamId != channel.messageStreamId) return@mapNotNull null
            val b = info.uploadBubble ?: return@mapNotNull null
            if (_messages.value.any { it.id == b.id }) null else b
        }
        if (bubbles.isNotEmpty()) mergeMessages(bubbles)
    }

    /**
     * Shares a file in the open channel over the storage nodes (Persistent File
     * Sharing). The engine publishes chunks, verifies & repairs, then signs and
     * publishes the announce; here we only own the optimistic bubble and the
     * timeline. Progress is observed via [storageMedia].uploads, keyed by the
     * bubble's transferId.
     */
    suspend fun sendStorageFile(source: com.pombo.android.core.StorageMedia.Source) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (source.size <= 0) throw IllegalArgumentException("File is empty")
        val sender = myAddress() ?: throw IllegalStateException("No identity")
        val isDm = channel.type == "dm"
        // DM: resolve the pair's key up front — the chunks seal with it and the
        // announce rides the ECDH envelope to the peer's inbox.
        val dmPeerPk = if (isDm) {
            val peer = channel.peerAddress ?: throw IllegalStateException("DM has no peer")
            peerPubKey(peer) ?: throw IllegalStateException("Peer public key unavailable")
        } else null

        val id = Protocol.generateMessageId()
        val tid = Protocol.generateMessageId()
        val timestamp = System.currentTimeMillis()
        val draft = com.pombo.android.core.StorageMedia.StorageFileMetadata(
            transferId = tid, fileName = source.fileName, fileType = source.fileType,
            originalSize = source.size, compressedSize = null, compression = "none",
            totalChunks = null, chunkDataSize = null,
            chunkPartitions = StreamConstants.STORAGE_CHUNK_PARTITIONS,
            firstChunkPartition = StreamConstants.STORAGE_FIRST_CHUNK_PARTITION,
            firstChunkTs = null, lastChunkTs = null, storedChunks = null, encSalt = null
        )
        // Optimistic bubble before the upload, like every other send path. Kept in
        // storageTransferChannel so it can be re-inserted if the user leaves and
        // returns to this channel mid-upload (the announce is not published yet).
        val bubble = UiMessage(
            id = id, text = "", sender = sender, senderName = myUsername(),
            timestamp = timestamp, mine = true, pending = true, verified = true,
            storageFile = draft,
            ensName = ensStore.cachedName(sender), ensAvatar = ensStore.cachedAvatar(sender)
        )
        storageTransferChannel[tid] = StorageTransferInfo(source.fileName, channelDisplayName(channel), channel.messageStreamId, bubble)
        mergeMessages(listOf(bubble))
        try {
            val result = storageMedia.sendFile(
                messageStreamId = channel.messageStreamId,
                source = source,
                password = channel.password,
                isDm = isDm,
                dmPeerPublicKey = dmPeerPk,
                // public/password channels publish chunks under the channel's
                // ephemeral identity (same as the announce); native/readOnly
                // keep the account (D3), same rule as publishChannel.
                channelEphemeral = !isDm && !isEpochChannel(channel) && !channel.readOnly,
                messageId = id,
                transferId = tid
            )
            // Replace the draft with the confirmed metadata from the announce.
            val finalMeta = com.pombo.android.core.StorageMedia.StorageFileMetadata
                .from(result.announce.optJSONObject("metadata"))
            _messages.value = _messages.value.map {
                if (it.id == id) it.copy(pending = false, storageFile = finalMeta ?: it.storageFile) else it
            }
            if (isDm) {
                // A DM cannot be replayed from the peer's inbox — persist the whole
                // (small) announce so this device keeps rendering the bubble across
                // restarts. No bulky fields here (unlike mesh pieceHashes).
                sentDmStore.add(channel.messageStreamId, result.announce)
                onLocalStateChanged()
            }
        } catch (e: Exception) {
            _messages.value = _messages.value.filterNot { it.id == id }
            throw e
        }
    }

    /**
     * Starts downloading the storage-shared file announced by [messageId] in the
     * open channel. The engine handles resume and the completed-file handoff; the
     * bubble reflects [storageMedia].downloads. Non-DM only for now.
     */
    fun downloadStorageFile(messageId: String) {
        scope.launch {
            val channel = _current.value ?: return@launch
            val msg = _messages.value.firstOrNull { it.id == messageId } ?: return@launch
            val meta = msg.storageFile ?: return@launch
            storageTransferChannel[meta.transferId] = StorageTransferInfo(meta.fileName, channelDisplayName(channel), channel.messageStreamId)
            try {
                if (channel.type == "dm") {
                    // Chunks live on OUR own inbox (the sender published there); the
                    // channel's streamId points at the PEER's inbox. Open them with
                    // the sender's key (= the peer for this DM).
                    val me = myAddress()?.lowercase() ?: return@launch
                    val peer = channel.peerAddress ?: return@launch
                    val peerPk = peerPubKey(peer) ?: throw IllegalStateException("Peer public key unavailable")
                    storageMedia.downloadFile(
                        channel.messageStreamId, meta, msg.timestamp, channel.password,
                        isDm = true, dmInboxStreamId = "$me/Pombo-DM-1", peerPublicKey = peerPk
                    )
                } else {
                    storageMedia.downloadFile(channel.messageStreamId, meta, msg.timestamp, channel.password)
                }
            } catch (e: Exception) {
                Log.w(TAG, "storage download failed: ${e.message}")
            }
        }
    }

    /** The completed storage download's file on disk, or null (for saving). */
    fun storageCompletedFile(transferId: String): java.io.File? = storageMedia.completedFile(transferId)

    /**
     * Starts fetching the file announced by [messageId] in the open channel.
     * Safe to call twice — a download already running is left alone.
     */
    /**
     * Cancels a running download from the Active Transfers list. Runs under
     * the channel-switch mutex because it may drop media partitions, and those
     * only ever change inside it.
     */
    fun cancelTransfer(fileId: String) {
        scope.launch {
            channelSwitchMutex.withLock {
                releaseMediaIfIdle(media.cancelDownload(fileId))
            }
        }
    }

    /** Stops serving a file, durably, from the Active Transfers list. */
    fun stopSeeding(fileId: String) {
        scope.launch {
            channelSwitchMutex.withLock {
                releaseMediaIfIdle(media.stopSeeding(fileId))
            }
        }
    }

    /**
     * Drops the media partitions a finished/cancelled transfer was holding,
     * unless something still needs them: another transfer on the same stream,
     * or the channel being on screen (its open handler owns them then).
     */
    private suspend fun releaseMediaIfIdle(ref: Pair<String, Boolean>?) {
        val (messageStreamId, isDm) = ref ?: return
        if (isDm) {
            if (_current.value?.type != "dm" && !media.hasActiveDMTransfers()) {
                media.myDmEphemeralId()?.let { media.releaseMediaPartitions(it) }
            }
            return
        }
        val onScreen = _current.value?.messageStreamId == messageStreamId
        if (!onScreen && !media.hasActiveTransfers(messageStreamId)) {
            media.releaseMediaPartitions(StreamConstants.deriveEphemeralId(messageStreamId))
        }
    }

    fun downloadFile(messageId: String) {
        val channel = _current.value ?: return
        val metadata = _messages.value.firstOrNull { it.id == messageId }?.file ?: return
        // A lean record (own DM manifest persisted without pieceHashes) must
        // never start a download: with no expected hash, writePiece would
        // accept whatever bytes arrive. The full announce is what peers hold.
        if (metadata.pieceHashes.size != metadata.pieceCount) {
            Log.w(TAG, "refusing download of ${metadata.fileId}: manifest has no piece hashes")
            return
        }
        if (channel.type == "dm") {
            // Sealed-sender pieces open with OUR OWN static key and the epk in
            // each envelope — no peer-key registration, no publisher hint. The
            // bridge does it inline on the -2/P2 callback.
            scope.launch {
                media.startDownload(
                    messageStreamId = channel.messageStreamId,
                    metadata = metadata,
                    password = null,
                    isDm = true
                )
            }
            return
        }
        media.startDownload(
            messageStreamId = channel.messageStreamId,
            metadata = metadata,
            password = channel.password,
            isDm = false
        )
    }

    // ---- chunked image receive (transport 'chunked' v2) ----

    /**
     * Fills an image from the local ledger. Chunks live only as long as the
     * stream's retention, so history older than that would otherwise render as
     * a permanent placeholder even though we hold the bytes.
     */
    /**
     * Repaints any on-screen placeholders whose blobs just landed (called by
     * the sync pipeline after a blob import). A no-op for ids that belong to
     * closed conversations — their bubbles hydrate from the ledger on open.
     */
    fun hydrateImages(imageIds: List<String>) {
        imageIds.forEach { hydrateFromLedger(it) }
    }

    private fun hydrateFromLedger(imageId: String) {
        if (imageId.isEmpty() || imageId in deletedImageIds) return
        scope.launch {
            val dataUrl = blobStore.load(imageId) ?: return@launch
            val (bytes, mime) = com.pombo.android.core.ImageBlobStore.fromDataUrl(dataUrl) ?: return@launch
            synchronized(this@ChannelManager) {
                _messages.value = _messages.value.map {
                    if (it.imageId == imageId && it.imageBytes == null) {
                        it.copy(imageBytes = bytes, imageMime = mime, pending = false)
                    } else it
                }
            }
        }
    }

    /**
     * @param showBubble whether the placeholder belongs on the visible timeline.
     *   False for a DM arriving while another channel is open: the chunk
     *   assembly still has to be registered, but the bubble must not be merged
     *   into a timeline it does not belong to.
     */
    private fun handleImageManifest(channel: Channel, data: JSONObject, showBubble: Boolean = true) {
        if (data.optString("transport") != "chunked" || data.optInt("v") != 2) return
        val imageId = data.optString("imageId").ifEmpty { return }
        if (imageId in deletedImageIds) return
        val messageId = data.optString("id").ifEmpty { return }
        val hashes = data.optJSONArray("chunkHashes") ?: return
        val chunkCount = data.optInt("chunkCount")
        if (chunkCount <= 0 || hashes.length() != chunkCount) return

        // Refuse oversized manifests before allocating anything (web media.js
        // enforces the same ceiling at assembly time). A hostile peer must not
        // be able to make us buffer an arbitrary number of bytes.
        val finalMime = data.optString("finalMime", "image/jpeg")
        val declaredSize = data.optInt("finalSizeBytes")
        val sizeLimit = if (finalMime == "image/gif") GIF_MAX_ASSEMBLED_BYTES else IMAGE_MAX_ASSEMBLED_BYTES
        if (declaredSize <= 0 || declaredSize > sizeLimit) return
        if (chunkCount > MAX_CHUNKS) return

        val sender = data.optString("sender").ifEmpty { return }
        val mine = sender.equals(myAddress(), ignoreCase = true)
        // Placeholder bubble (image loading) keyed by the message id.
        if (showBubble && _messages.value.none { it.id == messageId }) {
            mergeMessages(listOf(UiMessage(
                id = messageId, text = "", sender = sender,
                senderName = data.optStringOrNull("senderName"),
                timestamp = data.optLong("timestamp", 0L), mine = mine,
                isImage = true, imageId = imageId, imageMime = data.optString("finalMime", "image/jpeg"),
                // Same ENS treatment as handleText: seed from the cache and
                // resolve below. Without both, a sender whose only message on
                // screen is an image never showed a name or avatar at all —
                // and an image merged after another message's resolve patch
                // missed the patch for good.
                ensName = ensStore.cachedName(sender),
                ensAvatar = ensStore.cachedAvatar(sender)
            )))
            // If we already hold the bytes, the placeholder resolves without
            // waiting for chunks that may no longer exist on the storage node.
            hydrateFromLedger(imageId)
            if (!mine) resolveEnsFor(sender)
        }

        // Chunks may arrive before the manifest (resend order): always (re)bind the message id.
        val p = pendingImages.getOrPut(imageId) { PendingImage(messageId) }
        p.messageId = messageId
        p.chunkCount = chunkCount
        p.chunkHashes = (0 until hashes.length()).map { hashes.optString(it) }
        p.assembledSha256 = data.optString("assembledSha256")
        p.finalSizeBytes = declaredSize
        p.finalMime = finalMime
        p.streamId = channel.messageStreamId
        p.haveManifest = true
        p.manifestTs = data.optLong("timestamp", 0L)
        // Image manifests are signed too (web verifyMessage branches on
        // transport 'chunked' and hashes the manifest fields).
        verifyImageManifestAsync(data)
        tryAssemble(imageId)
        // A manifest whose chunks never finish used to wait forever — the web
        // re-queries a window around it instead (channels.js:3842-3930).
        if (channel.type != "dm") scheduleChunkRecovery(channel, imageId)
    }

    /**
     * Targeted chunk recovery: when the manifest is in hand but chunks are
     * still missing after a grace period, re-resend a window around the
     * manifest's timestamp and feed any matching chunks back through the
     * normal path. Up to 3 attempts (web: 3 fetch tries over up to 20 rounds;
     * here the resend IS the transfer, so 3 windows carry the same coverage).
     * Channels only — a DM's chunks replay through the inbox router.
     */
    private fun scheduleChunkRecovery(channel: Channel, imageId: String) {
        val pending = pendingImages[imageId] ?: return
        if (pending.recoveryScheduled) return
        pending.recoveryScheduled = true
        val generation = switchGeneration
        scope.launch {
            while (true) {
                delay(CHUNK_RECOVERY_DELAY_MS)
                val p = pendingImages[imageId] ?: return@launch  // assembled or swept
                if (imageId in deletedImageIds || !stillCurrent(generation)) return@launch
                if (p.recoveryAttempts >= CHUNK_RECOVERY_MAX_ATTEMPTS) return@launch
                p.recoveryAttempts++
                val ts = p.manifestTs.takeIf { it > 0 } ?: return@launch
                try {
                    val res = bridge.call("resendWindow", JSONObject()
                        .put("streamId", channel.messageStreamId)
                        .put("partition", StreamConstants.P_MESSAGES)
                        .put("before", ts + CHUNK_RECOVERY_WINDOW_MS)
                        .put("windowMs", CHUNK_RECOVERY_WINDOW_MS * 2)
                        .put("budgetMs", 20_000), 30_000)
                    val arr = res.optJSONArray("messages") ?: continue
                    val contents = predecrypt(arr, channel.password)
                    for (i in 0 until arr.length()) {
                        val data = contents[i] as? JSONObject ?: continue
                        if (data.optString("type") == "image_chunk" &&
                            data.optString("imageId") == imageId
                        ) handleImageChunk(data)
                    }
                    android.util.Log.d(
                        TAG,
                        "chunk recovery for …${imageId.takeLast(8)}: attempt ${p.recoveryAttempts}, " +
                            "have ${p.chunks.size}/${p.chunkCount}"
                    )
                } catch (e: Exception) { /* next attempt */ }
            }
        }
    }

    /** Drops every trace of a deleted image (web blocks the same five paths). */
    private fun tombstoneImage(imageId: String) {
        deletedImageIds.add(imageId)
        pendingImages.remove(imageId)
        scope.launch { blobStore.forget(imageId) }
    }

    private fun handleImageChunk(data: JSONObject) {
        if (data.optInt("v") != 2) return
        val imageId = data.optString("imageId").ifEmpty { return }
        if (imageId in deletedImageIds) return
        val index = data.optInt("chunkIndex", -1)
        if (index < 0) return
        val b64 = data.optString("data").ifEmpty { return }
        val bytes = try { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) } catch (e: Exception) { return }
        if (bytes.size > MAX_CHUNK_BYTES) return
        if (index >= MAX_CHUNKS) return
        // Chunks routinely arrive before their manifest, so an entry may be
        // created here — bound the buffered total to keep that path safe too.
        val p = pendingImages.getOrPut(imageId) { PendingImage("") }
        if (p.chunks.values.sumOf { it.size } + bytes.size > GIF_MAX_ASSEMBLED_BYTES) return
        p.chunks[index] = bytes
        p.lastSeen = System.currentTimeMillis()
        sweepStaleAssemblies()
        tryAssemble(imageId)
    }

    /** Verifies and assembles a chunked image once all chunks + manifest are present. */
    private fun tryAssemble(imageId: String) {
        val p = pendingImages[imageId] ?: return
        if (!p.haveManifest || p.chunks.size < p.chunkCount) return
        val digest = try { java.security.MessageDigest.getInstance("SHA-256") } catch (e: Exception) { return }

        val ordered = ArrayList<ByteArray>(p.chunkCount)
        var total = 0
        for (i in 0 until p.chunkCount) {
            val bytes = p.chunks[i] ?: return
            val hex = digest.digest(bytes).joinToString("") { "%02x".format(it) }
            digest.reset()
            if (hex != p.chunkHashes.getOrNull(i)) return  // hash mismatch — keep waiting for redelivery
            ordered.add(bytes)
            total += bytes.size
        }
        if (total != p.finalSizeBytes) return
        val assembled = ByteArray(total)
        var off = 0
        for (b in ordered) { System.arraycopy(b, 0, assembled, off, b.size); off += b.size }
        val assembledHex = digest.digest(assembled).joinToString("") { "%02x".format(it) }
        if (assembledHex != p.assembledSha256) return

        pendingImages.remove(imageId)
        // Web media.js dispatches assembled data by imageId — order-independent.
        _messages.value = _messages.value.map {
            if (it.imageId == imageId) it.copy(imageBytes = assembled, imageMime = p.finalMime, pending = false) else it
        }

        // Ledger it, exactly as media.js does after a successful assembly. This
        // covers RECEIVED images too, which until now lived only in memory:
        // reopening the chat re-fetched them, and once retention dropped the
        // chunks they were gone for good. Saved unsynced so blob sync carries
        // them to this account's other devices.
        val streamId = p.streamId
        if (streamId.isNotEmpty()) {
            scope.launch {
                blobStore.save(
                    imageId, streamId,
                    com.pombo.android.core.ImageBlobStore.toDataUrl(assembled, p.finalMime),
                    synced = false
                )
                onLocalStateChanged()
            }
        }
    }

    private fun handleText(channel: Channel, data: JSONObject, historical: Boolean) {
        val id = data.optString("id").ifEmpty { return }
        val sender = data.optString("sender").ifEmpty { return }
        val me = myAddress()
        val mine = sender.equals(me, ignoreCase = true)
        if (mine && _messages.value.any { it.id == id }) {
            confirmMessage(id)
            return
        }
        val msg = UiMessage(
            id = id,
            text = data.optString("text"),
            sender = sender,
            senderName = data.optStringOrNull("senderName"),
            timestamp = data.optLong("timestamp", 0L),
            mine = mine,
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender),
            replyTo = data.optJSONObject("replyTo")?.let {
                val rid = it.optString("id")
                if (rid.isEmpty()) null else ReplyRef(
                    id = rid,
                    sender = it.optString("sender"),
                    senderName = it.optStringOrNull("senderName"),
                    text = it.optString("text")
                )
            }
        )
        mergeMessages(listOf(msg))
        if (!mine) resolveEnsFor(sender)
        verifyAsync(channel, data, historical)
        // Only live messages from other people are worth a notification —
        // replaying history on open must not fire a burst of them.
        if (!mine && !historical) {
            onIncomingMessage(channel, sender, msg.text)
            // No unread badge here: reaching this point means the channel IS
            // open, and the web treats the open channel as read on every render.
            // Messages that arrive for a channel the user just left are badged
            // by the fence in [handleContent] instead.
        }
    }

    /** Signature check for chunked-image manifests (same trust levels as text). */
    private fun verifyImageManifestAsync(data: JSONObject) {
        val id = data.optString("id")
        val signature = data.optString("signature")
        // Same discriminant as text (see verifyAsync): absent = current
        // format, identity already established at ingest.
        if (signature.isEmpty()) { markVerifiedFromAccount(id, data); return }
        scope.launch {
            try {
                val hashes = data.optJSONArray("chunkHashes") ?: return@launch
                val canonical = Protocol.canonicalImageManifestData(
                    id = id,
                    imageId = data.optString("imageId"),
                    sender = data.optString("sender"),
                    timestamp = data.optLong("timestamp", 0L),
                    channelId = data.optString("channelId"),
                    originalMime = data.optString("originalMime"),
                    finalMime = data.optString("finalMime"),
                    finalSizeBytes = data.optInt("finalSizeBytes"),
                    chunkCount = data.optInt("chunkCount"),
                    chunkHashes = (0 until hashes.length()).map { hashes.optString(it) },
                    assembledSha256 = data.optString("assembledSha256"),
                    qualityUsed = if (data.isNull("qualityUsed")) null else data.optDouble("qualityUsed"),
                    preservedOriginal = data.optBoolean("preservedOriginal", false),
                    convertedTo = if (data.isNull("convertedTo")) null
                        else data.optString("convertedTo").ifEmpty { null }
                )
                val res = bridge.call("verifyCanonical", JSONObject()
                    .put("data", canonical).put("signature", signature))
                val recovered = res.optString("address")
                val valid = recovered.equals(data.optString("sender"), ignoreCase = true)
                markVerified(id, valid)
                if (valid) applyTrustLevel(id, recovered)
            } catch (e: Exception) {
                markVerified(id, false)
            }
        }
    }

    /**
     * Signature check for a P2P file/video announce.
     *
     * The announce IS signed on send (canonicalFileManifestData, see
     * sendFile) — the receive side simply never checked it, so a file bubble
     * carried no badge at all while text and images did. Same trust ladder as
     * text, and the same batched queue: nothing here is announce-specific
     * beyond rebuilding the canonical string the sender signed.
     */
    private fun verifyFileAnnounceAsync(data: JSONObject) {
        val id = data.optString("id")
        val signature = data.optString("signature")
        // Same discriminant as text (see verifyAsync): absent = current
        // format, identity already established at ingest.
        if (signature.isEmpty()) { markVerifiedFromAccount(id, data); return }
        val meta = data.optJSONObject("metadata")
        val hashes = meta?.optJSONArray("pieceHashes")
        if (meta == null || hashes == null) { markVerified(id, null); return }
        val canonical = Protocol.canonicalFileManifestData(
            id = id,
            sender = data.optString("sender"),
            timestamp = data.optLong("timestamp", 0L),
            channelId = data.optString("channelId"),
            fileId = meta.optString("fileId"),
            fileName = meta.optString("fileName"),
            fileSize = meta.optLong("fileSize"),
            fileType = meta.optString("fileType"),
            pieceCount = meta.optInt("pieceCount"),
            pieceHashes = (0 until hashes.length()).map { hashes.optString(it) }
        )
        enqueueVerify(PendingVerify(id, canonical, signature, data.optString("sender")))
    }

    /**
     * Signature check for a Persistent File Sharing announce
     * (canonicalStorageFileManifestData, signed in StorageMedia.sendFile).
     *
     * Every optional field has to come back as absent-vs-present exactly as the
     * sender wrote it: the canonical emits `null` for a missing value, so an
     * absent `storedChunks` read as 0 would produce a different string and fail
     * a perfectly good signature. Hence isNull() rather than opt* defaults.
     */
    private fun verifyStorageAnnounceAsync(data: JSONObject) {
        val id = data.optString("id")
        val signature = data.optString("signature")
        // Same discriminant as text (see verifyAsync): absent = current
        // format, identity already established at ingest.
        if (signature.isEmpty()) { markVerifiedFromAccount(id, data); return }
        val meta = data.optJSONObject("metadata") ?: run { markVerified(id, null); return }
        fun str(k: String): String? = if (meta.isNull(k)) null else meta.optString(k)
        fun int(k: String): Int? = if (meta.isNull(k)) null else meta.optInt(k)
        fun long(k: String): Long? = if (meta.isNull(k)) null else meta.optLong(k)
        val canonical = Protocol.canonicalStorageFileManifestData(
            id = id,
            sender = data.optString("sender"),
            timestamp = data.optLong("timestamp", 0L),
            channelId = data.optString("channelId"),
            transferId = str("transferId"),
            fileName = str("fileName"),
            fileType = str("fileType"),
            originalSize = long("originalSize"),
            compressedSize = long("compressedSize"),
            compression = str("compression"),
            totalChunks = int("totalChunks"),
            chunkDataSize = int("chunkDataSize"),
            chunkPartitions = int("chunkPartitions"),
            firstChunkPartition = int("firstChunkPartition"),
            firstChunkTs = long("firstChunkTs"),
            lastChunkTs = long("lastChunkTs"),
            storedChunks = int("storedChunks"),
            encSalt = str("encSalt")
        )
        enqueueVerify(PendingVerify(id, canonical, signature, data.optString("sender")))
    }

    /**
     * Trust level for a verified sender (web identity.js _getTrustLevelSync):
     * 2 = trusted contact, 1 = has ENS, 0 = valid signature only.
     */
    private fun applyTrustLevel(messageId: String, address: String) {
        val level = when {
            isTrustedContact(address) -> 2
            ensStore.cachedName(address) != null -> 1
            else -> 0
        }
        // Via patchMessage so a batched (historical) message — still in the
        // merge buffer at verify time — gets its trust level too, not just
        // live messages (the trusted-contact star was missing on history).
        patchMessage(messageId) { it.copy(trustLevel = level) }
    }

    /**
     * Verification discriminates on the PRESENCE of `signature`, no version
     * field (web identity.js verifyMessage):
     *
     *  - present → pre-migration message; verify the old way (the canonical
     *    hash functions are kept exactly for this).
     *  - absent  → current format (D6). Identity was already established at
     *    ingest — account = ecrecover(proof) — so trusting it here is not a
     *    weakening: the Streamr envelope authenticates the ephemeral publisher
     *    and the proof authenticates the account behind it. A third signature
     *    added no authority and re-exposed the address.
     *
     * The replay guard runs on BOTH paths — the window must not widen just
     * because the app-layer signature went away.
     */
    private fun verifyAsync(channel: Channel, data: JSONObject, historical: Boolean) {
        val id = data.optString("id")
        val signature = data.optString("signature")
        val timestamp = data.optLong("timestamp", 0L)
        // The replay guard only applies to genuinely fresh messages. The web
        // keys this off the message age (isRecentMessage = < 30s), not off the
        // delivery path: a live subscription routinely delivers older messages
        // (resend-on-subscribe, slow peers, clock skew) and those must not be
        // failed for being old.
        val isRecent = System.currentTimeMillis() - timestamp < RECENT_MESSAGE_MS
        if (isRecent && kotlin.math.abs(System.currentTimeMillis() - timestamp) > TIMESTAMP_TOLERANCE_MS) {
            markVerified(id, false)
            return
        }
        if (signature.isEmpty()) { markVerifiedFromAccount(id, data); return }
        val canonical = Protocol.canonicalMessageData(
            id, data.optString("text"), data.optString("sender"), timestamp, data.optString("channelId")
        )
        enqueueVerify(PendingVerify(id, canonical, signature, data.optString("sender")))
    }

    /**
     * The unsigned-path verdict: valid via the ingest-resolved account, badge
     * from the same trust ladder as a recovered signature. No account at all
     * (a payload that never went through attachAccount, or arrived with no
     * publisher) keeps the web's old "no badge, not a red flag" behaviour.
     */
    private fun markVerifiedFromAccount(id: String, data: JSONObject) {
        val account = data.optString("account").ifEmpty { null }
        if (account == null) { markVerified(id, null); return }
        markVerified(id, true)
        applyTrustLevel(id, account)
    }

    private data class PendingVerify(
        val id: String, val canonical: String, val signature: String, val sender: String
    )

    private val verifyQueue = ArrayList<PendingVerify>()
    @Volatile private var verifyFlushJob: Job? = null

    /**
     * Batched signature verification (web verifies in a 50-message batch per
     * 100ms window over a worker pool — channels.js:2888). The WebView is
     * single-threaded, so what the batch buys here is the per-message
     * evaluateJavascript round-trip: a 100-message resend used to cost 100
     * bridge calls; now it costs two or three.
     */
    private fun enqueueVerify(pv: PendingVerify) {
        synchronized(verifyQueue) { verifyQueue.add(pv) }
        if (verifyFlushJob?.isActive == true) return
        verifyFlushJob = scope.launch {
            delay(VERIFY_BATCH_WINDOW_MS)
            while (true) {
                val batch = synchronized(verifyQueue) {
                    val take = ArrayList(verifyQueue.take(VERIFY_BATCH_MAX))
                    repeat(take.size) { verifyQueue.removeAt(0) }
                    take
                }
                if (batch.isEmpty()) return@launch
                try {
                    val items = JSONArray()
                    batch.forEach {
                        items.put(JSONObject().put("data", it.canonical).put("signature", it.signature))
                    }
                    val res = bridge.call(
                        "verifyCanonicalBatch", JSONObject().put("items", items), 60_000
                    )
                    val addrs = res.optJSONArray("addresses") ?: JSONArray()
                    batch.forEachIndexed { i, pv2 ->
                        val recovered = addrs.optString(i)
                        val valid = recovered.isNotEmpty() &&
                            recovered.equals(pv2.sender, ignoreCase = true)
                        markVerified(pv2.id, valid)
                        if (valid) applyTrustLevel(pv2.id, recovered)
                    }
                } catch (e: Exception) {
                    batch.forEach { markVerified(it.id, false) }
                }
            }
        }
    }

    private fun addChannel(channel: Channel) {
        _channels.value = _channels.value + channel
        store.save(_channels.value)
    }

    /**
     * Applies an edit/delete override (web: handleOverrideMessage).
     * An override whose target has not been loaded yet is parked in
     * [pendingOverrides] — overrides live on P1 and are always newer than
     * their target, so scroll-up pagination surfaces the target long after
     * the override was already consumed. Without parking them, paginating
     * would resurrect messages the author had deleted.
     */
    @Synchronized
    private fun applyOverride(data: JSONObject, senderId: String?) {
        val targetId = data.optString("targetId")
        if (targetId.isEmpty()) return
        val original = _messages.value.find { it.id == targetId }
        if (original == null) {
            pendingOverrides[targetId] = data to senderId
            return
        }
        // Authority is the publisher: only the original author may edit or delete.
        if (senderId == null || original.sender.lowercase() != senderId.lowercase()) {
            Log.w(TAG, "Override REJECTED (${data.optString("type")} on ${targetId.take(8)}): " +
                "original.sender=${original.sender} override.sender=$senderId")
            return
        }
        // The DM inbox cache holds the message as it first arrived. Without
        // rewriting it here, reopening a DM merges the PRE-EDIT copy back in
        // and the old text (or a deleted message) flashes on screen until live
        // traffic happens to re-deliver the override. Keep the cache in step
        // with what the timeline shows.
        applyOverrideToInboxCache(senderId, targetId, data)
        if (data.optString("type") == "delete") {
            // Tombstone, not just a removal. Deleting by filtering alone leaves
            // no record, so any later merge carrying the same id — realtime
            // replay, a second resend, an overlapping pagination window —
            // resurrects the message. Whether that happens comes down to
            // arrival timing, which is why it showed on a phone and not on the
            // emulator. mergeMessages screens every id against this set.
            deletedIds.add(targetId)
            // Images need their own tombstone: the chunks/manifest carry the
            // imageId, not the message id, and would rebuild the bubble.
            if (original.isImage) original.imageId?.let { tombstoneImage(it) }
            _messages.value = _messages.value.filterNot { it.id == targetId }
        } else {
            applyEdit(targetId, data.optString("text"))
        }
    }

    /**
     * Mirror an edit/delete into the in-memory DM inbox cache.
     *
     * [dmReceived] is what [loadDmTimeline] merges on every open, and it
     * deduplicates by id — so a cached pre-edit copy would win forever.
     */
    private fun applyOverrideToInboxCache(senderId: String, targetId: String, data: JSONObject) {
        val list = synchronized(dmReceived) { dmReceived[senderId] } ?: return
        synchronized(list) {
            val i = list.indexOfFirst { it.id == targetId }
            if (i < 0) return
            if (data.optString("type") == "delete") {
                list.removeAt(i)
            } else {
                list[i] = list[i].copy(text = data.optString("text"), edited = true)
            }
        }
    }

    /** Re-runs parked overrides once their targets have been paginated in. */
    private fun applyPendingOverrides() {
        if (pendingOverrides.isEmpty()) return
        val loaded = _messages.value.map { it.id }.toSet()
        pendingOverrides.keys.filter { it in loaded }.forEach { targetId ->
            val (data, senderId) = pendingOverrides.remove(targetId) ?: return@forEach
            applyOverride(data, senderId)
        }
    }

    /**
     * Set while a batch of merges is in flight. Read and written only under the
     * same monitor as [mergeMessages].
     */
    private var mergeBuffer: MutableList<UiMessage>? = null

    /**
     * Collects every [mergeMessages] call made inside [block] into a single
     * merge at the end.
     *
     * Loading history merged one message at a time, and each merge rebuilt a
     * map of the whole conversation, re-sorted it and emitted a new list — so
     * an N-message resend cost N sorts and N recompositions on the main thread,
     * which is most of what made opening a busy channel feel heavy.
     */
    // Inline so history loaders can call the now-suspending handleContent
    // inside the block (a non-inline lambda cannot suspend its caller).
    private inline fun batchingMerges(block: () -> Unit) {
        val alreadyBatching = synchronized(this) {
            if (mergeBuffer != null) true else { mergeBuffer = mutableListOf(); false }
        }
        // Nested call: the outermost batch owns the flush.
        if (alreadyBatching) { block(); return }
        try {
            block()
        } finally {
            val buffered = synchronized(this) { mergeBuffer.also { mergeBuffer = null } }
            buffered?.let { mergeMessages(it) }
        }
    }

    @Synchronized
    private fun mergeMessages(incoming: List<UiMessage>) {
        if (incoming.isEmpty()) return
        // Inside a batch, park these and let the batch flush them in one pass.
        mergeBuffer?.let { it.addAll(incoming); return }
        // A message the author deleted must never come back, no matter which
        // path re-delivers it.
        val fresh = incoming.filterNot { it.id in deletedIds }
        if (fresh.isEmpty()) return
        val byId = LinkedHashMap<String, UiMessage>()
        _messages.value.forEach { byId[it.id] = it }
        fresh.forEach { msg ->
            val existing = byId[msg.id]
            byId[msg.id] = existing?.copy(pending = false) ?: msg
        }
        _messages.value = byId.values.sortedBy { it.timestamp }
        // A parked override waits for its target; the target may well arrive
        // here, live, long after history finished. Without this the override
        // sits unapplied and the deleted message stays on screen.
        applyPendingOverrides()
    }

    @Synchronized
    private fun confirmMessage(id: String) {
        _messages.value = _messages.value.map { if (it.id == id) it.copy(pending = false) else it }
    }

    /**
     * Apply [transform] to the message with [id] wherever it currently lives —
     * the batch buffer while a history load is in flight, AND/OR the live list.
     *
     * A synchronous verdict (markVerified / applyTrustLevel on the unsigned
     * path) runs right after [mergeMessages] parked the message in the buffer,
     * so touching only [_messages] would miss every batched (historical)
     * message: it would flush with `verified = null` and render no badge — the
     * "badge shows on live messages but not on history" bug. Patch both, so
     * the update survives the flush no matter which list holds it.
     */
    @Synchronized
    private fun patchMessage(id: String, transform: (UiMessage) -> UiMessage) {
        mergeBuffer?.let { buf ->
            for (i in buf.indices) if (buf[i].id == id) buf[i] = transform(buf[i])
        }
        if (_messages.value.any { it.id == id }) {
            _messages.value = _messages.value.map { if (it.id == id) transform(it) else it }
        }
    }

    private fun markVerified(id: String, valid: Boolean?) =
        patchMessage(id) { it.copy(verified = valid) }

    @Synchronized
    private fun applyEdit(id: String, newText: String) {
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(text = newText, edited = true) else it
        }
    }

    @Synchronized
    private fun applyReaction(messageId: String, emoji: String, user: String, add: Boolean) {
        if (messageId.isEmpty() || emoji.isEmpty()) return
        val map = _reactions.value.toMutableMap()
        val perMsg = (map[messageId] ?: emptyMap()).toMutableMap()
        val users = (perMsg[emoji] ?: emptySet()).toMutableSet()
        if (add) users.add(user) else users.remove(user)
        if (users.isEmpty()) perMsg.remove(emoji) else perMsg[emoji] = users
        if (perMsg.isEmpty()) map.remove(messageId) else map[messageId] = perMsg
        _reactions.value = map
    }

    /** Android optString returns the string "null" for JSON null — guard against that. */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        val v = optString(key, "")
        return v.ifEmpty { null }
    }

    /**
     * A gated channel refused the join at the CONTRACT (checkAccess false).
     * Carries the clone address so the entry screen can read the requirement
     * (mode/token/price) and offer pay()/join() instead of a dead-end toast.
     */
    class GateAccessDenied(val gateAddress: String) :
        IllegalStateException("You do not have access to this gated channel.")

    companion object {
        private const val TAG = "PomboChannels"
        /** Canonical wrapped-native on Polygon (WPOL) — bridge `_WRAPPED_NATIVE`. */
        const val WRAPPED_NATIVE = "0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270"
        /** PomboGate.Mode — ABI order, never reorder (NONE=0, TOKEN=1, NFT=2, PAID=3). */
        const val GATE_MODE_NONE = 0
        const val GATE_MODE_TOKEN = 1
        const val GATE_MODE_NFT = 2
        const val GATE_MODE_PAID = 3
        const val STORAGE_NODE = "0xae340e799e8151f6a4999d245e466197aa217667"
        /** Web CONFIG.push.pushStreamId — the shared relay wake-signal stream. */
        const val PUSH_STREAM_ID = "0xae340e799e8151f6a4999d245e466197aa217667/push"
        const val ONLINE_TIMEOUT_MS = 25_000L
        /** How long a "typing" signal keeps someone in the indicator. */
        const val TYPING_TTL_MS = 4_000L
        const val PRESENCE_INTERVAL_MS = 5_000L
        /** Web config.js previewPresenceIntervalMs. */
        const val PREVIEW_PRESENCE_INTERVAL_MS = 20_000L
        /** Web config.js subscriptions.adminPollIntervalMs. */
        const val ADMIN_POLL_INTERVAL_MS = 30_000L
        const val TIMESTAMP_TOLERANCE_MS = 5 * 60_000L
        /** Web isRecentMessage: only messages under 30s old get the replay check. */
        const val RECENT_MESSAGE_MS = 30_000L

        // Receive-side ceilings, mirroring the web config.media values. Without
        // these a hostile publisher could pin arbitrary memory in the app.
        const val IMAGE_MAX_ASSEMBLED_BYTES = 1 * 1024 * 1024
        const val GIF_MAX_ASSEMBLED_BYTES = 5 * 1024 * 1024
        const val MAX_CHUNK_BYTES = 220 * 1024        // config.media.imagePayloadMaxBytes
        const val MAX_CHUNKS = 512
        const val ASSEMBLY_TTL_MS = 10 * 60 * 1000L   // config.media.imageAssemblyTtlMs
        // Targeted chunk recovery (web config.imageRecovery: 15min window).
        const val CHUNK_RECOVERY_DELAY_MS = 8_000L
        const val CHUNK_RECOVERY_WINDOW_MS = 15 * 60 * 1000L
        const val CHUNK_RECOVERY_MAX_ATTEMPTS = 3
        // Web signature-verification batching (channels.js:2888).
        const val VERIFY_BATCH_MAX = 50
        const val VERIFY_BATCH_WINDOW_MS = 100L
        // config.channels.latestMessageFetchLast
        const val LATEST_PREVIEW_FETCH = 10

        // TTL-aware republish on owner open (docs/TTL_REPUBLISH_PLAN.md; web
        // config.js storage.ttlRepublishAgeFraction + ttlRepublish.js).
        const val DEFAULT_RETENTION_DAYS = 180
        const val TTL_REPUBLISH_AGE_FRACTION = 0.8

        /**
         * Should a -3 artifact with payload timestamp [artifactTs] be
         * republished, given the channel's retention of [storageDays]? The
         * storage node's TTL purge deletes by message timestamp; republishing
         * inside the last stretch of the artifact's life resets that clock.
         * Pure — mirrors web ttlRepublish.js shouldRepublish. Unknown age or
         * TTL must never trigger a publish.
         */
        fun shouldRepublish(artifactTs: Long, storageDays: Int?, now: Long = System.currentTimeMillis()): Boolean {
            if (artifactTs <= 0L) return false
            if (storageDays == null || storageDays <= 0) return false
            val ttlMs = storageDays * 86_400_000L
            return now - artifactTs > (TTL_REPUBLISH_AGE_FRACTION * ttlMs).toLong()
        }
    }
}

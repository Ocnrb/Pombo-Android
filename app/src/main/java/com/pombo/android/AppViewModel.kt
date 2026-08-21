package com.pombo.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pombo.android.bridge.PomboBridge
import com.pombo.android.core.InviteToken
import com.pombo.android.data.Channel
import com.pombo.android.data.ChannelStore
import com.pombo.android.data.Contact
import com.pombo.android.data.ContactsStore
import com.pombo.android.identity.WalletStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Bridge/network connection state. */
enum class NetStatus { BOOTING, NO_IDENTITY, CONNECTING, CONNECTED, ERROR }

class AppViewModel(app: Application) : AndroidViewModel(app), PomboBridge.Listener {

    private val store = WalletStore(app)
    private val channelStore = ChannelStore(app)
    private val contactsStore = ContactsStore(app)
    private val settingsStore = com.pombo.android.data.SettingsStore(app)
    private val bridge = PomboBridge(
        app, this, store.privateKey ?: "",
        initialRpcUrls = com.pombo.android.core.RpcPresets.bridgeUrls(
            settingsStore.rpcPreset, settingsStore.rpcCustomUrl
        )
    )

    private val imageStore = com.pombo.android.core.ChannelImageStore(app)
    private val previewStore = com.pombo.android.core.LatestMessageStore(app)
    private val ensStore = com.pombo.android.core.EnsStore(app)
    private val inviteStore = com.pombo.android.data.InviteStore(app)
    private val unreadStore = com.pombo.android.data.UnreadStore(app)
    private val blobStore = com.pombo.android.core.ImageBlobStore(app)
    private val sentDmStore = com.pombo.android.data.SentDmStore(app)
    private val sentReactionsStore = com.pombo.android.data.SentReactionsStore(app)
    private val epochKeyStore = com.pombo.android.data.EpochKeyStore(app)
    private val notifier = com.pombo.android.core.Notifier(app)

    /** Set from the Activity lifecycle; notifications are suppressed while visible. */
    @Volatile var appInForeground = true

    /** Called from MainActivity.onResume. Besides the notification flag, a
     *  return to the app is the moment state from other devices is stalest —
     *  kick a background sync, throttled inside SyncManager so task-switch
     *  churn costs nothing. */
    fun onAppForeground() {
        appInForeground = true
        com.pombo.android.push.ForegroundGate.inForeground = true
        // The UI is back — drop the service UNLESS a transfer is still running: the
        // notification is the only progress indicator once you leave the bubble, and
        // stopping it here is exactly what made the badge flicker/vanish when the app
        // was re-entered mid-transfer. It self-stops when the transfers finish.
        if (!transferBusy()) MediaTransferService.stop(getApplication())
        if (_status.value == NetStatus.CONNECTED) {
            viewModelScope.launch { sync.autoSync() }
        }
    }

    /** Live transfer work across both transports (mesh swarm + storage nodes). */
    private fun transferBusy(): Boolean =
        manager.media.hasBackgroundWork() || manager.storageMedia.hasActiveTransfers()

    /**
     * Starts the foreground transfer service NOW (from the foreground, which
     * Android always allows — unlike a start deferred to onAppBackground, which
     * fails silently on 12+ when a popup has already shifted the app's importance,
     * so the badge would sometimes never appear). The service self-stops once the
     * transfers finish. Idempotent.
     */
    private fun ensureTransferService() {
        MediaTransferService.isBusy = { transferBusy() }
        MediaTransferService.start(getApplication())
    }

    /**
     * Transfer-notification tap: open the channel of the single in-flight storage
     * transfer, or fall back to the channel list (Chats) when there are several
     * (or none resolvable).
     */
    fun openTransferFromNotification() {
        val streams = manager.activeStorageTransferStreams()
        if (streams.size == 1) openChannel(streams.first())
        else manager.closeCurrent()
    }

    /** Called from MainActivity.onPause. Flushes pending local changes whose
     *  15s debounce would die with the process (web: forcePushNow on pagehide). */
    fun onAppBackground() {
        appInForeground = false
        com.pombo.android.push.ForegroundGate.inForeground = false
        sync.flushIfDirty()
        // Fallback: if a transfer is live and the service was not already started at
        // transfer-start (e.g. it began while backgrounded), carry it now. Transfers
        // are normally started FROM the foreground by ensureTransferService().
        if (transferBusy()) ensureTransferService()
    }

    val manager = ChannelManager(
        bridge = bridge,
        store = channelStore,
        scope = viewModelScope,
        // Live identity, not the persisted account: a guest session runs on a
        // throwaway wallet while store.* keeps pointing at the last real
        // account (by design, so leaving guest mode restores it). Reading
        // store.* here published every guest message under the real
        // account's address/name/key — _address/_username/bridge.privateKey
        // are the fields both startGuestSession and switchWallet keep live.
        myAddress = { _address.value },
        myPrivateKey = { bridge.privateKey.ifEmpty { null } },
        myUsername = { _username.value },
        imageStore = imageStore,
        previewStore = previewStore,
        ensStore = ensStore,
        blobStore = blobStore,
        sentDmStore = sentDmStore,
        sentReactionsStore = sentReactionsStore,
        inviteStore = inviteStore,
        unreadStore = unreadStore,
        epochKeyStore = epochKeyStore,
        transferDir = java.io.File(app.filesDir, "transfers"),
        isTrustedContact = { addr -> _contacts.value.any { it.address.equals(addr, ignoreCase = true) } },
        isBlockedPeer = { addr -> addr.lowercase() in settingsStore.blockedPeers },
        persistBlockedPeer = { addr ->
            settingsStore.blockedPeers = settingsStore.blockedPeers + addr.lowercase()
            // Latest-wins slice: without bumping the timestamp another device's
            // older snapshot would win the merge and silently unblock them.
            settingsStore.setSliceTs("blockedPeers", System.currentTimeMillis())
        }
    )

    /** Shared caches exposed to the UI so lists paint before the network answers. */
    val channelImages get() = manager.channelImages
    val channelImagesPending get() = manager.channelImagesPending
    val channelPreviews get() = previewStore.previews

    /** Warms a channel's image + preview for the list (cache-first, deduped). */
    fun ensureChannelPreview(channel: Channel) {
        manager.ensureChannelImage(channel.adminStreamId, channel.password)
        viewModelScope.launch {
            previewStore.dedup(channel.messageStreamId) {
                // Password channels need the key to unseal P0 or the preview is empty.
                manager.fetchLatestPreview(channel.messageStreamId, channel.password)
            }
        }
    }

    val channels: StateFlow<List<Channel>> get() = manager.channels
    val current: StateFlow<Channel?> get() = manager.current
    val messages: StateFlow<List<UiMessage>> get() = manager.messages
    val reactions get() = manager.reactions
    val onlineCount: StateFlow<Int> get() = manager.onlineCount
    val presenceReady: StateFlow<Boolean> get() = manager.presenceReady
    val onlineUsers get() = manager.onlineUsers

    /** Cached ENS lookups for list rows (resolution itself is deduped in EnsStore). */
    fun ensNameFor(address: String): String? = ensStore.cachedName(address)
    fun ensAvatarFor(address: String): String? = ensStore.cachedAvatar(address)
    val typingFrom: StateFlow<List<ChannelManager.TypingPeer>> get() = manager.typingFrom
    val pins get() = manager.pins
    val hiddenIds get() = manager.hiddenIds
    val bannedMembers get() = manager.bannedMembers

    /** Per-channel unread badge counts (web updateUnreadCount). */
    val unreadCounts get() = unreadStore.counts

    /**
     * The current account's private key, for the Security panel's export.
     *
     * Callers MUST gate this behind [com.pombo.android.ui.DeviceAuth]; it is
     * exposed unguarded here only because the guard is a UI concern (it needs
     * an Activity to host the prompt).
     */
    fun exportPrivateKey(): String? = store.privateKey

    /** Blocked peers, for the Privacy panel. */
    val blockedPeers: Set<String> get() = settingsStore.blockedPeers

    /** Unblock: the peer's messages start arriving again. */
    fun unblockPeer(address: String) {
        settingsStore.blockedPeers = settingsStore.blockedPeers - address.lowercase()
        settingsStore.setSliceTs("blockedPeers", System.currentTimeMillis())
        toast("User unblocked", com.pombo.android.ui.ToastKind.INFO)
        sync.scheduleAutoPush()
    }

    /**
     * Erase this account: keystore entry, channels, contacts and caches.
     *
     * Unlike `disconnect()`, which only forgets the session, this is the web's
     * "Delete Account" — unrecoverable without the private key the user was
     * shown in the Security panel. The caller must have authenticated first.
     */
    fun deleteAccount() {
        viewModelScope.launch {
            // Wipe the account-scoped data first, while the storage scope still
            // points at this account. disconnect() drops the keystore entry and
            // repoints everything, so doing it the other way round would leave
            // this account's channels and contacts orphaned on disk.
            manager.replaceChannels(emptyList())
            contactsStore.save(emptyList())
            _contacts.value = emptyList()
            settingsStore.blockedPeers = emptySet()
            settingsStore.syncBase = null
            disconnect()
            toast("Account deleted", com.pombo.android.ui.ToastKind.INFO)
        }
    }

    /** Leave a DM and permanently ignore the peer (web: Block User). */
    fun blockPeer(messageStreamId: String) {
        manager.blockPeer(messageStreamId)
        toast("User blocked", com.pombo.android.ui.ToastKind.INFO)
    }
    val channelImage get() = manager.channelImage

    /**
     * Owner-only: publishes the finished 512² crop from the crop dialog.
     * Three outcomes, mirroring the web settings panel: confirmed on storage
     * ("Image updated"), published but confirmation still propagating (soft
     * warning, not an error), or a real publish failure.
     */
    fun setChannelImage(bytes: ByteArray, mime: String) = viewModelScope.launch {
        val id = toast("Publishing image…", com.pombo.android.ui.ToastKind.LOADING, Long.MAX_VALUE)
        _busy.value = true
        try {
            val verified = manager.publishChannelImage(bytes, mime)
            dismissToast(id)
            if (verified) {
                toast("Channel image updated", com.pombo.android.ui.ToastKind.SUCCESS)
            } else {
                toast(
                    "Image published — still propagating",
                    com.pombo.android.ui.ToastKind.WARNING, 5000L,
                    subtitle = "Storage has not confirmed it yet; it should appear shortly."
                )
            }
        } catch (e: Exception) {
            dismissToast(id)
            toast("Failed to publish image: ${e.message ?: "unknown error"}", com.pombo.android.ui.ToastKind.ERROR, 5000L)
        } finally {
            _busy.value = false
        }
    }

    /** Owner-only channel rename/description — one on-chain transaction. */
    fun updateChannelMetadata(name: String?, description: String?) = viewModelScope.launch {
        chainAction("Update channel", "Saves the channel name and description on-chain (1 transaction).") {
        runWithToast("Saving on-chain…", "Channel updated", "Failed to update channel") { manager.updateChannelMetadata(name, description) }
        }
    }

    suspend fun storageNodes(): List<String> = manager.storageNodes()

    suspend fun channelStorageInfo(): ChannelManager.StorageInfo = manager.channelStorageInfo()

    // ==================== push notifications ====================

    private val pushRegistry = com.pombo.android.core.PushRegistry(app)
    val push = com.pombo.android.push.PushRelayClient(app, bridge, pushRegistry)

    private val _pushEnabled = MutableStateFlow(push.enabled)
    val pushEnabled: StateFlow<Boolean> = _pushEnabled.asStateFlow()

    /** Bumped after a per-channel toggle so the panel re-reads the registry. */
    private val _pushRev = MutableStateFlow(0)
    val pushRev: StateFlow<Int> = _pushRev.asStateFlow()

    fun isChannelNotified(streamId: String) = push.isSubscribed(streamId)

    fun setPushEnabled(on: Boolean) = viewModelScope.launch {
        if (!on) {
            push.disable()
            _pushEnabled.value = false
            _pushRev.value++
            toast("Push notifications disabled", com.pombo.android.ui.ToastKind.INFO)
            return@launch
        }
        if (!notifier.canPost()) {
            // Permission is requested by the Activity; without it the relay
            // would deliver pushes we are not allowed to show.
            toast("Enable Push Notifications first", com.pombo.android.ui.ToastKind.WARNING)
            return@launch
        }
        val ok = runWithToast("Enabling notifications…", "Push notifications enabled!", "Could not enable push notifications") {
            push.enable()
        }
        if (ok) resubscribeDmInboxIfWanted()
        _pushEnabled.value = push.enabled
        _pushRev.value++
        if (!ok) _pushEnabled.value = false
    }

    /**
     * Auto-enable push right after the user grants the Android notification
     * permission (first launch, or a re-grant).
     *
     * Races considered: the global enable only needs the FCM token — it never
     * touches the Streamr client, because on a fresh grant the per-channel
     * registry is empty and no publish happens. The token fetch does need the
     * network, which may not be up this early in the launch, so it retries
     * with backoff and stays silent on failure — the Settings switch still
     * works manually. (On a re-grant with existing registrations, enable()
     * already swallows per-entry publish failures; those rows are still on
     * the relay from before.)
     */
    fun enablePushAfterPermissionGrant() = viewModelScope.launch {
        if (push.enabled) return@launch
        var backoff = 2_000L
        repeat(4) {
            val ok = runCatching { push.enable() }.isSuccess
            if (ok) {
                resubscribeDmInboxIfWanted()
                _pushEnabled.value = true
                _pushRev.value++
                toast("Push notifications enabled", com.pombo.android.ui.ToastKind.SUCCESS)
                return@launch
            }
            delay(backoff)
            backoff *= 2
        }
    }

    /**
     * Web channels.js: every successful create/join (including joins from a
     * preview) auto-enables notifications for that channel when the global
     * toggle is on. Silent on failure — the per-channel switch in Channel
     * Details still works manually, and the relay registration retries on the
     * next global enable.
     */
    private fun autoEnableChannelPush(channel: Channel) {
        if (!push.enabled) return
        viewModelScope.launch {
            runCatching { push.subscribeChannel(channel.messageStreamId, channel.type, channel.name) }
            _pushRev.value++
        }
    }

    fun setChannelNotifications(channel: Channel, on: Boolean) = viewModelScope.launch {
        // DMs: the relay registration is per-inbox (the global "Direct
        // Messages" toggle), so this toggle is a LOCAL mute of the peer —
        // no relay round-trip, works offline, and never needs push enabled
        // to silence someone.
        if (channel.type == "dm") {
            val peer = channel.peerAddress?.lowercase() ?: return@launch
            settingsStore.mutedDmPeers =
                if (on) settingsStore.mutedDmPeers - peer else settingsStore.mutedDmPeers + peer
            // Purge any legacy per-conversation relay row from when this
            // toggle wrongly registered the peer's inbox tag.
            push.unsubscribeChannel(channel.messageStreamId)
            _pushRev.value++
            toast(
                if (on) "Notifications on for this conversation" else "Notifications muted for this conversation",
                com.pombo.android.ui.ToastKind.INFO
            )
            return@launch
        }
        if (on && !push.enabled) {
            toast("Enable Push Notifications in Settings first", com.pombo.android.ui.ToastKind.WARNING)
            return@launch
        }
        if (!on) {
            push.unsubscribeChannel(channel.messageStreamId)
            _pushRev.value++
            toast("Notifications off for this channel", com.pombo.android.ui.ToastKind.INFO)
            return@launch
        }
        runWithToast("Registering…", "Notifications on for this channel", "Failed to update notifications") {
            push.subscribeChannel(channel.messageStreamId, channel.type, channel.name)
        }
        _pushRev.value++
    }

    /** Whether a DM peer is muted (drives the Channel Details chip for DMs). */
    fun isDmMuted(peerAddress: String?): Boolean =
        peerAddress != null && peerAddress.lowercase() in settingsStore.mutedDmPeers

    // ==================== DM push (web dm-push-enabled sub-toggle) ====================

    private val _dmPushEnabled = MutableStateFlow(settingsStore.dmPushEnabled)
    /** "Direct Messages" sub-toggle — registers MY inbox on the relay. Default OFF. */
    val dmPushEnabled: StateFlow<Boolean> = _dmPushEnabled.asStateFlow()

    private fun myInboxStreamId(): String? = store.address?.lowercase()?.let { "$it/Pombo-DM-1" }

    fun setDmPushEnabled(on: Boolean) = viewModelScope.launch {
        if (!on) {
            // Like the web: drop the preference and the local row; the relay
            // registration expires on its own.
            settingsStore.dmPushEnabled = false
            _dmPushEnabled.value = false
            myInboxStreamId()?.let { push.unsubscribeDmInbox(it) }
            toast("DM notifications disabled", com.pombo.android.ui.ToastKind.INFO)
            return@launch
        }
        if (!push.enabled) {
            toast("Enable Push Notifications first", com.pombo.android.ui.ToastKind.WARNING)
            return@launch
        }
        if (!_hasDmInbox.value) {
            toast("Create your DM inbox first", com.pombo.android.ui.ToastKind.WARNING)
            return@launch
        }
        val inbox = myInboxStreamId() ?: return@launch
        val ok = runWithToast("Registering…", "DM notifications enabled!", "Failed to enable DM notifications") {
            push.subscribeDmInbox(inbox)
            settingsStore.dmPushEnabled = true
        }
        _dmPushEnabled.value = ok && settingsStore.dmPushEnabled
    }

    /**
     * Global disable wipes every relay row, so re-enabling push must bring
     * the inbox registration back when the DM preference is still on.
     */
    private suspend fun resubscribeDmInboxIfWanted() {
        if (!settingsStore.dmPushEnabled) return
        val inbox = myInboxStreamId() ?: return
        runCatching { push.subscribeDmInbox(inbox) }
    }

    // ==================== cross-device sync ====================

    private val syncStore = com.pombo.android.data.SyncStore(app)

    /**
     * Timestamps for the slices this device owns. The merge is latest-wins per
     * slice, so a slice we never stamp would always lose to a remote snapshot.
     */
    private fun sliceTouched(key: String) {
        settingsStore.setSliceTs(key, System.currentTimeMillis())
        sync.scheduleAutoPush()
    }

    val sync = SyncManager(
        bridge = bridge,
        scope = viewModelScope,
        store = syncStore,
        blobStore = blobStore,
        myAddress = { store.address },
        myPrivateKey = { store.privateKey },
        isGuest = { _isGuest.value },
        exportLocal = { exportSyncState() },
        importMerged = { importSyncState(it) },
        // Read live, not captured: the setting is per account, so switching
        // account has to change the answer without rebuilding the manager.
        syncMode = { settingsStore.syncMode },
        onBlobsImported = { manager.hydrateImages(it) }
    )

    /**
     * Local state in the web's payload shape. The last merged payload is kept
     * verbatim as the base so slices this client does not model — dmLeftAt and
     * anything a newer web version adds — are not wiped when we push.
     */
    private fun exportSyncState(): JSONObject {
        val base = settingsStore.syncBase?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: JSONObject()

        val channels = org.json.JSONArray()
        manager.channels.value.forEach { channels.put(it.toJson()) }
        base.put("channels", channels)
        // The local ledger mixes channel tombstones with "dm:"-prefixed DM
        // tombstones; the web keeps them as two separate slices, so split.
        run {
            val leftAll = channelStore.leftAtJson()
            val channelsLeft = JSONObject()
            val dmLeft = JSONObject()
            leftAll.keys().forEach { k ->
                if (k.startsWith("dm:")) dmLeft.put(k.removePrefix("dm:"), leftAll.optLong(k))
                else channelsLeft.put(k, leftAll.optLong(k))
            }
            base.put("channelsLeftAt", channelsLeft)
            base.put("dmLeftAt", dmLeft)
        }
        // ENS resolutions made here feed the shared cache (web ensCache slice,
        // per-key union). No push is scheduled for these — they ride along on
        // whatever push happens next.
        run {
            val ens = base.optJSONObject("ensCache") ?: JSONObject()
            val localEns = ensStore.exportSyncSlice()
            localEns.keys().forEach { ens.put(it, localEns.get(it)) }
            base.put("ensCache", ens)
        }

        // Epoch keys ride the sync so a second device reads native/gated
        // history without a KEY_REQUEST round-trip — and gets epochs the
        // network can no longer serve (paid gates, announces past retention).
        // Local wins per stream: the store is union-seeded by every pull.
        run {
            val keys = base.optJSONObject("epochKeys") ?: JSONObject()
            val local = exportEpochKeys()
            local.keys().forEach { keys.put(it, local.get(it)) }
            base.put("epochKeys", keys)
        }

        // Web schema: keyed by lowercase address, value carries the full
        // contact record (identity.js drops entries without an `address`).
        val contacts = JSONObject()
        _contacts.value.forEach { c ->
            contacts.put(c.address.lowercase(), c.toJson())
        }
        // Sent DMs exist only on the device that sent them, so syncing them is
        // the only way another device ever sees that half of a conversation.
        // MERGED with the base slice, not a replace: the web also keeps
        // write-only group-channel entries under sentMessages, which Android
        // does not model — a replace wiped them from every device on each push.
        base.put(
            "sentMessages",
            com.pombo.android.core.SyncMerge.mergeSentMessages(
                base.optJSONObject("sentMessages"),
                sentDmStore.exportAll(
                    manager.channels.value.filter { it.type == "dm" }.map { it.messageStreamId }
                )
            )
        )
        // Reactions made here in DMs/write-only channels exist only in the
        // local record — no resend carries them (web secureStorage slice).
        // The store is the truth: seeded from the sync base at init and
        // replaced by every merged pull, so exporting it directly cannot drop
        // entries other devices contributed.
        base.put("sentReactions", sentReactionsStore.exportAll())
        base.put("trustedContacts", contacts)
        // Now that blocks are modelled locally, they must be published too —
        // otherwise a block made on this device would be invisible to the web
        // and the peer's messages would keep arriving there.
        base.put("blockedPeers", org.json.JSONArray(settingsStore.blockedPeers.toList()))
        base.put("username", store.username ?: JSONObject.NULL)
        base.put("graphApiKey", settingsStore.graphApiKey ?: JSONObject.NULL)
        base.put("sliceTs", settingsStore.sliceTsJson())
        return base
    }

    /**
     * Backfills local stores from the last merged sync payload.
     *
     * A pull only calls importMerged for payloads it has not applied before, so
     * a store added after those payloads were first applied stays empty for
     * good: the data sits in `syncBase` on disk while `pullSync` keeps
     * answering "nothing new". That is exactly what hid DMs sent from the web —
     * they were in the payload, but never reached SentDmStore.
     */
    private fun hydrateFromSyncBase() {
        val base = settingsStore.syncBase?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return
        base.optJSONObject("sentMessages")?.let { sentDmStore.importAll(it) }
        // Union-seed, local-wins per message: the base is the LAST merged
        // payload, so a reaction removed locally since then must not come back.
        base.optJSONObject("sentReactions")?.let {
            sentReactionsStore.importAll(
                com.pombo.android.core.SyncMerge.mergeSentReactions(it, sentReactionsStore.exportAll())
            )
        }
        importBlockedPeers(base)
    }

    /**
     * Adopt the blocked list from a sync payload.
     *
     * Union rather than replace: a block is a safety decision, and letting an
     * older snapshot from another device narrow the list would silently unblock
     * someone. The slice timestamp still decides which snapshot wins the merge;
     * this only guarantees the local view never loses a block it already had.
     */
    private fun importBlockedPeers(payload: JSONObject) {
        val arr = payload.optJSONArray("blockedPeers") ?: return
        val incoming = (0 until arr.length())
            .mapNotNull { arr.optString(it).lowercase().ifEmpty { null } }
        if (incoming.isNotEmpty()) {
            settingsStore.blockedPeers = settingsStore.blockedPeers + incoming
        }
    }

    private fun importSyncState(merged: JSONObject) {
        // Keep the whole merged payload so the next push carries foreign slices.
        settingsStore.syncBase = merged.toString()

        merged.optJSONArray("channels")?.let { arr ->
            val incoming = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { runCatching { Channel.fromJson(it) }.getOrNull() }
            }
            manager.replaceChannels(incoming)
        }
        // Recombine the web's two tombstone slices into the single local
        // ledger ("dm:"-prefixed keys are the DM half).
        if (merged.has("channelsLeftAt") || merged.has("dmLeftAt")) {
            val combined = JSONObject()
            merged.optJSONObject("channelsLeftAt")?.let { c ->
                c.keys().forEach { k -> combined.put(k, c.optLong(k)) }
            }
            merged.optJSONObject("dmLeftAt")?.let { d ->
                d.keys().forEach { k -> combined.put("dm:${k.lowercase()}", d.optLong(k)) }
            }
            channelStore.saveLeftAt(combined)
        }
        merged.optJSONObject("epochKeys")?.let { importEpochKeys(it) }
        merged.optJSONObject("ensCache")?.let { slice ->
            if (ensStore.importSyncSlice(slice)) viewModelScope.launch { ensStore.persistNow() }
        }
        merged.optJSONObject("sentMessages")?.let { sentDmStore.importAll(it) }
        // Post-merge the slice is the union of every device's record — replace.
        merged.optJSONObject("sentReactions")?.let { sentReactionsStore.importAll(it) }
        importBlockedPeers(merged)

        merged.optJSONObject("trustedContacts")?.let { tc ->
            val list = tc.keys().asSequence().mapNotNull { key ->
                val o = tc.optJSONObject(key) ?: return@mapNotNull null
                // Tolerate entries without an inner `address` (legacy Android
                // snapshots) by falling back to the map key.
                val record = if (o.isNull("address")) JSONObject(o.toString()).put("address", key) else o
                runCatching { Contact.fromJson(record) }.getOrNull()
            }.toList()
            contactsStore.save(list)
            _contacts.value = list
        }
        if (!merged.isNull("username")) {
            merged.optString("username").ifEmpty { null }?.let {
                store.username = it
                _username.value = it
            }
        }
        if (!merged.isNull("graphApiKey")) {
            merged.optString("graphApiKey").ifEmpty { null }?.let {
                settingsStore.graphApiKey = it
                com.pombo.android.core.GraphApi.userApiKey = it
                _graphApiKey.value = it
            }
        }
        merged.optJSONObject("sliceTs")?.let { settingsStore.saveSliceTs(it) }
    }

    fun syncNow() = viewModelScope.launch {
        runWithToast("Syncing…", "Sync complete", "Sync failed") {
            val applied = sync.fullSync()
            if (applied == 0) { /* nothing remote was newer — still a success */ }
        }
    }

    // ==================== invites ====================

    val pendingInvites get() = manager.pendingInvites

    fun inviteLink(channel: Channel): String = manager.inviteLink(channel)

    fun sendChannelInvite(address: String, channel: Channel, onDone: () -> Unit = {}) =
        viewModelScope.launch {
            // The recipient decrypts the invite with OUR pubkey, published only
            // in our inbox metadata. With no inbox the invite publishes fine
            // and then can never be read by anyone — block it up front.
            if (!manager.hasInbox()) {
                toast(
                    "Create your DM inbox first (Chats → Create DM Inbox) — recipients need it to read your invites",
                    com.pombo.android.ui.ToastKind.WARNING
                )
                return@launch
            }
            // Only dismiss the form when the invite actually went out — closing
            // on failure would hide the reason it failed.
            val ok = runWithToast("Sending invite…", "Invite sent successfully!", "Failed to send invite") {
                manager.sendChannelInvite(address, channel)
            }
            if (ok) onDone()
        }

    /** Accepts an invite that arrived over DM (web: joinChannelFromInvite). */
    fun acceptInvite(invite: ChannelManager.PendingInvite) = viewModelScope.launch {
        runJoinToast("Joining channel...", "Joined channel successfully!", "Failed to join channel") {
            val channel = manager.joinChannel(invite.streamId, invite.password)
            manager.openChannel(channel.messageStreamId)
            autoEnableChannelPush(channel)
        }
        // Resolved, not dismissed — dismissInvite would file it into the
        // retained "All" list as if the user had swiped it away.
        manager.resolveInvite(invite.inviteId)
    }

    fun dismissInvite(inviteId: String) = manager.dismissInvite(inviteId)

    /** Dismissed invites retained for the bell's "All" view. */
    val dismissedInvites: StateFlow<List<ChannelManager.PendingInvite>> = manager.dismissedInvites

    /** Deep P3 replay backing the "All" view — once per session, see [ChannelManager.fetchAllInvites]. */
    fun fetchAllInvites() = manager.fetchAllInvites()

    /**
     * An invite link opened from outside the app (deep link). Parsed here so a
     * malformed token surfaces as an error instead of silently doing nothing.
     */
    private val _incomingInvite = MutableStateFlow<InviteToken.Invite?>(null)
    val incomingInvite: StateFlow<InviteToken.Invite?> = _incomingInvite.asStateFlow()

    fun openInviteToken(raw: String) {
        val parsed = InviteToken.parse(raw)
        if (parsed == null) {
            toast("Invalid invite link", com.pombo.android.ui.ToastKind.ERROR, 5000L)
            return
        }
        _incomingInvite.value = parsed
    }

    fun dismissIncomingInvite() { _incomingInvite.value = null }

    fun acceptIncomingInvite(invite: InviteToken.Invite) = viewModelScope.launch {
        _incomingInvite.value = null
        runJoinToast("Joining channel...", "Joined channel successfully!", "Failed to join channel",
            channelName = invite.name) {
            val channel = manager.joinChannel(invite.streamId, invite.password)
            manager.openChannel(channel.messageStreamId)
        }
    }

    val pomboStorageNode: String get() = manager.pomboStorageNode

    /** Owner-only storage ops — each writes to both -1 and -3 and costs gas. */
    fun addStorageNode(address: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        chainAction("Add storage node", "Assigns a storage node to this channel's streams (2 transactions).") {
            runWithToast("Adding storage node…", "Storage node added", "Failed to add storage node") {
                manager.addStorageNode(address)
            }
        }
        onDone()
    }

    fun removeStorageNode(address: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        chainAction(
            "Remove storage node",
            "Stops retaining this channel's history on that node (2 transactions)."
        ) {
            runWithToast("Removing storage node…", "Storage node removed", "Failed to remove storage node") {
                manager.removeStorageNode(address)
            }
        }
        onDone()
    }

    fun setStorageDays(days: Int, onDone: () -> Unit = {}) = viewModelScope.launch {
        chainAction("Change retention", "Sets history retention to $days days (2 transactions).") {
            runWithToast("Updating retention…", "Retention updated to $days days", "Failed to update retention") {
                manager.setStorageDays(days)
            }
        }
        onDone()
    }

    /** Manual channel order (streamIds) and its setter, for drag-to-reorder. */
    val channelOrder get() = manager.channelOrder
    fun setChannelOrder(order: List<String>) = manager.setChannelOrder(order)

    // ==================== background activity scan ====================

    /** Web `minPollIntervalMs` — the floor between two scans of one channel. */
    private val minScanIntervalMs = 10_000L

    /**
     * Resends run concurrently — the bridge multiplexes by request id, which is
     * what the Explore preview pass already relies on. The web serialises with
     * a 2s stagger because it repeats every 30s forever; ours is a one-off
     * burst when the user opens the list, so serialising just made the channels
     * further down the list take seconds to fill in. Bounded so a long channel
     * list does not fire dozens of resends at once.
     */
    private val scanConcurrency = 4

    private val scanning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastScanAt = HashMap<String, Long>()
    private var scanJob: kotlinx.coroutines.Job? = null

    /**
     * Drops whatever the scan still had queued. Called when a channel opens:
     * its history fetch must not wait behind background resends on the single
     * JS thread. A cancelled channel keeps its old `lastScanAt`, so the next
     * pass will not immediately redo it.
     */
    fun cancelActivityScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /**
     * Refreshes previews and unread badges for the channels we are NOT
     * subscribed to, by resending a small window per channel (see
     * ChannelManager.scanChannelActivity).
     *
     * The web runs this on a 30s timer; here it is event-driven, firing on
     * connect and whenever the user lands on Chats.
     *
     * DMs are skipped: their inbox is subscribed permanently, so their previews
     * are already live. The open channel is skipped for the same reason.
     */
    fun scanChannelsActivity() {
        // Nothing to gain while a channel is open: it holds the subscription
        // and the bridge, and the list is not on screen anyway.
        if (manager.current.value != null) return
        if (!scanning.compareAndSet(false, true)) return
        scanJob = viewModelScope.launch {
            try {
                val order = manager.channelOrder.value
                val ordered = manager.channels.value.sortedBy {
                    order.indexOf(it.messageStreamId).let { i -> if (i < 0) Int.MAX_VALUE else i }
                }
                // Never let a first scan report the whole stored history as new.
                unreadStore.seedWatermarks(ordered.map { it.messageStreamId })

                val due = ordered.filter { channel ->
                    if (channel.type == "dm") return@filter false
                    if (channel.messageStreamId == manager.current.value?.messageStreamId) return@filter false
                    val now = System.currentTimeMillis()
                    if (now - (lastScanAt[channel.messageStreamId] ?: 0L) < minScanIntervalMs) return@filter false
                    lastScanAt[channel.messageStreamId] = now
                    true
                }
                val gate = kotlinx.coroutines.sync.Semaphore(scanConcurrency)
                kotlinx.coroutines.coroutineScope {
                    // List order still decides who starts first, so the channels
                    // the user is looking at fill in first.
                    due.forEach { channel ->
                        launch {
                            gate.withPermit { manager.scanChannelActivity(channel) }
                        }
                    }
                }
            } finally {
                scanning.set(false)
            }
        }
    }

    suspend fun channelMembers(): List<ChannelManager.MemberRow> = manager.channelMembers()

    /** On-chain permission matrix for the Members panel's Stream Permissions. */
    suspend fun streamPermissions(): List<com.pombo.android.core.GraphApi.StreamPermission> =
        manager.streamPermissions()

    /** Owner-only: toggles a member's "Can add members" (GRANT) permission. */
    fun setMemberGrant(address: String, canGrant: Boolean, onDone: () -> Unit = {}) = viewModelScope.launch {
        chainAction(
            if (canGrant) "Grant admin" else "Revoke admin",
            "Updates this member's permissions on all three streams (3 transactions)."
        ) {
            runWithToast(
                if (canGrant) "Granting admin…" else "Revoking admin…",
                if (canGrant) "Member can now add others" else "Admin permission removed",
                "Failed to update permission"
            ) { manager.setMemberGrant(address, canGrant) }
        }
        onDone()
    }

    /** Owner-only: grants access on all three streams (on-chain, costs gas). */
    fun addMember(address: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        chainAction("Add member", "Grants access on all three channel streams (3 transactions).") {
            runWithToast("Adding member…", "Member added", "Failed to add member") {
                manager.addMember(address)
            }
        }
        onDone()
    }

    /**
     * Owner-only batch add (web handleBatchAddMembers). Runs the grants one at a
     * time — each is three on-chain transactions — and reports how many landed,
     * because a mid-list failure (already a member, gas ran out) must not abort
     * the ones that already succeeded or hide that some did not.
     */
    fun addMembers(addresses: List<String>, onDone: () -> Unit = {}) = viewModelScope.launch {
        // Accept raw addresses AND ENS names — addMember resolves each. Only
        // blanks are dropped here; anything unresolvable is counted as a failure.
        val valid = addresses.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        if (valid.isEmpty()) {
            toast("No addresses", com.pombo.android.ui.ToastKind.ERROR)
            return@launch
        }
        var added = 0
        val failures = mutableListOf<String>()
        // One confirmation for the batch, not one per address: the user is
        // approving the list they just typed, and the grant stays open for the
        // whole loop.
        chainAction(
            "Add ${valid.size} member${if (valid.size == 1) "" else "s"}",
            "Grants access on all three channel streams, ${valid.size * 3} transactions in total."
        ) {
            for (addr in valid) {
                try { manager.addMember(addr); added++ }
                catch (e: Exception) { failures.add("${addr.take(8)}…${addr.takeLast(4)}") }
            }
        }
        if (added == 0 && failures.isEmpty()) { onDone(); return@launch }   // cancelled
        when {
            failures.isEmpty() -> toast("Added $added member${if (added == 1) "" else "s"}", com.pombo.android.ui.ToastKind.SUCCESS)
            added == 0 -> toast("Could not add any: ${failures.joinToString()}", com.pombo.android.ui.ToastKind.ERROR, 5000L)
            else -> toast("Added $added, skipped ${failures.size}", com.pombo.android.ui.ToastKind.WARNING, 5000L)
        }
        onDone()
    }

    /** Owner-only: revokes all permissions for an address. */
    fun removeMember(address: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        chainAction("Remove member", "Revokes their access on all three channel streams (3 transactions).") {
            runWithToast("Removing member…", "Member removed", "Failed to remove member") {
                manager.removeMember(address)
            }
        }
        onDone()
    }

    val ensNames get() = manager.ensNames

    /**
     * Observable ENS avatars. A plain cache read cannot work for avatar slots:
     * resolution is async and nothing would recompose when it lands, so the
     * generated picture stayed on screen forever.
     */
    val ensAvatars get() = ensStore.avatarUrls
    fun ensureEns(address: String?) = manager.ensureEns(address)
    val initialLoad get() = manager.initialLoad
    val hasMoreHistory get() = manager.hasMoreHistory
    val waitingForKeys get() = manager.waitingForKeys
    val loadingHistory get() = manager.loadingHistory
    val isPreview get() = manager.isPreview

    /**
     * Where the open channel was entered from, so closing it returns there
     * instead of always landing on Chats.
     */
    enum class ChatOrigin { CHATS, EXPLORE }
    private val _chatOrigin = MutableStateFlow(ChatOrigin.CHATS)
    val chatOrigin: StateFlow<ChatOrigin> = _chatOrigin.asStateFlow()

    /**
     * Opening a chat swaps MainShell out of the composition entirely, so any
     * tab state remembered inside it is destroyed. It has to live here to
     * survive the round trip.
     */
    @Volatile var landedOnce: Boolean = false

    fun setChatOrigin(origin: ChatOrigin) { _chatOrigin.value = origin }

    /** Reads a public channel without joining it (web: preview mode). */
    /**
     * Explore tap on a gated channel (N-D). Routing by MODE:
     *  - TOKEN/NFT with access → PREVIEW (browse without committing; the
     *    Join button in the header adds it to the list)
     *  - PAID, no access, or unreadable gate → the join flow, which lands on
     *    the entry screen when the gate refuses (paying IS the commitment)
     */
    fun openGatedExplore(channel: ExploreChannel) = viewModelScope.launch {
        val gate = channel.gateAddress
        if (gate != null) {
            val mode = manager.gateModeOf(gate)
            val holding = mode == ChannelManager.GATE_MODE_TOKEN || mode == ChannelManager.GATE_MODE_NFT
            if (holding && manager.gateAccessSelf(gate)) {
                manager.previewChannel(channel)
                return@launch
            }
        }
        joinChannel(channel.messageStreamId, null)
    }

    fun previewChannel(channel: ExploreChannel) {
        _chatOrigin.value = ChatOrigin.EXPLORE
        manager.previewChannel(channel)
    }

    /** Join button in the preview header. */
    fun joinPreview() = viewModelScope.launch {
        runJoinToast("Joining channel…", "Joined channel", "Failed to join channel") {
            manager.joinPreview()?.let { autoEnableChannelPush(it) }
        }
    }

    /** Scroll-up pagination — one page of older messages. */
    fun loadMoreHistory() { viewModelScope.launch { manager.loadMoreHistory() } }

    /**
     * True when a DM's last windowed search came back empty but older history
     * still exists. The UI then offers "Search older" instead of automatically
     * scanning further empty windows (web ChatAreaUI).
     */
    private val _dmSearchOlder = MutableStateFlow(false)
    val dmSearchOlder: StateFlow<Boolean> = _dmSearchOlder.asStateFlow()

    fun loadMoreDmHistory() = viewModelScope.launch {
        _dmSearchOlder.value = false
        val page = manager.loadMoreDmHistory()
        _dmSearchOlder.value = page.noResultsInWindow
    }

    fun clearDmSearchOlder() { _dmSearchOlder.value = false }

    fun amOwnerOfCurrent(): Boolean = current.value?.let { manager.amOwner(it) } ?: false

    /**
     * On-chain DELETE permission on the open channel — the web's `isAdminUser`.
     * Drives every moderation affordance instead of the local `createdBy` guess.
     */
    val canModerate get() = manager.canModerate

    /** Full permission set, for the surfaces the web gates on GRANT or EDIT. */
    val perms get() = manager.perms

    /** On-chain channel deletion — irreversible, and not the same as leaving. */
    fun deleteChannelOnChain(messageStreamId: String, name: String) = viewModelScope.launch {
        chainAction(
            "Delete channel",
            "Destroys \"$name\" and its three streams for everyone (3 transactions). This cannot be undone."
        ) {
        val id = toast(
            "Deleting channel...", com.pombo.android.ui.ToastKind.LOADING, Long.MAX_VALUE,
            subtitle = "Three on-chain transactions"
        )
        try {
            val failed = manager.deleteChannel(messageStreamId)
            dismissToast(id)
            if (failed.isEmpty()) {
                toast("Channel \"$name\" deleted", com.pombo.android.ui.ToastKind.SUCCESS)
            } else {
                // Say which half failed rather than claiming success: the
                // remaining streams are still live on-chain.
                toast(
                    "Channel removed here, but ${failed.size} stream(s) could not be deleted on-chain",
                    com.pombo.android.ui.ToastKind.WARNING, 6000L
                )
            }
        } catch (e: Exception) {
            dismissToast(id)
            toast("Failed to delete channel: ${e.message ?: "unknown error"}", com.pombo.android.ui.ToastKind.ERROR, 5000L)
        }
        }
    }

    /**
     * Moderation publishes ADMIN_STATE for everyone in the channel, so it gets
     * the same confirmation the web gives ('Message pinned', 'User banned', …).
     * These used to fire and forget: a failed publish showed nothing at all.
     */
    fun hideMessage(id: String, hide: Boolean) = moderationAction(
        if (hide) "Message hidden" else "Message shown"
    ) { manager.hideMessage(id, hide) }

    fun pinMessage(id: String, pin: Boolean) = moderationAction(
        if (pin) "Message pinned" else "Message unpinned"
    ) { manager.pinMessage(id, pin) }

    fun banMember(address: String, ban: Boolean = true) = moderationAction(
        if (ban) "User banned" else "User unbanned"
    ) { manager.banMember(address, ban) }

    private fun moderationAction(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            toast(success, com.pombo.android.ui.ToastKind.SUCCESS)
        } catch (e: Exception) {
            toast(e.message ?: "Moderation failed", com.pombo.android.ui.ToastKind.ERROR, 5000L)
        }
    }

    private val _status = MutableStateFlow(NetStatus.BOOTING)
    val status: StateFlow<NetStatus> = _status.asStateFlow()

    private val _address = MutableStateFlow(store.address)
    val address: StateFlow<String?> = _address.asStateFlow()

    private val _hasWallet = MutableStateFlow(store.hasWallet)
    val hasWallet: StateFlow<Boolean> = _hasWallet.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // ==================== toasts (web: NotificationUI) ====================

    private val _toasts = MutableStateFlow<List<com.pombo.android.ui.Toast>>(emptyList())
    val toasts: StateFlow<List<com.pombo.android.ui.Toast>> = _toasts.asStateFlow()
    private var toastId = 0L

    fun toast(
        message: String,
        kind: com.pombo.android.ui.ToastKind = com.pombo.android.ui.ToastKind.INFO,
        durationMs: Long = 3000L,
        subtitle: String? = null,
        progress: com.pombo.android.ui.ToastProgress? = null
    ): Long {
        val id = ++toastId
        _toasts.value = _toasts.value +
            com.pombo.android.ui.Toast(id, message, kind, durationMs, subtitle, progress)
        return id
    }

    /** Advances a toast's step ring (web: notificationUI.setLoadingProgress). */
    fun setToastProgress(id: Long, current: Int, label: String) {
        _toasts.value = _toasts.value.map {
            if (it.id == id && it.progress != null)
                it.copy(progress = it.progress.copy(current = current, label = label))
            else it
        }
    }

    fun dismissToast(id: Long) {
        _toasts.value = _toasts.value.filterNot { it.id == id }
    }

    /** Swap a live toast's message (web: showLoadingToast re-issued per step). */
    fun updateToast(id: Long, message: String) {
        _toasts.value = _toasts.value.map { if (it.id == id) it.copy(message = message) else it }
    }

    private val _newMnemonic = MutableStateFlow<String?>(null)
    val newMnemonic: StateFlow<String?> = _newMnemonic.asStateFlow()

    private val _username = MutableStateFlow(store.username)
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _accounts = MutableStateFlow(store.accounts())
    val accounts: StateFlow<List<com.pombo.android.identity.WalletStore.Account>> = _accounts.asStateFlow()

    /** Another stored account's display name, for Profile's account list — no need to switch to it. */
    fun usernameFor(address: String): String? = store.usernameFor(address)

    private val _addingAccount = MutableStateFlow(false)
    val addingAccount: StateFlow<Boolean> = _addingAccount.asStateFlow()

    /** Guest mode: ephemeral in-memory wallet, nothing persisted (like the web). */
    private val _isGuest = MutableStateFlow(false)
    val isGuest: StateFlow<Boolean> = _isGuest.asStateFlow()

    @Volatile private var guestStarted = false

    /** Deferred sync kick after a bridge connect (cancelled by a re-connect). */
    private var autoSyncKickJob: kotlinx.coroutines.Job? = null
    private val AUTO_SYNC_CONNECT_DELAY_MS = 5_000L

    /** Lets Explore's own bridge-connect-gated image resends dispatch first on a cold start. */
    private val BRIDGE_CONNECT_BACKGROUND_DELAY_MS = 1_500L

    /**
     * The Graph API key. Empty means the shared default key that ships with the
     * app — the same one the web bundle exposes (web: Settings → API).
     */
    private val _graphApiKey = MutableStateFlow(settingsStore.graphApiKey ?: "")
    val graphApiKey: StateFlow<String> = _graphApiKey.asStateFlow()

    /**
     * Points the per-account stores at an identity and reloads what belongs to
     * it. Guests get a memory-only scope: the web's `initAsGuest` starts from
     * an empty cache and never persists, so a guest must not inherit — or
     * leave behind — any channels or contacts.
     */
    private fun applyStorageScope(address: String?, guest: Boolean) {
        channelStore.scopeAddress = address
        channelStore.memoryOnly = guest
        contactsStore.scopeAddress = address
        inviteStore.scopeAddress = address
        sentDmStore.scopeAddress = address
        unreadStore.scopeAddress = address
        sentDmStore.memoryOnly = guest
        sentReactionsStore.scopeAddress = address
        sentReactionsStore.memoryOnly = guest
        pushRegistry.scopeAddress = address
        contactsStore.memoryOnly = guest
        settingsStore.scopeAddress = address
        syncStore.scopeAddress = address
        epochKeyStore.scopeAddress = address
        epochKeyStore.memoryOnly = guest
        manager.reloadChannels()
        // Same-process cache, keyed only by messageStreamId — reloading the
        // store above does nothing for a channel already opened this
        // session; without this the new identity would keep reading the
        // previous account's already-decrypted epoch state from memory.
        manager.resetEpochRuntimeState()
        // Guests keep nothing, so their invite list starts (and stays) empty.
        if (guest) inviteStore.scopeAddress = null
        manager.reloadInvites()
        _contacts.value = contactsStore.load()
        _youtubeEmbeds.value = settingsStore.youtubeEmbeds
        _nsfwEnabled.value = settingsStore.nsfwEnabled
        _dmPushEnabled.value = settingsStore.dmPushEnabled
        // Per account, and read before the next connect decides whether to fire
        // the start-up sync.
        _syncMode.value = settingsStore.syncMode
        // Before subscribeMyInbox runs on the next connect, so the P3 gate
        // already reflects this account's preference.
        _inviteNotifications.value = settingsStore.inviteNotificationsEnabled
        manager.inviteNotificationsEnabled = settingsStore.inviteNotificationsEnabled
        // The Graph key is per account: re-point the client at this account's
        // key (or the shared default) so credentials never cross sessions.
        com.pombo.android.core.GraphApi.userApiKey = settingsStore.graphApiKey
        _graphApiKey.value = settingsStore.graphApiKey ?: ""
        com.pombo.android.core.GraphApi.clearCache()
    }

    init {
        // Scope to the stored account before anything reads the lists.
        channelStore.scopeAddress = store.address
        contactsStore.scopeAddress = store.address
        inviteStore.scopeAddress = store.address
        sentDmStore.scopeAddress = store.address
        sentReactionsStore.scopeAddress = store.address
        pushRegistry.scopeAddress = store.address
        settingsStore.scopeAddress = store.address
        syncStore.scopeAddress = store.address
        // Declared above this block, so its initializer read the unscoped key;
        // re-read now that the account scope is set.
        _dmPushEnabled.value = settingsStore.dmPushEnabled
        // Same reason: the Graph key is per account, so it can only be read
        // after the scope is known (reading it earlier leaked the last
        // account's billable credential into this session).
        com.pombo.android.core.GraphApi.userApiKey = settingsStore.graphApiKey
        _graphApiKey.value = settingsStore.graphApiKey ?: ""

        // Stop the transfer foreground-service the MOMENT the last transfer finishes,
        // instead of waiting out its 30-60s idle self-check (the badge lingered). Only
        // stops on a busy -> idle transition, so it never kills a service that
        // ensureTransferService() has just started for a transfer about to populate.
        viewModelScope.launch {
            var wasBusy = false
            kotlinx.coroutines.flow.combine(
                manager.storageMedia.uploads, manager.storageMedia.downloads, manager.media.progress
            ) { _, _, _ -> transferBusy() }.collect { busy ->
                if (busy) wasBusy = true
                else if (wasBusy) { wasBusy = false; MediaTransferService.stop(getApplication()) }
            }
        }
        // The P3 gate must hold this account's preference before the first
        // subscribeMyInbox (which runs on bridge connect).
        manager.inviteNotificationsEnabled = settingsStore.inviteNotificationsEnabled
        // Any local mutation coalesces into one push after a quiet period.
        manager.onLocalStateChanged = { sync.scheduleAutoPush() }
        // Slice timestamps for changes born inside the manager (dmLeftAt).
        manager.onSliceTouched = { sliceTouched(it) }
        // A verified FCM wake arriving while the app is on screen becomes an
        // unread-badge update instead of a system notification (sw.js hands
        // the event to the focused client the same way). DMs are skipped:
        // the inbox subscription is always live in-foreground, so the in-app
        // path already paints/notifies them — a second count would be double.
        com.pombo.android.push.ForegroundGate.deliverInApp = { type, conversationId, ts ->
            if (type != "dm-inbox" && current.value?.messageStreamId != conversationId) {
                unreadStore.increment(conversationId)
                unreadStore.setWatermark(conversationId, ts)
            }
        }
        manager.onIncomingMessage = { channel, sender, preview ->
            // Nothing to notify about if the user is already looking at it —
            // and a muted DM peer stays silent on the in-app path too.
            val looking = appInForeground && current.value?.messageStreamId == channel.messageStreamId
            val dmMuted = channel.type == "dm" && sender.lowercase() in settingsStore.mutedDmPeers
            if (!looking && !dmMuted) {
                viewModelScope.launch {
                    // Sender identity like the web (getCachedDisplayName):
                    // contact nickname → ENS → the DM room's own name → address.
                    val nickname = _contacts.value
                        .firstOrNull { it.address.equals(sender, ignoreCase = true) }?.nickname
                    val who = nickname
                        ?: ensStore.cachedName(sender)
                        ?: if (channel.type == "dm") channel.name
                        else (sender.take(6) + "…" + sender.takeLast(4))
                    val title = if (channel.type == "dm") who else "${channel.name} · $who"
                    // DM notifications carry the sender's face: ENS avatar
                    // when cached, the generated one otherwise.
                    val avatar = if (channel.type == "dm")
                        com.pombo.android.core.NotificationAvatar.bitmapFor(
                            getApplication(), sender, ensStore.cachedAvatar(sender)
                        )
                    else null
                    notifier.postMessage(
                        conversationId = channel.messageStreamId,
                        title = title,
                        body = preview.ifEmpty { "Sent an image" },
                        largeIcon = avatar
                    )
                }
            }
        }
        // ChannelManager reads the store in its own property initialiser, which
        // runs before this block — so that first read used the unscoped key
        // while every save goes to the scoped one, and channels created in a
        // session were gone on the next launch. Re-read now that the scope is
        // known.
        // (`_contacts` is declared below this block, so it already reads the
        // scoped key and needs no reload.)
        if (store.address != null) {
            manager.reloadChannels()
            manager.reloadInvites()
            hydrateFromSyncBase()
            // My own ENS picture shows in the pill and in Settings, and every
            // DM peer's shows in the chat list — resolve them up front so the
            // avatars are right on the first frame instead of after a chat is
            // opened.
            manager.ensureEns(store.address)
            manager.channels.value.mapNotNull { it.peerAddress }.distinct()
                .forEach { manager.ensureEns(it) }
        }
        // Load the disk caches before the first frame needs them.
        viewModelScope.launch {
            imageStore.warmUp()
            previewStore.warmUp()
            ensStore.warmUp()
            // Background, non-critical: picks up admin renames for channels the
            // user already joined (web does this right after the initial render).
            runCatching { manager.refreshChannelMetadataFromGraph() }
        }
        // A Keystore invalidation (lock screen removed, backup restore) forces
        // SecurePrefs to reset the affected files so the app can boot at all —
        // a silent reset of the wallet file would otherwise look like account loss.
        val resets = com.pombo.android.core.SecurePrefs.consumeResetNotices(app)
        if (resets.isNotEmpty()) {
            val lostWallet = "pombo_secure" in resets
            toast(
                if (lostWallet)
                    "Secure storage was reset (device lock changed?). Your account key could not be recovered — restore it from a backup or private key."
                else
                    "Secure storage was reset (device lock changed?). Your data will restore via device sync.",
                com.pombo.android.ui.ToastKind.ERROR,
                12_000L
            )
        }
    }

    fun setGraphApiKey(key: String) {
        val v = key.trim().ifEmpty { null }
        settingsStore.graphApiKey = v
        com.pombo.android.core.GraphApi.userApiKey = v
        _graphApiKey.value = v ?: ""
        // Discovery results depend on the key — refetch with the new one.
        com.pombo.android.core.GraphApi.clearCache()
        loadExplore()
        toast("Graph API key updated!", com.pombo.android.ui.ToastKind.SUCCESS)
        sliceTouched("graphApiKey")
    }

    private fun startGuestSession() = viewModelScope.launch {
        try {
            val res = bridge.call("createWallet", timeoutMs = 30_000)
            val pk = res.getString("privateKey")
            _address.value = res.getString("address")
            _isGuest.value = true
            _hasWallet.value = false
            // A guest is a brand-new identity, so it carries no name. The store
            // keys usernames by the SAVED account's address, which a guest
            // session does not change — leaving this alone showed the guest
            // under the previous account's name.
            _username.value = null
            // Guest: memory-only storage, starting empty (web initAsGuest).
            applyStorageScope(null, guest = true)
            bridge.privateKey = pk
            _status.value = NetStatus.CONNECTING
            bridge.reconnect()
        } catch (e: Exception) {
            _status.value = NetStatus.NO_IDENTITY
        }
    }

    fun startAddAccount() { _addingAccount.value = true }
    fun cancelAddAccount() { _addingAccount.value = false }

    /** Switches to another stored account and reconnects. */
    fun switchWallet(address: String) {
        // A guest session runs on a throwaway wallet while `store.address` still
        // points at the last real account, so this "already there" check would
        // short-circuit the one path back — the user tapped their own account in
        // the list and nothing happened.
        if (!_isGuest.value && address.equals(store.address, ignoreCase = true)) return
        if (!store.switchTo(address)) return
        manager.closeCurrent()
        applyStorageScope(address, guest = false)
        // Leaving guest mode is part of switching: without these the session is
        // the real account but the UI still calls it a guest.
        _isGuest.value = false
        _hasWallet.value = true
        _address.value = store.address
        _username.value = store.username
        bridge.privateKey = store.privateKey ?: ""
        _status.value = NetStatus.CONNECTING
        bridge.reconnect()
        toast("Account switched!", com.pombo.android.ui.ToastKind.SUCCESS)
    }

    private val _contacts = MutableStateFlow(contactsStore.load())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    // Declared after the init block, like _contacts, so the first read already
    // uses the account-scoped key.
    private val _youtubeEmbeds = MutableStateFlow(settingsStore.youtubeEmbeds)
    /** Web settings "YouTube Embeds" — inline players for YouTube links, default ON. */
    val youtubeEmbeds: StateFlow<Boolean> = _youtubeEmbeds.asStateFlow()

    // ==================== P2P file transfers ====================

    /** Live per-file transfer state, keyed by fileId. */
    val fileProgress: StateFlow<Map<String, com.pombo.android.core.MediaController.Progress>> =
        manager.media.progress

    val uploadStats: StateFlow<Map<String, com.pombo.android.core.MediaController.UploadStats>> =
        manager.media.uploadStats

    /** Live storage-transport upload state, keyed by transferId (Persistent File Sharing). */
    val storageUploads: StateFlow<Map<String, com.pombo.android.core.StorageMedia.UploadProgress>> =
        manager.storageMedia.uploads

    /** Live storage-transport download state, keyed by transferId. */
    val storageDownloads: StateFlow<Map<String, com.pombo.android.core.StorageMedia.DownloadProgress>> =
        manager.storageMedia.downloads

    /** Whether a storage download has landed on disk, ready to save. */
    fun storageFileReady(transferId: String) = manager.storageCompletedFile(transferId) != null

    // Auto-save bookkeeping: which completed transfers already had saveFile/
    // saveStorageFile run for them, so a bubble doesn't re-trigger the
    // MediaStore write on every recomposition. Persisted (plain prefs, not
    // sensitive — just fileIds) because unlike the web's in-memory blob URLs,
    // a seeded/completed file's "local, ready" state survives an app
    // restart here — without persistence, every relaunch would look like a
    // fresh completion and re-save every already-saved file again.
    private val savedFilesPrefs = app.getSharedPreferences("pombo_saved_files", android.content.Context.MODE_PRIVATE)

    private val _savedFileIds = MutableStateFlow(savedFilesPrefs.getStringSet("fileIds", emptySet()) ?: emptySet())
    val savedFileIds: StateFlow<Set<String>> = _savedFileIds.asStateFlow()
    private fun markFileSaved(fileId: String) {
        val next = _savedFileIds.value + fileId
        _savedFileIds.value = next
        savedFilesPrefs.edit().putStringSet("fileIds", next).apply()
    }

    private val _savedTransferIds = MutableStateFlow(savedFilesPrefs.getStringSet("transferIds", emptySet()) ?: emptySet())
    val savedTransferIds: StateFlow<Set<String>> = _savedTransferIds.asStateFlow()
    private fun markTransferSaved(transferId: String) {
        val next = _savedTransferIds.value + transferId
        _savedTransferIds.value = next
        savedFilesPrefs.edit().putStringSet("transferIds", next).apply()
    }

    /** File + channel names for a storage transfer (Active Transfers list). */
    fun storageTransferInfo(transferId: String) = manager.storageTransferInfo(transferId)

    /** Starts (or resumes) the storage download announced by [messageId]. */
    fun downloadStorageFile(messageId: String) {
        ensureTransferService()
        manager.downloadStorageFile(messageId)
    }

    /** Pauses a running storage download from the Active Transfers list — keeps its bytes. */
    fun pauseStorageTransfer(transferId: String) = manager.pauseStorageTransfer(transferId)

    /** Cancels a storage download — partial bytes deleted, unlike pause. */
    fun cancelStorageTransfer(transferId: String) = manager.cancelStorageTransfer(transferId)

    /** Resumes a storage download [pauseStorageTransfer] stopped. */
    fun resumeStorageTransfer(transferId: String) {
        ensureTransferService()
        manager.resumeStorageTransfer(transferId)
    }

    /** transferId -> "pausing"/"resuming" — see [ChannelManager.storageTransferPhase]. */
    val storageTransferPhases: StateFlow<Map<String, String>> = manager.storageTransferPhase

    /** Copies a completed storage download into the public Downloads collection (see [saveFile]). */
    fun saveStorageFile(transferId: String, fileName: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val app = getApplication<Application>()
            val file = manager.storageCompletedFile(transferId)
                ?: run { toast("The file is not ready yet", com.pombo.android.ui.ToastKind.ERROR); return@launch }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = app.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw java.io.IOException("MediaStore refused the file")
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: throw java.io.IOException("Cannot open the destination")
                    values.clear()
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    val dir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                        ?: throw java.io.IOException("No external storage")
                    java.io.File(dir, fileName).outputStream().use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                markTransferSaved(transferId)
                toast("Saved $fileName", com.pombo.android.ui.ToastKind.SUCCESS)
            } catch (e: Exception) {
                toast("Save failed: ${e.message}", com.pombo.android.ui.ToastKind.ERROR)
            }
        }
    }

    /** Whether this device holds the file complete and serves it. */
    fun isSeedingFile(fileId: String) = manager.media.isSeedingFile(fileId)

    val activeSeeds: StateFlow<List<com.pombo.android.core.MediaController.SeedInfo>> =
        manager.media.seeds

    fun cancelTransfer(fileId: String) = manager.cancelTransfer(fileId)
    fun pauseTransfer(fileId: String) = manager.pauseTransfer(fileId)
    fun resumeTransfer(fileId: String) = manager.resumeTransfer(fileId)
    fun stopSeeding(fileId: String) = manager.stopSeeding(fileId)
    fun deleteSeed(fileId: String) = manager.deleteSeed(fileId)
    fun reseedFile(fileId: String, messageStreamId: String) = manager.reseedFile(fileId, messageStreamId)
    fun inactiveSeeds() = manager.inactiveSeeds()

    /** Starts fetching the file announced by [messageId] in the open channel. */
    fun downloadFile(messageId: String) {
        ensureTransferService()
        manager.downloadFile(messageId)
    }

    /**
     * Copies a finished transfer into the public Downloads collection.
     *
     * MediaStore rather than a direct write to external storage: from API 29
     * that is the only way in without WRITE_EXTERNAL_STORAGE, and it puts the
     * file somewhere the user can actually find it. Below 29 the app's own
     * external directory is used, which needs no permission either.
     */
    fun saveFile(fileId: String, fileName: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                var ok: Boolean
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                        // Pending hides the entry from other apps until the
                        // bytes are all there, so nothing can open a half-
                        // written file and decide it is corrupt.
                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = app.contentResolver
                    val uri = resolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: throw java.io.IOException("MediaStore refused the file")
                    ok = resolver.openOutputStream(uri)?.use { out ->
                        manager.media.exportTo(fileId, out)
                    } ?: throw java.io.IOException("Cannot open the destination")
                    if (ok) {
                        values.clear()
                        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } else {
                        // Never leave a pending, empty entry behind.
                        resolver.delete(uri, null, null)
                    }
                } else {
                    val dir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                        ?: throw java.io.IOException("No external storage")
                    val out = java.io.File(dir, fileName)
                    ok = out.outputStream().use { manager.media.exportTo(fileId, it) }
                    if (!ok) out.delete()
                }
                if (ok) {
                    markFileSaved(fileId)
                    toast("Saved $fileName", com.pombo.android.ui.ToastKind.SUCCESS)
                } else toast("Could not read the downloaded file", com.pombo.android.ui.ToastKind.ERROR)
            } catch (e: Exception) {
                toast("Save failed: ${e.message}", com.pombo.android.ui.ToastKind.ERROR)
            }
        }
    }

    fun setYoutubeEmbeds(enabled: Boolean) {
        settingsStore.youtubeEmbeds = enabled
        _youtubeEmbeds.value = enabled
    }

    private val _inviteNotifications = MutableStateFlow(settingsStore.inviteNotificationsEnabled)
    /** Web settings "Channel Invites" — inbox P3 subscription, default ON. */
    val inviteNotifications: StateFlow<Boolean> = _inviteNotifications.asStateFlow()

    fun setInviteNotifications(enabled: Boolean) {
        settingsStore.inviteNotificationsEnabled = enabled
        _inviteNotifications.value = enabled
        manager.setInviteNotifications(enabled)
        toast(
            if (enabled) "Channel invites enabled" else "Channel invites muted",
            com.pombo.android.ui.ToastKind.INFO
        )
    }

    private val _syncMode = MutableStateFlow(settingsStore.syncMode)
    /** Settings → Account "Device Sync" — which unprompted sync triggers may fire. */
    val syncMode: StateFlow<com.pombo.android.data.SyncMode> = _syncMode.asStateFlow()

    fun setSyncMode(mode: com.pombo.android.data.SyncMode) {
        if (_syncMode.value == mode) return
        settingsStore.syncMode = mode
        _syncMode.value = mode
        // Leaving an automatic mode should not leave a debounced push armed.
        if (mode == com.pombo.android.data.SyncMode.MANUAL_ONLY) sync.cancelAutoPush()
        toast("Sync set to ${mode.label.lowercase()}", com.pombo.android.ui.ToastKind.INFO)
    }

    private val _nsfwEnabled = MutableStateFlow(settingsStore.nsfwEnabled)
    /** Web settings "Show Sensitive Content" — NSFW/Adult channels in Explore, default OFF. */
    val nsfwEnabled: StateFlow<Boolean> = _nsfwEnabled.asStateFlow()

    fun setNsfwEnabled(enabled: Boolean) {
        settingsStore.nsfwEnabled = enabled
        _nsfwEnabled.value = enabled
    }

    private val _explore = MutableStateFlow<List<ExploreChannel>>(emptyList())
    val explore: StateFlow<List<ExploreChannel>> = _explore.asStateFlow()
    private val _exploreLoading = MutableStateFlow(false)
    val exploreLoading: StateFlow<Boolean> = _exploreLoading.asStateFlow()

    // ==================== identity ====================

    fun createWallet() = viewModelScope.launch {
        runBusy {
            val res = bridge.call("createWallet")
            adoptWallet(res.getString("privateKey"), res.getString("address"))
            _newMnemonic.value = res.optString("mnemonic", "").ifEmpty { null }
            toast("Account created!", com.pombo.android.ui.ToastKind.SUCCESS)
        }
    }

    /**
     * Candidate wallets for the new-account wizard's avatar grid (web
     * walletFlows.showNewAccountSetupModal): every avatar IS a real wallet,
     * because the avatar is derived deterministically from the address —
     * picking the picture picks the account.
     */
    suspend fun generateWalletCandidates(count: Int = 6): List<Pair<String, String>> =
        (1..count).map {
            val res = bridge.call("createWallet")
            res.getString("address") to res.getString("privateKey")
        }

    /**
     * Finishes the wizard: adopts the picked wallet and applies the chosen
     * display name in one step (no "Name saved" toast — the account toast
     * covers the whole action).
     */
    fun createAccountFromSetup(privateKey: String, address: String, name: String?) {
        adoptWallet(privateKey, address)
        name?.trim()?.ifEmpty { null }?.let {
            store.username = it
            _username.value = it
            sliceTouched("username")
        }
        toast("Account created!", com.pombo.android.ui.ToastKind.SUCCESS)
    }

    fun importWallet(input: String) = viewModelScope.launch {
        runBusy {
            val trimmed = input.trim()
            val res = if (trimmed.split(Regex("\\s+")).size >= 12) {
                bridge.call("walletFromMnemonic", JSONObject().put("phrase", trimmed))
            } else {
                bridge.call("walletFromPrivateKey", JSONObject().put("privateKey", trimmed))
            }
            adoptWallet(res.getString("privateKey"), res.getString("address"))
            toast("Account imported!", com.pombo.android.ui.ToastKind.SUCCESS)
        }
    }

    // ==================== account backup (web SettingsUI Backup & Restore) ====================

    /**
     * Writes a web-compatible encrypted account backup to [uri]
     * (web export-all-data-btn → secureStorage.exportAccountBackup): keystore
     * V3 + scrypt/AES-GCM state, so the file restores on the web with the
     * same password. The web reuses the account's keystore password; this app
     * has no password, so the user chooses one at export time.
     */
    fun exportBackupTo(
        uri: android.net.Uri,
        password: String,
        includeSentDmMedia: Boolean = true
    ) = viewModelScope.launch {
        runWithToast("Creating backup… this can take a minute", "Backup exported", "Backup failed") {
            val pk = store.privateKey ?: throw IllegalStateException("No account")
            val address = store.address ?: throw IllegalStateException("No account")

            // Backup policy: only state with no other durable copy. Received
            // messages and media re-fetch from the storage nodes within
            // retention; the ENS cache re-resolves; slice timestamps describe
            // the sync merge, not the account. Epoch keys DO enter (via
            // exportSyncState): paid gates never re-distribute past epochs
            // (D14), and an announce older than the -4 retention can no
            // longer anchor a re-adopted key.
            val data = exportSyncState().apply {
                remove("ensCache")
                remove("sliceTs")
            }

            // Sent-message media is the only irreplaceable media: an outgoing
            // DM lands in the PEER's inbox (owner-only reads) and a write-only
            // stream cannot be resent. Everything else in the blob ledger is a
            // warm cache of what the streams still serve.
            val blobs = org.json.JSONArray()
            if (includeSentDmMedia) {
                val sentImageIds = mutableSetOf<String>()
                data.optJSONObject("sentMessages")?.let { sm ->
                    sm.keys().forEach { streamId ->
                        val arr = sm.optJSONArray(streamId) ?: return@forEach
                        for (i in 0 until arr.length()) {
                            val m = arr.optJSONObject(i) ?: continue
                            if (m.optString("type") == "image") {
                                m.optString("imageId").ifEmpty { null }?.let { sentImageIds.add(it) }
                            }
                        }
                    }
                }
                blobStore.allRecords().forEach { rec ->
                    if (rec.imageId !in sentImageIds) return@forEach
                    blobStore.load(rec.imageId)?.let { dataUrl ->
                        blobs.put(
                            JSONObject()
                                .put("imageId", rec.imageId)
                                .put("streamId", rec.streamId)
                                .put("data", dataUrl)
                        )
                    }
                }
            }
            val payload = JSONObject()
                .put("version", 1)
                .put(
                    "exportedAt",
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .format(java.util.Date())
                )
                .put("address", address)
                .put("data", data)
                .put("imageBlobs", blobs)

            // Native (AccountBackup): the key and the multi-MB payload never
            // cross the bridge. Two scrypt runs at N=131072 — slow by design.
            withContext(Dispatchers.Default) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                    com.pombo.android.core.AccountBackup.export(it, pk, password, payload)
                } ?: throw IllegalStateException("Could not open the selected file")
            }
        }
    }

    /**
     * Restores a `pombo-account-backup` file (made here or on the web):
     * decrypts natively (AccountBackup — no bridge, so it also works in guest
     * mode), adopts the wallet, then feeds the state through the same import
     * path a sync pull uses and refills the image ledger.
     */
    fun importBackupFrom(uri: android.net.Uri, password: String) = viewModelScope.launch {
        runWithToast("Restoring backup… this can take a minute", "Backup restored", "Restore failed") {
            val res = withContext(Dispatchers.Default) {
                com.pombo.android.core.AccountBackup.import(
                    {
                        getApplication<Application>().contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Could not read the selected file")
                    },
                    password
                )
            }
            val payload = res.payload

            // Adopt first so every scoped store points at the restored account
            // before any of its data lands.
            adoptWallet(res.privateKey, res.address)
            payload.optJSONObject("data")?.let { importSyncState(it) }
            // Unlike a sync pull, a restore has no corresponding payload on
            // the storage node yet — push it, or every other device (and this
            // one, on its next pull) keeps converging on the pre-backup state.
            sync.scheduleAutoPush()

            payload.optJSONArray("imageBlobs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("imageId")
                    val data = o.optString("data")
                    if (id.isNotEmpty() && data.isNotEmpty()) {
                        // synced=true: these came from a backup of an account
                        // whose other devices already hold them.
                        blobStore.save(id, o.optString("streamId"), data, synced = true)
                    }
                }
            }
        }
    }

    /**
     * Epoch keys for every channel running the keys-stream protocol, in the
     * web's persisted shape ({ epochs, announces?, currentEpoch } per stream).
     */
    private fun exportEpochKeys(): JSONObject {
        val out = JSONObject()
        manager.channels.value.forEach { ch ->
            epochKeyStore.load(ch.messageStreamId)?.let { out.put(ch.messageStreamId, it) }
        }
        return out
    }

    /**
     * Union-merge per channel, local wins: a live session may hold epochs the
     * backup predates, and a channel key adopted once must never regress.
     */
    private fun importEpochKeys(slice: JSONObject) {
        slice.keys().forEach { streamId ->
            val incoming = slice.optJSONObject(streamId) ?: return@forEach
            val local = epochKeyStore.load(streamId)
            if (local == null) {
                epochKeyStore.save(streamId, incoming)
                return@forEach
            }
            val localEpochs = local.optJSONObject("epochs")
                ?: JSONObject().also { local.put("epochs", it) }
            incoming.optJSONObject("epochs")?.let { inc ->
                inc.keys().forEach { kid -> if (!localEpochs.has(kid)) localEpochs.put(kid, inc.get(kid)) }
            }
            val localAnnounces = local.optJSONObject("announces")
                ?: JSONObject().also { local.put("announces", it) }
            incoming.optJSONObject("announces")?.let { inc ->
                inc.keys().forEach { e -> if (!localAnnounces.has(e)) localAnnounces.put(e, inc.get(e)) }
            }
            if (incoming.optInt("currentEpoch") > local.optInt("currentEpoch")) {
                local.put("currentEpoch", incoming.optInt("currentEpoch"))
            }
            epochKeyStore.save(streamId, local)
        }
    }

    fun dismissMnemonic() { _newMnemonic.value = null }

    fun setUsername(name: String?) {
        val v = name?.trim()?.ifEmpty { null }
        store.username = v
        _username.value = v
        toast("Name saved", com.pombo.android.ui.ToastKind.SUCCESS)
        sliceTouched("username")
    }

    fun disconnect() {
        manager.closeCurrent()
        store.clear()
        _accounts.value = store.accounts()
        _address.value = store.address
        _username.value = store.username
        _hasWallet.value = store.hasWallet
        bridge.privateKey = store.privateKey ?: ""
        _status.value = if (store.hasWallet) NetStatus.CONNECTING else NetStatus.NO_IDENTITY
        bridge.reconnect()
    }

    /**
     * Leaves the current account for a throwaway guest identity, WITHOUT
     * touching the keystore — the stored accounts stay put, so the profile's
     * account list can bring the user straight back. This is what the profile
     * menu offers; erasing an account is Settings → Security only.
     */
    fun browseAsGuest() {
        if (_isGuest.value) return
        manager.closeCurrent()
        startGuestSession()
    }

    private fun adoptWallet(pk: String, address: String) {
        store.save(pk, address)
        applyStorageScope(address, guest = false)
        _accounts.value = store.accounts()
        _addingAccount.value = false
        _isGuest.value = false
        _address.value = address
        _username.value = store.username
        _hasWallet.value = true
        bridge.privateKey = pk
        _status.value = NetStatus.CONNECTING
        bridge.reconnect()
    }

    // ==================== gas ====================

    /**
     * Estimated on-chain cost per channel type and the wallet's POL balance —
     * web: ui/GasEstimator.js, shown in the create-channel footer, the DM inbox
     * modal and Settings → Wallet.
     */
    private val _gasCosts = MutableStateFlow<com.pombo.android.core.GasEstimator.Costs?>(null)
    val gasCosts: StateFlow<com.pombo.android.core.GasEstimator.Costs?> = _gasCosts.asStateFlow()

    private val _balanceWei = MutableStateFlow<java.math.BigInteger?>(null)
    val balanceWei: StateFlow<java.math.BigInteger?> = _balanceWei.asStateFlow()

    /** DATA token balance (Settings → Wallet) — an addition over the web panel. */
    private val _dataBalanceWei = MutableStateFlow<java.math.BigInteger?>(null)
    val dataBalanceWei: StateFlow<java.math.BigInteger?> = _dataBalanceWei.asStateFlow()

    // ==================== RPC preference (web rpc-preset-select) ====================

    private val _rpcPreset = MutableStateFlow(settingsStore.rpcPreset)
    val rpcPreset: StateFlow<String> = _rpcPreset.asStateFlow()
    private val _rpcCustomUrl = MutableStateFlow(settingsStore.rpcCustomUrl ?: "")
    val rpcCustomUrl: StateFlow<String> = _rpcCustomUrl.asStateFlow()

    sealed interface RpcTest {
        data object Idle : RpcTest
        data object Testing : RpcTest
        data class Done(val working: Int, val total: Int) : RpcTest
    }

    private val _rpcTest = MutableStateFlow<RpcTest>(RpcTest.Idle)
    val rpcTest: StateFlow<RpcTest> = _rpcTest.asStateFlow()

    /**
     * Web saveRpcPreference: persist, then reconnect the Streamr client with
     * the new endpoints. Picking "custom" without a URL only switches the UI —
     * nothing to connect to yet; Apply with the URL does the save.
     */
    fun setRpcPreference(preset: String, customUrl: String?) {
        val custom = customUrl?.trim()?.ifEmpty { null }
        _rpcPreset.value = preset
        _rpcTest.value = RpcTest.Idle
        if (preset == "custom" && custom == null) return
        settingsStore.rpcPreset = preset
        settingsStore.rpcCustomUrl = if (preset == "custom") custom else settingsStore.rpcCustomUrl
        _rpcCustomUrl.value = settingsStore.rpcCustomUrl ?: ""
        bridge.rpcUrls = com.pombo.android.core.RpcPresets.bridgeUrls(preset, custom)
        toast("Reconnecting with new RPC...", com.pombo.android.ui.ToastKind.INFO)
        _status.value = NetStatus.CONNECTING
        bridge.reconnect()
    }

    /** Web testRpcConnection — tests the first 3 endpoints of the selection. */
    fun testRpc() = viewModelScope.launch {
        val urls = com.pombo.android.core.RpcPresets.urlsFor(
            _rpcPreset.value,
            _rpcCustomUrl.value.ifEmpty { null }
        ).take(3)
        if (urls.isEmpty()) return@launch
        _rpcTest.value = RpcTest.Testing
        val working = com.pombo.android.core.GasEstimator.testEndpoints(urls)
        _rpcTest.value = RpcTest.Done(working, urls.size)
    }

    /** True when the last balance fetch came back empty — drives "Unavailable". */
    private val _balancesFailed = MutableStateFlow(false)
    val balancesFailed: StateFlow<Boolean> = _balancesFailed.asStateFlow()

    /** Pulled when a spend-money screen opens; all calls are independently cached/cheap. */
    fun refreshGas() = viewModelScope.launch {
        // Reset to null first so the UI shows a loading state immediately.
        _balanceWei.value = null
        _dataBalanceWei.value = null
        _balancesFailed.value = false
        _gasCosts.value = com.pombo.android.core.GasEstimator.estimateCosts()
        _address.value?.let {
            _balanceWei.value = com.pombo.android.core.GasEstimator.getBalance(it)
            _dataBalanceWei.value = com.pombo.android.core.GasEstimator.getDataBalance(it)
        }
        _balancesFailed.value = _balanceWei.value == null || _dataBalanceWei.value == null
    }

    /**
     * Verdict of the pre-flight balance check (web: ChannelModalsUI ~line 585).
     * `Low` still allows the spend — the web asks for confirmation there rather
     * than refusing, since the estimate is only an estimate.
     */
    sealed interface CostCheck {
        data object Ok : CostCheck
        data class Low(val balance: String, val estimate: String) : CostCheck
        data class Blocked(val message: String) : CostCheck
    }

    /**
     * Requiring 1.5× the estimate as "safe": below that a gas spike between the
     * quote and the last of the eight transactions can strand the run half-way.
     */
    private val SAFETY_NUMERATOR = java.math.BigInteger.valueOf(3)
    private val SAFETY_DENOMINATOR = java.math.BigInteger.valueOf(2)

    /**
     * Runs the check. An RPC failure never blocks — the user can still try,
     * exactly as on web. Also refreshes the exposed cost/balance state.
     */
    suspend fun checkSpendCost(type: String): CostCheck {
        val address = _address.value ?: return CostCheck.Ok
        val estimator = com.pombo.android.core.GasEstimator
        val balance = estimator.getBalance(address) ?: return CostCheck.Ok
        val costs = estimator.estimateCosts()
        _gasCosts.value = costs
        _balanceWei.value = balance

        val estimate = costs.forType(type)
        val formattedEstimate = costs.formattedFor(type)
        val formattedBalance = estimator.formatBalancePOL(balance)
        val safe = estimate.multiply(SAFETY_NUMERATOR).divide(SAFETY_DENOMINATOR)

        return when {
            balance.signum() == 0 -> CostCheck.Blocked(
                "No POL in wallet — this costs about $formattedEstimate. Top up and try again."
            )
            balance < estimate -> CostCheck.Blocked(
                "Insufficient POL: balance $formattedBalance is below the estimated cost ($formattedEstimate)."
            )
            balance < safe -> CostCheck.Low(formattedBalance, formattedEstimate)
            else -> CostCheck.Ok
        }
    }

    /**
     * Pre-flight for flows with no modal of their own, so a plain toast is
     * visible. Create Channel does its own check inside the dialog instead —
     * that dialog is a separate window and would hide this toast.
     */
    private suspend fun canAffordOrWarn(type: String): Boolean {
        val check = checkSpendCost(type)
        if (check is CostCheck.Blocked) {
            toast(check.message, com.pombo.android.ui.ToastKind.ERROR, 6000L)
            return false
        }
        return true
    }

    // ==================== channels ====================

    /**
     * The balance pre-flight runs in CreateChannelDialog before this is called
     * (web: ChannelModalsUI.handleCreateChannel does the same in the UI layer),
     * so a wallet that cannot pay never reaches stream creation.
     */
    fun createChannel(spec: com.pombo.android.ui.screens.NewChannel) = viewModelScope.launch {
        val streamCount = if (spec.type == "native" || spec.type == "gated") 4 else 3
        chainAction(
            "Create channel",
            "Creates \"${spec.name}\" — $streamCount streams, their permissions and storage."
        ) {

        // createStream + setPermissions per stream + storage (bridge does
        // addToStorageNode and setStorageDayCount in one call, unlike the web).
        // Public/password: 3+3+2 = 8. Native adds the keys stream (-4, with
        // storage): 4+4+3 = 11.
        // Gated adds the gate deploy: 1+4+4+3 = 12.
        val totalSteps = when (spec.type) {
            "gated" -> 12
            "native" -> 11
            else -> 8
        }
        val id = toast(
            "Creating channel...", com.pombo.android.ui.ToastKind.LOADING, Long.MAX_VALUE,
            subtitle = "This may take a minute",
            progress = com.pombo.android.ui.ToastProgress(0, totalSteps, "Creating Channel...")
        )
        var step = 0
        _busy.value = true
        try {
            val channel = manager.createChannel(
                name = spec.name,
                type = spec.type,
                password = spec.password,
                exposure = spec.exposure,
                readOnly = spec.readOnly,
                description = spec.description,
                language = spec.language,
                category = spec.category,
                members = spec.members,
                classification = spec.classification,
                storageProvider = spec.storageProvider,
                customStorageAddress = spec.customStorageAddress,
                storageDays = spec.storageDays,
                gateMode = spec.gateMode,
                gateToken = spec.gateToken,
                gateMinBalance = spec.gateMinBalance,
                gatePrice = spec.gatePrice,
                gateDuration = spec.gateDurationSeconds
            ) {
                step += 1
                val label = when {
                    step <= streamCount -> "Creating Channel..."
                    step <= streamCount * 2 -> "Setting Permissions..."
                    else -> "Setting Storage..."
                }
                setToastProgress(id, step, label)
            }
            dismissToast(id)
            toast("Channel created successfully!", com.pombo.android.ui.ToastKind.SUCCESS)
            manager.openChannel(channel.messageStreamId)
            autoEnableChannelPush(channel)
        } catch (e: Exception) {
            dismissToast(id)
            toast("Failed to create channel: ${e.message ?: "unknown error"}", com.pombo.android.ui.ToastKind.ERROR, 5000L)
        } finally {
            _busy.value = false
        }
        }
    }

    fun joinChannel(input: String, password: String?) = viewModelScope.launch {
        runJoinToast("Joining channel…", "Joined channel", "Failed to join channel") {
            val channel = manager.joinChannel(input, password?.ifEmpty { null })
            manager.openChannel(channel.messageStreamId)
            autoEnableChannelPush(channel)
        }
        // Joining by raw stream ID names the channel after the ID segment; the
        // on-chain metadata has the real name. Runs after the open so the header
        // is already up.
        runCatching { manager.refreshChannelMetadataFromGraph() }
    }

    // ---- Gate entry screen (N-D): a gated channel refused the join ----

    data class GateEntry(
        val info: ChannelManager.GateEntryInfo,
        val channelName: String?,
        /** Renewing from inside the channel: pay is always offered, no "Enter". */
        val renewal: Boolean = false,
        val retry: suspend () -> Unit
    )

    private val _gateEntry = MutableStateFlow<GateEntry?>(null)
    val gateEntry: StateFlow<GateEntry?> = _gateEntry.asStateFlow()

    /** The live pay toast, while gatePay runs — its message follows the
     *  bridge's step announcements (wrap → approve → pay). */
    @Volatile private var payToastId: Long? = null

    override fun onBridgeProgress(op: String, step: String) {
        if (op != "gatePay") return
        val id = payToastId ?: return
        updateToast(id, when (step) {
            "wrap" -> "Wrapping POL…"
            "approve" -> "Approving token…"
            else -> "Paying subscription…"
        })
    }

    fun dismissGateEntry() { _gateEntry.value = null }

    private suspend fun openGateEntry(
        gateAddress: String, channelName: String?,
        renewal: Boolean = false, retry: suspend () -> Unit
    ) {
        try {
            _gateEntry.value = GateEntry(manager.gateEntryInfo(gateAddress), channelName, renewal, retry)
        } catch (e: Exception) {
            toast(
                "Could not read the gate contract: ${com.pombo.android.core.ChainErrors.friendly(e)}",
                com.pombo.android.ui.ToastKind.ERROR, 5000L
            )
        }
    }

    /** Re-read the on-chain standing (balance / paidUntil are never cached). */
    fun gateEntryRecheck() = viewModelScope.launch {
        val entry = _gateEntry.value ?: return@launch
        manager.gateInvalidateAccess(entry.info.gateAddress)
        openGateEntry(entry.info.gateAddress, entry.channelName, entry.renewal, entry.retry)
    }

    /** The current user's PAID standing on the open channel (null = not paid-gated). */
    val paidStatus get() = manager.paidStatus

    /**
     * Renew (or restore) the subscription from inside the open channel — the
     * entry screen in renewal mode. After payment the retry re-reads the
     * standing and fires one immediate KEY_REQUEST: requests sent while
     * expired were refused silently, so waiting out the backoff would leave
     * the channel locked for up to a minute after paying.
     */
    fun renewSubscription() = viewModelScope.launch {
        val channel = manager.current.value ?: return@launch
        val gate = channel.gateAddress ?: return@launch
        openGateEntry(gate, channel.name, renewal = true) {
            manager.gateInvalidateAccess(gate)
            manager.refreshPaidStatus()
            manager.requestChannelKeysNow()
        }
    }

    /** Enter: the deny that opened this screen is cached fail-closed 10 min —
     *  invalidate before the retry or the user pays and still gets refused. */
    fun gateEntryEnter() = viewModelScope.launch {
        val entry = _gateEntry.value ?: return@launch
        _gateEntry.value = null
        manager.gateInvalidateAccess(entry.info.gateAddress)
        entry.retry()
    }

    fun gateEntryPay() = viewModelScope.launch {
        val entry = _gateEntry.value ?: return@launch
        // Renewal: the dialog's job ends at the tap — the toast narrates the
        // payment from here. (First-time pay keeps the dialog up: on failure
        // it is the retry context.)
        if (entry.renewal) _gateEntry.value = null
        chainAction(
            "Pay subscription",
            "Pays one subscription period to this channel's gate (may wrap POL and approve the token first)."
        ) {
            val ok = try {
                runWithToast(
                    "Paying subscription…", null, "Payment failed",
                    onToastId = { payToastId = it }
                ) {
                    manager.gatePay(entry.info.gateAddress)
                }
            } finally {
                payToastId = null
            }
            if (ok) {
                _gateEntry.value = null
                entry.retry()
            }
        }
    }

    /** Optional TOKEN/NFT membership registration (sticky everMember). */
    fun gateEntryJoin() = viewModelScope.launch {
        val entry = _gateEntry.value ?: return@launch
        chainAction(
            "Register membership",
            "Registers your address on this channel's gate so your messages stay valid even after selling the asset."
        ) {
            runWithToast("Registering membership…", "Membership registered on-chain", "Registration failed") {
                manager.gateJoin(entry.info.gateAddress)
            }
        }
    }

    // ---- Create-form helpers (N-D token fields) ----

    suspend fun gateTokenMeta(token: String) = manager.gateTokenMeta(token)
    suspend fun gateTokenBalance(token: String) = manager.gateTokenBalance(token)

    /** Members panel: NONE gates take manual adds, TOKEN/NFT/PAID never. */
    suspend fun currentGateMode(): Int? = manager.currentGateMode()

    /** Channel Details access line (null → keep the default label). */
    suspend fun gateAccessLabel(): String? = manager.gateAccessLabel()

    /**
     * Loads the Explore tab (web: channels.js getPublicChannels + exploreCuration).
     * The channel list comes from The Graph; assets/explore.json is only the
     * curation manifest — `hidden` is filtered out and `pinned` is floated to
     * the top in the order listed.
     *
     * Web: `showExploreView()` calls `exploreUI.loadChannels()` on EVERY open —
     * there is no polling timer; the 30s GraphApi query cache is what stops it
     * hammering the subgraph. The only guard kept here is against re-entrancy.
     */
    fun loadExplore() {
        if (_exploreLoading.value) return
        viewModelScope.launch {
            _exploreLoading.value = true
            try {
                val (pinned, hidden) = loadCurationManifest()

                // 1. Public Pombo channels indexed by The Graph.
                val discovered = com.pombo.android.core.GraphApi.getPublicPomboChannels()
                    .associateBy { it.streamId.lowercase() }
                    .toMutableMap()

                // 2. Pinned channels the Graph hasn't indexed (or that are curated
                //    in by ID) still have to show up.
                for (id in pinned) {
                    if (id in discovered) continue
                    com.pombo.android.core.GraphApi.getChannelInfo(id)?.let { discovered[id] = it }
                }

                val visible = discovered.values.filter { it.streamId.lowercase() !in hidden }
                val ordered = visible.sortedWith(
                    compareBy<com.pombo.android.core.GraphApi.ChannelInfo> { info ->
                        val idx = pinned.indexOf(info.streamId.lowercase())
                        if (idx >= 0) idx else Int.MAX_VALUE
                    }.thenByDescending { it.createdAt }
                )

                // Paint immediately: name, description, language and category all
                // come from the Graph metadata, no resend needed. Previews and
                // images fill in afterwards (web ExploreUI renders from cache and
                // lazy-fetches the misses).
                _explore.value = ordered.map { info ->
                    val cached = previewStore.cached(info.streamId)
                    ExploreChannel(
                        messageStreamId = info.streamId,
                        name = info.displayName,
                        description = info.description,
                        type = info.type,
                        language = info.language,
                        category = info.category,
                        lastSender = cached?.sender.orEmpty(),
                        lastText = cached?.text.orEmpty(),
                        lastSenderAddress = cached?.senderAddress.orEmpty(),
                        readOnly = info.readOnly,
                        gateAddress = info.gateAddress
                    )
                }
                _exploreLoading.value = false

                // Lazy pass: last message + channel image per channel, patching
                // the list as each answer arrives.
                for (info in ordered) {
                    // Gated cards: resolve mode + access condition (cached
                    // reads) — drives the Open/Gated/Paid markers and the
                    // access line on the card.
                    if (info.type == "gated" && info.gateAddress != null) {
                        launch {
                            manager.gateCardInfo(info.gateAddress)?.let { card ->
                                _explore.value = _explore.value.map {
                                    if (it.messageStreamId == info.streamId)
                                        it.copy(
                                            gateMode = card.mode, gateVerb = card.verb,
                                            gateValue = card.value, gateQualifier = card.qualifier
                                        ) else it
                                }
                            }
                        }
                    }
                    launch {
                        manager.ensureChannelImage(
                            com.pombo.android.core.StreamConstants.deriveAdminId(info.streamId),
                            label = info.displayName
                        )
                        val preview = previewStore.dedup(info.streamId) {
                            manager.fetchLatestPreview(info.streamId)
                        } ?: return@launch
                        _explore.value = _explore.value.map {
                            if (it.messageStreamId == info.streamId)
                                it.copy(
                                    lastSender = preview.sender, lastText = preview.text,
                                    lastSenderAddress = preview.senderAddress
                                )
                            else it
                        }
                    }
                }
            } catch (e: Exception) {
                _lastError.value = e.message
            } finally {
                _exploreLoading.value = false
            }
        }
    }

    /** assets/explore.json → (pinned, hidden), both lowercase stream IDs. */
    private fun loadCurationManifest(): Pair<List<String>, Set<String>> = try {
        val json = getApplication<Application>().assets.open("explore.json")
            .readBytes().toString(Charsets.UTF_8)
        val obj = JSONObject(json)
        fun list(key: String): List<String> {
            val arr = obj.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).map { arr.optString(it).trim().lowercase() }
                .filter { it.isNotEmpty() }.distinct()
        }
        list("pinned") to list("hidden").toSet()
    } catch (e: Exception) {
        emptyList<String>() to emptySet()
    }

    /** Adds a trusted contact. Accepts a raw address or an ENS name, like the members field; nickname is optional. */
    fun addContact(address: String, name: String?) = viewModelScope.launch {
        // resolveMemberInput returns the address unchanged when it already is
        // one, and only hits the network for a name — so this stays instant for
        // the common case.
        val addr = manager.resolveMemberInput(address)
        if (addr == null) {
            _lastError.value = "Invalid address or ENS name"
            return@launch
        }
        val cleanName = name?.trim()?.ifEmpty { null }
        // Re-adding keeps notes/addedAt/addedBy and only refreshes the
        // nickname + updatedAt, same as the web's addTrustedContact.
        val existing = _contacts.value.firstOrNull { it.address.equals(addr, ignoreCase = true) }
        val now = System.currentTimeMillis()
        val contact = Contact(
            address = addr,
            nickname = cleanName,
            notes = existing?.notes,
            addedAt = existing?.addedAt ?: now,
            addedBy = existing?.addedBy ?: store.address,
            updatedAt = now
        )
        val next = _contacts.value.filterNot { it.address.equals(addr, ignoreCase = true) } + contact
        _contacts.value = next
        contactsStore.save(next)
        // Contacts and DM rooms are linked (web dm.resolveDisplayName): the
        // conversation takes the contact's name, and both sides sync.
        manager.renameDmForContact(addr, cleanName)
        toast("Contact added", com.pombo.android.ui.ToastKind.SUCCESS)
        sliceTouched("trustedContacts")
    }

    fun removeContact(address: String) {
        val next = _contacts.value.filterNot { it.address.equals(address, ignoreCase = true) }
        _contacts.value = next
        contactsStore.save(next)
        // The DM room falls back to ENS/short address once the contact is gone.
        manager.renameDmForContact(address, null)
        toast("Contact removed", com.pombo.android.ui.ToastKind.SUCCESS)
        sliceTouched("trustedContacts")
    }

    /**
     * Whether my DM inbox exists. Drives which button the Chats tab shows —
     * "Create DM Inbox" before, "New DM" after (web: DMModalsUI.updateVisibility).
     * Never shown to guests, who have no persistent identity.
     */
    private val _hasDmInbox = MutableStateFlow(false)
    val hasDmInbox: StateFlow<Boolean> = _hasDmInbox.asStateFlow()

    fun refreshDmInbox() {
        viewModelScope.launch {
            _hasDmInbox.value = if (_isGuest.value) false else manager.hasInbox()
        }
    }

    /**
     * Creates my DM inbox on-chain (needs POL) so others can DM me — with the
     * web's step progress (DMModalsUI.handleCreateInbox: Creating Inbox →
     * Setting Permissions → Setting Storage). The web counts 6 steps because
     * addToStorageNode and setStorageDayCount are separate there; our bridge
     * does both in one call, so it is 5 here.
     */
    fun setupDmInbox(
        storageProvider: String = "streamr",
        customStorageAddress: String? = null,
        storageDays: Int = 180
    ) = viewModelScope.launch {
        if (!canAffordOrWarn("dmInbox")) return@launch
        chainAction(
            "Create DM inbox",
            "Creates your inbox streams, permissions and storage (5 transactions)."
        ) {
        val totalSteps = 5
        val id = toast(
            "Creating DM inbox...", com.pombo.android.ui.ToastKind.LOADING, Long.MAX_VALUE,
            subtitle = "This may take a minute",
            progress = com.pombo.android.ui.ToastProgress(0, totalSteps, "Creating Inbox...")
        )
        var step = 0
        _busy.value = true
        try {
            manager.setupDmInbox(storageProvider, customStorageAddress, storageDays) {
                step += 1
                val label = when {
                    step <= 2 -> "Creating Inbox..."
                    step <= 4 -> "Setting Permissions..."
                    else -> "Setting Storage..."
                }
                setToastProgress(id, step, label)
            }
            _hasDmInbox.value = true
            dismissToast(id)
            toast("DM inbox created!", com.pombo.android.ui.ToastKind.SUCCESS)
        } catch (e: Exception) {
            dismissToast(id)
            toast("Failed to create DM inbox: ${e.message ?: "unknown error"}", com.pombo.android.ui.ToastKind.ERROR, 5000L)
        } finally {
            _busy.value = false
        }
        }
    }

    /**
     * DM rename is local-only (web saveChannelName's isDM branch): no chain
     * write, no gas — the new name reaches other devices via the channels
     * slice of sync.
     */
    fun renameDm(channel: Channel, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            toast("Name cannot be empty", com.pombo.android.ui.ToastKind.ERROR)
            return
        }
        channel.peerAddress?.let { manager.renameDmForContact(it, clean) }
        toast("Name updated", com.pombo.android.ui.ToastKind.SUCCESS)
    }

    // ==================== DM Inbox panel (web settings-panel-repair) ====================

    sealed interface InboxHealth {
        data object Idle : InboxHealth
        data object Loading : InboxHealth
        data object NoInbox : InboxHealth
        data class Healthy(val detail: String) : InboxHealth
        data class Issues(val summary: String, val diagnosis: JSONObject) : InboxHealth
        data class Failed(val message: String) : InboxHealth
        data class Repairing(val step: String) : InboxHealth
        data class Repaired(val detail: String) : InboxHealth
    }

    data class InboxStorage(val nodes: List<String>, val storageDays: Int?)

    private val _inboxHealth = MutableStateFlow<InboxHealth>(InboxHealth.Idle)
    val inboxHealth: StateFlow<InboxHealth> = _inboxHealth.asStateFlow()
    private val _inboxStorage = MutableStateFlow<InboxStorage?>(null)
    val inboxStorage: StateFlow<InboxStorage?> = _inboxStorage.asStateFlow()

    /**
     * Storage info without a full diagnosis — the storage section shows as
     * soon as the panel opens, like the channel storage panel does.
     */
    fun loadInboxStorage() = viewModelScope.launch {
        try {
            if (!manager.hasInbox()) {
                _inboxStorage.value = null
                _inboxHealth.value = InboxHealth.NoInbox
                return@launch
            }
            val inbox = myInboxStreamId() ?: return@launch
            val info = bridge.call("getStorageInfo", JSONObject().put("streamId", inbox), 60_000)
            _inboxStorage.value = InboxStorage(
                nodes = info.optJSONArray("nodes")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }
                } ?: emptyList(),
                storageDays = if (info.isNull("storageDays")) null else info.optInt("storageDays")
            )
        } catch (e: Exception) {
            // Section stays empty; a Diagnose can still populate it.
        }
    }

    /** Web runDiagnosis: read-only (no gas), safe to repeat. */
    fun diagnoseInbox() = viewModelScope.launch {
        _inboxHealth.value = InboxHealth.Loading
        try {
            if (!manager.hasInbox()) {
                _inboxHealth.value = InboxHealth.NoInbox
                _inboxStorage.value = null
                return@launch
            }
            val report = bridge.call("diagnoseDmInbox", JSONObject(), 120_000)
            val msg = report.getJSONObject("messageStream")
            val eph = report.getJSONObject("ephemeralStream")
            val perms = report.getJSONObject("permissions")
            val storage = report.getJSONObject("storage")

            _inboxStorage.value = InboxStorage(
                nodes = storage.optJSONArray("nodes")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }
                } ?: emptyList(),
                storageDays = if (storage.isNull("storageDays")) null else storage.optInt("storageDays")
            )

            // Same issue list and order as the web's runDiagnosis.
            val issues = buildList {
                if (!msg.optBoolean("exists")) add("message stream")
                if (!eph.optBoolean("exists")) add("ephemeral stream")
                if (msg.optBoolean("exists") && !msg.optBoolean("partitionsCorrect")) add("partition count")
                if (msg.optBoolean("exists") && !msg.optBoolean("publicKeyPresent")) add("public key")
                if (!perms.optBoolean("messagePublicPublish")) add("message permissions")
                if (!perms.optBoolean("ephemeralPublicPublish")) add("ephemeral permissions")
                if (!storage.optBoolean("enabled")) add("storage")
            }
            _inboxHealth.value = if (issues.isNotEmpty()) {
                val summary = if (issues.size == 1) "Missing: ${issues[0]}"
                else "${issues.size} issues: ${issues.joinToString(", ")}"
                InboxHealth.Issues(summary, report)
            } else {
                val parts = buildList {
                    storage.optString("provider").ifEmpty { null }?.let { add(it) }
                    if (!storage.isNull("storageDays")) add("${storage.optInt("storageDays")}d")
                }
                InboxHealth.Healthy(
                    if (parts.isNotEmpty()) "Storage: ${parts.joinToString(" · ")}" else "All checks passed"
                )
            }
        } catch (e: Exception) {
            _inboxHealth.value = InboxHealth.Failed(e.message ?: "unknown error")
        }
    }

    /** Web runRepair: fixes only what the last diagnosis flagged (needs gas). */
    fun repairInbox() = viewModelScope.launch {
        val diagnosis = (_inboxHealth.value as? InboxHealth.Issues)?.diagnosis ?: return@launch
        if (!canAffordOrWarn("dmInbox")) return@launch
        chainAction(
            "Repair DM inbox",
            "Recreates whatever the diagnosis flagged — streams, permissions or storage."
        ) {
        _inboxHealth.value = InboxHealth.Repairing("Starting...")
        try {
            val pk = com.pombo.android.core.EthereumSigner.compressedPublicKey(
                store.privateKey ?: throw IllegalStateException("No identity")
            )
            _inboxHealth.value = InboxHealth.Repairing("Fixing inbox — this may require gas")
            val res = bridge.call(
                "repairDmInbox",
                JSONObject().put("diagnosis", diagnosis).put("publicKey", pk),
                300_000
            )
            val steps = res.getJSONObject("steps")
            var fixed = 0
            var failed = 0
            steps.keys().forEach { key ->
                when (steps.optString(key)) {
                    "ok" -> fixed++
                    "fail" -> failed++
                }
            }
            if (failed > 0) {
                _inboxHealth.value = InboxHealth.Issues("$fixed fixed, $failed failed", diagnosis)
                toast("Inbox repair: $fixed fixed, $failed failed", com.pombo.android.ui.ToastKind.WARNING)
            } else {
                _inboxHealth.value = InboxHealth.Repaired(
                    if (fixed > 0) "$fixed issue${if (fixed > 1) "s" else ""} fixed" else "Nothing to repair"
                )
                toast("Inbox repaired successfully", com.pombo.android.ui.ToastKind.SUCCESS)
            }
            refreshDmInbox()
        } catch (e: Exception) {
            _inboxHealth.value = InboxHealth.Failed(e.message ?: "unknown error")
            toast("Inbox repair failed: ${e.message}", com.pombo.android.ui.ToastKind.ERROR, 5000L)
        }
        }
    }

    /** Retention save (web inbox-storage-retention-save) — on-chain, costs gas. */
    fun setInboxRetention(days: Int) = viewModelScope.launch {
        val inbox = myInboxStreamId() ?: return@launch
        chainAction("Change inbox retention", "Sets DM history retention to $days days (1 transaction).") {
            runWithToast("Updating retention…", "Retention updated", "Failed to update retention") {
                bridge.call(
                    "setStorageDays",
                    JSONObject().put("streamId", inbox).put("storageDays", days),
                    120_000
                )
            }
        }
        diagnoseInbox()
    }

    /** Add a storage node to the inbox (web inbox-storage-add-confirm). */
    fun addInboxStorageNode(provider: String, customAddress: String?, days: Int) = viewModelScope.launch {
        val inbox = myInboxStreamId() ?: return@launch
        val node = if (provider == "custom" && !customAddress.isNullOrBlank())
            customAddress.trim() else com.pombo.android.ChannelManager.STORAGE_NODE
        chainAction("Add inbox storage node", "Assigns a storage node to your DM inbox (1 transaction).") {
            runWithToast("Adding storage node…", "Storage node added", "Failed to add storage node") {
                bridge.call(
                    "addToStorageNode",
                    JSONObject().put("streamId", inbox).put("nodeAddress", node).put("storageDays", days),
                    120_000
                )
            }
        }
        diagnoseInbox()
    }

    /** Remove a storage node from the inbox (web inbox-storage-remove-btn). */
    fun removeInboxStorageNode(nodeAddress: String) = viewModelScope.launch {
        val inbox = myInboxStreamId() ?: return@launch
        chainAction(
            "Remove inbox storage node",
            "Stops retaining your DM history on that node (1 transaction)."
        ) {
            runWithToast("Removing storage node…", "Storage node removed", "Failed to remove storage node") {
                bridge.call(
                    "removeStorageNode",
                    JSONObject().put("streamId", inbox).put("nodeAddress", nodeAddress),
                    120_000
                )
            }
        }
        diagnoseInbox()
    }

    /** Starts (or opens) a DM conversation with a peer address. */
    fun startDm(address: String, localName: String? = null) = viewModelScope.launch {
        runBusy {
            val channel = manager.startDm(address, localName?.trim()?.ifEmpty { null })
            manager.openChannel(channel.messageStreamId)
        }
    }

    fun openChannel(messageStreamId: String, from: ChatOrigin = ChatOrigin.CHATS) {
        _chatOrigin.value = from
        // The scan is opportunistic; opening a channel is not. The bridge runs
        // on the WebView's single JS thread, so background resends still in
        // flight would sit in front of this channel's own history fetch and
        // make the room visibly slower to open.
        cancelActivityScan()
        manager.openChannel(messageStreamId)
    }
    fun closeChannel() = manager.closeCurrent()
    fun removeChannel(messageStreamId: String) {
        val name = channels.value.find { it.messageStreamId == messageStreamId }?.name
        manager.removeChannel(messageStreamId)
        toast(if (name != null) "Left $name" else "Left channel", com.pombo.android.ui.ToastKind.SUCCESS)
    }

    fun sendMessage(text: String, replyTo: com.pombo.android.ReplyRef? = null) = viewModelScope.launch {
        try { manager.sendMessage(text, replyTo) } catch (e: Exception) { _lastError.value = "Failed to send: ${e.message}" }
    }

    /** Reads an image from a content Uri and sends it over the chunked transport. */
    fun sendImage(uri: android.net.Uri) = viewModelScope.launch {
        try {
            val ctx = getApplication<Application>()
            val bytes = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: return@launch
            val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            manager.sendImage(bytes, mime)
        } catch (e: Exception) {
            _lastError.value = "Image send failed: ${e.message}"
        }
    }

    /** Display name, size and mime of a content Uri, off the main thread. */
    private suspend fun documentInfo(uri: android.net.Uri): Triple<String, Long, String>? {
        val resolver = getApplication<Application>().contentResolver
        var name: String? = null
        var size = -1L
        withContext(Dispatchers.IO) {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
            if (size <= 0) {
                size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }
        }
        if (size <= 0) return null
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return Triple(name ?: "file", size, mime)
    }

    /**
     * Shares a document Uri over the P2P swarm (web InputUI routes non-images
     * to mediaController.sendFile; this is that path). Name/size come from the
     * resolver — SAF display names are the only honest name a content Uri has.
     */
    fun sendFile(uri: android.net.Uri) = viewModelScope.launch {
        try {
            val (name, size, mime) = documentInfo(uri)
                ?: run { _lastError.value = "Could not read the file size"; return@launch }
            val resolver = getApplication<Application>().contentResolver
            ensureTransferService()
            manager.sendFile(fileName = name, fileSize = size, mimeType = mime) {
                resolver.openInputStream(uri)
                    ?: throw java.io.IOException("cannot open the selected file")
            }
        } catch (e: Exception) {
            _lastError.value = "File send failed: ${e.message}"
        }
    }

    /** Shares a video Uri over the P2P swarm (web sendVideo → sendFile). */
    fun sendVideo(uri: android.net.Uri) = viewModelScope.launch {
        try {
            val (name, size, mime) = documentInfo(uri)
                ?: run { _lastError.value = "Could not read the file size"; return@launch }
            val resolver = getApplication<Application>().contentResolver
            ensureTransferService()
            manager.sendVideo(fileName = name, fileSize = size, mimeType = mime) {
                resolver.openInputStream(uri)
                    ?: throw java.io.IOException("cannot open the selected file")
            }
        } catch (e: Exception) {
            _lastError.value = "Video send failed: ${e.message}"
        }
    }

    /**
     * Shares a document Uri over the storage nodes (Persistent File Sharing —
     * web "Storage Cluster"). Unlike the mesh path this needs random access to
     * the source (chunk i = bytes i*upc..), so the slicer opens a SEEKABLE file
     * descriptor; compression reads it sequentially via [openStream].
     */
    fun sendStorageFile(uri: android.net.Uri) = viewModelScope.launch {
        try {
            val (name, size, mime) = documentInfo(uri)
                ?: run { _lastError.value = "Could not read the file size"; return@launch }
            val resolver = getApplication<Application>().contentResolver
            val fName = name; val fMime = mime; val fSize = size
            val source = object : com.pombo.android.core.StorageMedia.Source {
                override val fileName = fName
                override val fileType = fMime
                override val size = fSize
                override fun openStream() = resolver.openInputStream(uri)
                    ?: throw java.io.IOException("cannot open the selected file")
                override fun openSlicer(): com.pombo.android.core.StorageMedia.Source.Slicer {
                    val pfd = resolver.openFileDescriptor(uri, "r")
                        ?: throw java.io.IOException("cannot open the selected file for reading")
                    return object : com.pombo.android.core.StorageMedia.Source.Slicer {
                        private val fis = java.io.FileInputStream(pfd.fileDescriptor)
                        private val ch = fis.channel
                        override fun readAt(offset: Long, length: Int): ByteArray {
                            val buf = java.nio.ByteBuffer.allocate(length)
                            var pos = offset
                            while (buf.hasRemaining()) {
                                val n = ch.read(buf, pos)
                                if (n < 0) break
                                pos += n
                            }
                            return if (buf.position() == length) buf.array() else buf.array().copyOf(buf.position())
                        }
                        override fun close() {
                            runCatching { ch.close() }; runCatching { fis.close() }; runCatching { pfd.close() }
                        }
                    }
                }
            }
            ensureTransferService()
            manager.sendStorageFile(source)
        } catch (e: Exception) {
            _lastError.value = "Storage share failed: ${e.message}"
        }
    }

    fun toggleReaction(messageId: String, emoji: String, add: Boolean) = viewModelScope.launch {
        // Web wording: a raw exception string means nothing to the user.
        try { manager.sendReaction(messageId, emoji, add) } catch (e: Exception) {
            _lastError.value = "Failed to send reaction"
        }
    }

    fun editMessage(id: String, text: String) = viewModelScope.launch {
        try { manager.editMessage(id, text) } catch (e: Exception) { _lastError.value = e.message }
    }

    fun deleteMessage(id: String) = viewModelScope.launch {
        try { manager.deleteMessage(id) } catch (e: Exception) { _lastError.value = e.message }
    }

    fun notifyTyping() = manager.sendTyping()

    fun clearError() { _lastError.value = null }

    // ==================== bridge callbacks ====================

    override fun onBridgeStatus(status: String) {
        when (status) {
            "no-identity" -> {
                _status.value = NetStatus.NO_IDENTITY
                // Bridge is ready but has no key: start a guest session (web parity).
                if (!store.hasWallet && !guestStarted) {
                    guestStarted = true
                    startGuestSession()
                }
            }
            "connecting" -> _status.value = NetStatus.CONNECTING
        }
    }

    override fun onBridgeConnected(address: String) {
        _status.value = NetStatus.CONNECTED
        // Immediate: the only one of this group with something on screen
        // waiting for it (an open channel resubscribing) — null on a cold
        // start, so this is a no-op exactly when Explore owns the screen.
        manager.resubscribeCurrent()
        // The rest is bookkeeping nothing on screen is blocked on. On a cold
        // start Explore's own channel-image resends are queued on this same
        // bridge-connect gate (they awaitConnected() too) — firing inbox
        // replay, activity scan and the inbox check in the same tick used to
        // have them all compete for the bridge with what the user is actually
        // looking at. A short stagger lets Explore's burst dispatch first.
        viewModelScope.launch {
            kotlinx.coroutines.delay(BRIDGE_CONNECT_BACKGROUND_DELAY_MS)
            // force: a (re)connect rebuilds the bridge's JS world, so whatever
            // the previous session subscribed no longer exists there. This is
            // the one place the inbox replay is supposed to run.
            manager.subscribeMyInbox(force = true)
            // hasInbox() needs a live client, so the check only means anything
            // once the bridge is connected — re-run it here, not just on
            // identity change.
            refreshDmInbox()
            // Catch up on what happened in the other channels while we were away.
            scanChannelsActivity()
        }
        // Push relay housekeeping: republish rows after an FCM token rotation
        // (they hold a dead token until then) and at the web's 6h cadence.
        viewModelScope.launch { push.refreshRegistrationsIfDue() }
        // Cross-device state (channels/contacts/blocks/username changed on the
        // web) — without this pull the sync button was the only way it ever
        // arrived. Deferred a few seconds so the first paint, the inbox replay
        // and the activity scan own the connection first; forced because a
        // connect is rare and is exactly when local state is stalest.
        // "Not on start" opts out of exactly this run — the connect one. The
        // foreground tick and the post-change push stay on, and they catch up
        // within minutes. ("Manual only" is caught inside autoSync, which
        // covers every unprompted path at once.)
        autoSyncKickJob?.cancel()
        if (settingsStore.syncMode != com.pombo.android.data.SyncMode.NOT_ON_START) {
            autoSyncKickJob = viewModelScope.launch {
                kotlinx.coroutines.delay(AUTO_SYNC_CONNECT_DELAY_MS)
                sync.autoSync(force = true)
            }
        }
    }

    override fun onBridgeMessage(streamId: String, partition: Int, contentJson: String, metaJson: String) {
        manager.onIncoming(streamId, partition, contentJson, metaJson)
    }

    override fun onBridgeBinary(streamId: String, partition: Int, data: ByteArray, metaJson: String) {
        manager.onIncomingBinary(streamId, partition, data, metaJson)
    }

    override fun onBridgeError(message: String) {
        _status.value = NetStatus.ERROR
        _lastError.value = message
    }

    // ==================== helpers ====================

    private suspend fun runBusy(block: suspend () -> Unit) {
        _busy.value = true
        try {
            block()
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Unexpected error"
        } finally {
            _busy.value = false
        }
    }

    /**
     * Wraps a user action that signs Polygon transactions: fingerprint (or
     * device credential) first, with a warning naming the action, then the work
     * with the approval held open so the action's several transactions and
     * their retries do not each ask again.
     *
     * Refusal returns quietly — no exception, just an early return.
     */
    private suspend fun chainAction(action: String, detail: String, block: suspend () -> Unit) {
        if (!com.pombo.android.ui.ChainGuard.approve(action, detail)) {
            toast("Cancelled — not confirmed", com.pombo.android.ui.ToastKind.WARNING)
            return
        }
        com.pombo.android.ui.ChainGuard.holding(block)
    }

    /**
     * runWithToast for JOIN flows (N-D): a [ChannelManager.GateAccessDenied]
     * is not a failure — it opens the gate entry screen with a retry that
     * re-runs [block] (a repeat denial re-opens the screen with fresh state).
     */
    private suspend fun runJoinToast(
        loading: String,
        success: String?,
        failure: String,
        channelName: String? = null,
        block: suspend () -> Unit
    ): Boolean {
        val id = toast(loading, com.pombo.android.ui.ToastKind.LOADING, Long.MAX_VALUE)
        _busy.value = true
        return try {
            block()
            dismissToast(id)
            success?.let { toast(it, com.pombo.android.ui.ToastKind.SUCCESS) }
            true
        } catch (e: ChannelManager.GateAccessDenied) {
            dismissToast(id)
            openGateEntry(e.gateAddress, channelName) {
                runJoinToast(loading, success, failure, channelName, block)
            }
            false
        } catch (e: Exception) {
            dismissToast(id)
            toast(
                "$failure: ${com.pombo.android.core.ChainErrors.friendly(e)}",
                com.pombo.android.ui.ToastKind.ERROR, 5000L
            )
            false
        } finally {
            _busy.value = false
        }
    }

    /** Returns whether the block succeeded, so callers can keep a form open on failure. */
    private suspend fun runWithToast(
        loading: String,
        success: String?,
        failure: String,
        /** Hands the loading toast's id to flows that update it mid-run. */
        onToastId: ((Long) -> Unit)? = null,
        block: suspend () -> Unit
    ): Boolean {
        val id = toast(loading, com.pombo.android.ui.ToastKind.LOADING, Long.MAX_VALUE)
        onToastId?.invoke(id)
        _busy.value = true
        return try {
            block()
            dismissToast(id)
            success?.let { toast(it, com.pombo.android.ui.ToastKind.SUCCESS) }
            true
        } catch (e: Exception) {
            dismissToast(id)
            // Chain failures arrive as raw ethers strings; map the known ones
            // to the web's friendly texts (utils/chainErrors.js). Anything
            // unrecognised passes through untouched, so non-chain flows keep
            // their original messages.
            toast(
                "$failure: ${com.pombo.android.core.ChainErrors.friendly(e)}",
                com.pombo.android.ui.ToastKind.ERROR, 5000L
            )
            false
        } finally {
            _busy.value = false
        }
    }

    override fun onCleared() {
        bridge.destroy()
    }
}

package com.pombo.android.core

import android.util.Log
import com.pombo.android.bridge.PomboBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * P2P file transfer: the pull-based engine and the lifecycle of the media
 * partitions it needs.
 *
 * Pull-based, like the web. A leecher asks a NAMED seeder for a SPECIFIC piece
 * and the seeder answers; nobody pushes. That is what makes the number of
 * outstanding requests the flow control — the request is the credit — and what
 * lets a swarm work without any coordinator: every peer that finishes becomes a
 * seeder, and the file outlives the original sender.
 *
 * Ownership: this class holds transfer state and drives requests, but never
 * seals a payload itself. Everything it sends goes out through [Transport],
 * implemented by ChannelManager, so the one place that decides how a channel's
 * payload is encrypted stays the one place.
 */
class MediaController(
    private val bridge: PomboBridge,
    private val scope: CoroutineScope,
    /** Own address — a DM transfer's partitions live on OUR inbox, not the peer's. */
    private val myAddress: () -> String?,
    /** Where partial downloads live. Must be under filesDir, never cacheDir. */
    private val transferDir: () -> File,
    private val transport: Transport,
    /**
     * Gate clone address when the stream belongs to a GATED channel, else
     * null. Media subscribes need it: access is proved via the gate's
     * ERC-1271 (no per-member grant exists), and the bridge must recover the
     * envelope signer so piece authorship survives the shared clone publisher.
     */
    private val gateAddressFor: (String) -> String? = { null }
) {

    private companion object {
        const val TAG = "PomboMedia"

        /**
         * Whole-transfer watchdog. Replaces the per-piece failure cap as the
         * way a stuck download gives up: what matters is whether ANYTHING is
         * still arriving, not how often one particular piece was re-asked.
         */
        const val STALL_TIMEOUT_MS = 120_000L
    }

    /**
     * How this controller reaches the network. Deliberately narrow: it can send
     * a coordination signal and a piece, and nothing else.
     */
    interface Transport {
        /** JSON onto -2/P1, sealed by the caller's channel rules. */
        suspend fun publishMediaSignal(
            ephemeralStreamId: String,
            payload: JSONObject,
            password: String?,
            isDm: Boolean
        )

        /** Raw bytes onto -2/P2. Password channels are encrypted in the bridge. */
        suspend fun publishMediaPiece(
            ephemeralStreamId: String,
            bytes: ByteArray,
            password: String?,
            isDm: Boolean
        )
    }

    // ==================== metadata ====================

    /** The file_announce payload, as the web writes it. */
    data class FileMetadata(
        val fileId: String,
        val fileName: String,
        val fileSize: Long,
        val fileType: String,
        val pieceCount: Int,
        val pieceHashes: List<String>
    ) {
        companion object {
            fun from(json: JSONObject?): FileMetadata? {
                if (json == null) return null
                val fileId = json.optString("fileId").ifEmpty { return null }
                val fileSize = json.optLong("fileSize", 0L)
                if (fileSize <= 0) return null
                val hashesArray = json.optJSONArray("pieceHashes") ?: JSONArray()
                val hashes = (0 until hashesArray.length()).map { hashesArray.optString(it) }
                val pieceCount = json.optInt("pieceCount", hashes.size)
                // Piece hashes are what makes a transfer verifiable at all. An
                // announcement without one per piece cannot be checked, and
                // accepting it would mean writing unverified bytes to disk.
                if (pieceCount <= 0 || hashes.size != pieceCount) return null
                if (pieceCount != MediaConfig.pieceCount(fileSize)) return null
                return FileMetadata(
                    fileId = fileId,
                    fileName = json.optString("fileName").ifEmpty { fileId },
                    fileSize = fileSize,
                    fileType = json.optString("fileType"),
                    pieceCount = pieceCount,
                    pieceHashes = hashes
                )
            }
        }
    }

    /** What the UI needs to draw a transfer. */
    data class Progress(
        val fileId: String,
        val fileName: String,
        val received: Long,
        val total: Long,
        val bytesPerSecond: Long?,
        val seeders: Int,
        val done: Boolean,
        val failure: String? = null
    )

    // ==================== state ====================

    private inner class Transfer(
        val metadata: FileMetadata,
        val messageStreamId: String,
        val ephemeralStreamId: String,
        val isDm: Boolean,
        val password: String?,
        val store: PieceStore
    ) {
        val seeders = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        val inFlight = ConcurrentHashMap<Int, Job>()

        /** AIMD request window — the flow control. See [RequestWindow]. */
        val window = RequestWindow()

        /** Measured retransmission timeout. See [PieceTimeout]. */
        val timeout = PieceTimeout()

        /** pieceIndex -> when it was asked for, to feed the RTT estimator. */
        val requestedAt = ConcurrentHashMap<Int, Long>()

        /** Last time ANY piece landed — the transfer-level stall detector. */
        @Volatile var lastPieceAt = System.currentTimeMillis()

        /** Per-piece delivery failures; caps retry loops. */
        val failures = ConcurrentHashMap<Int, Int>()

        /** Seeders that already delivered a bad copy of a given piece. */
        val exclusions = ConcurrentHashMap<Int, MutableSet<String>>()

        /** Bad pieces per seeder; at MAX_SEEDER_STRIKES the peer is dropped. */
        val strikes = ConcurrentHashMap<String, Int>()

        /**
         * Round-robins seeder choice ACROSS retries, not just across pieces.
         * A per-call random or a fixed first-seeder both send every retry back
         * to the peer that just failed.
         */
        val cursor = AtomicInteger(0)

        /** (timestampMs, cumulative bytes) for the sliding rate window. */
        val samples = ArrayDeque<Pair<Long, Long>>()

        @Volatile var discovery: Job? = null
        @Volatile var failed: String? = null
    }

    private val incoming = ConcurrentHashMap<String, Transfer>()
    private val seeding = ConcurrentHashMap<String, SeedFile>()

    /** Which files this device can serve, durable across restarts. */
    private val registry = SeedRegistry(transferDir)

    /** When THIS device last answered a piece request — feeds [hasBackgroundWork]. */
    @Volatile private var lastServedAt = 0L

    init {
        // Decay tick for the serving stats: pieces stopping is exactly when
        // nothing else re-emits, so without this the displayed upload rate
        // would freeze at its last value instead of falling to zero.
        scope.launch {
            while (true) {
                delay(1_000)
                if (uploads.isNotEmpty()) emitUploadStats(force = true)
            }
        }
    }

    /**
     * Whether backgrounding the app would interrupt real transfer work: any
     * download in flight, or a piece served within the grace period (an idle
     * seed does not count — holding a foreground service forever because a
     * file MIGHT be asked for would be a permanent notification and a battery
     * bill for nothing).
     */
    fun hasBackgroundWork(uploadGraceMs: Long = 120_000L): Boolean =
        incoming.isNotEmpty() ||
            (lastServedAt != 0L && System.currentTimeMillis() - lastServedAt < uploadGraceMs)

    /** A completed or locally-owned file this device serves to others. */
    data class SeedFile(
        val fileId: String,
        val messageStreamId: String,
        val ephemeralStreamId: String,
        val isDm: Boolean,
        val password: String?,
        val store: PieceStore,
        val pieceCount: Int,
        /** For the Active Transfers list — the engine never needs it. */
        val fileName: String = ""
    )

    /** One row of the Active Transfers list: a file this device serves. */
    data class SeedInfo(
        val fileId: String,
        val fileName: String,
        val fileSize: Long,
        val messageStreamId: String,
        val isDm: Boolean
    )

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /** Observable per-file progress, keyed by fileId. */
    val progress: StateFlow<Map<String, Progress>> = _progress

    /** What the seeder side of a bubble shows (web upload stats beside the size). */
    data class UploadStats(val bytesPerSecond: Long?, val leechers: Int)

    private class UploadState {
        /** (timestampMs, cumulative bytes served) for the sliding rate window. */
        val samples = ArrayDeque<Pair<Long, Long>>()
        var totalBytes = 0L
        /** requester address -> last piece_request time; stale ones age out. */
        val leechers = HashMap<String, Long>()
    }

    private val uploads = ConcurrentHashMap<String, UploadState>()
    @Volatile private var lastUploadEmit = 0L
    private val _uploadStats = MutableStateFlow<Map<String, UploadStats>>(emptyMap())

    /** Observable per-file serving stats: upload rate + who is pulling from us. */
    val uploadStats: StateFlow<Map<String, UploadStats>> = _uploadStats

    /** Whether this device holds [fileId] complete and can serve it. */
    fun isSeedingFile(fileId: String): Boolean = seeding.containsKey(fileId)

    private val _seeds = MutableStateFlow<List<SeedInfo>>(emptyList())

    /** Observable list of everything this device is serving. */
    val seeds: StateFlow<List<SeedInfo>> = _seeds

    private fun emitSeeds() {
        _seeds.value = seeding.values.map {
            SeedInfo(it.fileId, it.fileName, it.store.fileSize, it.messageStreamId, it.isDm)
        }
    }

    /**
     * Cancels a running download: stops the requests, closes the store and
     * deletes the partial bytes (web cancelDownload clears its pieces too — a
     * cancel is "I do not want this", not "pause"). Returns the transfer's
     * stream and DM flag so the caller can decide whether the media
     * partitions are still needed by anything else.
     */
    fun cancelDownload(fileId: String): Pair<String, Boolean>? {
        val transfer = incoming.remove(fileId) ?: return null
        transfer.discovery?.cancel()
        transfer.inFlight.values.forEach { it.cancel() }
        transfer.inFlight.clear()
        transfer.store.close()
        PieceStore.delete(transferDir(), fileId)
        _progress.value = _progress.value - fileId
        Log.i(TAG, "download cancelled $fileId")
        return transfer.messageStreamId to transfer.isDm
    }

    /**
     * Stops serving a file and forgets it durably: registry entry and piece
     * files go with it, so it does not come back on the next channel open.
     * Returns the seed's stream and DM flag for the same reason as
     * [cancelDownload].
     */
    fun stopSeeding(fileId: String): Pair<String, Boolean>? {
        val seed = seeding.remove(fileId) ?: return null
        seed.store.close()
        registry.remove(fileId)
        PieceStore.delete(transferDir(), fileId)
        _progress.value = _progress.value - fileId
        emitSeeds()
        Log.i(TAG, "stopped seeding $fileId (${seed.fileName})")
        return seed.messageStreamId to seed.isDm
    }

    private fun recordUpload(fileId: String, requester: String?, bytes: Int) {
        val state = uploads.getOrPut(fileId) { UploadState() }
        synchronized(state) {
            val now = System.currentTimeMillis()
            if (requester != null) state.leechers[requester] = now
            state.totalBytes += bytes
            state.samples.addLast(now to state.totalBytes)
        }
        emitUploadStats()
    }

    /**
     * Publishes the serving stats, throttled like the web (a piece can leave
     * every few milliseconds and repainting the bubble that fast reads as
     * flicker). [force] is the decay tick: with no pieces flowing nothing
     * would re-emit, and the displayed rate would freeze at its last value
     * instead of falling to zero.
     */
    private fun emitUploadStats(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUploadEmit < MediaConfig.UPLOAD_STATS_INTERVAL_MS) return
        lastUploadEmit = now
        val out = HashMap<String, UploadStats>()
        val iterator = uploads.entries.iterator()
        while (iterator.hasNext()) {
            val (fileId, state) = iterator.next()
            synchronized(state) {
                state.leechers.entries.removeAll { now - it.value > MediaConfig.LEECHER_TIMEOUT_MS }
                while (state.samples.isNotEmpty() &&
                    now - state.samples.first().first > MediaConfig.SPEED_WINDOW_MS
                ) {
                    state.samples.removeFirst()
                }
                val rate = if (state.samples.size >= 2) {
                    val (t0, b0) = state.samples.first()
                    val (t1, b1) = state.samples.last()
                    if (t1 > t0) (b1 - b0) * 1000 / (t1 - t0) else null
                } else null
                if (rate == null && state.leechers.isEmpty()) {
                    iterator.remove()
                } else {
                    out[fileId] = UploadStats(rate, state.leechers.size)
                }
            }
        }
        _uploadStats.value = out
    }

    // ==================== teardown vetoes ====================

    fun hasActiveTransfers(messageStreamId: String): Boolean =
        incoming.values.any { it.messageStreamId == messageStreamId } ||
            seeding.values.any { it.messageStreamId == messageStreamId }

    /**
     * Whether ANY DM conversation still has a transfer.
     *
     * Every DM shares one ephemeral stream — the one derived from our own
     * address — so this cannot take a stream id the way [hasActiveTransfers]
     * does. Asking "is *this* conversation idle?" would tear down the shared
     * stream and cut seeding for every other conversation at once.
     */
    fun hasActiveDMTransfers(): Boolean =
        incoming.values.any { it.isDm } || seeding.values.any { it.isDm }

    /** The single DM ephemeral stream every conversation shares — ours, not the peer's. */
    fun myDmEphemeralId(): String? = myAddress()?.lowercase()?.let { "$it/Pombo-DM-2" }

    /**
     * Every ephemeral stream that must stay subscribed for the transfers
     * currently tracked, mapped to its channel password. Used after a bridge
     * reconnect, which rebuilds the JS world and takes every subscription with
     * it — including ones in channels the user is not even looking at.
     */
    fun activeEphemeralStreams(): Map<String, String?> {
        val out = HashMap<String, String?>()
        for (t in incoming.values) out[t.ephemeralStreamId] = t.password
        for (s in seeding.values) out[s.ephemeralStreamId] = s.password
        // A DM transfer's stored ephemeral is the PEER's inbox (where we
        // publish); what we RECEIVE on is our own — without this line a bridge
        // reconnect would rebuild every subscription except the one the pieces
        // actually arrive through.
        if (incoming.values.any { it.isDm } || seeding.values.any { it.isDm }) {
            myDmEphemeralId()?.let { out[it] = null }
        }
        return out
    }

    // ==================== partition lifecycle ====================

    /**
     * Brings up -2/P1 and -2/P2, if they are not already up.
     *
     * No local registry of open partitions: the bridge already dedupes
     * (`subscribe` returns `already` for a stream+partition it holds), so a
     * registry here would be a second copy of that truth, free to drift.
     *
     * Called at the same moments as the web's `ensureMediaSubscription`: when a
     * download starts, when a file is offered (the sender needs P1 to hear
     * piece requests), and when re-announcing seeds on channel open.
     *
     * @param password handed to the bridge so it can unwrap incoming pieces
     *   where the bytes already are — see the PERFORMANCE NOTE beside
     *   pwDeriveKey in pombo_bridge.html.
     */
    suspend fun ensureMediaPartitions(ephemeralStreamId: String, password: String? = null) {
        subscribe(ephemeralStreamId, StreamConstants.EPH_MEDIA_SIGNALS, password)
        subscribe(ephemeralStreamId, StreamConstants.EPH_MEDIA_DATA, password)
        Log.i(TAG, "media partitions up: $ephemeralStreamId")
    }

    /**
     * Drops -2/P1 and -2/P2. The caller checks first whether anything is still
     * transferring — this does not second-guess it, because the DM case needs a
     * different question than the channel case.
     */
    suspend fun releaseMediaPartitions(ephemeralStreamId: String) {
        unsubscribe(ephemeralStreamId, StreamConstants.EPH_MEDIA_SIGNALS)
        unsubscribe(ephemeralStreamId, StreamConstants.EPH_MEDIA_DATA)
        Log.i(TAG, "media partitions down: $ephemeralStreamId")
    }

    private suspend fun subscribe(streamId: String, partition: Int, password: String?) {
        try {
            val args = JSONObject().put("streamId", streamId).put("partition", partition)
            if (password != null) args.put("password", password)
            // Gated: prove access via the gate contract and recover the
            // envelope signer — piece authorship on a shared clone publisher.
            gateAddressFor(streamId)?.let {
                args.put("erc1271Contract", it).put("recoverSigner", true)
            }
            bridge.call("subscribe", args)
        } catch (e: Exception) {
            Log.w(TAG, "subscribe $streamId#$partition failed: ${e.message}")
        }
    }

    private suspend fun unsubscribe(streamId: String, partition: Int) {
        try {
            bridge.call(
                "unsubscribe",
                JSONObject().put("streamId", streamId).put("partition", partition)
            )
        } catch (e: Exception) {
            Log.w(TAG, "unsubscribe $streamId#$partition failed: ${e.message}")
        }
    }

    // ==================== downloading ====================

    /**
     * Starts (or resumes) a download.
     *
     * Resume is free: [PieceStore] writes each piece at its final offset, so
     * reopening it finds whatever a previous session managed to fetch and the
     * work list is simply whatever is still missing.
     */
    fun startDownload(
        messageStreamId: String,
        metadata: FileMetadata,
        password: String?,
        isDm: Boolean
    ) {
        if (incoming.containsKey(metadata.fileId)) {
            Log.d(TAG, "download already running: ${metadata.fileId}")
            return
        }
        // DM asymmetry: requests go out on the PEER's inbox ephemeral (derived
        // from messageStreamId below, which for a DM IS the peer's inbox), but
        // the pieces come back on OUR OWN inbox — so that one must be
        // subscribed too, which happens in the launch below.
        val ephemeralStreamId = StreamConstants.deriveEphemeralId(messageStreamId)
        val store = try {
            PieceStore.open(transferDir(), metadata.fileId, metadata.fileSize)
        } catch (e: Exception) {
            Log.e(TAG, "cannot open storage for ${metadata.fileId}: ${e.message}")
            publish(
                Progress(
                    metadata.fileId, metadata.fileName, 0, metadata.fileSize,
                    null, 0, done = false, failure = "Cannot open local storage"
                )
            )
            return
        }

        val transfer = Transfer(
            metadata, messageStreamId, ephemeralStreamId, isDm, password, store
        )
        incoming[metadata.fileId] = transfer
        Log.i(
            TAG,
            "download start ${metadata.fileId} ${metadata.fileName} " +
                "${store.completedPieces()}/${metadata.pieceCount} already on disk"
        )
        emitProgress(transfer)

        scope.launch {
            ensureMediaPartitions(ephemeralStreamId, password)
            // DM: the answers arrive on OUR inbox, not the peer's. The bridge
            // recognises our own -2 by address and unwraps the ECDH pieces.
            if (isDm) myDmEphemeralId()?.let { ensureMediaPartitions(it, null) }
            if (store.isComplete()) {
                completeDownload(transfer)
                return@launch
            }
            transfer.discovery = scope.launch { discoverSeeders(transfer) }
        }
    }

    /**
     * Asks who has the file, and keeps asking while the download has no seeder.
     *
     * The repeat matters: a peer that joins the channel after the first request
     * never sees it, so a single shot leaves a download stuck forever whenever
     * the only seeder happened to be offline at the moment it started.
     */
    private suspend fun discoverSeeders(transfer: Transfer) {
        while (incoming.containsKey(transfer.metadata.fileId) && !transfer.store.isComplete()) {
            if (transfer.seeders.isEmpty()) {
                try {
                    transport.publishMediaSignal(
                        transfer.ephemeralStreamId,
                        JSONObject()
                            .put("type", MediaWire.SIGNAL_SOURCE_REQUEST)
                            .put("fileId", transfer.metadata.fileId),
                        transfer.password,
                        transfer.isDm
                    )
                    Log.i(TAG, "source_request sent for ${transfer.metadata.fileId}")
                } catch (e: Exception) {
                    // Logged: an unanswered request looks identical to one that
                    // was never sent, so a silent failure here would be invisible.
                    Log.w(TAG, "source_request failed: ${e.message}")
                }
            }
            delay(MediaConfig.SEEDER_REFRESH_INTERVAL_MS)
        }
    }

    /**
     * Issues piece requests up to the current window.
     *
     * Called after every event that could free capacity — a piece landing, a
     * timeout, a new seeder appearing — so the window stays full without a
     * polling loop.
     */
    private fun pump(transfer: Transfer) {
        if (transfer.failed != null) return
        synchronized(transfer) {
            for (index in transfer.store.missingPieces()) {
                if (!transfer.window.hasRoom(transfer.inFlight.size)) return
                if (transfer.inFlight.containsKey(index)) continue
                val seeder = pickSeeder(transfer, index) ?: continue
                requestPiece(transfer, index, seeder)
            }
        }
    }

    /**
     * Chooses which seeder to ask for [index].
     *
     * The cursor is per TRANSFER and advances on every pick, so a retry moves on
     * to a different peer instead of going straight back to the one that just
     * failed. Seeders excluded for this specific piece are skipped: a peer can
     * be fine for the rest of the file and hold one corrupt piece.
     */
    private fun pickSeeder(transfer: Transfer, index: Int): String? {
        val excluded = transfer.exclusions[index] ?: emptySet<String>()
        val candidates = synchronized(transfer.seeders) {
            transfer.seeders.filterNot { it in excluded }
        }
        if (candidates.isEmpty()) return null
        val position = Math.floorMod(transfer.cursor.getAndIncrement(), candidates.size)
        return candidates[position]
    }

    private fun requestPiece(transfer: Transfer, index: Int, seederId: String) {
        val job = scope.launch {
            transport.publishMediaSignal(
                transfer.ephemeralStreamId,
                JSONObject()
                    .put("type", MediaWire.SIGNAL_PIECE_REQUEST)
                    .put("fileId", transfer.metadata.fileId)
                    .put("pieceIndex", index)
                    .put("targetSeederId", seederId),
                transfer.password,
                transfer.isDm
            )
            delay(transfer.timeout.currentMs())
            // Still in flight after the timeout: the seeder has not answered yet.
            if (transfer.inFlight.remove(index) != null) {
                transfer.requestedAt.remove(index)
                transfer.timeout.onTimeout()
                Log.d(
                    TAG,
                    "piece $index timed out from $seederId " +
                        "(next wait ${transfer.timeout.currentMs()}ms)"
                )
                if (transfer.window.onTimeout()) {
                    Log.d(TAG, "window -> ${transfer.window.size} for ${transfer.metadata.fileId}")
                }
                // A timeout does NOT count toward the give-up cap. That cap is
                // for pieces that arrive CORRUPT, where asking again is likely
                // pointless; a timeout only says the answer has not come yet.
                // Stalls are caught by the whole-transfer watchdog below instead.
                if (System.currentTimeMillis() - transfer.lastPieceAt > STALL_TIMEOUT_MS) {
                    failDownload(transfer, "No pieces received for ${STALL_TIMEOUT_MS / 1000}s")
                    return@launch
                }
                pump(transfer)
            }
        }
        transfer.requestedAt[index] = System.currentTimeMillis()
        transfer.inFlight[index] = job
    }

    /**
     * Records a failed piece delivery.
     *
     * [blameSeeder] separates "you sent me rubbish" from "you sent me nothing".
     * Corrupt bytes are attributable and earn a strike; a timeout may just be a
     * slow link or a peer that went offline, and striking for that would evict
     * healthy seeders on a bad network.
     */
    private fun registerFailure(
        transfer: Transfer,
        index: Int,
        seederId: String?,
        blameSeeder: Boolean
    ) {
        if (seederId != null && blameSeeder) {
            transfer.exclusions.getOrPut(index) { java.util.Collections.synchronizedSet(HashSet()) }
                .add(seederId)
            val strikes = transfer.strikes.merge(seederId, 1, Int::plus) ?: 1
            if (strikes >= MediaConfig.MAX_SEEDER_STRIKES) {
                transfer.seeders.remove(seederId)
                Log.w(TAG, "evicted seeder $seederId after $strikes bad pieces")
            }
        }
        val failures = transfer.failures.merge(index, 1, Int::plus) ?: 1
        if (failures >= MediaConfig.MAX_PIECE_FAILURES) {
            failDownload(transfer, "Piece $index failed $failures times")
        }
    }

    private fun failDownload(transfer: Transfer, reason: String) {
        if (transfer.failed != null) return
        transfer.failed = reason
        Log.e(TAG, "download failed ${transfer.metadata.fileId}: $reason")
        transfer.inFlight.values.forEach { it.cancel() }
        transfer.inFlight.clear()
        transfer.discovery?.cancel()
        incoming.remove(transfer.metadata.fileId)
        // The partial file stays on disk: a retry resumes rather than starting
        // over, and a failure here is usually about seeders, not about bytes.
        transfer.store.close()
        emitProgress(transfer)
    }

    private fun completeDownload(transfer: Transfer) {
        val metadata = transfer.metadata
        transfer.discovery?.cancel()
        transfer.inFlight.values.forEach { it.cancel() }
        transfer.inFlight.clear()
        incoming.remove(metadata.fileId)

        // Keep serving what we just finished. This is what makes the swarm work:
        // the file survives the original sender leaving, because everyone who
        // completed it can answer for it.
        seeding[metadata.fileId] = SeedFile(
            fileId = metadata.fileId,
            messageStreamId = transfer.messageStreamId,
            ephemeralStreamId = transfer.ephemeralStreamId,
            isDm = transfer.isDm,
            password = transfer.password,
            store = transfer.store,
            pieceCount = metadata.pieceCount,
            fileName = metadata.fileName
        )
        emitSeeds()
        registry.record(
            SeedRegistry.Entry(
                fileId = metadata.fileId,
                messageStreamId = transfer.messageStreamId,
                ephemeralStreamId = transfer.ephemeralStreamId,
                isDm = transfer.isDm,
                pieceCount = metadata.pieceCount,
                fileSize = metadata.fileSize,
                fileName = metadata.fileName,
                completedAt = System.currentTimeMillis()
            )
        )
        Log.i(TAG, "download complete ${metadata.fileId} — now seeding")
        emitProgress(transfer, done = true)

        scope.launch {
            transport.publishMediaSignal(
                transfer.ephemeralStreamId,
                JSONObject()
                    .put("type", MediaWire.SIGNAL_SOURCE_ANNOUNCE)
                    .put("fileId", metadata.fileId)
                    .put("pieceCount", metadata.pieceCount),
                transfer.password,
                transfer.isDm
            )
        }
    }

    /**
     * Streams a finished file into [sink]. Separate from completion because the
     * user chooses when and where to save; until then the bytes sit in the
     * transfer directory, already assembled.
     *
     * Deliberately a COPY. Moving the file out would stop this device seeding
     * something it just finished, so the swarm would lose a seeder every time
     * somebody saved — the opposite of what should happen.
     */
    fun exportTo(fileId: String, sink: java.io.OutputStream): Boolean = try {
        val store = seeding[fileId]?.store
        if (store == null) false else { store.exportTo(sink); true }
    } catch (e: Exception) {
        Log.e(TAG, "export failed for $fileId: ${e.message}")
        false
    }

    // ==================== intake ====================

    /**
     * A JSON coordination signal off -2/P1.
     *
     * Runs on the WebView binder thread and is NOT gated on the open channel: a
     * seeder must answer a piece request for a file in a channel the user left
     * minutes ago.
     */
    fun onSignal(ephemeralStreamId: String, content: Any?, publisherId: String?) {
        val signal = content as? JSONObject ?: return
        val fileId = signal.optString("fileId").ifEmpty { return }
        when (signal.optString("type")) {
            MediaWire.SIGNAL_SOURCE_REQUEST -> onSourceRequest(fileId)
            MediaWire.SIGNAL_SOURCE_ANNOUNCE -> onSourceAnnounce(fileId, publisherId)
            MediaWire.SIGNAL_PIECE_REQUEST -> onPieceRequest(
                fileId,
                signal.optInt("pieceIndex", -1),
                signal.optString("targetSeederId").lowercase().ifEmpty { null },
                publisherId
            )
        }
    }

    /** Somebody is looking for a file. Answer only if we can actually serve it. */
    private fun onSourceRequest(fileId: String) {
        val seed = seeding[fileId] ?: return
        scope.launch {
            transport.publishMediaSignal(
                seed.ephemeralStreamId,
                JSONObject()
                    .put("type", MediaWire.SIGNAL_SOURCE_ANNOUNCE)
                    .put("fileId", fileId)
                    .put("pieceCount", seed.pieceCount),
                seed.password,
                seed.isDm
            )
        }
    }

    private fun onSourceAnnounce(fileId: String, publisherId: String?) {
        val transfer = incoming[fileId] ?: return
        val seeder = publisherId ?: return
        // Identity comes from the Streamr publisher id, never from the payload:
        // the transport signs it, so a peer cannot claim to be someone else.
        if (seeder == myAddress()?.lowercase()) return
        if (transfer.seeders.add(seeder)) {
            Log.i(TAG, "seeder $seeder for $fileId (${transfer.seeders.size} known)")
            emitProgress(transfer)
        }
        pump(transfer)
    }

    /**
     * A leecher wants a piece from us.
     *
     * `targetSeederId` is honoured: every subscriber on the partition sees the
     * request, and without this check all of them would answer it at once —
     * the same amplification that pull-based transfer exists to avoid.
     */
    private fun onPieceRequest(fileId: String, index: Int, targetSeederId: String?, requester: String?) {
        if (index < 0) return
        val me = myAddress()?.lowercase()
        if (targetSeederId != null && me != null && targetSeederId != me) return
        val seed = seeding[fileId] ?: return
        val bytes = seed.store.readPiece(index) ?: return
        lastServedAt = System.currentTimeMillis()
        recordUpload(fileId, requester, bytes.size)
        scope.launch {
            try {
                transport.publishMediaPiece(
                    seed.ephemeralStreamId,
                    MediaWire.encodeFilePiece(fileId, index, bytes),
                    seed.password,
                    seed.isDm
                )
            } catch (e: Exception) {
                Log.w(TAG, "failed to send piece $index of $fileId: ${e.message}")
            }
        }
    }

    /**
     * A binary payload off -2/P2 — a file piece.
     *
     * Also on the binder thread, deliberately: verification hashes the piece
     * and the write hits disk, neither of which belongs on the main thread.
     */
    fun onBinary(ephemeralStreamId: String, data: ByteArray, publisherId: String?) {
        val piece = MediaWire.decodeBinaryMedia(data)
        if (piece == null) {
            Log.w(TAG, "undecodable binary (${data.size}B) on $ephemeralStreamId")
            return
        }
        // A piece for a file we are not downloading is normal, not an error: a
        // seeder answers onto the shared partition, so every subscriber sees
        // pieces meant for someone else.
        val transfer = incoming[piece.fileId] ?: return
        transfer.inFlight.remove(piece.pieceIndex)?.cancel()

        // Feed the RTT estimator. Only pieces still marked as requested count:
        // one that already timed out has no honest round-trip to report (we
        // stopped waiting), and folding a late arrival in as a sample would
        // teach the estimator the very delay it is meant to detect.
        transfer.requestedAt.remove(piece.pieceIndex)?.let { at ->
            transfer.timeout.onSample(System.currentTimeMillis() - at)
        }
        transfer.lastPieceAt = System.currentTimeMillis()

        if (transfer.store.hasPiece(piece.pieceIndex)) return

        val expected = transfer.metadata.pieceHashes.getOrNull(piece.pieceIndex)
        val accepted = transfer.store.writePiece(piece.pieceIndex, piece.data, expected)
        if (!accepted) {
            Log.w(TAG, "bad piece ${piece.pieceIndex} of ${piece.fileId} from $publisherId")
            registerFailure(transfer, piece.pieceIndex, publisherId, blameSeeder = true)
            pump(transfer)
            return
        }

        recordThroughput(transfer)
        // Grow only while the path is not already queueing. Growing on every
        // delivery regardless is how the window inflates past what the link can
        // carry: throughput stops improving, round-trips triple, and the only
        // thing that would pull it back — a timeout — has been tuned out of
        // existence by the RTT estimator. Rising delay is the earlier signal.
        if (transfer.timeout.isCongested()) {
            transfer.window.onCongestionSignal()
        } else {
            transfer.window.onDelivered()
        }
        emitProgress(transfer)
        Log.d(
            TAG,
            "piece ${piece.pieceIndex} ok (${transfer.store.completedPieces()}/" +
                "${transfer.metadata.pieceCount}, window ${transfer.window.size}, " +
                "rtt ${transfer.timeout.smoothedRttMs()}ms, rto ${transfer.timeout.currentMs()}ms)"
        )

        if (transfer.store.isComplete()) completeDownload(transfer) else pump(transfer)
    }

    // ==================== seeding our own files ====================

    /**
     * Registers a file this device already holds in full, so it can serve it.
     * Used both for a file we are sending and for one restored from disk.
     */
    fun trackSeed(seed: SeedFile) {
        seeding[seed.fileId] = seed
        emitSeeds()
    }

    /**
     * Registers a file THIS device is sharing (already fully written to
     * [store]) and announces it as a source. The mirror of completeDownload's
     * tail for the sending side: partitions up, seed tracked, registry entry
     * for restarts, a done Progress so the sender's own bubble renders green,
     * and a source_announce so leechers already in the channel notice at once.
     */
    suspend fun seedSentFile(
        metadata: FileMetadata,
        messageStreamId: String,
        isDm: Boolean,
        password: String?,
        store: PieceStore
    ) {
        val ephemeralStreamId = StreamConstants.deriveEphemeralId(messageStreamId)
        ensureMediaPartitions(ephemeralStreamId, password)
        // DM: piece REQUESTS arrive on our own inbox ephemeral, not the peer's.
        if (isDm) myDmEphemeralId()?.let { ensureMediaPartitions(it, null) }
        seeding[metadata.fileId] = SeedFile(
            fileId = metadata.fileId,
            messageStreamId = messageStreamId,
            ephemeralStreamId = ephemeralStreamId,
            isDm = isDm,
            password = password,
            store = store,
            pieceCount = metadata.pieceCount,
            fileName = metadata.fileName
        )
        emitSeeds()
        registry.record(
            SeedRegistry.Entry(
                fileId = metadata.fileId,
                messageStreamId = messageStreamId,
                ephemeralStreamId = ephemeralStreamId,
                isDm = isDm,
                pieceCount = metadata.pieceCount,
                fileSize = metadata.fileSize,
                fileName = metadata.fileName,
                completedAt = System.currentTimeMillis()
            )
        )
        publish(
            Progress(
                fileId = metadata.fileId,
                fileName = metadata.fileName,
                received = metadata.fileSize,
                total = metadata.fileSize,
                bytesPerSecond = null,
                seeders = 1,
                done = true
            )
        )
        transport.publishMediaSignal(
            ephemeralStreamId,
            JSONObject()
                .put("type", MediaWire.SIGNAL_SOURCE_ANNOUNCE)
                .put("fileId", metadata.fileId)
                .put("pieceCount", metadata.pieceCount),
            password,
            isDm
        )
        Log.i(TAG, "seeding sent file ${metadata.fileId} (${metadata.fileName})")
    }

    fun untrackSeed(fileId: String) {
        seeding.remove(fileId)?.store?.close()
        registry.remove(fileId)
        emitSeeds()
    }

    /**
     * Rehydrates this channel's seeds from the on-disk registry, so files
     * completed in a previous session serve again. Called on channel open,
     * before [reannounceForChannel] — that is the one moment the caller holds
     * the channel's password, which the registry deliberately does not store.
     *
     * Idempotent: fileIds already seeding (or still downloading) are skipped,
     * and an entry whose piece files are gone or incomplete is dropped rather
     * than restored — announcing a file we cannot fully serve would earn this
     * device strikes from every leecher that asks.
     */
    fun restoreSeedsFor(messageStreamId: String, password: String?) {
        for (dead in registry.sweepExpired()) {
            if (!seeding.containsKey(dead.fileId)) PieceStore.delete(transferDir(), dead.fileId)
        }
        for (entry in registry.entriesFor(messageStreamId)) {
            if (seeding.containsKey(entry.fileId) || incoming.containsKey(entry.fileId)) continue
            try {
                val store = PieceStore.open(transferDir(), entry.fileId, entry.fileSize)
                if (!store.isComplete()) {
                    store.close()
                    registry.remove(entry.fileId)
                    continue
                }
                seeding[entry.fileId] = SeedFile(
                    fileId = entry.fileId,
                    messageStreamId = entry.messageStreamId,
                    ephemeralStreamId = entry.ephemeralStreamId,
                    isDm = entry.isDm,
                    password = password,
                    store = store,
                    pieceCount = entry.pieceCount,
                    fileName = entry.fileName
                )
                emitSeeds()
                Log.i(TAG, "restored seed ${entry.fileId} (${entry.fileName})")
            } catch (e: Exception) {
                Log.w(TAG, "seed restore failed for ${entry.fileId}: ${e.message}")
                registry.remove(entry.fileId)
            }
        }
    }

    /** Seeds registered for a channel — the set re-announced on channel open. */
    fun seedsFor(messageStreamId: String): List<SeedFile> =
        seeding.values.filter { it.messageStreamId == messageStreamId }

    /**
     * Re-announces our seeds for a channel. Called on channel open: a seeder
     * that says nothing is invisible, so a file would look dead to anyone who
     * arrived while we were away.
     */
    suspend fun reannounceForChannel(messageStreamId: String, password: String?) {
        val seeds = seedsFor(messageStreamId)
        if (seeds.isEmpty()) return
        ensureMediaPartitions(StreamConstants.deriveEphemeralId(messageStreamId), password)
        for (seed in seeds) {
            transport.publishMediaSignal(
                seed.ephemeralStreamId,
                JSONObject()
                    .put("type", MediaWire.SIGNAL_SOURCE_ANNOUNCE)
                    .put("fileId", seed.fileId)
                    .put("pieceCount", seed.pieceCount),
                password,
                seed.isDm
            )
        }
        Log.i(TAG, "re-announced ${seeds.size} seed(s) for $messageStreamId")
    }

    // ==================== progress ====================

    /**
     * Sliding-window rate.
     *
     * The span is capped against NOW rather than the last sample, so a stalled
     * transfer reads as slowing down instead of freezing at whatever it managed
     * before it stopped — a frozen number looks like a working download.
     */
    private fun recordThroughput(transfer: Transfer) {
        val now = System.currentTimeMillis()
        synchronized(transfer.samples) {
            transfer.samples.addLast(now to transfer.store.bytesOnDisk())
            while (transfer.samples.size > 2 &&
                transfer.samples[1].first <= now - MediaConfig.SPEED_WINDOW_MS
            ) {
                transfer.samples.removeFirst()
            }
        }
    }

    private fun rateOf(transfer: Transfer): Long? {
        synchronized(transfer.samples) {
            if (transfer.samples.size < 2) return null
            val first = transfer.samples.first()
            val last = transfer.samples.last()
            val spanMs = maxOf(System.currentTimeMillis(), last.first) - first.first
            if (spanMs < 1000) return null
            return (last.second - first.second) * 1000 / spanMs
        }
    }

    private fun emitProgress(transfer: Transfer, done: Boolean = false) {
        publish(
            Progress(
                fileId = transfer.metadata.fileId,
                fileName = transfer.metadata.fileName,
                received = transfer.store.bytesOnDisk(),
                total = transfer.metadata.fileSize,
                bytesPerSecond = rateOf(transfer),
                seeders = transfer.seeders.size,
                done = done,
                failure = transfer.failed
            )
        )
    }

    private fun publish(p: Progress) {
        _progress.value = _progress.value + (p.fileId to p)
    }
}

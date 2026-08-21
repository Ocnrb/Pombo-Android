package com.pombo.android.core

import android.util.Base64
import android.util.Log
import com.pombo.android.bridge.PomboBridge
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private typealias Win = StorageWindows.Window

/**
 * Persistent File Sharing engine (storage-node transport) — native port of the
 * web storageMedia.js. STANDALONE (not owned by ChannelManager, unlike the mesh
 * [MediaController]): the mesh engine lives there to coordinate its LIVE -2
 * subscriptions with the channel-switch mutex; storage chunks are NEVER
 * subscribed live (read via HTTP/resend on demand), so there is nothing to
 * coordinate and the web's standalone-controller shape is the right one.
 *
 * This file covers both halves: upload (warm-up, paced publish with the full
 * auto-tune v3 oscillator, timestamp-based verify & repair, announce)
 * and download (windowed HTTP/resend fetch into resumable staging, then
 * assemble/inflate).
 *
 * Concurrency model: the engine's coordination (publish workers + the auto-tune
 * probe + verify/repair) runs on a per-transfer SINGLE-slot dispatcher, so the
 * shared publish state (sendSpacing, stored, chunkTs, doneCount) is only ever
 * touched by one coroutine at a time — mirroring the web's single thread and
 * avoiding data races. The blocking I/O underneath (bridge publish, HTTP reads)
 * still fans out onto Dispatchers.IO.
 *
 * Deliberate divergences from the web: native per-file crypto ([PomboCrypto]),
 * native HTTP reads ([StorageHttp]), compress-then-publish (not pipelined),
 * MP4 faststart skipped ([StorageMediaConfig.FASTSTART] = false).
 */
class StorageMedia(
    private val bridge: PomboBridge,
    private val endpoints: StorageEndpoints,
    private val scope: CoroutineScope,
    /** Staging dir for compressed uploads. Must be under filesDir, never cacheDir. */
    private val transferDir: () -> File,
    /** DM chunk sealing and channel pseudonyms are native — the key stays in Kotlin. */
    private val myPrivateKey: () -> String?,
    private val transport: Transport
) {
    private companion object {
        const val TAG = "PomboMedia"
        const val INFLIGHT_HIGH = 1_536 * 1024L   // 1.5 MB — mirrors the web dcRadar HIGH watermark
        const val INFLIGHT_LOW = 384 * 1024L
        const val CHUNK_PARTS = StreamConstants.STORAGE_CHUNK_PARTITIONS
    }

    /** How the engine reaches the network for anything that is NOT a raw chunk publish. */
    interface Transport {
        /**
         * Publish the announce as a normal P0 message, sealed by the
         * channel's rules (the single sealing point — ephemeral identity +
         * proof / password AES-GCM / DM sealed sender), and return the
         * publish timestamp for the confirm loop.
         */
        suspend fun publishAnnounce(messageStreamId: String, announce: JSONObject, password: String?, isDm: Boolean): Long

        fun myAddress(): String?
        fun username(): String?
    }

    /** A file to upload. The caller (which holds the ContentResolver) supplies the bytes. */
    interface Source {
        val fileName: String
        val fileType: String
        val size: Long
        /** Sequential whole-file stream (compression path). */
        fun openStream(): InputStream
        /** Random-access reader (non-compressed path). */
        fun openSlicer(): Slicer
        interface Slicer : Closeable {
            fun readAt(offset: Long, length: Int): ByteArray
        }
    }

    /** Sender-bubble stats snapshot (mirror of the web upState). */
    data class UploadProgress(
        val transferId: String,
        val stage: String,           // processing | sending | verifying | repairing | announce | done
        val phase: String,           // human label
        val percent: Int,
        val bytesSent: Long,
        val totalBytes: Long?,       // compressed size (null until known)
        val fileSize: Long,
        val instBps: Long?,
        val avgBps: Long?,
        val etaSec: Long?,
        val processingPct: Int = 100,   // compression progress; <100 while the pipeline still deflates
        val error: String? = null
    )

    data class SendResult(val messageId: String, val announce: JSONObject, val storedChunks: Int?, val totalChunks: Int)

    /**
     * The `storage_file_announce` metadata, as a UI bubble and (later) a download
     * need it. Mirrors the web announce.metadata. [totalChunks] is null on the
     * optimistic sender bubble (before chunking is known).
     */
    data class StorageFileMetadata(
        val transferId: String,
        val fileName: String,
        val fileType: String,
        val originalSize: Long,
        val compressedSize: Long?,
        val compression: String,
        val totalChunks: Int?,
        val chunkDataSize: Int?,
        val chunkPartitions: Int,
        val firstChunkPartition: Int,
        val firstChunkTs: Long?,
        val lastChunkTs: Long?,
        val storedChunks: Int?,
        val encSalt: String?
    ) {
        companion object {
            /** Parse from an announce message's `metadata` object; null if unusable. */
            fun from(md: JSONObject?): StorageFileMetadata? {
                if (md == null) return null
                val tid = md.optString("transferId").ifEmpty { return null }
                val size = md.optLong("originalSize", 0L)
                if (size <= 0) return null
                fun optIntOrNull(k: String) = if (md.isNull(k) || !md.has(k)) null else md.optInt(k)
                fun optLongOrNull(k: String) = if (md.isNull(k) || !md.has(k)) null else md.optLong(k)
                fun optStrOrNull(k: String) = if (md.isNull(k) || !md.has(k)) null else md.optString(k).ifEmpty { null }
                return StorageFileMetadata(
                    transferId = tid,
                    fileName = md.optString("fileName").ifEmpty { tid },
                    fileType = md.optString("fileType"),
                    originalSize = size,
                    compressedSize = optLongOrNull("compressedSize"),
                    compression = md.optString("compression", "none"),
                    totalChunks = optIntOrNull("totalChunks"),
                    chunkDataSize = optIntOrNull("chunkDataSize"),
                    chunkPartitions = md.optInt("chunkPartitions", CHUNK_PARTS),
                    firstChunkPartition = md.optInt("firstChunkPartition", StreamConstants.STORAGE_FIRST_CHUNK_PARTITION),
                    firstChunkTs = optLongOrNull("firstChunkTs"),
                    lastChunkTs = optLongOrNull("lastChunkTs"),
                    storedChunks = optIntOrNull("storedChunks"),
                    encSalt = optStrOrNull("encSalt")
                )
            }
        }
    }

    /** Receiver-bubble download snapshot. */
    data class DownloadProgress(
        val transferId: String,
        val status: String,          // downloading | complete | error | paused
        val percent: Int,
        val received: Int,
        val total: Int,
        val bytesReceived: Long,
        val fileSize: Long,
        val bytesPerSec: Long?,
        val phase: String?,          // "Finishing write to disk…" / "Extracting… N%"
        val error: String? = null,
        val etaSec: Long? = null
    )

    private val _uploads = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())
    val uploads: StateFlow<Map<String, UploadProgress>> = _uploads

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads

    /** transferId -> completed file on disk (staged bytes or inflated output), for saving. */
    private val completed = ConcurrentHashMap<String, File>()
    fun completedFile(transferId: String): File? = completed[transferId]

    /**
     * Forgets a download's partial state for good: staged bytes and progress
     * entry both go. The cancel counterpart of the pause path (whose
     * CancellationException branch deliberately KEEPS the staging) — the
     * caller cancels the Job first and calls this after it unwinds.
     */
    fun discardDownload(transferId: String) {
        StorageStagingStore.delete(transferDir(), transferId)
        _downloads.update { it - transferId }
        Log.i(TAG, "storage download discarded: $transferId")
    }
    fun isDownloading(transferId: String): Boolean = _downloads.value[transferId]?.status == "downloading"

    /**
     * Any upload or download still in flight — drives the background foreground
     * service (so a transfer survives a channel switch or the app backgrounding).
     */
    fun hasActiveTransfers(): Boolean =
        _uploads.value.values.any { it.stage != "done" && it.error == null } ||
            _downloads.value.values.any { it.status == "downloading" }

    private val warmedUp = Collections.synchronizedSet(HashSet<String>())
    private val warmUpJobs = ConcurrentHashMap<String, Job>()

    private fun perf(): Long = System.nanoTime() / 1_000_000
    private fun wall(): Long = System.currentTimeMillis()

    private class Sealer(val seal: (ByteArray) -> ByteArray, val encSalt: String?, val overhead: Int)

    private fun makeSealer(
        password: String?, isDm: Boolean, epochKey: EpochKeyManager.CurrentKey? = null
    ): Sealer {
        // DM chunks seal with the pair's ECDH key IN THE BRIDGE (native crypto has
        // no ECDH), so the seal here is identity — but the [iv][ct] the bridge adds
        // still costs SEAL_OVERHEAD out of the chunk budget, and there is no salt.
        if (isDm) return Sealer({ it }, null, StorageWire.SEAL_OVERHEAD)
        // Epoch channels (native/gated): ONE key per transfer, captured by the
        // caller — a rotation mid-upload does not re-key chunks already out,
        // exactly as it does not re-key a sent message.
        if (epochKey != null) return Sealer(
            seal = { EpochKeyCrypto.sealBinaryWithEpochKey(it, epochKey.keyHex, epochKey.kid) },
            encSalt = null,
            // [1B version][1B kidLen][kid][12B iv][16B tag] + headroom for a
            // longer kid if a rotation lands mid-transfer
            overhead = 30 + epochKey.kid.toByteArray(Charsets.UTF_8).size + 8
        )
        if (password.isNullOrEmpty()) return Sealer({ it }, null, 0)
        val salt = PomboCrypto.generateSalt()
        val key: SecretKeySpec = PomboCrypto.deriveKeyWithSalt(password, salt) // one PBKDF2, cached per file
        return Sealer(
            seal = { PomboCrypto.encryptBinaryWithKey(it, key) },
            encSalt = Base64.encodeToString(salt, Base64.NO_WRAP),
            overhead = StorageWire.SEAL_OVERHEAD
        )
    }

    private fun shouldCompress(fileType: String, fileName: String): Boolean {
        val t = fileType.lowercase()
        if (Regex("^(video|audio)/").containsMatchIn(t)) return false
        if (t.startsWith("image/") && !Regex("svg|bmp|tiff").containsMatchIn(t)) return false
        if (Regex("zip|gzip|compressed|x-7z|x-rar|x-bzip").containsMatchIn(t)) return false
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext !in setOf(
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "mp3", "m4a", "aac", "ogg", "opus", "flac",
            "jpg", "jpeg", "png", "webp", "gif", "heic", "avif", "zip", "gz", "7z", "rar", "bz2", "xz", "zst"
        )
    }

    // ---- warm-up ----
    /** Per-transfer DM chunk publishing identities — disposable, in memory only. */
    private val chunkIdentities = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The channel pseudonym shared with the announce path (ChannelIdentities). */
    private fun channelEntry(streamId: String): ChannelIdentities.Entry {
        val pk = myPrivateKey() ?: throw IllegalStateException("No identity")
        return ChannelIdentities.entryFor(
            streamId, EthereumSigner.checksumAddress(EthereumSigner.address(pk)), pk
        )
    }

    private suspend fun warmUpPartitions(
        sid: String, firstPartition: Int, identityPk: String?,
        gateAddress: String? = null, asAccount: Boolean = false,
        onStatus: (String) -> Unit
    ) {
        if (warmedUp.contains(sid)) return
        warmUpJobs[sid]?.let { it.join(); return }
        val job = scope.launch(Dispatchers.IO) {
            onStatus("Preparing network…")
            val ping = byteArrayOf(0)
            coroutineScope {
                for (k in 0 until CHUNK_PARTS) launch {
                    // Pings ride the same publisher as the chunks, or the wallet
                    // would appear on the channel's -1 stream just from warm-up.
                    try {
                        bridge.publishStorageChunk(
                            sid, firstPartition + k, ping,
                            identityPk = identityPk,
                            gateAddress = gateAddress, asAccount = asAccount
                        )
                    }
                    catch (e: Exception) { Log.w(TAG, "storage warm-up P${firstPartition + k} failed: ${e.message}") }
                }
            }
            delay(StorageMediaConfig.WARMUP_WAIT_MS)
            warmedUp.add(sid)
        }
        warmUpJobs[sid] = job
        try { job.join() } finally { warmUpJobs.remove(sid) }
    }

    // ---- verify reads (by timestamp; HTTP with format=metadata fast path) ----
    private class ReadStats { var winsOk = 0; var winsFailed = 0; var rows = 0; var foreignRows = 0 }

    private suspend fun readStoredIndices(
        sid: String, windows: List<Win>, tsIndex: Map<String, Int>, bases: List<String>,
        label: String, stats: ReadStats? = null,
        /**
         * Which publisher our own chunks carry. The wallet for channel
         * uploads — but a DM transfer publishes under a per-transfer
         * throwaway identity, and filtering by the wallet there would
         * classify every one of OUR OWN rows as foreign and verify blind
         * (trap 1 of the migration, storage flavour).
         */
        expectedPublisher: String? = null,
        onProgress: ((Set<Int>) -> Unit)? = null
    ): Set<Int> {
        val found = Collections.synchronizedSet(HashSet<Int>())
        val expected = (expectedPublisher ?: transport.myAddress() ?: "").lowercase()
        val next = AtomicInteger(0)
        val lock = Any()
        fun matchTs(partition: Int, timestamp: Long, publisherId: String?) {
            synchronized(lock) { stats?.let { it.rows++ } }
            if (expected.isNotEmpty() && publisherId != null && publisherId.lowercase() != expected) {
                synchronized(lock) { stats?.let { it.foreignRows++ } }; return
            }
            tsIndex["$partition:$timestamp"]?.let { found.add(it) }
        }
        suspend fun worker() {
            while (true) {
                val idx = next.getAndIncrement()
                if (idx >= windows.size) return
                val w = windows[idx]
                val base = if (bases.isNotEmpty()) bases[idx % bases.size] else null
                var done = false
                if (base != null && endpoints.supportsMetaFormat(base) != false) {
                    try {
                        StorageHttp.directFetchRangeMeta(base, sid, w.partition, w.from, w.to)
                            .forEach { matchTs(w.partition, it.timestamp, it.publisherId) }
                        endpoints.noteSuccess(base)
                        synchronized(lock) { stats?.let { it.winsOk++ } }
                        done = true
                    } catch (e: StorageHttp.HttpStatusException) {
                        if (e.code == 400) { endpoints.setMetaFormatSupport(base, false); Log.w(TAG, "format=metadata unavailable on $base — full reads") }
                        else { endpoints.noteFailure(base); Log.w(TAG, "$label: metadata read failed on $base — full read") }
                    } catch (e: Exception) {
                        endpoints.noteFailure(base); Log.w(TAG, "$label: metadata read failed on $base — full read")
                    }
                }
                if (!done) {
                    try {
                        if (base == null) throw IllegalStateException("no HTTP endpoint")
                        StorageHttp.directFetchRange(base, sid, w.partition, w.from, w.to) { matchTs(w.partition, it.timestamp, it.publisherId) }
                        endpoints.noteSuccess(base)
                        synchronized(lock) { stats?.let { it.winsOk++ } }
                    } catch (e: Exception) {
                        synchronized(lock) { stats?.let { it.winsFailed++ } }
                        Log.w(TAG, "$label: window P${w.partition} read failed: ${e.message}")
                        if (base != null) endpoints.noteFailure(base)
                    }
                }
                onProgress?.invoke(found.toSet())
            }
        }
        coroutineScope { repeat(min(4, max(1, windows.size))) { launch(Dispatchers.IO) { worker() } } }
        return found.toSet()
    }

    private fun windowedRate(hist: ArrayDeque<Pair<Long, Long>>, now: Long): Long? {
        if (hist.size < 2) return null
        val first = hist.first(); val last = hist.last()
        val span = max(now, last.first) - first.first
        if (span < 1000) return null
        return (last.second - first.second) * 1000 / span
    }

    private fun countRate(hist: ArrayDeque<Pair<Long, Int>>, now: Long): Double? {
        if (hist.size < 2) return null
        val first = hist.first(); val last = hist.last()
        val span = (max(now, last.first) - first.first) / 1000.0
        if (span < 1) return null
        return (last.second - first.second) / span
    }

    // ==================== upload ====================

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun sendFile(
        messageStreamId: String, source: Source, password: String?,
        isDm: Boolean = false, dmPeerPublicKey: String? = null,
        /** public/password channel: publish chunks + warm-up under the
         *  channel's ephemeral identity (native/readOnly stay on the account). */
        channelEphemeral: Boolean = false,
        /** Epoch channel (native/gated): the CURRENT key, captured by the
         *  caller — chunks seal with it for the whole transfer. */
        epochKey: EpochKeyManager.CurrentKey? = null,
        /** Gated channel: the clone address — chunks and warm-up pings publish
         *  through it (ERC-1271), and verify matches rows against it. */
        gateAddress: String? = null,
        messageId: String? = null, transferId: String? = null
    ): SendResult {
        if (source.size <= 0L) throw IllegalStateException("File is empty")
        if (isDm && dmPeerPublicKey.isNullOrEmpty()) throw IllegalStateException("DM storage transfer needs the peer's public key")

        // DM inboxes place chunks at P4-P12 (after the 4 classic partitions).
        val firstPart = if (isDm) StreamConstants.STORAGE_DM_FIRST_CHUNK_PARTITION else StreamConstants.STORAGE_FIRST_CHUNK_PARTITION
        fun chunkPartition(i: Int) = StreamConstants.storageChunkPartition(i, firstPart)

        val bases = try { endpoints.rotation(messageStreamId) } catch (e: Exception) {
            Log.w(TAG, "storage endpoint resolution failed: ${e.message}"); emptyList()
        }
        if (bases.isEmpty()) {
            // DM verify is HTTP-only by timestamp (the sender has no subscribe
            // permission on the peer's inbox → no SDK-resend fallback), so a DM
            // upload with no resolvable endpoint cannot be verified — refuse it.
            if (isDm) throw IllegalStateException("No reachable storage endpoint for this inbox — persistent DM sharing needs one")
            Log.w(TAG, "No storage HTTP endpoints — verify degraded")
        } else Log.i(TAG, "Storage upload verify endpoints (${bases.size}): ${bases.joinToString()}")

        val sealer = makeSealer(password, isDm, epochKey)
        val tid = transferId ?: Protocol.generateMessageId()
        val msgId = messageId ?: Protocol.generateMessageId()
        val maxB = StorageMediaConfig.CHUNK_KB * 1024

        val up = UploadState(tid, source.size)
        emit(up)

        // Single-slot dispatcher: engine coordination never runs two coroutines at once.
        val engine = Dispatchers.Default.limitedParallelism(1)
        var stagingFile: File? = null
        var slicer: Source.Slicer? = null
        var producerJob: Job? = null
        try {
            val doCompress = shouldCompress(source.fileType, source.fileName)
            val compression = if (doCompress) "deflate" else "none"
            up.phase = "Processing…"; emit(up)

            // Worst-case deflate size bound (zlib stored blocks + header slack), so
            // the chunk meta + chunk math start BEFORE compression finishes — the
            // true size goes in the announce. Same estimate the web uses.
            val metaCompSize = if (doCompress) source.size + ceil(source.size / 16384.0).toLong() * 5 + 64 else source.size
            val startTs = wall()
            val metaBytes = StorageWire.chunkMetaJson(source.fileName, source.fileType, source.size, metaCompSize, compression, tid, startTs)
            val overheadHeader = 4 + metaBytes.size + 4 + 4
            val upc = maxB - overheadHeader - sealer.overhead
            if (upc <= 0) throw IllegalStateException("Chunk size too small for metadata overhead")
            val estTc = max(1, ceil(metaCompSize.toDouble() / upc).toInt())
            up.totalBytes = metaCompSize

            // ---- compression PRODUCER, pipelined with publishing (web parity) ----
            // committed = compressed bytes on disk & readable; publish workers consume
            // the growing prefix instead of waiting for the whole file to compress
            // first. The non-compressed path has everything available immediately.
            val committed = AtomicLong(if (doCompress) 0L else source.size)
            val compDoneFlag = AtomicBoolean(!doCompress)
            val compSizeRef = AtomicLong(if (doCompress) 0L else source.size)
            val producerError = AtomicReference<Throwable?>(null)
            fun curTc(): Int = if (compDoneFlag.get()) max(1, ceil(compSizeRef.get().toDouble() / upc).toInt()) else estTc

            if (doCompress) {
                val staged = File(transferDir().also { it.mkdirs() }, "psf_up_$tid.z")
                stagingFile = staged
                // The read-only slicer must find the file already there — create it
                // empty first; the producer's FileOutputStream truncates & fills it
                // as it compresses (same-process page cache makes the growth visible).
                withContext(Dispatchers.IO) { staged.delete(); staged.createNewFile() }
                slicer = FileSlicer(staged)
                producerJob = scope.launch(Dispatchers.IO) {
                    try {
                        val n = source.openStream().use { input ->
                            // Raw FileOutputStream (unbuffered) so `committed` reflects readable bytes.
                            java.io.FileOutputStream(staged).use { fos ->
                                StorageDeflate.deflate(input, fos, onCommitted = { committed.set(it) }) { done ->
                                    up.processingPct = ((done.toDouble() / source.size) * 100).toInt()
                                    if (up.doneCount == 0) { up.phase = "Processing… ${up.processingPct}%"; emitThrottled(up) }
                                }
                            }
                        }
                        compSizeRef.set(n)
                    } catch (e: Throwable) {
                        producerError.set(e)
                    } finally {
                        compDoneFlag.set(true)
                    }
                }
            } else {
                slicer = withContext(Dispatchers.IO) { source.openSlicer() }
            }
            val theSlicer = slicer!!

            fun chunkStart(i: Int) = i.toLong() * upc
            fun chunkEnd(i: Int) = if (compDoneFlag.get()) min(compSizeRef.get(), (i + 1).toLong() * upc) else (i + 1).toLong() * upc
            fun buildPayload(i: Int): ByteArray {
                val len = (chunkEnd(i) - chunkStart(i)).toInt()
                val cd = theSlicer.readAt(chunkStart(i), len)
                return sealer.seal(StorageWire.packChunkPayload(metaBytes, curTc(), i, cd))
            }

            // Who publishes the chunks and warm-up pings (and thus who verify
            // must match): the announce goes out under an ephemeral identity,
            // so the chunks must not fall back to the wallet.
            //   DM        → a per-transfer throwaway identity (the chunk bytes
            //               stay on the v1 pair key; only the publisher is
            //               disposable), address from dmChunkIdentityCreate.
            //   public/pw → the channel's own ephemeral identity, the same
            //               pseudonym as the announce.
            //   gated     → the clone (ERC-1271, inside the bridge publish);
            //               verify expects the gate address.
            //   native/ro → the account; verifyPublisher stays null and verify
            //               falls back to our wallet (D3).
            // Publishing identities are minted natively now; the pair key that
            // seals DM chunk bytes is derived once per transfer.
            val chunkIdentityPk = if (isDm) chunkIdentities.computeIfAbsent("chunks|$messageStreamId|$tid") {
                SealedSenderCrypto.generateEphemeralKey()
            } else null
            val dmChunkKey = if (isDm) {
                val myPk = myPrivateKey() ?: throw IllegalStateException("No identity")
                SealedSenderCrypto.pairKey(myPk, dmPeerPublicKey!!)
                    ?: throw IllegalStateException("Invalid peer public key for DM storage transfer")
            } else null
            val publishIdentityPk = when {
                chunkIdentityPk != null -> chunkIdentityPk
                channelEphemeral -> channelEntry(messageStreamId).identityPk
                else -> null
            }
            val verifyPublisher = when {
                chunkIdentityPk != null -> EthereumSigner.address(chunkIdentityPk)
                channelEphemeral -> channelEntry(messageStreamId).publisherId.lowercase()
                gateAddress != null -> gateAddress.lowercase()
                else -> null
            }
            // Epoch chunks must ride publishAs (encryptionType NONE): a bare
            // client.publish on a members-only stream would re-wrap them in
            // the SDK's group-key AES and break the HTTP hex reader.
            val asAccount = epochKey != null && gateAddress == null

            warmUpPartitions(messageStreamId, firstPart, publishIdentityPk, gateAddress, asAccount) { up.phase = it; emit(up) }

            val firstChunkTs = wall()
            val uploadStart = perf()

            // ---- engine-thread-confined publish state ----
            val stored = HashSet<Int>()
            val chunkTs = HashMap<Int, Long>()
            val chunkTsHist = HashMap<Int, MutableList<Long>>()
            val failedChunks = ArrayList<Int>()
            val nextIdx = AtomicInteger(0)
            var totalBytesSent = 0L
            val speedHist = ArrayDeque<Pair<Long, Long>>()
            val etaHist = ArrayDeque<Pair<Long, Int>>()
            // Proven web pacing (storageMedia.js): while publishing we are always
            // connected to the overlay, so atFullStart is true — start at ~1.5 MB/s
            // and let the auto-tune ramp/cut, not the conservative 500 KB/s cold start.
            val atFullStart = StorageMediaConfig.AUTOTUNE
            var sendSpacing = when {
                !StorageMediaConfig.AUTOTUNE -> StorageMediaConfig.THROTTLE_MS.toDouble() / StorageMediaConfig.PARALLEL
                atFullStart -> maxB / (1.5 * 1024 * 1024) * 1000
                else -> max(StorageMediaConfig.THROTTLE_MS.toDouble() / StorageMediaConfig.PARALLEL, maxB / (500.0 * 1024) * 1000)
            }
            // Wire-backpressure signal — the web's dcRadar analog via bridge.inFlight().
            var wireThrottled = false
            var throttleCount = 0
            var verifyBlind = false
            var fullMissProbes = 0

            fun tsIndexForVerify(): Map<String, Int> {
                val m = HashMap<String, Int>()
                for ((i, hist) in chunkTsHist) { val p = chunkPartition(i); for (t in hist) m["$p:$t"] = i }
                return m
            }
            fun allPartitionWindows(from: Long, to: Long) = (0 until CHUNK_PARTS).map { Win(firstPart + it, from, to) }
            fun windowsForIndices(indices: Collection<Int>): List<Win> {
                val pad = 1500L
                val raw = ArrayList<Win>()
                for (i in indices) {
                    val p = chunkPartition(i)
                    val hist = chunkTsHist[i]
                    if (hist != null && hist.isNotEmpty()) for (t in hist) raw.add(Win(p, t - pad, t + pad))
                    else raw.add(Win(p, firstChunkTs - 5000, wall() + 1000))
                }
                return StorageWindows.mergeWindows(raw)
            }

            // ---- pacing ----
            var nextSlotAt = perf().toDouble()
            suspend fun awaitSendSlot() {
                // Wire-level backpressure (web dcBackpressure): pause while the bridge
                // holds too many unresolved bytes. A pause counts as a "throttle" —
                // the releasedFull jump only fires when the first 10 s saw none.
                if (bridge.inFlight() >= INFLIGHT_HIGH) {
                    wireThrottled = true; throttleCount++
                    while (bridge.inFlight() > INFLIGHT_LOW) delay(50)
                }
                val now = perf().toDouble()
                val wait = nextSlotAt - now
                nextSlotAt = max(now, nextSlotAt) + sendSpacing
                if (wait > 0) delay(wait.toLong())
            }
            suspend fun publishOne(i: Int): Int {
                var pay = buildPayload(i)
                // DM chunk rows stay on the v1 pair key, sealed natively now.
                if (dmChunkKey != null) pay = SealedSenderCrypto.pairSeal(pay, dmChunkKey)
                awaitSendSlot()
                val ts = publishChunkWithRetry(
                    messageStreamId, chunkPartition(i), pay,
                    publishIdentityPk, gateAddress, asAccount
                )
                chunkTs[i] = ts
                chunkTsHist.getOrPut(i) { ArrayList() }.add(ts)
                return pay.size
            }
            suspend fun republishParallel(indices: List<Int>) {
                val n = AtomicInteger(0)
                val failed = ArrayList<Int>()
                coroutineScope {
                    repeat(min(StorageMediaConfig.PARALLEL, max(1, indices.size))) {
                        launch(engine) {
                            while (true) {
                                val k = n.getAndIncrement(); if (k >= indices.size) break
                                try { publishOne(indices[k]) } catch (e: Exception) { Log.e(TAG, "repair chunk ${indices[k]} failed: ${e.message}"); failed.add(indices[k]) }
                            }
                        }
                    }
                }
                failedChunks.addAll(failed)
            }

            // ---- publish worker ----
            suspend fun publishWorker() {
                while (true) {
                    val i = nextIdx.getAndIncrement()
                    // Wait until chunk i's compressed bytes are on disk (or compression finished).
                    while (!compDoneFlag.get() && (i + 1L) * upc > committed.get()) {
                        producerError.get()?.let { throw it }
                        delay(5)
                    }
                    producerError.get()?.let { throw it }
                    val tcNow = curTc()
                    if (compDoneFlag.get() && i >= tcNow) return
                    val payLen = try { publishOne(i) } catch (e: Exception) {
                        Log.e(TAG, "Publish of chunk $i failed: ${e.message}"); failedChunks.add(i); upc + overheadHeader + sealer.overhead
                    }
                    totalBytesSent += payLen
                    up.bytesSent = totalBytesSent
                    val now = perf()
                    speedHist.addLast(now to totalBytesSent)
                    while (speedHist.size > 2 && speedHist.first().first < now - 1500) speedHist.removeFirst()
                    up.doneCount++
                    etaHist.addLast(now to up.doneCount)
                    while (etaHist.size > 2 && etaHist.first().first < now - StorageMediaConfig.RATE_WINDOW_MS) etaHist.removeFirst()
                    up.stage = "sending"
                    up.instBps = windowedRate(speedHist, now)
                    up.avgBps = (totalBytesSent / max(0.001, (now - uploadStart) / 1000.0)).toLong()
                    val cps = countRate(etaHist, now)
                    up.etaSec = if (cps != null && cps > 0) ((tcNow - up.doneCount) / cps).toLong() else null
                    up.percent = (up.doneCount.toDouble() / tcNow * 100).toInt()
                    emitThrottled(up)
                }
            }

            // ---- auto-tune v3 oscillator (search/cut/drain/climb/hold) ----
            val rateKBs = { -> (maxB / sendSpacing).toInt() }
            var atState = "search"; var ceilSpacing = 0.0; var drainMisses = 0; var holdProbes = 0
            var lastCutTs = 0L; var cutStreak = 0; var researchOnDrain = false
            var judgeAfterTs = 0L
            var lastProbeSentBytes = 0L; var lastProbeT = 0L; var lastRealBps = 0.0
            // releasedFull: armed when atFullStart — 10 s into search with no wire
            // throttle, drop pacing entirely (web's 24 MB/s "release") and let the
            // network be the limit; the auto-tune cuts back only on verified loss.
            val atStartTs = wall(); val throttlesAtStart = throttleCount
            var releasedFull = !atFullStart
            val rateHist = ArrayList<Pair<Long, Double>>().apply { add(wall() to sendSpacing) }
            fun noteRate() { rateHist.add(wall() to sendSpacing) }
            fun spacingAt(ts: Long): Double { var s = rateHist[0].second; for (e in rateHist) { if (e.first <= ts) s = e.second else break }; return s }
            fun enterDrain(lossSp: Double, why: String) {
                ceilSpacing = lossSp
                if (lastRealBps > 100 * 1024) ceilSpacing = max(ceilSpacing, maxB * 1000.0 / (lastRealBps * 1.05))
                val nowCut = wall()
                cutStreak = if (nowCut - lastCutTs < 75000) cutStreak + 1 else 1
                lastCutTs = nowCut
                if (cutStreak > 1) { ceilSpacing *= 1.12.pow(cutStreak - 1); if (cutStreak >= 4) researchOnDrain = true }
                atState = "drain"; drainMisses = 0; judgeAfterTs = wall()
                sendSpacing = min(2000.0, ceilSpacing * 3)
                noteRate()
                Log.w(TAG, "storage auto-tune: $why — CUT to ${rateKBs()}KB/s (draining)")
            }
            suspend fun probeOnce() {
                if (compDoneFlag.get() && up.doneCount >= curTc()) return
                if (!releasedFull && atState == "search" && wall() - atStartTs > 10000) {
                    releasedFull = true
                    if (throttleCount == throttlesAtStart) { sendSpacing = maxB / (24.0 * 1024 * 1024) * 1000; noteRate() }
                }
                val nowP = perf()
                if (lastProbeT > 0 && nowP > lastProbeT + 1000) lastRealBps = (totalBytesSent - lastProbeSentBytes).toDouble() / ((nowP - lastProbeT) / 1000.0)
                lastProbeT = nowP; lastProbeSentBytes = totalBytesSent
                val now = wall()
                val cand = ArrayList<Int>()
                for ((i, ts) in chunkTs) if (ts > judgeAfterTs && !stored.contains(i)) { val age = now - ts; if (age in 12001..39999) cand.add(i) }
                if (verifyBlind || cand.size < 3) return
                val sample = ArrayList<Int>()
                val step = max(1, cand.size / 5)
                var k = 0; while (k < cand.size && sample.size < 5) { sample.add(cand[k]); k += step }
                val st = ReadStats()
                val found = readStoredIndices(messageStreamId, windowsForIndices(sample), tsIndexForVerify(), bases, "auto-tune probe", st, expectedPublisher = verifyPublisher)
                stored.addAll(found)
                val missed = sample.filter { !found.contains(it) }
                if (missed.isNotEmpty()) {
                    if (st.winsOk == 0) { Log.w(TAG, "auto-tune probe: every verify read failed — skipping judgement"); return }
                    if (atState != "drain") {
                        delay(2500)
                        val found2 = readStoredIndices(messageStreamId, windowsForIndices(missed), tsIndexForVerify(), bases, "cut confirmation", expectedPublisher = verifyPublisher)
                        stored.addAll(found2)
                        val confirmed = missed.filter { !found2.contains(it) }
                        if (confirmed.isEmpty()) return
                        if (confirmed.size == sample.size && stored.isEmpty()) {
                            fullMissProbes++
                            if (fullMissProbes >= 3) { verifyBlind = true; Log.e(TAG, "Storage verify is BLIND: reads succeed but no published chunk is visible — likely the node isn't subscribed to the chunk partitions. Announcing UNVERIFIED."); return }
                        } else fullMissProbes = 0
                        val oldestTs = confirmed.minOf { chunkTs[it] ?: wall() }
                        enterDrain(spacingAt(oldestTs), "${confirmed.size}/${sample.size} invisible at ≥12s (2 reads)")
                    } else {
                        drainMisses++
                        if (drainMisses >= 2) { drainMisses = 0; judgeAfterTs = wall(); sendSpacing = min(2000.0, sendSpacing * 1.5); noteRate(); Log.w(TAG, "auto-tune: drain insufficient → ${rateKBs()}KB/s") }
                    }
                    return
                }
                // A wire throttle since the last probe: back the rate off to the real
                // throughput rather than ramping into a queue (web dcRadar.throttled).
                if (wireThrottled && atState != "drain") {
                    wireThrottled = false
                    if (lastRealBps > 100 * 1024) { sendSpacing = max(sendSpacing, maxB * 1000.0 / (lastRealBps * 1.05)); noteRate() }
                    return
                }
                if (lastRealBps > 100 * 1024 && (maxB * 1000.0 / sendSpacing) > lastRealBps * 1.25) return
                when (atState) {
                    "search" -> { sendSpacing = max(10.0, sendSpacing * 0.82); noteRate() }
                    "drain" -> {
                        if (researchOnDrain) { researchOnDrain = false; cutStreak = 0; lastCutTs = 0; atState = "search"; sendSpacing = min(2000.0, ceilSpacing * 1.6); noteRate() }
                        else { atState = "climb"; sendSpacing = max(10.0, ceilSpacing / 0.85); noteRate() }
                    }
                    "climb" -> { if (sendSpacing <= ceilSpacing / 0.95) { atState = "hold"; holdProbes = 0 } else { sendSpacing = max(10.0, sendSpacing * 0.95); noteRate() } }
                    else -> { holdProbes++; if (holdProbes == 6) { cutStreak = 0; lastCutTs = 0 }; if (holdProbes % 3 == 0) { sendSpacing = max(10.0, sendSpacing * 0.97); noteRate(); if (sendSpacing < ceilSpacing) ceilSpacing = sendSpacing } }
                }
            }

            val probeJob: Job? = if (StorageMediaConfig.AUTOTUNE) scope.launch(engine) {
                while (isActive) { delay(4000); try { probeOnce() } catch (e: Exception) { Log.w(TAG, "auto-tune probe failed: ${e.message}") } }
            } else null

            try {
                coroutineScope { repeat(min(StorageMediaConfig.PARALLEL, estTc)) { launch(engine) { publishWorker() } } }
            } finally { probeJob?.cancel() }

            // Compression is now fully consumed; finalize the real sizes for verify+announce.
            producerJob?.join()
            producerError.get()?.let { throw it }
            val compSize = compSizeRef.get()
            if (compSize > metaCompSize) throw IllegalStateException("compressed size ($compSize) exceeded the estimate ($metaCompSize)")
            val tc = max(1, ceil(compSize.toDouble() / upc).toInt())
            up.totalBytes = compSize

            val lastChunkTs = wall()

            if (failedChunks.isNotEmpty()) {
                up.phase = "Resending failed chunks…"; up.stage = "sending"; emit(up)
                val retry = failedChunks.toList(); failedChunks.clear()
                withContext(engine) { republishParallel(retry) }
            }

            // ---- verify & repair ----
            var storedCount: Int? = null
            if (StorageMediaConfig.VERIFY) withContext(engine) {
                fun missingIdx(): List<Int> = (0 until tc).filter { !stored.contains(it) }
                fun updBar() { up.percent = if (tc > 0) stored.size * 100 / tc else 0; up.stage = "verifying"; emitThrottled(up) }
                updBar()

                // Frontier visibility: wait until each partition's newest chunk is readable.
                val newestByPart = LongArray(CHUNK_PARTS)
                for (i in 0 until tc) { val k = i % CHUNK_PARTS; val ts = chunkTs[i] ?: 0L; if (ts > newestByPart[k]) newestByPart[k] = ts }
                suspend fun frontierVisible(k: Int): Boolean {
                    val base = if (bases.isNotEmpty()) bases[k % bases.size] else null
                    if (base == null || newestByPart[k] == 0L) return true
                    if (endpoints.supportsMetaFormat(base) != false) {
                        try { return StorageHttp.directFetchRangeMeta(base, messageStreamId, firstPart + k, newestByPart[k] - 1500, newestByPart[k] + 1500).any { it.timestamp == newestByPart[k] } }
                        catch (e: StorageHttp.HttpStatusException) { if (e.code == 400) endpoints.setMetaFormatSupport(base, false) } catch (e: Exception) { /* fall through */ }
                    }
                    return try { var seen = false; StorageHttp.directFetchRange(base, messageStreamId, firstPart + k, newestByPart[k] - 1500, newestByPart[k] + 1500) { seen = true }; seen } catch (e: Exception) { true }
                }
                if (bases.isNotEmpty() && tc > 1) {
                    val pending = (0 until CHUNK_PARTS).filter { newestByPart[it] > 0 }.toMutableSet()
                    var lastPending = pending.size; var frontStall = 0
                    while (pending.isNotEmpty() && frontStall < 10) {
                        for (k in pending.toList()) try { if (frontierVisible(k)) pending.remove(k) } catch (e: Exception) { /* retry */ }
                        if (pending.isEmpty()) break
                        frontStall = if (pending.size < lastPending) 0 else frontStall + 1
                        lastPending = pending.size
                        up.phase = "Waiting for storage (${CHUNK_PARTS - pending.size}/$CHUNK_PARTS partitions caught up)…"; emit(up)
                        delay(2000)
                    }
                }

                var missing = missingIdx()
                if (missing.isNotEmpty()) {
                    up.phase = "Verifying on storage…"; emit(up)
                    val found = readStoredIndices(messageStreamId, allPartitionWindows(firstChunkTs - 15000, wall() + 1000), tsIndexForVerify(), bases, "full sweep", expectedPublisher = verifyPublisher) { fs ->
                        stored.addAll(fs); updBar(); up.phase = "Verifying: ${stored.size}/$tc confirmed…"; emitThrottled(up)
                    }
                    stored.addAll(found); missing = missingIdx(); updBar()
                }

                if (StorageMediaConfig.SECOND_CHANCE && !verifyBlind && missing.size > max(10, tc / 20)) {
                    val scStart = perf(); var zeroProgress = 0; var stalledReal = 0
                    Log.w(TAG, "Storage pre-repair drain: ${missing.size}/$tc missing after the sweep")
                    while (missing.isNotEmpty() && stalledReal < 2 && zeroProgress < 24 && perf() - scStart < 5 * 60000) {
                        delay(2500)
                        val before = missing.size
                        stored.addAll(readStoredIndices(messageStreamId, windowsForIndices(missing), tsIndexForVerify(), bases, "drain", expectedPublisher = verifyPublisher))
                        missing = missingIdx(); updBar()
                        if (missing.size < before) { zeroProgress = 0; stalledReal = 0 } else {
                            zeroProgress++
                            val parts = missing.map { it % CHUNK_PARTS }.toSet()
                            var allPassed = true; for (k in parts) if (!frontierVisible(k)) { allPassed = false; break }
                            if (allPassed) stalledReal++ else stalledReal = 0
                        }
                        up.phase = "Waiting for storage: ${missing.size} unconfirmed…"; emit(up)
                    }
                }

                var pass = 0; var stalledPasses = 0
                while (missing.isNotEmpty() && !verifyBlind && pass < 8 && stalledPasses < 3) {
                    pass++
                    val storedBefore = stored.size
                    if (pass > 1) sendSpacing = min(2000.0, sendSpacing * 1.5)
                    Log.w(TAG, "Storage repair pass $pass: ${missing.size} chunks confirmed missing")
                    up.phase = "Repairing (pass $pass)…"; up.stage = "repairing"; emit(up)
                    republishParallel(missing)
                    var noProg = 0
                    var r = 0
                    while (r < 30 && missing.isNotEmpty()) {
                        r++
                        delay(2000)
                        stored.addAll(readStoredIndices(messageStreamId, windowsForIndices(missing), tsIndexForVerify(), bases, "post-repair pass $pass", expectedPublisher = verifyPublisher))
                        val before = missing.size; missing = missingIdx(); updBar()
                        if (missing.size < before) { noProg = 0; continue }
                        noProg++; if (noProg >= 6) { Log.w(TAG, "$noProg reads with no progress — republishing the ${missing.size} missing again"); break }
                    }
                    stalledPasses = if (stored.size > storedBefore) 0 else stalledPasses + 1
                }
                storedCount = if (verifyBlind && stored.isEmpty()) {
                    Log.e(TAG, "Storage verify skipped (blind) — announcing UNVERIFIED"); null
                } else {
                    Log.i(TAG, "Storage verify & repair: ${stored.size}/$tc confirmed${if (stored.size < tc) " — INCOMPLETE" else ""}"); stored.size
                }
            }

            // ---- announce ----
            up.phase = "Publishing announcement…"; up.stage = "announce"; emit(up)
            val sender = transport.myAddress() ?: throw IllegalStateException("No identity")
            val timestamp = wall()
            val meta = JSONObject().apply {
                put("transferId", tid); put("fileName", source.fileName); put("fileType", source.fileType)
                put("originalSize", source.size); put("compressedSize", compSize); put("compression", compression)
                put("totalChunks", tc); put("chunkDataSize", upc); put("chunkPartitions", CHUNK_PARTS)
                put("firstChunkPartition", firstPart); put("firstChunkTs", firstChunkTs); put("lastChunkTs", lastChunkTs)
                put("storedChunks", storedCount ?: JSONObject.NULL); put("encSalt", sealer.encSalt ?: JSONObject.NULL)
            }
            // Unsigned announce (D6): identity comes from the proof (channels)
            // or the sealed envelope (DMs). `sender` stays on the object for
            // the local bubble; the bridge strips it at egress on ephemeral
            // publishes. Canonical builders survive in Protocol.kt for
            // verifying pre-migration history only.
            val announce = JSONObject().apply {
                put("type", "storage_file_announce"); put("v", 1); put("id", msgId); put("sender", sender)
                put("senderName", transport.username() ?: JSONObject.NULL); put("timestamp", timestamp)
                put("metadata", meta); put("replyTo", JSONObject.NULL)
            }
            val annTs = transport.publishAnnounce(messageStreamId, announce, password, isDm)
            confirmAnnounce(messageStreamId, annTs, bases) { a, n -> up.phase = "Confirming announcement ($a/$n)…"; emit(up) }

            val ok = failedChunks.isEmpty() && (storedCount == tc || storedCount == null)
            val secs = (perf() - uploadStart) / 1000.0
            up.phase = when { storedCount == null -> "Stored — UNVERIFIED (storage node issue)"; ok -> "Stored in %.1fs ✓".format(secs); else -> "Stored with gaps ($storedCount/$tc)" }
            up.stage = "done"; up.percent = 100; up.bytesSent = up.totalBytes ?: totalBytesSent
            emit(up); clearProgress(tid)
            return SendResult(msgId, announce, storedCount, tc)
        } catch (e: Exception) {
            up.error = e.message; emit(up); clearProgress(tid); throw e
        } finally {
            producerJob?.cancel()   // stop a still-running compression on an abnormal exit
            try { slicer?.close() } catch (e: Exception) { /* closed */ }
            stagingFile?.let { runCatching { it.delete() } }
            // The per-transfer throwaway publisher dies with the transfer —
            // native, in memory only, and not even for the rest of the session.
            if (isDm) chunkIdentities.remove("chunks|$messageStreamId|$tid")
        }
    }

    /** Announce visibility check on P0, matching by publish timestamp. */
    private suspend fun confirmAnnounce(messageStreamId: String, annTs: Long, bases: List<String>, onTry: (Int, Int) -> Unit) {
        val waits = longArrayOf(1500, 3000, 6000, 10000, 15000, 25000)
        val from = annTs - 1500; val to = annTs + 1500; val p0 = StreamConstants.P_MESSAGES
        for (a in waits.indices) {
            onTry(a + 1, waits.size)
            delay(waits[a])
            val seen = try {
                var found = false
                if (bases.isNotEmpty()) {
                    for (base in bases) {
                        if (endpoints.supportsMetaFormat(base) != false) {
                            try { if (StorageHttp.directFetchRangeMeta(base, messageStreamId, p0, from, to).any { it.timestamp == annTs }) { found = true; break }; continue }
                            catch (e: StorageHttp.HttpStatusException) { if (e.code == 400) endpoints.setMetaFormatSupport(base, false) }
                        }
                        try { StorageHttp.directFetchRange(base, messageStreamId, p0, from, to) { if (it.timestamp == annTs) found = true } } catch (e: Exception) { endpoints.noteFailure(base) }
                        if (found) break
                    }
                }
                found
            } catch (e: Exception) { false }
            if (seen) { Log.i(TAG, "Storage announce confirmed ✓"); return }
        }
        Log.i(TAG, "Storage announce NOT confirmed (~60s) — it may surface later")
    }

    // ==================== download ====================

    /**
     * Download a storage-shared file described by a [StorageFileMetadata] (from a
     * `storage_file_announce`). Reads the chunk partitions over direct HTTP,
     * decrypts (native, one key per file), stages into a sparse file, and inflates
     * to the final file when compressed. Resumable across restarts via the staging
     * bitmap. Password/public channels read over direct HTTP; DMs read via
     * SDK-resend from the receiver's own inbox instead.
     *
     * The completed file lands in [completedFile]; progress is on [downloads].
     */
    suspend fun downloadFile(
        messageStreamId: String,
        meta: StorageFileMetadata,
        announceTimestamp: Long,
        password: String?,
        isDm: Boolean = false,
        /** DM: the RECEIVER's own inbox message stream (chunks live there, not on the channel's peer-inbox id). */
        dmInboxStreamId: String? = null,
        /** DM: the SENDER's public key, to open the ECDH-sealed chunks (in the bridge). */
        peerPublicKey: String? = null,
        /**
         * Epoch channel (native/gated): opens a 0x04 chunk given its transport
         * timestamp (the gated kid-freshness rule judges old kids against the
         * announce in force at that moment). Null result = cannot open (missing
         * key already noted) — the chunk is skipped and retries re-read it.
         */
        epochOpener: ((ByteArray, Long) -> ByteArray?)? = null
    ) {
        val tid = meta.transferId
        if (isDownloading(tid)) { Log.w(TAG, "storage download already running: $tid"); return }
        if (completed.containsKey(tid)) { Log.i(TAG, "storage file already downloaded: $tid"); return }
        val total = meta.totalChunks ?: throw IllegalStateException("Announce has no chunk count")
        val chunkDataSize = meta.chunkDataSize ?: throw IllegalStateException("Announce has no chunk size")
        val compressedSize = meta.compressedSize ?: throw IllegalStateException("Announce has no compressed size")

        // DMs read from the RECEIVER'S OWN inbox via SDK resend (the inbox is
        // private at the Streamr layer, so direct HTTP would return SDK
        // ciphertext); password/public channels read the channel stream over HTTP.
        val chunkStreamId: String
        val bases: List<String>
        if (isDm) {
            chunkStreamId = dmInboxStreamId ?: throw IllegalStateException("DM download needs the own-inbox id")
            if (peerPublicKey.isNullOrEmpty()) throw IllegalStateException("DM download needs the sender's public key")
            bases = emptyList()
        } else {
            chunkStreamId = messageStreamId
            bases = try { endpoints.rotation(messageStreamId) } catch (e: Exception) { emptyList() }
            if (bases.isEmpty()) throw IllegalStateException("No storage endpoint available to download from")
            Log.i(TAG, "Storage download endpoints (${bases.size}): ${bases.joinToString()}")
        }

        // Opener: DM chunks arrive already ECDH-decrypted from the bridge (identity
        // here); epoch channels -> the caller's kid-resolving opener; password
        // channels -> per-file key (one PBKDF2); public -> identity.
        val opener: (ByteArray, Long) -> ByteArray = when {
            !isDm && epochOpener != null ->
                ({ c, ts -> epochOpener(c, ts) ?: throw IllegalStateException("epoch chunk did not open") })
            !isDm && !password.isNullOrEmpty() && !meta.encSalt.isNullOrEmpty() -> {
                val salt = Base64.decode(meta.encSalt, Base64.NO_WRAP)
                val key = PomboCrypto.deriveKeyWithSalt(password, salt)
                ({ c, _ -> PomboCrypto.decryptBinaryWithKey(c, key) })
            }
            else -> ({ c, _ -> c })
        }

        val isCompressed = meta.compression != "none" && meta.compression.isNotEmpty()
        val staging = withContext(Dispatchers.IO) {
            StorageStagingStore.open(transferDir().also { it.mkdirs() }, tid, compressedSize, chunkDataSize, total)
        }

        var status = "downloading"
        var bytesReceived = staging.completedChunks().toLong() * chunkDataSize
        val recvHist = ArrayDeque<Pair<Long, Long>>()   // (perfMs, bytesReceived)
        var phase: String? = null
        fun emitDl() {
            val done = staging.completedChunks()
            val rate = synchronized(recvHist) { windowedRate(recvHist, perf()) }
            // ETA from the byte rate and the average chunk size (received are
            // compressed chunks, so scale by chunkDataSize, not originalSize).
            val eta = if (rate != null && rate > 0 && done < total) (total - done).toLong() * chunkDataSize / rate else null
            _downloads.update {
                it + (tid to DownloadProgress(
                    tid, status, if (total > 0) done * 100 / total else 0, done, total,
                    bytesReceived, meta.originalSize, rate, phase, null, eta
                ))
            }
        }
        var lastEmitDl = 0L
        fun emitDlThrottled() { val now = perf(); if (now - lastEmitDl >= 150) { lastEmitDl = now; emitDl() } }

        // Row handler: synchronous (writeChunk + StateFlow update are non-suspend),
        // so it doubles as read-side backpressure — the HTTP parser blocks on it.
        val downloadJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        val onRow: (StorageHttp.Row) -> Unit = onRow@{ row ->
            // The reads are blocking HTTP with no suspension points, so a
            // cancelled Job (pause/cancel) needs a synchronous escape hatch —
            // this throw aborts the window mid-read instead of draining it.
            if (downloadJob?.isActive == false) {
                throw kotlinx.coroutines.CancellationException("storage download cancelled")
            }
            val c = row.content ?: return@onRow
            val opened = try { opener(c, row.timestamp) } catch (e: Exception) { return@onRow }   // warm-up ping / foreign / tampered / key not held yet
            val u = StorageWire.unpackChunkPayload(opened) ?: return@onRow
            if (u.transferId != tid || u.chunkIndex >= total) return@onRow
            if (staging.hasChunk(u.chunkIndex)) return@onRow
            if (staging.writeChunk(u.chunkIndex, u.chunkData)) {
                bytesReceived += u.chunkData.size
                val now = perf()
                synchronized(recvHist) {
                    recvHist.addLast(now to bytesReceived)
                    while (recvHist.size > 2 && recvHist.first().first < now - StorageMediaConfig.RATE_WINDOW_MS) recvHist.removeFirst()
                }
                emitDlThrottled()
            }
        }

        _downloads.update { it + (tid to DownloadProgress(tid, status, 0, staging.completedChunks(), total, bytesReceived, meta.originalSize, null, null)) }

        val dlStart = perf()
        try {
            val margin = max(total.toLong() * 600 + 10000, 300000)
            val fromT = meta.firstChunkTs?.minus(30000) ?: (announceTimestamp - margin)
            val toT = announceTimestamp + 60000
            fun missing() = staging.missingChunks()
            fun windowsFor(m: List<Int>) = StorageWindows.buildMissingWindows(
                meta.firstChunkTs, meta.lastChunkTs, total, meta.firstChunkPartition, meta.chunkPartitions, m, fromT, toT
            )

            suspend fun fetch(windows: List<Win>) {
                if (isDm) fetchWindowsResendDm(chunkStreamId, windows, onRow, peerPublicKey!!)
                else fetchWindowsParallel(chunkStreamId, windows, onRow, bases)
            }

            val resumeN = staging.completedChunks()
            if (resumeN < total) {
                val windows = if (resumeN > 0 && resumeN >= total * 0.3) windowsFor(missing())
                    else StorageWindows.chunkWindowsFor(meta.firstChunkPartition, meta.chunkPartitions, fromT, toT)
                fetch(windows)
            }

            var pass = 0
            while (staging.completedChunks() < total && pass < StorageMediaConfig.DOWNLOAD_RETRY_PASSES) {
                pass++
                delay(2000L * pass)
                val before = staging.completedChunks()
                val m = missing()
                Log.w(TAG, "Storage download retry $pass/${StorageMediaConfig.DOWNLOAD_RETRY_PASSES}: ${m.size} chunks missing")
                fetch(windowsFor(m))
                if (staging.completedChunks() == before) {
                    Log.w(TAG, "Storage retry made no progress — storage does not have the missing chunks")
                    break
                }
            }

            if (staging.completedChunks() == total) {
                val fetchMs = perf() - dlStart
                val fetchKBs = if (fetchMs > 0) compressedSize * 1000 / fetchMs / 1024 else 0
                Log.i(TAG, "Storage download fetch: $total chunks (${compressedSize / 1024}KB) in ${fetchMs}ms = ${fetchKBs}KB/s")
                phase = "Finishing write to disk…"; emitDl()
                val asmStart = perf()
                val outFile = withContext(Dispatchers.IO) { assemble(staging, tid, isCompressed) { p -> phase = p; emitDlThrottled() } }
                completed[tid] = outFile
                status = "complete"; phase = null
                bytesReceived = meta.originalSize
                emitDl()
                Log.i(TAG, "Storage download complete: ${meta.fileName} ($total chunks); assemble ${perf() - asmStart}ms")
            } else {
                status = "error"
                _downloads.update {
                    it + (tid to DownloadProgress(tid, status, staging.completedChunks() * 100 / max(1, total),
                        staging.completedChunks(), total, bytesReceived, meta.originalSize, null, null,
                        "Storage incomplete — some chunks are missing. Retry later."))
                }
                // Keep the partial staging on disk for a later resume.
                staging.close()
                Log.w(TAG, "Incomplete storage download: ${staging.completedChunks()}/$total preserved on disk")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // pauseStorageTransfer() cancelling this Job lands here too — not a
            // failure, so the staging stays on disk (same as the "incomplete"
            // branch above) and the status reflects a deliberate pause rather
            // than an error.
            status = "paused"
            val done = staging.completedChunks()
            _downloads.update { it + (tid to DownloadProgress(tid, status, if (total > 0) done * 100 / total else 0, done, total, bytesReceived, meta.originalSize, null, null)) }
            runCatching { staging.close() }
            throw e
        } catch (e: Exception) {
            status = "error"
            _downloads.update { it + (tid to DownloadProgress(tid, status, 0, staging.completedChunks(), total, bytesReceived, meta.originalSize, null, null, e.message)) }
            runCatching { staging.close() }
            throw e
        }
    }

    /** Assemble the staged file: inflate to a .bin output when compressed, else the staged file IS the download. */
    private fun assemble(staging: StorageStagingStore, tid: String, isCompressed: Boolean, onPhase: (String) -> Unit): File {
        if (!isCompressed) {
            staging.close() // stop writing; the .spart file is the finished download
            return staging.stagedFile()
        }
        val dir = transferDir()
        val staged = staging.stagedFile()
        val out = File(dir, "psf_$tid.bin")
        if (out.exists()) out.delete()
        staging.close()
        staged.inputStream().buffered().use { input ->
            out.outputStream().buffered().use { output ->
                var lastPct = -1
                val stagedLen = staged.length().coerceAtLeast(1)
                StorageDeflate.inflate(input, output) { produced ->
                    // Progress is only a rough guide here (inflate output can exceed input).
                    val pct = ((produced.coerceAtMost(stagedLen) * 100) / stagedLen).toInt()
                    if (pct != lastPct) { lastPct = pct; onPhase("Extracting… $pct%") }
                }
            }
        }
        StorageStagingStore.delete(dir, tid) // drop the compressed staging + bitmap
        return out
    }

    /**
     * Runs the given windows over the node rotation, [concurrency] at a time,
     * round-robining windows across bases. A per-window failure is logged and the
     * retry passes re-request through other nodes.
     */
    private suspend fun fetchWindowsParallel(sid: String, windows: List<Win>, onRow: (StorageHttp.Row) -> Unit, bases: List<String>) {
        if (windows.isEmpty() || bases.isEmpty()) return
        val next = AtomicInteger(0)
        coroutineScope {
            repeat(min(StorageMediaConfig.DOWNLOAD_CONCURRENCY, windows.size)) {
                launch(Dispatchers.IO) {
                    // isActive, not `true`: the reads below are BLOCKING HTTP —
                    // no suspension points — so a cancelled Job (pause/cancel)
                    // would otherwise grind through every remaining window
                    // before the cancellation could take effect. The row
                    // handler in downloadFile aborts mid-window for the same
                    // reason; this check catches the between-windows gap.
                    while (isActive) {
                        val idx = next.getAndIncrement()
                        if (idx >= windows.size) break
                        val w = windows[idx]
                        val base = bases[idx % bases.size]
                        try {
                            StorageHttp.directFetchRange(base, sid, w.partition, w.from, w.to, onRow)
                            endpoints.noteSuccess(base)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Not an endpoint failure — the read was aborted
                            // on purpose (see downloadFile's row handler).
                            throw e
                        } catch (e: Exception) {
                            endpoints.noteFailure(base)
                            Log.w(TAG, "storage download window P${w.partition} @ $base failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * DM download reads: resend each window from the receiver's own inbox through
     * the bridge, which SDK-decrypts and ECDH-opens each chunk and hands back the
     * packed bytes (base64). Sequential — a window can carry many chunks and each
     * response is large. [onRow] gets the already-opened packed chunk as content.
     */
    private suspend fun fetchWindowsResendDm(inboxStreamId: String, windows: List<Win>, onRow: (StorageHttp.Row) -> Unit, peerPublicKey: String) {
        // Rows cross the bridge SEALED; the pair key opens them here — the
        // ECDH decrypt IS the integrity check, so a foreign/tampered row
        // simply fails to open and is skipped.
        val myPk = myPrivateKey() ?: return
        val pairKey = SealedSenderCrypto.pairKey(myPk, peerPublicKey) ?: return
        for (w in windows) {
            try {
                val res = bridge.call(
                    "resendStorageChunksDm",
                    JSONObject().put("streamId", inboxStreamId).put("partition", w.partition)
                        .put("fromTs", w.from).put("toTs", w.to),
                    timeoutMs = 180_000
                )
                val rows = res.optJSONArray("rows") ?: continue
                for (i in 0 until rows.length()) {
                    val r = rows.optJSONObject(i) ?: continue
                    val b64 = r.optString("data")
                    if (b64.isEmpty()) continue
                    val sealed = try { Base64.decode(b64, Base64.NO_WRAP) } catch (e: Exception) { continue }
                    val bytes = SealedSenderCrypto.pairOpen(sealed, pairKey) ?: continue
                    val pub = if (r.isNull("publisherId")) null else r.optString("publisherId")
                    onRow(StorageHttp.Row(bytes, r.optLong("timestamp"), pub))
                }
            } catch (e: Exception) {
                Log.w(TAG, "DM storage resend window P${w.partition} failed: ${e.message}")
            }
        }
    }

    private suspend fun publishChunkWithRetry(sid: String, partition: Int, payload: ByteArray, identityPk: String? = null, gateAddress: String? = null, asAccount: Boolean = false, attempts: Int = 3): Long {
        var last: Exception? = null
        for (a in 0 until attempts) {
            try { return bridge.publishStorageChunk(sid, partition, payload, identityPk, gateAddress, asAccount) }
            catch (e: Exception) { last = e; delay(500L * (a + 1)) }
        }
        throw last ?: IllegalStateException("publish failed")
    }

    // ==================== progress plumbing ====================

    private class UploadState(val tid: String, val fileSize: Long) {
        var stage = "processing"; var phase = "Processing…"; var percent = 0
        var bytesSent = 0L; var totalBytes: Long? = null
        var instBps: Long? = null; var avgBps: Long? = null; var etaSec: Long? = null
        var processingPct = 0; var doneCount = 0; var error: String? = null
        fun snapshot() = UploadProgress(tid, stage, phase, percent, bytesSent, totalBytes, fileSize, instBps, avgBps, etaSec, processingPct, error)
    }

    @Volatile private var lastEmit = 0L
    private fun emit(up: UploadState) = setProgress(up.snapshot())
    private fun emitThrottled(up: UploadState) { val now = perf(); if (now - lastEmit >= 150) { lastEmit = now; setProgress(up.snapshot()) } }
    private fun setProgress(p: UploadProgress) = _uploads.update { it + (p.transferId to p) }
    private fun clearProgress(tid: String) = _uploads.update { it - tid }

    /** File-backed random-access slicer for the compressed staging file. */
    private class FileSlicer(file: File) : Source.Slicer {
        private val raf = RandomAccessFile(file, "r")
        override fun readAt(offset: Long, length: Int): ByteArray {
            val buf = ByteArray(length)
            synchronized(raf) { raf.seek(offset); raf.readFully(buf) }
            return buf
        }
        override fun close() = raf.close()
    }
}

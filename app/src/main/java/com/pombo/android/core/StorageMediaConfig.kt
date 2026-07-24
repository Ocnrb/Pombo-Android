package com.pombo.android.core

/**
 * Persistent File Sharing (storage-node transport) tuning — mirrors the web's
 * `CONFIG.storageMedia` (src/js/config.js). Separate from [MediaConfig], which
 * tunes the live P2P mesh transport; the two coexist and never share knobs.
 *
 * Wire-shaping constants that both ends must agree on (chunk size, chunk
 * partition layout) live in [StreamConstants], not here — this file is local
 * policy only.
 */
object StorageMediaConfig {

    /**
     * How long a resolved storage-endpoint set is cached per stream. Storage-node
     * assignments change rarely (an on-chain write), so a 10-minute TTL spares
     * two SDK round-trips per transfer without pinning a stale set for long.
     */
    const val ENDPOINT_CACHE_TTL_MS = 10L * 60L * 1000L

    /**
     * Consecutive read failures before a node URL is ejected from the rotation
     * for the session. A single success resets the count. The Pombo cluster
     * registers one node with two URLs, so ejecting one still leaves the other.
     */
    const val NODE_FAILURE_LIMIT = 3

    // ===== Upload =====

    /** Max wire chunk (browser SCTP ceiling — kept for interop). Seal overhead comes OUT of this. */
    const val CHUNK_KB = 240

    /** Base send spacing before auto-tune takes over (web throttleMs). */
    const val THROTTLE_MS = 100L

    /** Concurrent publish workers. 1 = paced-serial, exactly like the web default; auto-tune shapes the RATE, not the width. */
    const val PARALLEL = 1

    /** Auto-tune oscillator (search/cut/drain/climb/hold). */
    const val AUTOTUNE = true

    /** Verify persistence by timestamp after publishing; repair what is missing. */
    const val VERIFY = true

    /** Pre-repair drain: re-read while the cluster catches up before republishing. */
    const val SECOND_CHANCE = true

    /**
     * MP4 faststart remux on upload is DEFERRED on Android (brief §6): the web
     * gates it behind a flag and the owner flagged it as flaky. Files upload
     * as-is; nothing downstream depends on the faststart layout.
     */
    const val FASTSTART = false

    /** Sliding window for the displayed transfer rate / ETA. */
    const val RATE_WINDOW_MS = 10_000L

    /** Wait after warm-up pings so the partition overlays are joined before real chunks. */
    const val WARMUP_WAIT_MS = 2_000L

    /** Repair passes over chunks still missing after the verify sweep. */
    const val DOWNLOAD_RETRY_PASSES = 3

    // ===== Download =====

    /** Parallel window reads on download (web concurrencyMobile). */
    const val DOWNLOAD_CONCURRENCY = 3
}

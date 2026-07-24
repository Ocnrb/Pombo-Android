package com.pombo.android.core

/**
 * P2P transfer tuning — mirrors the web's `CONFIG.media` (src/js/config.js).
 *
 * [PIECE_SIZE] is a PROTOCOL constant, not a preference: it decides where every
 * piece boundary falls, so a sender and a receiver that disagree produce
 * hashes that never match. The rest are local policy and may be tuned freely.
 */
object MediaConfig {

    /** 220 KB. Changing this breaks every in-flight transfer with a web peer. */
    const val PIECE_SIZE = 220 * 1024

    /** How long a piece request may go unanswered before it is retried. */
    const val PIECE_REQUEST_TIMEOUT_MS = 5_000L

    /**
     * Adaptive request window (AIMD). Transfers are pull-based — the leecher
     * asks named seeders for specific pieces — so the number of outstanding
     * requests IS the flow control: the request is the credit. Grows by one per
     * delivered piece, halves on a timeout.
     */
    const val WINDOW_START = 8
    const val WINDOW_MIN = 4
    const val WINDOW_MAX = 64

    /**
     * A stall usually times out several in-flight pieces at once. Without a
     * cooldown each of them would halve the window independently and slam it
     * shut on a single event.
     */
    const val WINDOW_BACKOFF_COOLDOWN_MS = 5_000L

    /** Give up on a piece after this many bad deliveries — stops retry loops. */
    const val MAX_PIECE_FAILURES = 5

    /** Drop a seeder from this transfer after this many bad pieces. */
    const val MAX_SEEDER_STRIKES = 3

    /** Sliding window for the displayed transfer rate. */
    const val SPEED_WINDOW_MS = 10_000L

    /** A leecher that has not asked for this long is dropped from the count. */
    const val LEECHER_TIMEOUT_MS = 15_000L

    /** Throttle for upload stat updates (one per piece would be a flood). */
    const val UPLOAD_STATS_INTERVAL_MS = 500L

    /** 500MB — web maxFileSize. */
    const val MAX_FILE_SIZE = 500L * 1024 * 1024

    /**
     * Web ALLOWED_VIDEO_TYPES — the only video-specific rule in the whole
     * pipeline; hashing, pieces, announce and seeding are type-agnostic.
     */
    val ALLOWED_VIDEO_TYPES = setOf(
        "video/mp4", "video/webm", "video/quicktime", "video/x-m4v", "video/ogg"
    )

    /** Re-ask who has the file while a download has no usable seeder. */
    const val SEEDER_REFRESH_INTERVAL_MS = 10_000L

    /** Length of piece [index] for a file of [fileSize] — the last one is short. */
    fun pieceLength(fileSize: Long, index: Int): Int {
        val start = index.toLong() * PIECE_SIZE
        if (start >= fileSize) return 0
        return minOf(PIECE_SIZE.toLong(), fileSize - start).toInt()
    }

    /** Number of pieces a file of [fileSize] is cut into. */
    fun pieceCount(fileSize: Long): Int =
        ((fileSize + PIECE_SIZE - 1) / PIECE_SIZE).toInt()
}

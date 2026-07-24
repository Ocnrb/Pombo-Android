package com.pombo.android.core

/**
 * Triple-stream architecture PROTOCOL constants (exact mirror of
 * Pombo web src/js/streamConstants.js) — changing them breaks
 * interoperability with already-published messages.
 *
 * Each channel derives up to 3 streams from the base ID by suffix:
 *   -1 -> messages (with storage)  -2 -> ephemeral (no storage)  -3 -> admin
 */
object StreamConstants {
    const val SUFFIX_MESSAGE = "-1"
    const val SUFFIX_EPHEMERAL = "-2"
    const val SUFFIX_ADMIN = "-3"

    // Message stream (-1)
    //
    // Regular channels use 11 partitions: P0 content, P1 control overrides,
    // P2-P10 storage-file chunks. DM inboxes use 13: P0 messages, P1 sync,
    // P2 sync_blobs, P3 notifications, P4-P12 storage-file chunks. The 9 chunk
    // partitions carry Persistent File Sharing payloads (uploaded to the
    // channel's storage nodes, read via storage HTTP/resend — NEVER subscribed
    // live). See [STORAGE_FILE] and [storageChunkPartition].
    const val MSG_PARTITIONS = 11       // regular channels: content + control + 9 chunk partitions
    const val MSG_DM_PARTITIONS = 13    // inbox DM: msgs + sync + sync_blobs + notifs + 9 chunk partitions
    const val P_MESSAGES = 0            // text, reactions, images, announcements
    const val P_CONTROL = 1             // edit/delete overrides
    const val P_SYNC = 1                // cross-device sync (DM inbox only)
    const val P_SYNC_BLOBS = 2          // image blobs (DM inbox only)
    const val P_NOTIFICATIONS = 3       // invites/notifications (DM inbox only)

    // Persistent File Sharing over storage nodes (message stream -1 chunk
    // partitions). Chunk i round-robins over 9 partitions starting right after
    // the last "classic" partition of the stream flavor: P2 on regular channels,
    // P4 on DM inboxes. Nine partitions exist for storage-node resend efficiency
    // (small buckets, parallel per-partition reads), NOT throughput. The
    // announcement is a normal signed chat message on P0 and carries
    // firstChunkPartition/chunkPartitions, so readers follow the announce, not
    // these local constants. (Mirror of streamConstants.js STORAGE_FILE.)
    const val STORAGE_CHUNK_PARTITIONS = 9
    const val STORAGE_FIRST_CHUNK_PARTITION = 2      // regular channels (after P0 msgs + P1 control)
    const val STORAGE_DM_FIRST_CHUNK_PARTITION = 4   // DM inboxes (after P0-P3)

    /**
     * Partition for storage-file chunk [i].
     *
     * @param i chunk index
     * @param firstPartition first chunk partition (2 regular / 4 DM, or from an announce)
     * @param count number of chunk partitions (default 9, or from an announce)
     */
    fun storageChunkPartition(
        i: Int,
        firstPartition: Int,
        count: Int = STORAGE_CHUNK_PARTITIONS
    ): Int = firstPartition + (i % count)

    // Ephemeral stream (-2)
    const val EPH_PARTITIONS = 3
    const val EPH_CONTROL = 0           // presence, typing (JSON)
    const val EPH_MEDIA_SIGNALS = 1     // P2P coordination (JSON)
    const val EPH_MEDIA_DATA = 2        // binary payloads (file_piece)

    // Stream admin (-3)
    const val ADMIN_PARTITIONS = 3
    const val ADMIN_MODERATION = 0      // ADMIN_STATE: bans, hidden, pins
    const val ADMIN_CHANNEL_IMAGE = 1
    const val ADMIN_PASSWORD_CHALLENGE = 2

    const val PASSWORD_CHALLENGE_MAGIC = "POMBO_PWD_CHALLENGE_V1"

    // History windows (web config.js: stream.initialMessages / loadMoreCount)
    const val INITIAL_MESSAGES = 50
    const val LOAD_MORE_COUNT = 50

    fun deriveEphemeralId(messageStreamId: String): String =
        messageStreamId.replace(Regex("-1$"), SUFFIX_EPHEMERAL)

    fun deriveAdminId(messageStreamId: String): String =
        messageStreamId.replace(Regex("-1$"), SUFFIX_ADMIN)

    /**
     * Inverse of [deriveEphemeralId]. Media signals and pieces arrive addressed
     * to the ephemeral stream, but transfers are tracked by the channel's
     * message stream id (the key everything else uses), so the routing has to
     * map back. Works for DM inboxes too: `<addr>/Pombo-DM-2` -> `-DM-1`.
     */
    fun deriveMessageId(ephemeralStreamId: String): String =
        ephemeralStreamId.replace(Regex("-2$"), SUFFIX_MESSAGE)

    fun isEphemeralStream(streamId: String): Boolean = streamId.endsWith(SUFFIX_EPHEMERAL)

    fun isMessageStream(streamId: String): Boolean = streamId.endsWith(SUFFIX_MESSAGE)
}

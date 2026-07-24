package com.pombo.android.core

/**
 * Storage-node read windows — native mirror of the web storageMedia.js window
 * helpers (chunkWindowsFor / mergeWindows / buildMissingWindows /
 * partitionOfChunk). A "window" is a `{partition, from, to}` time range read
 * from a storage node; a download or a verify sweep issues one or more of these
 * and matches rows back to chunk indices.
 *
 * Pure and deterministic, so the estimate logic is unit-tested without a network.
 */
object StorageWindows {

    data class Window(val partition: Int, val from: Long, val to: Long)

    /** Partition holding chunk [i] per an announce's layout (reader trusts the announce). */
    fun partitionOfChunk(i: Int, firstPartition: Int, chunkPartitions: Int): Int =
        StreamConstants.storageChunkPartition(i, firstPartition, chunkPartitions)

    /** One window per chunk partition, all over the same [fromT]..[toT] range. */
    fun chunkWindowsFor(firstPartition: Int, chunkPartitions: Int, fromT: Long, toT: Long): List<Window> =
        (0 until chunkPartitions).map { Window(firstPartition + it, fromT, toT) }

    /** Merge overlapping same-partition windows. Input need not be sorted. */
    fun mergeWindows(raw: List<Window>): List<Window> {
        val sorted = raw.sortedWith(compareBy({ it.partition }, { it.from }))
        val out = ArrayList<Window>()
        for (w in sorted) {
            val last = out.lastOrNull()
            if (last != null && w.partition == last.partition && w.from <= last.to) {
                out[out.size - 1] = last.copy(to = maxOf(last.to, w.to))
            } else out.add(w)
        }
        return out
    }

    /**
     * Narrow re-request windows for [missing] chunks, estimated linearly between
     * the announced first/last chunk timestamps, merged per partition, plus a
     * tail window per partition for chunks republished by verify & repair (whose
     * real timestamps sit after lastChunkTs). Falls back to full per-partition
     * ranges when the estimate cannot help (no timestamps, single chunk, or more
     * than 30% missing — at that point narrow windows save nothing).
     */
    fun buildMissingWindows(
        firstChunkTs: Long?, lastChunkTs: Long?, totalChunks: Int,
        firstPartition: Int, chunkPartitions: Int,
        missing: List<Int>, fromT: Long, toT: Long
    ): List<Window> {
        if (firstChunkTs == null || lastChunkTs == null || totalChunks <= 1 || missing.size > totalChunks * 0.3) {
            return chunkWindowsFor(firstPartition, chunkPartitions, fromT, toT)
        }
        val interval = (lastChunkTs - firstChunkTs).toDouble() / (totalChunks - 1)
        val pad = 30_000L
        val raw = missing.map { i ->
            val est = firstChunkTs + (i * interval).toLong()
            Window(partitionOfChunk(i, firstPartition, chunkPartitions), est - pad, est + pad)
        }
        val windows = ArrayList(mergeWindows(raw))
        val tailParts = missing.map { partitionOfChunk(it, firstPartition, chunkPartitions) }.toSet()
        for (p in tailParts) windows.add(Window(p, lastChunkTs - 5000, toT))
        return windows
    }
}

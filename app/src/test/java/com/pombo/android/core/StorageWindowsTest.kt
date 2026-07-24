package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageWindowsTest {

    @Test
    fun `partitionOfChunk round-robins from the first partition`() {
        assertEquals(2, StorageWindows.partitionOfChunk(0, 2, 9))
        assertEquals(10, StorageWindows.partitionOfChunk(8, 2, 9))
        assertEquals(2, StorageWindows.partitionOfChunk(9, 2, 9))
        assertEquals(4, StorageWindows.partitionOfChunk(0, 4, 9)) // DM inbox layout
    }

    @Test
    fun `chunkWindowsFor is one window per partition over the same range`() {
        val ws = StorageWindows.chunkWindowsFor(2, 9, 100L, 200L)
        assertEquals(9, ws.size)
        assertEquals((2..10).toList(), ws.map { it.partition })
        assertTrue(ws.all { it.from == 100L && it.to == 200L })
    }

    @Test
    fun `mergeWindows merges overlapping same-partition windows only`() {
        val merged = StorageWindows.mergeWindows(
            listOf(
                StorageWindows.Window(2, 0, 100),
                StorageWindows.Window(2, 50, 150),   // overlaps -> merges to [0,150]
                StorageWindows.Window(2, 400, 500),  // disjoint -> separate
                StorageWindows.Window(3, 0, 100)     // different partition -> separate
            )
        )
        assertEquals(
            listOf(
                StorageWindows.Window(2, 0, 150),
                StorageWindows.Window(2, 400, 500),
                StorageWindows.Window(3, 0, 100)
            ),
            merged
        )
    }

    @Test
    fun `buildMissingWindows falls back to full ranges when estimate cannot help`() {
        // No timestamps.
        assertEquals(9, StorageWindows.buildMissingWindows(null, null, 100, 2, 9, listOf(1, 2), 0, 999).size)
        // Single chunk.
        assertEquals(9, StorageWindows.buildMissingWindows(10, 20, 1, 2, 9, listOf(0), 0, 999).size)
        // More than 30% missing.
        val many = (0 until 40).toList()
        assertEquals(9, StorageWindows.buildMissingWindows(10, 20, 100, 2, 9, many, 0, 999).size)
    }

    @Test
    fun `buildMissingWindows estimates narrow windows plus a per-partition tail`() {
        // 101 chunks from ts 1000 to 11000 -> interval 100ms/chunk. Miss chunk 50 only.
        val first = 1000L; val last = 11000L; val total = 101
        val windows = StorageWindows.buildMissingWindows(first, last, total, 2, 9, listOf(50), 0, 20000)
        // Chunk 50 -> partition 2 + 50%9 = 2+5 = 7; est ts = 1000 + 50*100 = 6000; PAD 30000.
        val p = StorageWindows.partitionOfChunk(50, 2, 9)
        assertEquals(7, p)
        // A narrow estimate window around 6000 and a tail window from lastChunkTs-5000.
        assertTrue(windows.any { it.partition == p && it.from <= 6000L && it.to >= 6000L })
        assertTrue(windows.any { it.partition == p && it.from == last - 5000 && it.to == 20000L })
    }
}

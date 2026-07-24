package com.pombo.android.core

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageStagingStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // stagedSize 1000, chunkDataSize 400 -> chunks 400, 400, 200.
    private fun store(id: String = "tid-1") =
        StorageStagingStore.open(tmp.root, id, stagedSize = 1000, chunkDataSize = 400, totalChunks = 3)

    @Test
    fun `chunkLength is uniform except the short final chunk`() {
        val s = store()
        assertEquals(400, s.chunkLength(0))
        assertEquals(400, s.chunkLength(1))
        assertEquals(200, s.chunkLength(2))
        s.close()
    }

    @Test
    fun `writes chunks at their offsets and exports the staged bytes`() {
        val s = store()
        val c0 = ByteArray(400) { 1 }
        val c1 = ByteArray(400) { 2 }
        val c2 = ByteArray(200) { 3 }
        assertTrue(s.writeChunk(0, c0))
        assertTrue(s.writeChunk(2, c2))
        assertFalse(s.isComplete())
        assertEquals(listOf(1), s.missingChunks())
        assertTrue(s.writeChunk(1, c1))
        assertTrue(s.isComplete())
        assertEquals(3, s.completedChunks())

        val out = ByteArrayOutputStream()
        s.exportTo(out)
        val expected = c0 + c1 + c2
        assertArrayEquals(expected, out.toByteArray())
        s.close()
    }

    @Test
    fun `rejects a wrong-length chunk`() {
        val s = store()
        assertFalse(s.writeChunk(0, ByteArray(399)))  // too short
        assertFalse(s.writeChunk(2, ByteArray(400)))  // last chunk is 200
        assertFalse(s.hasChunk(0))
        s.close()
    }

    @Test
    fun `rejects out-of-range indices`() {
        val s = store()
        assertFalse(s.writeChunk(-1, ByteArray(400)))
        assertFalse(s.writeChunk(3, ByteArray(400)))
        s.close()
    }

    @Test
    fun `resumes from the on-disk bitmap after reopen`() {
        val s1 = store("resume-1")
        s1.writeChunk(0, ByteArray(400) { 7 })
        s1.close()

        val s2 = store("resume-1")
        assertTrue(s2.hasChunk(0))
        assertFalse(s2.hasChunk(1))
        assertEquals(1, s2.completedChunks())
        assertEquals(listOf(1, 2), s2.missingChunks())
        s2.close()
    }

    @Test
    fun `staged file survives close for the non-compressed download path`() {
        val s = store("staged-1")
        s.writeChunk(0, ByteArray(400) { 9 })
        val f = s.stagedFile()
        s.close()
        assertTrue(f.exists())
        assertEquals(1000L, f.length())
    }
}

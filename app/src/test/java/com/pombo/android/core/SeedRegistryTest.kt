package com.pombo.android.core

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SeedRegistryTest {

    private lateinit var dir: File
    private lateinit var registry: SeedRegistry

    @Before
    fun setUp() {
        dir = File.createTempFile("seedreg", null).apply { delete(); mkdirs() }
        registry = SeedRegistry { dir }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun entry(
        fileId: String = "aaaaaaaa-0000-0000-0000-000000000001",
        streamId: String = "0xabc/chan-1",
        completedAt: Long = System.currentTimeMillis()
    ) = SeedRegistry.Entry(
        fileId = fileId,
        messageStreamId = streamId,
        ephemeralStreamId = "0xabc/chan-2",
        isDm = false,
        pieceCount = 3,
        fileSize = 3L * 220 * 1024,
        fileName = "file.bin",
        completedAt = completedAt
    )

    @Test
    fun `recorded entries survive a fresh instance`() {
        registry.record(entry())
        val reloaded = SeedRegistry { dir }
        assertEquals(1, reloaded.all().size)
        assertEquals("file.bin", reloaded.all()[0].fileName)
        assertEquals(3L * 220 * 1024, reloaded.all()[0].fileSize)
    }

    @Test
    fun `remove drops the entry durably`() {
        registry.record(entry())
        registry.remove(entry().fileId)
        assertTrue(SeedRegistry { dir }.all().isEmpty())
    }

    @Test
    fun `re-recording the same fileId overwrites, not duplicates`() {
        registry.record(entry())
        registry.record(entry().copy(fileName = "renamed.bin"))
        assertEquals(1, registry.all().size)
        assertEquals("renamed.bin", registry.all()[0].fileName)
    }

    @Test
    fun `entriesFor filters by stream`() {
        registry.record(entry(fileId = "aaaaaaaa-0000-0000-0000-000000000001", streamId = "0xabc/one-1"))
        registry.record(entry(fileId = "aaaaaaaa-0000-0000-0000-000000000002", streamId = "0xabc/two-1"))
        val one = registry.entriesFor("0xabc/one-1")
        assertEquals(1, one.size)
        assertEquals("aaaaaaaa-0000-0000-0000-000000000001", one[0].fileId)
    }

    @Test
    fun `sweepExpired removes only entries past the policy and returns them`() {
        val now = System.currentTimeMillis()
        val old = entry(
            fileId = "aaaaaaaa-0000-0000-0000-00000000dead",
            completedAt = now - SeedRegistry.EXPIRE_MS - 1
        )
        val fresh = entry(fileId = "aaaaaaaa-0000-0000-0000-00000000feed", completedAt = now)
        registry.record(old)
        registry.record(fresh)

        val swept = registry.sweepExpired(now)

        assertEquals(listOf(old.fileId), swept.map { it.fileId })
        assertEquals(listOf(fresh.fileId), registry.all().map { it.fileId })
        // And durably so.
        assertEquals(listOf(fresh.fileId), SeedRegistry { dir }.all().map { it.fileId })
    }

    @Test
    fun `a corrupt registry file loads as empty instead of throwing`() {
        File(dir, "seeds.json").writeText("{not json")
        assertTrue(SeedRegistry { dir }.all().isEmpty())
    }

    @Test
    fun `a corrupt registry recovers on the next record`() {
        File(dir, "seeds.json").writeText("garbage")
        registry.record(entry())
        assertEquals(1, SeedRegistry { dir }.all().size)
    }

    @Test
    fun `entries with impossible sizes are skipped on load`() {
        registry.record(entry())
        // Hand-craft a bad sibling entry in the same file.
        val raw = File(dir, "seeds.json").readText()
        val doctored = raw.replace(
            "\"seeds\":[",
            "\"seeds\":[{\"fileId\":\"bad\",\"messageStreamId\":\"s\"," +
                "\"ephemeralStreamId\":\"e\",\"pieceCount\":0,\"fileSize\":0},"
        )
        File(dir, "seeds.json").writeText(doctored)
        val reloaded = SeedRegistry { dir }
        assertEquals(1, reloaded.all().size)
        assertEquals(entry().fileId, reloaded.all()[0].fileId)
    }
}

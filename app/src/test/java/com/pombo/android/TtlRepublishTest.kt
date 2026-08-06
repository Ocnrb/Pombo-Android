package com.pombo.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChannelManager.shouldRepublish — the TTL-aware republish decision for the
 * -3 artifacts on owner open (docs/TTL_REPUBLISH_PLAN.md). Mirrors the web's
 * ttlRepublish.test.js thresholds so the two implementations cannot drift:
 * 180d retention with the 0.8 fraction flips at exactly 144 days.
 */
class TtlRepublishTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test
    fun `missing or invalid artifact ts never republishes`() {
        assertFalse(ChannelManager.shouldRepublish(0L, 180, now))
        assertFalse(ChannelManager.shouldRepublish(-5L, 180, now))
    }

    @Test
    fun `missing or invalid storageDays never republishes`() {
        assertFalse(ChannelManager.shouldRepublish(now - 100 * day, null, now))
        assertFalse(ChannelManager.shouldRepublish(now - 100 * day, 0, now))
        assertFalse(ChannelManager.shouldRepublish(now - 100 * day, -1, now))
    }

    @Test
    fun `fresh artifacts stay quiet`() {
        assertFalse(ChannelManager.shouldRepublish(now - 10 * day, 180, now))
        assertFalse(ChannelManager.shouldRepublish(now - 143 * day, 180, now))
    }

    @Test
    fun `artifacts past the age fraction republish`() {
        assertTrue(ChannelManager.shouldRepublish(now - 145 * day, 180, now))
        assertTrue(ChannelManager.shouldRepublish(now - 179 * day, 180, now))
        // Even past the TTL (still retained until the purge runs)
        assertTrue(ChannelManager.shouldRepublish(now - 500 * day, 180, now))
    }

    @Test
    fun `strict at the exact threshold`() {
        assertFalse(ChannelManager.shouldRepublish(now - 144 * day, 180, now))
    }

    @Test
    fun `scales with short TTLs`() {
        // 1d TTL -> threshold at 0.8 days
        assertFalse(ChannelManager.shouldRepublish(now - day / 2, 1, now))
        assertTrue(ChannelManager.shouldRepublish(now - 9 * day / 10, 1, now))
    }
}

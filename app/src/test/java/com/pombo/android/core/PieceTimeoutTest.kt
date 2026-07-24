package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PieceTimeoutTest {

    @Test
    fun `starts at the initial value before any measurement`() {
        val t = PieceTimeout(initialMs = 5_000, minMs = 3_000, maxMs = 60_000)
        assertEquals(5_000L, t.currentMs())
        assertNull(t.smoothedRttMs())
    }

    @Test
    fun `first sample seeds the estimator`() {
        val t = PieceTimeout()
        t.onSample(1_000)
        // RFC 6298 rule 2.2: srtt = r, rttvar = r/2, so rto = r + 4*(r/2) = 3r.
        assertEquals(1_000L, t.smoothedRttMs())
        assertEquals(3_000L, t.currentMs())
    }

    /**
     * The case that motivated this class: a link where pieces reliably take
     * about seven seconds. A fixed 5s timeout declares every one of them failed
     * moments before it lands; the estimator has to settle ABOVE the real time.
     */
    @Test
    fun `settles above a steady round-trip time`() {
        val t = PieceTimeout()
        repeat(30) { t.onSample(7_000) }
        val rtt = t.smoothedRttMs()!!
        assertTrue("srtt $rtt should track 7000", rtt in 6_800..7_200)
        assertTrue("rto ${t.currentMs()} must exceed the real rtt", t.currentMs() > 7_000)
    }

    @Test
    fun `a jittery link gets a wider margin than a steady one`() {
        val steady = PieceTimeout()
        val jittery = PieceTimeout()
        repeat(30) { steady.onSample(5_000) }
        // Same mean, wildly different spread.
        repeat(30) { jittery.onSample(if (it % 2 == 0) 1_000L else 9_000L) }
        assertTrue(
            "jittery ${jittery.currentMs()} should allow more slack than steady ${steady.currentMs()}",
            jittery.currentMs() > steady.currentMs()
        )
    }

    @Test
    fun `doubles on each timeout`() {
        val t = PieceTimeout(initialMs = 5_000, minMs = 3_000, maxMs = 60_000)
        t.onTimeout()
        assertEquals(10_000L, t.currentMs())
        t.onTimeout()
        assertEquals(20_000L, t.currentMs())
    }

    @Test
    fun `backoff is cleared by a successful sample`() {
        val t = PieceTimeout(initialMs = 5_000)
        t.onTimeout()
        t.onTimeout()
        assertEquals(20_000L, t.currentMs())
        // The path is working again — holding the inflated timeout would make
        // every later stall take four times as long to notice.
        t.onSample(1_000)
        assertEquals(3_000L, t.currentMs())
    }

    @Test
    fun `never exceeds the ceiling`() {
        val t = PieceTimeout(initialMs = 5_000, maxMs = 30_000)
        repeat(20) { t.onTimeout() }
        assertEquals(30_000L, t.currentMs())
    }

    @Test
    fun `never drops below the floor`() {
        val t = PieceTimeout(minMs = 3_000)
        // A very fast path would otherwise compute a timeout of a few tens of
        // milliseconds and declare failure on ordinary scheduling jitter.
        repeat(30) { t.onSample(5) }
        assertEquals(3_000L, t.currentMs())
    }

    @Test
    fun `ignores non-positive samples`() {
        val t = PieceTimeout(initialMs = 5_000)
        t.onSample(0)
        t.onSample(-100)
        assertNull(t.smoothedRttMs())
        assertEquals(5_000L, t.currentMs())
    }

    @Test
    fun `adapts downward when the path gets faster`() {
        val t = PieceTimeout()
        repeat(30) { t.onSample(9_000) }
        val slow = t.currentMs()
        repeat(60) { t.onSample(500) }
        assertTrue("rto ${t.currentMs()} should fall well below $slow", t.currentMs() < slow / 2)
    }

    // ---- delay-based congestion signal ----

    @Test
    fun `reports no congestion before any sample`() {
        assertFalse(PieceTimeout().isCongested())
    }

    @Test
    fun `a steady path is never congested`() {
        val t = PieceTimeout()
        repeat(30) { t.onSample(5_000) }
        assertFalse(t.isCongested())
    }

    /**
     * The bufferbloat case, seen live: pieces kept arriving, nothing was lost,
     * and the round-trip climbed from 11s to 15s as the window grew. Loss would
     * never have reported that — only delay does.
     */
    @Test
    fun `detects a round-trip inflating past the floor`() {
        val t = PieceTimeout()
        // A clean path first, so the floor learns what the link can really do.
        repeat(20) { t.onSample(1_000) }
        assertFalse(t.isCongested())
        assertEquals(1_000L, t.minRttMs())
        // Then a queue builds in front of it: same link, three times the wait.
        repeat(20) { t.onSample(3_000) }
        assertTrue("srtt ${t.smoothedRttMs()} vs min ${t.minRttMs()}", t.isCongested())
    }

    @Test
    fun `ordinary jitter does not read as congestion`() {
        val t = PieceTimeout()
        repeat(10) { t.onSample(1_000) }
        // 40% above the floor is noise, not a filling queue — reacting to it
        // would keep the window pinned at the minimum on any normal link.
        repeat(20) { t.onSample(1_400) }
        assertFalse(t.isCongested())
    }

    @Test
    fun `clears once the queue drains`() {
        val t = PieceTimeout()
        repeat(20) { t.onSample(1_000) }
        repeat(20) { t.onSample(4_000) }
        assertTrue(t.isCongested())
        repeat(40) { t.onSample(1_100) }
        assertFalse(t.isCongested())
    }

    @Test
    fun `reset returns it to the initial state`() {
        val t = PieceTimeout(initialMs = 5_000)
        repeat(10) { t.onSample(9_000) }
        t.onTimeout()
        t.reset()
        assertNull(t.smoothedRttMs())
        assertEquals(5_000L, t.currentMs())
    }
}

package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestWindowTest {

    /** Drivable clock, so the cooldown is tested without sleeping. */
    private class Clock(var t: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = t
    }

    private fun window(clock: Clock, start: Int = 8, min: Int = 4, max: Int = 64) =
        RequestWindow(start = start, min = min, max = max, cooldownMs = 5_000L, now = clock)

    @Test
    fun `starts at the configured size`() {
        assertEquals(8, window(Clock()).size)
    }

    @Test
    fun `grows by one per delivered piece`() {
        val w = window(Clock())
        repeat(3) { w.onDelivered() }
        assertEquals(11, w.size)
    }

    @Test
    fun `never grows past the maximum`() {
        val w = window(Clock(), start = 8, max = 10)
        repeat(50) { w.onDelivered() }
        assertEquals(10, w.size)
    }

    @Test
    fun `halves on a timeout`() {
        val clock = Clock()
        val w = window(clock, start = 32)
        assertTrue(w.onTimeout())
        assertEquals(16, w.size)
    }

    /**
     * The reason the cooldown exists. A stall times out every outstanding
     * request at once; without the guard, eight simultaneous timeouts would
     * take a window of 32 down to the floor on a single event, and recovery
     * then crawls back one piece at a time.
     */
    @Test
    fun `a burst of timeouts counts as one stall`() {
        val clock = Clock()
        val w = window(clock, start = 32)

        assertTrue("first timeout moves the window", w.onTimeout())
        repeat(7) { assertFalse("burst timeout ignored", w.onTimeout()) }

        assertEquals(16, w.size)
    }

    @Test
    fun `a later timeout backs off again`() {
        val clock = Clock()
        val w = window(clock, start = 32)
        w.onTimeout()
        assertEquals(16, w.size)

        clock.t += 5_000
        assertTrue(w.onTimeout())
        assertEquals(8, w.size)
    }

    @Test
    fun `a timeout one millisecond inside the cooldown is still ignored`() {
        val clock = Clock()
        val w = window(clock, start = 32)
        w.onTimeout()
        clock.t += 4_999
        assertFalse(w.onTimeout())
        assertEquals(16, w.size)
    }

    @Test
    fun `never shrinks below the minimum`() {
        val clock = Clock()
        val w = window(clock, start = 8, min = 4)
        for (i in 0 until 10) {
            w.onTimeout()
            clock.t += 5_000
        }
        assertEquals(4, w.size)
    }

    @Test
    fun `a timeout at the floor reports no movement`() {
        val clock = Clock()
        val w = window(clock, start = 4, min = 4)
        // Already at the floor: the caller uses the return value for logging,
        // and reporting a back-off that did not happen is noise.
        assertFalse(w.onTimeout())
        assertEquals(4, w.size)
    }

    @Test
    fun `the very first timeout is never swallowed`() {
        // Guards against initialising lastBackoffAt to zero and comparing it
        // against a real clock, which would make the first stall look like it
        // was inside a cooldown that never happened.
        val clock = Clock(t = 1_000_000L)
        val w = window(clock, start = 16)
        assertTrue(w.onTimeout())
        assertEquals(8, w.size)
    }

    @Test
    fun `a congestion signal backs off by one, not by half`() {
        val w = window(Clock(), start = 32)
        w.onCongestionSignal()
        // Rising delay means slightly too large, not broken. Halving here would
        // sawtooth between too much and too little without gaining throughput.
        assertEquals(31, w.size)
    }

    @Test
    fun `a congestion signal never goes below the minimum`() {
        val w = window(Clock(), start = 5, min = 4)
        repeat(10) { w.onCongestionSignal() }
        assertEquals(4, w.size)
    }

    @Test
    fun `hasRoom tracks the current size`() {
        val w = window(Clock(), start = 8)
        assertTrue(w.hasRoom(7))
        assertFalse(w.hasRoom(8))
        assertFalse("over the window", w.hasRoom(20))
    }

    @Test
    fun `recovers by growing after backing off`() {
        val clock = Clock()
        val w = window(clock, start = 32)
        w.onTimeout()
        assertEquals(16, w.size)
        repeat(4) { w.onDelivered() }
        assertEquals(20, w.size)
    }
}

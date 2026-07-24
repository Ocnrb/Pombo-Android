package com.pombo.android.core

/**
 * AIMD flow control for a pull-based transfer.
 *
 * There is no separate credit mechanism: the leecher asks a named seeder for a
 * specific piece, so the number of outstanding requests IS the amount of data
 * in flight — the request is the credit. The window grows by one per delivered
 * piece and halves on a timeout, the same additive-increase /
 * multiplicative-decrease shape TCP uses, for the same reason: probe upward
 * slowly, retreat fast when the path says it is overloaded.
 *
 * Extracted from the transfer engine so the backoff rule can be tested without
 * a network, a bridge or a clock.
 */
class RequestWindow(
    private val start: Int = MediaConfig.WINDOW_START,
    private val min: Int = MediaConfig.WINDOW_MIN,
    private val max: Int = MediaConfig.WINDOW_MAX,
    private val cooldownMs: Long = MediaConfig.WINDOW_BACKOFF_COOLDOWN_MS,
    /** Injected so tests can drive time instead of sleeping. */
    private val now: () -> Long = System::currentTimeMillis
) {

    @Volatile
    var size: Int = start
        private set

    private var lastBackoffAt = 0L

    /** A piece arrived: additive increase, capped. */
    @Synchronized
    fun onDelivered() {
        if (size < max) size += 1
    }

    /**
     * Delay says the queue is filling, though nothing has been lost yet.
     *
     * Gentler than [onTimeout]: rising latency means we are a little
     * past the right window, not that the path has broken. Backing off by one
     * finds the operating point and sits near it, where halving would sawtooth
     * between too much and too little for no gain.
     */
    @Synchronized
    fun onCongestionSignal() {
        if (size > min) size -= 1
    }

    /**
     * A request timed out: multiplicative decrease, at most once per cooldown.
     *
     * A stall times out every outstanding request within milliseconds of each
     * other; letting each one halve the window independently would take it
     * from 32 to the floor on what was a single event — after which recovery
     * is one piece at a time.
     *
     * @return true if this timeout actually moved the window.
     */
    @Synchronized
    fun onTimeout(): Boolean {
        val at = now()
        if (lastBackoffAt != 0L && at - lastBackoffAt < cooldownMs) return false
        lastBackoffAt = at
        val next = maxOf(min, size / 2)
        val moved = next != size
        size = next
        return moved
    }

    /** Whether another request fits alongside [inFlight] outstanding ones. */
    fun hasRoom(inFlight: Int): Boolean = inFlight < size

    @Synchronized
    fun reset() {
        size = start
        lastBackoffAt = 0L
    }
}

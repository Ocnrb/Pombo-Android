package com.pombo.android.core

/**
 * Adaptive retransmission timeout for piece requests, following the shape of
 * TCP's RTO estimator (RFC 6298): a smoothed round-trip time plus four times
 * its variance, doubled on every timeout and re-measured from every success.
 *
 * A fixed timeout is the wrong tool here. The web uses 5s, which suits a
 * desktop with working WebRTC; on a path where a 220 KB piece takes six or
 * seven seconds, that constant declares failure on pieces still in flight.
 * The request window then halves, the piece is asked for again, and the
 * duplicate answer competes with the pieces still coming while the network
 * was delivering everything asked of it.
 *
 * Measuring instead means the timeout lands just past what the path actually
 * does, on a fast link and a slow one alike, with no constant to tune.
 */
class PieceTimeout(
    private val initialMs: Long = 5_000L,
    private val minMs: Long = 3_000L,
    private val maxMs: Long = 60_000L
) {

    private var srtt = -1.0      // smoothed round-trip time
    private var rttvar = 0.0     // round-trip time variation
    private var backoff = 1
    private var minRtt = Long.MAX_VALUE

    /** Current timeout to arm a request with. */
    @Synchronized
    fun currentMs(): Long {
        val base = if (srtt < 0) initialMs.toDouble() else srtt + 4 * rttvar
        return (base * backoff).toLong().coerceIn(minMs, maxMs)
    }

    /**
     * A piece arrived after [rttMs]. Clears the backoff — the path is working,
     * so whatever made us double the timeout has passed.
     */
    @Synchronized
    fun onSample(rttMs: Long) {
        if (rttMs <= 0) return
        if (rttMs < minRtt) minRtt = rttMs
        val r = rttMs.toDouble()
        if (srtt < 0) {
            // First measurement seeds the estimator (RFC 6298 rule 2.2).
            srtt = r
            rttvar = r / 2
        } else {
            // The variance term is updated FIRST and from the OLD srtt; using
            // the updated one would make the estimator track its own output and
            // collapse the variance to nothing on a jittery link.
            rttvar = 0.75 * rttvar + 0.25 * Math.abs(srtt - r)
            srtt = 0.875 * srtt + 0.125 * r
        }
        backoff = 1
    }

    /**
     * A request timed out: double the wait, up to the ceiling.
     *
     * Backing off rather than re-measuring is deliberate — a timeout gives no
     * round-trip sample at all (we do not know whether the answer is late or
     * never coming), so the only honest response is to wait longer next time.
     */
    @Synchronized
    fun onTimeout() {
        if (currentMs() < maxMs) backoff = minOf(backoff * 2, 64)
    }

    /** Smoothed RTT in ms, or null before the first sample. */
    @Synchronized
    fun smoothedRttMs(): Long? = if (srtt < 0) null else srtt.toLong()

    /** Best round-trip seen, the path's latency with no queue in front of it. */
    @Synchronized
    fun minRttMs(): Long? = if (minRtt == Long.MAX_VALUE) null else minRtt

    /**
     * Whether the current round-trip has inflated well past the path's best —
     * the delay-based congestion signal Vegas and BBR use.
     *
     * Loss alone is not enough here. The request window only shrinks on a
     * timeout, and once the timeout is measured rather than fixed, timeouts
     * become rare by construction. Left to grow on every delivery the window
     * inflates until pieces sit in a queue for three times as long as the path
     * needs — throughput unchanged, latency ruined, and the transfer no longer
     * able to react to anything. Rising delay says the queue is filling long
     * before any packet is dropped.
     *
     * The [factor] threshold is loose: normal jitter should not read as
     * congestion, only a clear multiple of the floor.
     */
    @Synchronized
    fun isCongested(factor: Double = 2.0): Boolean {
        if (srtt < 0 || minRtt == Long.MAX_VALUE) return false
        return srtt > minRtt * factor
    }

    @Synchronized
    fun reset() {
        srtt = -1.0
        rttvar = 0.0
        backoff = 1
        minRtt = Long.MAX_VALUE
    }
}

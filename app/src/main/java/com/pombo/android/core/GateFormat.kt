package com.pombo.android.core

/**
 * Paid-subscription display arithmetic (N-F), shared by the gate entry
 * dialog, the chat header chip, and the expiry strip. Pure functions —
 * parity with the web's SubscriptionBannerUI.formatRemaining.
 */
object GateFormat {

    /** Show the renew warning when less than this remains (§7.14). */
    const val WARNING_MS = 3L * 24 * 60 * 60 * 1000

    /** "12 days" / "1 day" / "5h" / "less than an hour" */
    fun formatRemaining(msLeft: Long): String {
        val days = msLeft / 86_400_000L
        if (days >= 1) return "$days " + if (days == 1L) "day" else "days"
        val hours = msLeft / 3_600_000L
        if (hours >= 1) return "${hours}h"
        return "less than an hour"
    }
}

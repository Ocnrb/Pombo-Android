package com.pombo.android.push

/**
 * Process-wide bridge between the FCM service and the running UI.
 *
 * The web SW never shows a system notification while a client is focused —
 * it posts the event to that client instead (sw.js:461-476), which updates
 * its own unread state. [PomboMessagingService] runs outside the ViewModel
 * graph, so this singleton is the equivalent handoff point: MainActivity
 * keeps [inForeground] current, the ViewModel installs [deliverInApp], and a
 * verified wake that arrives while the user is looking at the app becomes an
 * unread-badge update instead of a redundant system notification.
 *
 * When the process is cold-started by FCM neither field is set — the service
 * falls through to the notification path, which is exactly right.
 */
object ForegroundGate {

    @Volatile var inForeground = false

    /**
     * Installed by the ViewModel. Receives (entryType, conversationId,
     * timestamp) for a verified wake while [inForeground]; the notification
     * is suppressed whenever this ran.
     */
    @Volatile var deliverInApp: ((String, String, Long) -> Unit)? = null

    /** True when the wake was consumed in-app and must not be notified. */
    fun tryHandle(entryType: String, conversationId: String, timestamp: Long): Boolean {
        if (!inForeground) return false
        deliverInApp?.invoke(entryType, conversationId, timestamp)
        return true
    }
}

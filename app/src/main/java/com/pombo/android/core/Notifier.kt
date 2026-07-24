package com.pombo.android.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * System notifications for messages that arrive while the app is not in front.
 *
 * This is the half of push that works without relay support: the web receives
 * relay pushes through a browser Web Push subscription, which has no native
 * equivalent — a native client would need the relay to speak FCM. Until then
 * Android still notifies for anything it receives while connected, and it
 * publishes wake signals so web users get notified about Android's messages.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        // API 26+ requires a channel before anything can be posted.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "New messages in your Pombo channels" }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun canPost(): Boolean = manager.areNotificationsEnabled()

    /**
     * @param conversationId used as the notification tag so a second message in
     *   the same conversation replaces the first instead of stacking.
     */
    fun postMessage(
        conversationId: String,
        title: String,
        body: String,
        largeIcon: android.graphics.Bitmap? = null
    ) {
        if (!canPost()) return
        val open = Intent(context, Class.forName("com.pombo.android.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, conversationId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // The status-bar icon is alpha-only — Android paints every opaque
            // pixel with the tint — so this is the Pombo bird as a silhouette,
            // not the colour logo. It was android.R.drawable.ic_dialog_email,
            // the system envelope, which is why the PWA (which gets the site
            // icon for free) looked right and the native app did not.
            .setSmallIcon(com.pombo.android.R.drawable.ic_notification)
            .setColor(0xFFF6851B.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .apply { if (largeIcon != null) setLargeIcon(largeIcon) }
            .build()
        try {
            manager.notify(conversationId, MESSAGE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
        }
    }

    fun clear(conversationId: String) = manager.cancel(conversationId, MESSAGE_NOTIFICATION_ID)

    private companion object {
        const val CHANNEL_ID = "pombo_messages"
        const val MESSAGE_NOTIFICATION_ID = 1001
    }
}

package com.pombo.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Foreground service that holds the process alive while file transfers are
 * running and the UI is not visible. The whole engine — headless WebView
 * bridge, MediaController, piece stores — lives in the app process; without
 * this, backgrounding the app during a large download risks the OOM killer
 * ending the transfer mid-piece.
 *
 * Lifecycle: started by AppViewModel when the app goes to background WITH
 * active work (a start from the background is not allowed on Android 12+, so
 * the decision is made on the way out); stopped when the app returns, and it
 * stops ITSELF once [isBusy] reports idle twice in a row.
 */
class MediaTransferService : Service() {

    companion object {
        private const val CHANNEL_ID = "pombo_transfers"
        private const val NOTIFICATION_ID = 41

        /** Tapping the notification opens the transfer's channel (or Chats if several). */
        const val ACTION_OPEN_TRANSFERS = "com.pombo.android.OPEN_TRANSFERS"

        private const val IDLE_CHECK_MS = 30_000L

        /**
         * Injected by AppViewModel — the service cannot reach the ViewModel's
         * MediaController any other way without turning the engine into a
         * global. Null (process restarted the service alone) reads as idle.
         */
        @Volatile var isBusy: (() -> Boolean)? = null

        fun start(context: Context) {
            val intent = Intent(context, MediaTransferService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaTransferService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var idleStrikes = 0

    private val idleCheck = object : Runnable {
        override fun run() {
            if (isBusy?.invoke() == true) {
                idleStrikes = 0
            } else {
                // Two consecutive idle reads, not one: a download between
                // pieces or a seeder between requests must not kill the
                // service that is keeping it alive.
                idleStrikes++
                if (idleStrikes >= 2) {
                    stopSelf()
                    return
                }
            }
            handler.postDelayed(this, IDLE_CHECK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "File transfers", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps file transfers running in the background" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Tapping routes to MainActivity, which asks the ViewModel to open the
        // transfer's channel (single) or the channel list (several) — decided at
        // tap time so a second transfer starting does not stale the target.
        val tapIntent = Intent(ACTION_OPEN_TRANSFERS, null, this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentPi = PendingIntent.getActivity(this, 0, tapIntent, piFlags)
        val notification: Notification = (
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Pombo")
            .setContentText("File transfers active")
            .setContentIntent(contentPi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        idleStrikes = 0
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, IDLE_CHECK_MS)
        // Not sticky: restarted with no work and no isBusy hook, it would just
        // show a notification about nothing.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

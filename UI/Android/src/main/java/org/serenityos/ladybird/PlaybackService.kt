package org.serenityos.ladybird

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * A tiny foreground service whose only job is to keep the browser process alive
 * (and not frozen) while audio plays in the background, for sites the user has
 * allowed in Background playback settings. It owns no audio itself — the engine's
 * AAudio stream keeps running as long as the process does; this just stops
 * Android from suspending that process when the screen is off.
 *
 * The accompanying notification is required for a foreground service and doubles
 * as a "tap to return to the browser" affordance.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra(EXTRA_HOST).orEmpty()
        startForegroundWithNotification(host)
        return START_STICKY
    }

    private fun startForegroundWithNotification(host: String) {
        ensureChannel()

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, LadybirdActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bg_audio_notification_title))
            .setContentText(if (host.isBlank()) getString(R.string.app_name) else host)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bg_audio_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "ladybird_playback"
        private const val NOTIFICATION_ID = 0xB6
        private const val EXTRA_HOST = "host"

        /** Promote the app to a foreground (media) service so its audio survives
         *  the screen turning off. [host] is shown in the notification. */
        fun start(context: Context, host: String) {
            val intent = Intent(context, PlaybackService::class.java).putExtra(EXTRA_HOST, host)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}

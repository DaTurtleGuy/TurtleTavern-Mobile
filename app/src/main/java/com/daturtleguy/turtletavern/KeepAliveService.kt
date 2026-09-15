package com.daturtleguy.turtletavern

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(TAG, "KeepAlive service starting (startId=$startId)")
        startForeground(NOTIFICATION_ID, buildNotification())
        if (wakeLock?.isHeld != true) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "turtletavern::server")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
            AppLog.i(TAG, "PARTIAL_WAKE_LOCK acquired")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.i(TAG, "KeepAlive service stopping — releasing wake lock")
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getText(R.string.keep_alive_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getText(R.string.keep_alive_notification_title))
            .setContentText(getText(R.string.keep_alive_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "KeepAlive"
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 1
    }
}

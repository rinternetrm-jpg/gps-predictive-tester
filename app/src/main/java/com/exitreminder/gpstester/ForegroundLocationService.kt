package com.exitreminder.gpstester

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ForegroundLocationService : Service() {

    companion object {
        const val CHANNEL_ID = "gps_tester_channel"
        const val NOTIFICATION_ID = 1001
    }

    private val binder = LocalBinder()
    var locationManager: PredictiveLocationManager? = null

    inner class LocalBinder : Binder() {
        fun getService(): ForegroundLocationService = this@ForegroundLocationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("GPS Tracking aktiv", "Warte auf Position...")
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    fun updateNotification(distance: Float, nextCheck: Float, mode: String) {
        val notification = createNotification(
            "GPS Tracking aktiv",
            "${Utils.formatDistance(distance)} entfernt \u2022 Nächster Check: ${Utils.formatDuration(nextCheck)} \u2022 $mode"
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt GPS Tracking Status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

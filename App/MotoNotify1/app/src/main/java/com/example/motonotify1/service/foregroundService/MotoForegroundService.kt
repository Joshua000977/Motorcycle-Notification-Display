package com.example.motonotify1.service.foregroundService

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.motonotify1.BleManager.BleManagerProvider

class MotoForegroundService : Service() {
    private val channelId = "moto_notify_channel"
    private val notificationId = 1
    companion object {

        var instance: MotoForegroundService? = null
    }
    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startMotoForeground()
        BleManagerProvider.bleManager.log("Foreground service started")
        BleManagerProvider.bleManager.startScan()


    }

    private fun startMotoForeground() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                channelId,
                "MotoNotify Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }

        startForeground(
            notificationId,
            buildNotification("Starting...")
        )
    }


    private fun buildNotification(
        text: String
    ): Notification {

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("MotoNotify")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }
    fun updateNotification(
        text: String
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            notificationId,
            buildNotification(text)
        )
    }
}

package com.example.motonotify1.service.foregroundService

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.motonotify1.bleManager.BleManagerProvider
import com.example.motonotify1.bleManager.TpmsBleManagerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that owns the BLE coroutine scope and drives [BleManager] lifecycle.
 */
class ForegroundBleService : Service() {

    private val channelId = "moto_notify_ble_channel"
    private val notificationId = 1001

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startBleForeground()

        val bleManager = BleManagerProvider.bleManager
        bleManager.attachServiceScope(serviceScope)
        bleManager.attachBatteryMonitoring(applicationContext)
        bleManager.log("Foreground BLE service created")
        bleManager.startForegroundBleOperations()

        val tpmsBleManager = TpmsBleManagerProvider.tpmsBleManager
        tpmsBleManager.attachContext(applicationContext)
        tpmsBleManager.attachServiceScope(serviceScope)
        tpmsBleManager.startPeriodicReads()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bleManager = BleManagerProvider.bleManager
        bleManager.attachServiceScope(serviceScope)
        bleManager.startForegroundBleOperations()

        val tpmsBleManager = TpmsBleManagerProvider.tpmsBleManager
        tpmsBleManager.attachContext(applicationContext)
        tpmsBleManager.attachServiceScope(serviceScope)
        tpmsBleManager.startPeriodicReads()

        return START_STICKY
    }

    override fun onDestroy() {
        val tpmsBleManager = TpmsBleManagerProvider.tpmsBleManager
        tpmsBleManager.stopPeriodicReads()
        tpmsBleManager.detachServiceScope()

        val bleManager = BleManagerProvider.bleManager
        bleManager.log("Foreground BLE service destroying")
        bleManager.stopForegroundBleOperations()
        bleManager.detachBatteryMonitoring()
        bleManager.detachServiceScope()

        serviceScope.cancel()
        serviceJob.cancel()

        instance = null
        super.onDestroy()
    }

    private fun startBleForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "MotoNotify BLE",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps BLE connected to the MotoNotify display"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification = buildNotification("Starting…")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(notificationId, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("MotoNotify")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            buildNotification(text)
        )
    }

    companion object {
        @Volatile
        var instance: ForegroundBleService? = null
            private set
    }
}

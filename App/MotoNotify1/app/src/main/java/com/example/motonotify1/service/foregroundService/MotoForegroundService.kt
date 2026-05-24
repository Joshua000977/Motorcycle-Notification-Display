package com.example.motonotify1.service.foregroundService

import android.app.Service
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.motonotify1.bleManager.BleManagerProvider

/**
 * Legacy foreground service kept for compatibility.
 * Delegates BLE ownership to [ForegroundBleService].
 */
class MotoForegroundService : Service() {

  override fun onBind(intent: Intent?) = null

  override fun onCreate() {
    super.onCreate()
    instance = this
    BleManagerProvider.bleManager.log("MotoForegroundService: delegating to ForegroundBleService")
    ContextCompat.startForegroundService(
      this,
      Intent(this, ForegroundBleService::class.java)
    )
  }

  override fun onDestroy() {
    instance = null
    super.onDestroy()
  }

  companion object {
    @Volatile
    var instance: MotoForegroundService? = null
      private set
  }
}

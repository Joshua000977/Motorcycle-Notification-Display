package com.example.motonotify1.service.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads phone battery level from [Intent.ACTION_BATTERY_CHANGED] sticky broadcasts.
 */
class PhoneBatteryMonitor(
    private val appContext: Context
) {

    private val _levelPercent = MutableStateFlow<Int?>(null)
    val levelPercent: StateFlow<Int?> = _levelPercent.asStateFlow()

    private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            parseLevel(intent)?.let { level ->
                _levelPercent.value = level
            }
        }
    }

    fun start() {
        if (isRegistered) {
            refresh()
            return
        }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        appContext.registerReceiver(receiver, filter)
        isRegistered = true
        refresh()
    }

    fun stop() {
        if (!isRegistered) {
            return
        }
        appContext.unregisterReceiver(receiver)
        isRegistered = false
    }

    fun currentLevelPercent(): Int? {
        refresh()
        return _levelPercent.value
    }

    private fun refresh() {
        val sticky = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        parseLevel(sticky)?.let { level ->
            _levelPercent.value = level
        }
    }

    private fun parseLevel(intent: Intent?): Int? {
        if (intent == null) {
            return null
        }

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return null
        }

        return ((level * 100f) / scale).toInt().coerceIn(0, 100)
    }
}

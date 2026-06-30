package com.example.motonotify1.service.battery

import com.example.motonotify1.bleManager.BleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sends `BAT:<percent>` to the ESP when connected, on level change, and periodically.
 */
class BatteryReporter(
    private val bleManager: BleManager,
    private val monitor: PhoneBatteryMonitor,
    private val scope: CoroutineScope
) {

    private var levelJob: Job? = null
    private var periodicJob: Job? = null
    private var lastSentPercent: Int? = null
    private var reportingActive = false

    fun onBleConnected() {
        reportingActive = true
        monitor.start()

        levelJob?.cancel()
        levelJob = scope.launch {
            monitor.levelPercent
                .filterNotNull()
                .distinctUntilChanged()
                .collect { percent ->
                    if (reportingActive) {
                        sendIfNeeded(percent, force = false)
                    }
                }
        }

        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (reportingActive && isActive) {
                delay(BATTERY_PERIODIC_INTERVAL_MS)
                val percent = monitor.currentLevelPercent() ?: continue
                sendIfNeeded(percent, force = false)
            }
        }

        val current = monitor.currentLevelPercent()
        if (current != null) {
            sendIfNeeded(current, force = true)
        } else {
            bleManager.log("Battery: level unavailable after connect")
        }
    }

    fun onBleDisconnected() {
        reportingActive = false
        levelJob?.cancel()
        levelJob = null
        periodicJob?.cancel()
        periodicJob = null
        monitor.stop()
    }

    fun shutdown() {
        onBleDisconnected()
        lastSentPercent = null
    }

    private fun sendIfNeeded(percent: Int, force: Boolean) {
        if (!bleManager.isBleConnected()) {
            return
        }
        if (!force && percent == lastSentPercent) {
            return
        }

        lastSentPercent = percent
        bleManager.sendBatteryPercent(percent)
    }

    companion object {
        private const val BATTERY_PERIODIC_INTERVAL_MS = 5 * 60 * 1000L
    }
}

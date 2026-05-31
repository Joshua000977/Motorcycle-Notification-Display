package com.example.motonotify1.bleManager

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import com.juul.kable.peripheral
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class TpmsSensorPosition {
    FRONT,
    REAR
}

enum class TpmsReadStatus {
    Idle,
    Scanning,
    Connecting,
    Reading,
    Success,
    Failed,
    Disabled
}

data class TpmsSensorState(
    val pressureBar: Double? = null,
    val temperatureC: Int? = null,
    val status: TpmsReadStatus = TpmsReadStatus.Idle,
    val lastError: String? = null
)

data class TpmsUiState(
    val front: TpmsSensorState = TpmsSensorState(),
    val rear: TpmsSensorState = TpmsSensorState(),
    val lastUpdateTimeMillis: Long? = null,
    val lastUpdateTimeText: String = "Never",
    val cycleStatus: String = "Idle",
    val isRunning: Boolean = false,
    val bluetoothAvailable: Boolean = false,
    val permissionsGranted: Boolean = false
)

/**
 * Manages RiDEET Pro TPMS sensors over BLE.
 *
 * Reads front and rear tyre pressure/temperature sequentially using a
 * connect -> read FFD1 -> disconnect cycle, then forwards values to the
 * ESP32 display via the existing [BleManager] connection.
 */
class TpmsBleManager {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serviceScope: CoroutineScope? = null

    @Volatile
    private var appContext: Context? = null

    private val running = AtomicBoolean(false)
    private var readLoopJob: Job? = null

    private val _uiState = MutableStateFlow(TpmsUiState())
    val uiState: StateFlow<TpmsUiState> = _uiState.asStateFlow()

    private val tpmsReadCharacteristic = characteristicOf(
        service = TPMS_SERVICE_UUID,
        characteristic = TPMS_CHARACTERISTIC_UUID
    )

    fun attachServiceScope(scope: CoroutineScope) {
        serviceScope = scope
        Log.d(TAG, "Service scope attached")
    }

    fun detachServiceScope() {
        serviceScope = null
        Log.d(TAG, "Service scope detached")
    }

    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Starts the periodic TPMS read loop (every [READ_INTERVAL_MS]).
     * Safe to call multiple times; only one loop runs at a time.
     */
    fun startPeriodicReads() {
        val scope = serviceScope ?: appScope

        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "Periodic reads already running")
            return
        }

        _uiState.update { it.copy(isRunning = true, cycleStatus = "Starting…") }
        Log.d(TAG, "Starting periodic TPMS reads")

        readLoopJob = scope.launch {
            // Run one cycle immediately, then wait between cycles.
            while (isActive && running.get()) {
                runReadCycle()
                delay(READ_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops the periodic TPMS read loop.
     */
    fun stopPeriodicReads() {
        if (!running.compareAndSet(true, false)) {
            return
        }

        Log.d(TAG, "Stopping periodic TPMS reads")
        readLoopJob?.cancel()
        readLoopJob = null

        _uiState.update {
            it.copy(
                isRunning = false,
                cycleStatus = "Stopped"
            )
        }
    }

    private suspend fun runReadCycle() {
        val context = appContext

        val permissionsGranted = context?.let { hasRequiredBlePermissions(it) } == true
        val bluetoothAvailable = context?.let { isBluetoothEnabled(it) } == true

        _uiState.update {
            it.copy(
                permissionsGranted = permissionsGranted,
                bluetoothAvailable = bluetoothAvailable
            )
        }

        if (context == null) {
            Log.e(TAG, "Read cycle skipped: no context attached")
            _uiState.update { it.copy(cycleStatus = "No context") }
            return
        }

        if (!permissionsGranted) {
            Log.e(TAG, "Read cycle skipped: BLE permissions missing")
            _uiState.update { it.copy(cycleStatus = "Missing BLE permissions") }
            return
        }

        if (!bluetoothAvailable) {
            Log.e(TAG, "Read cycle skipped: Bluetooth adapter disabled or unavailable")
            _uiState.update { it.copy(cycleStatus = "Bluetooth unavailable") }
            return
        }

        _uiState.update { it.copy(cycleStatus = "Reading sensors…") }
        Log.d(TAG, "TPMS read cycle started")

        // Read front first; if it fails, still attempt rear.
        readSensor(context, TpmsSensorPosition.FRONT, FRONT_SENSOR_MAC)
        readSensor(context, TpmsSensorPosition.REAR, REAR_SENSOR_MAC)

        val now = System.currentTimeMillis()
        val timeText = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

        _uiState.update {
            it.copy(
                lastUpdateTimeMillis = now,
                lastUpdateTimeText = timeText,
                cycleStatus = "Cycle complete"
            )
        }

        Log.d(TAG, "TPMS read cycle finished")
    }

    private suspend fun readSensor(
        context: Context,
        position: TpmsSensorPosition,
        macAddress: String
    ) {
        val scope = serviceScope ?: appScope
        val label = position.name.lowercase().replaceFirstChar { it.uppercase() }

        updateSensorStatus(position, TpmsReadStatus.Scanning, null)

        var peripheral: Peripheral? = null

        try {
            Log.d(TAG, "Scan started for $label sensor ($macAddress)")

            val advertisement = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                val scanner = Scanner()
                scanner.advertisements.first { adv ->
                    adv.address.equals(macAddress, ignoreCase = true)
                }
            }

            if (advertisement != null) {
                Log.d(TAG, "Sensor found: $label (${advertisement.address})")
                peripheral = scope.peripheral(advertisement)
            } else {
                // Scan timed out — try a direct connect by known MAC as a fallback.
                Log.d(TAG, "Scan timeout for $label; trying direct connect to $macAddress")
                peripheral = scope.peripheral(macAddress)
            }

            updateSensorStatus(position, TpmsReadStatus.Connecting, null)
            Log.d(TAG, "Connecting to $label sensor ($macAddress)")

            withTimeout(CONNECT_TIMEOUT_MS) {
                peripheral.connect()
            }

            Log.d(TAG, "Connect success: $label ($macAddress)")

            updateSensorStatus(position, TpmsReadStatus.Reading, null)

            val rawBytes = withTimeout(READ_TIMEOUT_MS) {
                peripheral.read(tpmsReadCharacteristic)
            }

            val hex = rawBytes.toHexString()
            Log.d(TAG, "Raw FFD1 bytes ($label): $hex")

            val decoded = decodeTpmsData(rawBytes)
            if (decoded == null) {
                Log.e(TAG, "Decode failed for $label: expected at least 2 bytes, got ${rawBytes.size}")
                updateSensorStatus(
                    position,
                    TpmsReadStatus.Failed,
                    "Invalid data length (${rawBytes.size} bytes)"
                )
                return
            }

            val (temperatureC, pressureBar) = decoded
            Log.d(
                TAG,
                "Decoded $label: pressure=${"%.3f".format(pressureBar)} bar, temperature=$temperatureC °C"
            )

            updateSensorReading(position, pressureBar, temperatureC)
            sendToEsp32(position, pressureBar, temperatureC)

            updateSensorStatus(position, TpmsReadStatus.Success, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Read failed for $label sensor ($macAddress)", e)
            updateSensorStatus(position, TpmsReadStatus.Failed, e.message ?: e.javaClass.simpleName)
        } finally {
            peripheral?.let { activePeripheral ->
                try {
                    activePeripheral.disconnect()
                    Log.d(TAG, "Disconnected from $label sensor")
                } catch (e: Exception) {
                    Log.e(TAG, "Disconnect failed for $label sensor", e)
                }
            }
        }
    }

    private fun updateSensorStatus(
        position: TpmsSensorPosition,
        status: TpmsReadStatus,
        error: String?
    ) {
        _uiState.update { state ->
            when (position) {
                TpmsSensorPosition.FRONT -> state.copy(
                    front = state.front.copy(status = status, lastError = error)
                )

                TpmsSensorPosition.REAR -> state.copy(
                    rear = state.rear.copy(status = status, lastError = error)
                )
            }
        }
    }

    private fun updateSensorReading(
        position: TpmsSensorPosition,
        pressureBar: Double,
        temperatureC: Int
    ) {
        _uiState.update { state ->
            when (position) {
                TpmsSensorPosition.FRONT -> state.copy(
                    front = state.front.copy(
                        pressureBar = pressureBar,
                        temperatureC = temperatureC
                    )
                )

                TpmsSensorPosition.REAR -> state.copy(
                    rear = state.rear.copy(
                        pressureBar = pressureBar,
                        temperatureC = temperatureC
                    )
                )
            }
        }
    }

    private fun sendToEsp32(
        position: TpmsSensorPosition,
        pressureBar: Double,
        temperatureC: Int
    ) {
        val bleManager = BleManagerProvider.bleManager

        if (!bleManager.isBleConnected()) {
            Log.e(TAG, "ESP32 send fail (${position.name}): not connected to MotoNotifyDisplay")
            return
        }

        val pressureMessage = when (position) {
            TpmsSensorPosition.FRONT -> "TPMSF:${"%.2f".format(pressureBar)}"
            TpmsSensorPosition.REAR -> "TPMSR:${"%.2f".format(pressureBar)}"
        }

        val temperatureMessage = when (position) {
            TpmsSensorPosition.FRONT -> "TPMSFT:$temperatureC"
            TpmsSensorPosition.REAR -> "TPMSRT:$temperatureC"
        }

        bleManager.sendText(pressureMessage)
        Log.d(TAG, "ESP32 send queued: $pressureMessage")

        bleManager.sendText(temperatureMessage)
        Log.d(TAG, "ESP32 send queued: $temperatureMessage")
    }

    private fun hasRequiredBlePermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @Suppress("DEPRECATION")
    private fun isBluetoothEnabled(context: Context): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        return adapter?.isEnabled == true
    }

    companion object {
        private const val TAG = "TpmsBleManager"

        private const val FRONT_SENSOR_MAC = "08:35:1B:02:43:CC"
        private const val REAR_SENSOR_MAC = "08:35:1B:02:43:73"

        private const val TPMS_SERVICE_UUID = "0000ffd0-0000-1000-8000-00805f9b34fb"
        private const val TPMS_CHARACTERISTIC_UUID = "0000ffd1-0000-1000-8000-00805f9b34fb"

        private const val READ_INTERVAL_MS = 30_000L
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 5_000L

        /**
         * Decodes RiDEET TPMS characteristic FFD1 payload.
         *
         * temperatureC = byte0 - 40
         * pressureBar  = byte1 / 40.0
         */
        fun decodeTpmsData(data: ByteArray): Pair<Int, Double>? {
            if (data.size < 2) {
                return null
            }

            val temperatureC = (data[0].toInt() and 0xFF) - 40
            val pressureBar = (data[1].toInt() and 0xFF) / 40.0
            return temperatureC to pressureBar
        }

        private fun ByteArray.toHexString(): String =
            joinToString(" ") { byte -> "%02X".format(byte) }
    }
}

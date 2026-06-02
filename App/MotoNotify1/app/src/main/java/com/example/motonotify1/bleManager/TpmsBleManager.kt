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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Reads front and rear sensors sequentially using connect -> read/notify FFD1
 * (pressure + temperature) -> disconnect, then forwards values to the ESP32
 * display via the existing [BleManager] connection.
 */
class TpmsBleManager {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serviceScope: CoroutineScope? = null

    @Volatile
    private var appContext: Context? = null

    private val running = AtomicBoolean(false)
    private var readLoopJob: Job? = null
    private val operationMutex = Mutex()

    @Volatile
    private var tpmsPeripheral: Peripheral? = null

    @Volatile
    private var tpmsPeripheralScope: CoroutineScope? = null

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

        if (!BleManagerProvider.bleManager.isBleConnected()) {
            Log.d(TAG, "TPMS waiting: ESP32 not connected yet")
            _uiState.update { it.copy(cycleStatus = "Waiting for ESP32 connection") }
            return
        }

        _uiState.update { it.copy(cycleStatus = "Reading sensors…") }
        Log.d(TAG, "TPMS read cycle started")

        readSensor(TpmsSensorPosition.FRONT, FRONT_SENSOR_MAC)
        delay(BETWEEN_SENSORS_DELAY_MS)
        readSensor(TpmsSensorPosition.REAR, REAR_SENSOR_MAC)

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
        position: TpmsSensorPosition,
        macAddress: String
    ) = operationMutex.withLock {
        val label = position.name.lowercase().replaceFirstChar { it.uppercase() }
        val scope = serviceScope ?: appScope

        updateSensorStatus(position, TpmsReadStatus.Scanning, null)

        try {
            // Defensive: never start a new TPMS session while prior TPMS GATT is still open.
            cleanupTpmsGatt()

            val readCompleted = withTimeoutOrNull(SENSOR_READ_TIMEOUT_MS) {
                Log.d(TAG, "Scan started for $label sensor ($macAddress)")
                val advertisement = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    val scanner = Scanner()
                    scanner.advertisements.first { adv ->
                        adv.address.equals(macAddress, ignoreCase = true)
                    }
                }
                Log.d(TAG, "Scan stopped for $label sensor")

                tpmsPeripheralScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                tpmsPeripheral = if (advertisement != null) {
                    Log.d(TAG, "Sensor found: $label (${advertisement.address})")
                    tpmsPeripheralScope!!.peripheral(advertisement)
                } else {
                    Log.d(TAG, "Sensor not found in scan; trying direct connect: $label ($macAddress)")
                    tpmsPeripheralScope!!.peripheral(macAddress)
                }

                val peripheral = tpmsPeripheral!!

                updateSensorStatus(position, TpmsReadStatus.Connecting, null)
                Log.d(TAG, "Connect started for $label sensor ($macAddress)")

                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    peripheral.connect()
                } ?: run {
                    Log.e(TAG, "Connect timeout for $label sensor")
                    return@withTimeoutOrNull false
                }
                Log.d(TAG, "Connect success for $label sensor")
                Log.d(TAG, "Services discovered for $label sensor")

                updateSensorStatus(position, TpmsReadStatus.Reading, null)
                delay(POST_CONNECT_SETTLE_MS)

                val rawBytes = readTpmsPayload(peripheral, label)
                if (rawBytes == null) {
                    updateSensorStatus(position, TpmsReadStatus.Failed, "No valid FFD1 payload (need >=2 bytes)")
                    return@withTimeoutOrNull false
                }

                Log.d(TAG, "FFD1 read result ($label): ${rawBytes.toHexString()}")

                val decoded = decodeTpmsData(rawBytes)
                if (decoded == null) {
                    updateSensorStatus(position, TpmsReadStatus.Failed, "Invalid FFD1 data")
                    return@withTimeoutOrNull false
                }

                val (temperatureC, pressureBar) = decoded
                Log.d(
                    TAG,
                    "Decoded $label: pressure=${"%.3f".format(pressureBar)} bar, temperature=$temperatureC °C"
                )
                updateSensorReading(position, pressureBar, temperatureC)
                sendToEsp32(position, pressureBar, temperatureC)
                updateSensorStatus(position, TpmsReadStatus.Success, null)
                true
            }

            if (readCompleted == null) {
                Log.e(TAG, "Sensor read timeout ($label): ${SENSOR_READ_TIMEOUT_MS}ms")
                updateSensorStatus(position, TpmsReadStatus.Failed, "Timeout")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Read failed for $label sensor ($macAddress)", e)
            updateSensorStatus(position, TpmsReadStatus.Failed, e.message ?: e.javaClass.simpleName)
        } finally {
            cleanupTpmsGatt()
            Log.d(
                TAG,
                "ESP32 still connected after TPMS cleanup: ${BleManagerProvider.bleManager.isBleConnected()}"
            )
        }
    }

    /**
     * Reads FFD1 via READ retries, then NOTIFY fallback. Ignores invalid payloads.
     */
    private suspend fun readTpmsPayload(
        peripheral: Peripheral,
        label: String
    ): ByteArray? {
        repeat(READ_RETRY_COUNT) { attempt ->
            val bytes = readTpmsOnce(peripheral, label, attempt + 1)
            if (isValidTpmsPayload(bytes)) {
                return bytes
            }
            if (bytes != null) {
                Log.w(
                    TAG,
                    "FFD1 ignored ($label) attempt ${attempt + 1}: ${bytes.toHexString()}"
                )
            }
            delay(READ_RETRY_DELAY_MS)
        }

        Log.d(TAG, "FFD1 READ retries exhausted for $label; trying NOTIFY on FFD1")
        return withTimeoutOrNull(NOTIFY_FALLBACK_TIMEOUT_MS) {
            peripheral.observe(tpmsReadCharacteristic).first { data ->
                Log.d(TAG, "FFD1 notification result ($label): ${data.toHexString()}")
                isValidTpmsPayload(data)
            }
        }
    }

    private suspend fun readTpmsOnce(
        peripheral: Peripheral,
        label: String,
        attempt: Int
    ): ByteArray? {
        return try {
            withTimeoutOrNull(READ_TIMEOUT_MS) {
                peripheral.read(tpmsReadCharacteristic)
            }?.also { bytes ->
                Log.d(TAG, "FFD1 read result ($label) attempt $attempt: ${bytes.toHexString()}")
            } ?: run {
                Log.e(TAG, "FFD1 read timed out ($label) attempt $attempt")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "FFD1 read failed ($label) attempt $attempt", e)
            null
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
            TpmsSensorPosition.FRONT -> String.format(Locale.US, "TPMSF:%.2f", pressureBar)
            TpmsSensorPosition.REAR -> String.format(Locale.US, "TPMSR:%.2f", pressureBar)
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

    /**
     * TPMS-only cleanup; never touches ESP32 BLE manager internals.
     */
    private suspend fun cleanupTpmsGatt() {
        val peripheral = tpmsPeripheral
        val scope = tpmsPeripheralScope

        if (peripheral == null && scope == null) {
            return
        }

        Log.d(TAG, "TPMS cleanup started")
        try {
            peripheral?.disconnect()
            Log.d(TAG, "TPMS gatt disconnect completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting TPMS GATT", e)
        }

        try {
            scope?.cancel()
            Log.d(TAG, "TPMS gatt closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TPMS GATT", e)
        }

        tpmsPeripheral = null
        tpmsPeripheralScope = null
        Log.d(TAG, "TPMS cleanup finished")
        delay(AFTER_TPMS_CLOSE_DELAY_MS)
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
        private const val READ_TIMEOUT_MS = 10_000L
        private const val SENSOR_READ_TIMEOUT_MS = 15_000L
        private const val POST_CONNECT_SETTLE_MS = 500L
        private const val READ_RETRY_COUNT = 3
        private const val READ_RETRY_DELAY_MS = 400L
        private const val NOTIFY_FALLBACK_TIMEOUT_MS = 8_000L
        private const val BETWEEN_SENSORS_DELAY_MS = 1_000L
        private const val AFTER_TPMS_CLOSE_DELAY_MS = 750L

        /**
         * True when FFD1 has at least 2 bytes and is not the invalid single-byte 0x00 payload.
         */
        fun isValidTpmsPayload(data: ByteArray?): Boolean {
            if (data == null || data.size < 2) {
                return false
            }
            if (data.size == 1 && data[0] == 0.toByte()) {
                return false
            }
            return true
        }

        /**
         * Decodes RiDEET FFD1 (service FFD0, characteristic FFD1).
         *
         * byte0 unsigned: temperatureC = raw - 40
         * byte1 unsigned: pressureBar = raw / 40.0
         *
         * Valid: 47 00 -> 31 °C, 0.0 bar; 3D 4F -> 21 °C, 1.975 bar
         */
        fun decodeTpmsData(data: ByteArray): Pair<Int, Double>? {
            if (!isValidTpmsPayload(data)) {
                return null
            }

            val tempRaw = data[0].toInt() and 0xFF
            val pressureRaw = data[1].toInt() and 0xFF
            val temperatureC = tempRaw - 40
            val pressureBar = pressureRaw / 40.0
            return temperatureC to pressureBar
        }

        private fun ByteArray.toHexString(): String =
            joinToString(" ") { byte -> "%02X".format(byte) }
    }
}

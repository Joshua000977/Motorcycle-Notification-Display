package com.example.motonotify1.bleManager

import android.util.Log
import com.example.motonotify1.service.foregroundService.ForegroundBleService
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.characteristicOf
import com.juul.kable.peripheral
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

enum class BleConnectionPhase {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Reconnecting
}

enum class OtaPreparePhase {
    Idle,
    Waiting,
    Ready,
    TimedOut,
    Failed
}

data class BleDeviceUi(
    val name: String,
    val address: String,
    val peripheral: Peripheral
)

data class BleUiState(
    val phase: BleConnectionPhase = BleConnectionPhase.Disconnected,
    val isScanning: Boolean = false,
    val devices: List<BleDeviceUi> = emptyList(),
    val espIp: String = "No IP",
    val wifiStatus: String = "Unknown",
    /** Blocks WA/C and other notification writes while ESP is in OTA mode. */
    val otaModeActive: Boolean = false,
    val otaPreparePhase: OtaPreparePhase = OtaPreparePhase.Idle,
    val otaPrepareMessage: String? = null,
    val logs: List<String> = emptyList()
) {
    /** Backward-compatible label for existing UI code. */
    val connectionState: String
        get() = when (phase) {
            BleConnectionPhase.Disconnected -> "Disconnected"
            BleConnectionPhase.Scanning -> "Scanning"
            BleConnectionPhase.Connecting -> "Connecting"
            BleConnectionPhase.Connected -> "Connected"
            BleConnectionPhase.Reconnecting -> "Reconnecting"
        }
}

/**
 * Owns BLE state and I/O. Long-running scan/connect/reconnect work runs on the scope
 * supplied by [ForegroundBleService] via [attachServiceScope].
 */
class BleManager {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serviceScope: CoroutineScope? = null

    private val foregroundOpsRunning = AtomicBoolean(false)

    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    private var scanner: Scanner? = null
    private var peripheral: Peripheral? = null
    private var lastKnownDevice: BleDeviceUi? = null

    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var notifyJob: Job? = null
    private var reconnectJob: Job? = null
    private var otaPrepareJob: Job? = null
    @Volatile
    private var otaIpAwaiter: CompletableDeferred<String>? = null
    private var disconnectDebounceJob: Job? = null

    private var manualDisconnect = false
    private val isHandlingDisconnect = AtomicBoolean(false)
    private var disconnectGeneration = 0

    private val writeCharacteristic = characteristicOf(
        service = NUS_SERVICE_UUID,
        characteristic = NUS_TX_CHARACTERISTIC_UUID
    )
    private val notifyCharacteristic = characteristicOf(
        service = NUS_SERVICE_UUID,
        characteristic = NUS_RX_CHARACTERISTIC_UUID
    )

    // -------------------------------------------------------------------------
    // Service lifecycle API
    // -------------------------------------------------------------------------

    fun attachServiceScope(scope: CoroutineScope) {
        serviceScope = scope
        log("Service scope attached")
    }

    fun detachServiceScope() {
        serviceScope = null
        log("Service scope detached")
    }

    /**
     * Idempotent entry point for background BLE. Called from [ForegroundBleService].
     */
    fun startForegroundBleOperations() {
        val scope = serviceScope
        if (scope == null) {
            log("startForegroundBleOperations: service scope not attached")
            return
        }

        manualDisconnect = false
        foregroundOpsRunning.set(true)

        log("Starting foreground BLE operations")
        ForegroundBleService.instance?.updateNotification("Connecting…")
        // Always schedule recovery — the singleton may outlive a prior service instance.
        scheduleRecovery(scope)
    }

    /**
     * Stops scan/reconnect/observe jobs. Does not cancel [appScope] so UI/logging still works.
     */
    fun stopForegroundBleOperations() {
        if (!foregroundOpsRunning.compareAndSet(true, false)) {
            return
        }

        log("Stopping foreground BLE operations")

        disconnectDebounceJob?.cancel()
        disconnectDebounceJob = null

        reconnectJob?.cancel()
        reconnectJob = null

        notifyJob?.cancel()
        notifyJob = null

        connectionJob?.cancel()
        connectionJob = null

        stopScanInternal()

        val scope = serviceScope
        if (scope != null) {
            scope.launch {
                try {
                    peripheral?.disconnect()
                } catch (_: Exception) {
                }
                peripheral = null
                updatePhase(BleConnectionPhase.Disconnected)
            }
        } else {
            peripheral = null
            updatePhase(BleConnectionPhase.Disconnected)
        }
    }

    // -------------------------------------------------------------------------
    // UI / app entry points (do not start duplicate foreground loops)
    // -------------------------------------------------------------------------

    /** Manual scan from UI; safe to call while the foreground service is running. */
    fun startScan() {
        val scope = serviceScope ?: appScope
        manualDisconnect = false
        startScanIfNeeded(scope)
    }

    fun stopScan() {
        stopScanInternal()
    }

    fun connect(device: BleDeviceUi) {
        val scope = serviceScope ?: appScope
        manualDisconnect = false
        connectInternal(scope, device)
    }

    fun disconnect() {
        manualDisconnect = true
        disconnectDebounceJob?.cancel()
        disconnectDebounceJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        notifyJob?.cancel()
        notifyJob = null
        connectionJob?.cancel()
        connectionJob = null
        stopScanInternal()

        val activePeripheral = peripheral
        peripheral = null

        val scope = serviceScope ?: appScope
        scope.launch {
            try {
                activePeripheral?.disconnect()
            } catch (_: Exception) {
            }
            updatePhase(BleConnectionPhase.Disconnected)
            ForegroundBleService.instance?.updateNotification("Disconnected")
        }
    }

    /**
     * Sends `OTA_START` and waits up to 20s for NOTIFY `IP:…` as the ready signal.
     * Does not upload firmware from Android.
     */
    fun enterOtaMode() {
        val scope = serviceScope ?: appScope

        if (_uiState.value.phase != BleConnectionPhase.Connected || peripheral == null) {
            _uiState.update {
                it.copy(
                    otaPreparePhase = OtaPreparePhase.Failed,
                    otaPrepareMessage = "Connect to the display before entering OTA mode"
                )
            }
            return
        }

        if (_uiState.value.otaPreparePhase == OtaPreparePhase.Waiting) {
            return
        }

        otaPrepareJob?.cancel()
        otaPrepareJob = scope.launch {
            var ipAwaiter: CompletableDeferred<String>? = null
            try {
                _uiState.update {
                    it.copy(
                        otaModeActive = true,
                        otaPreparePhase = OtaPreparePhase.Waiting,
                        otaPrepareMessage = null
                    )
                }

                val activePeripheral = peripheral
                    ?: throw IllegalStateException("Not connected")

                ipAwaiter = CompletableDeferred()
                otaIpAwaiter = ipAwaiter

                activePeripheral.write(
                    characteristic = writeCharacteristic,
                    data = OTA_START_COMMAND.encodeToByteArray()
                )
                log("OTA: sent $OTA_START_COMMAND, waiting for IP:…")

                val ip = withTimeout(OTA_PREPARE_TIMEOUT_MS) {
                    ipAwaiter.await()
                }

                _uiState.update {
                    it.copy(
                        otaModeActive = true,
                        otaPreparePhase = OtaPreparePhase.Ready,
                        otaPrepareMessage = OTA_READY_MESSAGE,
                        espIp = ip
                    )
                }
                log("OTA: device ready, IP: $ip")
                ForegroundBleService.instance?.updateNotification("OTA ready — $ip")
            } catch (_: TimeoutCancellationException) {
                _uiState.update {
                    it.copy(
                        otaModeActive = false,
                        otaPreparePhase = OtaPreparePhase.TimedOut,
                        otaPrepareMessage =
                            "No IP: response within ${OTA_PREPARE_TIMEOUT_MS / 1000} seconds"
                    )
                }
                log("OTA: timed out waiting for IP:")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        otaModeActive = false,
                        otaPreparePhase = OtaPreparePhase.Failed,
                        otaPrepareMessage = e.message ?: "OTA prepare failed"
                    )
                }
                logError("OTA prepare failed", e)
            } finally {
                ipAwaiter?.cancel()
                if (otaIpAwaiter === ipAwaiter) {
                    otaIpAwaiter = null
                }
            }
        }
    }

    fun exitOtaMode() {
        otaPrepareJob?.cancel()
        otaPrepareJob = null
        otaIpAwaiter?.cancel()
        otaIpAwaiter = null
        _uiState.update {
            it.copy(
                otaModeActive = false,
                otaPreparePhase = OtaPreparePhase.Idle,
                otaPrepareMessage = null
            )
        }
        log("OTA mode cleared on phone (ESP may still be in OTA)")
    }

    fun sendText(text: String) {
        if (_uiState.value.otaModeActive) {
            log("Blocked send (OTA mode active): $text")
            return
        }

        val activePeripheral = peripheral ?: return
        val scope = serviceScope ?: appScope

        scope.launch {
            try {
                activePeripheral.write(
                    characteristic = writeCharacteristic,
                    data = text.encodeToByteArray()
                )
                log("Sent: $text")
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logError("Send failed", e)
                }
            }
        }
    }

    fun close() {
        stopForegroundBleOperations()
        disconnect()
        appScope.cancel()
    }

    // -------------------------------------------------------------------------
    // Scan
    // -------------------------------------------------------------------------

    private fun startScanIfNeeded(scope: CoroutineScope) {
        if (scanJob?.isActive == true) {
            return
        }
        if (_uiState.value.phase == BleConnectionPhase.Connected) {
            return
        }

        stopScanInternal()

        _uiState.update {
            it.copy(
                phase = BleConnectionPhase.Scanning,
                isScanning = true
            )
        }

        val localScanner = Scanner()
        scanner = localScanner

        scanJob = scope.launch {
            try {
                localScanner.advertisements.collect { advertisement ->
                    val name = advertisement.name ?: return@collect
                    if (name != TARGET_DEVICE_NAME) return@collect

                    val device = BleDeviceUi(
                        name = name,
                        address = advertisement.address,
                        peripheral = peripheral(advertisement)
                    )

                    _uiState.update { state ->
                        val list = state.devices.toMutableList()
                        if (list.none { it.address == device.address }) {
                            list.add(device)
                        }
                        state.copy(devices = list)
                    }

                    log("Found device: ${device.name} (${device.address})")

                    if (shouldAutoConnectFromScan()) {
                        log("Auto-connecting from scan")
                        connectInternal(scope, device)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logError("Scan failed", e)
                }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    private fun stopScanInternal() {
        scanJob?.cancel()
        scanJob = null
        scanner = null

        _uiState.update { state ->
            val phase = if (state.phase == BleConnectionPhase.Scanning) {
                BleConnectionPhase.Disconnected
            } else {
                state.phase
            }
            state.copy(isScanning = false, phase = phase)
        }
    }

    // -------------------------------------------------------------------------
    // Connect / observe / reconnect
    // -------------------------------------------------------------------------

    private fun shouldAutoConnectFromScan(): Boolean {
        val phase = _uiState.value.phase
        return peripheral == null &&
            connectionJob?.isActive != true &&
            reconnectJob?.isActive != true &&
            !manualDisconnect &&
            phase != BleConnectionPhase.Connected &&
            phase != BleConnectionPhase.Connecting
    }

    private fun connectInternal(scope: CoroutineScope, device: BleDeviceUi) {
        if (connectionJob?.isActive == true) {
            log("Connect skipped: connection already in progress")
            return
        }

        disconnectGeneration++
        disconnectDebounceJob?.cancel()
        disconnectDebounceJob = null

        connectionJob?.cancel()
        notifyJob?.cancel()
        notifyJob = null

        peripheral = device.peripheral
        lastKnownDevice = device
        reconnectJob?.cancel()
        reconnectJob = null

        connectionJob = scope.launch {
            try {
                updatePhase(BleConnectionPhase.Connecting)
                ForegroundBleService.instance?.updateNotification("Connecting…")
                log("Connecting to ${device.address}")

                withTimeout(CONNECT_TIMEOUT_MS) {
                    device.peripheral.connect()
                }

                stopScanInternal()
                updatePhase(BleConnectionPhase.Connected)
                ForegroundBleService.instance?.updateNotification("Connected")
                log("Connected to ${device.name}")

                startNotifyObserver(scope, device.peripheral)

                var hasSeenConnected = false
                device.peripheral.state.collect { state ->
                    when (state) {
                        is State.Connected -> {
                            hasSeenConnected = true
                            updatePhase(BleConnectionPhase.Connected)
                        }

                        is State.Disconnected -> {
                            if (!hasSeenConnected) {
                                log("Ignoring initial Disconnected state before link up")
                                return@collect
                            }
                            log("Peripheral disconnected (debouncing)")
                            scheduleDisconnectHandling(scope)
                            return@collect
                        }

                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    return@launch
                }
                logError("Connection failed", e)
                scheduleDisconnectHandling(scope, immediate = true)
            }
        }
    }

    /**
     * Ignores brief link drops from [State.Disconnected] before reconnecting.
     * Connection failures use [immediate] because the session is already dead.
     */
    private fun scheduleDisconnectHandling(scope: CoroutineScope, immediate: Boolean = false) {
        val generation = ++disconnectGeneration
        disconnectDebounceJob?.cancel()
        disconnectDebounceJob = scope.launch {
            if (!immediate) {
                delay(DISCONNECT_DEBOUNCE_MS)
            }
            if (generation != disconnectGeneration) {
                return@launch
            }
            handleDisconnect(scope)
        }
    }

    /**
     * One recovery path: scan when unknown, direct reconnect when we have an address.
     * [afterDisconnect] uses a short delay so recovery does not fight a flaky link.
     */
    private fun scheduleRecovery(scope: CoroutineScope, afterDisconnect: Boolean = false) {
        if (manualDisconnect || !foregroundOpsRunning.get()) {
            return
        }
        if (_uiState.value.phase == BleConnectionPhase.Connected) {
            return
        }
        if (connectionJob?.isActive == true || reconnectJob?.isActive == true) {
            return
        }

        val known = lastKnownDevice
        if (known != null) {
            ensureReconnectScheduled(
                scope,
                skipInitialDelay = !afterDisconnect
            )
        } else {
            startScanIfNeeded(scope)
        }
    }

    private fun startNotifyObserver(scope: CoroutineScope, activePeripheral: Peripheral) {
        notifyJob?.cancel()
        notifyJob = scope.launch {
            try {
                activePeripheral.observe(notifyCharacteristic).collect { data ->
                    handleEspNotification(decodeEspPayload(data))
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logError("Notify observer failed", e)
                }
            }
        }
    }

    /** ESP NOTIFY payloads, e.g. `IP:192.168.1.42` (often null-terminated from firmware). */
    private fun decodeEspPayload(data: ByteArray): String =
        data
            .decodeToString()
            .trim { it <= ' ' || it == '\u0000' }

    private fun handleEspNotification(raw: String) {
        if (raw.isEmpty()) return

        raw.lineSequence()
            .map { it.trim { ch -> ch <= ' ' || ch == '\u0000' } }
            .filter { it.isNotEmpty() }
            .forEach { line -> handleEspLine(line) }
    }

    private fun handleEspLine(message: String) {
        log("ESP: $message")

        when {
            message.startsWith("IP:", ignoreCase = true) -> {
                val ip = message.substringAfter(":").trim()
                if (ip.isEmpty()) return
                deliverIpNotify(message, ip)
            }

            message.startsWith("WiFi:", ignoreCase = true) -> {
                val value = message.substringAfter(":").trim()
                if (_uiState.value.otaPreparePhase == OtaPreparePhase.Waiting) {
                    log("OTA: received WiFi notify: $message")
                }
                _uiState.update { it.copy(wifiStatus = value) }
            }

            message.startsWith("OTA:", ignoreCase = true) -> {
                log("OTA: received notify (ignored for ready): $message")
            }

            message.equals("WIFI_FAILED", ignoreCase = true) -> {
                log("OTA: received WiFi failure notify: $message")
                _uiState.update { it.copy(wifiStatus = "Failed") }
                failOtaPrepareIfWaiting("WiFi connection failed on ESP")
            }

            else -> {
                if (_uiState.value.otaPreparePhase == OtaPreparePhase.Waiting) {
                    log("OTA: received notify while waiting for IP: $message")
                } else {
                    log("ESP (unhandled): $message")
                }
            }
        }
    }

    private fun deliverIpNotify(rawMessage: String, ip: String) {
        val waitingForOta = _uiState.value.otaPreparePhase == OtaPreparePhase.Waiting

        if (waitingForOta) {
            log("OTA: received IP notify: $rawMessage")
        }

        _uiState.update { it.copy(espIp = ip) }

        if (waitingForOta) {
            val awaiter = otaIpAwaiter
            if (awaiter != null && !awaiter.isCompleted) {
                awaiter.complete(ip)
            } else {
                log("OTA: duplicate IP notify ignored: $rawMessage")
            }
            return
        }

        ForegroundBleService.instance?.updateNotification("ESP $ip")
    }

    private fun failOtaPrepareIfWaiting(reason: String) {
        if (_uiState.value.otaPreparePhase != OtaPreparePhase.Waiting) {
            return
        }
        log("OTA: prepare failed — $reason")
        otaIpAwaiter?.cancel()
        otaIpAwaiter = null
        otaPrepareJob?.cancel()
        otaPrepareJob = null
        _uiState.update {
            it.copy(
                otaModeActive = false,
                otaPreparePhase = OtaPreparePhase.Failed,
                otaPrepareMessage = reason
            )
        }
        log("OTA prepare failed: $reason")
    }

    private fun handleDisconnect(scope: CoroutineScope) {
        if (!isHandlingDisconnect.compareAndSet(false, true)) {
            return
        }

        try {
            disconnectDebounceJob?.cancel()
            disconnectDebounceJob = null

            otaPrepareJob?.cancel()
            otaPrepareJob = null
            otaIpAwaiter?.cancel()
            otaIpAwaiter = null
            _uiState.update {
                it.copy(
                    otaModeActive = false,
                    otaPreparePhase = OtaPreparePhase.Idle,
                    otaPrepareMessage = null
                )
            }

            connectionJob?.cancel()
            connectionJob = null

            notifyJob?.cancel()
            notifyJob = null

            val activePeripheral = peripheral
            peripheral = null

            scope.launch {
                try {
                    activePeripheral?.disconnect()
                } catch (_: Exception) {
                }
            }

            updatePhase(BleConnectionPhase.Disconnected)
            ForegroundBleService.instance?.updateNotification("Disconnected")
            log("Link down — scheduling recovery")

            scheduleRecovery(scope, afterDisconnect = true)
        } finally {
            isHandlingDisconnect.set(false)
        }
    }

    private fun ensureReconnectScheduled(
        scope: CoroutineScope,
        skipInitialDelay: Boolean = false
    ) {
        if (manualDisconnect || !foregroundOpsRunning.get()) {
            return
        }
        if (reconnectJob?.isActive == true) {
            return
        }
        if (_uiState.value.phase == BleConnectionPhase.Connected) {
            return
        }
        if (connectionJob?.isActive == true) {
            return
        }

        val known = lastKnownDevice ?: return

        reconnectJob = scope.launch {
            updatePhase(BleConnectionPhase.Reconnecting)
            ForegroundBleService.instance?.updateNotification("Reconnecting…")
            if (!skipInitialDelay) {
                delay(RECONNECT_INITIAL_DELAY_MS)
            }

            while (
                isActive &&
                foregroundOpsRunning.get() &&
                !manualDisconnect
            ) {
                if (connectionJob?.isActive == true) {
                    delay(RECONNECT_POLL_MS)
                    continue
                }
                if (_uiState.value.phase == BleConnectionPhase.Connected) {
                    break
                }

                try {
                    log("Reconnect attempt to ${known.address}")
                    val freshPeripheral = scope.peripheral(known.address)
                    val freshDevice = BleDeviceUi(
                        name = known.name,
                        address = known.address,
                        peripheral = freshPeripheral
                    )
                    lastKnownDevice = freshDevice
                    connectInternal(scope, freshDevice)

                    delay(RECONNECT_ATTEMPT_WINDOW_MS)
                    if (_uiState.value.phase == BleConnectionPhase.Connected) {
                        log("Reconnect succeeded")
                        break
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        return@launch
                    }
                    logError("Reconnect attempt failed", e)
                }

                delay(RECONNECT_INTERVAL_MS)
            }

            reconnectJob = null
            if (_uiState.value.phase != BleConnectionPhase.Connected) {
                updatePhase(BleConnectionPhase.Disconnected)
            }
        }
    }

    private fun updatePhase(phase: BleConnectionPhase) {
        _uiState.update { it.copy(phase = phase) }
    }

    // -------------------------------------------------------------------------
    // Logging
    // -------------------------------------------------------------------------

    fun log(message: String) {
        Log.d(TAG, message)
        _uiState.update {
            it.copy(logs = (it.logs + message).takeLast(MAX_LOG_LINES))
        }
    }

    fun logError(message: String, throwable: Throwable) {
        Log.e(TAG, message, throwable)
        _uiState.update {
            it.copy(
                logs = (
                    it.logs + "$message: ${throwable.message}"
                    ).takeLast(MAX_LOG_LINES)
            )
        }
    }

    companion object {
        private const val TAG = "BleManager"
        private const val TARGET_DEVICE_NAME = "MotoNotifyDisplay"

        private const val NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        private const val NUS_TX_CHARACTERISTIC_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        private const val NUS_RX_CHARACTERISTIC_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val DISCONNECT_DEBOUNCE_MS = 1_500L
        private const val RECONNECT_INITIAL_DELAY_MS = 2_000L
        private const val RECONNECT_INTERVAL_MS = 8_000L
        private const val RECONNECT_ATTEMPT_WINDOW_MS = 25_000L
        private const val RECONNECT_POLL_MS = 2_000L
        private const val MAX_LOG_LINES = 40

        private const val OTA_START_COMMAND = "OTA_START"
        private const val OTA_READY_MESSAGE = "Device ready for OTA"
        private const val OTA_PREPARE_TIMEOUT_MS = 20_000L
    }
}

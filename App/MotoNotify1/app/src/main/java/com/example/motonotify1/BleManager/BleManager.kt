package com.example.motonotify1.BleManager

import android.util.Log
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.juul.kable.peripheral
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import com.example.motonotify1.service.foregroundService.MotoForegroundService

data class BleDeviceUi(
    val name: String,
    val address: String,
    val peripheral: Peripheral
)

data class BleUiState(
    val isScanning: Boolean = false,
    val connectionState: String = "Disconnected",
    val devices: List<BleDeviceUi> = emptyList(),
    val logs: List<String> = emptyList()
)

class BleManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    private var scanner: Scanner? = null
    private var peripheral: Peripheral? = null
    private var lastKnownDevice: BleDeviceUi? = null
    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null

    private var manualDisconnect = false

    private val writeCharacteristic = characteristicOf(
        service = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
        characteristic = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    )

    fun startScan() {

        stopScan()

        _uiState.update {
            it.copy(
                isScanning = true,
                devices = emptyList()
            )
        }

        val localScanner = Scanner()
        scanner = localScanner

        scanJob = scope.launch {

            localScanner.advertisements.collect { advertisement ->

                val name = advertisement.name ?: return@collect

                if (name != "MotoNotifyDisplay") return@collect

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

                log("Found device: ${device.name}")
                if (
                    peripheral == null &&
                    connectionJob?.isActive != true
                ) {

                    log("Auto connecting")

                    connect(device)
                }

            }
        }
    }


    fun stopScan() {
        scanJob?.cancel()
        scanJob = null

        _uiState.update {
            it.copy(isScanning = false)
        }
    }

    fun connect(device: BleDeviceUi) {


        if (connectionJob?.isActive == true) {
            log("Already connecting")
            return
        }
        peripheral = device.peripheral
        lastKnownDevice = device
        manualDisconnect = false

        connectionJob = scope.launch {


            try {
                _uiState.update {
                    it.copy(connectionState = "Connecting")
                }

                log("Before connect")

                withTimeout(15000) {
                    device.peripheral.connect()
                }

                log("After connect")



                _uiState.update {
                    it.copy(connectionState = "Connected")
                }

                log("Connected")
                MotoForegroundService.instance
                    ?.updateNotification("Connected")

                device.peripheral.state.collect { state ->

                    when (state) {

                        is State.Connected -> {

                            _uiState.update {
                                it.copy(connectionState = "Connected")
                            }
                        }

                        is State.Disconnected -> {

                            log("Disconnected")
                            MotoForegroundService.instance
                                ?.updateNotification("Disconnected")

                            _uiState.update {
                                it.copy(connectionState = "Disconnected")
                            }
                            connectionJob = null
                            peripheral = null
                            if (!manualDisconnect) {
                                startScan()

                            }
                        }

                        else -> {}
                    }
                }

            } catch (e: Exception) {

                if (e is CancellationException) {
                    return@launch
                }

                logError("Connection failed", e)
                connectionJob = null
                _uiState.update {
                    it.copy(connectionState = "Disconnected")
                }
            }
        }
    }

    private fun startReconnectLoop() {

        if (reconnectJob?.isActive == true) {
            return
        }

        val device = lastKnownDevice ?: return

        reconnectJob = scope.launch {
            delay(3000)
            while (!manualDisconnect) {

                try {

                    log("Reconnect attempt...")
                    MotoForegroundService.instance
                        ?.updateNotification("Reconnecting...")

                    connect(device)

                    log("Reconnect success")

                    _uiState.update {
                        it.copy(connectionState = "Connected")
                    }

                    break

                } catch (e: Exception) {

                    if (e is CancellationException) {
                        return@launch
                    }

                    logError("Reconnect failed", e)
                }

                delay(5000)
            }
        }
    }

    fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        connectionJob?.cancel()
        scope.launch {

            try {
                peripheral?.disconnect()
            } catch (_: Exception) {
            }

            _uiState.update {
                it.copy(connectionState = "Disconnected")
            }
        }
    }

    fun sendText(text: String) {

        val localPeripheral = peripheral ?: return

        scope.launch {

            try {

                localPeripheral.write(
                    characteristic = writeCharacteristic,
                    data = text.encodeToByteArray()
                )

                log("Sent: $text")

            } catch (e: Exception) {
                logError("Send failed", e)
            }
        }
    }

    fun close() {
        stopScan()
        disconnect()
        scope.cancel()
    }

    fun log(message: String) {

        Log.d("BleManager", message)

        _uiState.update {
            it.copy(
                logs = (it.logs + message).takeLast(40)
            )
        }
    }

    fun logError(message: String, throwable: Throwable) {

        Log.e("BleManager", message, throwable)

        _uiState.update {
            it.copy(
                logs = (
                        it.logs +
                                "$message: ${throwable.message}"
                        ).takeLast(40)
            )
        }
    }
}

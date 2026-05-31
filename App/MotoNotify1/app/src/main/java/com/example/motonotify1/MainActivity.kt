package com.example.motonotify1

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.example.motonotify1.bleManager.BleConnectionPhase
import com.example.motonotify1.bleManager.OtaPreparePhase
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.motonotify1.bleManager.BleManagerProvider
import com.example.motonotify1.bleManager.TpmsBleManagerProvider
import com.example.motonotify1.bleManager.TpmsReadStatus
import com.example.motonotify1.bleManager.TpmsUiState
import com.example.motonotify1.ui.theme.MotoNotify1Theme
import android.content.Intent
import com.example.motonotify1.service.foregroundService.ForegroundBleService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MotoNotify1Theme {
                BleTestScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun BleTestScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bleManager = remember {
        BleManagerProvider.bleManager
    }
    val tpmsBleManager = remember {
        TpmsBleManagerProvider.tpmsBleManager
    }
    val uiState by bleManager.uiState.collectAsState()
    val tpmsState by tpmsBleManager.uiState.collectAsState()
    var textToSend by remember { mutableStateOf("") }

        val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CONTACTS
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasAllPermissions(): Boolean =
        permissions.all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ForegroundBleService::class.java)
            )
        }
    }

    DisposableEffect(Unit) {
        if (hasAllPermissions()) {

            val serviceIntent = Intent(
                context,
                ForegroundBleService::class.java
            )
            ContextCompat.startForegroundService(
                context,
                serviceIntent
            )

        } else {

            permissionLauncher.launch(permissions)
        }
        onDispose {

        }
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "State: ${uiState.connectionState}${if (uiState.isScanning) " | Scanning" else ""}",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "ESP IP: ${uiState.espIp}",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "WiFi: ${uiState.wifiStatus}",
            style = MaterialTheme.typography.bodyMedium
        )

        TpmsStatusCard(tpmsState = tpmsState)

        val isConnected = uiState.phase == BleConnectionPhase.Connected
        val otaWaiting = uiState.otaPreparePhase == OtaPreparePhase.Waiting
        val notificationsBlocked = uiState.otaModeActive

        Button(
            onClick = { bleManager.enterOtaMode() },
            enabled = isConnected && !otaWaiting
        ) {
            Text("Enter OTA Mode")
        }

        if (otaWaiting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    text = "Waiting for IP:…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (uiState.otaPreparePhase == OtaPreparePhase.Ready) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = uiState.otaPrepareMessage ?: "Device ready for OTA",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = uiState.espIp,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "Upload firmware from your computer to this address.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            uiState.otaPrepareMessage?.let { message ->
                val isError = uiState.otaPreparePhase == OtaPreparePhase.TimedOut ||
                    uiState.otaPreparePhase == OtaPreparePhase.Failed
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (isError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (notificationsBlocked && uiState.otaPreparePhase == OtaPreparePhase.Ready) {
            OutlinedButton(onClick = { bleManager.exitOtaMode() }) {
                Text("Resume notifications")
            }
        }

        if (notificationsBlocked) {
            Text(
                text = "Notification sending paused (OTA mode)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Button(
            onClick = {

                permissions.forEach {

                    bleManager.log(
                        "$it = ${
                            ContextCompat.checkSelfPermission(
                                context,
                                it
                            ) == PackageManager.PERMISSION_GRANTED
                        }"
                    )
                }

                permissionLauncher.launch(permissions)
            }
        ) {
            Text("Request Permissions")
        }
        Button(
            onClick = {
                if (hasAllPermissions()) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ForegroundBleService::class.java)
                    )
                    bleManager.startScan()
                } else {
                    bleManager.log("Missing permissions")
                }
            }
        ) {
            Text("Start Scan")
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f, fill = true),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.devices, key = { it.address }) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { bleManager.connect(device) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = device.name)
                        Text(text = device.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textToSend,
                onValueChange = { textToSend = it },
                label = { Text("Text to send") },
                modifier = Modifier.weight(1f),
                enabled = !notificationsBlocked
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    bleManager.sendText(textToSend)
                    textToSend = ""
                },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !notificationsBlocked && textToSend.isNotBlank()
            ) {
                Text("Send")
            }
        }
        Text(
            text = "Logs",
            style = MaterialTheme.typography.titleMedium
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {

            items(uiState.logs.reversed()) { log ->

                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun TpmsStatusCard(tpmsState: TpmsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "TPMS (RiDEET Pro)",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Cycle: ${tpmsState.cycleStatus}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Last update: ${tpmsState.lastUpdateTimeText}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "BT: ${if (tpmsState.bluetoothAvailable) "On" else "Off"} | " +
                    "Permissions: ${if (tpmsState.permissionsGranted) "OK" else "Missing"}",
                style = MaterialTheme.typography.bodySmall
            )
            TpmsSensorRow(
                label = "Front",
                pressureBar = tpmsState.front.pressureBar,
                temperatureC = tpmsState.front.temperatureC,
                status = tpmsState.front.status,
                error = tpmsState.front.lastError
            )
            TpmsSensorRow(
                label = "Rear",
                pressureBar = tpmsState.rear.pressureBar,
                temperatureC = tpmsState.rear.temperatureC,
                status = tpmsState.rear.status,
                error = tpmsState.rear.lastError
            )
        }
    }
}

@Composable
private fun TpmsSensorRow(
    label: String,
    pressureBar: Double?,
    temperatureC: Int?,
    status: TpmsReadStatus,
    error: String?
) {
    val pressureText = pressureBar?.let { "%.2f bar".format(it) } ?: "—"
    val temperatureText = temperatureC?.let { "$it °C" } ?: "—"

    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = "$label: $pressureText | $temperatureText | ${status.name}",
            style = MaterialTheme.typography.bodyMedium
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
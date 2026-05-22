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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.example.motonotify1.BleManager.BleManagerProvider
import com.example.motonotify1.ui.theme.MotoNotify1Theme
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat.startForegroundService
import com.example.motonotify1.BleManager.ForegroundBleService

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
    val uiState by bleManager.uiState.collectAsState()
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
            bleManager.startScan()
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
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    bleManager.sendText(textToSend)
                    textToSend = ""
                },
                modifier = Modifier.padding(start = 8.dp)
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
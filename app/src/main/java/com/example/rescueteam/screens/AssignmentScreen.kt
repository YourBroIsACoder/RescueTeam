package com.example.rescueteam.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items // Import items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel


@SuppressLint("MissingPermission") // Ensure permissions are checked before calling scan/connect
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentScreen( // Consider renaming to BluetoothScanScreen
    onNavigateToChat: (deviceId: String) -> Unit, // Callback for navigation
    viewModel: AssignmentViewModel = viewModel()
) {
    // Collect state from the ViewModel
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect to Victim") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.Top, // Align content to the top
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Display current connection status
            Text("Status: $connectionStatus")

            Spacer(modifier = Modifier.height(16.dp))

            // Button to start or stop scanning
            Button(
                onClick = {
                    if (isScanning) viewModel.stopScanning() else viewModel.startScanning()
                },
                // Disable button if already trying to connect (optional)
                // enabled = connectionStatus == "Disconnected" || connectionStatus == "Scanning..."
            ) {
                Text(if (isScanning) "Stop Scan" else "Scan for Victims")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Show progress indicator and text while scanning
            if (isScanning) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Scanning...")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Display the list of discovered devices
            if (discoveredDevices.isEmpty() && !isScanning) {
                Text("No devices found nearby. Ensure victim app is discoverable.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Use device address as a stable key
                    items(discoveredDevices, key = { it.address }) { device ->
                        DiscoveredDeviceItem(
                            device = device,
                            onClick = {
                                // Attempt to connect when an item is clicked
                                viewModel.connectToDevice(device.address) { success ->
                                    if (success) {
                                        // Navigate to chat screen on successful connection
                                        onNavigateToChat(device.address) // Pass address or ID
                                    } else {
                                        // Handle connection failure (e.g., show a Toast)
                                        // You might want to update connectionStatus in ViewModel
                                    }
                                }
                            }
                        )
                        HorizontalDivider() // Separator between items
                    }
                }
            }
        }
    }
}

// Composable for displaying a single discovered Bluetooth device
@Composable
fun DiscoveredDeviceItem(
    device: DiscoveredDevice,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick) // Make the whole row clickable
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                // Show name or "Unknown" if name is null/empty
                text = if (device.name.isNullOrBlank()) "Unknown Device" else device.name,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = device.address, // Display the MAC address
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}
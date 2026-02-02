package com.example.rescueteam.screens

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice // Import BluetoothDevice
import androidx.lifecycle.AndroidViewModel // Use AndroidViewModel for context
import androidx.lifecycle.viewModelScope
import com.example.rescueteam.service.BluetoothManagerProvider // Import the provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log // Import Log

// Simple data class for UI representation of a discovered device
data class DiscoveredDevice(
    val name: String?,
    val address: String
)

// Use AndroidViewModel to get application context needed for BluetoothManager
@SuppressLint("MissingPermission") // Permissions MUST be checked in the UI before calling these functions
class AssignmentViewModel(application: Application) : AndroidViewModel(application) {

    // Get the shared instance of BluetoothChatManager
    private val bluetoothManager = BluetoothManagerProvider.getInstance(application.applicationContext)

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    // StateFlow holding the list of discovered devices for the UI
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices = _discoveredDevices.asStateFlow()

    // Expose connection status from the manager
    val connectionStatus = bluetoothManager.connectionStatus
    // Expose messages if needed directly (though not typical for assignment screen)
    // val messages = bluetoothManager.messages

    fun startScanning() {
        if (_isScanning.value) return // Already scanning

        viewModelScope.launch {
            _isScanning.value = true
            _discoveredDevices.value = emptyList() // Clear previous results

            Log.d("AssignmentViewModel", "Attempting to start Bluetooth scan...")

            // Call the manager's startScan with callbacks
            bluetoothManager.startScan(
                onDeviceFound = { device ->
                    // This callback updates the list as devices are found
                    val uiDevice = mapToUiDevice(device)
                    // Add device only if it's not already in the list
                    // Use thread-safe update for StateFlow
                    _discoveredDevices.value = _discoveredDevices.value.let { list ->
                        if (list.none { it.address == uiDevice.address }) {
                            list + uiDevice
                        } else {
                            list
                        }
                    }
                    Log.d("AssignmentViewModel", "Device found: ${uiDevice.name} (${uiDevice.address})")
                },
                onScanFinished = {
                    _isScanning.value = false
                    Log.d("AssignmentViewModel", "Scan finished.")
                }
            )
        }
    }

    fun stopScanning() {
        if (!_isScanning.value) return
        Log.d("AssignmentViewModel", "Stopping Bluetooth scan...")
        bluetoothManager.stopScan() // Assuming your manager has this method
        // _isScanning.value = false // Let onScanFinished callback handle this
    }

    // Connect to the selected device
    fun connectToDevice(deviceAddress: String, onConnected: (Boolean) -> Unit) {
        if (isScanning.value) {
            stopScanning() // Stop scanning before attempting to connect
        }
        viewModelScope.launch {
            Log.d("AssignmentViewModel", "Attempting to connect to $deviceAddress")
            bluetoothManager.connect(deviceAddress) { success ->
                Log.d("AssignmentViewModel", "Connection attempt result: $success")
                onConnected(success) // Call the callback with connection result
            }
        }
    }

    // Helper to convert BluetoothDevice to a simpler UI object (Keep this)
    @SuppressLint("MissingPermission")
    private fun mapToUiDevice(device: BluetoothDevice): DiscoveredDevice {
        // Handle potential SecurityException when accessing device name
        val deviceName = try {
            device.name
        } catch (e: SecurityException) {
            Log.e("AssignmentViewModel", "Permission missing to get device name for ${device.address}", e)
            null // Or return a placeholder like "Name requires permission"
        }
        return DiscoveredDevice(
            name = deviceName ?: "Unknown Device",
            address = device.address
        )
    }


    override fun onCleared() {
        super.onCleared()
        // Ensure scanning stops if ViewModel is cleared while scanning
        if (_isScanning.value) {
            stopScanning()
        }
        // Don't call bluetoothManager.cleanup() here.
        // Let the ChatViewModel or Application lifecycle handle cleanup
        // of the shared manager instance.
        Log.d("AssignmentViewModel", "onCleared")
    }
}


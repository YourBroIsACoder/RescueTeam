package com.example.rescueteam.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager // Added
import android.location.Location
import android.location.LocationListener
import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.rescueteam.data.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive // Import isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import android.provider.Settings
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase


// Permissions required: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION (for discovery)
@SuppressLint("MissingPermission") // Permissions MUST be checked before using this class
class BluetoothChatManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var clientSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var listeningJob: Job? = null
    private var connectionJob: Job? = null
    private var locationJob: Job? = null // Job for location sharing
    private var locationListener: LocationListener? = null // Listener for active updates

    // This is the UUID your Victim App must be listening with
    private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard SPP UUID

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus = _connectionStatus.asStateFlow()

    // Use SupervisorJob to prevent one failure cancelling others
    // CRITICAL: Must be defined BEFORE init block to avoid NullPointerException
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // PER REQUEST: Start location sharing immediately on creation (if permissions allowed)
        try {
            startLocationSharing()
        } catch (e: Exception) {
            Log.e("BluetoothChatManager", "Critical Error starting location sharing in init", e)
        }
    }

    // --- State for Scanning ---
    private var isScanning = false
    private var onDeviceFoundCallback: ((BluetoothDevice) -> Unit)? = null
    private var onScanFinishedCallback: (() -> Unit)? = null
    private var scanTimeoutJob: Job? = null

    // --- BroadcastReceiver for Discovery ---
    private val discoveryReceiver = object : BroadcastReceiver() {
        // ... (Receiver code is likely correct) ...
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        Log.d("BluetoothChatManager", "Device Found: ${it.name ?: "Unknown"} (${it.address})")
                        onDeviceFoundCallback?.invoke(it)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d("BluetoothChatManager", "Discovery Finished.")
                    isScanning = false
                    scanTimeoutJob?.cancel()
                    onScanFinishedCallback?.invoke()
                    try { context.unregisterReceiver(this) } catch (e: IllegalArgumentException) {
                        Log.w("BluetoothChatManager", "Receiver already unregistered.")
                    }
                }
            }
        }
    }

    // --- Public Functions ---

    // Implements the scan logic with callbacks
    fun startScan(
        onDeviceFound: (BluetoothDevice) -> Unit,
        onScanFinished: () -> Unit,
        scanDurationMillis: Long = 12000
    ) {
        // ... (startScan logic is likely correct) ...
        if (!hasPermissions()) {
            Log.e("BluetoothChatManager", "Required Bluetooth/Location permissions missing for scanning.")
            _connectionStatus.value = "Permission Error"
            onScanFinished()
            return
        }
        if (bluetoothAdapter == null) {
            Log.e("BluetoothChatManager", "Bluetooth adapter is NULL.")
            _connectionStatus.value = "Bluetooth Not Supported"
            onScanFinished()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.e("BluetoothChatManager", "Bluetooth is DISABLED.")
            _connectionStatus.value = "Bluetooth Disabled"
            onScanFinished()
            return
        }

        if (isScanning) {
            Log.w("BluetoothChatManager", "Already scanning.")
            return
        }

        Log.d("BluetoothChatManager", "Starting Bluetooth discovery...")
        startLocationSharing() // CRITICAL: Start broadcasting location automatically when scan starts

        isScanning = true
        this.onDeviceFoundCallback = onDeviceFound
        this.onScanFinishedCallback = onScanFinished
        _connectionStatus.value = "Scanning..."

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(discoveryReceiver, filter)
            }
        } catch (e: SecurityException) {
            Log.e("BluetoothChatManager", "SecurityException during registerReceiver. Permissions revoked?", e)
            isScanning = false
            onScanFinished()
            _connectionStatus.value = "Permission Error"
            return
        }

        // Check if System Location is enabled (Required for Scanning)
        if (!isLocationEnabled()) {
            Log.e("BluetoothChatManager", "System Location Services are DISABLED. Scanning will fail.")
            _connectionStatus.value = "Location Disabled"
            isScanning = false
            try { context.unregisterReceiver(discoveryReceiver) } catch (e: IllegalArgumentException) {}
            onScanFinished()
            return
        }

        // Diagnostic logs
        Log.d("BluetoothChatManager", "Adapter State: ${bluetoothAdapter.state} (12=ON), isDiscovering: ${bluetoothAdapter.isDiscovering}")

        if (bluetoothAdapter.isDiscovering) {
            Log.w("BluetoothChatManager", "Discovery already in progress. Cancelling first...")
            bluetoothAdapter.cancelDiscovery()
        }

        if (!bluetoothAdapter.startDiscovery()) {
            Log.e("BluetoothChatManager", "startDiscovery() failed IMMEDIATELY. Check Logcat for system 'BluetoothAdapter' errors.")
            isScanning = false
            try { context.unregisterReceiver(discoveryReceiver) } catch (e: IllegalArgumentException) {}
            onScanFinished()
            _connectionStatus.value = "Scan Failed"
            return
        } else {
            Log.d("BluetoothChatManager", "Bluetooth discovery started successfully.")
        }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = coroutineScope.launch {
            delay(scanDurationMillis)
            if (isScanning) {
                Log.w("BluetoothChatManager", "Scan timed out after ${scanDurationMillis}ms.")
                stopScan()
            }
        }
    }

    // Stops the scan explicitly
    fun stopScan() {
        // ... (stopScan logic is likely correct) ...
        if (bluetoothAdapter == null) return
        if (isScanning) {
            Log.d("BluetoothChatManager", "Stopping Bluetooth discovery...")
            try {
                bluetoothAdapter.cancelDiscovery()
            } catch (e: SecurityException) {
                Log.e("BluetoothChatManager", "SecurityException during cancelDiscovery", e)
                // Manually handle cleanup if cancelDiscovery fails
                isScanning = false
                scanTimeoutJob?.cancel()
                try { context.unregisterReceiver(discoveryReceiver) } catch (ignore: Exception) {}
                onScanFinishedCallback?.invoke() // Notify scan ended (due to error)
            }
        }
        scanTimeoutJob?.cancel()
    }

    // (MODIFIED) Connect logic with refined callback timing
    fun connect(deviceAddress: String, onResult: (Boolean) -> Unit) {
        if (isScanning) {
            stopScan()
        }
        if (!hasPermissions()) {
            Log.e("BluetoothChatManager", "Bluetooth permissions missing for connecting.")
            onResult(false)
            return
        }
        if (bluetoothAdapter == null) {
            Log.e("BluetoothChatManager", "Bluetooth adapter is null.")
            onResult(false)
            return
        }

        connectionJob?.cancel() // Cancel any previous attempt
        connectionJob = coroutineScope.launch { // Use the manager's scope
            _connectionStatus.value = "Connecting..."
            Log.d("BluetoothChatManager", "Attempting connection to $deviceAddress")
            var tempSocket: BluetoothSocket? = null // Temporary socket variable

            try {
                val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                Log.d("BluetoothChatManager", "Got remote device: ${device.name}")
                cleanupConnection() // Ensure previous connection is fully closed
                Log.d("BluetoothChatManager", "Creating RFCOMM socket...")
                tempSocket = device.createRfcommSocketToServiceRecord(MY_UUID)
                Log.d("BluetoothChatManager", "Socket created, attempting connect...")
                tempSocket?.connect() // Blocking call
                Log.d("BluetoothChatManager", "Connect successful!")

                // --- (REFINED LOGIC) ---
                // 1. Assign to class variable only AFTER successful connect
                clientSocket = tempSocket
                inputStream = clientSocket?.inputStream
                outputStream = clientSocket?.outputStream

                // 2. Update status and call onResult immediately on Main thread
                withContext(Dispatchers.Main) {
                    _connectionStatus.value = "Connected to ${device.name ?: deviceAddress}"
                    onResult(true) // <<< THIS TRIGGERS NAVIGATION
                }

                // 3. Start listening *after* confirming connection and notifying UI
                startListeningForMessages()
                // NOTE: Location sharing is auto-started in init{} block - no need to start here
                // --- (END REFINEMENT) ---

            } catch (e: IOException) {
                Log.e("BluetoothChatManager", "IOException during connect for $deviceAddress: ${e.message}", e)
                // Ensure temp socket is closed on failure
                try { tempSocket?.close() } catch (ignore: IOException) {}
                withContext(Dispatchers.Main) {
                    _connectionStatus.value = "Connection Failed: ${e.message}"
                    onResult(false)
                }
                cleanupConnection() // Clean up class variables if connection failed mid-setup
            } catch (e: SecurityException){
                Log.e("BluetoothChatManager", "SecurityException during connect: ${e.message}", e)
                try { tempSocket?.close() } catch (ignore: IOException) {}
                withContext(Dispatchers.Main) {
                    _connectionStatus.value = "Permission Error: ${e.message}"
                    onResult(false)
                }
                cleanupConnection()
            } catch (e: IllegalArgumentException) {
                Log.e("BluetoothChatManager", "IllegalArgumentException (Invalid Address?): $deviceAddress", e)
                try { tempSocket?.close() } catch (ignore: IOException) {}
                withContext(Dispatchers.Main) {
                    _connectionStatus.value = "Invalid Address"
                    onResult(false)
                }
                cleanupConnection()
            } catch (e: Exception) {
                // Catch JobCancellationException specifically
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.w("BluetoothChatManager", "Connection job cancelled: ${e.message}")
                    try { tempSocket?.close() } catch (ignore: IOException) {}
                    withContext(Dispatchers.Main) {
                        // Update status but don't call onResult(false) if cancelled externally
                        _connectionStatus.value = "Connection Cancelled"
                    }
                } else {
                    Log.e("BluetoothChatManager", "Unexpected exception during connect: ${e.message}", e)
                    try { tempSocket?.close() } catch (ignore: IOException) {}
                    withContext(Dispatchers.Main) {
                        _connectionStatus.value = "Unknown Error: ${e.message}"
                        onResult(false)
                    }
                }
                cleanupConnection()
            }
        }
    }

    fun sendMessage(message: String) {
        // ... (sendMessage logic is likely correct) ...
        if (clientSocket?.isConnected != true || outputStream == null) {
            Log.w("BluetoothChatManager", "Cannot send message, not connected.")
            _connectionStatus.value = "Send Failed: Not Connected"
            return
        }
        coroutineScope.launch {
            try {
                outputStream?.write(message.toByteArray())
                outputStream?.flush()
                Log.d("BluetoothChatManager", "Sent: $message")
                withContext(Dispatchers.Main) {
                    val chatMessage = ChatMessage(message, isSentByMe = true)
                    _messages.value = _messages.value + chatMessage
                }
            } catch (e: IOException) {
                Log.e("BluetoothChatManager", "Send failed", e)
                withContext(Dispatchers.Main) {
                    _connectionStatus.value = "Send Failed: ${e.message}"
                }
            }
        }
    }

    private fun cleanupConnection() {
        // ... (cleanupConnection logic is likely correct) ...
        Log.d("BluetoothChatManager", "Cleaning up Bluetooth connection.")
        Log.d("BluetoothChatManager", "⚠️ Location sharing continues running (not stopped by Bluetooth cleanup)")
        listeningJob?.cancel()
        // locationJob?.cancel() // Logic Change: Location sharing is now independent of Bluetooth connection
        // DO NOT CANCEL connectionJob here - it might cancel itself or be needed
        try { inputStream?.close() } catch (e: IOException) { Log.e("BluetoothChatManager", "Error closing input stream", e)}
        try { outputStream?.close() } catch (e: IOException) { Log.e("BluetoothChatManager", "Error closing output stream", e)}
        try { clientSocket?.close() } catch (e: IOException) { Log.e("BluetoothChatManager", "Error closing client socket", e)}
        finally {
            inputStream = null
            outputStream = null
            clientSocket = null
            val currentStatus = _connectionStatus.value
            // Only update to Disconnected if not already in an error state
            if (currentStatus.startsWith("Connected") || currentStatus.startsWith("Connecting")) {
                _connectionStatus.value = "Disconnected"
            }
            _messages.value = emptyList()
        }
    }

    private fun startListeningForMessages() {
        listeningJob?.cancel()
        listeningJob = coroutineScope.launch {
            Log.d("BluetoothChatManager", "Starting to listen for messages...")
            val buffer = ByteArray(1024)
            var bytes: Int

            while (isActive) {
                try {
                    val currentInputStream = inputStream ?: break
                    
                    // Check if data is available before attempting blocking read
                    if (currentInputStream.available() > 0) {
                        bytes = currentInputStream.read(buffer)
                        if (bytes > 0) {
                            val receivedMessage = String(buffer, 0, bytes)
                            Log.d("BluetoothChatManager", "Received: $receivedMessage")
                            val chatMessage = ChatMessage(receivedMessage, isSentByMe = false)

                            withContext(Dispatchers.Main) {
                                Log.d("BluetoothChatManager", "Updating _messages StateFlow on Main thread. Current size: ${_messages.value.size}")
                                _messages.value = _messages.value + chatMessage
                                Log.d("BluetoothChatManager", "_messages StateFlow updated. New size: ${_messages.value.size}")
                            }
                        } else if (bytes == -1) {
                            Log.d("BluetoothChatManager", "Input stream ended.")
                            break
                        }
                    } else {
                        // No data available, sleep briefly to avoid busy-waiting
                        delay(100)
                    }
                } catch (e: IOException) {
                    Log.e("BluetoothChatManager", "Read failed, connection likely lost.", e)
                    if (isActive) { // Check if coroutine is still active
                        withContext(Dispatchers.Main) {
                            if (_connectionStatus.value.startsWith("Connected")) {
                                _connectionStatus.value = "Connection Lost"
                            }
                        }
                    }
                    break
                } catch(e: Exception) {
                    Log.e("BluetoothChatManager", "Unexpected error during message listening", e)
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            if (_connectionStatus.value.startsWith("Connected")) {
                                _connectionStatus.value = "Read Error"
                            }
                        }
                    }
                    break
                }
            }
            Log.d("BluetoothChatManager", "Stopped listening for messages.")
            // Ensure connection is cleaned up if listening stops
            cleanupConnection() // Call cleanup when loop finishes
        }
    }

    private fun hasPermissions(): Boolean {
        // ... (hasPermissions logic is likely correct) ...
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.e("BluetoothChatManager", "Missing permissions: $missingPermissions")
            return false
        }
        Log.d("BluetoothChatManager", "All required permissions granted: $requiredPermissions")
        return true
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }






    @SuppressLint("MissingPermission")
    fun startLocationSharing() {
        coroutineScope.launch(Dispatchers.Main) {
            try {
                if (!hasPermissions()) {
                    Log.w("BluetoothChatManager", "Cannot start location sharing: Permissions missing.")
                    return@launch
                }
                if (locationListener != null) {
                     Log.w("BluetoothChatManager", "Location sharing already active.")
                     return@launch
                }

                Log.d("BluetoothChatManager", "🌍 Starting location sharing (Active Updates via Firestore)...")
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@launch
                val db = Firebase.firestore
                val myDeviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
                Log.d("BluetoothChatManager", "Device ID: $myDeviceId")

                locationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                         val data = hashMapOf(
                            "id" to myDeviceId,
                            "name" to "Rescue Team (${myDeviceId.take(4)})",
                            "latitude" to location.latitude,
                            "longitude" to location.longitude,
                            "timestamp" to FieldValue.serverTimestamp(),
                            "lastUpdated" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        )

                        db.collection("rescue_teams").document(myDeviceId)
                            .set(data, SetOptions.merge())
                            .addOnFailureListener { e -> 
                                Log.e("BluetoothChatManager", "Error updating location to Firestore", e) 
                            }
                            .addOnSuccessListener {
                                Log.v("BluetoothChatManager", "Loc update: ${location.latitude},${location.longitude}")
                            }
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    // Deprecated in API 29 but needed for interface implementation on older SDKs
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }

                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    // Update every 10s, regardless of distance (minDistance = 0f) to ensure continuous updates
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 0f, locationListener!!)
                    Log.d("BluetoothChatManager", "✅ GPS location updates ACTIVE (every 10s)")
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    // Update every 10s, regardless of distance (minDistance = 0f)
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 0f, locationListener!!)
                    Log.d("BluetoothChatManager", "✅ Network location updates ACTIVE (every 10s)")
                }
            } catch (e: Exception) {
                Log.e("BluetoothChatManager", "Error starting location updates", e)
                locationListener = null
            }
        }
    }

    fun stopLocationSharing() {
        Log.d("BluetoothChatManager", "Stopping location sharing...")
        if (locationListener != null) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locationManager?.removeUpdates(locationListener!!)
            locationListener = null
        }
    }

    fun cleanup() {
        // ... (cleanup logic is likely correct) ...
        Log.d("BluetoothChatManager", "Cleaning up Bluetooth Manager...")
        stopLocationSharing() // Ensure location updates are removed
        stopScan() // Ensure scan is stopped

        connectionJob?.cancel() // Cancel any ongoing connection attempt
        cleanupConnection() // Clean up sockets and streams
        coroutineScope.cancel() // Cancel all coroutines in the manager's scope
        try {
            context.unregisterReceiver(discoveryReceiver)
        } catch (e: IllegalArgumentException) {
            // Ignore if not registered
        }
    }
}


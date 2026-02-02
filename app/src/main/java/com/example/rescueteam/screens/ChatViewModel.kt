package com.example.rescueteam.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
// No need for viewModelScope or launch here if only delegating
import com.example.rescueteam.data.ChatMessage
import com.example.rescueteam.service.BluetoothManagerProvider // Import the provider
import kotlinx.coroutines.flow.StateFlow
import android.util.Log // Import Log

// Use AndroidViewModel to get the Application context for BT
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // Get the shared instance from the provider
    private val bluetoothManager = BluetoothManagerProvider.getInstance(application.applicationContext)

    // Expose state directly from the manager
    val messages: StateFlow<List<ChatMessage>> = bluetoothManager.messages
    val connectionStatus: StateFlow<String> = bluetoothManager.connectionStatus

    init {
        Log.d("ChatViewModel", "Initializing. Current BT Status: ${connectionStatus.value}")
        // Optionally, check if the status is actually 'Connected' and log a warning if not
        if (!connectionStatus.value.startsWith("Connected")) {
            Log.w("ChatViewModel", "ChatViewModel initialized but Bluetooth status is not 'Connected'. Status: ${connectionStatus.value}")
        }
    }

    fun sendMessage(message: String) {
        // Basic validation
        if (message.isNotBlank()) {
            Log.d("ChatViewModel", "Sending message: $message")
            bluetoothManager.sendMessage(message)
        }
    }

    override fun onCleared() {
        Log.d("ChatViewModel", "onCleared called.")
        // It's crucial to decide where the shared manager's cleanup happens.
        // Doing it here means the connection closes when the chat screen closes.
        // If you want the connection to persist longer (e.g., across screens),
        // cleanup should happen at a higher level (e.g., MainActivity or Application).
        // bluetoothManager.cleanup() // REMOVED: Do not destroy the singleton manager on screen exit
        // This ensures location sharing (and the manager scope) attempts to stay alive.
        super.onCleared()
    }
}


package com.example.rescueteam.service

import android.content.Context

/**
 * Singleton object to provide a shared instance of BluetoothChatManager.
 * Initialize this in your Application class or MainActivity.
 */
object BluetoothManagerProvider {
    private var instance: BluetoothChatManager? = null

    fun getInstance(context: Context): BluetoothChatManager {
        if (instance == null) {
            // Use application context to avoid leaks
            instance = BluetoothChatManager(context.applicationContext)
        }
        return instance!!
    }

    // Optional: Add a cleanup method if needed, though cleanup might be better
    // handled within the manager itself when the app closes.
}

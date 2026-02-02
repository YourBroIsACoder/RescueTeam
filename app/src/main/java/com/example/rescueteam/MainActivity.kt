package com.example.rescueteam

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.rememberNavController
import com.example.rescueteam.navigation.AppNavigation
import com.example.rescueteam.ui.theme.RescueTeamTheme

class MainActivity : ComponentActivity() {

    // (NEW) Set up permission launcher for multiple permissions
    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var locationGranted = false
            permissions.entries.forEach {
                if ((it.key == Manifest.permission.ACCESS_FINE_LOCATION || it.key == Manifest.permission.ACCESS_COARSE_LOCATION) && it.value) {
                    locationGranted = true
                }
            }
            if (locationGranted) {
                // Permissions granted, attempt to start location sharing if not already active
                com.example.rescueteam.service.BluetoothManagerProvider.getInstance(applicationContext).startLocationSharing()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // (NEW) Ask for all required permissions on startup
        askPermissions()

        setContent {
            RescueTeamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavigation(navController = navController)
                }
            }
        }
    }

    private fun askPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            // Android 11 and below
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        requestMultiplePermissionsLauncher.launch(requiredPermissions)
    }
}

// (NEW) Simple theme for the app
@Composable
fun RescueTeamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF0277BD), // Blue theme for rescue
            secondary = Color(0xFF0097A7),
            surface = Color(0xFF121212),
            background = Color(0xFF121212)
        ),
        content = content
    )
}

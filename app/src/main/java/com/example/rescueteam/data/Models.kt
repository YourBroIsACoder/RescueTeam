package com.example.rescueteam.data

//import com.google.android.gms.maps.model.LatLng

// (NEW) Represents a victim assigned to this team
data class Assignment(
    val sessionId: String, // e.g., "sos_victim_android_001"
    val userId: String,
    val lastKnownLocation: com.google.android.gms.maps.model.LatLng,
    val status: String // "NEEDS_HELP", "CRITICAL"
)

// (NEW) Represents a single chat message
data class ChatMessage(
    val message: String,
    val isSentByMe: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

package com.example.rescueteam.screens

import android.util.Log // Import Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer // Import Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size // Import Size
import androidx.compose.foundation.layout.width // Import Width
import androidx.compose.foundation.layout.imePadding // Import imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState // Import rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.* // Import specific components if needed
import androidx.compose.runtime.* // Import getValue, remember, mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rescueteam.data.ChatMessage
import kotlinx.coroutines.launch // Import launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    victimId: String, // Keep victimId if needed for display or context
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val status by viewModel.connectionStatus.collectAsState()
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Log recomposition and state
    Log.d("ChatScreen", "Recomposing. Message count from StateFlow: ${messages.size}")
    messages.forEachIndexed { index, msg ->
        Log.d("ChatScreen", "Message[$index]: '${msg.message}' (isSentByMe=${msg.isSentByMe})")
    }

    // Scroll to bottom when new message arrives
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                Log.d("ChatScreen", "Scrolling to item 0")
                listState.animateScrollToItem(0) // Scrolls to the top of the reversed list
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chatting...") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        // --- (MODIFICATION) Remove bottomBar from Scaffold ---
        // The input bar will now be part of the main Column content
        // bottomBar = { ... }
    ) { paddingValues -> // paddingValues contains system bar padding (top, bottom)
        Column(
            modifier = Modifier
                .fillMaxSize()
                // --- (MODIFICATION) Apply padding from Scaffold ---
                // This adds padding for status bar (top) and navigation bar (bottom)
                .padding(paddingValues)
                // --- (MODIFICATION) Add imePadding ---
                // This adjusts padding automatically when keyboard appears/disappears
                .imePadding()
        ) {
            // Status bar (Stays at the top of the content area)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background( /* ... status color logic ... */
                        when {
                            status.startsWith("Connected") -> Color(0xFF4CAF50) // Green
                            status.startsWith("Error") ||
                                    status == "Connection Lost" ||
                                    status == "Rescuer disconnected" ||
                                    status == "Send Failed: Not Connected" ||
                                    status.startsWith("Send Failed:") ||
                                    status.startsWith("Connection Failed")
                                -> Color(0xFFF44336) // Red
                            status == "Initializing..." || status == "Connecting..." || status == "Scanning..."
                                -> Color(0xFFFF9800) // Orange
                            else -> Color.DarkGray // Gray
                        }
                    )
                    .padding(vertical = 8.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = status, color = Color.White)
            }

            // Message list taking up available space
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f) // Takes up remaining vertical space above input bar
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                reverseLayout = true,
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
            ) {
                items(
                    items = messages.reversed(),
                    key = { message -> "${message.message}-${message.isSentByMe}-${System.identityHashCode(message)}" } // Slightly more robust key
                ) { message ->
                    Log.d("ChatScreen", "Rendering message bubble for: ${message.message}")
                    MessageBubble(message)
                }
            }

            // --- (MODIFICATION) Input bar moved inside Column, appears last ---
            ChatInputBar(
                text = text,
                onTextChange = { text = it },
                onSend = {
                    if (text.isNotBlank()) {
                        viewModel.sendMessage(text)
                        text = ""
                    }
                },
                enabled = status.startsWith("Connected")
            )
            // --- (END MODIFICATION) ---
        }
    }
}

// Composable for displaying a single chat message bubble
@Composable
fun MessageBubble(message: ChatMessage) {
    // Determine alignment based on sender
    val alignment = if (message.isSentByMe) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = if (message.isSentByMe) MaterialTheme.colorScheme.primary else Color.DarkGray
    val textColor = Color.White

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp), // Spacing between bubbles
        contentAlignment = alignment // Align bubble left or right
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f) // Limit bubble width
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundColor)
                .padding(horizontal = 16.dp, vertical = 10.dp) // Padding inside the bubble
        ) {
            Text(text = message.message, color = textColor)
        }
    }
}

// Composable for the text input field and send button at the bottom
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean // Control if input area is enabled
) {
    Surface(shadowElevation = 8.dp) { // Add elevation for visual separation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface) // Use surface color
                // Add vertical padding to lift it slightly from the absolute bottom edge
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                enabled = enabled,
                maxLines = 4,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (enabled && text.isNotBlank()) LocalContentColor.current else Color.Gray
                )
            }
        }
    }
}


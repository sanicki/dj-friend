package com.djfriend.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.djfriend.app.service.DjFriendService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DjFriendScreen(
                    onStart = { startService() },
                    onStop  = { stopService(Intent(this, DjFriendService::class.java)) },
                    onNotificationAccess = { openNotificationAccess() },
                    onBatteryExempt = { requestBatteryExemption() }
                )
            }
        }
    }

    private fun startService() {
        startForegroundService(Intent(this, DjFriendService::class.java))
    }

    private fun openNotificationAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }
}

@Composable
fun DjFriendScreen(
    onStart: () -> Unit,
    onStop:  () -> Unit,
    onNotificationAccess: () -> Unit,
    onBatteryExempt: () -> Unit
) {
    var isRunning by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🎧 DJ Friend", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { isRunning = !isRunning; if (isRunning) onStart() else onStop() },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRunning) "Stop DJ Friend" else "Start DJ Friend")
            }

            OutlinedButton(
                onClick = onNotificationAccess,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant Notification Access")
            }

            OutlinedButton(
                onClick = onBatteryExempt,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disable Battery Optimisation")
            }

            Text(
                "DJ Friend monitors your media playback and suggests what to play next via Last.fm.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

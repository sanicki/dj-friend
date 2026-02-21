package com.djfriend.app.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.djfriend.app.service.DjFriendService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DjFriendScreen()
            }
        }
    }
}

@Composable
fun DjFriendScreen() {
    val context = LocalContext.current

    // Check if service is running
    fun isServiceRunning(): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == DjFriendService::class.java.name }
    }

    // Check notification listener access
    fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(context.packageName)
    }

    // Check music/audio permission
    fun hasMusicPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    var isRunning        by remember { mutableStateOf(isServiceRunning()) }
    var hasNotifAccess   by remember { mutableStateOf(hasNotificationAccess()) }
    var hasMusicAccess   by remember { mutableStateOf(hasMusicPermission()) }

    // Re-check states when activity resumes (e.g. after returning from Settings)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isRunning      = isServiceRunning()
                hasNotifAccess = hasNotificationAccess()
                hasMusicAccess = hasMusicPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val musicPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMusicAccess = granted }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Text("DJ Friend", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Background music discovery",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // Start / Stop
            Button(
                onClick = {
                    if (isRunning) {
                        context.stopService(Intent(context, DjFriendService::class.java))
                        isRunning = false
                    } else {
                        context.startForegroundService(Intent(context, DjFriendService::class.java))
                        isRunning = true
                    }
                },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isRunning) "Stop DJ Friend" else "Start DJ Friend")
            }

            // Notification access
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedButtonDefaults.outlinedButtonColors(
                    contentColor = if (hasNotifAccess)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (hasNotifAccess) "Notification Access Granted" else "Grant Notification Access")
            }

            // Music access
            OutlinedButton(
                onClick = {
                    if (hasMusicAccess) return@OutlinedButton
                    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        Manifest.permission.READ_MEDIA_AUDIO
                    else
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    musicPermLauncher.launch(perm)
                },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedButtonDefaults.outlinedButtonColors(
                    contentColor = if (hasMusicAccess)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (hasMusicAccess) "Music Access Granted" else "Grant Music Access")
            }

            // Battery exemption
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    }
                },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disable Battery Optimisation")
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "DJ Friend monitors your media playback and suggests what to play next via Last.fm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

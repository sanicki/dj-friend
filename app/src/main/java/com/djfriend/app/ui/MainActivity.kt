package com.djfriend.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.djfriend.app.service.DjFriendService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DjFriendScreen() } }
    }
}

@Composable
fun DjFriendScreen() {
    val context = LocalContext.current

    fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(context.packageName)
    }

    // POST_NOTIFICATIONS permission (Android 13+) — needed for the notification to appear
    fun hasPostNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasMusicPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun isSpotifyInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("com.spotify.music", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun isServiceRunning(): Boolean =
        context.getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
            .getBoolean("service_running", false)

    var isRunning          by remember { mutableStateOf(isServiceRunning()) }
    var hasNotifAccess     by remember { mutableStateOf(hasNotificationAccess()) }
    var hasNotifPermission by remember { mutableStateOf(hasPostNotificationPermission()) }
    var hasMusicAccess     by remember { mutableStateOf(hasMusicPermission()) }
    var spotifyInstalled   by remember { mutableStateOf(isSpotifyInstalled()) }

    // Re-check all states when screen comes back into focus
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isRunning          = isServiceRunning()
                hasNotifAccess     = hasNotificationAccess()
                hasNotifPermission = hasPostNotificationPermission()
                hasMusicAccess     = hasMusicPermission()
                spotifyInstalled   = isSpotifyInstalled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Listen for service stopping (timeout etc.) to update button immediately
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { isRunning = false }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(DjFriendService.ACTION_SERVICE_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotifPermission = granted }

    val musicPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMusicAccess = granted }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
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
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                                     else          MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isRunning) "Stop DJ Friend" else "Start DJ Friend")
            }

            // Notification listener access (special system setting)
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (hasNotifAccess) "Notification Listener Granted"
                    else               "Grant Notification Listener Access",
                    color = if (hasNotifAccess) MaterialTheme.colorScheme.tertiary
                            else               MaterialTheme.colorScheme.primary
                )
            }

            // POST_NOTIFICATIONS permission (needed for notification to actually show)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(
                    onClick = {
                        if (!hasNotifPermission)
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (hasNotifPermission) "Notifications Allowed"
                        else                   "Allow Notifications",
                        color = if (hasNotifPermission) MaterialTheme.colorScheme.tertiary
                                else                   MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Music library access
            OutlinedButton(
                onClick = {
                    if (!hasMusicAccess) {
                        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            Manifest.permission.READ_MEDIA_AUDIO
                        else
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        musicPermLauncher.launch(perm)
                    }
                },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (hasMusicAccess) "Music Access Granted" else "Grant Music Access",
                    color = if (hasMusicAccess) MaterialTheme.colorScheme.tertiary
                            else               MaterialTheme.colorScheme.primary
                )
            }

            // Spotify install check
            OutlinedButton(
                onClick = {
                    if (!spotifyInstalled) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=com.spotify.music")
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                    }
                },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (spotifyInstalled) "Spotify Installed" else "Install Spotify",
                    color = if (spotifyInstalled) MaterialTheme.colorScheme.tertiary
                            else                 MaterialTheme.colorScheme.primary
                )
            }

            // Battery optimisation
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

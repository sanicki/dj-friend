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
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.djfriend.app.service.DjFriendService
import org.json.JSONObject

// ─── Screen enum ──────────────────────────────────────────────────────────────
private enum class Screen { WELCOME, MAIN, SETTINGS }

// ─── Data class for UI state broadcast from service ───────────────────────────
data class NowPlayingState(
    val currentArtist:      String  = "",
    val currentTrack:       String  = "",
    val currentPackage:     String  = "",
    val currentTrackIsLocal: Boolean = true,   // true = in library (default safe: no web action)
    val pageOffset:         Int     = 0,
    val canGoBack:          Boolean = false,
    val canGoMore:          Boolean = false,
    val suggestions:        List<SuggestionUiItem> = emptyList()
)

data class SuggestionUiItem(
    val globalIndex: Int,
    val artist:      String,
    val track:       String,
    val isLocal:     Boolean,
    val localUri:    String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DjFriendApp() } }
    }
}

@Composable
fun DjFriendApp() {
    val context = LocalContext.current
    val prefs   = context.getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
    val hasSeenWelcome = prefs.getBoolean("has_seen_welcome", false)

    var screen by remember { mutableStateOf(if (hasSeenWelcome) Screen.MAIN else Screen.WELCOME) }
    var showExitDialog by remember { mutableStateOf(false) }

    // Handle system back button/gesture
    val activity = context as? ComponentActivity
    DisposableEffect(screen) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    Screen.WELCOME  -> { /* no-op on welcome screen */ }
                    Screen.SETTINGS -> screen = Screen.MAIN
                    Screen.MAIN     -> showExitDialog = true
                }
            }
        }
        activity?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

    when (screen) {
        Screen.WELCOME  -> WelcomeScreen(onDone = {
            prefs.edit().putBoolean("has_seen_welcome", true).apply()
            screen = Screen.MAIN
        })
        Screen.MAIN     -> MainScreen(
            onSettings     = { screen = Screen.SETTINGS },
            showExitDialog = showExitDialog,
            onExitConfirm  = { activity?.finish() },
            onExitDismiss  = { showExitDialog = false }
        )
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.MAIN })
    }
}

// ─── Welcome / First-run Screen ───────────────────────────────────────────────

@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Text("Welcome to DJ Friend 🎧", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "DJ Friend runs in the background, detects what you're playing, and suggests similar tracks from Last.fm — right in your notification shade and in this app.",
                style = MaterialTheme.typography.bodyMedium
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                "First-time setup",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )

            SetupStep(
                number = "1",
                text   = "Open Settings and work through the permission buttons:"
            )
            SetupDetail("Allow Notifications — lets DJ Friend post its notification")
            SetupDetail("Grant Music Access — lets DJ Friend check suggestions against your local library")
            SetupDetail("Grant Notification Listener Access — required for media monitoring; opens system settings, find DJ Friend in the list and enable it")
            SetupDetail("Disable Battery Optimisation — recommended, prevents Android from killing the service")

            SetupStep(
                number = "2",
                text   = "Optionally install SpotiFLAC and/or Spotify to preview songs not in your local music library."
            )
            SetupDetail("SpotiFLAC and Spotify install links are in Settings")

            SetupStep(
                number = "3",
                text   = "Tap Start on the main screen."
            )

            SetupStep(
                number = "4",
                text   = "Play any song in any music app. Suggestions will appear in your notification shade and in the app within a few seconds."
            )
            SetupDetail("The Now Playing button shows the current track. If it's in your local library it opens your player; if not, it turns reddish and taps like a web suggestion — finding a Spotify link via your preferred setting.")

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Text("Get Started")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SetupStep(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "$number.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp)
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SetupDetail(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("•", style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─── Main / Now Playing Screen ────────────────────────────────────────────────

@Composable
fun MainScreen(
    onSettings:     () -> Unit,
    showExitDialog: Boolean,
    onExitConfirm:  () -> Unit,
    onExitDismiss:  () -> Unit
) {
    val context = LocalContext.current

    fun isServiceRunning() =
        context.getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
            .getBoolean("service_running", false)

    var isRunning     by remember { mutableStateOf(isServiceRunning()) }
    var nowPlaying    by remember { mutableStateOf(NowPlayingState()) }

    // Exit confirmation dialog
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = onExitDismiss,
            title   = { Text("Exit DJ Friend") },
            text    = { Text("Are you sure? The service will keep running in the background if already started.") },
            confirmButton = {
                TextButton(onClick = onExitConfirm) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = onExitDismiss) { Text("No") }
            }
        )
    }

    // Listen for state broadcasts from the service
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    DjFriendService.ACTION_STATE_UPDATE -> {
                        val json = intent.getStringExtra(DjFriendService.EXTRA_STATE_JSON) ?: return
                        nowPlaying = parseStateJson(json)
                    }
                    DjFriendService.ACTION_SERVICE_STOPPED -> {
                        isRunning  = false
                        nowPlaying = NowPlayingState()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(DjFriendService.ACTION_STATE_UPDATE)
            addAction(DjFriendService.ACTION_SERVICE_STOPPED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val prefs      = context.getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
    val copyFormat = prefs.getString("copy_format", "song_only") ?: "song_only"

    fun requestPage(offset: Int) {
        val pageSize = prefs.getInt("songs_per_page", Int.MAX_VALUE).let {
            if (it == Int.MAX_VALUE) 1000 else it
        }
        context.sendBroadcast(
            Intent(DjFriendService.ACTION_REQUEST_PAGE)
                .setPackage(context.packageName)
                .putExtra(DjFriendService.EXTRA_PAGE_OFFSET, offset)
                .putExtra("extra_page_size", pageSize)
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isRunning = isServiceRunning()
                if (isRunning) requestPage(nowPlaying.pageOffset)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Text("DJ Friend", style = MaterialTheme.typography.headlineLarge)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (isRunning) {
                            context.stopService(Intent(context, DjFriendService::class.java))
                            isRunning  = false
                            nowPlaying = NowPlayingState()
                        } else {
                            context.startForegroundService(Intent(context, DjFriendService::class.java))
                            isRunning = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) MaterialTheme.colorScheme.error
                                         else          MaterialTheme.colorScheme.primary
                    )
                ) { Text(if (isRunning) "Stop" else "Start") }

                OutlinedButton(
                    onClick = onSettings,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.extraLarge
                ) { Text("Settings") }
            }

            if (nowPlaying.currentTrack.isNotEmpty()) {
                // Faded maroon = error color at low alpha for the container, when not in library.
                // We blend with the surface color manually so it reads as a muted tint rather
                // than a near-transparent overlay (which would look washed out on light themes).
                val nowPlayingContainerColor = if (nowPlaying.currentTrackIsLocal) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    // Mix error color at ~30% over the surface to produce a faded maroon
                    val errorColor   = MaterialTheme.colorScheme.error
                    val surfaceColor = MaterialTheme.colorScheme.surface
                    Color(
                        red   = surfaceColor.red   * 0.70f + errorColor.red   * 0.30f,
                        green = surfaceColor.green * 0.70f + errorColor.green * 0.30f,
                        blue  = surfaceColor.blue  * 0.70f + errorColor.blue  * 0.30f,
                        alpha = 1f
                    )
                }
                val nowPlayingContentColor = if (nowPlaying.currentTrackIsLocal) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                val nowPlayingSymbol = if (nowPlaying.currentTrackIsLocal) "" else " ${DjFriendService.CROSS}"

                Button(
                    onClick = {
                        if (nowPlaying.currentTrackIsLocal) {
                            // In library: open the active player app
                            val launchIntent = context.packageManager
                                .getLaunchIntentForPackage(nowPlaying.currentPackage)
                            if (launchIntent != null) context.startActivity(launchIntent)
                        } else {
                            // Not in library: resolve Spotify URL just like a web suggestion
                            com.djfriend.app.receiver.NotificationActionReceiver
                                .handleWebSuggestionTap(
                                    context,
                                    nowPlaying.currentArtist,
                                    nowPlaying.currentTrack
                                )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = nowPlayingContainerColor,
                        contentColor   = nowPlayingContentColor
                    )
                ) {
                    Text(
                        "Now Playing: ${nowPlaying.currentTrack} by ${nowPlaying.currentArtist}$nowPlayingSymbol",
                        maxLines = 2
                    )
                }
            } else if (isRunning) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge
                ) { Text("Listening for music...") }
            }

            if (nowPlaying.suggestions.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "Suggested for you:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start)
                )

                nowPlaying.suggestions.forEach { s ->
                    val symbol = if (s.isLocal) DjFriendService.CHECK else DjFriendService.CROSS
                    val label  = "${s.globalIndex + 1}. ${s.track} by ${s.artist} $symbol"
                    OutlinedButton(
                        onClick = { handleSuggestionTap(context, s, copyFormat) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Text(
                            label,
                            fontWeight = if (s.isLocal) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2
                        )
                    }
                }

                val showingAll = prefs.getInt("songs_per_page", Int.MAX_VALUE) == Int.MAX_VALUE
                if (!showingAll || nowPlaying.canGoMore) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!showingAll) {
                            OutlinedButton(
                                onClick = {
                                    val newOffset = (nowPlaying.pageOffset - DjFriendService.PAGE_SIZE)
                                        .coerceAtLeast(0)
                                    requestPage(newOffset)
                                },
                                enabled = nowPlaying.canGoBack,
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.extraLarge
                            ) { Text("◀ Back") }
                        }

                        if (nowPlaying.canGoMore) {
                            Button(
                                onClick = {
                                    val newOffset = nowPlaying.pageOffset + DjFriendService.PAGE_SIZE
                                    requestPage(newOffset)
                                },
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.extraLarge
                            ) { Text("More ▶") }
                        } else if (!showingAll) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ─── Settings Screen ──────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    fun hasNotificationListenerAccess(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(context.packageName)
    }

    fun hasPostNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasMusicPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun isSpotifyInstalled(): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:"))
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }
        return activities.any { it.activityInfo.packageName == "com.spotify.music" }
    }

    fun isSpotiflacInstalled(): Boolean = try {
        context.packageManager.getApplicationInfo("com.zarz.spotiflac", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun openAppInfo() = context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )

    val prefs = context.getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)

    var hasListenerAccess  by remember { mutableStateOf(hasNotificationListenerAccess()) }
    var hasNotifPermission by remember { mutableStateOf(hasPostNotificationPermission()) }
    var hasMusicAccess     by remember { mutableStateOf(hasMusicPermission()) }
    var spotifyInstalled   by remember { mutableStateOf(isSpotifyInstalled()) }
    var spotiflacInstalled by remember { mutableStateOf(isSpotiflacInstalled()) }
    var copyFormat           by remember { mutableStateOf(prefs.getString("copy_format", "song_only") ?: "song_only") }
    var songsPerPage          by remember { mutableStateOf(prefs.getInt("songs_per_page", Int.MAX_VALUE)) }
    var webAction             by remember { mutableStateOf(prefs.getString("web_action", "spotiflac") ?: "spotiflac") }
    var copyDropdownExpanded  by remember { mutableStateOf(false) }
    var webActionExpanded     by remember { mutableStateOf(false) }
    var pageDropdownExpanded  by remember { mutableStateOf(false) }

    val copyFormatOptions = listOf(
        "song_only"   to "Copy song name",
        "artist_song" to "Copy artist - song name"
    )
    val webActionOptions = listOf(
        "spotiflac" to "Open SpotiFLAC",
        "spotify"   to "Open Spotify"
    )
    val songsPerPageOptions = listOf(5 to "5 songs", 10 to "10 songs", Int.MAX_VALUE to "All songs")

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasListenerAccess  = hasNotificationListenerAccess()
                hasNotifPermission = hasPostNotificationPermission()
                hasMusicAccess     = hasMusicPermission()
                spotifyInstalled   = isSpotifyInstalled()
                spotiflacInstalled = isSpotiflacInstalled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotifPermission = granted; if (!granted) openAppInfo() }

    val musicPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMusicAccess = granted; if (!granted) openAppInfo() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))

            // Copy format
            Text(
                "When I tap a song in my music library:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start)
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { copyDropdownExpanded = true },
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(copyFormatOptions.first { it.first == copyFormat }.second) }
                DropdownMenu(
                    expanded = copyDropdownExpanded,
                    onDismissRequest = { copyDropdownExpanded = false }
                ) {
                    copyFormatOptions.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                copyFormat = value
                                prefs.edit().putString("copy_format", value).apply()
                                copyDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // When I tap a song NOT in my music library
            Text(
                "When I tap a song NOT in my music library:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start)
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { webActionExpanded = true },
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(webActionOptions.first { it.first == webAction }.second)
                }
                DropdownMenu(
                    expanded = webActionExpanded,
                    onDismissRequest = { webActionExpanded = false }
                ) {
                    webActionOptions.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                webAction = value
                                prefs.edit().putString("web_action", value).apply()
                                webActionExpanded = false
                            }
                        )
                    }
                }
            }

            // Songs per page
            Text(
                "Songs per page:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start)
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { pageDropdownExpanded = true },
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(songsPerPageOptions.first { it.first == songsPerPage }.second)
                }
                DropdownMenu(
                    expanded = pageDropdownExpanded,
                    onDismissRequest = { pageDropdownExpanded = false }
                ) {
                    songsPerPageOptions.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                songsPerPage = value
                                prefs.edit().putInt("songs_per_page", value).apply()
                                pageDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Back to main
            Button(
                onClick = onBack,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Back to DJ Friend") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Allow Notifications
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(
                    onClick = {
                        if (hasNotifPermission) openAppInfo()
                        else notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (hasNotifPermission) "Notifications Allowed" else "Allow Notifications",
                        color = if (hasNotifPermission) MaterialTheme.colorScheme.tertiary
                                else                   MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Music access
            OutlinedButton(
                onClick = {
                    if (hasMusicAccess) openAppInfo()
                    else {
                        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            Manifest.permission.READ_MEDIA_AUDIO
                        else Manifest.permission.READ_EXTERNAL_STORAGE
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

            // Notification Listener
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (hasListenerAccess) "Notification Listener Granted"
                    else                  "Grant Notification Listener Access",
                    color = if (hasListenerAccess) MaterialTheme.colorScheme.tertiary
                            else                  MaterialTheme.colorScheme.primary
                )
            }

            // Battery
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
            ) { Text("Disable Battery Optimisation") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // SpotiFLAC — listed first, always opens GitHub releases page
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/zarzet/SpotiFLAC-Mobile/releases"))
                            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )
                },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (spotiflacInstalled) "SpotiFLAC Installed" else "Install SpotiFLAC",
                    color = if (spotiflacInstalled) MaterialTheme.colorScheme.tertiary
                            else                   MaterialTheme.colorScheme.primary
                )
            }

            // Spotify — always opens Play Store
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=com.spotify.music")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )
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

            // ── "Powered by Last.fm" attribution — no divider above ───────────
            Spacer(Modifier.height(4.dp))
            Text(
                "Powered by Last.fm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun handleSuggestionTap(context: Context, s: SuggestionUiItem, copyFormat: String) {
    if (s.isLocal) {
        val prefs = context.getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
        val fmt   = prefs.getString("copy_format", "song_only") ?: "song_only"
        val text  = if (fmt == "artist_song") "${s.artist} - ${s.track}" else s.track
        val cb    = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText("DJ Friend", text))
        android.widget.Toast.makeText(context, "Copied: $text", android.widget.Toast.LENGTH_SHORT).show()
    } else {
        com.djfriend.app.receiver.NotificationActionReceiver.handleWebSuggestionTap(
            context, s.artist, s.track
        )
    }
}

private fun parseStateJson(json: String): NowPlayingState {
    return try {
        val obj         = JSONObject(json)
        val suggestions = mutableListOf<SuggestionUiItem>()
        val arr         = obj.getJSONArray("suggestions")
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            suggestions += SuggestionUiItem(
                globalIndex = s.getInt("index"),
                artist      = s.getString("artist"),
                track       = s.getString("track"),
                isLocal     = s.getBoolean("isLocal"),
                localUri    = s.getString("localUri")
            )
        }
        NowPlayingState(
            currentArtist       = obj.getString("currentArtist"),
            currentTrack        = obj.getString("currentTrack"),
            currentPackage      = obj.getString("currentPackage"),
            currentTrackIsLocal = obj.optBoolean("currentTrackLocal", true),
            pageOffset          = obj.getInt("pageOffset"),
            canGoBack           = obj.getBoolean("canGoBack"),
            canGoMore           = obj.getBoolean("canGoMore"),
            suggestions         = suggestions
        )
    } catch (e: Exception) { NowPlayingState() }
}

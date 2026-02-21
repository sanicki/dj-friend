package com.djfriend.app.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.djfriend.app.model.SuggestionResult
import com.djfriend.app.api.RetrofitClient
import com.djfriend.app.receiver.NotificationActionReceiver
import com.djfriend.app.util.FuzzyMatcher
import kotlinx.coroutines.*

class DjFriendService : Service() {

    companion object {
        const val CHANNEL_ID             = "djfriend_channel"
        const val NOTIFICATION_ID        = 1001
        const val ACTION_PLAY_LOCAL      = "com.djfriend.ACTION_PLAY_LOCAL"
        const val ACTION_OPEN_SPOTIFY    = "com.djfriend.ACTION_OPEN_SPOTIFY"
        const val ACTION_SERVICE_STOPPED = "com.djfriend.ACTION_SERVICE_STOPPED"
        const val EXTRA_MEDIA_URI        = "extra_media_uri"
        const val EXTRA_ARTIST           = "extra_artist"
        const val EXTRA_TRACK            = "extra_track"

        private const val MIN_MATCH_SCORE = 0.8f

        val TIMEOUT_OPTIONS = mapOf(
            "1 min"  to 60_000L,
            "3 min"  to 180_000L,
            "5 min"  to 300_000L,
            "Always" to 0L
        )

        private val NON_MUSIC_REGEX = Regex(
            "(podcast|episode|chapter|news|alert|notification|talk|interview|show|ep\\s*\\d+)",
            RegexOption.IGNORE_CASE
        )
        private const val MAX_DURATION_MS = 10 * 60 * 1000L

        private const val CHECK = "\u2714"  // ✔
        private const val CROSS = "\u2717"  // ✗

        // Normalise "The Cranberries" / "Cranberries, The" to a canonical key
        // Used for both local library matching and Last.fm artist de-duplication
        fun canonicalArtist(name: String) = FuzzyMatcher.normalizeArtist(name)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler   = Handler(Looper.getMainLooper())
    private val lastFmApi     = RetrofitClient.lastFmApi
    private val apiKey        = RetrofitClient.apiKey
    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSessionManager: MediaSessionManager

    private var currentArtist = ""
    private var currentTrack  = ""
    private var timeoutRunnable: Runnable? = null
    private var timeoutDurationMs = TIMEOUT_OPTIONS["3 min"]!!

    // Bug 1 fix: track which package "owns" the current suggestion set,
    // and which package is considered the "primary" player.
    // We ignore metadata changes from Spotify if a non-Spotify player is active.
    private val registeredControllers = mutableMapOf<MediaController, String>() // controller -> packageName
    private var primaryPlayerPackage  = ""   // the first non-Spotify app to play something

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata ?: return
            val artist   = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: return
            val track    = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)  ?: return
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

            // Find which package this callback belongs to
            val senderPackage = registeredControllers.entries
                .firstOrNull { it.key.metadata == metadata }
                ?.value ?: return

            val isSpotify = senderPackage == "com.spotify.music"

            // If a non-Spotify player was established first, ignore Spotify metadata
            if (isSpotify && primaryPlayerPackage.isNotEmpty() &&
                primaryPlayerPackage != "com.spotify.music") return

            // Record primary player on first non-Spotify activity
            if (!isSpotify && primaryPlayerPackage.isEmpty()) {
                primaryPlayerPackage = senderPackage
            }

            cancelTimeout()
            if (isMusicContent(track, duration) &&
                (artist != currentArtist || track != currentTrack)) {
                currentArtist = artist
                currentTrack  = track
                fetchSuggestions(artist, track)
            }
        }

        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
            val playing = state?.state == android.media.session.PlaybackState.STATE_PLAYING
            if (!playing && timeoutDurationMs > 0) startTimeout() else cancelTimeout()
        }
    }

    private val rescanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MediaSessionListenerService.ACTION_RESCAN) observeMediaSessions()
        }
    }

    // Lifecycle

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        createNotificationChannel()
        loadPreferences()
        ContextCompat.registerReceiver(
            this, rescanReceiver,
            IntentFilter(MediaSessionListenerService.ACTION_RESCAN),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_running", true).apply()
        startForeground(NOTIFICATION_ID, buildBaseNotification("Listening for music..."))
        observeMediaSessions()
        return START_STICKY
    }

    override fun onDestroy() {
        cancelTimeout()
        serviceScope.cancel()
        unregisterReceiver(rescanReceiver)
        getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED).setPackage(packageName))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // Media Session

    private fun observeMediaSessions() {
        try {
            val sessions = mediaSessionManager.getActiveSessions(
                android.content.ComponentName(this, MediaSessionListenerService::class.java)
            )

            sessions.forEach { controller ->
                if (!registeredControllers.containsKey(controller)) {
                    val pkg = controller.packageName ?: ""
                    controller.registerCallback(mediaControllerCallback, mainHandler)
                    registeredControllers[controller] = pkg

                    // If Spotify is NOT the primary player, skip Spotify's initial metadata
                    val isSpotify = pkg == "com.spotify.music"
                    if (isSpotify && primaryPlayerPackage.isNotEmpty() &&
                        primaryPlayerPackage != "com.spotify.music") return@forEach

                    controller.metadata?.let { meta ->
                        val artist   = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: return@let
                        val track    = meta.getString(MediaMetadata.METADATA_KEY_TITLE)  ?: return@let
                        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        if (!isSpotify && primaryPlayerPackage.isEmpty()) {
                            primaryPlayerPackage = pkg
                        }
                        if (isMusicContent(track, duration) &&
                            (artist != currentArtist || track != currentTrack)) {
                            currentArtist = artist
                            currentTrack  = track
                            fetchSuggestions(artist, track)
                        }
                    }
                }
            }

            // Clean up dead controllers; if Spotify's session ended, clear its primacy
            val removed = registeredControllers.keys.filter { it !in sessions }
            removed.forEach { c ->
                val pkg = registeredControllers[c]
                if (pkg == primaryPlayerPackage && pkg == "com.spotify.music") {
                    primaryPlayerPackage = ""
                }
                registeredControllers.remove(c)
            }

        } catch (e: SecurityException) {
            updateNotification("Grant Notification Access in Settings", emptyList())
        }
    }

    // Music Classification

    private fun isMusicContent(title: String, durationMs: Long): Boolean {
        if (NON_MUSIC_REGEX.containsMatchIn(title)) return false
        if (durationMs in 1..MAX_DURATION_MS) return true
        return durationMs <= 0
    }

    // Recommendation Engine

    private fun fetchSuggestions(artist: String, track: String) {
        serviceScope.launch {
            updateNotification("Finding suggestions...", emptyList())

            val trackInfo = runCatching {
                lastFmApi.getTrackInfo(artist = artist, track = track, apiKey = apiKey)
            }.getOrNull()

            if (trackInfo?.track == null) {
                updateNotification("No Last.fm match for $track", emptyList())
                return@launch
            }

            val suggestions = mutableListOf<SuggestionResult>()
            // Seed used-artists with both canonical forms of the current artist
            val usedArtists = mutableSetOf(
                canonicalArtist(artist),
                artist.lowercase()
            )

            // Primary: track.getSimilar
            val similarTracks = runCatching {
                lastFmApi.getSimilarTracks(artist, track, 30, apiKey)
            }.getOrNull()?.similarTracks?.tracks ?: emptyList()

            similarTracks.forEach { t ->
                if (suggestions.size >= 3) return@forEach
                val canonical = canonicalArtist(t.artist.name)
                if (canonical in usedArtists || t.artist.name.lowercase() in usedArtists) return@forEach
                suggestions += resolveSuggestion(t.artist.name, t.name)
                usedArtists += canonical
                usedArtists += t.artist.name.lowercase()
            }

            // Fallback A: artist.getSimilar
            if (suggestions.size < 3) {
                val listeners = trackInfo.track.listeners?.toLongOrNull() ?: Long.MAX_VALUE
                if (listeners < 10_000 || suggestions.isEmpty()) {
                    runCatching { lastFmApi.getSimilarArtists(artist, 10, apiKey) }
                        .getOrNull()?.similarArtists?.artists
                        ?.forEach { simArtist ->
                            if (suggestions.size >= 3) return@forEach
                            val canonical = canonicalArtist(simArtist.name)
                            if (canonical in usedArtists ||
                                simArtist.name.lowercase() in usedArtists) return@forEach
                            runCatching {
                                lastFmApi.getArtistTopTracks(simArtist.name, 1, apiKey)
                            }.getOrNull()?.topTracks?.tracks?.firstOrNull()?.let {
                                suggestions += resolveSuggestion(simArtist.name, it.name)
                                usedArtists += canonical
                                usedArtists += simArtist.name.lowercase()
                            }
                        }
                }
            }

            // Fallback B: current artist's other tracks
            if (suggestions.isEmpty()) {
                runCatching { lastFmApi.getArtistTopTracks(artist, 5, apiKey) }
                    .getOrNull()?.topTracks?.tracks
                    ?.filter { it.name.lowercase() != track.lowercase() }
                    ?.take(3)
                    ?.forEach { suggestions += resolveSuggestion(artist, it.name) }
            }

            withContext(Dispatchers.Main) {
                updateNotification("Suggested for you:", suggestions)
            }
        }
    }

    // Local Library

    private suspend fun resolveSuggestion(artist: String, track: String): SuggestionResult =
        withContext(Dispatchers.IO) {
            val localUri = findLocalTrack(artist, track)
            SuggestionResult(artist, track, localUri, localUri != null)
        }

    private fun findLocalTrack(artist: String, track: String): Uri? {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} = 1",
            null, null
        ) ?: return null

        var bestUri: Uri? = null
        var bestScore = Int.MAX_VALUE
        // Normalise using canonical artist (strips "The " prefix, handles "X, The" form)
        val targetArtistKey = canonicalArtist(artist)
        val targetTrackKey  = FuzzyMatcher.normalize(track)
        val target          = "$targetArtistKey $targetTrackKey"

        cursor.use { c ->
            val idIdx     = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIdx  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (c.moveToNext()) {
                val candidateArtist = canonicalArtist(c.getString(artistIdx))
                val candidateTrack  = FuzzyMatcher.normalize(c.getString(titleIdx))
                val candidate       = "$candidateArtist $candidateTrack"
                val dist            = FuzzyMatcher.levenshtein(target, candidate)
                val threshold       = (target.length * 0.35).toInt()
                if (dist < bestScore && dist <= threshold) {
                    bestScore = dist
                    bestUri   = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(idIdx).toString()
                    )
                }
            }
        }
        return bestUri
    }

    // Timeout

    private fun startTimeout() {
        timeoutRunnable = Runnable { stopSelf() }
        mainHandler.postDelayed(timeoutRunnable!!, timeoutDurationMs)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
        timeoutDurationMs = TIMEOUT_OPTIONS[prefs.getString("timeout", "3 min")] ?: 180_000L
    }

    // Notification

    private fun createNotificationChannel() {
        NotificationChannel(CHANNEL_ID, "DJ Friend", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Music suggestions from DJ Friend"
            setShowBadge(false)
            notificationManager.createNotificationChannel(this)
        }
    }

    private fun buildBaseNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("DJ Friend")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(statusText: String, suggestions: List<SuggestionResult>) {
        val prefs      = getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
        val copyFormat = prefs.getString("copy_format", "song_only") ?: "song_only"

        val bigText = SpannableStringBuilder()
        if (suggestions.isEmpty()) {
            bigText.append("Searching Last.fm...")
        } else {
            bigText.append(statusText)
            bigText.append("\n")
            suggestions.forEachIndexed { i, s ->
                val symbol = if (s.isLocal) CHECK else CROSS
                val line   = "${i + 1}. ${s.track} by ${s.artist} $symbol\n"
                if (s.isLocal) {
                    val start = bigText.length
                    bigText.append(line)
                    bigText.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start, bigText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else {
                    bigText.append(line)
                }
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Now: $currentTrack")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setOngoing(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setDeleteIntent(buildStopServicePendingIntent())

        suggestions.forEachIndexed { i, s ->
            val icon = if (s.isLocal) android.R.drawable.ic_menu_save
                       else           android.R.drawable.ic_menu_search
            builder.addAction(icon, "Suggestion ${i + 1}",
                buildActionPendingIntent(i, s, copyFormat))
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun buildStopServicePendingIntent(): PendingIntent {
        val intent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_STOP_SERVICE
        }
        return PendingIntent.getBroadcast(
            this, 999, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildActionPendingIntent(
        index: Int,
        s: SuggestionResult,
        copyFormat: String
    ): PendingIntent {
        val intent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = if (s.isLocal) ACTION_PLAY_LOCAL else ACTION_OPEN_SPOTIFY
            putExtra(EXTRA_ARTIST, s.artist)
            putExtra(EXTRA_TRACK, s.track)
            putExtra(NotificationActionReceiver.EXTRA_COPY_FORMAT, copyFormat)
            s.localUri?.let { putExtra(EXTRA_MEDIA_URI, it.toString()) }
        }
        // Bug 2 fix: use a unique request code per suggestion content, not just index.
        // Android reuses PendingIntents with the same request code, so tapping
        // Suggestion 2 after Suggestion 1 was tapped would replay Suggestion 1's intent.
        val requestCode = (s.artist + s.track + index).hashCode()
        return PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

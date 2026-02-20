package com.djfriend.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.djfriend.app.R
import com.djfriend.app.api.LastFmApiService
import com.djfriend.app.api.RetrofitClient
import com.djfriend.app.model.SuggestionResult
import com.djfriend.app.receiver.NotificationActionReceiver
import com.djfriend.app.util.FuzzyMatcher
import kotlinx.coroutines.*
import kotlin.math.min

class DjFriendService : Service() {

    companion object {
        const val CHANNEL_ID = "djfriend_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY_LOCAL = "com.djfriend.ACTION_PLAY_LOCAL"
        const val ACTION_OPEN_SPOTIFY = "com.djfriend.ACTION_OPEN_SPOTIFY"
        const val EXTRA_MEDIA_URI = "extra_media_uri"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_TRACK = "extra_track"

        // Timeout options in milliseconds (0 = always on)
        val TIMEOUT_OPTIONS = mapOf(
            "1 min" to 60_000L,
            "3 min" to 180_000L,
            "5 min" to 300_000L,
            "Always" to 0L
        )

        // Regex to filter non-music (podcasts, audiobooks, notifications)
        private val NON_MUSIC_REGEX = Regex(
            pattern = "(podcast|episode|chapter|news|alert|notification|talk|interview|show|ep\\s*\\d+)",
            option = RegexOption.IGNORE_CASE
        )
        private const val MAX_DURATION_MS = 10 * 60 * 1000L // 10 minutes
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var lastFmApi: LastFmApiService
    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSessionManager: MediaSessionManager

    private var currentArtist: String = ""
    private var currentTrack: String = ""
    private var currentSuggestions: List<SuggestionResult> = emptyList()

    private var timeoutRunnable: Runnable? = null
    private var timeoutDurationMs: Long = TIMEOUT_OPTIONS["3 min"]!!

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata ?: return
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: return
            val track = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

            cancelTimeout()

            if (isMusicContent(track, duration)) {
                if (artist != currentArtist || track != currentTrack) {
                    currentArtist = artist
                    currentTrack = track
                    fetchSuggestions(artist, track)
                }
            }
        }

        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
            val isPlaying = state?.state == android.media.session.PlaybackState.STATE_PLAYING
            if (!isPlaying && timeoutDurationMs > 0) {
                startTimeout()
            } else if (isPlaying) {
                cancelTimeout()
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        lastFmApi = RetrofitClient.lastFmApi
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        createNotificationChannel()
        loadPreferences()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildBaseNotification("Listening for music…"))
        observeMediaSessions()
        return START_STICKY
    }

    override fun onDestroy() {
        cancelTimeout()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────
    // Media Session Observation
    // ──────────────────────────────────────────────────────────

    private fun observeMediaSessions() {
        try {
            val sessions = mediaSessionManager.getActiveSessions(
                android.content.ComponentName(this, MediaSessionListenerService::class.java)
            )
            sessions.firstOrNull()?.registerCallback(mediaControllerCallback, mainHandler)
        } catch (e: SecurityException) {
            updateNotification("Grant Notification Access in Settings", emptyList())
        }
    }

    // ──────────────────────────────────────────────────────────
    // Music Classification
    // ──────────────────────────────────────────────────────────

    private fun isMusicContent(title: String, durationMs: Long): Boolean {
        if (NON_MUSIC_REGEX.containsMatchIn(title)) return false
        if (durationMs in 1..MAX_DURATION_MS) return true
        return durationMs <= 0 // unknown duration — allow through
    }

    // ──────────────────────────────────────────────────────────
    // Last.fm Recommendation Engine
    // ──────────────────────────────────────────────────────────

    private fun fetchSuggestions(artist: String, track: String) {
        serviceScope.launch {
            updateNotification("Finding suggestions for "$track"…", emptyList())

            // 1. Verify track exists on Last.fm
            val trackInfo = runCatching {
                lastFmApi.getTrackInfo(artist = artist, track = track)
            }.getOrNull()

            if (trackInfo?.track == null) {
                updateNotification("No Last.fm match for "$track"", emptyList())
                return@launch
            }

            val suggestions = mutableListOf<SuggestionResult>()

            // 2a. Primary: track.getSimilar
            val similarTracks = runCatching {
                lastFmApi.getSimilarTracks(artist = artist, track = track, limit = 5)
            }.getOrNull()?.similarTracks?.tracks

            if (!similarTracks.isNullOrEmpty()) {
                similarTracks.take(3).forEach { t ->
                    suggestions += resolveSuggestion(t.artist.name, t.name)
                }
            }

            // 2b. Fallback A: artist.getSimilar → top tracks
            if (suggestions.size < 3) {
                val listeners = trackInfo.track.listeners?.toLongOrNull() ?: Long.MAX_VALUE
                val isObscure = listeners < 10_000

                if (isObscure || suggestions.isEmpty()) {
                    val similarArtists = runCatching {
                        lastFmApi.getSimilarArtists(artist = artist, limit = 5)
                    }.getOrNull()?.similarArtists?.artists

                    similarArtists
                        ?.filter { it.name.lowercase() != artist.lowercase() }
                        ?.take(3 - suggestions.size)
                        ?.forEach { simArtist ->
                            val topTrack = runCatching {
                                lastFmApi.getArtistTopTracks(artist = simArtist.name, limit = 1)
                            }.getOrNull()?.topTracks?.tracks?.firstOrNull()

                            if (topTrack != null) {
                                suggestions += resolveSuggestion(simArtist.name, topTrack.name)
                            }
                        }
                }
            }

            // 2c. Fallback B: current artist top tracks
            if (suggestions.isEmpty()) {
                val topTracks = runCatching {
                    lastFmApi.getArtistTopTracks(artist = artist, limit = 5)
                }.getOrNull()?.topTracks?.tracks

                topTracks
                    ?.filter { it.name.lowercase() != track.lowercase() }
                    ?.take(3)
                    ?.forEach { t ->
                        suggestions += resolveSuggestion(artist, t.name)
                    }
            }

            currentSuggestions = suggestions
            withContext(Dispatchers.Main) {
                updateNotification("Up next for you:", suggestions)
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Local Library Resolution (MediaStore + Fuzzy Match)
    // ──────────────────────────────────────────────────────────

    private suspend fun resolveSuggestion(artist: String, track: String): SuggestionResult =
        withContext(Dispatchers.IO) {
            val localUri = findLocalTrack(artist, track)
            SuggestionResult(
                artist = artist,
                track = track,
                localUri = localUri,
                isLocal = localUri != null
            )
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
            null,
            null
        ) ?: return null

        var bestUri: Uri? = null
        var bestScore = Int.MAX_VALUE
        val normalizedTarget = FuzzyMatcher.normalize("$artist $track")

        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

            while (it.moveToNext()) {
                val candidate = FuzzyMatcher.normalize(
                    "${it.getString(artistCol)} ${it.getString(titleCol)}"
                )
                val dist = FuzzyMatcher.levenshtein(normalizedTarget, candidate)
                val threshold = (normalizedTarget.length * 0.35).toInt()
                if (dist < bestScore && dist <= threshold) {
                    bestScore = dist
                    val id = it.getLong(idCol)
                    bestUri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                }
            }
        }
        return bestUri
    }

    // ──────────────────────────────────────────────────────────
    // Timeout Logic
    // ──────────────────────────────────────────────────────────

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
        val timeoutKey = prefs.getString("timeout", "3 min") ?: "3 min"
        timeoutDurationMs = TIMEOUT_OPTIONS[timeoutKey] ?: TIMEOUT_OPTIONS["3 min"]!!
    }

    // ──────────────────────────────────────────────────────────
    // Notification Builder
    // ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DJ Friend",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Music suggestions from DJ Friend"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildBaseNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dj_friend)
            .setContentTitle("DJ Friend")
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String, suggestions: List<SuggestionResult>) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dj_friend)
            .setContentTitle("🎵 DJ Friend — $currentTrack")
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                buildSuggestionsText(suggestions)
            ))

        suggestions.take(3).forEachIndexed { index, suggestion ->
            val icon = if (suggestion.isLocal) R.drawable.ic_folder else R.drawable.ic_web
            val label = buildString {
                append(if (suggestion.isLocal) "📁" else "🌐")
                append(" ${suggestion.track} – ${suggestion.artist}")
            }
            val pendingIntent = buildActionPendingIntent(index, suggestion)
            builder.addAction(icon, label, pendingIntent)
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun buildSuggestionsText(suggestions: List<SuggestionResult>): String {
        if (suggestions.isEmpty()) return "Searching Last.fm…"
        return suggestions.take(3).joinToString("\n") { s ->
            val sourceIcon = if (s.isLocal) "📁" else "🌐"
            "$sourceIcon  ${s.track} by ${s.artist}"
        }
    }

    private fun buildActionPendingIntent(index: Int, suggestion: SuggestionResult): PendingIntent {
        val intent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = if (suggestion.isLocal) ACTION_PLAY_LOCAL else ACTION_OPEN_SPOTIFY
            putExtra(EXTRA_ARTIST, suggestion.artist)
            putExtra(EXTRA_TRACK, suggestion.track)
            suggestion.localUri?.let { putExtra(EXTRA_MEDIA_URI, it.toString()) }
        }
        return PendingIntent.getBroadcast(
            this,
            index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

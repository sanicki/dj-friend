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
import com.djfriend.app.model.SuggestionResult
import com.djfriend.app.api.RetrofitClient
import com.djfriend.app.receiver.NotificationActionReceiver
import com.djfriend.app.util.FuzzyMatcher
import kotlinx.coroutines.*

class DjFriendService : Service() {

    companion object {
        const val CHANNEL_ID = "djfriend_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY_LOCAL = "com.djfriend.ACTION_PLAY_LOCAL"
        const val ACTION_OPEN_SPOTIFY = "com.djfriend.ACTION_OPEN_SPOTIFY"
        const val EXTRA_MEDIA_URI = "extra_media_uri"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_TRACK = "extra_track"

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
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastFmApi = RetrofitClient.lastFmApi
    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSessionManager: MediaSessionManager

    private var currentArtist = ""
    private var currentTrack = ""
    private var timeoutRunnable: Runnable? = null
    private var timeoutDurationMs = TIMEOUT_OPTIONS["3 min"]!!

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata ?: return
            val artist   = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: return
            val track    = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)  ?: return
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            cancelTimeout()
            if (isMusicContent(track, duration) && (artist != currentArtist || track != currentTrack)) {
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

    // Lifecycle

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        createNotificationChannel()
        loadPreferences()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildBaseNotification("Listening for music..."))
        observeMediaSessions()
        return START_STICKY
    }

    override fun onDestroy() {
        cancelTimeout()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Media Session

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
                lastFmApi.getTrackInfo(artist = artist, track = track)
            }.getOrNull()

            if (trackInfo?.track == null) {
                updateNotification("No Last.fm match for $track", emptyList())
                return@launch
            }

            val suggestions = mutableListOf<SuggestionResult>()

            // Primary: track.getSimilar
            runCatching { lastFmApi.getSimilarTracks(artist, track, 5) }
                .getOrNull()?.similarTracks?.tracks
                ?.take(3)
                ?.forEach { suggestions += resolveSuggestion(it.artist.name, it.name) }

            // Fallback A: artist.getSimilar -> each artist's top track
            if (suggestions.size < 3) {
                val listeners = trackInfo.track.listeners?.toLongOrNull() ?: Long.MAX_VALUE
                if (listeners < 10_000 || suggestions.isEmpty()) {
                    runCatching { lastFmApi.getSimilarArtists(artist, 5) }
                        .getOrNull()?.similarArtists?.artists
                        ?.filter { it.name.lowercase() != artist.lowercase() }
                        ?.take(3 - suggestions.size)
                        ?.forEach { simArtist ->
                            runCatching { lastFmApi.getArtistTopTracks(simArtist.name, 1) }
                                .getOrNull()?.topTracks?.tracks?.firstOrNull()
                                ?.let { suggestions += resolveSuggestion(simArtist.name, it.name) }
                        }
                }
            }

            // Fallback B: current artist top tracks
            if (suggestions.isEmpty()) {
                runCatching { lastFmApi.getArtistTopTracks(artist, 5) }
                    .getOrNull()?.topTracks?.tracks
                    ?.filter { it.name.lowercase() != track.lowercase() }
                    ?.take(3)
                    ?.forEach { suggestions += resolveSuggestion(artist, it.name) }
            }

            withContext(Dispatchers.Main) {
                updateNotification("Up next for you:", suggestions)
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
        val target = FuzzyMatcher.normalize("$artist $track")

        cursor.use { c ->
            val idIdx     = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIdx  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (c.moveToNext()) {
                val candidate = FuzzyMatcher.normalize(
                    "${c.getString(artistIdx)} ${c.getString(titleIdx)}"
                )
                val dist      = FuzzyMatcher.levenshtein(target, candidate)
                val threshold = (target.length * 0.35).toInt()
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
        val bigText = if (suggestions.isEmpty()) "Searching Last.fm..."
        else suggestions.take(3).joinToString("\n") {
            "${if (it.isLocal) "[Local]" else "[Web]"}  ${it.track} by ${it.artist}"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Now: $currentTrack")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        suggestions.take(3).forEachIndexed { i, s ->
            val icon  = if (s.isLocal) android.R.drawable.ic_menu_save
                        else           android.R.drawable.ic_menu_search
            val label = "${if (s.isLocal) "[Local]" else "[Web]"} ${s.track} - ${s.artist}"
            builder.addAction(icon, label, buildActionPendingIntent(i, s))
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun buildActionPendingIntent(index: Int, s: SuggestionResult): PendingIntent {
        val intent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = if (s.isLocal) ACTION_PLAY_LOCAL else ACTION_OPEN_SPOTIFY
            putExtra(EXTRA_ARTIST, s.artist)
            putExtra(EXTRA_TRACK, s.track)
            s.localUri?.let { putExtra(EXTRA_MEDIA_URI, it.toString()) }
        }
        return PendingIntent.getBroadcast(
            this, index, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

package com.djfriend.app.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
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
import org.json.JSONArray
import org.json.JSONObject

class DjFriendService : Service() {

    companion object {
        const val CHANNEL_ID             = "djfriend_channel"
        const val NOTIFICATION_ID        = 1001
        const val ACTION_PLAY_LOCAL      = "com.djfriend.ACTION_PLAY_LOCAL"
        const val ACTION_OPEN_SPOTIFY    = "com.djfriend.ACTION_OPEN_SPOTIFY"
        const val ACTION_SERVICE_STOPPED = "com.djfriend.ACTION_SERVICE_STOPPED"
        const val ACTION_STATE_UPDATE    = "com.djfriend.ACTION_STATE_UPDATE"
        const val ACTION_REQUEST_PAGE    = "com.djfriend.ACTION_REQUEST_PAGE"
        const val EXTRA_MEDIA_URI        = "extra_media_uri"
        const val EXTRA_ARTIST           = "extra_artist"
        const val EXTRA_TRACK            = "extra_track"
        const val EXTRA_STATE_JSON       = "extra_state_json"
        const val EXTRA_PAGE_OFFSET      = "extra_page_offset"
        const val PAGE_SIZE              = Int.MAX_VALUE   // default "All" — overridden by pref

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
        const val CHECK = "\u2714"
        const val CROSS = "\u2717"

        fun canonicalArtist(name: String) = FuzzyMatcher.normalizeArtist(name)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler   = Handler(Looper.getMainLooper())
    private val lastFmApi     = RetrofitClient.lastFmApi
    private val apiKey        = RetrofitClient.apiKey
    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSessionManager: MediaSessionManager

    private var currentArtist        = ""
    private var currentTrack         = ""
    private var currentPlayerPackage = ""
    private var currentTrackIsLocal  = false   // true if the now-playing track is in local library
    private var allCandidates        = mutableListOf<SuggestionResult>()

    private var timeoutRunnable: Runnable? = null
    private var timeoutDurationMs = TIMEOUT_OPTIONS["3 min"]!!

    // Map controller -> package. Each controller gets its OWN callback instance
    // so we can identify the sender without metadata identity comparison.
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val rescanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaSessionListenerService.ACTION_RESCAN -> observeMediaSessions()
                ACTION_REQUEST_PAGE -> {
                    val offset   = intent.getIntExtra(EXTRA_PAGE_OFFSET, 0)
                    val pageSize = intent.getIntExtra("extra_page_size", PAGE_SIZE)
                    broadcastStateUpdate(offset, pageSize)
                }
            }
        }
    }

    // ─── Build a per-controller callback capturing its package name ───────────
    private fun makeCallback(pkg: String): MediaController.Callback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                metadata ?: return
                val artist   = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim() ?: return
                val track    = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()  ?: return
                val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

                if (!isActivePackage(pkg)) return
                cancelTimeout()
                if (isMusicContent(track, duration) &&
                    (artist != currentArtist || track != currentTrack)) {
                    currentArtist        = artist
                    currentTrack         = track
                    currentPlayerPackage = pkg
                    currentTrackIsLocal  = false   // reset until checked
                    allCandidates.clear()
                    fetchSuggestions(artist, track)
                }
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state?.state == PlaybackState.STATE_PLAYING) {
                    // This app is now playing — promote it as the active player
                    currentPlayerPackage = pkg
                    cancelTimeout()
                } else if (state?.state == PlaybackState.STATE_PAUSED ||
                           state?.state == PlaybackState.STATE_STOPPED ||
                           state?.state == PlaybackState.STATE_NONE) {
                    // Only start the timeout if this is the package we consider active
                    if (pkg == currentPlayerPackage && timeoutDurationMs > 0) {
                        startTimeout()
                    }
                }
            }
        }

    // An app is "active" if it's currently playing OR it's the most-recently-playing app
    // and nothing else is currently playing.
    private fun isActivePackage(pkg: String): Boolean {
        val sessions = try {
            mediaSessionManager.getActiveSessions(
                android.content.ComponentName(this, MediaSessionListenerService::class.java)
            )
        } catch (e: SecurityException) { return false }

        val playingPackages = sessions
            .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .mapNotNull { it.packageName }
            .toSet()

        return if (playingPackages.isNotEmpty()) {
            pkg in playingPackages
        } else {
            pkg == currentPlayerPackage || currentPlayerPackage.isEmpty()
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        createNotificationChannel()
        loadPreferences()
        val filter = IntentFilter().apply {
            addAction(MediaSessionListenerService.ACTION_RESCAN)
            addAction(ACTION_REQUEST_PAGE)
        }
        ContextCompat.registerReceiver(this, rescanReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
        // Unregister all controller callbacks to avoid leaks
        controllerCallbacks.forEach { (controller, callback) ->
            try { controller.unregisterCallback(callback) } catch (_: Exception) {}
        }
        controllerCallbacks.clear()
        getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED).setPackage(packageName))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Do NOT stop on task removal — the service should survive the app being swiped away.
    // Android will restart it (START_STICKY) if the system kills it.
    // override fun onTaskRemoved(rootIntent: Intent?) { stopSelf(); super.onTaskRemoved(rootIntent) }

    // ─── Media Session ────────────────────────────────────────────────────────

    private fun observeMediaSessions() {
        try {
            val sessions = mediaSessionManager.getActiveSessions(
                android.content.ComponentName(this, MediaSessionListenerService::class.java)
            )

            sessions.forEach { controller ->
                if (!controllerCallbacks.containsKey(controller)) {
                    val pkg      = controller.packageName ?: ""
                    val callback = makeCallback(pkg)
                    controller.registerCallback(callback, mainHandler)
                    controllerCallbacks[controller] = callback

                    // Promote to active if currently playing
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        currentPlayerPackage = pkg
                    }

                    // Check already-playing metadata immediately
                    controller.metadata?.let { meta ->
                        val artist   = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim() ?: return@let
                        val track    = meta.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()  ?: return@let
                        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                            isMusicContent(track, duration) &&
                            (artist != currentArtist || track != currentTrack)) {
                            currentArtist        = artist
                            currentTrack         = track
                            currentPlayerPackage = pkg
                            currentTrackIsLocal  = false   // reset until checked
                            allCandidates.clear()
                            fetchSuggestions(artist, track)
                        }
                    }
                }
            }

            // Remove dead controllers and clean up their callbacks
            val activeSessions = sessions.toSet()
            val dead = controllerCallbacks.keys.filter { it !in activeSessions }
            dead.forEach { controller ->
                val callback = controllerCallbacks[controller]
                if (callback != null) {
                    try { controller.unregisterCallback(callback) } catch (_: Exception) {}
                }
                // If the dead controller was our active player, hand off to whatever is
                // currently playing — or clear the package so the next event wins.
                if (controller.packageName == currentPlayerPackage) {
                    val nowPlaying = sessions.firstOrNull {
                        it.playbackState?.state == PlaybackState.STATE_PLAYING
                    }
                    currentPlayerPackage = nowPlaying?.packageName ?: ""
                }
                controllerCallbacks.remove(controller)
            }

        } catch (e: SecurityException) {
            updateNotification("Grant Notification Access in Settings", emptyList(), 0)
        }
    }

    // ─── Music Classification ─────────────────────────────────────────────────

    private fun isMusicContent(title: String, durationMs: Long): Boolean {
        if (NON_MUSIC_REGEX.containsMatchIn(title)) return false
        if (durationMs in 1..MAX_DURATION_MS) return true
        return durationMs <= 0
    }

    // ─── Recommendation Engine ────────────────────────────────────────────────

    private fun fetchSuggestions(artist: String, track: String) {
        serviceScope.launch {
            updateNotification("Finding suggestions...", emptyList(), 0)

            val trackInfo = runCatching {
                lastFmApi.getTrackInfo(artist = artist, track = track, apiKey = apiKey)
            }.getOrNull()

            if (trackInfo?.track == null) {
                currentTrackIsLocal = false
                updateNotification("No Last.fm match for $track", emptyList(), 0)
                broadcastStateUpdate(0)
                return@launch
            }

            val usedArtists   = mutableSetOf(canonicalArtist(artist), artist.lowercase())
            val rawCandidates = mutableListOf<SuggestionResult>()

            val similarTracks = runCatching {
                lastFmApi.getSimilarTracks(artist, track, 50, apiKey)
            }.getOrNull()?.similarTracks?.tracks
                ?.sortedByDescending { it.match ?: 0f }
                ?: emptyList()

            similarTracks.forEach { t ->
                val canonical = canonicalArtist(t.artist.name)
                if (canonical in usedArtists || t.artist.name.lowercase() in usedArtists) return@forEach
                if ((t.match ?: 0f) < 0.2f) return@forEach
                rawCandidates += resolveSuggestion(t.artist.name, t.name)
                usedArtists += canonical
                usedArtists += t.artist.name.lowercase()
            }

            // Fallback A
            if (rawCandidates.size < 3) {
                val listeners = trackInfo.track.listeners?.toLongOrNull() ?: Long.MAX_VALUE
                if (listeners < 10_000 || rawCandidates.isEmpty()) {
                    runCatching { lastFmApi.getSimilarArtists(artist, 10, apiKey) }
                        .getOrNull()?.similarArtists?.artists
                        ?.forEach { simArtist ->
                            if (rawCandidates.size >= 3) return@forEach
                            val canonical = canonicalArtist(simArtist.name)
                            if (canonical in usedArtists || simArtist.name.lowercase() in usedArtists) return@forEach
                            runCatching { lastFmApi.getArtistTopTracks(simArtist.name, 1, apiKey) }
                                .getOrNull()?.topTracks?.tracks?.firstOrNull()?.let {
                                    rawCandidates += resolveSuggestion(simArtist.name, it.name)
                                    usedArtists += canonical
                                    usedArtists += simArtist.name.lowercase()
                                }
                        }
                }
            }

            // Fallback B
            if (rawCandidates.isEmpty()) {
                runCatching { lastFmApi.getArtistTopTracks(artist, 5, apiKey) }
                    .getOrNull()?.topTracks?.tracks
                    ?.filter { it.name.lowercase() != track.lowercase() }
                    ?.take(3)
                    ?.forEach { rawCandidates += resolveSuggestion(artist, it.name) }
            }

            allCandidates = rawCandidates
            // Check if the now-playing track itself is in the local library
            currentTrackIsLocal = findLocalTrack(artist, track) != null
            withContext(Dispatchers.Main) {
                updateNotification("Suggested for you:", allCandidates.take(3), 0)
                broadcastStateUpdate(0)
            }
        }
    }

    // ─── Local Library ────────────────────────────────────────────────────────

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
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} = 1", null, null
        ) ?: return null

        var bestUri   = null as Uri?
        var bestScore = Int.MAX_VALUE
        val target    = "${canonicalArtist(artist)} ${FuzzyMatcher.normalize(track)}"

        cursor.use { c ->
            val idIdx     = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIdx  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (c.moveToNext()) {
                val candidate = "${canonicalArtist(c.getString(artistIdx))} ${FuzzyMatcher.normalize(c.getString(titleIdx))}"
                val dist      = FuzzyMatcher.levenshtein(target, candidate)
                val threshold = (target.length * 0.35).toInt()
                if (dist < bestScore && dist <= threshold) {
                    bestScore = dist
                    bestUri   = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idIdx).toString()
                    )
                }
            }
        }
        return bestUri
    }

    // ─── State Broadcast ──────────────────────────────────────────────────────

    private fun prefPageSize(): Int {
        val stored = getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
            .getInt("songs_per_page", Int.MAX_VALUE)
        return if (stored == Int.MAX_VALUE) Int.MAX_VALUE else stored
    }

    fun broadcastStateUpdate(pageOffset: Int, pageSize: Int = prefPageSize()) {
        val page      = allCandidates.drop(pageOffset).take(pageSize)
        val canGoBack = pageOffset > 0
        val canGoMore = (pageOffset + pageSize) < allCandidates.size

        val suggestionsJson = JSONArray().apply {
            page.forEachIndexed { i, s ->
                put(JSONObject().apply {
                    put("artist",   s.artist)
                    put("track",    s.track)
                    put("isLocal",  s.isLocal)
                    put("localUri", s.localUri?.toString() ?: "")
                    put("index",    pageOffset + i)
                })
            }
        }

        sendBroadcast(
            Intent(ACTION_STATE_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE_JSON, JSONObject().apply {
                    put("currentArtist",     currentArtist)
                    put("currentTrack",      currentTrack)
                    put("currentPackage",    currentPlayerPackage)
                    put("currentTrackLocal", currentTrackIsLocal)
                    put("pageOffset",        pageOffset)
                    put("canGoBack",         canGoBack)
                    put("canGoMore",         canGoMore)
                    put("suggestions",       suggestionsJson)
                }.toString())
        )
    }

    // ─── Timeout ──────────────────────────────────────────────────────────────

    private fun startTimeout() {
        cancelTimeout()
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

    // ─── Notification ─────────────────────────────────────────────────────────

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

    private fun updateNotification(statusText: String, suggestions: List<SuggestionResult>, pageOffset: Int) {
        val prefs      = getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
        val copyFormat = prefs.getString("copy_format", "song_only") ?: "song_only"

        val bigText = SpannableStringBuilder()
        if (suggestions.isEmpty()) {
            bigText.append(statusText)
        } else {
            bigText.append("Suggested for you:\n")
            suggestions.forEachIndexed { i, s ->
                val symbol = if (s.isLocal) CHECK else CROSS
                val line   = "${pageOffset + i + 1}. ${s.track} by ${s.artist} $symbol\n"
                if (s.isLocal) {
                    val start = bigText.length
                    bigText.append(line)
                    bigText.setSpan(StyleSpan(Typeface.BOLD), start, bigText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    bigText.append(line)
                }
            }
        }

        val nowTitle = if (currentTrack.isNotEmpty() && currentArtist.isNotEmpty())
            "Now: $currentTrack by $currentArtist" else "DJ Friend"

        val openAppIntent = Intent(this, Class.forName("com.djfriend.app.ui.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(nowTitle)
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(contentIntent)
            .setOngoing(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setDeleteIntent(buildStopServicePendingIntent())

        suggestions.take(3).forEachIndexed { i, s ->
            val icon = if (s.isLocal) android.R.drawable.ic_menu_save
                       else           android.R.drawable.ic_menu_search
            builder.addAction(icon, "Suggestion ${i + 1}",
                buildActionPendingIntent(pageOffset + i, s, copyFormat))
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun buildStopServicePendingIntent(): PendingIntent {
        val intent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_STOP_SERVICE
        }
        return PendingIntent.getBroadcast(this, 999, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun buildActionPendingIntent(globalIndex: Int, s: SuggestionResult, copyFormat: String): PendingIntent {
        val intent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = if (s.isLocal) "${ACTION_PLAY_LOCAL}_$globalIndex"
                     else           "${ACTION_OPEN_SPOTIFY}_$globalIndex"
            putExtra(EXTRA_ARTIST, s.artist)
            putExtra(EXTRA_TRACK, s.track)
            putExtra(NotificationActionReceiver.EXTRA_COPY_FORMAT, copyFormat)
            putExtra(NotificationActionReceiver.EXTRA_IS_LOCAL, s.isLocal)
            s.localUri?.let { putExtra(EXTRA_MEDIA_URI, it.toString()) }
        }
        return PendingIntent.getBroadcast(this, globalIndex, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

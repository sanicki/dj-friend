package com.djfriend.app.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.djfriend.app.api.SpotifyLinkResolver
import com.djfriend.app.service.DjFriendService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_SERVICE = "com.djfriend.ACTION_STOP_SERVICE"
        const val EXTRA_COPY_FORMAT   = "extra_copy_format"
        const val EXTRA_IS_LOCAL      = "extra_is_local"

        /**
         * Shared handler for tapping a non-local suggestion from either the notification
         * or the in-app suggestion list. Resolves iTunes → Odesli → Spotify URL, then
         * acts based on the "web_action" pref:
         *
         *   "spotiflac" → resolve Spotify URL; if found copy it and open SpotiFLAC (or Spotify
         *                 as fallback if SpotiFLAC is not installed); if lookup fails copy
         *                 "artist - track" instead.
         *   "spotify"   → resolve Spotify URL; copy "artist - track", open Spotify URL directly.
         */
        fun handleWebSuggestionTap(context: Context, artist: String, track: String) {
            val prefs     = context.getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
            val webAction = prefs.getString("web_action", "spotiflac") ?: "spotiflac"

            Toast.makeText(context, "Finding link…", Toast.LENGTH_SHORT).show()

            CoroutineScope(Dispatchers.Main).launch {
                val spotifyUrl = SpotifyLinkResolver.resolve(artist, track)

                when (webAction) {
                    "spotiflac" -> {
                        if (spotifyUrl != null) {
                            // Spotify URL resolved — copy it, show toast, open SpotiFLAC (or Spotify)
                            copyToClipboard(context, spotifyUrl)
                            Toast.makeText(
                                context,
                                "Copied: Spotify URL for $artist - $track",
                                Toast.LENGTH_SHORT
                            ).show()
                            openSpotiflacOrSpotify(context, spotifyUrl)
                        } else {
                            // Lookup failed — copy "artist - track" as fallback
                            val fallbackText = "$artist - $track"
                            copyToClipboard(context, fallbackText)
                            Toast.makeText(
                                context,
                                "Copied: $fallbackText",
                                Toast.LENGTH_SHORT
                            ).show()
                            openSpotiflacOrSpotify(context, null)
                        }
                    }
                    else -> {
                        // "spotify" action: copy "artist - track", open Spotify URL
                        val artistTrack = "$artist - $track"
                        copyToClipboard(context, artistTrack)
                        val urlToOpen = spotifyUrl
                            ?: "https://open.spotify.com/search/${Uri.encode("$track $artist")}"
                        Toast.makeText(
                            context,
                            "Copied: $artistTrack",
                            Toast.LENGTH_SHORT
                        ).show()
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(urlToOpen))
                                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                    }
                }
            }
        }

        /**
         * Opens SpotiFLAC if installed; falls back to Spotify app (via URL) if available;
         * falls back to SpotiFLAC GitHub releases page as last resort.
         * [spotifyUrl] is used to deep-link into Spotify if SpotiFLAC is not present.
         */
        private fun openSpotiflacOrSpotify(context: Context, spotifyUrl: String?) {
            val spotiflacIntent = context.packageManager
                .getLaunchIntentForPackage("com.zarz.spotiflac")
            if (spotiflacIntent != null) {
                context.startActivity(spotiflacIntent.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return
            }

            // SpotiFLAC not installed — try opening Spotify directly with the URL
            if (spotifyUrl != null) {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUrl))
                            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )
                    return
                } catch (_: Exception) { /* fall through */ }
            }

            // Last resort: SpotiFLAC GitHub releases page
            context.startActivity(
                Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/zarzet/SpotiFLAC-Mobile/releases"))
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }

        fun copyToClipboard(context: Context, text: String) {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("DJ Friend suggestion", text))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when {
            action == ACTION_STOP_SERVICE -> {
                context.stopService(Intent(context, DjFriendService::class.java))
            }

            action.startsWith("com.djfriend.ACTION_PLAY_LOCAL") ||
            action.startsWith("com.djfriend.ACTION_OPEN_SPOTIFY") -> {
                val artist     = intent.getStringExtra(DjFriendService.EXTRA_ARTIST)  ?: return
                val track      = intent.getStringExtra(DjFriendService.EXTRA_TRACK)   ?: return
                val isLocal    = intent.getBooleanExtra(EXTRA_IS_LOCAL, false)
                val copyFormat = intent.getStringExtra(EXTRA_COPY_FORMAT) ?: "song_only"

                if (isLocal) {
                    val text = if (copyFormat == "artist_song") "$artist - $track" else track
                    copyToClipboard(context, text)
                    Toast.makeText(context, "Copied: $text", Toast.LENGTH_SHORT).show()
                } else {
                    val pending = goAsync()
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            handleWebSuggestionTap(context, artist, track)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }
    }
}

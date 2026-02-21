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
         *   "spotiflac" → copy Spotify URL, open SpotiFLAC
         *   "spotify"   → copy "Track by Artist", open Spotify URL
         */
        fun handleWebSuggestionTap(context: Context, artist: String, track: String) {
            val prefs     = context.getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
            val webAction = prefs.getString("web_action", "spotiflac") ?: "spotiflac"

            // Show immediate feedback while resolving
            Toast.makeText(context, "Finding link…", Toast.LENGTH_SHORT).show()

            CoroutineScope(Dispatchers.Main).launch {
                val spotifyUrl = SpotifyLinkResolver.resolve(artist, track)
                    ?: run {
                        // Fallback: plain Spotify search URL
                        val q = Uri.encode("$track $artist")
                        "https://open.spotify.com/search/$q"
                    }

                when (webAction) {
                    "spotiflac" -> {
                        // Copy Spotify URL, open SpotiFLAC
                        copyToClipboard(context, spotifyUrl)
                        openSpotiflac(context)
                    }
                    else -> {
                        // Copy "Track by Artist", open Spotify URL
                        copyToClipboard(context, "$track by $artist")
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUrl))
                                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                    }
                }
            }
        }

        private fun openSpotiflac(context: Context) {
            // Try SpotiFLAC app first; fall back to Play Store page
            val launch = context.packageManager.getLaunchIntentForPackage("com.spotiflac.android")
            if (launch != null) {
                context.startActivity(launch.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/zarzet/SpotiFLAC-Mobile/releases"))
                        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
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
                    // Use goAsync() so we can launch a coroutine from onReceive
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

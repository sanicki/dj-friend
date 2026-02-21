package com.djfriend.app.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.djfriend.app.service.DjFriendService

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_SERVICE = "com.djfriend.ACTION_STOP_SERVICE"
        const val EXTRA_COPY_FORMAT   = "extra_copy_format"
        const val EXTRA_IS_LOCAL      = "extra_is_local"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when {
            action == ACTION_STOP_SERVICE -> {
                context.stopService(Intent(context, DjFriendService::class.java))
            }

            // Both local and Spotify actions share this handler — differentiated by EXTRA_IS_LOCAL
            action.startsWith("com.djfriend.ACTION_PLAY_LOCAL") ||
            action.startsWith("com.djfriend.ACTION_OPEN_SPOTIFY") -> {
                val artist   = intent.getStringExtra(DjFriendService.EXTRA_ARTIST)  ?: return
                val track    = intent.getStringExtra(DjFriendService.EXTRA_TRACK)   ?: return
                val isLocal  = intent.getBooleanExtra(EXTRA_IS_LOCAL, false)
                val copyFormat = intent.getStringExtra(EXTRA_COPY_FORMAT) ?: "song_only"

                if (isLocal) {
                    // Local: copy in user's chosen format
                    val text = if (copyFormat == "artist_song") "$artist - $track" else track
                    copyToClipboard(context, text)
                    Toast.makeText(context, "Copied: $text", Toast.LENGTH_SHORT).show()
                } else {
                    // Spotify: always copy "Track by Artist", then open search
                    copyToClipboard(context, "$track by $artist")
                    val query = Uri.encode("$track $artist")
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://open.spotify.com/search/$query"))
                            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )
                }
            }
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("DJ Friend suggestion", text))
    }
}

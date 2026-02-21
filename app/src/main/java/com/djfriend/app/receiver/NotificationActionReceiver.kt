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
        const val ACTION_STOP_SERVICE  = "com.djfriend.ACTION_STOP_SERVICE"
        const val EXTRA_COPY_FORMAT    = "extra_copy_format"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {

            // Fired when user swipes the notification away
            ACTION_STOP_SERVICE -> {
                context.stopService(Intent(context, DjFriendService::class.java))
            }

            // Local match: copy to clipboard using chosen format
            DjFriendService.ACTION_PLAY_LOCAL -> {
                val artist     = intent.getStringExtra(DjFriendService.EXTRA_ARTIST) ?: return
                val track      = intent.getStringExtra(DjFriendService.EXTRA_TRACK)  ?: return
                val copyFormat = intent.getStringExtra(EXTRA_COPY_FORMAT) ?: "song_only"
                val text       = if (copyFormat == "artist_song") "$artist - $track" else track
                val clipboard  = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("DJ Friend suggestion", text))
                Toast.makeText(context, "Copied: $text", Toast.LENGTH_SHORT).show()
            }

            // Web match: open Spotify search
            DjFriendService.ACTION_OPEN_SPOTIFY -> {
                val artist = intent.getStringExtra(DjFriendService.EXTRA_ARTIST) ?: return
                val track  = intent.getStringExtra(DjFriendService.EXTRA_TRACK)  ?: return
                val query  = Uri.encode("$track $artist")
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$query"))
                        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
        }
    }
}

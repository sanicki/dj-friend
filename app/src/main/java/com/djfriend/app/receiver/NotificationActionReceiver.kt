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
    override fun onReceive(context: Context, intent: Intent) {
        val artist = intent.getStringExtra(DjFriendService.EXTRA_ARTIST) ?: return
        val track  = intent.getStringExtra(DjFriendService.EXTRA_TRACK)  ?: return

        when (intent.action) {

            // Local match: copy "Track by Artist" to clipboard
            DjFriendService.ACTION_PLAY_LOCAL -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val label = "$track by $artist"
                clipboard.setPrimaryClip(ClipData.newPlainText("DJ Friend suggestion", label))
                Toast.makeText(context, "Copied: $label", Toast.LENGTH_SHORT).show()
            }

            // No local match: open Spotify search
            DjFriendService.ACTION_OPEN_SPOTIFY -> {
                // Format: https://open.spotify.com/search/Heaven%20is%20a%20Place%20on%20Earth%20Belinda%20Carlisle
                val query = Uri.encode("$track $artist")
                val url   = "https://open.spotify.com/search/$query"
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }
}

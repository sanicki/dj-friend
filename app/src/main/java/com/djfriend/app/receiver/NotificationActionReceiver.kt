package com.djfriend.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.djfriend.app.service.DjFriendService

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DjFriendService.ACTION_PLAY_LOCAL -> {
                val uriString = intent.getStringExtra(DjFriendService.EXTRA_MEDIA_URI) ?: return
                val playIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(uriString), "audio/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // Launch in user's preferred audio player
                context.startActivity(Intent.createChooser(playIntent, "Play with").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }

            DjFriendService.ACTION_OPEN_SPOTIFY -> {
                val artist = intent.getStringExtra(DjFriendService.EXTRA_ARTIST) ?: return
                val track = intent.getStringExtra(DjFriendService.EXTRA_TRACK) ?: return
                val query = Uri.encode("$artist $track")
                val spotifyIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://open.spotify.com/search/$query")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(spotifyIntent)
            }
        }
    }
}

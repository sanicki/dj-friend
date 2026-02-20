package com.djfriend.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.djfriend.app.service.DjFriendService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("djfriend_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("start_on_boot", false)) {
                context.startForegroundService(Intent(context, DjFriendService::class.java))
            }
        }
    }
}

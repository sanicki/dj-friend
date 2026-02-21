package com.djfriend.app.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Keeps the NotificationListenerService connection alive so
 * MediaSessionManager.getActiveSessions() stays authorised.
 *
 * Also broadcasts a rescan signal to DjFriendService whenever
 * notifications change — this catches new media apps starting up
 * after DJ Friend is already running.
 */
class MediaSessionListenerService : NotificationListenerService() {

    companion object {
        const val ACTION_RESCAN = "com.djfriend.ACTION_RESCAN_SESSIONS"
    }

    private fun rescan() {
        sendBroadcast(Intent(ACTION_RESCAN).setPackage(packageName))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        rescan()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        rescan()
    }

    override fun onListenerConnected() {
        rescan()
    }
}

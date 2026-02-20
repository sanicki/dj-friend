package com.djfriend.app.service

import android.service.notification.NotificationListenerService

/**
 * Stub required so MediaSessionManager.getActiveSessions() accepts our ComponentName.
 * The actual listening is done via MediaController.Callback in DjFriendService.
 */
class MediaSessionListenerService : NotificationListenerService()

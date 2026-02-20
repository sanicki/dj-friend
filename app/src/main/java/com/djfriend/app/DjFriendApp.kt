package com.djfriend.app

import android.app.Application

class DjFriendApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Global app initialisation (logging, crash reporting, etc.) goes here
    }
}

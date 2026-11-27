package com.lyrix.overlay

import android.app.Application

class LyrixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Favorites.init(this)
        Library.init()
    }
}

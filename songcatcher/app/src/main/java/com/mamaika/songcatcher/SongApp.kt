package com.mamaika.songcatcher

import android.app.Application
import android.util.Log
import java.io.File

class SongApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(Log.getStackTraceString(e))
            } catch (_: Throwable) {
            }
            old?.uncaughtException(t, e)
        }
    }
}

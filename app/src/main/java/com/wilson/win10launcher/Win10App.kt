package com.wilson.win10launcher

import android.app.Application

/** Keeps the last crash so Diagnostics can send it. */
class Win10App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                Diagnostics.recordCrash(this, error)
            } catch (e: Exception) {
                // the process is already dying
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

package ru.aska.icq

import android.app.Application
import java.io.File

class AskaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText("Поток: ${t.name}\n" + e.stackTraceToString())
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}

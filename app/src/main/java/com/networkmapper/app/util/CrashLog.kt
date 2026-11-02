package com.networkmapper.app.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.networkmapper.app.BuildConfig
import java.io.File

/**
 * Keeps the details of the last crash on the phone, so they can be shown (and shared) the next time the app
 * opens. Nothing is sent anywhere automatically.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file(app).writeText(
                    "Network Mapper ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) – Android ${Build.VERSION.RELEASE} " +
                        "(SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "${Fmt.dateTime(System.currentTimeMillis())} on thread ${thread.name}\n\n" +
                        Log.getStackTraceString(error),
                )
            } catch (ignored: Throwable) {
                // Never let the crash logger itself get in the way.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        file(context).delete()
    }
}

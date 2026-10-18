package com.quickshare.app.util

import android.os.Build
import android.util.Log
import com.quickshare.core.util.Logs

/**
 * Routes [Logs] to Logcat.
 *
 * Installed only when `BuildConfig.DEBUG` is true (see [com.quickshare.app.QuickShareApp]), which is
 * what guarantees the released APK writes no logs at all.
 */
class AndroidLogSink : Logs.Sink {

    override fun log(level: Logs.Level, tag: String, message: String, throwable: Throwable?) {
        val safeTag = if (tag.length > 23 && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            tag.substring(0, 23)
        } else {
            tag
        }
        when (level) {
            Logs.Level.DEBUG -> Log.d(safeTag, message, throwable)
            Logs.Level.INFO -> Log.i(safeTag, message, throwable)
            Logs.Level.WARN -> Log.w(safeTag, message, throwable)
            Logs.Level.ERROR -> Log.e(safeTag, message, throwable)
        }
    }
}

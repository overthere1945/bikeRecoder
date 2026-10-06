package com.cowork.bikerecoder

import android.util.Log

/** Where exceptions that are caught and handled (not rethrown) are reported. Injected so JVM tests can record them. */
fun interface WarnLog {
    fun warn(message: String, error: Throwable?)
}

/** [WarnLog] to logcat at WARN level under [tag]. */
fun androidWarnLog(tag: String) = WarnLog { message, error -> Log.w(tag, message, error) }

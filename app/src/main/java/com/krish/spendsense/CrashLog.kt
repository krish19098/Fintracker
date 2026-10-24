package com.krish.spendsense

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZonedDateTime

/**
 * Diagnostic aid: keeps the last crash stack trace in app-private storage (never sent anywhere),
 * shown under Settings -> About.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Records uncaught exceptions, then lets the previous handler finish the crash as usual. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            record(app, e, "Crash on thread ${thread.name}")
            previous?.uncaughtException(thread, e)
        }
    }

    fun record(ctx: Context, e: Throwable, title: String) {
        runCatching {
            val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
            file(ctx).writeText("$title\n${ZonedDateTime.now()}\nVersion ${BuildConfig.VERSION_NAME}\n\n$trace")
        }
    }

    fun read(ctx: Context): String? = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}

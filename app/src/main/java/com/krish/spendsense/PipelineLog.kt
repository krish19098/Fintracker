package com.krish.spendsense

import android.content.Context
import android.util.Log
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Diagnostic trail of the SMS -> transaction -> limit -> notification pipeline, kept in app-private
 * storage and shown under Settings -> About. It records outcomes only: never message text, payee
 * names, account digits or amounts of individual payments.
 */
object PipelineLog {
    private const val FILE = "pipeline_log.txt"
    private const val MAX_LINES = 80
    private val fmt = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss", Locale.ENGLISH)

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun record(ctx: Context, event: String) {
        if (BuildConfig.DEBUG) Log.d("SpendSense", event)
        runCatching {
            val f = file(ctx)
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "${fmt.format(ZonedDateTime.now())}  $event"
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    fun read(ctx: Context): String? = runCatching { file(ctx).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } }.getOrNull()

    @Synchronized
    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}

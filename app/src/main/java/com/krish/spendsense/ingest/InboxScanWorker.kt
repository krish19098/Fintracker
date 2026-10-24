package com.krish.spendsense.ingest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.krish.spendsense.SpendSenseApp
import com.krish.spendsense.data.AppSettings
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit
import com.krish.spendsense.parse.ParseUtils

/** One inbox row. */
data class InboxSms(val address: String, val body: String, val date: Long, val dateSent: Long, val subId: Int?)

object SmsInbox {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Inbox messages received after [since], oldest first. `sub_id` is used when the ROM has it. */
    fun read(ctx: Context, since: Long): List<InboxSms> {
        if (!hasPermission(ctx)) return emptyList()
        val base = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT)
        val withSub = base + Telephony.Sms.SUBSCRIPTION_ID
        fun query(projection: Array<String>) = ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection, "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC",
        )
        val cursor = try {
            query(withSub)
        } catch (_: Exception) {
            try { query(base) } catch (_: Exception) { null }
        } ?: return emptyList()
        return cursor.use { c ->
            val sub = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            buildList {
                while (c.moveToNext()) {
                    val address = c.getString(0) ?: continue
                    val body = c.getString(1) ?: continue
                    val date = c.getLong(2)
                    add(InboxSms(address, body, date, c.getLong(3).takeIf { it > 0 } ?: date, if (sub >= 0) c.getInt(sub) else null))
                }
            }
        }
    }
}

/** Every 15 minutes and on app open: catches anything the receiver missed. */
class InboxScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as SpendSenseApp).container
        val s = c.settings.get()
        val now = System.currentTimeMillis()
        // Before the first backfill only the last day is scanned; backfill covers the rest.
        val since = if (s.lastScanTs > 0) s.lastScanTs else now - TimeUnit.DAYS.toMillis(1)
        var latest = since
        for (m in SmsInbox.read(applicationContext, since)) {
            c.ingestor.onSms(m.address, m.body, m.dateSent, m.date, m.subId, alert = now - m.date < TimeUnit.HOURS.toMillis(2))
            latest = maxOf(latest, m.date)
        }
        c.settings.setLastScan(latest)
        return Result.success()
    }
}

/** End-of-day summary, weekly and monthly digests; each reschedules itself for the next run. */
class SummaryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as SpendSenseApp).container
        val kind = inputData.getInt(KEY_KIND, DAILY)
        try {
            Summaries.post(c, kind)
        } finally {
            Work.scheduleSummary(applicationContext, c.settings.get(), kind)
        }
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
        const val DAILY = 0
        const val WEEKLY = 1
        const val MONTHLY = 2
    }
}

object Work {
    private const val SCAN_PERIODIC = "inbox_scan_periodic"
    private const val SCAN_NOW = "inbox_scan_now"
    private val summaryNames = listOf("summary_daily", "summary_weekly", "summary_monthly")

    fun schedulePeriodicScan(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        SCAN_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<InboxScanWorker>(15, TimeUnit.MINUTES).build(),
    )

    fun scanNow(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork(
        SCAN_NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<InboxScanWorker>().build(),
    )

    /** Next occurrence (IST) of the summary [kind] after now. */
    fun nextRun(now: ZonedDateTime, s: AppSettings, kind: Int): ZonedDateTime {
        val minute = when (kind) {
            SummaryWorker.WEEKLY -> s.weeklyMinute
            SummaryWorker.MONTHLY -> s.monthlyMinute
            else -> s.summaryMinute
        }
        val t = LocalTime.of(minute / 60, minute % 60)
        var at = now.with(t)
        when (kind) {
            SummaryWorker.WEEKLY -> {
                at = at.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                if (!at.isAfter(now)) at = at.plusWeeks(1)
            }
            SummaryWorker.MONTHLY -> {
                at = at.withDayOfMonth(1)
                if (!at.isAfter(now)) at = at.plusMonths(1)
            }
            else -> if (!at.isAfter(now)) at = at.plusDays(1)
        }
        return at
    }

    fun scheduleSummary(ctx: Context, s: AppSettings, kind: Int) {
        val now = ZonedDateTime.now(ParseUtils.IST)
        val delay = Duration.between(now, nextRun(now, s, kind)).toMillis()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            summaryNames[kind], ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SummaryWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(SummaryWorker.KEY_KIND to kind))
                .build(),
        )
    }

    fun scheduleAllSummaries(ctx: Context, s: AppSettings) = (0..2).forEach { scheduleSummary(ctx, s, it) }
}

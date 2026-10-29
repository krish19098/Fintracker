package com.krish.spendsense.ingest

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.krish.spendsense.SpendSenseApp
import com.krish.spendsense.widget.SpendWidget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** First-run backfill of the last 180 days of the SMS inbox, with progress for the UI. */
class BackfillWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as SpendSenseApp).container
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Backfill.DAYS)
        val messages = SmsInbox.read(applicationContext, since)
        var latest = since
        messages.forEachIndexed { i, m ->
            c.ingestor.onSms(m.address, m.body, m.dateSent, m.date, m.subId, alert = false, via = "backfill")
            latest = maxOf(latest, m.date)
            if (i % 25 == 0) setProgress(workDataOf(Backfill.DONE to i, Backfill.TOTAL to messages.size))
        }
        c.settings.setLastScan(latest)
        c.settings.setBackfillDone(true)
        c.log("backfill: read ${messages.size} SMS from the last ${Backfill.DAYS} days")
        SpendWidget.refresh(applicationContext)
        return Result.success(workDataOf(Backfill.DONE to messages.size, Backfill.TOTAL to messages.size))
    }
}

data class BackfillState(val running: Boolean, val done: Int, val total: Int, val finished: Boolean)

object Backfill {
    const val DAYS = 180L
    const val DONE = "done"
    const val TOTAL = "total"
    private const val NAME = "backfill"

    fun start(ctx: Context) = WorkManager.getInstance(ctx)
        .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BackfillWorker>().build())

    fun observe(ctx: Context): Flow<BackfillState?> = WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(NAME).map { list ->
        list.lastOrNull()?.let { w ->
            val data = if (w.state == WorkInfo.State.SUCCEEDED) w.outputData else w.progress
            BackfillState(
                running = w.state == WorkInfo.State.RUNNING || w.state == WorkInfo.State.ENQUEUED,
                done = data.getInt(DONE, 0), total = data.getInt(TOTAL, 0),
                finished = w.state == WorkInfo.State.SUCCEEDED,
            )
        }
    }
}

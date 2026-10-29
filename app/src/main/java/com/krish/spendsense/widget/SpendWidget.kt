package com.krish.spendsense.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.krish.spendsense.MainActivity
import com.krish.spendsense.R
import com.krish.spendsense.SpendSenseApp
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.domain.Money
import com.krish.spendsense.domain.SpendBand
import com.krish.spendsense.domain.SpendState
import com.krish.spendsense.ingest.Notifier
import com.krish.spendsense.parse.ParseUtils
import com.krish.spendsense.ui.SpendPalettes
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Home-screen widget: today's spend vs the daily limit, coloured by the same [SpendState] as the app. */
object SpendWidget {
    const val ACTION_REFRESH = "com.krish.spendsense.widget.REFRESH"

    fun background(band: SpendBand): Int = when (band) {
        SpendBand.HEALTHY -> R.drawable.widget_bg_healthy
        SpendBand.APPROACHING -> R.drawable.widget_bg_approaching
        SpendBand.NEAR -> R.drawable.widget_bg_near
        SpendBand.OVER -> R.drawable.widget_bg_over
    }

    fun render(context: Context, state: SpendState): RemoteViews = RemoteViews(context.packageName, R.layout.widget_spend).apply {
        setInt(R.id.widget_root, "setBackgroundResource", background(state.band))
        setTextViewText(R.id.widget_amount, Money.short(state.spentPaise))
        setTextViewText(
            R.id.widget_detail,
            if (state.hasLimit) "of ${Money.short(state.limitPaise)} · ${state.percent}% used" else "Set a daily limit in Settings",
        )
        setTextViewText(R.id.widget_status, SpendPalettes.status(state))
        setProgressBar(R.id.widget_progress, 100, state.percent.coerceIn(0, 100), false)
        setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(Notifier.EXTRA_OPEN, Notifier.OPEN_HOME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    /** Today's state from the database and settings: the same inputs Home uses for "All". */
    suspend fun currentState(context: Context, today: LocalDate = LocalDate.now(ParseUtils.IST)): SpendState {
        val c = (context.applicationContext as SpendSenseApp).container
        val s = c.settings.get()
        return SpendState.of(c.repository.dayTxns(today), today, s.dailyLimitPaise, s.warnPct, c.repository.excludedAccountIds())
    }

    /** Re-renders every placed widget. Cheap no-op when none is placed. */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val mgr = AppWidgetManager.getInstance(app) ?: return
        val ids = mgr.getAppWidgetIds(ComponentName(app, SpendWidgetReceiver::class.java))
        if (ids == null || ids.isEmpty()) return
        mgr.updateAppWidget(ids, render(app, currentState(app)))
        scheduleMidnight(app)
    }

    /** An inexact alarm just after IST midnight resets the widget to the new day (no exact-alarm permission needed). */
    private fun scheduleMidnight(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = Dates.startOf(LocalDate.now(ParseUtils.IST).plusDays(1)) + 30_000
        val pi = PendingIntent.getBroadcast(
            context, 1, Intent(context, SpendWidgetReceiver::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.set(AlarmManager.RTC, next, pi)
    }
}

class SpendWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refreshAsync(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            SpendWidget.ACTION_REFRESH, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refreshAsync(context)
        }
    }

    private fun refreshAsync(context: Context) {
        val c = (context.applicationContext as SpendSenseApp).container
        val pending = goAsync()
        c.appScope.launch {
            try {
                SpendWidget.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}

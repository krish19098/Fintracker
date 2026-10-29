package com.krish.spendsense.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.krish.spendsense.SpendSenseApp
import com.krish.spendsense.widget.SpendWidget
import kotlinx.coroutines.launch

/** Reschedules work after boot / app update and checks for a swapped SIM. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val c = (context.applicationContext as SpendSenseApp).container
        val pending = goAsync()
        c.appScope.launch {
            try {
                Work.schedulePeriodicScan(context)
                Work.scheduleAllSummaries(context, c.settings.get())
                Work.scanNow(context)
                if (c.repository.refreshSims(c.simDetector.current()).isNotEmpty()) c.notifier.simChanged(c.settings.get().moneySounds)
                SpendWidget.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}

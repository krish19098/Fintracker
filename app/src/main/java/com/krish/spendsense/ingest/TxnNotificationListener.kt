package com.krish.spendsense.ingest

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.krish.spendsense.SpendSenseApp
import com.krish.spendsense.parse.AppNotifParser
import kotlinx.coroutines.launch

/** Reads only GPay / Paytm notifications; every other package is ignored before touching content. */
class TxnNotificationListener : NotificationListenerService() {
    private val container get() = (applicationContext as SpendSenseApp).container

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (AppNotifParser.forPackage(sbn.packageName) == null) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        // `when` survives notification updates, so a re-posted notification hashes the same.
        val at = n.`when`.takeIf { it > 0 } ?: sbn.postTime
        container.appScope.launch { container.ingestor.onNotification(sbn.packageName, title, text, at) }
    }

    override fun onListenerConnected() {
        container.appScope.launch { container.settings.setListenerState(true, System.currentTimeMillis()) }
    }

    override fun onListenerDisconnected() {
        container.appScope.launch { container.settings.setListenerState(false, System.currentTimeMillis()) }
        requestRebind(ComponentName(this, TxnNotificationListener::class.java))
    }

    companion object {
        fun isEnabled(context: Context) = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}

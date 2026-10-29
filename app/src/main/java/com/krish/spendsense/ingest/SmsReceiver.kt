package com.krish.spendsense.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.krish.spendsense.SpendSenseApp
import kotlinx.coroutines.launch

/** Manifest-declared so it fires even when HyperOS has killed the app. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // Malformed PDUs can make the platform parser throw; such a broadcast is just skipped.
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull() }.getOrNull().orEmpty()
        if (messages.isEmpty()) return
        val extras = intent.extras
        val subId = listOf("subscription", SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, "android.telephony.extra.SUBSCRIPTION_INDEX")
            .firstOrNull { extras?.containsKey(it) == true }?.let { extras!!.getInt(it, -1) }
        val receivedAt = System.currentTimeMillis()
        val c = (context.applicationContext as SpendSenseApp).container
        val woke = SystemClock.elapsedRealtime() - SpendSenseApp.startedAtElapsed < 5_000
        c.log("receiver: SMS broadcast received${if (woke) " (it started the app)" else ""}")
        val pending = goAsync()
        c.appScope.launch {
            try {
                // Multipart messages arrive together; group by sender and join parts in order.
                messages.groupBy { it.originatingAddress.orEmpty() }.forEach { (sender, parts) ->
                    if (sender.isEmpty()) return@forEach
                    c.ingestor.onSms(sender, parts.joinToString("") { it.messageBody.orEmpty() }, parts.first().timestampMillis, receivedAt, subId, alert = true)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

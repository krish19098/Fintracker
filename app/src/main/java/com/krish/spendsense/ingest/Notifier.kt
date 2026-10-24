package com.krish.spendsense.ingest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.krish.spendsense.MainActivity
import com.krish.spendsense.R
import com.krish.spendsense.data.Account
import com.krish.spendsense.data.Category
import com.krish.spendsense.data.Txn
import com.krish.spendsense.domain.LimitAlert

/** All local notifications. Channels: Transactions, Limits, Summaries. */
class Notifier(private val context: Context) {
    companion object {
        const val CH_TXN = "transactions"
        const val CH_LIMITS = "limits"
        const val CH_SUMMARY = "summaries"
        const val EXTRA_TXN_ID = "txnId"
        const val EXTRA_CATEGORY_SHEET = "categorySheet"
        const val EXTRA_OPEN = "open"
        const val OPEN_INSIGHTS = "insights"
        const val OPEN_ACCOUNTS = "accounts"
        const val OPEN_HOME = "home"
        const val KEY_NOTE = "note"

        private const val ID_LIMIT = 1_000_000
        private const val ID_SUMMARY = 1_000_100
        private const val ID_ACCOUNT = 1_000_200
        private const val ID_SIM = 1_000_300

        fun txnNotifId(txnId: Long) = (txnId % ID_LIMIT).toInt()
    }

    private val nm = NotificationManagerCompat.from(context)

    fun createChannels() {
        val m = context.getSystemService(NotificationManager::class.java) ?: return
        m.createNotificationChannels(
            listOf(
                NotificationChannel(CH_TXN, "Transactions", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_LIMITS, "Limits", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_SUMMARY, "Summaries", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    private fun openApp(requestCode: Int, block: Intent.() -> Unit): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            block()
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun base(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xFF0F766E.toInt())
        .setAutoCancel(true)

    private fun post(id: Int, n: NotificationCompat.Builder) {
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, n.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked; Settings shows it in the permission checklist.
        }
    }

    /**
     * "₹250 → Ravi Tea Stall · Today ₹1,840 / ₹2,000". Android shows at most 3 action buttons, so
     * the top 2 suggested categories and "Add note" are buttons; tapping opens the "Other…" sheet.
     */
    fun postTxn(t: Txn, text: String, suggestions: List<Category>, silent: Boolean = false) {
        val id = txnNotifId(t.id)
        val b = base(CH_TXN)
            .setContentTitle(text.substringBefore(" · "))
            .setContentText(text.substringAfter(" · ", ""))
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setContentIntent(openApp(id) { putExtra(EXTRA_TXN_ID, t.id); putExtra(EXTRA_CATEGORY_SHEET, true) })
        suggestions.take(2).forEach { c ->
            b.addAction(0, c.name, NotifActionReceiver.setCategory(context, t.id, c.id))
        }
        val reply = RemoteInput.Builder(KEY_NOTE).setLabel("Add note").build()
        b.addAction(
            NotificationCompat.Action.Builder(0, "Add note", NotifActionReceiver.addNote(context, t.id))
                .addRemoteInput(reply).setAllowGeneratedReplies(false).build(),
        )
        post(id, b)
    }

    fun cancelTxn(txnId: Long) = nm.cancel(txnNotifId(txnId))

    fun postLimit(a: LimitAlert, idOffset: Int = 0) =
        post(ID_LIMIT + (a.keys.last().hashCode() and 0x7f) + idOffset, base(CH_LIMITS).setContentTitle(a.title).setContentText(a.text)
            .setContentIntent(openApp(ID_LIMIT) { putExtra(EXTRA_OPEN, OPEN_HOME) }))

    fun postLarge(t: Txn, text: String) =
        post(ID_LIMIT + 200 + txnNotifId(t.id) % 100, base(CH_LIMITS).setContentTitle("Large payment").setContentText(text)
            .setContentIntent(openApp(txnNotifId(t.id)) { putExtra(EXTRA_TXN_ID, t.id) }))

    fun postSummary(kind: Int, title: String, text: String, openInsights: Boolean) =
        post(ID_SUMMARY + kind, base(CH_SUMMARY).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ID_SUMMARY + kind) { putExtra(EXTRA_OPEN, if (openInsights) OPEN_INSIGHTS else OPEN_HOME) }))

    fun newAccount(a: Account) =
        post(ID_ACCOUNT + (a.id % 100).toInt(), base(CH_TXN).setContentTitle("New account found: ${a.bank} ••${a.last4}")
            .setContentText("Give it a name?")
            .setContentIntent(openApp(ID_ACCOUNT) { putExtra(EXTRA_OPEN, OPEN_ACCOUNTS) }))

    fun simChanged() =
        post(ID_SIM, base(CH_TXN).setContentTitle("New SIM detected").setContentText("Open SpendSense to keep your accounts linked")
            .setContentIntent(openApp(ID_SIM) { putExtra(EXTRA_OPEN, OPEN_HOME) }))

    /** "HDFC ••1234 now on SIM 2" */
    fun toast(text: String) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}

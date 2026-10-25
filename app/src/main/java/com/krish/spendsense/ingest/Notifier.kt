package com.krish.spendsense.ingest

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import android.widget.RemoteViews
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.krish.spendsense.MainActivity
import com.krish.spendsense.R
import com.krish.spendsense.data.Account
import com.krish.spendsense.data.Category
import com.krish.spendsense.data.Txn
import com.krish.spendsense.domain.LimitAlert
import com.krish.spendsense.ui.Mood
import com.krish.spendsense.ui.SpendStyle
import com.krish.spendsense.ui.coinRes

/**
 * All local notifications. Payment and limit notifications go to a channel picked by today's spend
 * (each with its own sound, see [SpendStyle.channel]); summaries have their own channel.
 */
class Notifier(private val context: Context) {
    companion object {
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

    /**
     * A channel's sound is fixed once created, so each sound has its own channel. Channels from
     * earlier versions (one shared sound) are deleted.
     */
    fun createChannels() {
        val m = context.getSystemService(NotificationManager::class.java) ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        fun channel(id: String, name: String, importance: Int, sound: Int?) = NotificationChannel(id, name, importance).apply {
            if (sound != null) setSound(Uri.parse("android.resource://${context.packageName}/$sound"), attrs)
        }
        SpendStyle.OLD_CHANNELS.forEach(m::deleteNotificationChannel)
        m.createNotificationChannels(
            listOf(
                channel(SpendStyle.CH_CALM, "Payments · on track", NotificationManager.IMPORTANCE_DEFAULT, R.raw.coin_clink),
                channel(SpendStyle.CH_WARNING, "Payments · near limit", NotificationManager.IMPORTANCE_HIGH, R.raw.coin_kaching),
                channel(SpendStyle.CH_OVER, "Payments · over limit", NotificationManager.IMPORTANCE_HIGH, R.raw.coin_spill),
                channel(SpendStyle.CH_SUMMARY, "Summaries", NotificationManager.IMPORTANCE_DEFAULT, R.raw.soft_chime),
                channel(SpendStyle.CH_PLAIN, "Payments (system sound)", NotificationManager.IMPORTANCE_DEFAULT, null),
                channel(SpendStyle.CH_PLAIN_ALERT, "Limits (system sound)", NotificationManager.IMPORTANCE_HIGH, null),
                channel(SpendStyle.CH_SUMMARY_PLAIN, "Summaries (system sound)", NotificationManager.IMPORTANCE_DEFAULT, null),
            ),
        )
    }

    private val night
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val coinCache = HashMap<Mood, Bitmap>()

    /** The mood coin rendered for the notification large icon (vectors can't be used there directly). */
    private fun coinBitmap(mood: Mood): Bitmap? = synchronized(coinCache) {
        coinCache[mood] ?: ContextCompat.getDrawable(context, coinRes(mood))?.toBitmap(192, 192)?.also { coinCache[mood] = it }
    }

    /**
     * Collapsed and expanded content views. Background shows today's spend ÷ daily limit;
     * text and icon switch between black and white for WCAG AA contrast.
     */
    fun spendViews(pct: Int, title: String, text: String, dark: Boolean = night): Pair<RemoteViews, RemoteViews> {
        val surface = if (dark) SpendStyle.SURFACE_DARK else SpendStyle.SURFACE_LIGHT
        val bg = SpendStyle.background(pct, surface)
        val fg = SpendStyle.contentColor(bg)
        fun RemoteViews.paint() = apply {
            setInt(R.id.notif_root, "setBackgroundColor", bg)
            setTextViewText(R.id.notif_title, title)
            setTextColor(R.id.notif_title, fg)
            setTextViewText(R.id.notif_text, text)
            setTextColor(R.id.notif_text, fg)
        }
        val collapsed = RemoteViews(context.packageName, R.layout.notif_spend_collapsed).paint()
        val expanded = RemoteViews(context.packageName, R.layout.notif_spend_expanded).paint().apply {
            setTextViewText(R.id.notif_detail, "$pct% of today's limit used")
            setTextColor(R.id.notif_detail, fg)
            setInt(R.id.notif_icon, "setColorFilter", fg)
            setProgressBar(R.id.notif_progress, 100, pct.coerceIn(0, 100), false)
            if (Build.VERSION.SDK_INT >= 31) setColorStateList(R.id.notif_progress, "setProgressTintList", ColorStateList.valueOf(fg))
        }
        return collapsed to expanded
    }

    /** Payment / limit notification styled by today's spend, on the channel whose sound matches it. */
    private fun spendBuilder(pct: Int, sounds: Boolean, title: String, text: String): NotificationCompat.Builder {
        val dark = night
        val (collapsed, expanded) = spendViews(pct, title, text, dark)
        return base(SpendStyle.channel(pct, sounds))
            .setColor(SpendStyle.accent(pct, if (dark) SpendStyle.SURFACE_DARK else SpendStyle.SURFACE_LIGHT))
            .setLargeIcon(coinBitmap(SpendStyle.mood(pct)))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
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
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
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
    fun postTxn(t: Txn, text: String, suggestions: List<Category>, pct: Int, sounds: Boolean, silent: Boolean = false) {
        val id = txnNotifId(t.id)
        val b = spendBuilder(pct, sounds, text.substringBefore(" · "), text.substringAfter(" · ", ""))
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

    fun postLimit(a: LimitAlert, pct: Int, sounds: Boolean, idOffset: Int = 0) =
        post(ID_LIMIT + (a.keys.last().hashCode() and 0x7f) + idOffset, spendBuilder(pct, sounds, a.title, a.text)
            .setContentIntent(openApp(ID_LIMIT) { putExtra(EXTRA_OPEN, OPEN_HOME) }))

    fun postLarge(t: Txn, text: String, pct: Int, sounds: Boolean) =
        post(ID_LIMIT + 200 + txnNotifId(t.id) % 100, spendBuilder(pct, sounds, "Large payment", text)
            .setContentIntent(openApp(txnNotifId(t.id)) { putExtra(EXTRA_TXN_ID, t.id) }))

    fun postSummary(kind: Int, title: String, text: String, openInsights: Boolean, sounds: Boolean) =
        post(ID_SUMMARY + kind, base(SpendStyle.summaryChannel(sounds)).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ID_SUMMARY + kind) { putExtra(EXTRA_OPEN, if (openInsights) OPEN_INSIGHTS else OPEN_HOME) }))

    fun newAccount(a: Account, sounds: Boolean) =
        post(ID_ACCOUNT + (a.id % 100).toInt(), base(SpendStyle.channel(0, sounds)).setContentTitle("New account found: ${a.bank} ••${a.last4}")
            .setContentText("Give it a name?")
            .setContentIntent(openApp(ID_ACCOUNT) { putExtra(EXTRA_OPEN, OPEN_ACCOUNTS) }))

    fun simChanged(sounds: Boolean) =
        post(ID_SIM, base(SpendStyle.channel(0, sounds)).setContentTitle("New SIM detected").setContentText("Open SpendSense to keep your accounts linked")
            .setContentIntent(openApp(ID_SIM) { putExtra(EXTRA_OPEN, OPEN_HOME) }))

    /** "HDFC ••1234 now on SIM 2" */
    fun toast(text: String) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}

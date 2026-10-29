package com.krish.spendsense.ingest

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Looper
import android.provider.Telephony
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.krish.spendsense.MainActivity
import com.krish.spendsense.PipelineLog
import com.krish.spendsense.R
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.parse.ParseUtils
import com.krish.spendsense.ui.SpendStyle
import com.krish.spendsense.ui.TestApp
import com.krish.spendsense.widget.SpendWidget
import com.krish.spendsense.widget.SpendWidgetReceiver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * SMS -> parse -> persist -> today's spend -> daily limit -> alert -> notification -> widget, with the
 * real container (in-memory DB) and no Activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class PipelineTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val c get() = app.container
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private var ref = 600_000_000_000L

    @Before
    fun setUp() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        c.repository.seedIfEmpty()
        c.settings.setDailyLimit(100_000) // ₹1,000
        c.settings.setWarnPct(80)
        c.settings.setNotifyEachTxn(true)
        c.settings.clearFiredAlerts()
    }

    /** An HDFC "Sent" SMS dated today (IST) with a fresh RRN. */
    private fun hdfcSms(rupees: Int): String {
        val d = DateTimeFormatter.ofPattern("dd/MM/yy").format(LocalDate.now(ParseUtils.IST))
        return "Sent Rs.$rupees.00\nFrom HDFC Bank A/C *9924\nTo RAVI TEA STALL\nOn $d\nRef ${ref++}\nNot You?"
    }

    private fun ingest(rupees: Int, alert: Boolean = true) = runBlocking {
        val now = System.currentTimeMillis()
        c.ingestor.onSms("VM-HDFCBK-S", hdfcSms(rupees), now + ref, now, null, alert)
    }

    private fun titles() = shadowOf(nm).allNotifications.map { it.extras.getCharSequence("android.title").toString() }
    private fun limitTitles() = titles().filter { "limit" in it.lowercase() }
    private fun log() = PipelineLog.read(app).orEmpty()

    @Test
    fun smsBroadcastWithAppClosedIsSavedAndNotifies() {
        val body = hdfcSms(250)
        val pdu = SmsPdu.deliver("VM-HDFCBK", body, ZonedDateTime.now(ParseUtils.IST))
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            .setPackage(app.packageName)
            .putExtra("pdus", arrayOf<Any>(pdu))
            .putExtra("format", "3gpp")
        app.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
        val deadline = System.currentTimeMillis() + 10_000
        // The receiver works on a background coroutine: wait for its last step (the log line follows the post).
        while (System.currentTimeMillis() < deadline && !log().contains("payment alert")) Thread.sleep(50)

        val today = runBlocking { c.repository.dayTxns(LocalDate.now(ParseUtils.IST)) }
        assertEquals(25_000L, today.single().amountPaise)
        // The first SMS from this account also posts "New account found"; pick the payment alert.
        val n = shadowOf(nm).allNotifications.single { it.extras.getCharSequence("android.title").toString().startsWith("₹250") }
        assertEquals(SpendStyle.CH_CALM, n.channelId)
        assertTrue(titles().toString(), titles().any { it.startsWith("New account found: HDFC") })
        assertTrue(log(), log().contains("receiver: SMS broadcast received"))
        assertTrue(log(), log().contains("saved"))
        assertTrue(log(), log().contains("payment alert posted"))
    }

    @Test
    fun usesConfiguredDailyLimitAndFiresEachThresholdOnce() {
        ingest(500) // 50%
        assertTrue(limitTitles().isEmpty())
        ingest(350) // 85% -> warning
        assertEquals(listOf("80% of today's limit used"), limitTitles())
        ingest(100) // 95%: same level, no repeat
        assertEquals(1, limitTitles().size)
        ingest(200) // 115% -> limit reached, a new crossing
        // The 100% alert may reuse the warning's notification slot; it must be showing now.
        assertTrue(limitTitles().toString(), "Daily limit reached" in limitTitles())
        assertTrue(log(), log().contains("of the ₹1,000 daily limit"))
        assertTrue(log(), log().contains("suppressed: already sent today"))
    }

    @Test
    fun changedLimitIsUsedImmediately() = runBlocking {
        c.settings.setDailyLimit(50_000) // ₹500
        ingest(450) // 90% of the new limit
        assertEquals(listOf("80% of today's limit used"), limitTitles())
        assertTrue(log().contains("of the ₹500 daily limit"))
    }

    @Test
    fun duplicateSmsCreatesNoSecondTransactionOrAlert() = runBlocking {
        val now = System.currentTimeMillis()
        val body = hdfcSms(900)
        assertTrue(c.ingestor.onSms("VM-HDFCBK-S", body, now, now, null, alert = true))
        val before = shadowOf(nm).allNotifications.size
        assertFalse(c.ingestor.onSms("VM-HDFCBK-S", body, now, now, null, alert = true))
        assertEquals(1, c.repository.dayTxns(LocalDate.now(ParseUtils.IST)).size)
        assertEquals(before, shadowOf(nm).allNotifications.size)
        assertTrue(log().contains("already processed"))
    }

    @Test
    fun catchUpScanStillChecksLimitsButSkipsOldPaymentAlert() {
        ingest(1200, alert = false) // e.g. found by the scanner hours later
        assertEquals(listOf("Daily limit reached"), limitTitles())
        assertTrue(titles().none { it.startsWith("₹1,200") })
        assertTrue(log().contains("per-payment alert skipped"))
    }

    @Test
    fun concurrentIngestionsSendTheThresholdAlertOnce() = runBlocking {
        val now = System.currentTimeMillis()
        val bodies = (1..4).map { hdfcSms(230) }
        val jobs = bodies.mapIndexed { i, body -> async(kotlinx.coroutines.Dispatchers.Default) { c.ingestor.onSms("VM-HDFCBK-S", body, now + i, now, null, alert = true) } }
        jobs.awaitAll()
        // ₹920 = 92%: exactly one warning however the four ingestions interleave.
        assertEquals(listOf("80% of today's limit used"), limitTitles())
    }

    @Test
    fun deniedPermissionIsReportedNotSilent() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ingest(300)
        assertEquals(1, runBlocking { c.repository.dayTxns(LocalDate.now(ParseUtils.IST)) }.size)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertTrue(log(), log().contains("NOT posted: notification permission denied"))
    }

    @Test
    fun disabledChannelIsReported() {
        nm.deleteNotificationChannel(SpendStyle.CH_CALM)
        nm.createNotificationChannel(NotificationChannel(SpendStyle.CH_CALM, "Payments · on track", NotificationManager.IMPORTANCE_NONE))
        ingest(300)
        assertTrue(log(), log().contains("NOT posted: this notification category is turned off"))
    }

    @Test
    fun widgetFollowsTransactionsLimitsAndTapOpensApp() {
        val shadowMgr = shadowOf(AppWidgetManager.getInstance(app))
        val id = shadowMgr.createWidget(SpendWidgetReceiver::class.java, R.layout.widget_spend)
        ingest(600) // 60% of ₹1,000 -> approaching
        // Placing the widget also queued its own background refresh; refreshes are serialised, so a
        // final one here must still show the newest state.
        runBlocking { SpendWidget.refresh(app) }
        var view = shadowMgr.getViewFor(id)
        assertEquals("₹600", view.findViewById<TextView>(R.id.widget_amount).text.toString())
        assertEquals("of ₹1,000 · 60% used", view.findViewById<TextView>(R.id.widget_detail).text.toString())
        assertEquals(60, view.findViewById<ProgressBar>(R.id.widget_progress).progress)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_coin).visibility)
        assertNotNull(view.findViewById<android.widget.ImageView>(R.id.widget_coin).drawable)
        assertEquals(R.drawable.widget_bg_approaching, shadowOf(view.background).createdFromResId)

        // Limit lowered: the widget turns red on the next refresh.
        runBlocking {
            c.settings.setDailyLimit(50_000)
            SpendWidget.refresh(app)
        }
        view = shadowMgr.getViewFor(id)
        assertEquals("Over by ₹100", view.findViewById<TextView>(R.id.widget_status).text.toString())
        assertEquals(R.drawable.widget_bg_over, shadowOf(view.background).createdFromResId)

        view.performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component!!.className)
        assertEquals(Notifier.OPEN_HOME, started.getStringExtra(Notifier.EXTRA_OPEN))
    }

    @Test
    fun widgetStartsANewDayAtZero() = runBlocking {
        val yesterday = LocalDate.now(ParseUtils.IST).minusDays(1)
        c.repository.addManual(90_000, com.krish.spendsense.parse.Direction.DEBIT, "X", null, null, Dates.startOf(yesterday) + 3_600_000, null)
        val state = SpendWidget.currentState(app)
        assertEquals(0L, state.spentPaise)
        assertNotNull(SpendWidget.render(app, state))
    }
}

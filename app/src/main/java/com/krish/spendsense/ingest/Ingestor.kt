package com.krish.spendsense.ingest

import com.krish.spendsense.data.IngestResult
import com.krish.spendsense.data.Repository
import com.krish.spendsense.data.SettingsStore
import com.krish.spendsense.data.Txn
import com.krish.spendsense.data.TxnMerge
import com.krish.spendsense.data.UnparsedMessage
import com.krish.spendsense.domain.AlertEngine
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.domain.Totals
import com.krish.spendsense.parse.AppNotifParser
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.SmsOutcome
import com.krish.spendsense.parse.SmsParser
import com.krish.spendsense.parse.Source
import com.krish.spendsense.ui.SpendStyle
import java.time.YearMonth

/** Single entry point for SMS and notifications, shared by receiver, listener, scanner and backfill. */
class Ingestor(
    private val repo: Repository,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val sims: SimDetector,
) {
    /**
     * [sentAt] is the SMS service-centre time (same for receiver and inbox `date_sent`), used in
     * the raw hash; [receivedAt] drives the timestamp rules. Non-bank SMS are discarded unstored.
     */
    suspend fun onSms(sender: String, body: String, sentAt: Long, receivedAt: Long, subId: Int?, alert: Boolean) {
        val slot = sims.slotFor(subId)
        if (!repo.isSimTracked(slot)) return
        val hash = TxnMerge.rawHash(sender, body, sentAt)
        when (val out = SmsParser.parse(sender, body, receivedAt, slot, repo.senderKeyMap())) {
            SmsOutcome.NotBankSender, is SmsOutcome.Filtered -> Unit
            is SmsOutcome.Unparsed ->
                repo.saveUnparsed(hash, UnparsedMessage(sender = sender, body = body, timestamp = receivedAt, simSlot = slot, source = Source.SMS, bank = out.bank))
            is SmsOutcome.Parsed -> handle(repo.ingest(out.txn, hash, out.senderKey), alert)
        }
    }

    /** Only GPay / Paytm reach here; failures are kept only in Debug capture mode. */
    suspend fun onNotification(pkg: String, title: String?, text: String?, postedAt: Long) {
        val parser = AppNotifParser.forPackage(pkg) ?: return
        val content = "${title.orEmpty()}\n${text.orEmpty()}"
        val hash = TxnMerge.rawHash(pkg, content, postedAt)
        val parsed = parser.parse(title, text, postedAt)
        if (parsed == null) {
            if (settings.get().debugCapture) {
                repo.saveUnparsed(hash, UnparsedMessage(sender = pkg, body = content.trim(), timestamp = postedAt, source = Source.NOTIF))
            }
            return
        }
        handle(repo.ingest(parsed, hash, null), alert = true)
    }

    private suspend fun handle(result: IngestResult, alert: Boolean) {
        val saved = result as? IngestResult.Saved ?: return
        saved.simMoved?.let { notifier.toast("${it.bank} ••${it.last4} now on SIM ${saved.txn.simSlot ?: "?"}") }
        if (!alert) return
        saved.newAccount?.let { notifier.newAccount(it, settings.get().moneySounds) }
        val t = saved.txn
        if (t.direction != Direction.DEBIT || t.excludedReason != null) return
        // A merge only refreshes the notification already shown for this payment.
        if (!saved.inserted) {
            postTxnNotification(t, silent = true)
            return
        }
        postTxnNotification(t, silent = false)
        checkLimits(t)
    }

    private suspend fun postTxnNotification(t: Txn, silent: Boolean) {
        val s = settings.get()
        if (!s.notifyEachTxn) return
        val today = repo.dayTxns(Dates.date(t.timestamp))
        val spent = Totals.spend(today, repo.excludedAccountIds())
        notifier.postTxn(
            t, AlertEngine.txnText(t, spent, s.dailyLimitPaise), repo.suggestedCategories(3),
            SpendStyle.percent(spent, s.dailyLimitPaise), s.moneySounds, silent,
        )
    }

    private suspend fun checkLimits(t: Txn) {
        val s = settings.get()
        val day = Dates.date(t.timestamp)
        if (day != Dates.date(System.currentTimeMillis())) return
        val excluded = repo.excludedAccountIds()
        val today = repo.dayTxns(day)
        val fired = mutableListOf<String>()
        // Every limit notification is coloured by today's spend against the daily limit.
        val pct = SpendStyle.percent(Totals.spend(today, excluded), s.dailyLimitPaise)
        AlertEngine.dailyLimit(Totals.spend(today, excluded), s.dailyLimitPaise, s.warnPct, s.firedAlerts, day)?.let {
            notifier.postLimit(it, pct, s.moneySounds); fired += it.keys
        }
        t.accountId?.let { id -> repo.allAccounts().firstOrNull { it.id == id } }?.let { a ->
            val limit = a.dailyLimitPaise ?: return@let
            AlertEngine.dailyLimit(Totals.spend(today.filter { it.accountId == a.id }), limit, s.warnPct, s.firedAlerts, day, "acct${a.id}", a.label)
                ?.let { notifier.postLimit(it, pct, s.moneySounds, 1); fired += it.keys }
        }
        repo.allCategories().firstOrNull { it.id == t.categoryId && it.monthlyBudgetPaise != null }?.let { c ->
            val month = YearMonth.from(day)
            val rows = repo.range(Dates.startOf(month.atDay(1)), Dates.endOf(month.atEndOfMonth()))
            val spent = Totals.byCategory(rows, excluded)[c.id] ?: 0
            AlertEngine.categoryBudget(c, spent, s.warnPct, s.firedAlerts, month)?.let { notifier.postLimit(it, pct, s.moneySounds, 2); fired += it.keys }
        }
        if (AlertEngine.isLarge(t, s.largeTxnPaise)) notifier.postLarge(t, AlertEngine.txnText(t, Totals.spend(today, excluded), s.dailyLimitPaise), pct, s.moneySounds)
        if (fired.isNotEmpty()) settings.markFired(fired)
    }
}

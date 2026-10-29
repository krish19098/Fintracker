package com.krish.spendsense.ingest

import com.krish.spendsense.data.IngestResult
import com.krish.spendsense.data.Repository
import com.krish.spendsense.data.SettingsStore
import com.krish.spendsense.data.Txn
import com.krish.spendsense.data.TxnMerge
import com.krish.spendsense.data.UnparsedMessage
import com.krish.spendsense.domain.AlertEngine
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.domain.Money
import com.krish.spendsense.domain.SpendState
import com.krish.spendsense.domain.Totals
import com.krish.spendsense.parse.AppNotifParser
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.MessageFilter
import com.krish.spendsense.parse.SmsOutcome
import com.krish.spendsense.parse.SmsParser
import com.krish.spendsense.parse.Source
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.YearMonth

/**
 * Single entry point for SMS and notifications, shared by receiver, listener, scanner and backfill.
 * [log] receives pipeline outcomes (no message text or account digits); [onTodayChanged] runs after
 * today's spending changed (refreshes the widget).
 */
class Ingestor(
    private val repo: Repository,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val sims: SimDetector,
    private val log: (String) -> Unit = {},
    private val onTodayChanged: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Limit checks read, post and record "already sent" keys; two ingestions at once must not interleave. */
    private val alertLock = Mutex()

    /**
     * [sentAt] is the SMS service-centre time (same for receiver and inbox `date_sent`), used in
     * the raw hash; [receivedAt] drives the timestamp rules. Non-bank SMS are discarded unstored.
     * Returns true when this call stored something new (a transaction or an unread message).
     */
    suspend fun onSms(
        sender: String, body: String, sentAt: Long, receivedAt: Long, subId: Int?, alert: Boolean, via: String = "receiver",
    ): Boolean {
        val slot = sims.slotFor(subId)
        if (!repo.isSimTracked(slot)) {
            log("$via: SMS on SIM $slot ignored (tracking off for that SIM)")
            return false
        }
        val hash = TxnMerge.rawHash(sender, body, sentAt)
        return when (val out = SmsParser.parse(sender, body, receivedAt, slot, repo.senderKeyMap())) {
            SmsOutcome.NotBankSender -> false
            is SmsOutcome.Filtered -> {
                log("$via: ${MessageFilter.senderKey(sender)} SMS skipped (${out.reason.name.lowercase()})")
                false
            }
            is SmsOutcome.Unparsed -> {
                val saved = repo.saveUnparsed(hash, UnparsedMessage(sender = sender, body = body, timestamp = receivedAt, simSlot = slot, source = Source.SMS, bank = out.bank))
                if (saved) log("$via: ${out.senderKey} SMS not understood -> Review inbox")
                saved
            }
            is SmsOutcome.Parsed -> handle(repo.ingest(out.txn, hash, out.senderKey), alert, "$via: ${out.senderKey}")
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
        handle(repo.ingest(parsed, hash, null), alert = true, via = "app notification (${parsed.app.name})")
    }

    private suspend fun handle(result: IngestResult, alert: Boolean, via: String): Boolean {
        val saved = when (result) {
            IngestResult.Duplicate -> {
                log("$via: already processed, skipped")
                return false
            }
            IngestResult.Paused -> {
                log("$via: account paused (Track this account is off), not captured")
                return true
            }
            is IngestResult.Saved -> result
        }
        val t = saved.txn
        log("$via: transaction #${t.id} ${if (saved.inserted) "saved" else "merged into existing"}")
        saved.simMoved?.let { notifier.toast("${it.bank} ••${it.last4} now on SIM ${saved.txn.simSlot ?: "?"}") }
        val countable = t.direction == Direction.DEBIT && t.excludedReason == null
        if (alert) {
            saved.newAccount?.let { notifier.newAccount(it, settings.get().moneySounds) }
            // A merge only refreshes the notification already shown for this payment.
            if (countable) postTxnNotification(t, silent = !saved.inserted)
        } else if (saved.inserted && countable) {
            log("#${t.id}: per-payment alert skipped (message older than the alert window)")
        }
        // Limits are checked for every new debit of today, also when it was caught up by the scanner,
        // so a missed live broadcast can't hide a threshold crossing.
        val isToday = Dates.date(t.timestamp) == Dates.date(clock())
        if (saved.inserted && countable && isToday) checkLimits(t)
        if (isToday) onTodayChanged()
        return true
    }

    private suspend fun postTxnNotification(t: Txn, silent: Boolean) {
        val s = settings.get()
        if (!s.notifyEachTxn) {
            log("#${t.id}: per-payment alert off in Settings")
            return
        }
        val today = repo.dayTxns(Dates.date(t.timestamp))
        val state = SpendState(Totals.spend(today, repo.excludedAccountIds()), s.dailyLimitPaise, s.warnPct)
        val r = notifier.postTxn(
            t, AlertEngine.txnText(t, state.spentPaise, s.dailyLimitPaise), repo.suggestedCategories(3),
            state.percent, s.moneySounds, silent,
        )
        log("#${t.id}: payment alert ${r.label}${if (silent) " (silent update)" else ""}")
    }

    private suspend fun checkLimits(t: Txn) = alertLock.withLock {
        // Read inside the lock: the "already sent" keys must include whatever the previous check stored.
        val s = settings.get()
        val day = Dates.date(t.timestamp)
        val excluded = repo.excludedAccountIds()
        val today = repo.dayTxns(day)
        val state = SpendState(Totals.spend(today, excluded), s.dailyLimitPaise, s.warnPct)
        log("#${t.id}: today ${state.percent}% of the ${Money.short(s.dailyLimitPaise)} daily limit (Settings > Budgets), warn at ${s.warnPct}%")
        val fired = mutableListOf<String>()
        // Every limit notification is coloured by today's spend against the daily limit.
        val pct = state.percent
        val daily = AlertEngine.dailyLimit(state.spentPaise, s.dailyLimitPaise, s.warnPct, s.firedAlerts, day)
        if (daily != null) {
            log("daily limit ${daily.level}% alert ${notifier.postLimit(daily, pct, s.moneySounds).label}")
            fired += daily.keys
        } else if (state.hasLimit && state.percent >= s.warnPct) {
            log("daily limit alert suppressed: already sent today for this level")
        }
        t.accountId?.let { id -> repo.allAccounts().firstOrNull { it.id == id } }?.let { a ->
            val limit = a.dailyLimitPaise ?: return@let
            AlertEngine.dailyLimit(Totals.spend(today.filter { it.accountId == a.id }), limit, s.warnPct, s.firedAlerts, day, "acct${a.id}", a.label)
                ?.let {
                    log("account limit ${it.level}% alert ${notifier.postLimit(it, pct, s.moneySounds, 1).label}")
                    fired += it.keys
                }
        }
        repo.allCategories().firstOrNull { it.id == t.categoryId && it.monthlyBudgetPaise != null }?.let { c ->
            val month = YearMonth.from(day)
            val rows = repo.range(Dates.startOf(month.atDay(1)), Dates.endOf(month.atEndOfMonth()))
            val spent = Totals.byCategory(rows, excluded)[c.id] ?: 0
            AlertEngine.categoryBudget(c, spent, s.warnPct, s.firedAlerts, month)?.let {
                log("category budget ${it.level}% alert ${notifier.postLimit(it, pct, s.moneySounds, 2).label}")
                fired += it.keys
            }
        }
        if (AlertEngine.isLarge(t, s.largeTxnPaise)) {
            log("#${t.id}: large payment alert ${notifier.postLarge(t, AlertEngine.txnText(t, state.spentPaise, s.dailyLimitPaise), pct, s.moneySounds).label}")
        }
        if (fired.isNotEmpty()) settings.markFired(fired)
    }
}

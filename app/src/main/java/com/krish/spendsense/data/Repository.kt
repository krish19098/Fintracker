package com.krish.spendsense.data

import androidx.room.withTransaction
import com.krish.spendsense.domain.CatKeys
import com.krish.spendsense.domain.Categorizer
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.domain.DefaultCategories
import com.krish.spendsense.domain.Exclusions
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.IncomeKind
import com.krish.spendsense.parse.MessageFilter
import com.krish.spendsense.parse.ParsedTxn
import com.krish.spendsense.parse.Source
import com.krish.spendsense.parse.TxnApp
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

sealed interface IngestResult {
    data object Duplicate : IngestResult
    /** Account paused ("Track this account" off). */
    data object Paused : IngestResult
    data class Saved(
        val txn: Txn,
        val inserted: Boolean,
        val newAccount: Account? = null,
        val simMoved: Account? = null,
    ) : IngestResult
}

/** The single repository. Every multi-step write runs in one Room transaction. */
class Repository(
    private val db: AppDatabase,
    /** Current settings (own VPAs, CC payees); a lambda so tests need no DataStore. */
    private val settings: suspend () -> AppSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val txns = db.txnDao()
    private val accounts = db.accountDao()
    private val sims = db.simDao()
    private val categories = db.categoryDao()
    private val rules = db.ruleDao()
    private val senders = db.senderDao()
    private val unparsed = db.unparsedDao()
    private val hashes = db.hashDao()

    // ---- Observables for the UI ----
    fun observeRange(from: Long, to: Long): Flow<List<Txn>> = txns.observeRange(from, to)
    fun observeTxn(id: Long): Flow<Txn?> = txns.observe(id)
    fun observeNeedsReview(): Flow<List<Txn>> = txns.observeNeedsReview()
    fun observeAccounts(): Flow<List<Account>> = accounts.observeAll()
    fun observeAccount(id: Long): Flow<Account?> = accounts.observe(id)
    fun observeLatestBalance(accountId: Long): Flow<Txn?> = txns.observeLatestBalance(accountId)
    fun observeSims(): Flow<List<SimProfile>> = sims.observeAll()
    fun observeCategories(): Flow<List<Category>> = categories.observeAll()
    fun observePayeeRules(): Flow<List<PayeeRule>> = rules.observePayeeRules()
    fun observeKeywordRules(): Flow<List<KeywordRule>> = rules.observeKeywordRules()
    fun observeSenders(): Flow<List<AccountSender>> = senders.observeAll()
    fun observeUnparsed(): Flow<List<UnparsedMessage>> = unparsed.observeAll()

    suspend fun txn(id: Long) = txns.byId(id)
    suspend fun range(from: Long, to: Long) = txns.range(from, to)
    suspend fun allAccounts() = accounts.all()
    suspend fun allSims() = sims.all()
    suspend fun allCategories() = categories.all()
    suspend fun unparsedById(id: Long) = unparsed.byId(id)

    /** Accounts the user excluded from spending totals. */
    suspend fun excludedAccountIds(): Set<Long> = accounts.all().filterNot { it.includeInTotals }.map { it.id }.toSet()

    /** DLT sender key -> bank, from bank defaults and per-account senders. */
    suspend fun senderKeyMap(): Map<String, String> = senders.all().associate { it.senderKey to it.bank }

    // ---- Seeding ----
    suspend fun seedIfEmpty() = db.withTransaction {
        if (categories.count() > 0) return@withTransaction
        val ids = DefaultCategories.seeds.associate { s ->
            s.key to categories.insert(Category(name = s.name, icon = s.icon, color = s.color, isDefault = true, kind = s.kind, systemKey = s.key))
        }
        rules.insertKeywords(DefaultCategories.keywords.flatMap { (key, words) -> words.map { KeywordRule(keyword = it, categoryId = ids.getValue(key)) } })
        senders.insertAll(MessageFilter.DEFAULT_SENDER_KEYS.map { (key, bank) -> AccountSender(bank = bank, senderKey = key) })
    }

    // ---- Ingestion ----

    /** Marks a source as processed; false if it was already handled (receiver, scanner or backfill). */
    suspend fun markProcessed(rawHash: String): Boolean = hashes.insert(ProcessedHash(rawHash, clock())) != -1L

    suspend fun saveUnparsed(rawHash: String, msg: UnparsedMessage): Boolean = db.withTransaction {
        if (!markProcessed(rawHash)) return@withTransaction false
        unparsed.insert(msg)
        true
    }

    /**
     * Dedup + merge + insert in one transaction: rawHash guard, then upiRef match, then the
     * no-ref fallback; otherwise a new row is categorised, checked for exclusions and paired.
     */
    suspend fun ingest(p: ParsedTxn, rawHash: String, senderKey: String?): IngestResult = db.withTransaction {
        if (!markProcessed(rawHash)) return@withTransaction IngestResult.Duplicate
        val s = settings()
        var newAccount: Account? = null
        var simMoved: Account? = null
        var accountId: Long? = null
        if (p.source == Source.SMS) {
            val profile = p.simSlot?.let { sims.activeInSlot(it) }
            val all = accounts.all()
            when (val m = AccountMatcher.match(p.bank, p.accountTail, senderKey, all, rules.matchRules())) {
                is AccountMatch.Found -> {
                    if (!m.account.tracked) return@withTransaction IngestResult.Paused
                    val (updated, moved) = SimLogic.onAccountSeen(m.account, profile?.id)
                    if (updated != m.account) accounts.update(updated)
                    if (moved) simMoved = updated
                    accountId = updated.id
                }
                is AccountMatch.NotFound -> if (m.creatable) {
                    val a = Account(
                        bank = p.bank, last4 = p.accountTail!!, nickname = "", simProfileId = profile?.id,
                        isPrimary = all.isEmpty(), createdAt = clock(),
                    )
                    val id = accounts.insert(a)
                    senderKey?.let { senders.insert(AccountSender(accountId = id, bank = p.bank, senderKey = it)) }
                    newAccount = a.copy(id = id)
                    accountId = id
                }
                AccountMatch.Ambiguous -> Unit
            }
        }

        var incoming = TxnMerge.toTxn(p, rawHash, accountId, senderKey)
        val byRef = p.upiRef?.let { txns.byRef(it) }
        // The same RRN in the opposite direction is the other leg of a transfer between the user's own
        // accounts, not a duplicate: keep it as its own row (upiRef is unique, so the ref stays on the first leg).
        val refTwin = byRef?.takeIf { it.direction != p.direction }
        if (refTwin != null) incoming = incoming.copy(upiRef = null)
        val existing = byRef?.takeIf { refTwin == null } ?: TxnMerge.findFallback(
            incoming,
            txns.window(p.amountPaise, p.direction, p.timestamp - TxnMerge.FALLBACK_WINDOW_MS, p.timestamp + TxnMerge.FALLBACK_WINDOW_MS),
        )
        if (existing != null) {
            var merged = TxnMerge.merge(existing, incoming)
            if (!merged.categoryUserSet && merged.payeeName != existing.payeeName) {
                val d = categorizer().categorize(merged, p.incomeHint)
                if (d.categoryId != null && !d.needsReview) merged = merged.copy(categoryId = d.categoryId, needsReview = false)
            }
            txns.update(merged)
            return@withTransaction IngestResult.Saved(merged, inserted = false, newAccount = newAccount, simMoved = simMoved)
        }

        val classified = classify(incoming, p.incomeHint, s)
        val inserted = classified.copy(id = txns.insert(classified))
        val saved = refTwin?.let { pairRefTwins(inserted, it) } ?: link(inserted)
        IngestResult.Saved(saved, inserted = true, newAccount = newAccount, simMoved = simMoved)
    }

    private suspend fun categorizer() = Categorizer(
        rules.payeeRules().associate { it.payeeKey to it.categoryId }, rules.keywordRules(), categories.all(),
    )

    private suspend fun classify(t: Txn, hint: IncomeKind?, s: AppSettings): Txn {
        val d = categorizer().categorize(t, hint)
        val reason = Exclusions.autoReason(t, accounts.all().filter { it.isOwn }, s.ownVpas, s.ccPayees)
        return t.copy(categoryId = d.categoryId, needsReview = d.needsReview && reason == null, excludedReason = reason)
    }

    /** Both legs of one RRN on two different accounts: a self-transfer, unless the user decided otherwise. */
    private suspend fun pairRefTwins(t: Txn, twin: Txn): Txn {
        if (t.accountId == null || twin.accountId == null || t.accountId == twin.accountId || t.exclusionUserSet || twin.exclusionUserSet) return link(t)
        txns.update(twin.copy(excludedReason = ExcludedReason.SELF_TRANSFER, linkedTxnId = t.id, needsReview = false))
        return t.copy(excludedReason = ExcludedReason.SELF_TRANSFER, linkedTxnId = twin.id, needsReview = false).also { txns.update(it) }
    }

    /** Self-transfer pairing and refund linking against existing rows. */
    private suspend fun link(t: Txn): Txn {
        if (t.excludedReason != null) return t
        val ownIds = accounts.all().filter { it.isOwn }.map { it.id }.toSet()
        val opposite = if (t.direction == Direction.DEBIT) Direction.CREDIT else Direction.DEBIT
        val window = txns.window(t.amountPaise, opposite, t.timestamp - Exclusions.SELF_TRANSFER_WINDOW_MS, t.timestamp + Exclusions.SELF_TRANSFER_WINDOW_MS)
        Exclusions.findSelfTransferPair(t, window, ownIds)?.let { other ->
            txns.update(other.copy(excludedReason = ExcludedReason.SELF_TRANSFER, linkedTxnId = t.id, needsReview = false))
            return t.copy(excludedReason = ExcludedReason.SELF_TRANSFER, linkedTxnId = other.id, needsReview = false).also { txns.update(it) }
        }
        if (Exclusions.isRefund(t)) {
            val candidates = txns.range(t.timestamp - Exclusions.REFUND_WINDOW_MS, t.timestamp)
            Exclusions.findRefundOriginal(t, candidates)?.let { orig ->
                val refundCat = categories.bySystemKey(CatKeys.income(IncomeKind.REFUND))?.id ?: t.categoryId
                return t.copy(excludedReason = ExcludedReason.REFUND, linkedTxnId = orig.id, categoryId = refundCat, needsReview = false)
                    .also { txns.update(it) }
            }
        }
        return t
    }

    // ---- Transaction edits ----

    suspend fun updateTxn(t: Txn) = txns.update(t)

    /** Sets a category; with [always], learns a PayeeRule and applies it to that payee's other rows. */
    suspend fun setCategory(txnId: Long, categoryId: Long, always: Boolean) = db.withTransaction {
        val t = txns.byId(txnId) ?: return@withTransaction
        txns.update(t.copy(categoryId = categoryId, categoryUserSet = true, needsReview = false))
        val key = Categorizer.payeeKey(t.payeeName, t.payeeVpa)
        if (always && key != null) {
            rules.upsertPayeeRule(PayeeRule(key, categoryId))
            txns.all().filter { it.id != txnId && !it.categoryUserSet && it.direction == t.direction && Categorizer.payeeKey(it.payeeName, it.payeeVpa) == key }
                .forEach { txns.update(it.copy(categoryId = categoryId, needsReview = false)) }
        }
    }

    suspend fun setExcluded(txnId: Long, excluded: Boolean) {
        val t = txns.byId(txnId) ?: return
        txns.update(t.copy(excludedReason = if (excluded) t.excludedReason ?: ExcludedReason.MANUAL else null, exclusionUserSet = true))
    }

    suspend fun addManual(
        amountPaise: Long, direction: Direction, payee: String?, categoryId: Long?, accountId: Long?,
        timestamp: Long, note: String?, upiRef: String? = null,
    ): Long = db.withTransaction {
        val base = Txn(
            rawHash = "manual:${UUID.randomUUID()}", amountPaise = amountPaise, direction = direction, accountId = accountId,
            payeeName = payee?.trim()?.ifEmpty { null }, app = if (upiRef != null) TxnApp.OTHER_UPI else TxnApp.NON_UPI,
            note = note?.trim()?.ifEmpty { null }, timestamp = timestamp, sourceFlags = Source.MANUAL.flag,
            upiRef = upiRef?.takeIf { txns.byRef(it) == null },
        )
        val t = if (categoryId != null) base.copy(categoryId = categoryId, categoryUserSet = true) else classify(base, null, settings())
        link(t.copy(id = txns.insert(t))).id
    }

    suspend fun deleteTxn(id: Long) = db.withTransaction {
        val t = txns.byId(id) ?: return@withTransaction
        txns.unlinkFrom(id)
        txns.delete(t)
    }

    /** Assigns an unassigned row; with [always], creates an AccountMatchRule and applies it to siblings. */
    suspend fun assignAccount(txnId: Long, accountId: Long, always: Boolean) = db.withTransaction {
        val t = txns.byId(txnId) ?: return@withTransaction
        txns.update(t.copy(accountId = accountId))
        val key = t.senderKey
        val tail = t.accountTail
        if (always && key != null && tail != null) {
            rules.upsertMatchRule(AccountMatchRule(senderKey = key, tail = tail, accountId = accountId))
            txns.unassignedFor(key, tail).forEach { txns.update(it.copy(accountId = accountId)) }
        }
    }

    suspend fun suggestedCategories(n: Int): List<Category> {
        val all = categories.all().associateBy { it.id }
        val since = clock() - 90L * 24 * 3600_000
        val top = txns.topCategories(since, n + 2).mapNotNull { all[it.categoryId] }
            .filter { it.systemKey != CatKeys.UNCATEGORISED }
        val fallback = listOf(CatKeys.FOOD, CatKeys.GROCERIES, CatKeys.TRANSPORT, CatKeys.SHOPPING).mapNotNull { k -> all.values.firstOrNull { it.systemKey == k } }
        return (top + fallback).distinctBy { it.id }.take(n)
    }

    // ---- Review inbox ----
    suspend fun dismissUnparsed(id: Long) = unparsed.delete(id)

    /** Creates the transaction from a reviewed message and deletes its raw text. */
    suspend fun resolveUnparsed(id: Long, amountPaise: Long, direction: Direction, payee: String?, categoryId: Long?, accountId: Long?, timestamp: Long, upiRef: String?) =
        db.withTransaction {
            addManual(amountPaise, direction, payee, categoryId, accountId, timestamp, null, upiRef)
            unparsed.delete(id)
        }

    // ---- Accounts ----
    suspend fun updateAccount(a: Account) = accounts.update(a)
    suspend fun setPrimary(id: Long) = accounts.setPrimary(id)

    suspend fun createAccount(bank: String, last4: String, nickname: String): Long =
        accounts.insert(Account(bank = bank.uppercase(), last4 = last4, nickname = nickname, named = true, createdAt = clock()))

    /** Moves every transaction, sender and match rule into [keepId], then removes [dropId]. */
    suspend fun mergeAccounts(keepId: Long, dropId: Long) = db.withTransaction {
        if (keepId == dropId) return@withTransaction
        val drop = accounts.byId(dropId) ?: return@withTransaction
        txns.moveAccount(dropId, keepId)
        senders.moveSenders(dropId, keepId)
        senders.deleteFor(dropId)
        rules.moveMatchRules(dropId, keepId)
        accounts.delete(drop)
    }

    suspend fun deleteAccount(id: Long, deleteTransactions: Boolean) = db.withTransaction {
        val a = accounts.byId(id) ?: return@withTransaction
        if (deleteTransactions) {
            // Refunds / transfer legs elsewhere must not stay linked to rows that are about to go.
            txns.unlinkFromAccount(id)
            txns.deleteByAccount(id)
        } else {
            txns.unassignAccount(id)
        }
        senders.deleteFor(id)
        rules.deleteMatchRulesFor(id)
        accounts.delete(a)
    }

    suspend fun addSender(bank: String, senderKey: String, accountId: Long?) =
        senders.insert(AccountSender(accountId = accountId, bank = bank.trim().uppercase(), senderKey = MessageFilter.senderKey(senderKey)))

    suspend fun deleteSender(s: AccountSender) = senders.delete(s)

    // ---- SIMs ----
    suspend fun updateSim(s: SimProfile) = sims.update(s)

    /** Applies silent changes (new slots, last seen) and returns swaps that need the user's answer. */
    suspend fun refreshSims(current: List<DetectedSim>): List<SimChange.Swapped> = db.withTransaction {
        val stored = sims.all()
        val changes = SimLogic.detectChanges(stored, current)
        changes.filterIsInstance<SimChange.Added>().forEach { sims.insert(SimLogic.newProfile(it.sim, clock())) }
        current.forEach { c ->
            stored.firstOrNull { it.subscriptionId == c.subscriptionId && it.active }?.let {
                sims.update(it.copy(slot = c.slot, carrier = c.carrier ?: it.carrier, phoneNumber = it.phoneNumber ?: c.number, lastSeenAt = clock()))
            }
        }
        changes.filterIsInstance<SimChange.Swapped>()
    }

    /** Never loses history: either remap in place, or keep the old profile inactive. */
    suspend fun resolveSwap(change: SimChange.Swapped, keepLinked: Boolean) = db.withTransaction {
        if (keepLinked) {
            sims.update(SimLogic.keepLinked(change.old, change.sim, clock()))
        } else {
            val (old, fresh) = SimLogic.setupFresh(change.old, change.sim, clock())
            sims.update(old)
            sims.insert(fresh)
        }
    }

    suspend fun isSimTracked(slot: Int?): Boolean = slot == null || sims.activeInSlot(slot)?.trackSms != false

    // ---- Categories & rules ----
    suspend fun addCategory(name: String, icon: String, color: Long, kind: CategoryKind) =
        categories.insert(Category(name = name, icon = icon, color = color, kind = kind))

    suspend fun updateCategory(c: Category) = categories.update(c)

    /** Deleting a category moves its transactions to Others (or Other Income). */
    suspend fun deleteCategory(c: Category) = db.withTransaction {
        if (c.systemKey == CatKeys.OTHERS || c.systemKey == CatKeys.UNCATEGORISED || c.systemKey == CatKeys.income(IncomeKind.OTHER_INCOME)) return@withTransaction
        val target = categories.bySystemKey(if (c.kind == CategoryKind.INCOME) CatKeys.income(IncomeKind.OTHER_INCOME) else CatKeys.OTHERS) ?: return@withTransaction
        txns.moveCategory(c.id, target.id)
        rules.deletePayeeRulesFor(c.id)
        rules.deleteKeywordsFor(c.id)
        categories.delete(c)
    }

    suspend fun setCategoryBudget(categoryId: Long, paise: Long?) = db.withTransaction {
        categories.all().firstOrNull { it.id == categoryId }?.let { categories.update(it.copy(monthlyBudgetPaise = paise)) }
    }

    suspend fun deletePayeeRule(r: PayeeRule) = rules.deletePayeeRule(r)
    suspend fun addKeyword(keyword: String, categoryId: Long) = rules.insertKeyword(KeywordRule(keyword = keyword.trim().lowercase(), categoryId = categoryId))
    suspend fun deleteKeyword(r: KeywordRule) = rules.deleteKeyword(r)

    // ---- Queries for alerts ----
    suspend fun dayTxns(day: LocalDate) = txns.range(Dates.startOf(day), Dates.endOf(day))

    // ---- Backup ----
    suspend fun snapshot() = BackupData(
        sims = sims.all(), accounts = accounts.all(), senders = senders.all(), matchRules = rules.matchRules(),
        txns = txns.all(), categories = categories.all(), payeeRules = rules.payeeRules(), keywordRules = rules.keywordRules(),
        unparsed = unparsed.all(), hashes = hashes.all(),
    )

    suspend fun restore(b: BackupData) = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        listOf("txn", "account_sender", "account_match_rule", "account", "sim_profile", "payee_rule", "keyword_rule", "category", "unparsed_message", "processed_hash")
            .forEach { sql.execSQL("DELETE FROM $it") }
        sims.insertAll(b.sims)
        accounts.insertAll(b.accounts)
        senders.insertAll(b.senders)
        rules.insertMatchRules(b.matchRules)
        categories.insertAll(b.categories)
        txns.insertAll(b.txns)
        rules.insertPayeeRules(b.payeeRules)
        rules.insertKeywords(b.keywordRules)
        unparsed.insertAll(b.unparsed)
        hashes.insertAll(b.hashes)
    }
}

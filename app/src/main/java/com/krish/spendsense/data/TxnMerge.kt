package com.krish.spendsense.data

import com.krish.spendsense.parse.ParsedTxn
import com.krish.spendsense.parse.Source
import com.krish.spendsense.parse.TxnApp
import java.security.MessageDigest
import kotlin.math.abs

/** Pure dedup / merge rules; the Repository runs them inside one Room transaction. */
object TxnMerge {
    const val FALLBACK_WINDOW_MS = 3 * 60_000L

    fun rawHash(sender: String, body: String, timestamp: Long): String =
        MessageDigest.getInstance("SHA-256").digest("$sender\u0000$body\u0000$timestamp".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Builds a new row. An alphabetic counterparty tail (e.g. IPAY) is a merchant handle, used as the payee name. */
    fun toTxn(p: ParsedTxn, rawHash: String, accountId: Long?, senderKey: String?): Txn = Txn(
        upiRef = p.upiRef,
        rawHash = rawHash,
        amountPaise = p.amountPaise,
        direction = p.direction,
        accountId = accountId,
        payeeName = p.payeeName ?: p.counterpartyTail?.takeIf { !p.counterpartyIsAccount },
        payeeVpa = p.payeeVpa,
        app = p.app,
        note = p.note,
        timestamp = p.timestamp,
        sourceFlags = p.source.flag,
        simSlot = p.simSlot,
        balanceAfterPaise = p.balanceAfterPaise,
        bank = p.bank.takeIf { p.source == Source.SMS },
        accountTail = p.accountTail,
        senderKey = senderKey,
        counterpartyTail = p.counterpartyTail,
        upiType = p.upiType,
    )

    /**
     * Fallback when either side has no ref: same amount and direction, within ±3 minutes,
     * compatible account, and from a different source (two SMS never merge with each other
     * this way, so two genuine identical payments stay separate).
     */
    fun findFallback(incoming: Txn, candidates: List<Txn>): Txn? = candidates.filter {
        it.id != incoming.id &&
            it.amountPaise == incoming.amountPaise &&
            it.direction == incoming.direction &&
            abs(it.timestamp - incoming.timestamp) <= FALLBACK_WINDOW_MS &&
            (it.upiRef == null || incoming.upiRef == null) &&
            (it.accountId == null || incoming.accountId == null || it.accountId == incoming.accountId) &&
            (it.sourceFlags and incoming.sourceFlags) == 0
    }.minByOrNull { abs(it.timestamp - incoming.timestamp) }

    private fun isAppSpecific(app: TxnApp) = app == TxnApp.GPAY || app == TxnApp.PAYTM

    /**
     * SMS keeps account, balance and SIM; the notification keeps payee name and app;
     * source flags are OR-ed. User edits on the existing row are preserved.
     */
    fun merge(existing: Txn, incoming: Txn): Txn {
        val sms = listOf(incoming, existing).firstOrNull { it.hasSource(Source.SMS) }
        val notif = listOf(incoming, existing).firstOrNull { it.hasSource(Source.NOTIF) }
        return existing.copy(
            upiRef = existing.upiRef ?: incoming.upiRef,
            accountId = sms?.accountId ?: existing.accountId ?: incoming.accountId,
            balanceAfterPaise = sms?.balanceAfterPaise ?: existing.balanceAfterPaise ?: incoming.balanceAfterPaise,
            simSlot = sms?.simSlot ?: existing.simSlot ?: incoming.simSlot,
            payeeName = notif?.payeeName ?: existing.payeeName ?: incoming.payeeName,
            payeeVpa = existing.payeeVpa ?: incoming.payeeVpa,
            app = listOf(existing.app, incoming.app).firstOrNull(::isAppSpecific)
                ?: if (existing.app == TxnApp.NON_UPI) incoming.app else existing.app,
            note = existing.note ?: incoming.note,
            timestamp = sms?.timestamp ?: existing.timestamp,
            sourceFlags = existing.sourceFlags or incoming.sourceFlags,
            bank = existing.bank ?: incoming.bank,
            accountTail = existing.accountTail ?: incoming.accountTail,
            senderKey = existing.senderKey ?: incoming.senderKey,
            counterpartyTail = existing.counterpartyTail ?: incoming.counterpartyTail,
            upiType = existing.upiType ?: incoming.upiType,
        )
    }
}

sealed interface AccountMatch {
    data class Found(val account: Account) : AccountMatch
    /** No account; [creatable] when the tail is long enough to auto-create one. */
    data class NotFound(val creatable: Boolean) : AccountMatch
    data object Ambiguous : AccountMatch
}

object AccountMatcher {
    /** Match rule first, then exact tail, then a unique suffix match among the bank's accounts. */
    fun match(bank: String, tail: String?, senderKey: String?, accounts: List<Account>, rules: List<AccountMatchRule>): AccountMatch {
        if (tail.isNullOrEmpty()) return AccountMatch.NotFound(false)
        val byId = accounts.associateBy { it.id }
        rules.firstOrNull { it.senderKey == senderKey && it.tail == tail }?.let { r ->
            byId[r.accountId]?.let { return AccountMatch.Found(it) }
        }
        val sameBank = accounts.filter { it.bank == bank }
        sameBank.firstOrNull { it.last4 == tail }?.let { return AccountMatch.Found(it) }
        val suffix = sameBank.filter { it.last4.endsWith(tail) || tail.endsWith(it.last4) }
        return when (suffix.size) {
            1 -> AccountMatch.Found(suffix.single())
            0 -> AccountMatch.NotFound(creatable = tail.length >= 4)
            else -> AccountMatch.Ambiguous
        }
    }
}

/** A SIM currently reported by SubscriptionManager. */
data class DetectedSim(val subscriptionId: Int, val slot: Int, val carrier: String?, val number: String?)

sealed interface SimChange {
    data class Added(val sim: DetectedSim) : SimChange
    data class Swapped(val old: SimProfile, val sim: DetectedSim) : SimChange
}

object SimLogic {
    /**
     * Compares stored active profiles with the SIMs present now. A slot with no stored profile is
     * Added (set up silently); a different subscription in a known slot is Swapped (user prompt).
     */
    fun detectChanges(stored: List<SimProfile>, current: List<DetectedSim>): List<SimChange> = current.mapNotNull { sim ->
        val known = stored.firstOrNull { it.subscriptionId == sim.subscriptionId && it.active }
        val inSlot = stored.firstOrNull { it.slot == sim.slot && it.active }
        when {
            known != null && known.slot == sim.slot -> null
            known != null -> null // same SIM moved slot; handled by refresh
            inSlot == null -> SimChange.Added(sim)
            else -> SimChange.Swapped(inSlot, sim)
        }
    }

    fun newProfile(sim: DetectedSim, now: Long) = SimProfile(
        subscriptionId = sim.subscriptionId, slot = sim.slot, carrier = sim.carrier,
        label = "SIM ${sim.slot}", phoneNumber = sim.number, lastSeenAt = now,
    )

    /** "Keep accounts linked": the same profile now points at the new subscription. */
    fun keepLinked(old: SimProfile, sim: DetectedSim, now: Long) = old.copy(
        subscriptionId = sim.subscriptionId, carrier = sim.carrier ?: old.carrier,
        phoneNumber = sim.number ?: old.phoneNumber, active = true, lastSeenAt = now,
    )

    /** "New number": old profile kept inactive (history intact), a fresh profile for the slot. */
    fun setupFresh(old: SimProfile, sim: DetectedSim, now: Long): Pair<SimProfile, SimProfile> =
        old.copy(active = false) to newProfile(sim, now)

    /**
     * Account seen on a SIM. Returns the updated account and whether it moved from another SIM
     * (to show "HDFC ••1234 now on SIM 2").
     */
    fun onAccountSeen(account: Account, simProfileId: Long?): Pair<Account, Boolean> = when {
        simProfileId == null || account.simProfileId == simProfileId -> account to false
        account.simProfileId == null -> account.copy(simProfileId = simProfileId) to false
        account.autoUpdateSim -> account.copy(simProfileId = simProfileId) to true
        else -> account to false
    }
}

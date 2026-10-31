package com.krish.spendsense.parse

enum class Direction { DEBIT, CREDIT }

enum class Source(val flag: Int) { SMS(1), NOTIF(2), IMPORT(4), MANUAL(8) }

enum class TxnApp { GPAY, PAYTM, OTHER_UPI, NON_UPI }

enum class UpiType { P2M, P2P, P2A }

/** Income classification a parser can infer from wording it sees (the raw body is not stored). */
enum class IncomeKind(val label: String) {
    SALARY("Salary"),
    DIVIDENDS_INTEREST("Dividends & Interest"),
    REFUND("Refund"),
    TRANSFER_IN("Transfer In"),
    OTHER_INCOME("Other Income"),
}

/**
 * Output of every SMS / notification parser. Money is always integer paise.
 *
 * [accountTail] generalises "last4": some banks give only 2-3 trailing digits.
 * [counterpartyTail] is the masked tail of the receiving account; it may be
 * alphanumeric (e.g. `IPAY`), in which case it identifies a merchant/aggregator.
 */
data class ParsedTxn(
    val amountPaise: Long,
    val direction: Direction,
    val bank: String,
    val timestamp: Long,
    val source: Source,
    val app: TxnApp,
    val accountTail: String? = null,
    val upiRef: String? = null,
    val payeeName: String? = null,
    val payeeVpa: String? = null,
    val note: String? = null,
    val balanceAfterPaise: Long? = null,
    val simSlot: Int? = null,
    val counterpartyTail: String? = null,
    val upiType: UpiType? = null,
    val incomeHint: IncomeKind? = null,
    /** Read by the general fallback reader, not a verified bank format: saved but flagged for review. */
    val needsReview: Boolean = false,
) {
    /** Only a numeric counterparty tail can be one of the user's own accounts. */
    val counterpartyIsAccount: Boolean get() = counterpartyTail?.all { it.isDigit() } == true
}

package com.krish.spendsense.parse

import com.krish.spendsense.parse.ParseUtils.AMT
import com.krish.spendsense.parse.ParseUtils.VPA

/**
 * GPay / Paytm notification parsers. Notifications contribute the payee name and the app;
 * account, balance and ref normally come from the bank SMS on merge.
 *
 * UNVERIFIED FORMAT: no real notification samples yet. Patterns follow the commonly seen wording;
 * use Debug capture mode to collect failures and tune them.
 */
abstract class AppNotifParser(val packageName: String, private val app: TxnApp) {
    protected abstract val debitPatterns: List<Regex>
    protected abstract val creditPatterns: List<Regex>

    fun parse(title: String?, text: String?, postedAt: Long): ParsedTxn? {
        val body = ParseUtils.normalize(listOfNotNull(title, text).joinToString("\n"))
        if (body.isEmpty() || notATxn.containsMatchIn(body)) return null
        val (match, direction) = debitPatterns.firstNotNullOfOrNull { r -> r.find(body)?.let { it to Direction.DEBIT } }
            ?: creditPatterns.firstNotNullOfOrNull { r -> r.find(body)?.let { it to Direction.CREDIT } }
            ?: return null
        val amount = ParseUtils.parseAmountPaise(match.groups["amt"]!!.value)?.takeIf { it > 0 } ?: return null
        val party = ParseUtils.cleanName(match.groups["party"]?.value)
        val partyIsVpa = party != null && ParseUtils.isVpa(party)
        return ParsedTxn(
            amountPaise = amount,
            direction = direction,
            bank = BANK_UNKNOWN,
            timestamp = postedAt,
            source = Source.NOTIF,
            app = app,
            upiRef = ref.find(body)?.groupValues?.get(1),
            payeeName = party.takeUnless { partyIsVpa },
            payeeVpa = (if (partyIsVpa) party else vpa.find(body)?.value)?.lowercase(),
        )
    }

    companion object {
        const val BANK_UNKNOWN = "UPI"

        /** Party names end at a line break, a sentence end or connective words. */
        const val PARTY = """(?<party>[^\n]+?)(?=\s*(?:\n|\.\s|\.$|$|\bvia\b|\busing\b|\bfrom your\b|\bon\b|\bsuccessfully\b|\bUPI\b|!))"""
        const val AMOUNT = """(?:Rs\.?|INR|₹)\s*(?<amt>[\d,]+(?:\.\d{1,2})?)"""

        private val notATxn = Regex(
            """\b(?:request(?:ed|s)?|pending|failed|declined|reminder|cashback|scratch card|offer|due)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val ref = Regex(
            """(?:UPI\s*(?:Ref(?:erence)?\.?|transaction)?\s*(?:No\.?|ID|number)?|Ref\.?\s*No\.?|RRN)\s*[:#]?\s*(\d{12})""",
            RegexOption.IGNORE_CASE,
        )
        private val vpa = Regex(VPA)

        fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

        val all: List<AppNotifParser> by lazy { listOf(GpayParser, PaytmParser) }

        fun forPackage(pkg: String): AppNotifParser? = all.firstOrNull { it.packageName == pkg }
    }
}

// UNVERIFIED FORMAT
object GpayParser : AppNotifParser("com.google.android.apps.nbu.paisa.user", TxnApp.GPAY) {
    override val debitPatterns = listOf(
        rx("""(?:You\s+)?paid\s+$AMOUNT\s+to\s+$PARTY"""),
        rx("""$AMOUNT\s+(?:paid|sent)\s+to\s+$PARTY"""),
        rx("""Payment\s+of\s+$AMOUNT\s+to\s+$PARTY"""),
    )
    override val creditPatterns = listOf(
        rx("""^(?<party>[^\n]+?)\s+(?:paid|sent)\s+you\s+$AMOUNT"""),
        rx("""(?:You\s+)?received\s+$AMOUNT\s+from\s+$PARTY"""),
        rx("""$AMOUNT\s+received\s+from\s+$PARTY"""),
    )
}

// UNVERIFIED FORMAT
object PaytmParser : AppNotifParser("net.one97.paytm", TxnApp.PAYTM) {
    override val debitPatterns = listOf(
        rx("""(?:Paid|Sent)\s+$AMOUNT\s+to\s+$PARTY"""),
        rx("""$AMOUNT\s+(?:paid|sent)\s+(?:successfully\s+)?to\s+$PARTY"""),
        rx("""Money\s+sent\s+to\s+$PARTY[\s\S]*?$AMOUNT"""),
        rx("""Payment\s+of\s+$AMOUNT\s+(?:to|at)\s+$PARTY"""),
    )
    override val creditPatterns = listOf(
        rx("""Received\s+$AMOUNT\s+from\s+$PARTY"""),
        rx("""$AMOUNT\s+received\s+from\s+$PARTY"""),
        rx("""^(?<party>[^\n]+?)\s+(?:paid|sent)\s+you\s+$AMOUNT"""),
    )
}

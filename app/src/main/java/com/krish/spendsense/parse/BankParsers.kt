package com.krish.spendsense.parse

import com.krish.spendsense.parse.ParseUtils.AMT
import com.krish.spendsense.parse.ParseUtils.DATE
import com.krish.spendsense.parse.ParseUtils.TIME
import com.krish.spendsense.parse.ParseUtils.VPA

/** One SMS being parsed: [body] is already normalised. */
class SmsInput(val bank: String, val body: String, val receivedAt: Long, val simSlot: Int?)

/** One regex template; returns null unless the whole template matches. */
internal class Template(pattern: String, val build: SmsInput.(MatchResult) -> ParsedTxn?) {
    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    fun parse(input: SmsInput): ParsedTxn? = regex.find(input.body)?.let { input.build(it) }
}

private fun SmsInput.txn(
    amount: String,
    direction: Direction,
    tail: String?,
    date: String?,
    time: String? = null,
    ref: String? = null,
    counterparty: String? = null,
    balance: String? = null,
    upiType: String? = null,
    app: TxnApp = if (ref != null) TxnApp.OTHER_UPI else TxnApp.NON_UPI,
    counterpartyTail: String? = null,
    incomeHint: IncomeKind? = null,
    note: String? = null,
): ParsedTxn? {
    val paise = ParseUtils.parseAmountPaise(amount)?.takeIf { it > 0 } ?: return null
    val parsedDate = date?.let { ParseUtils.parseDate(it) ?: return null }
    val parsedTime = time?.let { ParseUtils.parseTime(it) }
    val cp = ParseUtils.cleanName(counterparty)
    val isVpa = cp != null && ParseUtils.isVpa(cp)
    return ParsedTxn(
        amountPaise = paise,
        direction = direction,
        bank = bank,
        timestamp = ParseUtils.resolveTimestamp(parsedDate, parsedTime, receivedAt, ref),
        source = Source.SMS,
        app = app,
        accountTail = tail?.uppercase(),
        upiRef = ref,
        payeeName = cp.takeUnless { isVpa },
        payeeVpa = cp.takeIf { isVpa }?.lowercase(),
        balanceAfterPaise = balance?.let { ParseUtils.parseAmountPaise(it) },
        simSlot = simSlot,
        counterpartyTail = counterpartyTail?.uppercase(),
        upiType = upiType?.let { UpiType.valueOf(it.uppercase()) },
        incomeHint = incomeHint,
        note = note,
    )
}

private fun dirOf(verb: String) = if (verb.lowercase().startsWith("deb")) Direction.DEBIT else Direction.CREDIT

private val INCOME_INTEREST = Regex("""NACH_CR|ACH_CR|DIVIDEND|INT\.PD|INTEREST""", RegexOption.IGNORE_CASE)
private val INCOME_SALARY = Regex("""\bSALARY\b|\bSAL\b""", RegexOption.IGNORE_CASE)

private fun incomeHintFor(text: String): IncomeKind? = when {
    INCOME_SALARY.containsMatchIn(text) -> IncomeKind.SALARY
    INCOME_INTEREST.containsMatchIn(text) -> IncomeKind.DIVIDENDS_INTEREST
    else -> null
}

/**
 * Templates whose wording is shared by several banks, so the bank comes from the sender ID.
 * The debit form is verified against the CUB fixtures.
 */
private val commonTemplates = listOf(
    Template(
        """(?:a/c no\.?|Savings No\.?|A/c)\s*[X*]*(\d{2,4})\s+is debited for\s+$AMT\s+on\s+$DATE\s+and credited to\s+(?:a/c no\.?|A/c)\s*X+([A-Z0-9]{2,6})\s*\(\s*UPI Ref no\.?\s*(\d{12})\s*\)""",
    ) { m ->
        val (tail, amt, date, cpTail, ref) = m.destructured
        txn(amt, Direction.DEBIT, tail, date, ref = ref, counterpartyTail = cpTail)
    },
    // UNVERIFIED FORMAT: mirror of the CUB debit for an incoming UPI (P2P) credit.
    Template(
        """(?:a/c no\.?|Savings No\.?|A/c)\s*[X*]*(\d{2,4})\s+is credited (?:for|with|by)\s+$AMT\s+on\s+$DATE\s+(?:and debited from|from|by)\s+(?:a/c no\.?\s*X*([A-Z0-9]{2,6})|VPA\s*($VPA)|(.+?))\s*\(\s*UPI Ref no\.?\s*(\d{12})\s*\)""",
    ) { m ->
        val (tail, amt, date, cpTail, vpa, name, ref) = m.destructured
        txn(
            amt, Direction.CREDIT, tail, date, ref = ref,
            counterparty = vpa.ifEmpty { name }.ifEmpty { null },
            counterpartyTail = cpTail.ifEmpty { null },
            upiType = "P2P",
        )
    },
)

abstract class BankParser(val bank: String) {
    internal abstract val ownTemplates: List<Template>

    private val templates: List<Template> by lazy { ownTemplates + commonTemplates }

    /** Tries each template in order and returns the first full match. */
    fun parse(body: String, receivedAt: Long, simSlot: Int? = null): ParsedTxn? {
        val input = SmsInput(bank, ParseUtils.normalize(body), receivedAt, simSlot)
        return templates.firstNotNullOfOrNull { it.parse(input) }
    }
}

object HdfcParser : BankParser("HDFC") {
    override val ownTemplates: List<Template> = listOf(
        // Verified: Sent Rs.<amt> / From HDFC Bank A/C *<last4> / To <name> / On dd/MM/yy / Ref <rrn>
        Template("""Sent\s+$AMT\s+From\s+HDFC\s+Bank\s+A/C\s*[*X]*(\d{2,4})\s+To\s+(.+?)\s+On\s+$DATE\s+Ref\s+(\d{12})""") { m ->
            val (amt, tail, to, date, ref) = m.destructured
            txn(amt, Direction.DEBIT, tail, date, ref = ref, counterparty = to)
        },
        // Verified (NEFT): "Amt Deducted! Rs.<amt> from your HDFC Bank A/c XX<last4> for NEFT txn via HDFC Bank Online Banking".
        // No date or payee: the received time is used and the row goes to Review for a category. IMPS/RTGS: UNVERIFIED FORMAT.
        Template("""Amt\s+Deducted!?\s*$AMT\s+from\s+your\s+HDFC\s+Bank\s+A/\s*c\s*[*X]*(\d{2,4})\s+for\s+(NEFT|IMPS|RTGS)\b""") { m ->
            val (amt, tail, mode) = m.destructured
            txn(amt, Direction.DEBIT, tail, null, note = "${mode.uppercase()} transfer")
        },
        // UNVERIFIED FORMAT: Received Rs.<amt> / In HDFC Bank A/C *<last4> / From <name> / On <date> / Ref <rrn>
        Template("""Received\s+$AMT\s+In\s+HDFC\s+Bank\s+A/C\s*[*X]*(\d{2,4})\s+From\s+(.+?)\s+On\s+$DATE\s+Ref\s+(\d{12})""") { m ->
            val (amt, tail, from, date, ref) = m.destructured
            txn(amt, Direction.CREDIT, tail, date, ref = ref, counterparty = from)
        },
        // UNVERIFIED FORMAT: "Rs.500.00 credited to HDFC Bank A/c XX1234 on 01-01-25 from VPA a@b (UPI 123456789012)"
        Template("""$AMT\s+credited\s+to\s+HDFC\s+Bank\s+A/c\s*[*X]*(\d{2,4})\s+on\s+$DATE\s+from\s+VPA\s*($VPA)\s*\(\s*UPI(?:\s*Ref)?(?:\s*No\.?)?\s*(\d{12})\s*\)""") { m ->
            val (amt, tail, date, vpa, ref) = m.destructured
            txn(amt, Direction.CREDIT, tail, date, ref = ref, counterparty = vpa)
        },
        // UNVERIFIED FORMAT: "Rs.500.00 debited from a/c **1234 on 01-01-25 to VPA a@b (UPI Ref No 123456789012)"
        Template("""$AMT\s+debited\s+from\s+a/c\s*[*X]*(\d{2,4})\s+on\s+$DATE\s+to\s+VPA\s*($VPA)\s*\(\s*UPI(?:\s*Ref)?(?:\s*No\.?)?\s*(\d{12})\s*\)""") { m ->
            val (amt, tail, date, vpa, ref) = m.destructured
            txn(amt, Direction.DEBIT, tail, date, ref = ref, counterparty = vpa)
        },
    )
}

object AxisParser : BankParser("AXIS") {
    override val ownTemplates: List<Template> = listOf(
        // Verified (debit): INR <amt> debited / A/c no. XX<last4> / dd-MM-yy, HH:mm:ss / UPI/<type>/<ref>/<name>
        // The credit wording ("credited") is UNVERIFIED FORMAT. Axis truncates the name to ~20 chars.
        Template("""$AMT\s+(debited|credited)\s+A/c\s*no\.?\s*[X*]*(\d{2,4})\s+$DATE,?\s*$TIME\s+UPI/(P2M|P2P|P2A)/(\d{12})/(.+?)(?=\s*(?:\n|Not you\?|Axis Bank|$))""") { m ->
            val (amt, verb, tail, date, time, type, ref, name) = m.destructured
            txn(amt, dirOf(verb), tail, date, time, ref = ref, counterparty = name, upiType = type)
        },
    )
}

object CubParser : BankParser("CUB") {
    override val ownTemplates: List<Template> = listOf(
        // Verified: NACH/ACH bank-transfer credit with a 2-digit account tail and a bare balance.
        Template("""(?:Savings No\.?|a/c no\.?|A/c)\s*[X*]*(\d{2,4})\s+credited with\s+$AMT\s+towards\s+([\s\S]+?)\s+on\s+$DATE\.?(?:\s*Avl\.?\s*Bal\.?\s*(?:Rs\.?|INR|₹)?\s*([\d,]+(?:\.\d{1,2})?))?""") { m ->
            val (tail, amt, towards, date, bal) = m.destructured
            val desc = towards.replace(Regex("\\s+"), " ")
            val payee = Regex("""N?ACH_CR/([^/]+)""", RegexOption.IGNORE_CASE).find(desc)?.groupValues?.get(1) ?: desc
            val ref = Regex("""UPI\D{0,12}(\d{12})""", RegexOption.IGNORE_CASE).find(desc)?.groupValues?.get(1)
            txn(
                amt, Direction.CREDIT, tail, date, ref = ref, counterparty = payee,
                balance = bal.ifEmpty { null }, incomeHint = incomeHintFor(desc),
            )
        },
    )
}

/**
 * Last-resort reader for bank SMS that no template matched, from any bank. It needs an amount, one clear
 * direction word and an account ending; reminders, requests and failed transactions are refused. Everything
 * it reads is flagged [ParsedTxn.needsReview]. Returns null to leave the message for the Review inbox.
 */
internal object GeneralSmsReader {
    private val direction = Regex(
        """\b(debited|deducted|withdrawn|spent|sent|paid|credited|received|deposited)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val notDone = Regex(
        """\b(?:will\s+be|to\s+be|shall\s+be|is\s+due|are\s+due|due\s+(?:on|by|date)|min(?:imum)?\.?\s+(?:amt|amount)|""" +
            """has\s+requested|requested\s+(?:you|money|Rs|INR|₹)|collect\s+request|payment\s+request|scheduled|upcoming|""" +
            """failed|declined|unsuccessful|could\s+not|insufficient)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val amount = Regex(AMT, RegexOption.IGNORE_CASE)
    private val balanceBefore = Regex("""(?:bal(?:ance)?|limit)\W{0,3}(?:is\W{0,3})?$""", RegexOption.IGNORE_CASE)
    private val account = Regex(
        """\b(?:a/\s*c|acct|account|ac|card|savings\s+no)\.?\s*(?:no\.?|number)?\s*(?:ending(?:\s+with)?|xx|\*)?\s*[:\-]?\s*[X*]*(\d{2,})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val balance = Regex(
        """(?:Avl\.?|Available|Avail\.?)?\s*\bBal(?:ance)?\.?\s*(?:is|:)?\s*(?:Rs\.?|INR|₹)?\s*([\d,]+(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val upiRef = Regex("""(?:UPI|Ref|RRN|UTR)\D{0,15}(\d{12})(?!\d)""", RegexOption.IGNORE_CASE)
    private val mode = Regex("""\b(NEFT|IMPS|RTGS|UPI|ATM|NACH|ECS|POS)\b""", RegexOption.IGNORE_CASE)
    private val date = Regex("(?<!\\d)$DATE(?!\\d)")
    private val time = Regex(TIME)
    private val vpa = Regex("""\b(?:VPA|to|from|by)\s*:?\s*($VPA)""", RegexOption.IGNORE_CASE)

    fun read(input: SmsInput): ParsedTxn? = with(input) {
        if (notDone.containsMatchIn(body)) return null
        val verb = direction.find(body) ?: return null
        val dir = when (verb.value.lowercase()) {
            "credited", "received", "deposited" -> Direction.CREDIT
            else -> Direction.DEBIT
        }
        // First amount that is not a balance or limit.
        val amt = amount.findAll(body).firstOrNull { !balanceBefore.containsMatchIn(body.substring(0, it.range.first)) } ?: return null
        val tail = account.find(body)?.groupValues?.get(1)?.takeLast(4) ?: return null
        val kind = mode.find(body)?.value?.uppercase()
        val ref = upiRef.find(body)?.groupValues?.get(1)
        // A date later than the day the SMS arrived is not this transaction's date.
        val received = ParseUtils.istDate(receivedAt)
        val parsedDate = date.findAll(body).map { it.value }.firstOrNull { d -> ParseUtils.parseDate(d)?.let { !it.isAfter(received) } == true }
        txn(
            amt.groupValues[1], dir, tail, parsedDate,
            time = parsedDate?.let { time.find(body)?.value },
            ref = ref,
            app = if (kind == "UPI" || (kind == null && ref != null)) TxnApp.OTHER_UPI else TxnApp.NON_UPI,
            counterparty = vpa.find(body)?.groupValues?.get(1),
            balance = balance.find(body)?.groupValues?.get(1)?.takeIf { it.any(Char::isDigit) },
            incomeHint = if (dir == Direction.CREDIT) incomeHintFor(body) else null,
            note = when (kind) {
                null -> "Bank SMS"
                "ATM" -> "ATM withdrawal"
                "POS" -> "Card payment"
                else -> "$kind transfer"
            },
        )?.copy(needsReview = true)
    }
}

/** Bank added by the user in Settings with no dedicated parser: shared templates only. */
class GenericBankParser(bank: String) : BankParser(bank) {
    override val ownTemplates: List<Template> = emptyList()
}

sealed interface SmsOutcome {
    data object NotBankSender : SmsOutcome
    data class Filtered(val reason: MessageFilter.Reject) : SmsOutcome
    data class Parsed(val txn: ParsedTxn, val senderKey: String) : SmsOutcome
    data class Unparsed(val bank: String, val senderKey: String) : SmsOutcome
}

object SmsParser {
    private val parsers = listOf(HdfcParser, AxisParser, CubParser).associateBy { it.bank }

    fun parserFor(bank: String): BankParser = parsers[bank] ?: GenericBankParser(bank)

    /**
     * Full pipeline for one SMS: sender allow-list, OTP/promo filter, then the bank's parser.
     * [senderKeys] maps a DLT middle key (e.g. HDFCBK) to a bank code.
     */
    fun parse(
        sender: String,
        body: String,
        receivedAt: Long,
        simSlot: Int? = null,
        senderKeys: Map<String, String> = MessageFilter.DEFAULT_SENDER_KEYS,
    ): SmsOutcome {
        val key = MessageFilter.senderKey(sender)
        val bank = senderKeys[key] ?: return SmsOutcome.NotBankSender
        MessageFilter.rejectReason(body)?.let { return SmsOutcome.Filtered(it) }
        val txn = parserFor(bank).parse(body, receivedAt, simSlot)
            ?: GeneralSmsReader.read(SmsInput(bank, ParseUtils.normalize(body), receivedAt, simSlot))
            ?: return SmsOutcome.Unparsed(bank, key)
        return SmsOutcome.Parsed(txn, key)
    }

    private val anyAmount = Regex(AMT, RegexOption.IGNORE_CASE)
    private val anyTail = Regex("""(?:a/c|acct|account|Savings No|card)\.?\s*(?:no\.?)?\s*[X*]+(\d{2,4})""", RegexOption.IGNORE_CASE)
    private val anyRef = Regex("""(?:UPI|Ref|RRN)\D{0,15}(\d{12})""", RegexOption.IGNORE_CASE)
    private val anyVpa = Regex(VPA)
    private val creditWord = Regex("""\b(credited|received|deposited)\b""", RegexOption.IGNORE_CASE)

    /** Best-effort extraction used to prefill a manual transaction from an unparsed message. */
    fun partial(bank: String, body: String, receivedAt: Long): ParsedTxn? {
        val text = ParseUtils.normalize(body)
        val amount = anyAmount.find(text)?.groupValues?.get(1)?.let { ParseUtils.parseAmountPaise(it) } ?: return null
        val ref = anyRef.find(text)?.groupValues?.get(1)
        return ParsedTxn(
            amountPaise = amount,
            direction = if (creditWord.containsMatchIn(text)) Direction.CREDIT else Direction.DEBIT,
            bank = bank,
            timestamp = receivedAt,
            source = Source.SMS,
            app = if (ref != null) TxnApp.OTHER_UPI else TxnApp.NON_UPI,
            accountTail = anyTail.find(text)?.groupValues?.get(1),
            upiRef = ref,
            payeeVpa = anyVpa.find(text)?.value?.lowercase(),
        )
    }
}

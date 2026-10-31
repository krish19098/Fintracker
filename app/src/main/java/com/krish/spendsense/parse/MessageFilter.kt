package com.krish.spendsense.parse

/** Sender allow-listing and OTP / promo / balance-only rejection for bank SMS. */
object MessageFilter {
    /** Default DLT middle keys -> bank code. Editable in Settings -> Accounts. */
    val DEFAULT_SENDER_KEYS: Map<String, String> = mapOf(
        "HDFCBK" to "HDFC",
        "HDFCBN" to "HDFC",
        "AXISBK" to "AXIS",
        "CUBANK" to "CUB",
        "CUBLTD" to "CUB",
    )

    enum class Reject { OTP, PROMOTIONAL, NO_TRANSACTION }

    private val prefix = Regex("^[A-Z]{2}-")
    private val suffix = Regex("-[STPG]$")

    /** `XX-HDFCBK`, `XX-HDFCBK-S`, `HDFCBK` -> `HDFCBK`. */
    fun senderKey(sender: String): String =
        sender.trim().uppercase().replace(prefix, "").replace(suffix, "")

    private val otp = Regex(
        """\bOTP\b|one[\s-]?time\s+pass(?:word|code)|verification\s+code|\bcode\s+is\s+\d""",
        RegexOption.IGNORE_CASE,
    )
    private val verb = Regex(
        """\b(?:debited|credited|deducted|deposited|sent|received|spent|paid|withdrawn|transferred|debit|credit)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val amount = Regex(ParseUtils.AMT, RegexOption.IGNORE_CASE)
    private val promo = Regex(
        """\b(?:loan|offer|pre-?approved|apply now|click|voucher|reward points?|win|congratulations|limit increase)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val txnEvidence = Regex("""\d{12}|[X*]{2,}\w{2,4}|A/C\s*\*\d""", RegexOption.IGNORE_CASE)

    /** Null when the body looks like a real transaction; otherwise why it was dropped. */
    fun rejectReason(body: String): Reject? = when {
        otp.containsMatchIn(body) -> Reject.OTP
        !verb.containsMatchIn(body) || !amount.containsMatchIn(body) -> Reject.NO_TRANSACTION
        promo.containsMatchIn(body) && !txnEvidence.containsMatchIn(body) -> Reject.PROMOTIONAL
        else -> null
    }
}

package com.krish.spendsense.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * Runs every `fixtures/<kind>/<name>.txt` through the real pipeline and asserts the complete
 * [ParsedTxn] built from its `.expected.json`. Keys absent from the JSON take their documented
 * defaults: null, `app` = OTHER_UPI with a ref / NON_UPI without, `source` from the folder.
 */
class FixtureParserTest {
    private val root = File(System.getProperty("fixtures.dir") ?: "../fixtures")
    private val meta = Json.parseToJsonElement(File(root, "fixtures.json").readText()).jsonObject

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull

    private fun fixtures(kind: String) = File(root, kind).listFiles { f -> f.name.endsWith(".txt") }!!
        .sortedBy { it.name }

    @Test
    fun allSmsFixtures() {
        val files = fixtures("sms")
        assertTrue("expected the real samples", files.size >= 6)
        files.forEach { check("sms", it) }
    }

    @Test
    fun allNotifFixtures() = fixtures("notif").forEach { check("notif", it) }

    private fun check(kind: String, txt: File) {
        val name = txt.nameWithoutExtension
        val expectedJson = Json.parseToJsonElement(File(txt.parentFile, "$name.expected.json").readText()).jsonObject
        val m = meta["$kind/$name"]?.jsonObject ?: JsonObject(emptyMap())
        val body = txt.readText()

        val stated = expectedJson.str("timestamp")?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        val date = expectedJson.str("date")?.let(LocalDate::parse)
        val receivedAt = m.str("receivedAt")?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            // Injected receive time on the SMS date (a few minutes after any stated time).
            ?: ZonedDateTime.of(
                date ?: ParseUtils.istDate(stated!!),
                if (stated != null) LocalTime.of(23, 50) else LocalTime.of(10, 15),
                ParseUtils.IST,
            ).toInstant().toEpochMilli()
        val expectedTs = when {
            stated != null -> stated
            expectedJson.str("timeFrom") == "SMS_RECEIVED" -> receivedAt
            else -> error("$name: no timestamp rule")
        }

        val actual = if (kind == "sms") {
            val outcome = SmsParser.parse(m.str("sender")!!, body, receivedAt)
            (outcome as? SmsOutcome.Parsed)?.txn ?: error("$name: $outcome")
        } else {
            val lines = body.lines()
            AppNotifParser.forPackage(m.str("package")!!)!!
                .parse(lines.first(), lines.drop(1).joinToString("\n"), receivedAt) ?: error("$name: no parse")
        }

        val ref = expectedJson.str("upiRef")
        val expected = ParsedTxn(
            amountPaise = expectedJson.long("amountPaise")!!,
            direction = Direction.valueOf(expectedJson.str("direction")!!),
            bank = expectedJson.str("bank")!!,
            timestamp = expectedTs,
            source = if (kind == "sms") Source.SMS else Source.NOTIF,
            app = expectedJson.str("app")?.let(TxnApp::valueOf) ?: if (ref != null) TxnApp.OTHER_UPI else TxnApp.NON_UPI,
            accountTail = expectedJson.str("accountTail") ?: expectedJson.str("accountLast4"),
            upiRef = ref,
            payeeName = expectedJson.str("payeeName"),
            payeeVpa = expectedJson.str("payeeVpa"),
            note = expectedJson.str("note"),
            balanceAfterPaise = expectedJson.long("balanceAfterPaise"),
            counterpartyTail = expectedJson.str("counterpartyTail") ?: expectedJson.str("counterpartyAcctLast4"),
            upiType = expectedJson.str("upiType")?.let(UpiType::valueOf),
            incomeHint = expectedJson.str("incomeCategory")?.let { l -> IncomeKind.entries.first { it.label == l } },
        )
        assertEquals("fixture $kind/$name", expected, actual)
    }
}

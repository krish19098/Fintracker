package com.krish.spendsense.data

import com.krish.spendsense.domain.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val data = BackupData(
        sims = emptyList(), accounts = listOf(Account(id = 1, bank = "HDFC", last4 = "9924", nickname = "Main")),
        senders = emptyList(), matchRules = emptyList(), txns = listOf(TestData.txn(250, payee = "Tea, \"Ravi\"")),
        categories = TestData.categories, payeeRules = emptyList(), keywordRules = emptyList(), unparsed = emptyList(), hashes = emptyList(),
    )

    @Test
    fun encryptedRoundTripNeedsPassphrase() {
        val bytes = Backup.encrypt(data, "secret".toCharArray())
        assertTrue(!String(bytes).contains("9924"))
        assertEquals(data, Backup.decrypt(bytes, "secret".toCharArray()))
        assertThrows(Exception::class.java) { Backup.decrypt(bytes, "wrong".toCharArray()) }
    }

    @Test
    fun csvEscapesAndUsesExactAmounts() {
        val csv = Backup.toCsv(data.txns, data.accounts.associateBy { it.id }, emptyMap())
        val line = csv.lines()[1]
        assertTrue(line.contains(",DEBIT,250.00,\"Tea, \"\"Ravi\"\"\","))
        assertTrue(line.contains(",Main,"))
    }
}

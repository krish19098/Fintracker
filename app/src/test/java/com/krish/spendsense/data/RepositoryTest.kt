package com.krish.spendsense.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.krish.spendsense.domain.CatKeys
import com.krish.spendsense.domain.TestData
import com.krish.spendsense.domain.Totals
import com.krish.spendsense.parse.AppNotifParser
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.GpayParser
import com.krish.spendsense.parse.IncomeKind
import com.krish.spendsense.parse.ParsedTxn
import com.krish.spendsense.parse.SmsOutcome
import com.krish.spendsense.parse.SmsParser
import com.krish.spendsense.parse.Source
import com.krish.spendsense.parse.TxnApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Dedup / merge / account / SIM scenarios against a real (unencrypted, in-memory) Room DB. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private var settings = AppSettings()

    private val axisSms = "INR 19.00 debited\nA/c no. XX9694\n09-05-25, 11:37:56\nUPI/P2M/512920989783/Google India Digita\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank"
    private val axisAt = TestData.at(LocalDate.of(2025, 5, 9), 11, 38)

    @Before
    fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db, { settings })
        repo.seedIfEmpty()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun sms(sender: String, body: String, at: Long, slot: Int? = null): IngestResult {
        val out = SmsParser.parse(sender, body, at, slot, repo.senderKeyMap()) as SmsOutcome.Parsed
        return repo.ingest(out.txn, TxnMerge.rawHash(sender, body, at), out.senderKey)
    }

    private suspend fun notif(title: String, text: String?, at: Long, parser: AppNotifParser = GpayParser): IngestResult =
        repo.ingest(parser.parse(title, text, at)!!, TxnMerge.rawHash(parser.packageName, "$title\n$text", at), null)

    private suspend fun all() = db.txnDao().all()

    private fun assertMergedAxis(t: Txn) {
        assertEquals("Google India Digital Services", t.payeeName)
        assertEquals(TxnApp.GPAY, t.app)
        assertEquals(Source.SMS.flag or Source.NOTIF.flag, t.sourceFlags)
        assertEquals("512920989783", t.upiRef)
        assertTrue(t.accountId != null)
    }

    @Test
    fun smsThenNotification() = runBlocking {
        sms("AX-AXISBK-S", axisSms, axisAt)
        val r = notif("₹19.00 paid to Google India Digital Services", "UPI transaction ID: 512920989783", axisAt + 5_000)
        assertTrue(r is IngestResult.Saved && !r.inserted)
        assertMergedAxis(all().single())
    }

    @Test
    fun notificationThenSms() = runBlocking {
        notif("₹19.00 paid to Google India Digital Services", "UPI transaction ID: 512920989783", axisAt - 30_000)
        sms("AX-AXISBK-S", axisSms, axisAt)
        assertMergedAxis(all().single())
        assertEquals("9694", db.accountDao().byId(all().single().accountId!!)!!.last4)
    }

    @Test
    fun duplicateSmsProcessedOnce() = runBlocking {
        sms("AX-AXISBK-S", axisSms, axisAt)
        assertEquals(IngestResult.Duplicate, sms("AX-AXISBK-S", axisSms, axisAt))
        assertEquals(1, all().size)
    }

    @Test
    fun noRefFallbackMergesNotification() = runBlocking {
        sms("AX-AXISBK-S", axisSms, axisAt)
        notif("₹19.00 paid to Google India Digital Services", null, axisAt + 60_000)
        assertMergedAxis(all().single())
    }

    @Test
    fun selfTransferPairsBothLegs() = runBlocking {
        val hdfc = repo.createAccount("HDFC", "9924", "HDFC")
        repo.createAccount("CUB", "6578", "CUB")
        val day = LocalDate.of(2026, 9, 17)
        val debit = ParsedTxn(50_000, Direction.DEBIT, "CUB", TestData.at(day, 10, 0), Source.SMS, TxnApp.OTHER_UPI, accountTail = "6578")
        val credit = ParsedTxn(50_000, Direction.CREDIT, "HDFC", TestData.at(day, 10, 6), Source.SMS, TxnApp.OTHER_UPI, accountTail = "9924")
        repo.ingest(debit, "d", "CUBANK")
        repo.ingest(credit, "c", "HDFCBK")
        val rows = all()
        assertTrue(rows.all { it.excludedReason == ExcludedReason.SELF_TRANSFER })
        assertEquals(rows[0].id, rows[1].linkedTxnId)
        assertEquals(0L, Totals.spend(rows))
        assertEquals(0L, Totals.income(rows))
        assertTrue(rows.any { it.accountId == hdfc })
    }

    @Test
    fun sameRefOnBothOwnAccountsIsTwoLegsNotAMerge() = runBlocking {
        val cub = repo.createAccount("CUB", "6578", "CUB")
        val hdfc = repo.createAccount("HDFC", "9924", "HDFC")
        val day = LocalDate.of(2026, 9, 17)
        val ref = "626017540942"
        repo.ingest(ParsedTxn(37_600, Direction.DEBIT, "CUB", TestData.at(day, 10, 0), Source.SMS, TxnApp.OTHER_UPI, accountTail = "6578", upiRef = ref), "d", "CUBANK")
        repo.ingest(ParsedTxn(37_600, Direction.CREDIT, "HDFC", TestData.at(day, 10, 20), Source.SMS, TxnApp.OTHER_UPI, accountTail = "9924", upiRef = ref), "c", "HDFCBK")
        val rows = all()
        assertEquals(2, rows.size)
        assertEquals(cub, rows.first { it.direction == Direction.DEBIT }.accountId)
        assertEquals(hdfc, rows.first { it.direction == Direction.CREDIT }.accountId)
        assertTrue(rows.all { it.excludedReason == ExcludedReason.SELF_TRANSFER })
        assertEquals(0L, Totals.spend(rows))
    }

    @Test
    fun deletingAccountWithTransactionsUnlinksRefundsElsewhere() = runBlocking {
        val a = repo.createAccount("HDFC", "9924", "A")
        val day = LocalDate.of(2026, 10, 1)
        repo.ingest(ParsedTxn(120_000, Direction.DEBIT, "HDFC", TestData.at(day), Source.SMS, TxnApp.OTHER_UPI, accountTail = "9924", payeeName = "Flipkart"), "a", "HDFCBK")
        // The refund lands on another account but is linked to the debit on A.
        repo.ingest(ParsedTxn(120_000, Direction.CREDIT, "AXIS", TestData.at(day.plusDays(2)), Source.SMS, TxnApp.OTHER_UPI, accountTail = "1111", payeeName = "Flipkart", note = "Refund"), "b", "AXISBK")
        assertEquals(ExcludedReason.REFUND, all().first { it.direction == Direction.CREDIT }.excludedReason)
        repo.deleteAccount(a, deleteTransactions = true)
        val refund = all().single()
        assertNull(refund.linkedTxnId)
        assertNull(refund.excludedReason)
        assertEquals(0L, Totals.spend(listOf(refund)))
    }

    @Test
    fun cubDebitToOwnAccountTailIsSelfTransfer() = runBlocking {
        repo.createAccount("CUB", "6578", "CUB")
        repo.createAccount("HDFC", "2708", "HDFC")
        sms("JK-CUBANK-S", "Your a/c no. XXXXXXXX6578 is debited for Rs.376.00 on 17-09-2026 and credited to a/c no. XXXXXXXX2708 (UPI Ref no 626017540942)", TestData.at(LocalDate.of(2026, 9, 17)))
        assertEquals(ExcludedReason.SELF_TRANSFER, all().single().excludedReason)
    }

    @Test
    fun refundLinksToOriginal() = runBlocking {
        val day = LocalDate.of(2026, 10, 1)
        repo.ingest(ParsedTxn(120_000, Direction.DEBIT, "HDFC", TestData.at(day), Source.SMS, TxnApp.OTHER_UPI, accountTail = "9924", payeeName = "Flipkart"), "a", "HDFCBK")
        repo.ingest(ParsedTxn(120_000, Direction.CREDIT, "HDFC", TestData.at(day.plusDays(4)), Source.SMS, TxnApp.OTHER_UPI, accountTail = "9924", payeeName = "Flipkart", note = "Refund"), "b", "HDFCBK")
        val rows = all()
        val refund = rows.first { it.direction == Direction.CREDIT }
        assertEquals(ExcludedReason.REFUND, refund.excludedReason)
        assertEquals(rows.first { it.direction == Direction.DEBIT }.id, refund.linkedTxnId)
        assertEquals(0L, Totals.spend(rows))
        assertEquals(db.categoryDao().bySystemKey(CatKeys.income(IncomeKind.REFUND))!!.id, refund.categoryId)
    }

    @Test
    fun twoDigitTailMatchesBySuffixOrGoesToReviewWithMatchRule() = runBlocking {
        val nach = "Savings No XXXXXXXX78 credited with INR 161.00 towards BY ONL NACH_CR/NMDC LTD/ACH_CR/0000060167311:: CA 5109090102 on 23-OCT-2026.Avl Bal 4006.73"
        val at = TestData.at(LocalDate.of(2026, 10, 23))
        // No accounts yet: saved Unassigned and listed for review.
        sms("JK-CUBANK-S", nach, at)
        val unassigned = all().single()
        assertNull(unassigned.accountId)
        assertEquals(db.categoryDao().bySystemKey(CatKeys.income(IncomeKind.DIVIDENDS_INTEREST))!!.id, unassigned.categoryId)
        val cub = repo.createAccount("CUB", "6578", "CUB")
        // "Always use this account" creates a rule used for the next message.
        repo.assignAccount(unassigned.id, cub, always = true)
        sms("JK-CUBANK-S", nach.replace("161.00", "200.00"), at + 1000)
        assertTrue(all().all { it.accountId == cub })
        // Plain suffix match also works for a unique account.
        sms("JK-CUBANK-S", nach.replace("161.00", "300.00").replace("JK", "JK"), at + 2000)
        assertEquals(3, all().count { it.accountId == cub })
    }

    @Test
    fun accountAutoCreatedThenAutoSimUpdate() = runBlocking {
        repo.refreshSims(listOf(DetectedSim(1, 1, "Jio", null), DetectedSim(2, 2, "Airtel", null)))
        val body = "Your a/c no. XXXXXXXX6578 is debited for Rs.29.00 on 07-08-2026 and credited to a/c no. XXXXXXXX1614 (UPI Ref no 621970896384)"
        val first = sms("JK-CUBANK-S", body, TestData.at(LocalDate.of(2026, 8, 7)), slot = 1) as IngestResult.Saved
        val created = first.newAccount!!
        assertEquals("6578", created.last4)
        val slot1 = db.simDao().activeInSlot(1)!!.id
        assertEquals(slot1, created.simProfileId)
        val second = sms("JK-CUBANK-S", body.replace("621970896384", "621970896385"), TestData.at(LocalDate.of(2026, 8, 8)), slot = 2) as IngestResult.Saved
        assertEquals(db.simDao().activeInSlot(2)!!.id, second.simMoved!!.simProfileId)
        // With auto-update off the SIM stays.
        repo.updateAccount(second.simMoved!!.copy(autoUpdateSim = false))
        val third = sms("JK-CUBANK-S", body.replace("621970896384", "621970896386"), TestData.at(LocalDate.of(2026, 8, 9)), slot = 1) as IngestResult.Saved
        assertNull(third.simMoved)
        assertEquals(db.simDao().activeInSlot(2)!!.id, db.accountDao().byId(created.id)!!.simProfileId)
    }

    @Test
    fun simSwapRemapKeepsAccountsLinked() = runBlocking {
        repo.refreshSims(listOf(DetectedSim(1, 1, "Jio", null), DetectedSim(2, 2, "Airtel", null)))
        val slot2 = db.simDao().activeInSlot(2)!!
        val acct = repo.createAccount("HDFC", "9924", "HDFC")
        repo.updateAccount(db.accountDao().byId(acct)!!.copy(simProfileId = slot2.id))
        val swaps = repo.refreshSims(listOf(DetectedSim(1, 1, "Jio", null), DetectedSim(9, 2, "Vi", null)))
        repo.resolveSwap(swaps.single(), keepLinked = true)
        val now = db.simDao().activeInSlot(2)!!
        assertEquals(slot2.id, now.id)
        assertEquals(9, now.subscriptionId)
        assertEquals(slot2.id, db.accountDao().byId(acct)!!.simProfileId)
    }

    @Test
    fun simSwapFreshKeepsOldInactive() = runBlocking {
        repo.refreshSims(listOf(DetectedSim(2, 2, "Airtel", null)))
        val old = db.simDao().activeInSlot(2)!!
        val swap = repo.refreshSims(listOf(DetectedSim(9, 2, "Vi", null))).single()
        repo.resolveSwap(swap, keepLinked = false)
        val sims = db.simDao().all()
        assertEquals(2, sims.size)
        assertTrue(sims.first { it.id == old.id }.active.not())
        assertEquals(9, db.simDao().activeInSlot(2)!!.subscriptionId)
    }

    @Test
    fun mergeAccountsMovesTransactionsTotalsUnchanged() = runBlocking {
        val a = repo.createAccount("HDFC", "9924", "A")
        val b = repo.createAccount("HDFC", "*9924", "B")
        repo.addManual(10_000, Direction.DEBIT, "X", null, a, TestData.at(TestData.TODAY), null)
        repo.addManual(25_000, Direction.DEBIT, "Y", null, b, TestData.at(TestData.TODAY), null)
        val before = Totals.spend(all())
        repo.mergeAccounts(keepId = a, dropId = b)
        assertTrue(all().all { it.accountId == a })
        assertEquals(before, Totals.spend(all()))
        assertNull(db.accountDao().byId(b))
    }

    @Test
    fun deleteAccountKeepOrDeleteTransactions() = runBlocking {
        val a = repo.createAccount("HDFC", "9924", "A")
        val b = repo.createAccount("AXIS", "9694", "B")
        repo.addManual(10_000, Direction.DEBIT, "X", null, a, TestData.at(TestData.TODAY), null)
        repo.addManual(20_000, Direction.DEBIT, "Y", null, b, TestData.at(TestData.TODAY), null)
        repo.deleteAccount(a, deleteTransactions = false)
        assertEquals(2, all().size)
        assertNull(all().first { it.payeeName == "X" }.accountId)
        repo.deleteAccount(b, deleteTransactions = true)
        assertEquals(listOf("X"), all().map { it.payeeName })
    }

    @Test
    fun learnedPayeeRuleAppliesToNextTransaction() = runBlocking {
        val food = db.categoryDao().bySystemKey(CatKeys.FOOD)!!.id
        val id = repo.addManual(25_000, Direction.DEBIT, "Ravi Stall", null, null, TestData.at(TestData.TODAY), null)
        repo.setCategory(id, food, always = true)
        val next = repo.addManual(1_000, Direction.DEBIT, "RAVI STALL", null, null, TestData.at(TestData.TODAY), null)
        assertEquals(food, db.txnDao().byId(next)!!.categoryId)
    }

    @Test
    fun pausedAccountSkipsCapture() = runBlocking {
        val a = repo.createAccount("AXIS", "9694", "Axis")
        repo.updateAccount(db.accountDao().byId(a)!!.copy(tracked = false))
        assertEquals(IngestResult.Paused, sms("AX-AXISBK-S", axisSms, axisAt))
        assertTrue(all().isEmpty())
    }

    @Test
    fun deleteCategoryMovesToOthers() = runBlocking {
        val food = db.categoryDao().bySystemKey(CatKeys.FOOD)!!
        val id = repo.addManual(1_000, Direction.DEBIT, "Swiggy", null, null, TestData.at(TestData.TODAY), null)
        assertEquals(food.id, db.txnDao().byId(id)!!.categoryId)
        repo.deleteCategory(food)
        assertEquals(db.categoryDao().bySystemKey(CatKeys.OTHERS)!!.id, db.txnDao().byId(id)!!.categoryId)
    }

    @Test
    fun backupRoundTrip() = runBlocking {
        repo.createAccount("HDFC", "9924", "A")
        repo.addManual(1_000, Direction.DEBIT, "Swiggy", null, null, TestData.at(TestData.TODAY), null)
        val snap = repo.snapshot()
        val restored = Backup.decrypt(Backup.encrypt(snap, "pass".toCharArray()), "pass".toCharArray())
        repo.restore(restored)
        assertEquals(snap, repo.snapshot())
    }
}

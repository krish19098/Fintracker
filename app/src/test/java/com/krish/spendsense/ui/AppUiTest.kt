package com.krish.spendsense.ui

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.krish.spendsense.MainActivity
import com.krish.spendsense.domain.TestData
import com.krish.spendsense.ingest.Notifier
import com.krish.spendsense.parse.Direction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Drives the real MainActivity. A tall screen keeps every Insights card (including the Vico chart)
 * composed and drawn; native graphics makes Vico measure and draw for real.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w411dp-h2400dp")
class AppUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val repo get() = app.container.repository

    private fun prepare(txnCount: Int) = runBlocking {
        repo.seedIfEmpty()
        app.container.settings.setOnboardingDone(true)
        app.container.settings.setAppLock(false)
        val a = repo.createAccount("HDFC", "9924", "Main")
        val b = repo.createAccount("CUB", "6578", "Savings")
        val today = LocalDate.now(com.krish.spendsense.parse.ParseUtils.IST)
        val payees = listOf("Swiggy", "Zomato", "Netflix", "Ravi Tea Stall", "Amazon", "Uber", "Rent")
        repeat(txnCount) { i ->
            val day = today.minusDays((i * 3L) % 150)
            repo.addManual(
                amountPaise = (i % 9 + 1) * 4_250L + i,
                direction = if (i % 11 == 0) Direction.CREDIT else Direction.DEBIT,
                payee = payees[i % payees.size], categoryId = null, accountId = if (i % 2 == 0) a else b,
                timestamp = TestData.at(day, (i * 5) % 24, i % 60), note = null,
            )
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun click(text: String) {
        waitForText(text)
        compose.onNode(hasText(text) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    /** Rapid Day/Week/Month/Custom switching (with account filter changes) must never crash. */
    private fun stressInsights() {
        click("Insights")
        val periods = listOf("Day", "Week", "Month", "Custom", "Month", "Day", "Custom", "Week")
        repeat(64) { i ->
            val p = periods[i % periods.size]
            click(p)
            if (p == "Custom") click("OK")
            if (i % 5 == 0 && compose.onAllNodes(hasText("Main") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) {
                click(if (i % 10 == 0) "Main" else "All accounts")
            }
        }
        click("Month")
    }

    @Test
    fun insightsStressWithNoTransactions() {
        prepare(0)
        ActivityScenario.launch(MainActivity::class.java).use {
            stressInsights()
            waitForText("No insights yet")
        }
    }

    @Test
    fun insightsStressWithOneTransaction() {
        prepare(1)
        ActivityScenario.launch(MainActivity::class.java).use {
            stressInsights()
            waitForText("Spend over time")
        }
    }

    @Test
    fun insightsStressWithManyTransactions() {
        prepare(240)
        ActivityScenario.launch(MainActivity::class.java).use {
            stressInsights()
            waitForText("Spend over time")
        }
    }

    @Test
    fun navigatesAcrossAllTabsRepeatedly() {
        prepare(30)
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(5) {
                listOf("Transactions", "Insights", "Settings", "Home").forEach { tab ->
                    click(tab)
                }
            }
            click("Settings")
            click("About")
            waitForText("No crash recorded.")
        }
    }

    @Test
    fun deletedTransactionFromNotificationShowsNotFoundAndIsNotReopenedOnRotation() {
        prepare(0)
        val intent = Intent(app, MainActivity::class.java).putExtra(Notifier.EXTRA_TXN_ID, 999_999L)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitForText("This transaction no longer exists.")
            scenario.recreate()
            waitForText("This transaction no longer exists.")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitForText("Today")
            compose.onNodeWithText("This transaction no longer exists.").assertDoesNotExist()
        }
    }
    @Test
    fun aboutShowsTrainingDataCountsWithoutPersonalDetails() {
        prepare(30)
        runBlocking {
            val shopping = repo.observeCategories().first()
                .first { it.systemKey == com.krish.spendsense.domain.CatKeys.SHOPPING }.id
            val at = TestData.at(LocalDate.now(com.krish.spendsense.parse.ParseUtils.IST))
            repo.addManual(12_345, Direction.DEBIT, "Ravi Tea Stall", shopping, null, at, "birthday gift")
            repo.addManual(67_890, Direction.DEBIT, "Swiggy", shopping, null, at + 1, null)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            click("Settings")
            click("About")
            waitForText("Categorisation data")
            waitForText("Transactions: 32 · spending: 29")
            waitForText("Corrected by you: 2 spending · 2 payees")
            waitForText("Shopping: 2 labels · 2 payees")
            waitForText("Not enough corrected data yet: needs 30 corrected spending transactions (have 2) and 3 categories with 5+ each (have 0).")
            for (secret in listOf("Ravi", "Swiggy", "Zomato", "Netflix", "Amazon", "birthday", "123.45", "678.90", "9924", "6578")) {
                assertTrue(secret, compose.onAllNodes(hasText(secret, substring = true)).fetchSemanticsNodes().isEmpty())
            }
        }
        val log = com.krish.spendsense.PipelineLog.read(app).orEmpty()
        assertFalse(log, "Ravi" in log || "Swiggy" in log)
    }
}

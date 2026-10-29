package com.krish.spendsense.ui

import android.app.Activity
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.krish.spendsense.MainActivity
import com.krish.spendsense.domain.SpendBand
import com.krish.spendsense.domain.SpendState
import com.krish.spendsense.domain.TestData
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.ParseUtils
import com.krish.spendsense.widget.SpendWidget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** Screenshots of the redesign: Home and the widget in each spending band, plus other screens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w393dp-h852dp-xxhdpi")
class DesignScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    /** ₹600 spent today; the daily limit picks the band. */
    private fun prepare(limitRupees: Long) = runBlocking {
        val c = app.container
        c.repository.seedIfEmpty()
        c.settings.setOnboardingDone(true)
        c.settings.setAppLock(false)
        c.settings.setWarnPct(80)
        c.settings.setDailyLimit(limitRupees * 100)
        val a = c.repository.allAccounts().firstOrNull()?.id ?: c.repository.createAccount("HDFC", "9924", "Main").also {
            c.repository.createAccount("CUB", "6578", "Savings")
        }
        val today = LocalDate.now(ParseUtils.IST)
        listOf(250L to "Swiggy", 180L to "Ravi Tea Stall", 170L to "Uber").forEachIndexed { i, (r, p) ->
            c.repository.addManual(r * 100, Direction.DEBIT, p, null, a, TestData.at(today, 9 + i, 0).coerceAtMost(System.currentTimeMillis() - 60_000), null)
        }
    }

    private val bands = listOf(SpendBand.HEALTHY to 2000L, SpendBand.APPROACHING to 1000L, SpendBand.NEAR to 700L, SpendBand.OVER to 500L)

    @Test
    fun homeInEachBand() {
        for ((band, limit) in bands) {
            prepare(limit)
            val state = runBlocking { SpendWidget.currentState(app) }
            assertEquals(band, state.band)
            ActivityScenario.launch(MainActivity::class.java).use { s ->
                waitForText("Today")
                compose.waitForIdle()
                s.onActivity { it.window.decorView.captureRoboImage("build/outputs/roborazzi/design_home_${band.ordinal}_${band.name.lowercase()}.png") }
            }
            runBlocking { app.container.repository.range(0, Long.MAX_VALUE).forEach { app.container.repository.deleteTxn(it.id) } }
        }
    }

    @Test
    fun otherScreens() {
        prepare(1000)
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            for ((tab, wait) in listOf("Transactions" to "Swiggy", "Insights" to "Spend over time", "Settings" to "About")) {
                waitForText(tab)
                compose.onNode(hasText(tab) and hasClickAction()).performClick()
                waitForText(wait)
                compose.waitForIdle()
                s.onActivity { it.window.decorView.captureRoboImage("build/outputs/roborazzi/design_tab_${tab.lowercase()}.png") }
            }
        }
    }

    @Test
    fun widgetInEachBand() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for ((band, limit) in bands) {
            val state = SpendState(60_000, limit * 100, 80)
            assertEquals(band, state.band)
            val frame = FrameLayout(activity)
            activity.setContentView(frame)
            val view = SpendWidget.render(app, state).apply(activity, frame)
            val d = activity.resources.displayMetrics.density
            frame.addView(view, FrameLayout.LayoutParams((340 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            shadowOf(Looper.getMainLooper()).idle()
            view.captureRoboImage("build/outputs/roborazzi/design_widget_${band.ordinal}_${band.name.lowercase()}.png")
        }
    }
}

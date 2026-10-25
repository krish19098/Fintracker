package com.krish.spendsense.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.krish.spendsense.R
import com.krish.spendsense.data.Category
import com.krish.spendsense.domain.TestData
import com.krish.spendsense.ingest.Notifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of the spend-coloured notification layouts, plus checks on the real notifications. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class NotificationLookTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val levels = listOf(0, 49, 60, 75, 90, 100, 101, 150)

    private fun render(rv: RemoteViews): View {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity)
        activity.setContentView(frame)
        val view = rv.apply(activity, frame)
        frame.addView(view, FrameLayout.LayoutParams((360 * activity.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        shadowOf(Looper.getMainLooper()).idle()
        return view
    }

    @Test
    fun layoutsAtEachSpendLevel() {
        val notifier = Notifier(app)
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            for (pct in levels) {
                val (collapsed, expanded) = notifier.spendViews(pct, "₹250 → Ravi Tea Stall", "Today ₹1,840 / ₹2,000", dark)
                val surface = if (dark) SpendStyle.SURFACE_DARK else SpendStyle.SURFACE_LIGHT
                val bg = SpendStyle.background(pct, surface)
                for ((name, rv) in listOf("collapsed" to collapsed, "expanded" to expanded)) {
                    val v = render(rv)
                    assertEquals("$mode $pct $name background", bg, (v.background as ColorDrawable).color)
                    assertEquals(SpendStyle.contentColor(bg), v.findViewById<TextView>(R.id.notif_title).currentTextColor)
                    v.captureRoboImage("build/outputs/roborazzi/notif_${name}_${mode}_${pct.toString().padStart(3, '0')}.png")
                }
            }
        }
    }

    @Test
    fun postsOnSpendChannelWithCoinAndCustomViews() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nm = app.getSystemService(NotificationManager::class.java)
        val notifier = Notifier(app)
        notifier.createChannels()
        SpendStyle.OLD_CHANNELS.forEach { assertNull(nm.getNotificationChannel(it)) }
        listOf(SpendStyle.CH_CALM, SpendStyle.CH_WARNING, SpendStyle.CH_OVER, SpendStyle.CH_SUMMARY).forEach {
            assertNotNull("$it has a sound", nm.getNotificationChannel(it).sound)
        }
        val cat = Category(id = 1, name = "Food", icon = "restaurant", color = 0xFFE76F51)
        val t = TestData.txn(250, payee = "Ravi Tea Stall", id = 7)
        notifier.postTxn(t, "₹250 → Ravi Tea Stall · Today ₹1,840 / ₹2,000", listOf(cat), pct = 92, sounds = true)
        val posted = shadowOf(nm).allNotifications.single()
        assertEquals(SpendStyle.CH_WARNING, posted.channelId)
        assertEquals(SpendStyle.accent(92, SpendStyle.SURFACE_LIGHT), posted.color)
        assertNotNull(posted.getLargeIcon())
        assertNotNull(posted.contentView)
        assertNotNull(posted.bigContentView)
        // Existing actions kept: 1 suggested category + "Add note" with inline reply.
        assertEquals(listOf("Food", "Add note"), posted.actions.map { it.title.toString() })
        assertNotNull(posted.actions.last().remoteInputs)

        notifier.postTxn(t.copy(id = 8), "₹250 → X · Today ₹2,600 / ₹2,000", emptyList(), pct = 130, sounds = false)
        assertEquals(SpendStyle.CH_PLAIN_ALERT, shadowOf(nm).allNotifications.first { it.actions.size == 1 }.channelId)
    }
}

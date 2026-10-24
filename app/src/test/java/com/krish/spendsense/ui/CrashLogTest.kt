package com.krish.spendsense.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.krish.spendsense.CrashLog
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrashLogTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun uncaughtExceptionIsSavedAndPreviousHandlerStillRuns() {
        var previousCalled = false
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> previousCalled = true }
            CrashLog.install(ctx)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
            assertTrue(previousCalled)
            assertTrue(CrashLog.read(ctx)!!.contains("IllegalStateException: boom"))
            CrashLog.clear(ctx)
            assertNull(CrashLog.read(ctx))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}

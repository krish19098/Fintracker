package com.krish.spendsense

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.krish.spendsense.ingest.Work
import com.krish.spendsense.parse.ParseUtils
import kotlinx.coroutines.launch

open class SpendSenseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        startedAtElapsed = SystemClock.elapsedRealtime()
        CrashLog.install(this)
        container = createContainer()
        // Debug builds only, and never with message bodies.
        if (BuildConfig.DEBUG) ParseUtils.debugLog = { Log.d("SpendSense", it) }
        container.notifier.createChannels()
        container.appScope.launch {
            container.repository.seedIfEmpty()
            Work.schedulePeriodicScan(this@SpendSenseApp)
            Work.scheduleAllSummaries(this@SpendSenseApp, container.settings.get())
        }
    }

    protected open fun createContainer() = AppContainer(this)

    companion object {
        /** When this process started; lets the receiver log whether an SMS woke the app. */
        @Volatile
        var startedAtElapsed = 0L
    }
}

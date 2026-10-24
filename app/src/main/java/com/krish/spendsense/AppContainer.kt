package com.krish.spendsense

import android.content.Context
import com.krish.spendsense.data.AppDatabase
import com.krish.spendsense.data.Repository
import com.krish.spendsense.data.SettingsStore
import com.krish.spendsense.ingest.Ingestor
import com.krish.spendsense.ingest.Notifier
import com.krish.spendsense.ingest.SimDetector
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container (no DI framework), created once in [SpendSenseApp].
 * [databaseFactory] lets tests supply an in-memory database.
 */
class AppContainer(context: Context, databaseFactory: (Context) -> AppDatabase = AppDatabase::build) {
    private val app = context.applicationContext

    // A failure in background ingestion (receiver, listener, notification action) must not kill the
    // whole app; it is kept in the crash log instead.
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> CrashLog.record(app, e, "Background error (app kept running)") },
    )
    val settings = SettingsStore(app)
    val database: AppDatabase by lazy { databaseFactory(app) }
    val repository: Repository by lazy { Repository(database, settings::get) }
    val notifier = Notifier(app)
    val simDetector = SimDetector(app)
    val ingestor: Ingestor by lazy { Ingestor(repository, settings, notifier, simDetector) }
}

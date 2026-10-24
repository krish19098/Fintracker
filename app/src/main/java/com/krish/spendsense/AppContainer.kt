package com.krish.spendsense

import android.content.Context
import com.krish.spendsense.data.AppDatabase
import com.krish.spendsense.data.Repository
import com.krish.spendsense.data.SettingsStore
import com.krish.spendsense.ingest.Ingestor
import com.krish.spendsense.ingest.Notifier
import com.krish.spendsense.ingest.SimDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container (no DI framework), created once in [SpendSenseApp]. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app)
    val database: AppDatabase by lazy { AppDatabase.build(app) }
    val repository: Repository by lazy { Repository(database, settings::get) }
    val notifier = Notifier(app)
    val simDetector = SimDetector(app)
    val ingestor: Ingestor by lazy { Ingestor(repository, settings, notifier, simDetector) }
}

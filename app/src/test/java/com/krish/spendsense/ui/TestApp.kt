package com.krish.spendsense.ui

import androidx.room.Room
import androidx.work.testing.WorkManagerTestInitHelper
import com.krish.spendsense.AppContainer
import com.krish.spendsense.SpendSenseApp
import com.krish.spendsense.data.AppDatabase

/** The real app with an in-memory, unencrypted DB (SQLCipher's native library can't load on the JVM). */
class TestApp : SpendSenseApp() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(this)
        super.onCreate()
    }

    override fun createContainer() = AppContainer(this) { ctx ->
        Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
    }
}

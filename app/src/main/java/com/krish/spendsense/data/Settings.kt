package com.krish.spendsense.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.YearMonth

data class AppSettings(
    val dailyLimitPaise: Long = 100_000,
    val warnPct: Int = 80,
    val largeTxnPaise: Long = 500_000,
    /** 0 = derive from daily limit x days in month. */
    val monthlyBudgetPaise: Long = 0,
    val summaryMinute: Int = 21 * 60 + 30,
    val weeklyMinute: Int = 20 * 60,
    val monthlyMinute: Int = 9 * 60,
    val lastScanTs: Long = 0,
    val onboardingDone: Boolean = false,
    val backfillDone: Boolean = false,
    val appLock: Boolean = true,
    val debugCapture: Boolean = false,
    val notifyEachTxn: Boolean = true,
    /** Coin sounds for payment / limit / summary notifications; off = system default sound. */
    val moneySounds: Boolean = true,
    val ownVpas: Set<String> = emptySet(),
    val ccPayees: Set<String> = emptySet(),
    val firedAlerts: Set<String> = emptySet(),
    val listenerConnectedAt: Long = 0,
    val listenerDisconnectedAt: Long = 0,
    /** Ticked Xiaomi reliability steps (autostart, battery, recents lock). */
    val checklist: Set<String> = emptySet(),
    /** Last time the inbox scanner had to catch bank SMS the live receiver never got, and how many (last 7 days). */
    val receiverMissAt: Long = 0,
    val receiverMissCount: Int = 0,
) {
    fun monthlyBudget(month: YearMonth): Long =
        if (monthlyBudgetPaise > 0) monthlyBudgetPaise else dailyLimitPaise * month.lengthOfMonth()
}

private val Context.dataStore by preferencesDataStore(
    "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** DataStore (Preferences) for settings. */
class SettingsStore(private val context: Context) {
    private object K {
        val dailyLimit = longPreferencesKey("daily_limit")
        val warnPct = intPreferencesKey("warn_pct")
        val largeTxn = longPreferencesKey("large_txn")
        val monthlyBudget = longPreferencesKey("monthly_budget")
        val summaryMinute = intPreferencesKey("summary_minute")
        val weeklyMinute = intPreferencesKey("weekly_minute")
        val monthlyMinute = intPreferencesKey("monthly_minute")
        val lastScan = longPreferencesKey("last_scan_ts")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val backfillDone = booleanPreferencesKey("backfill_done")
        val appLock = booleanPreferencesKey("app_lock")
        val debugCapture = booleanPreferencesKey("debug_capture")
        val notifyEachTxn = booleanPreferencesKey("notify_each_txn")
        val moneySounds = booleanPreferencesKey("money_sounds")
        val ownVpas = stringSetPreferencesKey("own_vpas")
        val ccPayees = stringSetPreferencesKey("cc_payees")
        val fired = stringSetPreferencesKey("fired_alerts")
        val listenerUp = longPreferencesKey("listener_up")
        val listenerDown = longPreferencesKey("listener_down")
        val checklist = stringSetPreferencesKey("checklist")
        val receiverMissAt = longPreferencesKey("receiver_miss_at")
        val receiverMissCount = intPreferencesKey("receiver_miss_count")
    }

    private val d = AppSettings()

    // An unreadable settings file falls back to defaults instead of crashing every screen.
    val flow: Flow<AppSettings> = context.dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.map { p: Preferences ->
        AppSettings(
            dailyLimitPaise = p[K.dailyLimit] ?: d.dailyLimitPaise,
            warnPct = p[K.warnPct] ?: d.warnPct,
            largeTxnPaise = p[K.largeTxn] ?: d.largeTxnPaise,
            monthlyBudgetPaise = p[K.monthlyBudget] ?: d.monthlyBudgetPaise,
            summaryMinute = p[K.summaryMinute] ?: d.summaryMinute,
            weeklyMinute = p[K.weeklyMinute] ?: d.weeklyMinute,
            monthlyMinute = p[K.monthlyMinute] ?: d.monthlyMinute,
            lastScanTs = p[K.lastScan] ?: d.lastScanTs,
            onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
            backfillDone = p[K.backfillDone] ?: d.backfillDone,
            appLock = p[K.appLock] ?: d.appLock,
            debugCapture = p[K.debugCapture] ?: d.debugCapture,
            notifyEachTxn = p[K.notifyEachTxn] ?: d.notifyEachTxn,
            moneySounds = p[K.moneySounds] ?: d.moneySounds,
            ownVpas = p[K.ownVpas] ?: d.ownVpas,
            ccPayees = p[K.ccPayees] ?: d.ccPayees,
            firedAlerts = p[K.fired] ?: d.firedAlerts,
            listenerConnectedAt = p[K.listenerUp] ?: 0,
            listenerDisconnectedAt = p[K.listenerDown] ?: 0,
            checklist = p[K.checklist] ?: emptySet(),
            receiverMissAt = p[K.receiverMissAt] ?: 0,
            receiverMissCount = p[K.receiverMissCount] ?: 0,
        )
    }

    suspend fun get(): AppSettings = flow.first()

    private suspend fun set(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setDailyLimit(paise: Long) = set { it[K.dailyLimit] = paise }
    suspend fun setWarnPct(pct: Int) = set { it[K.warnPct] = pct.coerceIn(10, 99) }
    suspend fun setLargeTxn(paise: Long) = set { it[K.largeTxn] = paise }
    suspend fun setMonthlyBudget(paise: Long) = set { it[K.monthlyBudget] = paise }
    suspend fun setSummaryMinute(m: Int) = set { it[K.summaryMinute] = m }
    suspend fun setWeeklyMinute(m: Int) = set { it[K.weeklyMinute] = m }
    suspend fun setMonthlyMinute(m: Int) = set { it[K.monthlyMinute] = m }
    suspend fun setLastScan(ts: Long) = set { p -> if (ts > (p[K.lastScan] ?: 0)) p[K.lastScan] = ts }
    suspend fun setOnboardingDone(v: Boolean) = set { it[K.onboardingDone] = v }
    suspend fun setBackfillDone(v: Boolean) = set { it[K.backfillDone] = v }
    suspend fun setAppLock(v: Boolean) = set { it[K.appLock] = v }
    suspend fun setDebugCapture(v: Boolean) = set { it[K.debugCapture] = v }
    suspend fun setNotifyEachTxn(v: Boolean) = set { it[K.notifyEachTxn] = v }
    suspend fun setMoneySounds(v: Boolean) = set { it[K.moneySounds] = v }
    suspend fun setOwnVpas(v: Set<String>) = set { it[K.ownVpas] = v.map(String::lowercase).toSet() }
    suspend fun setCcPayees(v: Set<String>) = set { it[K.ccPayees] = v }
    /** Forgets which limit alerts were already sent (used by tests to start from a clean day). */
    suspend fun clearFiredAlerts() = set { it.remove(K.fired) }

        /** Counts receiver misses within a rolling 7-day window. */
    suspend fun recordReceiverMiss(at: Long, count: Int) = set { p ->
        val recent = (p[K.receiverMissAt] ?: 0) > at - 7L * 24 * 3600_000
        p[K.receiverMissCount] = (if (recent) p[K.receiverMissCount] ?: 0 else 0) + count
        p[K.receiverMissAt] = at
    }

    suspend fun setChecked(item: String, done: Boolean) =
        set { p -> p[K.checklist] = (p[K.checklist] ?: emptySet()).let { if (done) it + item else it - item } }
    suspend fun setListenerState(connected: Boolean, at: Long) =
        set { if (connected) it[K.listenerUp] = at else it[K.listenerDown] = at }

    /** Records fired alert keys; keys from earlier months are pruned. */
    suspend fun markFired(keys: Collection<String>) = set { p ->
        val month = YearMonth.now().toString()
        val prev = YearMonth.now().minusMonths(1).toString()
        p[K.fired] = ((p[K.fired] ?: emptySet()) + keys).filter { it.contains(month) || it.contains(prev) }.toSet()
    }
}

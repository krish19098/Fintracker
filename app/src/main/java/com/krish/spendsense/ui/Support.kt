package com.krish.spendsense.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krish.spendsense.data.Account
import com.krish.spendsense.data.AppSettings
import com.krish.spendsense.data.Category
import com.krish.spendsense.data.SimProfile
import com.krish.spendsense.ingest.TxnNotificationListener
import com.krish.spendsense.ui.components.LocalContainer

/** Reference data most screens need, collected once per screen. */
data class RefData(val accounts: List<Account>, val categories: List<Category>, val sims: List<SimProfile>, val settings: AppSettings) {
    val accountById = accounts.associateBy { it.id }
    val categoryById = categories.associateBy { it.id }
    val excludedAccounts: Set<Long> = accounts.filterNot { it.includeInTotals }.map { it.id }.toSet()
}

@Composable
fun rememberRefData(): RefData {
    val c = LocalContainer.current
    val accounts by remember { c.repository.observeAccounts() }.collectAsStateWithLifecycle(emptyList())
    val categories by remember { c.repository.observeCategories() }.collectAsStateWithLifecycle(emptyList())
    val sims by remember { c.repository.observeSims() }.collectAsStateWithLifecycle(emptyList())
    val settings by remember { c.settings.flow }.collectAsStateWithLifecycle(AppSettings(onboardingDone = true))
    return RefData(accounts, categories, sims, settings)
}

/** Permission checks and deep links (with Xiaomi / HyperOS specifics and safe fallbacks). */
object Perms {
    private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    val SMS = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
    const val PHONE = Manifest.permission.READ_PHONE_STATE
    const val POST = "android.permission.POST_NOTIFICATIONS"

    fun sms(ctx: Context) = SMS.all { has(ctx, it) }
    fun phone(ctx: Context) = has(ctx, PHONE)
    fun postNotifications(ctx: Context) = Build.VERSION.SDK_INT < 33 || has(ctx, POST)
    fun listener(ctx: Context) = TxnNotificationListener.isEnabled(ctx)
    fun batteryUnrestricted(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    private fun start(ctx: Context, vararg intents: Intent): Boolean = intents.any { i ->
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: Exception) {
            false
        }
    }

    private fun appDetails(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    fun openAppSettings(ctx: Context) = start(ctx, appDetails(ctx))

    fun openListenerSettings(ctx: Context) = start(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), appDetails(ctx))

    fun openAutostart(ctx: Context) = start(
        ctx,
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        appDetails(ctx),
    )

    @Suppress("BatteryLife")
    fun openBattery(ctx: Context) = start(
        ctx,
        Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", ctx.packageName).putExtra("package_label", "SpendSense"),
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
        appDetails(ctx),
    )
}

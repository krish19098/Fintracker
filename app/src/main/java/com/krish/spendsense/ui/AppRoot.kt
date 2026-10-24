package com.krish.spendsense.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.krish.spendsense.AppContainer
import com.krish.spendsense.data.SimChange
import com.krish.spendsense.ingest.Notifier
import com.krish.spendsense.ui.components.LocalContainer
import com.krish.spendsense.ui.screens.AboutScreen
import com.krish.spendsense.ui.screens.AccountDetailScreen
import com.krish.spendsense.ui.screens.AccountEditScreen
import com.krish.spendsense.ui.screens.AccountsScreen
import com.krish.spendsense.ui.screens.AddTxnScreen
import com.krish.spendsense.ui.screens.BackupScreen
import com.krish.spendsense.ui.screens.BudgetsScreen
import com.krish.spendsense.ui.screens.CategoriesScreen
import com.krish.spendsense.ui.screens.HomeScreen
import com.krish.spendsense.ui.screens.InsightsScreen
import com.krish.spendsense.ui.screens.NotificationTimesScreen
import com.krish.spendsense.ui.screens.OnboardingScreen
import com.krish.spendsense.ui.screens.PermissionsScreen
import com.krish.spendsense.ui.screens.ReviewScreen
import com.krish.spendsense.ui.screens.SettingsScreen
import com.krish.spendsense.ui.screens.SimsScreen
import com.krish.spendsense.ui.screens.SubscriptionsScreen
import com.krish.spendsense.ui.screens.TransactionsScreen
import com.krish.spendsense.ui.screens.TxnDetailScreen
import kotlinx.coroutines.launch

/** What a notification asked the app to open. */
data class LaunchRequest(val txnId: Long?, val categorySheet: Boolean, val open: String?) {
    companion object {
        fun from(intent: Intent?): LaunchRequest? {
            intent ?: return null
            val txn = intent.getLongExtra(Notifier.EXTRA_TXN_ID, -1).takeIf { it > 0 }
            val open = intent.getStringExtra(Notifier.EXTRA_OPEN)
            if (txn == null && open == null) return null
            return LaunchRequest(txn, intent.getBooleanExtra(Notifier.EXTRA_CATEGORY_SHEET, false), open)
        }
    }
}

object Routes {
    const val HOME = "home"
    const val TXNS = "txns"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"
    const val REVIEW = "review"
    const val SIMS = "sims"
    const val ACCOUNTS = "accounts"
    const val BUDGETS = "budgets"
    const val CATEGORIES = "categories"
    const val NOTIF_TIMES = "notifTimes"
    const val PERMISSIONS = "permissions"
    const val BACKUP = "backup"
    const val ABOUT = "about"
    const val SUBSCRIPTIONS = "subscriptions"
    fun txn(id: Long, sheet: Boolean = false) = "txn/$id?sheet=$sheet"
    fun add(unparsedId: Long = -1) = "add?unparsed=$unparsedId"
    fun account(id: Long) = "account/$id"
    fun accountEdit(id: Long) = "accountEdit/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Filled.Home),
    Tab(Routes.TXNS, "Transactions", Icons.AutoMirrored.Filled.ReceiptLong),
    Tab(Routes.INSIGHTS, "Insights", Icons.Filled.Insights),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(container: AppContainer, locked: Boolean, launch: LaunchRequest?, onUnlock: () -> Unit, onLaunchHandled: () -> Unit) {
    CompositionLocalProvider(LocalContainer provides container) {
        val settings by remember { container.settings.flow }.collectAsStateWithLifecycle(null)
        val s = settings
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                s == null -> Unit
                locked -> LockScreen(onUnlock)
                !s.onboardingDone -> OnboardingScreen()
                else -> MainNav(launch, onLaunchHandled)
            }
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.size(16.dp))
        Text("SpendSense is locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(24.dp))
        Button(onClick = onUnlock) { Text("Unlock") }
    }
}

@Composable
private fun MainNav(launch: LaunchRequest?, onLaunchHandled: () -> Unit) {
    val nav = rememberNavController()
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var swaps by remember { mutableStateOf<List<SimChange.Swapped>>(emptyList()) }

    LaunchedEffect(Unit) { swaps = c.repository.refreshSims(c.simDetector.current()) }
    LaunchedEffect(launch) {
        val l = launch ?: return@LaunchedEffect
        when {
            l.txnId != null -> nav.navigate(Routes.txn(l.txnId, l.categorySheet))
            l.open == Notifier.OPEN_INSIGHTS -> nav.navigateTab(Routes.INSIGHTS)
            l.open == Notifier.OPEN_ACCOUNTS -> nav.navigate(Routes.ACCOUNTS)
            else -> nav.navigateTab(Routes.HOME)
        }
        onLaunchHandled()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.navigateTab(t.route) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { HomeScreen(nav) }
                composable(Routes.TXNS) { TransactionsScreen(nav) }
                composable(Routes.INSIGHTS) { InsightsScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.REVIEW) { ReviewScreen(nav) }
                composable(
                    "txn/{id}?sheet={sheet}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("sheet") { type = NavType.BoolType; defaultValue = false }),
                ) { TxnDetailScreen(nav, it.arguments!!.getLong("id"), it.arguments!!.getBoolean("sheet")) }
                composable("add?unparsed={unparsed}", arguments = listOf(navArgument("unparsed") { type = NavType.LongType; defaultValue = -1L })) {
                    AddTxnScreen(nav, it.arguments!!.getLong("unparsed").takeIf { id -> id > 0 })
                }
                composable("account/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    AccountDetailScreen(nav, it.arguments!!.getLong("id"))
                }
                composable("accountEdit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    AccountEditScreen(nav, it.arguments!!.getLong("id"))
                }
                composable(Routes.SIMS) { SimsScreen(nav) }
                composable(Routes.ACCOUNTS) { AccountsScreen(nav) }
                composable(Routes.BUDGETS) { BudgetsScreen(nav) }
                composable(Routes.CATEGORIES) { CategoriesScreen(nav) }
                composable(Routes.NOTIF_TIMES) { NotificationTimesScreen(nav) }
                composable(Routes.PERMISSIONS) { PermissionsScreen(nav) }
                composable(Routes.BACKUP) { BackupScreen(nav) }
                composable(Routes.ABOUT) { AboutScreen(nav) }
                composable(Routes.SUBSCRIPTIONS) { SubscriptionsScreen(nav) }
            }
        }
    }

    swaps.firstOrNull()?.let { swap ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("New SIM detected in slot ${swap.sim.slot}") },
            text = {
                Text(
                    "Is this a new number?\n\n• Keep accounts linked: your accounts on \"${swap.old.label}\" move to this SIM.\n" +
                        "• New number: old accounts stay as they are and the old SIM is marked inactive.\n\nNo history is lost either way.",
                )
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { c.repository.resolveSwap(swap, keepLinked = true); swaps = swaps.drop(1) } }) { Text("Keep accounts linked") }
            },
            dismissButton = {
                TextButton(onClick = { scope.launch { c.repository.resolveSwap(swap, keepLinked = false); swaps = swaps.drop(1) } }) { Text("New number, set up fresh") }
            },
        )
    }
}

fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

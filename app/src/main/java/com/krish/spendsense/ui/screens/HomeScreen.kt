package com.krish.spendsense.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.domain.InsightsEngine
import com.krish.spendsense.domain.Money
import com.krish.spendsense.domain.Totals
import com.krish.spendsense.ingest.Backfill
import com.krish.spendsense.parse.ParseUtils
import com.krish.spendsense.ui.MoodCoin
import com.krish.spendsense.ui.Perms
import com.krish.spendsense.ui.SpendStyle
import com.krish.spendsense.ui.Routes
import com.krish.spendsense.ui.components.EmptyState
import com.krish.spendsense.ui.components.InfoCard
import com.krish.spendsense.ui.components.LocalContainer
import com.krish.spendsense.ui.components.ProgressRing
import com.krish.spendsense.ui.components.ScreenScaffold
import com.krish.spendsense.ui.components.SectionTitle
import com.krish.spendsense.ui.components.TxnRow
import com.krish.spendsense.ui.rememberRefData
import com.krish.spendsense.ui.theme.AmountLarge
import com.krish.spendsense.ui.theme.Bad
import com.krish.spendsense.ui.theme.Good
import com.krish.spendsense.ui.theme.Warn
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun HomeScreen(nav: NavController) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val ref = rememberRefData()
    val today = LocalDate.now(ParseUtils.IST)
    val month = YearMonth.from(today)
    val monthRows by remember(month, today) { c.repository.observeRange(Dates.startOf(month.atDay(1)), Dates.endOf(today)) }
        .collectAsStateWithLifecycle(emptyList())
    val review by remember { c.repository.observeNeedsReview() }.collectAsStateWithLifecycle(emptyList())
    val unparsed by remember { c.repository.observeUnparsed() }.collectAsStateWithLifecycle(emptyList())
    val backfill by remember { Backfill.observe(ctx) }.collectAsStateWithLifecycle(null)
    var accountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var permsOk by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        permsOk = Perms.sms(ctx) && Perms.listener(ctx)
        onPauseOrDispose {}
    }

    val account = accountId?.let { ref.accountById[it] }
    val rows = if (accountId == null) monthRows else monthRows.filter { it.accountId == accountId }
    val excluded = if (accountId == null) ref.excludedAccounts else emptySet()
    val todayRows = rows.filter { Dates.date(it.timestamp) == today }
    val spentToday = Totals.spend(todayRows, excluded)
    val limit = account?.dailyLimitPaise ?: ref.settings.dailyLimitPaise
    val fraction = if (limit > 0) spentToday.toFloat() / limit else 0f
    val ringColor = when {
        fraction >= 1f -> Bad
        fraction * 100 >= ref.settings.warnPct -> Warn
        else -> Good
    }
    val forecast = InsightsEngine(ref.categories, excluded).forecast(rows, today, ref.settings.monthlyBudget(month))
    val reviewCount = review.size + unparsed.size

    ScreenScaffold(title = "SpendSense") { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (!permsOk) item {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).clickable { nav.navigate(Routes.PERMISSIONS) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(12.dp))
                    Text("SMS or notification access is off — payments may be missed. Tap to fix.", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            backfill?.takeIf { it.running }?.let { b ->
                item {
                    InfoCard {
                        Text("Reading last 180 days of bank SMS… ${b.done}/${b.total}")
                        Spacer(Modifier.size(8.dp))
                        LinearProgressIndicator(progress = { if (b.total > 0) b.done.toFloat() / b.total else 0f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (ref.accounts.isNotEmpty()) item {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = accountId == null, onClick = { accountId = null }, label = { Text("All") }) }
                    items(ref.accounts, key = { it.id }) { a ->
                        FilterChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = { Text(a.label) })
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProgressRing(fraction, ringColor, Modifier.size(220.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            MoodCoin(SpendStyle.mood(SpendStyle.percent(spentToday, limit)), 56.dp)
                            Text("Today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Money.short(spentToday), style = AmountLarge)
                            Text("of ${Money.short(limit)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                InfoCard(onClick = { nav.navigate(Routes.INSIGHTS) }) {
                    Text("This month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Money.short(forecast.monthToDate), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "On pace for ${Money.short(forecast.projected)} · budget ${Money.short(forecast.budget)}",
                        color = if (forecast.projected > forecast.budget) Bad else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (reviewCount > 0) item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    AssistChip(
                        onClick = { nav.navigate(Routes.REVIEW) },
                        label = { Text("Needs review ($reviewCount)") },
                        leadingIcon = { Icon(Icons.Filled.Inbox, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
                    )
                }
            }
            item { SectionTitle("Recent") }
            val recent = rows.sortedByDescending { it.timestamp }.take(5)
            if (recent.isEmpty()) item {
                EmptyState(
                    Icons.AutoMirrored.Filled.ReceiptLong, "No transactions yet",
                    "Payments appear here automatically when your bank SMS or GPay/Paytm notification arrives.",
                    "Add one manually",
                ) { nav.navigate(Routes.add()) }
            }
            items(recent, key = { it.id }) { t ->
                TxnRow(t, t.categoryId?.let { ref.categoryById[it] }, t.accountId?.let { ref.accountById[it] }) { nav.navigate(Routes.txn(t.id)) }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

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
import com.krish.spendsense.ingest.Backfill
import com.krish.spendsense.ui.LocalToday
import com.krish.spendsense.ui.MoodCoin
import com.krish.spendsense.ui.SpendPalettes
import com.krish.spendsense.ui.navigateTab
import com.krish.spendsense.ui.components.PillChip
import com.krish.spendsense.domain.SpendState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import java.time.YearMonth

@Composable
fun HomeScreen(nav: NavController) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val ref = rememberRefData()
    val today = LocalToday.current
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
    // Same calculation as the app theme and widget; an account chip uses that account's own limit if set.
    val limit = account?.dailyLimitPaise ?: ref.settings.dailyLimitPaise
    val state = SpendState.of(rows, today, limit, ref.settings.warnPct, excluded)
    val forecast = InsightsEngine(ref.categories, excluded).forecast(rows, today, ref.settings.monthlyBudget(month))
    val reviewCount = review.size + unparsed.size

    ScreenScaffold(title = "SpendSense") { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (!permsOk) item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.errorContainer).clickable { nav.navigate(Routes.PERMISSIONS) }.padding(16.dp),
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
            item { TodayHero(state) }
            if (ref.accounts.isNotEmpty()) item {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { PillChip(selected = accountId == null, onClick = { accountId = null }, label = "All") }
                    items(ref.accounts, key = { it.id }) { a ->
                        PillChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = a.label)
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
            item {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Recent", Modifier.weight(1f))
                    TextButton(onClick = { nav.navigateTab(Routes.TXNS) }, modifier = Modifier.padding(top = 12.dp)) { Text("View all") }
                }
            }
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

/**
 * Today's hero card (emerald gradient in the healthy band, warming to amber / red with spending):
 * amount, limit, status and the progress ring with the mood coin.
 */
@Composable
private fun TodayHero(state: SpendState) {
    val p = SpendPalettes.of(state.band, isSystemInDarkTheme())
    val start by animateColorAsState(Color(p.heroStart), tween(600), label = "heroStart")
    val end by animateColorAsState(Color(p.heroEnd), tween(600), label = "heroEnd")
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).shadow(6.dp, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large).background(Brush.linearGradient(listOf(start, end))).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Today", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(Money.short(state.spentPaise), style = AmountLarge, color = Color.White)
                Text(
                    if (state.hasLimit) "of ${Money.short(state.limitPaise)} · ${state.percent}%" else "No daily limit",
                    style = MaterialTheme.typography.bodyMedium, color = Color.White,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    SpendPalettes.status(state),
                    style = MaterialTheme.typography.labelLarge, color = Color.White,
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            ProgressRing(state.fraction, Color.White, Modifier.size(132.dp), stroke = 12.dp, track = Color.White.copy(alpha = 0.25f)) {
                MoodCoin(SpendStyle.mood(state.percent), 68.dp)
            }
        }
    }
}

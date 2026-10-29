@file:OptIn(ExperimentalMaterial3Api::class)

package com.krish.spendsense.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.krish.spendsense.data.Txn
import com.krish.spendsense.domain.Categorizer
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.domain.InsightAction
import com.krish.spendsense.domain.InsightsEngine
import com.krish.spendsense.domain.Money
import com.krish.spendsense.domain.Recurring
import com.krish.spendsense.domain.TimeBand
import com.krish.spendsense.domain.Totals
import com.krish.spendsense.parse.ParseUtils
import com.krish.spendsense.ui.LocalToday
import com.krish.spendsense.ui.RefData
import com.krish.spendsense.ui.Routes
import com.krish.spendsense.ui.components.DonutChart
import com.krish.spendsense.ui.components.EmptyState
import com.krish.spendsense.ui.components.InfoCard
import com.krish.spendsense.ui.components.LocalContainer
import com.krish.spendsense.ui.components.PillChip
import com.krish.spendsense.ui.components.ScreenScaffold
import com.krish.spendsense.ui.components.SectionTitle
import com.krish.spendsense.ui.components.colorOf
import com.krish.spendsense.ui.rememberRefData
import com.krish.spendsense.ui.theme.Bad
import com.krish.spendsense.ui.theme.Good
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class Period(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), CUSTOM("Custom") }

@Composable
fun InsightsScreen(nav: NavController) {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    val today = LocalToday.current
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    var customFrom by rememberSaveable { mutableStateOf(today.minusDays(29).toEpochDay()) }
    var customTo by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var accountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickRange by remember { mutableStateOf(false) }

    val (from, to) = when (period) {
        Period.DAY -> today to today
        Period.WEEK -> today.minusDays(6) to today
        Period.MONTH -> YearMonth.from(today).atDay(1) to today
        Period.CUSTOM -> Dates.clampRange(LocalDate.ofEpochDay(customFrom), LocalDate.ofEpochDay(customTo), today)
    }
    // Four full months back feed the trend rules (median, recurring, month-over-month).
    val loadFrom = minOf(from, YearMonth.from(today).minusMonths(4).atDay(1))
    val all by remember(loadFrom, today) { c.repository.observeRange(Dates.startOf(loadFrom), Dates.endOf(today)) }.collectAsStateWithLifecycle(emptyList())
    // Computed only when inputs change, not on every recomposition while switching periods.
    val scoped = remember(all, accountId) { if (accountId == null) all else all.filter { it.accountId == accountId } }
    val excluded = if (accountId == null) ref.excludedAccounts else emptySet()
    val rows = remember(scoped, from, to) { scoped.filter { Dates.date(it.timestamp) in from..to } }
    val engine = remember(ref.categories, excluded) { InsightsEngine(ref.categories, excluded) }
    val dailyLimit = accountId?.let { ref.accountById[it]?.dailyLimitPaise } ?: ref.settings.dailyLimitPaise
    val monthlyBudget = ref.settings.monthlyBudget(YearMonth.from(today))
    val cards = remember(engine, scoped, today, dailyLimit, monthlyBudget) { engine.all(scoped, today, dailyLimit, monthlyBudget) }
    val recurring = remember(engine, scoped, today) { engine.recurring(scoped, today) }

    ScreenScaffold("Insights") { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Period.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = period == p, onClick = { period = p; if (p == Period.CUSTOM) pickRange = true },
                            shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                        ) { Text(p.label) }
                    }
                }
            }
            if (period == Period.CUSTOM) item {
                TextButton(onClick = { pickRange = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("${Dates.day(from)} – ${Dates.day(to)}") }
            }
            if (ref.accounts.isNotEmpty()) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { PillChip(accountId == null, { accountId = null }, "All accounts") }
                    items(ref.accounts, key = { it.id }) { a -> PillChip(accountId == a.id, { accountId = a.id }, a.label) }
                }
            }
            if (all.isEmpty()) {
                item { EmptyState(Icons.Filled.Insights, "No insights yet", "Insights appear once a few payments are captured. Run the 180-day backfill from Settings to start right away.") }
                return@LazyColumn
            }
            item { IncomeVsSpendCard(rows, excluded) }
            item { CategoryCard(rows, ref, excluded) }
            item { SpendBarsCard(rows, from, to, period, excluded) }
            item { TopPayeesCard(rows, excluded) }
            if (accountId == null && ref.accounts.size > 1) item { AccountSplitCard(all, from, to, ref) }
            if (cards.isNotEmpty()) item { SectionTitle("Insights") }
            items(cards) { i ->
                InfoCard {
                    Text(i.title, style = MaterialTheme.typography.titleMedium)
                    Text(i.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    i.action?.let { a ->
                        TextButton(onClick = {
                            when (a) {
                                is InsightAction.SetBudget -> scope.launch { c.repository.setCategoryBudget(a.categoryId, a.paise) }
                                InsightAction.OpenSubscriptions -> nav.navigate(Routes.SUBSCRIPTIONS)
                            }
                        }) { Text(i.actionLabel ?: "Open") }
                    }
                }
            }
            if (recurring.isNotEmpty()) {
                item { SectionTitle("Subscriptions & recurring · ${Money.short(recurring.sumOf { it.monthlyPaise })}/mo") }
                items(recurring.take(4)) { RecurringRow(it) }
                if (recurring.size > 4) item { TextButton(onClick = { nav.navigate(Routes.SUBSCRIPTIONS) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("See all ${recurring.size}") } }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    if (pickRange) {
        // DateRangePickerState rejects a start after the end, so seed it with the normalised range.
        val (initFrom, initTo) = Dates.clampRange(LocalDate.ofEpochDay(customFrom), LocalDate.ofEpochDay(customTo), today)
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = initFrom.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            initialSelectedEndDateMillis = initTo.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickRange = false },
            confirmButton = {
                TextButton(onClick = {
                    val s = state.selectedStartDateMillis
                    val e = state.selectedEndDateMillis ?: s
                    if (s != null && e != null) {
                        val (f, t) = Dates.clampRange(
                            Instant.ofEpochMilli(s).atZone(ZoneOffset.UTC).toLocalDate(),
                            Instant.ofEpochMilli(e).atZone(ZoneOffset.UTC).toLocalDate(),
                            today,
                        )
                        customFrom = f.toEpochDay()
                        customTo = t.toEpochDay()
                    }
                    pickRange = false
                }) { Text("OK") }
            },
        ) { DateRangePicker(state, Modifier.height(480.dp)) }
    }
}

@Composable
private fun IncomeVsSpendCard(rows: List<Txn>, excluded: Set<Long>) {
    val income = Totals.income(rows, excluded)
    val spend = Totals.spend(rows, excluded)
    InfoCard {
        Text("Income vs Spend", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        val max = maxOf(income, spend, 1)
        BarLine("Income", income, max, Good)
        BarLine("Spend", spend, max, Bad)
        Text(
            if (income >= spend) "You kept ${Money.short(income - spend)}" else "Spent ${Money.short(spend - income)} more than came in",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BarLine(label: String, value: Long, max: Long, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), maxLines = 1)
        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth((value.toFloat() / max).coerceIn(0f, 1f)).fillMaxHeight().background(color))
        }
        Text(Money.short(value), Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CategoryCard(rows: List<Txn>, ref: RefData, excluded: Set<Long>) {
    val split = Totals.byCategory(rows, excluded).entries.sortedByDescending { it.value }
    val total = split.sumOf { it.value }
    InfoCard {
        Text("By category", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            DonutChart(split.map { (id, v) -> (id?.let { ref.categoryById[it] }?.let { colorOf(it.color) } ?: MaterialTheme.colorScheme.outline) to v }, Modifier.size(140.dp)) {
                Text(Money.short(total), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (split.isEmpty()) Text("No spending in this period", color = MaterialTheme.colorScheme.onSurfaceVariant)
                split.take(6).forEach { (id, v) ->
                    val cat = id?.let { ref.categoryById[it] }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(cat?.let { colorOf(it.color) } ?: MaterialTheme.colorScheme.outline))
                        Spacer(Modifier.width(6.dp))
                        Text(cat?.name ?: "Uncategorised", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        Text("${if (total > 0) v * 100 / total else 0}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private val XLabelsKey = ExtraStore.Key<List<String>>()

/** Reads labels from the model being drawn; Vico throws if a formatter returns an empty string. */
private val XLabels = CartesianValueFormatter { context, x, _ ->
    context.model.extraStore.getOrNull(XLabelsKey)?.getOrNull(x.toInt())?.takeIf { it.isNotEmpty() } ?: x.toInt().toString()
}

private val YLabels = CartesianValueFormatter { _, y, _ -> Money.short(y.toLong() * 100) }

/** Spend over time: by time of day for a single day, by day up to ~2 months, by month beyond. */
@Composable
private fun SpendBarsCard(rows: List<Txn>, from: LocalDate, to: LocalDate, period: Period, excluded: Set<Long>) {
    val days = ChronoUnit.DAYS.between(from, to).toInt() + 1
    val (labels, values) = when {
        period == Period.DAY -> TimeBand.entries.map { it.label.take(5) } to TimeBand.entries.map { b ->
            rows.filter { TimeBand.of(Dates.zoned(it.timestamp).hour) == b }.sumOf { Totals.spendDelta(it, excluded) }
        }
        days <= 62 -> {
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
            val byDay = rows.groupBy { Dates.date(it.timestamp) }
            (0 until days).map { fmt.format(from.plusDays(it.toLong())) } to (0 until days).map { i ->
                byDay[from.plusDays(i.toLong())].orEmpty().sumOf { Totals.spendDelta(it, excluded) }
            }
        }
        else -> {
            val fmt = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)
            val months = generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(to) }.toList()
            val byMonth = rows.groupBy { YearMonth.from(Dates.date(it.timestamp)) }
            months.map { fmt.format(it) } to months.map { m -> byMonth[m].orEmpty().sumOf { Totals.spendDelta(it, excluded) } }
        }
    }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(labels, values) {
        // Vico rejects an empty series; labels go into the same model so they always match its x values.
        if (values.isEmpty()) return@LaunchedEffect
        producer.runTransaction {
            columnSeries { series(values.map { it.coerceAtLeast(0) / 100 }) }
            extras { it[XLabelsKey] = labels }
        }
    }
    InfoCard {
        Text("Spend over time", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        ProvideVicoTheme(rememberM3VicoTheme()) {
            CartesianChartHost(
                rememberCartesianChart(
                    rememberColumnCartesianLayer(),
                    startAxis = VerticalAxis.rememberStart(valueFormatter = YLabels),
                    bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = XLabels),
                ),
                producer,
                Modifier.fillMaxWidth().height(200.dp),
            )
        }
    }
}

@Composable
private fun TopPayeesCard(rows: List<Txn>, excluded: Set<Long>) {
    val top = rows.filter { Totals.spendDelta(it, excluded) > 0 }
        .groupBy { Categorizer.payeeKey(it.payeeName, it.payeeVpa) ?: it.displayPayee }
        .map { (_, v) -> v.maxBy { it.timestamp }.displayPayee to v }
        .sortedByDescending { (_, v) -> v.sumOf { it.amountPaise } }.take(5)
    if (top.isEmpty()) return
    InfoCard {
        Text("Top payees", style = MaterialTheme.typography.titleMedium)
        top.forEach { (name, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(name, Modifier.weight(1f), maxLines = 1)
                Text("${v.size}× · ${Money.short(v.sumOf { it.amountPaise })}", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Per-account comparison: spend this period vs the previous period of the same length. */
@Composable
private fun AccountSplitCard(all: List<Txn>, from: LocalDate, to: LocalDate, ref: RefData) {
    val len = ChronoUnit.DAYS.between(from, to) + 1
    val prevFrom = from.minusDays(len)
    val prevTo = from.minusDays(1)
    val now = all.filter { Dates.date(it.timestamp) in from..to }
    val prev = all.filter { Dates.date(it.timestamp) in prevFrom..prevTo }
    val per = ref.accounts.map { a -> Triple(a, Totals.spend(now.filter { it.accountId == a.id }), Totals.spend(prev.filter { it.accountId == a.id })) }
    val max = maxOf(per.maxOfOrNull { it.second } ?: 0, 1)
    InfoCard {
        Text("By account", style = MaterialTheme.typography.titleMedium)
        per.forEach { (a, cur, before) ->
            BarLine(a.label, cur, max, colorOf(a.color))
            if (before > 0) Text(
                "${if (cur >= before) "↑" else "↓"} ${kotlin.math.abs(cur - before) * 100 / before}% vs previous period",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 96.dp),
            )
        }
    }
}

@Composable
fun RecurringRow(r: Recurring) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(Icons.Filled.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.payee, maxLines = 1)
            Text(
                "${if (r.periodDays <= 8) "Weekly" else "Monthly"} · next ~${Dates.day(r.nextExpected)} · seen ${r.count}×",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(Money.short(r.amountPaise), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SubscriptionsScreen(nav: NavController) {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val today = LocalDate.now(ParseUtils.IST)
    val from = YearMonth.from(today).minusMonths(6).atDay(1)
    val all by remember { c.repository.observeRange(Dates.startOf(from), Dates.endOf(today)) }.collectAsStateWithLifecycle(emptyList())
    val list = InsightsEngine(ref.categories, ref.excludedAccounts).recurring(all, today)
    ScreenScaffold("Subscriptions & recurring", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                InfoCard {
                    Text("Monthly total", style = MaterialTheme.typography.labelLarge)
                    Text(Money.short(list.sumOf { it.monthlyPaise }), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
            if (list.isEmpty()) item { EmptyState(Icons.Filled.Repeat, "No recurring payments found", "Payments to the same payee every week or month show up here.") }
            items(list) { RecurringRow(it) }
        }
    }
}

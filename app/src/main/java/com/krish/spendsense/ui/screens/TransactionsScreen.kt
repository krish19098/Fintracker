@file:OptIn(ExperimentalMaterial3Api::class)

package com.krish.spendsense.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.krish.spendsense.data.CategoryKind
import com.krish.spendsense.data.Txn
import com.krish.spendsense.domain.Categorizer
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.domain.Money
import com.krish.spendsense.domain.Totals
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.ParseUtils
import com.krish.spendsense.parse.TxnApp
import com.krish.spendsense.ui.RefData
import com.krish.spendsense.ui.Routes
import com.krish.spendsense.ui.components.CategorySheet
import com.krish.spendsense.ui.components.EmptyState
import com.krish.spendsense.ui.components.LocalContainer
import com.krish.spendsense.ui.components.pillChipColors
import androidx.compose.foundation.shape.CircleShape
import com.krish.spendsense.ui.components.ScreenScaffold
import com.krish.spendsense.ui.components.TxnRow
import com.krish.spendsense.ui.rememberRefData
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth

enum class RangeOpt(val label: String) { TODAY("Today"), D7("7 days"), D30("30 days"), MONTH("This month"), D90("90 days"), YEAR("1 year"), ALL("All time") }

enum class AppOpt(val label: String) { GPAY("GPay"), PAYTM("Paytm"), OTHER("Other") }

@Serializable
data class TxnFilter(
    val range: RangeOpt = RangeOpt.D30,
    val accountId: Long? = null,
    val simSlot: Int? = null,
    val app: AppOpt? = null,
    val categoryId: Long? = null,
    val direction: Direction? = null,
    /** null = all, true = only excluded, false = hide excluded. */
    val excluded: Boolean? = null,
    val query: String = "",
) {
    fun bounds(today: LocalDate): Pair<Long, Long> {
        val from = when (range) {
            RangeOpt.TODAY -> today
            RangeOpt.D7 -> today.minusDays(6)
            RangeOpt.D30 -> today.minusDays(29)
            RangeOpt.MONTH -> YearMonth.from(today).atDay(1)
            RangeOpt.D90 -> today.minusDays(89)
            RangeOpt.YEAR -> today.minusYears(1)
            RangeOpt.ALL -> LocalDate.of(2000, 1, 1)
        }
        return Dates.startOf(from) to Dates.endOf(today)
    }

    fun matches(t: Txn, ref: RefData): Boolean {
        if (accountId != null && t.accountId != accountId) return false
        if (simSlot != null && t.simSlot != simSlot) return false
        when (app) {
            AppOpt.GPAY -> if (t.app != TxnApp.GPAY) return false
            AppOpt.PAYTM -> if (t.app != TxnApp.PAYTM) return false
            AppOpt.OTHER -> if (t.app == TxnApp.GPAY || t.app == TxnApp.PAYTM) return false
            null -> Unit
        }
        if (categoryId != null && t.categoryId != categoryId) return false
        if (direction != null && t.direction != direction) return false
        if (excluded != null && (t.excludedReason != null) != excluded) return false
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            val hay = listOfNotNull(t.payeeName, t.payeeVpa, t.note, t.upiRef, t.categoryId?.let { ref.categoryById[it]?.name }, Money.format(t.amountPaise))
                .joinToString(" ").lowercase()
            if (!hay.contains(q)) return false
        }
        return true
    }
}

private val filterSaver = Saver<TxnFilter, String>(save = { Json.encodeToString(TxnFilter.serializer(), it) }, restore = { Json.decodeFromString(TxnFilter.serializer(), it) })

@Composable
fun TransactionsScreen(nav: NavController) {
    ScreenScaffold(
        title = "Transactions",
        fab = { FloatingActionButton(onClick = { nav.navigate(Routes.add()) }) { Icon(Icons.Filled.Add, contentDescription = "Add cash or manual transaction") } },
    ) { padding -> TxnBrowser(nav, fixedAccountId = null, modifier = Modifier.padding(padding)) }
}

/**
 * Searchable, filterable list grouped by day with day totals. Reused by the account detail
 * screen with [fixedAccountId], so both share the same filters.
 */
@Composable
fun TxnBrowser(nav: NavController, fixedAccountId: Long?, modifier: Modifier = Modifier, header: LazyListScope.() -> Unit = {}) {
    val c = LocalContainer.current
    val ref = rememberRefData()
    var filter by rememberSaveable(stateSaver = filterSaver) { mutableStateOf(TxnFilter(accountId = fixedAccountId)) }
    var recat by remember { mutableStateOf<Txn?>(null) }
    val today = LocalDate.now(ParseUtils.IST)
    val (from, to) = filter.bounds(today)
    val all by remember(from, to) { c.repository.observeRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val rows = all.filter { filter.matches(it, ref) }
    val byDay = rows.groupBy { Dates.date(it.timestamp) }

    LazyColumn(modifier.fillMaxSize()) {
        header()
        item {
            OutlinedTextField(
                value = filter.query, onValueChange = { filter = filter.copy(query = it) },
                placeholder = { Text("Search payee, note, amount, ref") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.query.isNotEmpty()) IconButton(onClick = { filter = filter.copy(query = "") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { ChipMenu(filter.range.label, true, RangeOpt.entries, { it.label }) { filter = filter.copy(range = it) } }
                if (fixedAccountId == null) item {
                    ChipMenu(filter.accountId?.let { ref.accountById[it]?.label } ?: "Account", filter.accountId != null, listOf<Long?>(null) + ref.accounts.map { it.id },
                        { id -> id?.let { ref.accountById[it]?.label } ?: "All accounts" }) { filter = filter.copy(accountId = it) }
                }
                item {
                    ChipMenu(filter.simSlot?.let { "SIM $it" } ?: "SIM", filter.simSlot != null, listOf(null, 1, 2), { it?.let { s -> "SIM $s" } ?: "Any SIM" }) { filter = filter.copy(simSlot = it) }
                }
                item {
                    ChipMenu(filter.app?.label ?: "App", filter.app != null, listOf(null) + AppOpt.entries, { it?.label ?: "Any app" }) { filter = filter.copy(app = it) }
                }
                item {
                    ChipMenu(filter.categoryId?.let { ref.categoryById[it]?.name } ?: "Category", filter.categoryId != null, listOf<Long?>(null) + ref.categories.map { it.id },
                        { id -> id?.let { ref.categoryById[it]?.name } ?: "All categories" }) { filter = filter.copy(categoryId = it) }
                }
                item {
                    ChipMenu(
                        when (filter.direction) { Direction.DEBIT -> "Debits"; Direction.CREDIT -> "Credits"; null -> "Debit/Credit" }, filter.direction != null,
                        listOf(null, Direction.DEBIT, Direction.CREDIT), { when (it) { Direction.DEBIT -> "Debits"; Direction.CREDIT -> "Credits"; null -> "Both" } },
                    ) { filter = filter.copy(direction = it) }
                }
                item {
                    ChipMenu(
                        when (filter.excluded) { true -> "Excluded only"; false -> "Hide excluded"; null -> "Excluded" }, filter.excluded != null,
                        listOf(null, true, false), { when (it) { true -> "Excluded only"; false -> "Hide excluded"; null -> "Show all" } },
                    ) { filter = filter.copy(excluded = it) }
                }
            }
        }
        if (rows.isEmpty()) item {
            EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, "Nothing here", "No transactions match these filters. Try a longer date range.")
        }
        byDay.forEach { (day, list) ->
            item(key = "h$day") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                    Text(Dates.day(day), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(Money.short(Totals.spend(list, ref.excludedAccounts)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            list.forEach { t -> item(key = t.id) { SwipeRow(t, ref, onSwipe = { recat = t }) { nav.navigate(Routes.txn(t.id)) } } }
        }
        item { Spacer(Modifier.size(88.dp)) }
    }
    RecategorizeHost(recat, ref) { recat = null }
}

/** Swipe right to recategorise. */
@Composable
private fun SwipeRow(t: Txn, ref: RefData, onSwipe: () -> Unit, onClick: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.StartToEnd) onSwipe()
        false
    })
    SwipeToDismissBox(
        state = state, enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).padding(start = 24.dp), contentAlignment = Alignment.CenterStart) {
                Icon(Icons.Filled.Category, contentDescription = "Recategorise", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            TxnRow(t, t.categoryId?.let { ref.categoryById[it] }, t.accountId?.let { ref.accountById[it] }, onClick)
        }
    }
}

@Composable
fun <T> ChipMenu(label: String, active: Boolean, options: List<T>, display: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = active, onClick = { open = true }, label = { Text(label) }, shape = CircleShape, colors = pillChipColors())
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(display(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

/** Category sheet, then "Always categorise <payee> as <category>?" (Yes / Just this once). */
@Composable
fun RecategorizeHost(target: Txn?, ref: RefData, onDone: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var asked by remember(target) { mutableStateOf<Long?>(null) }
    val t = target ?: return
    val pickedId = asked
    if (pickedId == null) {
        CategorySheet(ref.categories, if (t.direction == Direction.DEBIT) CategoryKind.SPEND else CategoryKind.INCOME, onDismiss = onDone) { cat ->
            scope.launch { c.repository.setCategory(t.id, cat.id, always = false) }
            if (Categorizer.payeeKey(t.payeeName, t.payeeVpa) != null) asked = cat.id else onDone()
        }
    } else {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Always categorise ${t.displayPayee} as ${ref.categoryById[pickedId]?.name}?") },
            confirmButton = { TextButton(onClick = { scope.launch { c.repository.setCategory(t.id, pickedId, always = true) }; onDone() }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = onDone) { Text("Just this once") } },
        )
    }
}

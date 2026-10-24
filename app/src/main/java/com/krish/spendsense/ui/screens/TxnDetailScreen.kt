@file:OptIn(ExperimentalMaterial3Api::class)

package com.krish.spendsense.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.krish.spendsense.data.Account
import com.krish.spendsense.data.CategoryKind
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.domain.Money
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.ParseUtils
import com.krish.spendsense.parse.SmsParser
import com.krish.spendsense.ui.components.AmountText
import com.krish.spendsense.ui.components.CategoryAvatar
import com.krish.spendsense.ui.components.CategorySheet
import com.krish.spendsense.ui.components.ConfirmDialog
import com.krish.spendsense.ui.components.Dropdown
import com.krish.spendsense.ui.components.LocalContainer
import com.krish.spendsense.ui.components.MoneyField
import com.krish.spendsense.ui.components.ScreenScaffold
import com.krish.spendsense.ui.components.SettingRow
import com.krish.spendsense.ui.components.SwitchRow
import com.krish.spendsense.ui.components.Tag
import com.krish.spendsense.ui.components.excludedLabel
import com.krish.spendsense.ui.components.paiseToInput
import com.krish.spendsense.ui.components.sourceLabels
import com.krish.spendsense.ui.rememberRefData
import com.krish.spendsense.ui.theme.AmountLarge
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@Composable
fun TxnDetailScreen(nav: NavController, id: Long, openCategorySheet: Boolean) {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    val txn by remember(id) { c.repository.observeTxn(id) }.collectAsStateWithLifecycle(null)
    var recat by remember { mutableStateOf(openCategorySheet) }
    var confirmDelete by remember { mutableStateOf(false) }
    var assign by remember { mutableStateOf(false) }
    val t = txn

    ScreenScaffold(
        "Transaction", onBack = { nav.popBackStack() },
        actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete transaction") } },
    ) { padding ->
        if (t == null) return@ScreenScaffold
        var note by rememberSaveable(t.id) { mutableStateOf(t.note.orEmpty()) }
        var payee by rememberSaveable(t.id) { mutableStateOf(t.payeeName.orEmpty()) }
        val category = t.categoryId?.let { ref.categoryById[it] }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CategoryAvatar(category, 56.dp)
                Spacer(Modifier.size(8.dp))
                AmountText(t, AmountLarge)
                Text(t.displayPayee, style = MaterialTheme.typography.titleMedium)
                Text(Dates.full(t.timestamp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sourceLabels(t).forEach { Tag(it, MaterialTheme.colorScheme.primary) }
                    t.excludedReason?.let { Tag(excludedLabel(it.name)) }
                }
            }
            HorizontalDivider()
            SettingRow("Category", category?.name ?: "Uncategorised", onClick = { recat = true })
            SettingRow(
                "Account", t.accountId?.let { ref.accountById[it]?.label } ?: "Unassigned — tap to choose",
                onClick = { assign = true },
            )
            t.upiRef?.let { SettingRow("UPI ref", it) }
            t.payeeVpa?.let { SettingRow("UPI ID", it) }
            t.balanceAfterPaise?.let { SettingRow("Balance after", Money.format(it)) }
            t.simSlot?.let { SettingRow("Received on", "SIM $it") }
            SwitchRow(
                "Exclude from spending", t.excludedReason?.let { excludedLabel(it.name) } ?: "Counts towards your totals",
                checked = t.excludedReason != null,
            ) { v -> scope.launch { c.repository.setExcluded(t.id, v) } }
            OutlinedTextField(payee, { payee = it }, label = { Text("Payee name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            Button(
                onClick = {
                    scope.launch {
                        c.repository.updateTxn(t.copy(note = note.trim().ifEmpty { null }, payeeName = payee.trim().ifEmpty { null }))
                        nav.popBackStack()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("Save") }
        }
        if (recat) RecategorizeHost(t, ref) { recat = false }
        if (assign) AccountPickerDialog(ref.accounts, showAlways = t.senderKey != null && t.accountTail != null, onDismiss = { assign = false }) { a, always ->
            scope.launch { c.repository.assignAccount(t.id, a.id, always) }
            assign = false
        }
    }
    if (confirmDelete) ConfirmDialog(
        "Delete transaction?", "This removes it from all totals. This can't be undone.", "Delete",
        onConfirm = {
            confirmDelete = false
            scope.launch { c.repository.deleteTxn(id); nav.popBackStack() }
        },
        onDismiss = { confirmDelete = false },
    )
}

/** Account picker with the "Always use this account for this sender/last4" option. */
@Composable
fun AccountPickerDialog(accounts: List<Account>, showAlways: Boolean, onDismiss: () -> Unit, onPick: (Account, Boolean) -> Unit) {
    var always by remember { mutableStateOf(true) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose account") },
        text = {
            Column {
                if (accounts.isEmpty()) Text("No accounts yet. Add one in Settings → Accounts.")
                accounts.forEach { a -> TextButton(onClick = { onPick(a, showAlways && always) }) { Text(a.label) } }
                if (showAlways) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(always, { always = it })
                    Text("Always use this account for this sender / account digits")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Cash / manual entry; with [unparsedId] it is prefilled from a Review inbox message. */
@Composable
fun AddTxnScreen(nav: NavController, unparsedId: Long?) {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable { mutableStateOf("") }
    var direction by rememberSaveable { mutableStateOf(Direction.DEBIT) }
    var payee by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var accountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now(ParseUtils.IST).toEpochDay()) }
    var upiRef by rememberSaveable { mutableStateOf<String?>(null) }
    var raw by rememberSaveable { mutableStateOf<String?>(null) }
    var timeOfDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickCat by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var prefilled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(unparsedId) {
        if (unparsedId == null || prefilled) return@LaunchedEffect
        val m = c.repository.unparsedById(unparsedId) ?: return@LaunchedEffect
        raw = m.body
        date = Dates.date(m.timestamp).toEpochDay()
        timeOfDay = m.timestamp
        SmsParser.partial(m.bank ?: "", m.body, m.timestamp)?.let { p ->
            amount = paiseToInput(p.amountPaise)
            direction = p.direction
            payee = p.payeeName ?: p.payeeVpa.orEmpty()
            upiRef = p.upiRef
            accountId = ref.accounts.firstOrNull { a -> a.bank == m.bank && p.accountTail != null && a.last4.endsWith(p.accountTail!!) }?.id
        }
        prefilled = true
    }

    ScreenScaffold(if (unparsedId != null) "Create from message" else "Add transaction", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            raw?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(direction == Direction.DEBIT, { direction = Direction.DEBIT; categoryId = null }, label = { Text("Spent") })
                FilterChip(direction == Direction.CREDIT, { direction = Direction.CREDIT; categoryId = null }, label = { Text("Received") })
            }
            MoneyField(amount, { amount = it }, "Amount", Modifier.fillMaxWidth())
            OutlinedTextField(payee, { payee = it }, label = { Text(if (direction == Direction.DEBIT) "Paid to" else "Received from") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { pickCat = true }, modifier = Modifier.fillMaxWidth()) {
                Text(categoryId?.let { ref.categoryById[it]?.name } ?: "Category: auto")
            }
            Dropdown("Account", listOf<Long?>(null) + ref.accounts.map { it.id }, accountId, { id -> id?.let { ref.accountById[it]?.label } ?: "Cash / none" }, { accountId = it })
            val d = LocalDate.ofEpochDay(date)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val today = LocalDate.now(ParseUtils.IST)
                FilterChip(d == today, { date = today.toEpochDay() }, label = { Text("Today") })
                FilterChip(d == today.minusDays(1), { date = today.minusDays(1).toEpochDay() }, label = { Text("Yesterday") })
                TextButton(onClick = { pickDate = true }) { Text(Dates.day(d)) }
            }
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            val paise = Money.parse(amount)
            Button(
                enabled = paise != null && paise > 0,
                onClick = {
                    val ts = timeOfDay?.takeIf { Dates.date(it) == d }
                        ?: d.atTime(if (d == LocalDate.now(ParseUtils.IST)) LocalTime.now(ParseUtils.IST) else LocalTime.NOON).atZone(ParseUtils.IST).toInstant().toEpochMilli()
                    scope.launch {
                        if (unparsedId != null) {
                            c.repository.resolveUnparsed(unparsedId, paise!!, direction, payee, categoryId, accountId, ts, upiRef)
                        } else {
                            c.repository.addManual(paise!!, direction, payee, categoryId, accountId, ts, note)
                        }
                        nav.popBackStack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save", fontWeight = FontWeight.SemiBold) }
        }
    }
    if (pickCat) CategorySheet(ref.categories, if (direction == Direction.DEBIT) CategoryKind.SPEND else CategoryKind.INCOME, onDismiss = { pickCat = false }) {
        categoryId = it.id
        pickCat = false
    }
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = LocalDate.ofEpochDay(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                    pickDate = false
                }) { Text("OK") }
            },
        ) { DatePicker(state) }
    }
}

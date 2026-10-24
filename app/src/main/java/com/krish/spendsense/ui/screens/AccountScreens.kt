package com.krish.spendsense.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.krish.spendsense.data.Account
import com.krish.spendsense.data.AccountSender
import com.krish.spendsense.data.SimProfile
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.domain.Money
import com.krish.spendsense.domain.Totals
import com.krish.spendsense.parse.ParseUtils
import com.krish.spendsense.ui.Perms
import com.krish.spendsense.ui.Routes
import com.krish.spendsense.ui.components.COLORS
import com.krish.spendsense.ui.components.ConfirmDialog
import com.krish.spendsense.ui.components.Dropdown
import com.krish.spendsense.ui.components.EmptyState
import com.krish.spendsense.ui.components.ICONS
import com.krish.spendsense.ui.components.InfoCard
import com.krish.spendsense.ui.components.LocalContainer
import com.krish.spendsense.ui.components.MoneyField
import com.krish.spendsense.ui.components.ScreenScaffold
import com.krish.spendsense.ui.components.SectionTitle
import com.krish.spendsense.ui.components.SettingRow
import com.krish.spendsense.ui.components.SwitchRow
import com.krish.spendsense.ui.components.TextInputDialog
import com.krish.spendsense.ui.components.colorOf
import com.krish.spendsense.ui.components.iconFor
import com.krish.spendsense.ui.components.paiseToInput
import com.krish.spendsense.ui.rememberRefData
import com.krish.spendsense.ui.theme.AmountMedium
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

private fun SimProfile.title() = "SIM $slot · $label${if (!active) " (inactive)" else ""}"

@Composable
fun SimsScreen(nav: NavController) {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    var editLabel by remember { mutableStateOf<SimProfile?>(null) }
    var editNumber by remember { mutableStateOf<SimProfile?>(null) }
    val askPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { c.repository.refreshSims(c.simDetector.current()) }
    }

    ScreenScaffold("Numbers & SIMs", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (ref.sims.isEmpty()) item {
                EmptyState(
                    Icons.Filled.SimCard, "No SIMs detected yet",
                    "Allow phone access so SpendSense can tell your two SIMs apart. It only reads which SIM a bank SMS arrived on.",
                    "Allow phone access",
                ) { askPhone.launch(Perms.PHONE) }
            }
            items(ref.sims, key = { it.id }) { sim ->
                val linked = ref.accounts.filter { it.simProfileId == sim.id }
                InfoCard {
                    Text(sim.title(), style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(sim.carrier, sim.phoneNumber ?: "Number not set").joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { editLabel = sim }) { Text("Rename label") }
                        OutlinedButton(onClick = { editNumber = sim }) { Text("Edit number") }
                    }
                    SwitchRow("Track SMS from this SIM", if (sim.trackSms) "Bank SMS on this SIM are captured" else "SMS on this SIM are ignored", sim.trackSms) {
                        scope.launch { c.repository.updateSim(sim.copy(trackSms = it)) }
                    }
                    Text("Linked accounts", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    if (linked.isEmpty()) Text("None yet — accounts link automatically from their SMS.", style = MaterialTheme.typography.bodySmall)
                    linked.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.label, Modifier.weight(1f))
                            val others = ref.sims.filter { it.id != sim.id && it.active }
                            if (others.isNotEmpty()) {
                                var open by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { open = true }) { Text("Move") }
                                    androidx.compose.material3.DropdownMenu(open, { open = false }) {
                                        others.forEach { o ->
                                            androidx.compose.material3.DropdownMenuItem(text = { Text("To ${o.title()}") }, onClick = {
                                                open = false
                                                scope.launch { c.repository.updateAccount(a.copy(simProfileId = o.id)) }
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editLabel?.let { s ->
        TextInputDialog("Label for SIM ${s.slot}", s.label, "e.g. Personal, Work", { editLabel = null }) {
            scope.launch { c.repository.updateSim(s.copy(label = it.trim().ifEmpty { s.label })) }
            editLabel = null
        }
    }
    editNumber?.let { s ->
        TextInputDialog("Number on SIM ${s.slot}", s.phoneNumber.orEmpty(), "Phone number (display only)", { editNumber = null }, keyboardType = KeyboardType.Phone, onConfirm = {
            scope.launch { c.repository.updateSim(s.copy(phoneNumber = it.trim().ifEmpty { null })) }
            editNumber = null
        })
    }
}

@Composable
fun AccountsScreen(nav: NavController) {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    val senders by remember { c.repository.observeSenders() }.collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf(false) }
    var addingSender by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }

    ScreenScaffold("Accounts", onBack = { nav.popBackStack() }, actions = {
        IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Add account") }
    }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (ref.accounts.isEmpty()) item {
                EmptyState(Icons.Filled.AccountBalance, "No accounts yet", "Accounts are created automatically from your first bank SMS. You can also add one yourself.", "Add account") { adding = true }
            }
            items(ref.accounts, key = { it.id }) { a ->
                SettingRow(
                    a.label + if (a.isPrimary) "  ★" else "",
                    listOfNotNull("${a.bank} ••${a.last4}", a.simProfileId?.let { id -> ref.sims.firstOrNull { it.id == id }?.title() },
                        "Paused".takeIf { !a.tracked }, "Not in totals".takeIf { !a.includeInTotals }, "Needs a name".takeIf { !a.named }).joinToString(" · "),
                    icon = iconFor(a.icon), onClick = { nav.navigate(Routes.accountEdit(a.id)) },
                )
            }
            if (ref.accounts.size > 1) item {
                OutlinedButton(onClick = { merging = true }, modifier = Modifier.padding(16.dp)) { Text("Merge two accounts") }
            }
            item { SectionTitle("Bank SMS sender IDs") }
            item {
                Text(
                    "Bank SMS are recognised by sender, e.g. JK-CUBANK-S → CUBANK. Add one if your bank's SMS aren't being picked up.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(senders.filter { it.accountId == null }, key = { "s${it.id}" }) { s -> SenderRow(s) { scope.launch { c.repository.deleteSender(s) } } }
            item { TextButton(onClick = { addingSender = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("+ Add sender ID") } }
        }
    }
    if (adding) AddAccountDialog(onDismiss = { adding = false }) { bank, last4, name ->
        scope.launch { c.repository.createAccount(bank, last4, name) }
        adding = false
    }
    if (addingSender) AddSenderDialog(defaultBank = "", onDismiss = { addingSender = false }) { bank, key ->
        scope.launch { c.repository.addSender(bank, key, null) }
        addingSender = false
    }
    if (merging) MergeDialog(ref.accounts, null, onDismiss = { merging = false }) { keep, drop ->
        scope.launch { c.repository.mergeAccounts(keep.id, drop.id) }
        merging = false
    }
}

@Composable
private fun SenderRow(s: AccountSender, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${s.senderKey}  →  ${s.bank}", Modifier.weight(1f))
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = "Remove sender ${s.senderKey}") }
    }
}

@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var bank by rememberSaveable { mutableStateOf("") }
    var last4 by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(bank, { bank = it }, label = { Text("Bank (e.g. HDFC, CUB, AXIS)") }, singleLine = true)
                OutlinedTextField(last4, { v -> last4 = v.filter { it.isDigit() }.take(4) }, label = { Text("Last 4 digits") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Nickname") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = bank.isNotBlank() && last4.length >= 2, onClick = { onAdd(bank.trim(), last4, name.trim()) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddSenderDialog(defaultBank: String, onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var bank by rememberSaveable { mutableStateOf(defaultBank) }
    var key by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add sender ID") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key, { key = it }, label = { Text("Sender, e.g. JK-CUBANK-S") }, singleLine = true)
                OutlinedTextField(bank, { bank = it }, label = { Text("Bank (e.g. CUB)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = bank.isNotBlank() && key.isNotBlank(), onClick = { onAdd(bank, key) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Merge: every transaction of the merged account moves into the kept one. */
@Composable
private fun MergeDialog(accounts: List<Account>, keepFixed: Account?, onDismiss: () -> Unit, onMerge: (Account, Account) -> Unit) {
    var keep by remember { mutableStateOf(keepFixed ?: accounts.first()) }
    var drop by remember { mutableStateOf(accounts.firstOrNull { it.id != keep.id } ?: accounts.first()) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Merge accounts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Use this when the same account was detected twice. All transactions move into the kept account.")
                if (keepFixed == null) Dropdown("Keep", accounts, keep, { it.label }, { keep = it })
                Dropdown("Merge into it and remove", accounts.filter { it.id != keep.id }, drop, { it.label }, { drop = it })
            }
        },
        confirmButton = { TextButton(enabled = keep.id != drop.id, onClick = { onMerge(keep, drop) }) { Text("Merge") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Every per-account preference on one screen, in plain language. */
@Composable
fun AccountEditScreen(nav: NavController, id: Long) {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    val account by remember(id) { c.repository.observeAccount(id) }.collectAsStateWithLifecycle(null)
    val senders by remember { c.repository.observeSenders() }.collectAsStateWithLifecycle(emptyList())
    var deleting by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var addingSender by remember { mutableStateOf(false) }
    val a = account ?: return
    fun save(updated: Account) = scope.launch { c.repository.updateAccount(updated) }

    ScreenScaffold(a.label, onBack = { nav.popBackStack() }) { padding ->
        var nickname by rememberSaveable(a.id) { mutableStateOf(a.nickname) }
        var limit by rememberSaveable(a.id) { mutableStateOf(paiseToInput(a.dailyLimitPaise)) }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Text("${a.bank} ••${a.last4}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            OutlinedTextField(nickname, { nickname = it }, label = { Text("Nickname") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Text("Color", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(COLORS) { col ->
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(colorOf(col))
                            .let { if (col == a.color) it.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else it }
                            .clickable { save(a.copy(color = col)) },
                    )
                }
            }
            Text("Icon", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(listOf("bank", "card", "wallet", "work", "home", "people", "income")) { key ->
                    IconButton(onClick = { save(a.copy(icon = key)) }) {
                        Icon(ICONS.getValue(key), contentDescription = key, tint = if (a.icon == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Dropdown(
                "SIM / number it gets alerts on", listOf<Long?>(null) + ref.sims.map { it.id }, a.simProfileId,
                { sid -> sid?.let { s -> ref.sims.firstOrNull { it.id == s }?.title() } ?: "Not set" },
                { save(a.copy(simProfileId = it)) }, Modifier.fillMaxWidth().padding(16.dp),
            )
            SwitchRow("Auto-update SIM when alerts start arriving on another number", null, a.autoUpdateSim) { save(a.copy(autoUpdateSim = it)) }
            SwitchRow("Track this account", "Off pauses new captures; history is kept", a.tracked) { save(a.copy(tracked = it)) }
            SwitchRow("Include in spending totals", "Turn off for e.g. a business account", a.includeInTotals) { save(a.copy(includeInTotals = it)) }
            SwitchRow("This is my own account", "Used to spot transfers between your accounts", a.isOwn) { save(a.copy(isOwn = it)) }
            SwitchRow("This is a credit card", "Payments to it count as card bills, not spending", a.isCreditCard) { save(a.copy(isCreditCard = it)) }
            SwitchRow("Set as primary account", "Shown first on Home", a.isPrimary) { v -> scope.launch { if (v) c.repository.setPrimary(a.id) else c.repository.updateAccount(a.copy(isPrimary = false)) } }
            MoneyField(limit, { limit = it }, "Optional daily limit for this account", Modifier.fillMaxWidth().padding(16.dp))
            Button(onClick = {
                save(a.copy(nickname = nickname.trim(), named = true, dailyLimitPaise = Money.parse(limit)?.takeIf { it > 0 }))
                nav.popBackStack()
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("Save") }
            Text("You can change any of this anytime.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))

            SectionTitle("Bank SMS sender IDs")
            senders.filter { it.accountId == a.id || (it.accountId == null && it.bank == a.bank) }.forEach { s ->
                SenderRow(s) { scope.launch { c.repository.deleteSender(s) } }
            }
            TextButton(onClick = { addingSender = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("+ Add sender ID") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingRow("View transactions", "Balance, this month and history", icon = Icons.Filled.AccountBalance, onClick = { nav.navigate(Routes.account(a.id)) })
            if (ref.accounts.size > 1) TextButton(onClick = { merging = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Merge another account into this one") }
            TextButton(onClick = { deleting = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Delete account", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.size(32.dp))
        }
    }
    if (addingSender) AddSenderDialog(a.bank, onDismiss = { addingSender = false }) { bank, key ->
        scope.launch { c.repository.addSender(bank, key, a.id) }
        addingSender = false
    }
    if (merging) MergeDialog(ref.accounts, a, onDismiss = { merging = false }) { keep, drop ->
        scope.launch { c.repository.mergeAccounts(keep.id, drop.id) }
        merging = false
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete ${a.label}?") },
        text = { Text("Choose what happens to its transactions. Deleting them removes them from every total and can't be undone.") },
        confirmButton = {
            Column {
                TextButton(onClick = { deleting = false; scope.launch { c.repository.deleteAccount(a.id, false); nav.popBackStack() } }) { Text("Keep its transactions as Unassigned") }
                TextButton(onClick = { deleting = false; scope.launch { c.repository.deleteAccount(a.id, true); nav.popBackStack() } }) {
                    Text("Delete its transactions too", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { deleting = false }) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun AccountDetailScreen(nav: NavController, id: Long) {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val account by remember(id) { c.repository.observeAccount(id) }.collectAsStateWithLifecycle(null)
    val balance by remember(id) { c.repository.observeLatestBalance(id) }.collectAsStateWithLifecycle(null)
    val today = LocalDate.now(ParseUtils.IST)
    val month = YearMonth.from(today)
    val monthRows by remember(id) { c.repository.observeRange(Dates.startOf(month.atDay(1)), Dates.endOf(today)) }.collectAsStateWithLifecycle(emptyList())
    val a = account ?: return
    val rows = monthRows.filter { it.accountId == id }
    ScreenScaffold(a.label, onBack = { nav.popBackStack() }, actions = {
        IconButton(onClick = { nav.navigate(Routes.accountEdit(id)) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit account") }
    }) { padding ->
        TxnBrowser(nav, fixedAccountId = id, modifier = Modifier.padding(padding)) {
            item {
                InfoCard {
                    Text("Latest balance", style = MaterialTheme.typography.labelLarge)
                    val b = balance
                    if (b?.balanceAfterPaise != null) {
                        Text(Money.format(b.balanceAfterPaise), style = AmountMedium)
                        Text("as of ${Dates.day(b.timestamp)}, ${Dates.time(b.timestamp)}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Not known yet — it appears when an SMS includes the balance.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                InfoCard {
                    Text("This month", style = MaterialTheme.typography.labelLarge)
                    Row {
                        Column(Modifier.weight(1f)) { Text("Spent"); Text(Money.short(Totals.spend(rows)), fontWeight = FontWeight.Bold) }
                        Column(Modifier.weight(1f)) { Text("Income"); Text(Money.short(Totals.income(rows)), fontWeight = FontWeight.Bold) }
                    }
                    Totals.byCategory(rows).entries.sortedByDescending { it.value }.take(5).forEach { (cat, v) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(cat?.let { ref.categoryById[it]?.name } ?: "Uncategorised", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(Money.short(v), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

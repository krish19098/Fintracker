package com.krish.spendsense.ui.screens

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.krish.spendsense.BuildConfig
import com.krish.spendsense.data.Backup
import com.krish.spendsense.data.Category
import com.krish.spendsense.data.CategoryKind
import com.krish.spendsense.domain.CatKeys
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.domain.Money
import com.krish.spendsense.ingest.Backfill
import com.krish.spendsense.ingest.Work
import com.krish.spendsense.parse.IncomeKind
import com.krish.spendsense.ui.Perms
import com.krish.spendsense.ui.Routes
import com.krish.spendsense.ui.components.COLORS
import com.krish.spendsense.ui.components.CategoryAvatar
import com.krish.spendsense.ui.components.ConfirmDialog
import com.krish.spendsense.ui.components.Dropdown
import com.krish.spendsense.ui.components.ICONS
import com.krish.spendsense.ui.components.LocalContainer
import com.krish.spendsense.ui.components.MoneyField
import com.krish.spendsense.ui.components.ScreenScaffold
import com.krish.spendsense.ui.components.SectionTitle
import com.krish.spendsense.ui.components.SettingRow
import com.krish.spendsense.ui.components.SwitchRow
import com.krish.spendsense.ui.components.TextInputDialog
import com.krish.spendsense.ui.components.TimeDialog
import com.krish.spendsense.ui.components.colorOf
import com.krish.spendsense.ui.components.minuteLabel
import com.krish.spendsense.ui.components.paiseToInput
import com.krish.spendsense.ui.rememberRefData
import com.krish.spendsense.ui.theme.Bad
import com.krish.spendsense.ui.theme.Good
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: NavController) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    val s = ref.settings
    ScreenScaffold("Settings") { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SettingRow("Numbers & SIMs", "Labels, numbers, per-SIM tracking", Icons.Filled.SimCard, onClick = { nav.navigate(Routes.SIMS) }) }
            item { SettingRow("Accounts", "${ref.accounts.size} accounts · names, SIMs, totals, sender IDs", Icons.Filled.AccountBalance, onClick = { nav.navigate(Routes.ACCOUNTS) }) }
            item { SettingRow("Budgets & limits", "Daily ${Money.short(s.dailyLimitPaise)} · warn at ${s.warnPct}%", Icons.Filled.Tune, onClick = { nav.navigate(Routes.BUDGETS) }) }
            item { SettingRow("Categories & rules", "Edit categories, keywords and learned rules", Icons.Filled.Category, onClick = { nav.navigate(Routes.CATEGORIES) }) }
            item { SettingRow("Notification times", "Summary at ${minuteLabel(s.summaryMinute)}", Icons.Filled.Notifications, onClick = { nav.navigate(Routes.NOTIF_TIMES) }) }
            item {
                SwitchRow("App lock", "Fingerprint, face or screen lock when opening", s.appLock) { v -> scope.launch { c.settings.setAppLock(v) } }
            }
            item { SettingRow("Permission health", "Check that nothing is blocking capture", Icons.Filled.Security, onClick = { nav.navigate(Routes.PERMISSIONS) }) }
            item { SettingRow("Backup & export", "Encrypted backup, restore, CSV", Icons.Filled.Backup, onClick = { nav.navigate(Routes.BACKUP) }) }
            item {
                SettingRow(
                    "Re-scan last 180 days", if (s.backfillDone) "Already done · safe to run again (no duplicates)" else "Not run yet",
                    Icons.Filled.History, onClick = { Backfill.start(ctx); nav.navigate(Routes.HOME) },
                )
            }
            item {
                SwitchRow("Debug capture mode", "Save GPay/Paytm notifications that fail to parse to the Review inbox", s.debugCapture) { v ->
                    scope.launch { c.settings.setDebugCapture(v) }
                }
            }
            item { SettingRow("About", "Version ${BuildConfig.VERSION_NAME}", Icons.Filled.Info, onClick = { nav.navigate(Routes.ABOUT) }) }
        }
    }
}

@Composable
fun BudgetsScreen(nav: NavController) {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    val s = ref.settings
    var editCat by remember { mutableStateOf<Category?>(null) }
    ScreenScaffold("Budgets & limits", onBack = { nav.popBackStack() }) { padding ->
        var daily by rememberSaveable(s.dailyLimitPaise) { mutableStateOf(paiseToInput(s.dailyLimitPaise)) }
        var large by rememberSaveable(s.largeTxnPaise) { mutableStateOf(paiseToInput(s.largeTxnPaise)) }
        var monthly by rememberSaveable(s.monthlyBudgetPaise) { mutableStateOf(if (s.monthlyBudgetPaise > 0) paiseToInput(s.monthlyBudgetPaise) else "") }
        var warn by rememberSaveable(s.warnPct) { mutableStateOf(s.warnPct.toString()) }
        LazyColumn(Modifier.padding(padding)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MoneyField(daily, { daily = it }, "Daily limit", Modifier.fillMaxWidth())
                    OutlinedTextField(
                        warn, { v -> warn = v.filter { it.isDigit() }.take(2) }, label = { Text("Warn at (% of limit)") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                    MoneyField(large, { large = it }, "Large payment alert from", Modifier.fillMaxWidth())
                    MoneyField(monthly, { monthly = it }, "Monthly budget (blank = daily limit × days)", Modifier.fillMaxWidth())
                    Button(onClick = {
                        scope.launch {
                            Money.parse(daily)?.takeIf { it > 0 }?.let { c.settings.setDailyLimit(it) }
                            warn.toIntOrNull()?.let { c.settings.setWarnPct(it) }
                            Money.parse(large)?.let { c.settings.setLargeTxn(it) }
                            c.settings.setMonthlyBudget(Money.parse(monthly) ?: 0)
                            nav.popBackStack()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                }
            }
            item { SectionTitle("Category monthly budgets (optional)") }
            items(ref.categories.filter { it.kind == CategoryKind.SPEND }, key = { it.id }) { cat ->
                SettingRow(cat.name, cat.monthlyBudgetPaise?.let { "Budget ${Money.short(it)} · alerts at ${s.warnPct}% and 100%" } ?: "No budget", onClick = { editCat = cat }) {
                    CategoryAvatar(cat, 32.dp)
                }
            }
        }
    }
    editCat?.let { cat ->
        TextInputDialog("${cat.name} monthly budget", paiseToInput(cat.monthlyBudgetPaise), "Amount in ₹ (blank = none)", { editCat = null }, keyboardType = KeyboardType.Decimal) {
            scope.launch { c.repository.setCategoryBudget(cat.id, Money.parse(it)?.takeIf { p -> p > 0 }) }
            editCat = null
        }
    }
}

@Composable
fun CategoriesScreen(nav: NavController) {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    val keywords by remember { c.repository.observeKeywordRules() }.collectAsStateWithLifecycle(emptyList())
    val payeeRules by remember { c.repository.observePayeeRules() }.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf<CategoryKind?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    var addKeyword by remember { mutableStateOf(false) }
    var addOwnVpa by remember { mutableStateOf(false) }
    var addCc by remember { mutableStateOf(false) }
    val protected = setOf(CatKeys.OTHERS, CatKeys.UNCATEGORISED, CatKeys.income(IncomeKind.OTHER_INCOME))

    ScreenScaffold("Categories & rules", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            listOf(CategoryKind.SPEND to "Spending categories", CategoryKind.INCOME to "Income categories").forEach { (kind, title) ->
                item { SectionTitle(title) }
                items(ref.categories.filter { it.kind == kind }, key = { "c${it.id}" }) { cat ->
                    Row(Modifier.fillMaxWidth().clickable { editing = cat }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryAvatar(cat, 36.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(cat.name, Modifier.weight(1f))
                        if (cat.systemKey !in protected) IconButton(onClick = { deleting = cat }) { Icon(Icons.Filled.Close, contentDescription = "Delete ${cat.name}") }
                    }
                }
                item { TextButton(onClick = { creating = kind }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("+ Add category") } }
            }
            item { SectionTitle("Keyword rules") }
            item {
                Text("If a payee, UPI ID or note contains the word, it gets that category.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(keywords, key = { "k${it.id}" }) { k ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("“${k.keyword}” → ${ref.categoryById[k.categoryId]?.name ?: "?"}", Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { c.repository.deleteKeyword(k) } }) { Icon(Icons.Filled.Close, contentDescription = "Delete keyword ${k.keyword}") }
                }
            }
            item { TextButton(onClick = { addKeyword = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("+ Add keyword") } }
            item { SectionTitle("Learned rules") }
            if (payeeRules.isEmpty()) item { Text("None yet. When you change a category you'll be asked whether to remember it.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            items(payeeRules, key = { "p${it.payeeKey}" }) { r ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.payeeKey} → ${ref.categoryById[r.categoryId]?.name ?: "?"}", Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { c.repository.deletePayeeRule(r) } }) { Icon(Icons.Filled.Close, contentDescription = "Forget rule for ${r.payeeKey}") }
                }
            }
            item { SectionTitle("Never count as spending") }
            item { Text("Your own UPI IDs (payments to them are self-transfers)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp)) }
            items(ref.settings.ownVpas.toList()) { v ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(v, Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { c.settings.setOwnVpas(ref.settings.ownVpas - v) } }) { Icon(Icons.Filled.Close, contentDescription = "Remove $v") }
                }
            }
            item { TextButton(onClick = { addOwnVpa = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("+ Add my UPI ID") } }
            item { Text("Credit-card bill payees", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp)) }
            items(ref.settings.ccPayees.toList()) { v ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(v, Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { c.settings.setCcPayees(ref.settings.ccPayees - v) } }) { Icon(Icons.Filled.Close, contentDescription = "Remove $v") }
                }
            }
            item { TextButton(onClick = { addCc = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("+ Add card bill payee") } }
            item { Spacer(Modifier.size(32.dp)) }
        }
    }
    editing?.let { cat ->
        CategoryEditDialog(cat.name, cat.icon, cat.color, onDismiss = { editing = null }) { name, icon, color ->
            scope.launch { c.repository.updateCategory(cat.copy(name = name, icon = icon, color = color)) }
            editing = null
        }
    }
    creating?.let { kind ->
        CategoryEditDialog("", "category", COLORS.first(), onDismiss = { creating = null }) { name, icon, color ->
            scope.launch { c.repository.addCategory(name, icon, color, kind) }
            creating = null
        }
    }
    deleting?.let { cat ->
        ConfirmDialog("Delete ${cat.name}?", "Its transactions move to ${if (cat.kind == CategoryKind.INCOME) "Other Income" else "Others"}. Rules for it are removed.", "Delete",
            onConfirm = { scope.launch { c.repository.deleteCategory(cat) }; deleting = null }, onDismiss = { deleting = null })
    }
    if (addKeyword) {
        var word by remember { mutableStateOf("") }
        val spend = ref.categories.filter { it.kind == CategoryKind.SPEND }
        var cat by remember { mutableStateOf(spend.firstOrNull()) }
        AlertDialog(
            onDismissRequest = { addKeyword = false }, title = { Text("Add keyword") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(word, { word = it }, label = { Text("Word, e.g. chai") }, singleLine = true)
                    cat?.let { selected -> Dropdown("Category", spend, selected, { it.name }, { cat = it }) }
                }
            },
            confirmButton = {
                TextButton(enabled = word.isNotBlank() && cat != null, onClick = { scope.launch { c.repository.addKeyword(word, cat!!.id) }; addKeyword = false }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addKeyword = false }) { Text("Cancel") } },
        )
    }
    if (addOwnVpa) TextInputDialog("My UPI ID", "", "e.g. name@okhdfcbank", { addOwnVpa = false }) {
        scope.launch { if (it.isNotBlank()) c.settings.setOwnVpas(ref.settings.ownVpas + it.trim()) }
        addOwnVpa = false
    }
    if (addCc) TextInputDialog("Card bill payee", "", "Payee name or UPI ID, e.g. CRED", { addCc = false }) {
        scope.launch { if (it.isNotBlank()) c.settings.setCcPayees(ref.settings.ccPayees + it.trim().lowercase()) }
        addCc = false
    }
}

@Composable
private fun CategoryEditDialog(name: String, icon: String, color: Long, onDismiss: () -> Unit, onSave: (String, String, Long) -> Unit) {
    var n by rememberSaveable { mutableStateOf(name) }
    var i by rememberSaveable { mutableStateOf(icon) }
    var col by rememberSaveable { mutableStateOf(color) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (name.isEmpty()) "New category" else "Edit category") },
        text = {
            Column {
                OutlinedTextField(n, { n = it }, label = { Text("Name") }, singleLine = true)
                LazyRow(contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(COLORS) { cc ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(colorOf(cc))
                                .let { if (cc == col) it.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else it }.clickable { col = cc },
                        )
                    }
                }
                LazyRow {
                    items(ICONS.keys.toList()) { k ->
                        IconButton(onClick = { i = k }) {
                            Icon(ICONS.getValue(k), contentDescription = k, tint = if (k == i) colorOf(col) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = n.isNotBlank(), onClick = { onSave(n.trim(), i, col) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NotificationTimesScreen(nav: NavController) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    val s = ref.settings
    var editing by remember { mutableIntStateOf(-1) }
    ScreenScaffold("Notification times", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding)) {
            SwitchRow("Alert for every payment", "“₹250 → Ravi Tea Stall · Today ₹1,840 / ₹2,000”", s.notifyEachTxn) { v -> scope.launch { c.settings.setNotifyEachTxn(v) } }
            SettingRow("End-of-day summary", minuteLabel(s.summaryMinute), onClick = { editing = 0 })
            SettingRow("Weekly digest (Sundays)", minuteLabel(s.weeklyMinute), onClick = { editing = 1 })
            SettingRow("Monthly digest (1st of the month)", minuteLabel(s.monthlyMinute), onClick = { editing = 2 })
        }
    }
    if (editing >= 0) {
        val kind = editing
        TimeDialog(listOf(s.summaryMinute, s.weeklyMinute, s.monthlyMinute)[kind], onDismiss = { editing = -1 }) { m ->
            scope.launch {
                when (kind) {
                    0 -> c.settings.setSummaryMinute(m)
                    1 -> c.settings.setWeeklyMinute(m)
                    else -> c.settings.setMonthlyMinute(m)
                }
                Work.scheduleSummary(ctx, c.settings.get(), kind)
            }
            editing = -1
        }
    }
}

/** Shared by Settings and onboarding: a green / red status line with a fix action. */
@Composable
fun HealthRow(title: String, ok: Boolean, why: String, fixLabel: String = "Fix", onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error, contentDescription = if (ok) "OK" else "Needs attention", tint = if (ok) Good else Bad)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) OutlinedButton(onClick = onFix) { Text(fixLabel) }
    }
}

/** Manually confirmed Xiaomi step (no API to read its state). */
@Composable
fun CheckStepRow(title: String, why: String, checked: Boolean, onOpen: (() -> Unit)?, onCheck: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheck)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (checked) Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = Good)
        else if (onOpen != null) TextButton(onClick = onOpen) { Text("Open") }
    }
}

@Composable
fun PermissionsScreen(nav: NavController) {
    ScreenScaffold("Permission health", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) { PermissionChecklist(showXiaomi = true) }
    }
}

@Composable
fun PermissionChecklist(showXiaomi: Boolean) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose {}
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (Perms.phone(ctx)) scope.launch { c.repository.refreshSims(c.simDetector.current()) }
    }
    // Re-read on every resume (the user may come back from system settings).
    val smsOk = remember(tick) { Perms.sms(ctx) }
    val phoneOk = remember(tick) { Perms.phone(ctx) }
    val listener = remember(tick) { Perms.listener(ctx) }
    val postOk = remember(tick) { Perms.postNotifications(ctx) }
    val battery = remember(tick) { Perms.batteryUnrestricted(ctx) }
    val s = ref.settings
    HealthRow("Read bank SMS", smsOk, "Needed to capture payments from your bank SMS on both SIMs") {
        request.launch(Perms.SMS)
    }
    HealthRow("Tell SIMs apart", phoneOk, "Lets us know which SIM an SMS arrived on") { request.launch(arrayOf(Perms.PHONE)) }
    HealthRow(
        "Notification access", listener,
        if (listener) "GPay / Paytm names are added" + (if (s.listenerConnectedAt > 0) " · connected ${Dates.day(s.listenerConnectedAt)} ${Dates.time(s.listenerConnectedAt)}" else "")
        else "Adds payee names from GPay and Paytm. Other apps are ignored.",
    ) { Perms.openListenerSettings(ctx) }
    HealthRow("Show alerts", postOk, "For limit warnings and daily summaries") {
        if (android.os.Build.VERSION.SDK_INT >= 33) request.launch(arrayOf(Perms.POST)) else Perms.openAppSettings(ctx)
    }
    if (showXiaomi) {
        SectionTitle("Xiaomi / HyperOS reliability")
        Text(
            "Xiaomi phones stop background apps aggressively. These steps keep capture working.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
        )
        CheckStepRow("Autostart → ON", "So SMS are caught after a restart", "autostart" in s.checklist, { Perms.openAutostart(ctx) }) { v ->
            scope.launch { c.settings.setChecked("autostart", v) }
        }
        CheckStepRow("Battery saver → No restrictions", "So the app isn't stopped in the background", battery || "battery" in s.checklist, { Perms.openBattery(ctx) }) { v ->
            scope.launch { c.settings.setChecked("battery", v) }
        }
        CheckStepRow("Lock the app in Recents", "Open Recents, long-press SpendSense and tap the lock icon", "recents" in s.checklist, null) { v ->
            scope.launch { c.settings.setChecked("recents", v) }
        }
    }
}

@Composable
fun BackupScreen(nav: NavController) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf<CharArray?>(null) }
    var askPass by remember { mutableStateOf<String?>(null) }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var confirmRestore by remember { mutableStateOf<com.krish.spendsense.data.BackupData?>(null) }
    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = pass ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val bytes = withContext(Dispatchers.Default) { Backup.encrypt(c.repository.snapshot(), p) }
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }
            }.isSuccess
            p.fill(' ')
            toast(if (ok) "Encrypted backup saved" else "Backup failed")
        }
    }
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importUri = uri; askPass = "import" }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = Backup.toCsv(c.repository.range(0, Long.MAX_VALUE), c.repository.allAccounts().associateBy { it.id }, c.repository.allCategories().associateBy { it.id })
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }
            }.isSuccess
            toast(if (ok) "CSV exported" else "Export failed")
        }
    }

    ScreenScaffold("Backup & export", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Backups are encrypted with a passphrase you choose. Keep it safe — without it the file can't be opened.")
            Button(onClick = { askPass = "export" }, modifier = Modifier.fillMaxWidth()) { Text("Create encrypted backup") }
            OutlinedButton(onClick = { pickImport.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup") }
            OutlinedButton(onClick = { csv.launch("spendsense-transactions.csv") }, modifier = Modifier.fillMaxWidth()) { Text("Export transactions as CSV") }
            Text("CSV files are not encrypted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    askPass?.let { mode ->
        TextInputDialog(if (mode == "export") "Choose a passphrase" else "Backup passphrase", "", "Passphrase", { askPass = null }, password = true) { p ->
            askPass = null
            if (p.isEmpty()) return@TextInputDialog
            if (mode == "export") {
                pass = p.toCharArray()
                export.launch("spendsense-${java.time.LocalDate.now()}.ssb")
            } else {
                val uri = importUri ?: return@TextInputDialog
                scope.launch {
                    runCatching {
                        val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                        withContext(Dispatchers.Default) { Backup.decrypt(bytes, p.toCharArray()) }
                    }.onSuccess { confirmRestore = it }.onFailure { toast("Wrong passphrase or not a SpendSense backup") }
                }
            }
        }
    }
    confirmRestore?.let { data ->
        ConfirmDialog(
            "Replace all data?", "This replaces everything on this phone with the backup (${data.txns.size} transactions).", "Restore",
            onConfirm = {
                confirmRestore = null
                scope.launch { runCatching { c.repository.restore(data) }.onSuccess { toast("Restored") }.onFailure { toast("Restore failed; nothing was changed") } }
            },
            onDismiss = { confirmRestore = null },
        )
    }
}

@Composable
fun AboutScreen(nav: NavController) {
    ScreenScaffold("About", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SpendSense ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
            Text("Your data never leaves this phone. The app has no internet permission, no accounts and no analytics. The database is encrypted.")
            Text("Supported banks: HDFC, City Union Bank, Axis (SMS). GPay and Paytm notifications add payee names.")
            Text(
                "Some formats are not yet verified with real samples (HDFC/Axis credits, CUB UPI credits, GPay and Paytm notifications). " +
                    "Messages that can't be read go to the Review inbox; turn on Debug capture mode to collect notification samples.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row { Icon(Icons.Filled.BugReport, contentDescription = null); Spacer(Modifier.size(8.dp)); Text("Personal build — not from the Play Store.") }
        }
    }
}

package com.krish.spendsense.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krish.spendsense.data.Account
import com.krish.spendsense.domain.Money
import com.krish.spendsense.ingest.Backfill
import com.krish.spendsense.ui.Perms
import com.krish.spendsense.ui.components.Dropdown
import com.krish.spendsense.ui.components.InfoCard
import com.krish.spendsense.ui.components.LocalContainer
import com.krish.spendsense.ui.components.MoneyField
import com.krish.spendsense.ui.components.SwitchRow
import com.krish.spendsense.ui.components.paiseToInput
import com.krish.spendsense.ui.rememberRefData
import kotlinx.coroutines.launch

private const val STEPS = 9

/** One short screen per step, each with a plain-language "why we need this" line. */
@Composable
fun OnboardingScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    val finish: () -> Unit = {
        scope.launch {
            c.repository.allAccounts().filterNot { it.named }.forEach { c.repository.updateAccount(it.copy(named = true)) }
            c.settings.setOnboardingDone(true)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        LinearProgressIndicator(progress = { (step + 1f) / STEPS }, modifier = Modifier.fillMaxWidth())
        Text("Step ${step + 1} of $STEPS", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                0 -> Welcome()
                1 -> SmsStep()
                2 -> ListenerStep()
                3 -> PostNotificationsStep()
                4 -> XiaomiStep()
                5 -> LimitStep()
                6 -> BackfillStep()
                7 -> SimLabelStep()
                else -> AccountsStep()
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") } else Spacer(Modifier.size(1.dp))
            Button(onClick = { if (step < STEPS - 1) step++ else finish() }) { Text(if (step < STEPS - 1) "Next" else "Finish") }
        }
    }
}

@Composable
private fun Title(text: String, why: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
    Text(why, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Welcome() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        androidx.compose.material3.Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.size(16.dp))
        Text("Welcome to SpendSense", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.size(8.dp))
        Text("Your data never leaves this phone.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(
            "SpendSense reads your bank SMS and GPay/Paytm alerts to track every UPI payment automatically. " +
                "It has no internet access, and everything is stored encrypted on this phone.",
        )
    }
}

@Composable
private fun SmsStep() {
    Title("Read bank SMS", "Why we need this: your bank texts you after every payment. We read only messages from your banks' sender IDs; everything else is ignored and never stored.")
    PermissionChecklistPart(sms = true)
}

@Composable
private fun ListenerStep() {
    Title("Notification access", "Why we need this: GPay and Paytm show who you paid. We read notifications from those two apps only.")
    PermissionChecklistPart(listener = true)
}

@Composable
private fun PostNotificationsStep() {
    Title("Allow alerts", "Why we need this: to tell you when you near your daily limit and to send your daily summary.")
    PermissionChecklistPart(post = true)
}

@Composable
private fun XiaomiStep() {
    Title("Keep it running on Xiaomi", "Why we need this: HyperOS/MIUI stops background apps. These three steps make sure no payment is missed. Tick each one when done.")
    PermissionChecklist(showXiaomi = true)
}

/** Subset of the permission checklist for one onboarding step. */
@Composable
private fun PermissionChecklistPart(sms: Boolean = false, listener: Boolean = false, post: Boolean = false) {
    val ctx = LocalContext.current
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose {}
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (Perms.phone(ctx)) scope.launch { c.repository.refreshSims(c.simDetector.current()) }
    }
    if (sms) {
        val ok = remember(tick) { Perms.sms(ctx) && Perms.phone(ctx) }
        HealthRow("SMS and SIM access", ok, "Also lets us tell your two SIMs apart", "Allow") { request.launch(Perms.SMS + Perms.PHONE) }
    }
    if (listener) {
        val ok = remember(tick) { Perms.listener(ctx) }
        HealthRow("Notification access", ok, "Find SpendSense in the list and turn it on", "Open settings") { Perms.openListenerSettings(ctx) }
    }
    if (post) {
        val ok = remember(tick) { Perms.postNotifications(ctx) }
        HealthRow("Alerts", ok, if (android.os.Build.VERSION.SDK_INT >= 33) "Tap Allow on the next prompt" else "Already allowed on this Android version", "Allow") {
            request.launch(arrayOf(Perms.POST))
        }
    }
}

@Composable
private fun LimitStep() {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    var value by rememberSaveable { mutableStateOf(paiseToInput(ref.settings.dailyLimitPaise)) }
    Title("Set a daily limit", "Why we need this: we'll show your day's spending against it and warn you at ${ref.settings.warnPct}%.")
    MoneyField(value, { v ->
        value = v
        Money.parse(v)?.takeIf { it > 0 }?.let { scope.launch { c.settings.setDailyLimit(it) } }
    }, "Daily limit", Modifier.fillMaxWidth())
    Text("You can change this anytime in Settings.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun BackfillStep() {
    val ctx = LocalContext.current
    val state by remember { Backfill.observe(ctx) }.collectAsStateWithLifecycle(null)
    Title("Read past 180 days", "Why we need this: so your insights work from day one. It reads only bank SMS already on your phone.")
    val s = state
    when {
        !Perms.sms(ctx) -> Text("SMS permission is off, so there's nothing to read yet. You can run this later from Settings.")
        s == null -> Button(onClick = { Backfill.start(ctx) }) { Text("Start") }
        s.finished -> Text("Done — checked ${s.total} messages.", color = MaterialTheme.colorScheme.primary)
        else -> {
            Text("Reading… ${s.done} of ${s.total}")
            LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, modifier = Modifier.fillMaxWidth())
            Text("You can continue; it keeps running in the background.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SimLabelStep() {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { c.repository.refreshSims(c.simDetector.current()) }
    Title("Label your SIMs", "Why we need this: bank alerts may come to different numbers. Labels like “Personal” or “Work” make that clear.")
    if (ref.sims.isEmpty()) Text("No SIMs detected. Allow SIM access in step 2, or skip — you can set this later in Settings → Numbers & SIMs.")
    ref.sims.filter { it.active }.forEach { sim ->
        InfoCard {
            Text("SIM ${sim.slot}${sim.carrier?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.titleMedium)
            var label by rememberSaveable(sim.id) { mutableStateOf(sim.label) }
            var number by rememberSaveable(sim.id) { mutableStateOf(sim.phoneNumber.orEmpty()) }
            OutlinedTextField(label, { label = it; scope.launch { c.repository.updateSim(sim.copy(label = it.ifBlank { "SIM ${sim.slot}" }, phoneNumber = number.ifBlank { null })) } },
                label = { Text("Label, e.g. Personal") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(number, { number = it; scope.launch { c.repository.updateSim(sim.copy(label = label.ifBlank { "SIM ${sim.slot}" }, phoneNumber = it.ifBlank { null })) } },
                label = { Text("Phone number (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
    Text("You can change this anytime in Settings.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AccountsStep() {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    Title("Confirm your accounts", "Why we need this: we found these from your bank SMS. Name them so they're easy to recognise.")
    if (ref.accounts.isEmpty()) Text("No accounts found yet. They'll be added automatically when the next bank SMS arrives.")
    val groups = ref.accounts.groupBy { it.simProfileId }
    groups.forEach { (simId, accounts) ->
        Text(simId?.let { id -> ref.sims.firstOrNull { it.id == id }?.let { "SIM ${it.slot} · ${it.label}" } } ?: "SIM not known", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        accounts.forEach { a -> AccountInlineEditor(a, ref.sims.map { it.id to "SIM ${it.slot} · ${it.label}" }) { scope.launch { c.repository.updateAccount(it) } } }
    }
    Text("You can change this anytime in Settings.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AccountInlineEditor(a: Account, sims: List<Pair<Long, String>>, onChange: (Account) -> Unit) {
    InfoCard {
        Text("${a.bank} ••${a.last4}", style = MaterialTheme.typography.titleMedium)
        var name by rememberSaveable(a.id) { mutableStateOf(a.nickname) }
        OutlinedTextField(name, { name = it; onChange(a.copy(nickname = it, named = true)) }, label = { Text("Nickname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (sims.isNotEmpty()) Dropdown("SIM", listOf<Long?>(null) + sims.map { it.first }, a.simProfileId, { id -> sims.firstOrNull { it.first == id }?.second ?: "Not set" },
            { onChange(a.copy(simProfileId = it)) }, Modifier.fillMaxWidth().padding(top = 8.dp))
        SwitchRow("Include in spending totals", null, a.includeInTotals) { onChange(a.copy(includeInTotals = it)) }
    }
}

package com.krish.spendsense.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.krish.spendsense.data.Txn
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.parse.Source
import com.krish.spendsense.ui.Routes
import com.krish.spendsense.ui.components.EmptyState
import com.krish.spendsense.ui.components.InfoCard
import com.krish.spendsense.ui.components.LocalContainer
import com.krish.spendsense.ui.components.ScreenScaffold
import com.krish.spendsense.ui.components.SectionTitle
import com.krish.spendsense.ui.components.TxnRow
import com.krish.spendsense.ui.rememberRefData
import kotlinx.coroutines.launch

/** Unparsed messages, uncategorised transactions and unknown accounts, each with one-tap resolution. */
@Composable
fun ReviewScreen(nav: NavController) {
    val c = LocalContainer.current
    val ref = rememberRefData()
    val scope = rememberCoroutineScope()
    val review by remember { c.repository.observeNeedsReview() }.collectAsStateWithLifecycle(emptyList())
    val unparsed by remember { c.repository.observeUnparsed() }.collectAsStateWithLifecycle(emptyList())
    var recat by remember { mutableStateOf<Txn?>(null) }
    var assign by remember { mutableStateOf<Txn?>(null) }
    val unknownAccount = review.filter { it.accountId == null && it.senderKey != null }
    val uncategorised = review.filter { it.needsReview && it !in unknownAccount }

    ScreenScaffold("Review inbox", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (review.isEmpty() && unparsed.isEmpty()) item {
                EmptyState(Icons.Filled.DoneAll, "All caught up", "Nothing needs your attention right now.")
            }
            if (unparsed.isNotEmpty()) item { SectionTitle("Messages we couldn't read (${unparsed.size})") }
            items(unparsed, key = { "u${it.id}" }) { m ->
                InfoCard {
                    Text(
                        "${if (m.source == Source.NOTIF) "Notification" else "SMS"} from ${m.sender} · ${Dates.day(m.timestamp)} ${Dates.time(m.timestamp)}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(m.body, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { nav.navigate(Routes.add(m.id)) }) { Text("Create transaction") }
                        OutlinedButton(onClick = { scope.launch { c.repository.dismissUnparsed(m.id) } }) { Text("Dismiss") }
                    }
                }
            }
            if (unknownAccount.isNotEmpty()) item { SectionTitle("Unknown account (${unknownAccount.size})") }
            items(unknownAccount, key = { "a${it.id}" }) { t ->
                TxnRow(t, t.categoryId?.let { ref.categoryById[it] }, null) { assign = t }
            }
            if (uncategorised.isNotEmpty()) item { SectionTitle("Uncategorised (${uncategorised.size})") }
            items(uncategorised, key = { "c${it.id}" }) { t ->
                TxnRow(t, t.categoryId?.let { ref.categoryById[it] }, t.accountId?.let { ref.accountById[it] }) { recat = t }
            }
        }
    }
    RecategorizeHost(recat, ref) { recat = null }
    assign?.let { t ->
        AccountPickerDialog(ref.accounts, showAlways = t.senderKey != null && t.accountTail != null, onDismiss = { assign = null }) { a, always ->
            scope.launch { c.repository.assignAccount(t.id, a.id, always) }
            assign = null
        }
    }
}

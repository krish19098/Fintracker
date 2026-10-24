@file:OptIn(ExperimentalMaterial3Api::class)

package com.krish.spendsense.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.krish.spendsense.AppContainer
import com.krish.spendsense.data.Account
import com.krish.spendsense.data.Category
import com.krish.spendsense.data.CategoryKind
import com.krish.spendsense.data.Txn
import com.krish.spendsense.domain.Dates
import com.krish.spendsense.domain.Money
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.Source
import com.krish.spendsense.parse.TxnApp
import com.krish.spendsense.ui.theme.Good

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

fun colorOf(argb: Long) = Color(argb.toInt())

val ICONS: Map<String, ImageVector> = linkedMapOf(
    "restaurant" to Icons.Filled.Restaurant,
    "cart" to Icons.Filled.ShoppingCart,
    "car" to Icons.Filled.DirectionsCar,
    "bolt" to Icons.Filled.Bolt,
    "bag" to Icons.Filled.ShoppingBag,
    "movie" to Icons.Filled.Movie,
    "health" to Icons.Filled.LocalHospital,
    "home" to Icons.Filled.Home,
    "people" to Icons.Filled.People,
    "school" to Icons.Filled.School,
    "more" to Icons.Filled.MoreHoriz,
    "help" to Icons.AutoMirrored.Filled.HelpOutline,
    "income" to Icons.Filled.Savings,
    "bank" to Icons.Filled.AccountBalance,
    "card" to Icons.Filled.CreditCard,
    "wallet" to Icons.Filled.AccountBalanceWallet,
    "work" to Icons.Filled.Work,
    "category" to Icons.Filled.Category,
)

fun iconFor(key: String?): ImageVector = ICONS[key] ?: Icons.Filled.Category

val COLORS: List<Long> = listOf(
    0xFF0F766E, 0xFF2A9D8F, 0xFF457B9D, 0xFF0077B6, 0xFF7209B7, 0xFFB5179E,
    0xFFE76F51, 0xFFF4A261, 0xFFD62828, 0xFF588157, 0xFF6D597A, 0xFF8D99AE,
)

@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { actions() },
            )
        },
        floatingActionButton = fab,
        content = content,
    )
}

/** Circular progress ring; [fraction] may exceed 1 (drawn full). */
@Composable
fun ProgressRing(fraction: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 14.dp, content: @Composable () -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Category donut drawn with Canvas (Vico has no pie chart). */
@Composable
fun DonutChart(slices: List<Pair<Color, Long>>, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val total = slices.sumOf { it.second }.toFloat()
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.minDimension * 0.16f
            val arcSize = Size(size.width - w, size.height - w)
            val topLeft = Offset(w / 2, w / 2)
            if (total <= 0f) {
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
                return@Canvas
            }
            var start = -90f
            slices.forEach { (c, v) ->
                val sweep = 360f * v / total
                drawArc(c, start, sweep, false, topLeft, arcSize, style = Stroke(w))
                start += sweep
            }
        }
        content()
    }
}

@Composable
fun CategoryAvatar(category: Category?, size: Dp = 40.dp) {
    val c = category?.let { colorOf(it.color) } ?: MaterialTheme.colorScheme.outline
    Box(
        Modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(iconFor(category?.icon), contentDescription = category?.name ?: "Uncategorised", tint = c, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.secondary) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

fun sourceLabels(t: Txn): List<String> = buildList {
    if (t.hasSource(Source.SMS)) add("SMS")
    when (t.app) {
        TxnApp.GPAY -> add("GPay")
        TxnApp.PAYTM -> add("Paytm")
        else -> if (t.hasSource(Source.NOTIF)) add("App")
    }
    if (t.hasSource(Source.MANUAL)) add("Manual")
    if (t.hasSource(Source.IMPORT)) add("Import")
}

@Composable
fun AmountText(t: Txn, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium) {
    val credit = t.direction == Direction.CREDIT
    Text(
        (if (credit) "+" else "−") + Money.format(t.amountPaise),
        style = style, fontWeight = FontWeight.SemiBold,
        color = when {
            t.excludedReason != null -> MaterialTheme.colorScheme.outline
            credit -> Good
            else -> MaterialTheme.colorScheme.onSurface
        },
    )
}

@Composable
fun TxnRow(t: Txn, category: Category?, account: Account?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(category)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.displayPayee, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(Dates.time(t.timestamp), account?.label ?: "Unassigned".takeIf { t.accountId == null && t.senderKey != null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                t.excludedReason?.let { Tag(excludedLabel(it.name)) }
                if (t.needsReview) Tag("Review", MaterialTheme.colorScheme.error)
            }
        }
        AmountText(t)
    }
}

fun excludedLabel(reason: String) = when (reason) {
    "SELF_TRANSFER" -> "Self-transfer"
    "CC_BILL" -> "Card bill"
    "REFUND" -> "Refund"
    else -> "Excluded"
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.size(12.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp))

@Composable
fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
        if (onClick != null && icon != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun MoneyField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) = OutlinedTextField(
    value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }) },
    label = { Text(label) }, prefix = { Text("₹") }, singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
)

/** Rupee text for an editable field, e.g. 100000 paise -> "1000". */
fun paiseToInput(paise: Long?): String = paise?.let { if (it % 100 == 0L) (it / 100).toString() else "${it / 100}.${(it % 100).toString().padStart(2, '0')}" } ?: ""

@Composable
fun CategorySheet(categories: List<Category>, kind: CategoryKind, title: String = "Choose category", onDismiss: () -> Unit, onPick: (Category) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            items(categories.filter { it.kind == kind }, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onPick(c) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryAvatar(c, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(c.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, dismiss: String = "Cancel") =
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )

@Composable
fun TextInputDialog(
    title: String, initial: String, label: String, onDismiss: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text, password: Boolean = false,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            OutlinedTextField(
                value, { value = it }, label = { Text(label) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TimeDialog(minuteOfDay: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(minuteOfDay / 60, minuteOfDay % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun minuteLabel(m: Int): String {
    val h = m / 60
    val mm = (m % 60).toString().padStart(2, '0')
    return "${if (h % 12 == 0) 12 else h % 12}:$mm ${if (h < 12) "am" else "pm"}"
}

@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T, display: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = display(selected), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(display(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val m = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    if (onClick != null) {
        androidx.compose.material3.ElevatedCard(onClick = onClick, modifier = m) { Column(Modifier.padding(16.dp)) { content() } }
    } else {
        androidx.compose.material3.ElevatedCard(modifier = m) { Column(Modifier.padding(16.dp)) { content() } }
    }
}

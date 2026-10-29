package app.mono.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.Currency
import app.mono.domain.FundingStatus
import app.mono.domain.Money
import app.mono.finance.ui.theme.Mono
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: Dates

private val zone: ZoneId get() = ZoneId.systemDefault()

fun LocalDate.toMillis(): Long = atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
/** "29 Oct 2026" */
fun Long.short(): String = toLocalDate().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
fun LocalDate.short(): String = format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))

// MARK: Display pieces

enum class Tone { INFO, WARNING, DANGER }

@Composable
fun Callout(text: String, tone: Tone = Tone.INFO) {
    val c = Mono.colors
    val tint = when (tone) { Tone.INFO -> c.accent; Tone.WARNING -> c.warning; Tone.DANGER -> c.danger }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f))
            .border(0.5.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(tint))
        Text(text, color = c.ink, fontSize = 14.sp)
    }
}

@Composable
fun StatusChip(text: String, tint: Color) {
    Text(
        text, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(CircleShape).background(tint.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Sage normally, bronze from 80%, clay when over. */
@Composable
fun progressTint(fraction: Double): Color {
    val c = Mono.colors
    return when { fraction > 1.0 -> c.danger; fraction >= 0.8 -> c.warning; else -> c.accent }
}

@Composable
fun statusTint(status: FundingStatus): Color {
    val c = Mono.colors
    return when (status) {
        FundingStatus.FUNDED, FundingStatus.ON_TRACK -> c.accent
        FundingStatus.BEHIND -> c.warning
        FundingStatus.OVERDUE -> c.danger
        FundingStatus.OPEN -> c.inkSecondary
    }
}

fun statusText(status: FundingStatus) = when (status) {
    FundingStatus.FUNDED -> "Funded"
    FundingStatus.ON_TRACK -> "On track"
    FundingStatus.BEHIND -> "Behind plan"
    FundingStatus.OVERDUE -> "Overdue"
    FundingStatus.OPEN -> "No deadline"
}

@Composable
fun EmptyState(title: String, message: String, actionTitle: String? = null, onAction: (() -> Unit)? = null) {
    val c = Mono.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(message, color = c.inkSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (actionTitle != null && onAction != null) {
            Text(actionTitle, color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp).clickableNoRipple(onAction))
        }
    }
}

@Composable
fun ProgressRow(
    symbol: String, title: String, subtitle: String, trailing: String, value: Double, tint: Color,
    note: String? = null, noteTint: Color = Mono.colors.inkSecondary, modifier: Modifier = Modifier,
) {
    val c = Mono.colors
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Icon(symbolIcon(symbol), null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, color = c.ink, fontSize = 16.sp, maxLines = 1)
                Text(subtitle, color = c.inkSecondary, fontSize = 13.sp, maxLines = 1)
            }
            Text(trailing, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        ProgressLine(value, tint)
        if (note != null) Text(note, color = noteTint, fontSize = 12.sp)
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, tint: Color = Mono.colors.ink, footnote: String? = null) {
    val c = Mono.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = c.inkSecondary, fontSize = 12.sp)
        Text(value, color = tint, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        if (footnote != null) Text(footnote, color = c.inkTertiary, fontSize = 11.sp)
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Mono.colors
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
        shape = RoundedCornerShape(Mono.radius),
    ) { Text(text) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(Mono.radius)) {
        Text(text, color = Mono.colors.ink)
    }
}

// MARK: Form pieces

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Mono.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Mono.gutter).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Mono.colors.accent, unfocusedBorderColor = Mono.colors.line,
    focusedLabelColor = Mono.colors.accent, unfocusedLabelColor = Mono.colors.inkSecondary,
    focusedTextColor = Mono.colors.ink, unfocusedTextColor = Mono.colors.ink, cursorColor = Mono.colors.accent,
)

@Composable
fun TextInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, tag: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        modifier = modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it }, colors = fieldColors(),
    )
}

/** An amount in [currency]; shows an error for input that can't be an exact amount (it never rounds silently). */
@Composable
fun AmountInput(value: String, onChange: (String) -> Unit, label: String, currency: Currency? = null, tag: String? = null) {
    val cur = currency ?: Money.reporting
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text("$label (${cur.symbol})") }, singleLine = true,
        isError = value.isNotEmpty() && Money.parse(value, cur) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it }, colors = fieldColors(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate, onChange: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Mono.radius)) {
        Text("$label  ${date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))}", color = Mono.colors.ink)
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    val c = Mono.colors
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { o ->
            FilterChip(
                selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = c.accentSoft, selectedLabelColor = c.accent, containerColor = c.surface, labelColor = c.ink,
                ),
            )
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = Mono.colors.inkSecondary, fontSize = 13.sp)
}

@Composable
fun ConfirmDialog(
    title: String, message: String, confirm: String, destructive: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    val c = Mono.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = if (destructive) c.danger else c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

package app.mono.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.AccountEngine
import app.mono.domain.Currency
import app.mono.domain.Money
import app.mono.domain.Tags
import app.mono.domain.TxKind
import app.mono.domain.model.TransactionRecord
import app.mono.finance.data.LedgerState
import app.mono.finance.ui.theme.Mono
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Add or edit an income or expense. Transfers arrive with the Accounts screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionForm(
    ledger: LedgerState,
    editing: TransactionRecord?,
    initialKind: TxKind = TxKind.EXPENSE,
    onSave: (TransactionRecord) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Mono.colors
    val zone = ZoneId.systemDefault()
    val account = AccountEngine.defaultAccount(ledger.accounts)
    val currency = (editing?.accountID?.let { id -> ledger.accounts.firstOrNull { it.id == id } } ?: account)?.currency ?: Money.reporting

    var kind by remember { mutableStateOf(editing?.kind?.takeIf { it != TxKind.TRANSFER } ?: initialKind) }
    var amount by remember { mutableStateOf(editing?.let { Money.editable(it.amountCents, currency) } ?: "") }
    var category by remember { mutableStateOf(editing?.category ?: "") }
    var title by remember { mutableStateOf(editing?.title ?: "") }
    var notes by remember { mutableStateOf(editing?.notes ?: "") }
    var tags by remember { mutableStateOf(editing?.tags?.replace(",", ", ") ?: "") }
    var date by remember { mutableStateOf(editing?.let { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() } ?: LocalDate.now()) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val cats = ledger.categories.filter { it.kindRaw == kind.raw }
    val parsed = Money.parse(amount, currency)
    val valid = parsed != null && parsed > 0

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Mono.gutter).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (editing == null) "New transaction" else "Edit transaction", color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TxKind.EXPENSE, TxKind.INCOME).forEach { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k; category = "" }, label = { Text(k.label) }, colors = chipColors())
                }
            }

            OutlinedTextField(
                value = amount, onValueChange = { amount = it }, label = { Text("Amount (${currency.symbol})") }, singleLine = true,
                isError = amount.isNotEmpty() && parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("amount"), colors = fieldColors(),
            )

            Text("Category", color = c.inkSecondary, fontSize = 13.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cats, key = { it.pk }) { cat ->
                    FilterChip(selected = category == cat.name, onClick = { category = cat.name }, label = { Text(cat.name) }, colors = chipColors())
                }
            }

            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Tags (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(), colors = fieldColors())

            OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())), color = c.ink)
            }

            Button(
                enabled = valid && category.isNotBlank(),
                onClick = {
                    val millis = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                    val base = editing ?: TransactionRecord(accountID = account?.id)
                    onSave(base.copy(amountCents = parsed ?: 0, kindRaw = kind.raw, category = category, date = millis,
                        title = title.trim(), notes = notes.trim(), tags = Tags.normalize(tags)))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
            ) { Text(if (editing == null) "Save" else "Save changes") }
            if (valid && category.isBlank()) Text("Choose a category.", color = c.inkTertiary, fontSize = 12.sp)

            if (editing != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete transaction", color = c.danger) }
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    if (confirmDelete && editing != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            text = { Text("It will be removed from your balance and reports. This can’t be undone.") },
            confirmButton = { TextButton(onClick = { onDelete(editing.id); confirmDelete = false; onDismiss() }) { Text("Delete", color = c.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Mono.colors.accentSoft, selectedLabelColor = Mono.colors.accent,
    containerColor = Mono.colors.surface, labelColor = Mono.colors.ink,
)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Mono.colors.accent, unfocusedBorderColor = Mono.colors.line,
    focusedLabelColor = Mono.colors.accent, unfocusedLabelColor = Mono.colors.inkSecondary,
    focusedTextColor = Mono.colors.ink, unfocusedTextColor = Mono.colors.ink, cursorColor = Mono.colors.accent,
)

package app.mono.finance.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.CsvImport
import app.mono.domain.Money
import app.mono.domain.TxKind
import app.mono.finance.MonoViewModel
import app.mono.finance.data.LedgerState
import app.mono.finance.ui.Callout
import app.mono.finance.ui.PrimaryButton
import app.mono.finance.ui.SecondaryButton
import app.mono.finance.ui.SectionTitle
import app.mono.finance.ui.Tone
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono

@Composable
fun CsvImportScreen(ledger: LedgerState?, vm: MonoViewModel, onBack: () -> Unit) {
    val c = Mono.colors
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<List<String>>?>(null) }
    var mapping by remember { mutableStateOf(CsvImport.Mapping()) }
    var createCategories by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw CsvImport.ImportException("That file couldn’t be read.")
                val rows = CsvImport.parse(CsvImport.decode(bytes))
                records = rows; mapping = CsvImport.autoMapping(rows); error = null
                fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            } catch (e: CsvImport.ImportException) { error = e.message; records = null }
            catch (e: Exception) { error = "That file couldn’t be read."; records = null }
        }
    }

    ScreenFrame("Import from CSV", onBack) {
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bring in a bank statement or spreadsheet. MONO only adds records; nothing you already have is changed, and rows that match an existing transaction are skipped.",
                color = c.inkSecondary, fontSize = 13.sp)
            SecondaryButton(if (records == null) "Choose a CSV file" else "Choose a different file", Modifier.fillMaxWidth()) {
                pick.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel", "*/*"))
            }
            error?.let { Callout(it, Tone.WARNING) }
        }

        val rows = records ?: return@ScreenFrame
        val header = rows.first()
        val width = rows.take(50).maxOf { it.size }
        fun label(i: Int) = if (mapping.hasHeader && header.getOrNull(i)?.isNotBlank() == true) header[i].trim().take(24) else "Column ${i + 1}"

        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Columns", fileName)
            ToggleRow("First row is a header", mapping.hasHeader) { mapping = mapping.copy(hasHeader = it) }
            Picker("Date", mapping.date, width, ::label) { mapping = mapping.copy(date = it) }
            Picker("Description", mapping.title, width, ::label) { mapping = mapping.copy(title = it) }
            ChoiceRow("Amounts", CsvImport.AmountMode.entries, mapping.mode, { it.label }) { mapping = mapping.copy(mode = it) }
            when (mapping.mode) {
                CsvImport.AmountMode.SIGNED -> Picker("Amount", mapping.amount, width, ::label) { mapping = mapping.copy(amount = it) }
                CsvImport.AmountMode.DEBIT_CREDIT -> {
                    Picker("Debit (money out)", mapping.debit, width, ::label) { mapping = mapping.copy(debit = it) }
                    Picker("Credit (money in)", mapping.credit, width, ::label) { mapping = mapping.copy(credit = it) }
                }
                CsvImport.AmountMode.TYPE_COLUMN -> {
                    Picker("Amount", mapping.amount, width, ::label) { mapping = mapping.copy(amount = it) }
                    Picker("Type", mapping.type, width, ::label) { mapping = mapping.copy(type = it) }
                }
            }
            Picker("Category (optional)", mapping.category, width, ::label, optional = true) { mapping = mapping.copy(category = it) }
            Picker("Notes (optional)", mapping.notes, width, ::label, optional = true) { mapping = mapping.copy(notes = it) }
            ChoiceRow("Date format", CsvImport.DateFormat.entries, mapping.dateFormat, { it.label }) { mapping = mapping.copy(dateFormat = it) }
            if (mapping.mode == CsvImport.AmountMode.SIGNED) ToggleRow("Charges are positive (flip signs)", mapping.invertSigns) { mapping = mapping.copy(invertSigns = it) }
            ToggleRow("Create categories that don’t exist yet", createCategories) { createCategories = it }
        }

        val ready = ledger != null
        val plan = if (ready) CsvImport.plan(rows, mapping, ledger!!.transactions, ledger.categories, createCategories) else CsvImport.Plan()
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Preview")
            Text("${plan.rows.size} to import · ${plan.duplicates} already here · ${plan.skipped.size} skipped", color = c.ink, fontSize = 16.sp)
            if (plan.rows.isNotEmpty()) Text("Income ${Money.format(plan.income)} · Spending ${Money.format(plan.expense)}", color = c.inkSecondary, fontSize = 13.sp)
            if (plan.newCategories.isNotEmpty()) Text("New categories: " + plan.newCategories.joinToString { it.name }, color = c.inkSecondary, fontSize = 13.sp)
            plan.rows.take(5).forEach { r ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${r.date} · ${r.title.ifBlank { r.category }}", color = c.ink, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text((if (r.kind == TxKind.EXPENSE) "−" else "+") + Money.format(r.cents), color = c.inkSecondary, fontSize = 13.sp)
                }
            }
            if (plan.rows.size > 5) Text("and ${plan.rows.size - 5} more", color = c.inkTertiary, fontSize = 12.sp)
            if (plan.skipped.isNotEmpty()) {
                Text("Skipped", color = c.inkSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                plan.skipped.take(5).forEach { Text("Line ${it.line}: ${it.reason}", color = c.inkTertiary, fontSize = 12.sp) }
                if (plan.skipped.size > 5) Text("and ${plan.skipped.size - 5} more", color = c.inkTertiary, fontSize = 12.sp)
            }
            PrimaryButton(if (plan.rows.isEmpty()) "Nothing to import" else "Import ${plan.rows.size}", enabled = plan.rows.isNotEmpty()) {
                vm.importCsv(plan, null) { records = null; error = null }
            }
        }
    }
}

@Composable
private fun ToggleRow(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Mono.colors.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Picker(title: String, value: Int?, width: Int, label: (Int) -> String, optional: Boolean = false, onPick: (Int?) -> Unit) {
    val c = Mono.colors
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Column {
            TextButton(onClick = { open = true }) { Text(value?.let(label) ?: "None", color = c.accent) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (optional) DropdownMenuItem(text = { Text("None") }, onClick = { onPick(null); open = false })
                (0 until width).forEach { i -> DropdownMenuItem(text = { Text(label(i)) }, onClick = { onPick(i); open = false }) }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, value: T, name: (T) -> String, onPick: (T) -> Unit) {
    val c = Mono.colors
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Column {
            TextButton(onClick = { open = true }) { Text(name(value), color = c.accent, fontSize = 13.sp) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(name(o)) }, onClick = { onPick(o); open = false }) }
            }
        }
    }
}

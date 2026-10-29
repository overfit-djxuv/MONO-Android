package app.mono.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.Money
import app.mono.domain.PaymentStatus
import app.mono.domain.RecurrenceUnit
import app.mono.domain.SubscriptionSchedule
import app.mono.domain.TxKind
import app.mono.domain.annualCostEstimate
import app.mono.domain.anchorDay
import app.mono.domain.dueDate
import app.mono.domain.model.RecurringRule
import app.mono.domain.monthlyCostEstimate
import app.mono.domain.scheduleText
import app.mono.finance.MonoViewModel
import app.mono.finance.data.LedgerState
import app.mono.finance.ui.AmountInput
import app.mono.finance.ui.ChipRow
import app.mono.finance.ui.ConfirmDialog
import app.mono.finance.ui.DateField
import app.mono.finance.ui.EmptyState
import app.mono.finance.ui.FormSheet
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.Metric
import app.mono.finance.ui.PrimaryButton
import app.mono.finance.ui.SecondaryButton
import app.mono.finance.ui.SectionLabel
import app.mono.finance.ui.SectionTitle
import app.mono.finance.ui.StatusChip
import app.mono.finance.ui.TextInput
import app.mono.finance.ui.clickableNoRipple
import app.mono.finance.ui.fmt
import app.mono.finance.ui.short
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono
import app.mono.finance.ui.theme.figure
import app.mono.finance.ui.toMillis
import java.time.LocalDate

@Composable
fun SubscriptionsScreen(ledger: LedgerState?, vm: MonoViewModel, onBack: () -> Unit) {
    val c = Mono.colors
    var edit by remember { mutableStateOf<RecurringRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    if (ledger == null) return
    val today = LocalDate.now()

    val rules = ledger.rules
    val active = rules.filter { it.status == PaymentStatus.ACTIVE && it.kind == TxKind.EXPENSE }
    val monthly = active.sumOf { it.monthlyCostEstimate }
    val annual = active.sumOf { it.annualCostEstimate }
    val open = rules.firstOrNull { it.id == detail }

    ScreenFrame("Subscriptions & bills", onBack) {
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Active", "${active.size}", Modifier.weight(1f))
                Metric("Typical month", fmt(monthly), Modifier.weight(1f), footnote = "an estimate")
                Metric("Per year", fmt(annual), Modifier.weight(1f), footnote = "an estimate")
            }
            Text("Nothing is deducted automatically. When a payment is due, confirm it to record it as a real expense.", color = c.inkTertiary, fontSize = 12.sp)
        }

        if (rules.isEmpty()) EmptyState("No subscriptions or bills yet", "Add the payments that repeat, like streaming, rent or insurance, to see what is coming and what they cost.",
            "Add one") { adding = true }
        else {
            val groups = listOf(
                "Due or coming up" to rules.filter { it.status == PaymentStatus.ACTIVE }.sortedBy { it.dueDate() },
                "Paused" to rules.filter { it.status == PaymentStatus.PAUSED },
                "Cancelled" to rules.filter { it.status == PaymentStatus.CANCELLED },
            )
            for ((title, list) in groups) if (list.isNotEmpty()) Column(Modifier.fillMaxWidth().surface()) {
                SectionTitle(title, if (title.startsWith("Due")) "Add" else null) { adding = true }
                list.forEachIndexed { i, r ->
                    val due = r.dueDate()
                    val overdue = r.status == PaymentStatus.ACTIVE && due.isBefore(today)
                    Row(Modifier.fillMaxWidth().clickableNoRipple { detail = r.id }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, color = c.ink, fontSize = 16.sp, maxLines = 1)
                            Text("${r.scheduleText} · " + if (r.status == PaymentStatus.ACTIVE) (if (overdue) "Overdue since " else "Next ") + due.short() else r.status.label,
                                color = if (overdue) c.warning else c.inkSecondary, fontSize = 12.sp)
                        }
                        Text(fmt(r.amountCents), color = if (r.kind == TxKind.INCOME) c.income else c.ink, style = figure(15))
                    }
                    if (i < list.size - 1) Hairline()
                }
            }
        }
    }

    if (adding) RuleSheet(null, ledger, vm) { adding = false }
    edit?.let { RuleSheet(it, ledger, vm) { edit = null } }
    open?.let { r -> RuleDetail(r, ledger, vm, onEdit = { edit = r; detail = null }) { detail = null } }
}

@Composable
private fun RuleDetail(rule: RecurringRule, ledger: LedgerState, vm: MonoViewModel, onEdit: () -> Unit, close: () -> Unit) {
    val c = Mono.colors
    var recording by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val due = rule.dueDate()
    FormSheet(rule.title, close) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(rule.amountCents), color = c.ink, style = figure(28, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            StatusChip(rule.status.label, if (rule.status == PaymentStatus.ACTIVE) c.accent else c.inkSecondary)
        }
        Text("${rule.scheduleText} · about ${fmt(rule.monthlyCostEstimate)} a month, ${fmt(rule.annualCostEstimate)} a year (an estimate)", color = c.inkSecondary, fontSize = 13.sp)
        if (rule.status == PaymentStatus.ACTIVE) {
            Text("Next payment ${due.short()}", color = c.ink, fontSize = 15.sp)
            PrimaryButton("Record this payment") { recording = true }
            SecondaryButton("Skip this one", Modifier.fillMaxWidth()) { vm.skipPayment(rule); close() }
            SecondaryButton("Pause", Modifier.fillMaxWidth()) { vm.pause(rule); close() }
        } else {
            PrimaryButton("Resume") { vm.resume(rule); close() }
            Text("Nothing missed while it was ${rule.status.label.lowercase()} is owed.", color = c.inkTertiary, fontSize = 12.sp)
        }
        SecondaryButton("Edit", Modifier.fillMaxWidth(), onClick = onEdit)
        if (rule.status != PaymentStatus.CANCELLED) TextButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth()) { Text("Cancel subscription", color = c.warning) }
        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete", color = c.danger) }
    }
    if (recording) RecordSheet(rule, ledger, vm) { recording = false; close() }
    if (confirmCancel) ConfirmDialog("Cancel “${rule.title}”?", "It stays in your list for history but never comes due again and isn’t counted in forecasts.", "Cancel it",
        true, onConfirm = { vm.cancel(rule); close() }, onDismiss = { confirmCancel = false })
    if (confirmDelete) ConfirmDialog("Delete “${rule.title}”?", "Payments you already recorded stay in your transactions.", "Delete", true,
        onConfirm = { vm.deleteRule(rule); close() }, onDismiss = { confirmDelete = false })
}

@Composable
private fun RecordSheet(rule: RecurringRule, ledger: LedgerState, vm: MonoViewModel, close: () -> Unit) {
    val accounts = ledger.accounts.filter { !it.isArchived }
    var accountId by remember { mutableStateOf(rule.accountID ?: accounts.firstOrNull()?.id) }
    val currency = accounts.firstOrNull { it.id == accountId }?.currency
    var text by remember { mutableStateOf(Money.editable(rule.amountCents, currency)) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    val cents = Money.parse(text, currency)?.takeIf { it > 0 }
    FormSheet("Record payment", close) {
        AmountInput(text, { text = it }, "Amount paid", currency)
        DateField("Paid on", date) { date = it }
        if (accounts.size > 1) { SectionLabel("Account"); ChipRow(accounts, accounts.firstOrNull { it.id == accountId }, { it.name }) { accountId = it.id } }
        PrimaryButton("Record", enabled = cents != null) { vm.recordPayment(rule, date.toMillis(), cents, accountId); close() }
    }
}

@Composable
private fun RuleSheet(editing: RecurringRule?, ledger: LedgerState, vm: MonoViewModel, close: () -> Unit) {
    val accounts = ledger.accounts.filter { !it.isArchived }
    val cats = ledger.categories.map { it.name }.distinct()
    var title by remember { mutableStateOf(editing?.title ?: "") }
    var accountId by remember { mutableStateOf(editing?.accountID ?: accounts.firstOrNull()?.id) }
    val currency = accounts.firstOrNull { it.id == accountId }?.currency
    var text by remember { mutableStateOf(editing?.let { Money.editable(it.amountCents, currency) } ?: "") }
    var kind by remember { mutableStateOf(editing?.kind ?: TxKind.EXPENSE) }
    var category by remember { mutableStateOf(editing?.category ?: "Subscriptions") }
    var unit by remember { mutableStateOf(editing?.unit ?: RecurrenceUnit.MONTH) }
    var interval by remember { mutableStateOf((editing?.interval ?: 1).toString()) }
    var next by remember { mutableStateOf(editing?.dueDate() ?: LocalDate.now().plusDays(7)) }
    var remind by remember { mutableStateOf((editing?.remindDaysBefore ?: 1).toString()) }
    var notes by remember { mutableStateOf(editing?.notes ?: "") }
    val cents = Money.parse(text, currency)?.takeIf { it > 0 }
    val n = interval.toIntOrNull()?.takeIf { it in 1..60 }

    FormSheet(if (editing == null) "New subscription or bill" else "Edit", close) {
        TextInput(title, { title = it }, "Name")
        AmountInput(text, { text = it }, "Amount each time", currency)
        SectionLabel("Type"); ChipRow(listOf(TxKind.EXPENSE, TxKind.INCOME), kind, { it.label }) { kind = it }
        SectionLabel("Category"); ChipRow(cats, category, { it }) { category = it }
        SectionLabel("Repeats every"); Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(0.4f)) { TextInput(interval, { interval = it.filter(Char::isDigit).take(2) }, "Number") }
            Column(Modifier.weight(1f)) { ChipRow(RecurrenceUnit.entries, unit, { it.label }) { unit = it } }
        }
        Text(SubscriptionSchedule.describe(unit, n ?: 1), color = Mono.colors.inkSecondary, fontSize = 13.sp)
        DateField("Next payment", next) { next = it }
        if (accounts.size > 1) { SectionLabel("Paid from"); ChipRow(accounts, accounts.firstOrNull { it.id == accountId }, { it.name }) { accountId = it.id } }
        TextInput(remind, { remind = it.filter(Char::isDigit).take(2) }, "Remind me this many days before (0 = on the day)")
        TextInput(notes, { notes = it }, "Notes (optional)")
        PrimaryButton("Save", enabled = cents != null && title.isNotBlank() && n != null) {
            val base = editing ?: RecurringRule()
            vm.saveRule(base.copy(
                title = title.trim(), amountCents = cents ?: 0, kindRaw = kind.raw, category = category, unitRaw = unit.raw, interval = n ?: 1,
                dayOfMonth = next.dayOfMonth, nextDueDate = next.toMillis(), nextMonthKey = app.mono.domain.MonthKey.of(next).string,
                accountID = accountId, remindDaysBefore = (remind.toIntOrNull() ?: 1).coerceIn(0, 30), notes = notes.trim(),
            ))
            close()
        }
    }
}

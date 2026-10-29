package app.mono.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.Books
import app.mono.domain.Money
import app.mono.domain.SafeToSpend
import app.mono.domain.model.ReservedFund
import app.mono.finance.MonoViewModel
import app.mono.finance.data.LedgerState
import app.mono.finance.ui.AmountInput
import app.mono.finance.ui.Callout
import app.mono.finance.ui.ChipRow
import app.mono.finance.ui.ConfirmDialog
import app.mono.finance.ui.FormSheet
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.LocalMoneyContext
import app.mono.finance.ui.PrimaryButton
import app.mono.finance.ui.SectionTitle
import app.mono.finance.ui.TextInput
import app.mono.finance.ui.Tone
import app.mono.finance.ui.clickableNoRipple
import app.mono.finance.ui.fmt
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono
import app.mono.finance.ui.theme.figure
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A secondary screen: a back arrow, a title and scrolling content. */
@Composable
fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val c = Mono.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Mono.gutter).padding(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back", tint = c.ink) }
            Text(title, color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
fun SafeToSpendScreen(ledger: LedgerState?, vm: MonoViewModel, onBack: () -> Unit) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    val config by vm.safeConfig.collectAsState()
    var addReserved by remember { mutableStateOf(false) }
    var editReserved by remember { mutableStateOf<ReservedFund?>(null) }
    if (ledger == null) return

    val books = remember(ledger, money) { Books(ledger.accounts, ledger.rates, ledger.transactions, ledger.entries, reporting = money.reporting) }
    val today = LocalDate.now()
    val r = SafeToSpend.make(today, config, books, ledger.planned, ledger.rules, ledger.goals, ledger.costs, ledger.entries, ledger.categories,
        ledger.limits, ledger.monthBudgets, ledger.reserved)

    ScreenFrame("Safe to spend", onBack) {
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (r.isShortfall) "Short for what’s committed" else "You can spend", color = c.inkSecondary, fontSize = 13.sp)
            Text(fmt(kotlin.math.abs(r.flexible)), color = if (r.isShortfall) c.danger else c.ink, style = figure(40, FontWeight.SemiBold))
            Text("until ${r.horizonEnd.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}" +
                (r.perDay?.let { " · about ${fmt(it)} a day" } ?: ""), color = c.inkSecondary, fontSize = 13.sp)
            if (r.isShortfall) Callout("Your bills, planned savings and essentials add up to ${fmt(-r.flexible)} more than the money in your accounts.", Tone.DANGER)
            if (r.unconvertedAccounts.isNotEmpty()) Callout("${r.unconvertedAccounts.joinToString(", ")} isn’t counted: no exchange rate yet.", Tone.WARNING)
        }

        // Horizon
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Look ahead")
            val options = listOf<SafeToSpend.Horizon>(SafeToSpend.Horizon.EndOfMonth, SafeToSpend.Horizon.Days(7), SafeToSpend.Horizon.Days(14), SafeToSpend.Horizon.Days(30))
            ChipRow(options, config.horizon, { when (it) { SafeToSpend.Horizon.EndOfMonth -> "End of month"; is SafeToSpend.Horizon.Days -> "${it.n} days" } }) {
                vm.setSafeConfig(config.copy(horizon = it))
            }
        }

        // The sum
        Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("How it adds up")
            Line("Money in your accounts", r.accountMoney, positive = true)
            Hairline()
            Toggled("Bills and payments due", r.billsTotal, config.includeBills) { vm.setSafeConfig(config.copy(includeBills = it)) }
            r.bills.forEach { Detail(it.title, it.detail, it.amountCents) }
            Hairline()
            Toggled("Planned savings", r.savingsTotal, config.includeSavings) { vm.setSafeConfig(config.copy(includeSavings = it)) }
            r.savings.forEach { Detail(it.title, it.detail, it.amountCents) }
            Hairline()
            Toggled("Essential spending left", r.essentialsTotal, config.includeEssentials) { vm.setSafeConfig(config.copy(includeEssentials = it)) }
            r.essentials.forEach { Detail(it.title, it.detail, it.amountCents) }
            Hairline()
            Toggled("Set aside by you", r.reservedTotal, config.includeReserved) { vm.setSafeConfig(config.copy(includeReserved = it)) }
            Hairline()
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Safe to spend", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(fmt(r.flexible), color = if (r.isShortfall) c.danger else c.ink, style = figure(18, FontWeight.SemiBold))
            }
        }

        // Accounts included
        if (ledger.accounts.size > 1) Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Accounts included")
            ledger.accounts.filter { !it.isArchived }.forEach { a ->
                val on = a.id !in config.excludedAccountIDs
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { checked ->
                        vm.setSafeConfig(config.copy(excludedAccountIDs = if (checked) config.excludedAccountIDs - a.id else config.excludedAccountIDs + a.id))
                    })
                }
            }
        }

        // Reserved funds
        Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Reserved funds", "Add") { addReserved = true }
            if (ledger.reserved.isEmpty()) Text("Money you want kept out of reach, like an emergency buffer or next month’s rent.",
                color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            ledger.reserved.forEachIndexed { i, f ->
                Row(Modifier.fillMaxWidth().clickableNoRipple { editReserved = f }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = c.ink, fontSize = 16.sp)
                        if (!f.isActive) Text("Not counted", color = c.inkTertiary, fontSize = 12.sp)
                    }
                    Text(fmt(f.amountCents), color = c.ink, style = figure(15))
                }
                if (i < ledger.reserved.size - 1) Hairline()
            }
        }
        Text("Only money you already have counts. Nothing here forecasts income, and nothing is moved: this is a way of looking at your balances.",
            color = c.inkTertiary, fontSize = 12.sp)
    }

    if (addReserved) ReservedSheet(null, vm) { addReserved = false }
    editReserved?.let { ReservedSheet(it, vm) { editReserved = null } }
}

@Composable
private fun Line(label: String, cents: Long, positive: Boolean = false) {
    val c = Mono.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text((if (positive) "" else "−") + fmt(cents), color = c.ink, style = figure(15))
    }
}

@Composable
private fun Toggled(label: String, cents: Long, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Mono.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (on) c.ink else c.inkTertiary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("−" + fmt(cents), color = if (on) c.ink else c.inkTertiary, style = figure(15), modifier = Modifier.padding(end = 8.dp))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun Detail(title: String, detail: String, cents: Long) {
    val c = Mono.colors
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.inkSecondary, fontSize = 13.sp, maxLines = 1)
            Text(detail, color = c.inkTertiary, fontSize = 11.sp, maxLines = 1)
        }
        Text(fmt(cents), color = c.inkSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun ReservedSheet(editing: ReservedFund?, vm: MonoViewModel, close: () -> Unit) {
    val c = Mono.colors
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var text by remember { mutableStateOf(editing?.let { Money.editable(it.amountCents) } ?: "") }
    var active by remember { mutableStateOf(editing?.isActive ?: true) }
    var confirm by remember { mutableStateOf(false) }
    val cents = Money.parse(text)?.takeIf { it > 0 }
    FormSheet(if (editing == null) "Reserve money" else "Edit reserved fund", close) {
        TextInput(name, { name = it }, "What is it for?")
        AmountInput(text, { text = it }, "Amount")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Count it in Safe to spend", color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = active, onCheckedChange = { active = it })
        }
        PrimaryButton("Save", enabled = cents != null && name.isNotBlank()) {
            vm.saveReserved((editing ?: ReservedFund(createdAt = System.currentTimeMillis())).copy(name = name.trim(), amountCents = cents ?: 0, isActive = active)); close()
        }
        if (editing != null) TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete", color = c.danger) }
    }
    if (confirm && editing != null) ConfirmDialog("Delete this reserved fund?", editing.name, "Delete", true,
        onConfirm = { vm.deleteReserved(editing); close() }, onDismiss = { confirm = false })
}

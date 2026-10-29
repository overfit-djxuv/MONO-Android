package app.mono.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.mono.domain.AccountKind
import app.mono.domain.Books
import app.mono.domain.Currency
import app.mono.domain.CurrencyConverter
import app.mono.domain.Money
import app.mono.domain.model.Account
import app.mono.finance.MonoViewModel
import app.mono.finance.data.LedgerState
import app.mono.finance.ui.AmountInput
import app.mono.finance.ui.Callout
import app.mono.finance.ui.ChipRow
import app.mono.finance.ui.DateField
import app.mono.finance.ui.FormSheet
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.LocalMoneyContext
import app.mono.finance.ui.PrimaryButton
import app.mono.finance.ui.SecondaryButton
import app.mono.finance.ui.SectionLabel
import app.mono.finance.ui.SectionTitle
import app.mono.finance.ui.TextInput
import app.mono.finance.ui.Tone
import app.mono.finance.ui.clickableNoRipple
import app.mono.finance.ui.fmt
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono
import app.mono.finance.ui.theme.figure
import app.mono.finance.ui.toMillis
import java.time.LocalDate

@Composable
fun AccountsScreen(ledger: LedgerState?, vm: MonoViewModel, onBack: () -> Unit) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    var edit by remember { mutableStateOf<Account?>(null) }
    var adding by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf(false) }
    var rateFor by remember { mutableStateOf<String?>(null) }
    if (ledger == null) return
    val books = remember(ledger, money) { Books(ledger.accounts, ledger.rates, ledger.transactions, ledger.entries, reporting = money.reporting) }
    val reporting = money.reporting
    val missing = books.overview.unconverted.mapNotNull { id -> ledger.accounts.firstOrNull { it.id == id } }

    ScreenFrame("Accounts & currency", onBack) {
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Total in ${reporting.code}", color = c.inkSecondary, fontSize = 13.sp)
            Text(fmt(books.overview.total), color = c.ink, style = figure(32, FontWeight.SemiBold))
            if (missing.isNotEmpty()) Callout("${missing.joinToString(", ") { it.name }} has no exchange rate yet, so it isn’t in this total. Add a rate below.", Tone.WARNING)
        }

        Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Accounts", "Add") { adding = true }
            books.accounts.sortedWith(compareBy({ it.isArchived }, { it.sortOrder }, { it.createdAt })).forEachIndexed { i, a ->
                Row(Modifier.fillMaxWidth().clickableNoRipple { edit = a }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, color = if (a.isArchived) c.inkTertiary else c.ink, fontSize = 16.sp)
                        Text(a.kind.label + (if (a.isArchived) " · Archived" else ""), color = c.inkSecondary, fontSize = 12.sp)
                        if (a.currencyCode != reporting.code) {
                            Text(books.reportingBalance(a)?.let { "≈ ${fmt(it)}" } ?: "No exchange rate yet", color = if (books.needsRate(a)) c.warning else c.inkSecondary, fontSize = 12.sp)
                        }
                    }
                    Text(fmt(books.balance(a), currency = a.currency), color = c.ink, style = figure(16))
                }
                if (i < books.accounts.size - 1) Hairline()
            }
        }
        if (books.active.size > 1) SecondaryButton("Move money between accounts", Modifier.fillMaxWidth()) { transfer = true }

        Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Exchange rates")
            Text("Rates you enter yourself. MONO never looks them up, so a missing rate is never guessed.", color = c.inkTertiary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            val others = (ledger.accounts.map { it.currencyCode } + ledger.goals.map { it.currency.code }).distinct().filter { it != reporting.code }
            if (others.isEmpty() && ledger.rates.isEmpty()) Text("You only use ${reporting.code} so far.", color = c.inkSecondary, fontSize = 14.sp)
            (others + ledger.rates.map { it.fromCode }).distinct().forEach { code ->
                val micros = books.converter.micros(Currency.find(code))
                Row(Modifier.fillMaxWidth().clickableNoRipple { rateFor = code }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("1 $code", color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(micros?.let { "${CurrencyConverter.formatRate(it)} ${reporting.code}" } ?: "Add a rate", color = if (micros == null) c.warning else c.ink, fontSize = 15.sp)
                }
            }
        }
    }

    if (adding) AccountSheet(null, vm) { adding = false }
    edit?.let { AccountSheet(it, vm) { edit = null } }
    if (transfer) TransferSheet(books, vm) { transfer = false }
    rateFor?.let { RateSheet(it, reporting, vm) { rateFor = null } }
}

@Composable
private fun AccountSheet(editing: Account?, vm: MonoViewModel, close: () -> Unit) {
    val c = Mono.colors
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var kind by remember { mutableStateOf(editing?.kind ?: AccountKind.BANK) }
    var code by remember { mutableStateOf(editing?.currencyCode ?: Money.reporting.code) }
    val cur = Currency.find(code)
    var opening by remember { mutableStateOf(editing?.let { Money.editable(it.openingCents, cur) } ?: "") }
    var archived by remember { mutableStateOf(editing?.isArchived ?: false) }
    val openingCents = if (opening.isEmpty()) 0L else Money.parse(opening, cur)
    FormSheet(if (editing == null) "New account" else "Edit account", close) {
        TextInput(name, { name = it }, "Name")
        SectionLabel("Type"); ChipRow(AccountKind.entries, kind, { it.label }) { kind = it }
        if (editing == null) { SectionLabel("Currency"); ChipRow(Currency.all.map { it.code }, code, { it }) { code = it } }
        else Text("Currency: ${editing.currencyCode} (fixed once an account exists)", color = c.inkSecondary, fontSize = 13.sp)
        AmountInput(opening, { opening = it }, "Opening balance", cur)
        if (editing != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Archived (hidden from pickers; its money still counts)", color = c.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = archived, onCheckedChange = { archived = it })
        }
        PrimaryButton("Save", enabled = name.isNotBlank() && openingCents != null) {
            vm.saveAccount((editing ?: Account(createdAt = System.currentTimeMillis())).copy(
                name = name.trim(), kindRaw = kind.raw, currencyCode = if (editing == null) code else editing.currencyCode,
                openingCents = openingCents ?: 0, isArchived = archived))
            close()
        }
    }
}

@Composable
private fun TransferSheet(books: Books, vm: MonoViewModel, close: () -> Unit) {
    val c = Mono.colors
    val accounts = books.active
    var from by remember { mutableStateOf(accounts.firstOrNull()) }
    var to by remember { mutableStateOf(accounts.getOrNull(1)) }
    var sent by remember { mutableStateOf("") }
    var received by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var title by remember { mutableStateOf("") }
    val sameCurrency = from != null && to != null && from!!.currencyCode == to!!.currencyCode
    val sentCents = from?.let { Money.parse(sent, it.currency) }
    val receivedCents = if (sameCurrency) sentCents else to?.let { Money.parse(received, it.currency) }
    val valid = from != null && to != null && from!!.id != to!!.id && (sentCents ?: 0) > 0 && (receivedCents ?: 0) > 0
    FormSheet("Move money", close) {
        SectionLabel("From"); ChipRow(accounts, from, { it.name }) { from = it }
        SectionLabel("To"); ChipRow(accounts, to, { it.name }) { to = it }
        AmountInput(sent, { sent = it }, "Amount sent", from?.currency)
        if (!sameCurrency && to != null) AmountInput(received, { received = it }, "Amount received", to?.currency)
        if (!sameCurrency && sentCents != null && receivedCents != null && from != null && to != null) {
            CurrencyConverter.impliedMicros(sentCents, from!!.currency, receivedCents, to!!.currency)?.let {
                Text("That is a rate of ${CurrencyConverter.formatRate(it)} ${to!!.currency.code} per ${from!!.currency.code}.", color = c.inkSecondary, fontSize = 13.sp)
            }
        }
        DateField("Date", date) { date = it }
        TextInput(title, { title = it }, "Note (optional)")
        Text("A transfer is neither income nor spending, and both balances change by matching amounts.", color = c.inkTertiary, fontSize = 12.sp)
        PrimaryButton("Move money", enabled = valid) {
            vm.transfer(from!!, to!!, sentCents ?: 0, receivedCents ?: 0, date.toMillis(), title.trim(), ""); close()
        }
    }
}

@Composable
private fun RateSheet(code: String, reporting: Currency, vm: MonoViewModel, close: () -> Unit) {
    val c = Mono.colors
    var text by remember { mutableStateOf("") }
    val micros = CurrencyConverter.parseRate(text)
    FormSheet("1 $code in ${reporting.code}", close) {
        TextInput(text, { text = it }, "Rate (e.g. 4.4725)")
        Text("How many ${reporting.code} one $code is worth to you right now.", color = c.inkSecondary, fontSize = 13.sp)
        PrimaryButton("Save rate", enabled = micros != null) { vm.saveRate(code, reporting.code, micros ?: 0); close() }
        TextButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}


package app.mono.finance.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.Books
import app.mono.domain.Currency
import app.mono.domain.Money
import app.mono.domain.Tags
import app.mono.domain.TransactionFilter
import app.mono.domain.TransactionFilter.Period
import app.mono.domain.TxKind
import app.mono.domain.model.TransactionRecord
import app.mono.finance.MonoViewModel
import app.mono.finance.data.LedgerState
import app.mono.finance.ui.AmountInput
import app.mono.finance.ui.Callout
import app.mono.finance.ui.ChipRow
import app.mono.finance.ui.ConfirmDialog
import app.mono.finance.ui.DateField
import app.mono.finance.ui.EmptyState
import app.mono.finance.ui.FormSheet
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.LocalMoneyContext
import app.mono.finance.ui.PrimaryButton
import app.mono.finance.ui.SecondaryButton
import app.mono.finance.ui.StatTile
import app.mono.finance.ui.TextInput
import app.mono.finance.ui.Tone
import app.mono.finance.ui.TransactionRow
import app.mono.finance.ui.clickableNoRipple
import app.mono.finance.ui.fmt
import app.mono.finance.ui.short
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono
import app.mono.finance.ui.toLocalDate
import app.mono.finance.ui.toMillis
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface Row2 {
    data class Header(val label: String, val net: String) : Row2
    data class Item(val id: String) : Row2
}

@Composable
fun TransactionsScreen(ledger: LedgerState?, vm: MonoViewModel, onEdit: (String) -> Unit, onAttachReceipt: (String) -> Unit) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    var detailID by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf(TransactionFilter()) }
    var showFilters by remember { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(false) }
    val receipts by vm.receipts.collectAsState()

    if (ledger == null) return
    val detail = detailID?.let { id -> ledger.transactions.firstOrNull { it.id == id } }
    if (detailID != null && detail == null) detailID = null // deleted
    BackHandler(enabled = detail != null) { detailID = null }
    if (detail != null) {
        TransactionDetail(detail, ledger, receipts.firstOrNull { it.transactionID == detail.id }, vm, onEdit = { onEdit(detail.id) },
            onAttachReceipt = { onAttachReceipt(detail.id) }, onBack = { detailID = null })
        return
    }

    val f = filter.copy(search = search)
    val zone = ZoneId.systemDefault()
    val shown = remember(ledger.transactions, ledger.accounts, f) { f.apply(ledger.transactions, ledger.accounts) }
    val dupes = remember(ledger.transactions) { TransactionFilter.duplicateIDs(ledger.transactions) }
    val books = remember(shown, ledger, money) { Books(ledger.accounts, ledger.rates, shown, ledger.entries, reporting = money.reporting) }
    val income = books.reported.filter { it.kind == TxKind.INCOME }.sumOf { it.amountCents }
    val expense = books.reported.filter { it.kind == TxKind.EXPENSE }.sumOf { it.amountCents }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Mono.gutter).padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Transactions", color = c.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { showFilters = true }) { Text(if (filter.active) "Filters •" else "Filters", color = c.accent) }
        }
        if (ledger.transactions.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(Mono.gutter), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No transactions yet", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text("Tap + to add your first income or expense, or import a CSV from Tools.", color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            return@Column
        }
        Column(Modifier.padding(horizontal = Mono.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextInput(search, { search = it }, "Search transactions")
            ChipRow(listOf(Period.THIS_MONTH, Period.LAST_MONTH, Period.ALL, Period.CUSTOM), filter.period,
                { if (it == Period.CUSTOM && filter.period == Period.CUSTOM && filter.customFrom != null) "${filter.customFrom!!.short()} – ${filter.customTo?.short() ?: "now"}" else it.label }) {
                if (it == Period.CUSTOM) showCustom = true else filter = filter.copy(period = it)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Income", fmt(income), tint = c.income, modifier = Modifier.weight(1f), valueSize = 15)
                StatTile("Spent", fmt(expense), modifier = Modifier.weight(1f), valueSize = 15)
                StatTile("Net", fmt(income - expense, signed = true), modifier = Modifier.weight(1f), valueSize = 15)
            }
            if (books.skipped > 0) Text("${books.skipped} ${if (books.skipped == 1) "record is" else "records are"} in a currency with no exchange rate, so ${if (books.skipped == 1) "it isn’t" else "they aren’t"} in these totals.",
                color = c.warning, fontSize = 12.sp)
            val chips = listOfNotNull(
                filter.kind?.let { it.label to { filter = filter.copy(kind = null) } },
                filter.category?.let { it to { filter = filter.copy(category = null) } },
                filter.accountID?.let { id -> ledger.accounts.firstOrNull { it.id == id }?.name?.let { n -> n to { filter = filter.copy(accountID = null) } } },
                filter.tag?.let { "#$it" to { filter = filter.copy(tag = null) } },
                amountLabel(filter)?.let { it to { filter = filter.copy(minCents = null, maxCents = null) } },
            )
            if (chips.isNotEmpty()) Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), enabled = false), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { (label, clear) ->
                    Text("$label  ✕", color = c.accent, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(c.accentSoft).clickableNoRipple(clear).padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
        }

        if (shown.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(Mono.gutter), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No matches", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(if (filter.active || search.isNotBlank()) "Nothing fits the current search or filters." else "Nothing recorded in ${filter.period.label.lowercase()}.",
                    color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { filter = filter.cleared().copy(period = Period.ALL); search = "" }) { Text("Show all time", color = c.accent) }
            }
            return@Column
        }

        val byId = remember(shown) { shown.associateBy { it.id } }
        val symbols = remember(ledger.categories) { ledger.categories.associate { it.name to it.symbol } }
        val rows = remember(shown, money) {
            val out = ArrayList<Row2>()
            val days = shown.groupBy { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }
            for ((d, list) in days.toSortedMap(reverseOrder())) {
                val b = Books(ledger.accounts, ledger.rates, list, emptyList(), reporting = money.reporting)
                val inc = b.reported.filter { it.kind == TxKind.INCOME }.sumOf { it.amountCents }
                val exp = b.reported.filter { it.kind == TxKind.EXPENSE }.sumOf { it.amountCents }
                val net = if (inc == 0L && exp == 0L) (if (list.all { it.kind == TxKind.TRANSFER }) "Transfers only" else "") else Money.format(inc - exp, signed = true)
                out += Row2.Header(d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())), net)
                list.sortedByDescending { it.date }.forEach { out += Row2.Item(it.id) }
            }
            out
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = Mono.gutter), contentPadding = PaddingValues(bottom = 96.dp, top = 8.dp)) {
            items(rows, key = { when (it) { is Row2.Header -> "h-${it.label}"; is Row2.Item -> it.id } }) { r ->
                when (r) {
                    is Row2.Header -> Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp)) {
                        Text(r.label, color = c.inkSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(if (money.hidden && r.net.isNotEmpty() && r.net != "Transfers only") "••••" else r.net, color = c.inkTertiary, fontSize = 13.sp)
                    }
                    is Row2.Item -> byId[r.id]?.let { tx ->
                        Column {
                            TransactionRow(tx, symbols[tx.category] ?: "tag", Modifier.clickableNoRipple { detailID = tx.id }, ledger.accounts, if (tx.id in dupes) "Possible duplicate" else null)
                            Hairline()
                        }
                    }
                }
            }
        }
    }

    if (showCustom) FormSheet("Custom range", { showCustom = false }) {
        var from by remember { mutableStateOf(filter.customFrom ?: LocalDate.now().minusMonths(1)) }
        var to by remember { mutableStateOf(filter.customTo ?: LocalDate.now()) }
        DateField("From", from) { from = it; if (to.isBefore(it)) to = it }
        DateField("To", to) { to = it; if (from.isAfter(it)) from = it }
        PrimaryButton("Show", modifier = Modifier.fillMaxWidth()) { filter = filter.copy(period = Period.CUSTOM, customFrom = from, customTo = to); showCustom = false }
    }
    if (showFilters) FormSheet("Filters", { showFilters = false }) {
        FilterSheet(filter, ledger, onChange = { filter = it }, onClose = { showFilters = false })
    }
}

private fun amountLabel(f: TransactionFilter): String? {
    val lo = f.minCents; val hi = f.maxCents
    return when {
        lo != null && hi != null -> "${Money.format(lo)} to ${Money.format(hi)}"
        lo != null -> "From ${Money.format(lo)}"
        hi != null -> "Up to ${Money.format(hi)}"
        else -> null
    }
}

@Composable
private fun FilterSheet(filter: TransactionFilter, ledger: LedgerState, onChange: (TransactionFilter) -> Unit, onClose: () -> Unit) {
    val c = Mono.colors
    var f by remember { mutableStateOf(filter) }
    var minText by remember { mutableStateOf(filter.minCents?.let { Money.editable(it) } ?: "") }
    var maxText by remember { mutableStateOf(filter.maxCents?.let { Money.editable(it) } ?: "") }
    val lo = if (minText.isBlank()) null else Money.parse(minText)
    val hi = if (maxText.isBlank()) null else Money.parse(maxText)
    val valid = (minText.isBlank() || lo != null) && (maxText.isBlank() || hi != null) && !(lo != null && hi != null && lo > hi)
    val tags = ledger.transactions.flatMap { Tags.list(it.tags) }.toSet().sorted()
    val accounts = ledger.accounts.filter { !it.isArchived }

    Text("Type", color = c.inkSecondary, fontSize = 13.sp)
    ChipRow(listOf<TxKind?>(null) + TxKind.entries, f.kind, { it?.label ?: "All types" }) { f = f.copy(kind = it) }
    Text("Period", color = c.inkSecondary, fontSize = 13.sp)
    ChipRow(Period.entries.filter { it != Period.CUSTOM }, f.period.takeIf { it != Period.CUSTOM }, { it.label }) { f = f.copy(period = it) }
    Text("Category", color = c.inkSecondary, fontSize = 13.sp)
    ChipRow(listOf<String?>(null) + ledger.categories.sortedBy { it.sortOrder }.map { it.name }.distinct(), f.category, { it ?: "All" }) { f = f.copy(category = it) }
    if (accounts.size > 1) {
        Text("Account", color = c.inkSecondary, fontSize = 13.sp)
        ChipRow(listOf<String?>(null) + accounts.map { it.id }, f.accountID, { id -> accounts.firstOrNull { it.id == id }?.name ?: "All" }) { f = f.copy(accountID = it) }
    }
    if (tags.isNotEmpty()) {
        Text("Tag", color = c.inkSecondary, fontSize = 13.sp)
        ChipRow(listOf<String?>(null) + tags, f.tag, { if (it == null) "All" else "#$it" }) { f = f.copy(tag = it) }
    }
    Text("Amount", color = c.inkSecondary, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) { AmountInput(minText, { minText = it }, "From (any)") }
        Box(Modifier.weight(1f)) { AmountInput(maxText, { maxText = it }, "To (any)") }
    }
    if (!valid) Text("The first amount must not be larger than the second.", color = c.danger, fontSize = 12.sp)
    PrimaryButton("Apply", enabled = valid, modifier = Modifier.fillMaxWidth()) { onChange(f.copy(minCents = lo, maxCents = hi)); onClose() }
    if (f.active || lo != null || hi != null) TextButton(onClick = { onChange(f.cleared()); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Clear filters", color = c.danger) }
}

@Composable
private fun TransactionDetail(
    tx: TransactionRecord, ledger: LedgerState, receipt: app.mono.domain.model.Receipt?, vm: MonoViewModel,
    onEdit: () -> Unit, onAttachReceipt: () -> Unit, onBack: () -> Unit,
) {
    val c = Mono.colors
    var confirmDelete by remember { mutableStateOf(false) }
    val from = ledger.accounts.firstOrNull { it.id == tx.accountID } ?: ledger.accounts.minByOrNull { it.createdAt }
    val to = tx.toAccountID?.let { id -> ledger.accounts.firstOrNull { it.id == id } }
    val currency = from?.currency ?: Money.reporting
    val dupes = remember(ledger.transactions) { TransactionFilter.duplicateIDs(ledger.transactions) }
    val heading = when { tx.kind == TxKind.TRANSFER -> tx.title.ifBlank { "Transfer" }; tx.merchant.isNotBlank() -> tx.merchant; else -> tx.title.ifBlank { tx.category } }
    val amountText = when (tx.kind) {
        TxKind.INCOME -> fmt(tx.amountCents, signed = true, currency = currency)
        TxKind.EXPENSE -> fmt(-tx.amountCents, signed = true, currency = currency)
        TxKind.TRANSFER -> fmt(tx.amountCents, currency = currency)
    }
    val caption = when (tx.kind) { TxKind.EXPENSE -> "Expense · ${tx.category}"; TxKind.INCOME -> "Income · ${tx.category}"; TxKind.TRANSFER -> "Transfer · not counted as income or spending" }
    val lines = buildList {
        add("Date" to java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(tx.date)))
        if (tx.kind == TxKind.TRANSFER) {
            add("From" to (from?.name ?: "—")); add("To" to (to?.name ?: "—"))
            if (to != null && to.currencyCode != from?.currencyCode) add("Received" to fmt0(tx.toAmountCents, to.currency))
        } else {
            from?.let { add("Account" to it.name) }
            add("Category" to tx.category)
            if (tx.merchant.isNotBlank() && tx.title.isNotBlank()) add("Description" to tx.title)
            Tags.list(tx.tags).takeIf { it.isNotEmpty() }?.let { t -> add("Tags" to t.joinToString(" ") { "#$it" }) }
        }
        if (tx.notes.isNotBlank()) add("Notes" to tx.notes)
    }
    app.mono.finance.ui.screens.ScreenFrame("Details", onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(caption, color = c.inkSecondary, fontSize = 13.sp)
            Text(heading, color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(amountText, color = if (tx.kind == TxKind.INCOME) c.income else c.ink, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
        }
        if (tx.id in dupes) Callout("Another ${if (tx.kind == TxKind.INCOME) "income" else "expense"} with the same amount, name and day exists. If it was entered twice, delete one.", Tone.WARNING)
        Column(Modifier.fillMaxWidth().surface()) {
            lines.forEachIndexed { i, (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp)) { Text(k, color = c.inkSecondary, fontSize = 14.sp); Box(Modifier.weight(1f)); Text(v, color = c.ink, fontSize = 14.sp, modifier = Modifier.weight(2f), textAlign = androidx.compose.ui.text.style.TextAlign.End) }
                if (i < lines.size - 1) Hairline()
            }
        }
        if (receipt != null) {
            val thumb = remember(receipt.imageFile) { vm.receiptStore.thumbnail(receipt.imageFile, 200) }
            val matches = receipt.totalCents == tx.amountCents
            Row(Modifier.fillMaxWidth().surface(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp, 68.dp).clip(RoundedCornerShape(8.dp)).background(c.accentSoft)) { if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.size(52.dp, 68.dp), contentScale = ContentScale.Crop) }
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Receipt attached", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(if (matches) "Total matches this transaction" else "Receipt total " + fmt0(receipt.totalCents, Currency.find(receipt.currencyCode.ifEmpty { Money.reporting.code })),
                        color = if (matches) c.inkSecondary else c.warning, fontSize = 13.sp)
                }
            }
        } else if (tx.kind != TxKind.TRANSFER) SecondaryButton("Attach a receipt", Modifier.fillMaxWidth(), onClick = onAttachReceipt)
        PrimaryButton("Edit", modifier = Modifier.fillMaxWidth(), onClick = onEdit)
        SecondaryButton("Repeat for today", Modifier.fillMaxWidth()) { vm.repeatTransaction(tx) }
        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete transaction", color = c.danger) }
        Text("You’ll be asked to confirm. A receipt stays in your library.", color = c.inkTertiary, fontSize = 12.sp)
    }
    if (confirmDelete) ConfirmDialog("Delete this transaction?", "$heading · $amountText. Balances update immediately.", "Delete", true, { vm.deleteTransaction(tx.id) }, { confirmDelete = false })
}

private fun fmt0(cents: Long, currency: Currency) = Money.format(cents, currency = currency)

package app.mono.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.Books
import app.mono.domain.FinanceEngine
import app.mono.domain.LedgerRules
import app.mono.domain.ListKind
import app.mono.domain.Money
import app.mono.domain.MonthKey
import app.mono.domain.Priority
import app.mono.domain.ShoppingQuickAdd
import app.mono.domain.model.ShoppingItem
import app.mono.finance.MonoViewModel
import app.mono.finance.data.LedgerState
import app.mono.finance.ui.AmountInput
import app.mono.finance.ui.Callout
import app.mono.finance.ui.ChipRow
import app.mono.finance.ui.ConfirmDialog
import app.mono.finance.ui.EmptyState
import app.mono.finance.ui.FormSheet
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.LocalMoneyContext
import app.mono.finance.ui.PrimaryButton
import app.mono.finance.ui.SectionLabel
import app.mono.finance.ui.StatTile
import app.mono.finance.ui.StatusChip
import app.mono.finance.ui.TextInput
import app.mono.finance.ui.Tone
import app.mono.finance.ui.clickableNoRipple
import app.mono.finance.ui.fieldColors
import app.mono.finance.ui.fmt
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono
import app.mono.finance.ui.theme.figure
import java.time.LocalDate

private sealed interface ItemTarget {
    data object New : ItemTarget
    data class Edit(val id: String) : ItemTarget
}

@Composable
fun ShoppingScreen(ledger: LedgerState?, vm: MonoViewModel) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    var listRaw by rememberSaveable { mutableStateOf(ListKind.GROCERY.raw) }
    val list = ListKind.of(listRaw)
    var sheet by remember { mutableStateOf<ItemTarget?>(null) }
    var pendingDelete by remember { mutableStateOf<ShoppingItem?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var quick by remember { mutableStateOf("") }
    if (ledger == null) return

    val items = ledger.items.filter { it.listKind == list }
        .sortedWith(compareBy<ShoppingItem> { it.isPurchased }.thenByDescending { it.priorityRaw }.thenBy { it.createdAt })
    val summary = FinanceEngine.shoppingSummary(items)
    val unrecorded = items.filter { it.isPurchased && LedgerRules.canRecord(it, ledger.transactions) }

    // What the planned purchases are measured against.
    val month = MonthKey.of(LocalDate.now())
    val available: Pair<String, Long>? = run {
        if (list != ListKind.GROCERY) {
            val b = Books(ledger.accounts, ledger.rates, ledger.transactions, ledger.entries, reporting = money.reporting)
            return@run "Available cash" to b.overview.total
        }
        val reported = Books(ledger.accounts, ledger.rates, ledger.transactions, emptyList(), reporting = money.reporting).reported
        val b = FinanceEngine.budgetSummary(month, ledger.monthBudgets.firstOrNull { it.monthKey == month.string }?.totalCents, ledger.limits, reported)
        b.lines.firstOrNull { it.category == "Groceries" }?.let { return@run "Groceries budget left" to maxOf(it.remaining, 0) }
        b.remaining?.let { return@run "Monthly budget left" to maxOf(it, 0) }
        null
    }

    fun addQuick() {
        val p = ShoppingQuickAdd.parse(quick, money.reporting) ?: return
        val cats = ledger.categories.filter { it.kindRaw == "expense" }.map { it.name }
        val wanted = if (list == ListKind.GROCERY) "Groceries" else "Shopping"
        val category = if (wanted in cats) wanted else cats.firstOrNull() ?: "Groceries"
        vm.saveItem(ShoppingItem(name = p.name, quantity = p.quantity, estimatedCents = p.priceCents, category = category,
            priorityRaw = Priority.MEDIUM.raw, listKindRaw = list.raw, createdAt = System.currentTimeMillis()))
        quick = ""
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Mono.gutter, end = Mono.gutter, bottom = 96.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Shopping", color = c.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { sheet = ItemTarget.New }) { Icon(Icons.Outlined.Add, "Add item with details", tint = c.ink) }
            }
            ChipRow(ListKind.entries, list, { it.label }) { listRaw = it.raw }
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val after = available?.let { it.second - summary.toBuy }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Planned", fmt(summary.toBuy), modifier = Modifier.weight(1f), valueSize = 15)
                    StatTile("Bought", fmt(summary.purchased), tint = c.accent, modifier = Modifier.weight(1f), valueSize = 15)
                    StatTile(after?.let { if (it >= 0) "Left after plan" else "Over by" } ?: "Budget", after?.let { fmt(kotlin.math.abs(it)) } ?: "Not set",
                        tint = if ((after ?: 0) < 0) c.danger else c.ink, modifier = Modifier.weight(1f), valueSize = 15)
                }
                if (available != null && after != null) {
                    Text("Measured against ${available.first.lowercase()}: ${fmt(available.second)}.", color = c.inkSecondary, fontSize = 12.sp)
                    if (after < 0) Callout("The items still to buy cost ${fmt(-after)} more than your ${available.first.lowercase()}.", Tone.DANGER)
                    else if (available.second > 0 && summary.toBuy.toDouble() / available.second >= FinanceEngine.WARNING_THRESHOLD)
                        Callout("This plan uses most of your ${available.first.lowercase()}.", Tone.WARNING)
                } else if (list == ListKind.GROCERY) {
                    Text("Set a Groceries limit or monthly total in Budget to compare this list with what you can spend.", color = c.inkTertiary, fontSize = 12.sp)
                }
                Text("Planned prices are estimates and don’t change your balances or spending.", color = c.inkTertiary, fontSize = 12.sp)
            }
            OutlinedTextField(
                value = quick, onValueChange = { quick = it }, singleLine = true, placeholder = { Text("Add an item, e.g. milk 2 4.50") },
                trailingIcon = { if (quick.isNotEmpty()) TextButton(onClick = ::addQuick) { Text("Add", color = c.accent) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { addQuick() }),
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).fillMaxWidth().testTag("quickAddItem"), colors = fieldColors(),
            )
        }
        if (items.isEmpty()) item {
            EmptyState("${list.label} is empty",
                if (list == ListKind.GROCERY) "Type an item above with a quantity and price, like “milk 2 4.50”, to see the total before you shop."
                else "Keep these separate from everyday groceries, with estimated prices.", "Add with details") { sheet = ItemTarget.New }
        } else {
            item { Text("${summary.toBuyCount} to buy · ${summary.purchasedCount} bought", color = c.inkSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(items, key = { it.id }) { item ->
                Column {
                    Box {
                        ItemRow(item, recorded = item.expenseID != null,
                            onToggle = { vm.saveItem(item.copy(isPurchased = !item.isPurchased, purchasedAt = if (item.isPurchased) null else System.currentTimeMillis())) },
                            onOpen = { sheet = ItemTarget.Edit(item.id) }, onMenu = { menuFor = item.id })
                        DropdownMenu(expanded = menuFor == item.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text("Edit") }, onClick = { menuFor = null; sheet = ItemTarget.Edit(item.id) })
                            ListKind.entries.filter { it != item.listKind }.forEach { k ->
                                DropdownMenuItem(text = { Text("Move to ${k.label}") }, onClick = { menuFor = null; vm.saveItem(item.copy(listKindRaw = k.raw)) })
                            }
                            if (item.isPurchased && LedgerRules.canRecord(item, ledger.transactions)) {
                                DropdownMenuItem(text = { Text("Record as expense") }, onClick = { menuFor = null; vm.recordPurchase(item) })
                            }
                            DropdownMenuItem(text = { Text("Delete", color = c.danger) }, onClick = { menuFor = null; pendingDelete = item })
                        }
                    }
                    Hairline()
                }
            }
            if (unrecorded.isNotEmpty() || summary.purchasedCount > 0) item {
                Column(Modifier.padding(top = 16.dp).fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (unrecorded.isNotEmpty()) TextButton(onClick = { vm.recordPurchases(unrecorded) }) {
                        Text("Record ${unrecorded.size} purchased as expenses", color = c.accent)
                    }
                    if (summary.purchasedCount > 0) TextButton(onClick = { confirmClear = true }) { Text("Clear bought items", color = c.danger) }
                    Text("Marking an item bought changes nothing in your money. Only Record connects it to a real expense.", color = c.inkTertiary, fontSize = 12.sp)
                }
            }
        }
    }

    when (val s = sheet) {
        null -> {}
        ItemTarget.New -> ItemSheet(null, list, ledger, vm) { sheet = null }
        is ItemTarget.Edit -> ledger.items.firstOrNull { it.id == s.id }?.let { ItemSheet(it, list, ledger, vm) { sheet = null } } ?: run { sheet = null }
    }
    pendingDelete?.let { item ->
        ConfirmDialog("Delete this item?",
            if (item.expenseID != null) "The expense already recorded for “${item.name}” stays in your transactions." else item.name,
            "Delete", true, onConfirm = { vm.deleteItems(listOf(item)) }, onDismiss = { pendingDelete = null })
    }
    if (confirmClear) ConfirmDialog("Clear ${summary.purchasedCount} bought ${if (summary.purchasedCount == 1) "item" else "items"}?",
        if (unrecorded.isEmpty()) "Recorded expenses stay in your transactions."
        else "${unrecorded.size} of them have not been recorded as expenses and will be lost from the list.",
        "Clear", true, onConfirm = { vm.deleteItems(items.filter { it.isPurchased }) }, onDismiss = { confirmClear = false })
}

@Composable
private fun ItemRow(item: ShoppingItem, recorded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, onMenu: () -> Unit) {
    val c = Mono.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggle, modifier = Modifier.testTag("toggle-${item.name}")) {
            Icon(if (item.isPurchased) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                if (item.isPurchased) "Mark ${item.name} not purchased" else "Mark ${item.name} purchased",
                tint = if (item.isPurchased) c.accent else c.inkTertiary)
        }
        Column(Modifier.weight(1f).clickableNoRipple(onOpen), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.name, color = if (item.isPurchased) c.inkSecondary else c.ink, fontSize = 16.sp, maxLines = 1,
                textDecoration = if (item.isPurchased) TextDecoration.LineThrough else null)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val each = item.actualCents ?: item.estimatedCents
                val est = if (item.isPurchased && item.actualCents != null && item.actualCents != item.estimatedCents) " · est. ${fmt(item.estimatedCents)}" else ""
                Text("${item.quantity} × ${fmt(each)}$est", color = c.inkSecondary, fontSize = 12.sp)
                if (recorded) StatusChip("Linked to expense", c.accent) else if (item.isPurchased) StatusChip("Not recorded", c.warning)
            }
        }
        Text(fmt(if (item.isPurchased) item.effectiveTotal else item.estimatedTotal), color = if (item.isPurchased) c.inkSecondary else c.ink,
            style = figure(15), modifier = Modifier.padding(horizontal = 4.dp).clickableNoRipple(onMenu))
    }
}

@Composable
private fun ItemSheet(editing: ShoppingItem?, defaultList: ListKind, ledger: LedgerState, vm: MonoViewModel, close: () -> Unit) {
    val c = Mono.colors
    val cats = ledger.categories.filter { it.kindRaw == "expense" }.map { it.name }
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var quantity by remember { mutableStateOf((editing?.quantity ?: 1).toString()) }
    var estimate by remember { mutableStateOf(editing?.let { Money.editable(it.estimatedCents) } ?: "") }
    var actual by remember { mutableStateOf(editing?.actualCents?.let { Money.editable(it) } ?: "") }
    var category by remember { mutableStateOf(editing?.category ?: cats.firstOrNull() ?: "") }
    var priority by remember { mutableStateOf(editing?.priority ?: Priority.MEDIUM) }
    var list by remember { mutableStateOf(editing?.listKind ?: defaultList) }
    var purchased by remember { mutableStateOf(editing?.isPurchased ?: false) }
    val estimateCents = if (estimate.isEmpty()) 0L else Money.parse(estimate)
    val actualCents = if (actual.isEmpty()) null else Money.parse(actual)
    val qty = quantity.toIntOrNull()?.takeIf { it in 1..999 }
    val valid = name.isNotBlank() && estimateCents != null && (actual.isEmpty() || actualCents != null) && category.isNotBlank() && qty != null

    fun build(): ShoppingItem = (editing ?: ShoppingItem(createdAt = System.currentTimeMillis())).copy(
        name = name.trim(), quantity = qty ?: 1, estimatedCents = estimateCents ?: 0, actualCents = if (purchased) actualCents else null,
        category = category, priorityRaw = priority.raw, listKindRaw = list.raw, isPurchased = purchased,
        purchasedAt = if (purchased) (editing?.purchasedAt ?: System.currentTimeMillis()) else null,
    )

    FormSheet(if (editing == null) "New item" else "Edit item", close) {
        TextInput(name, { name = it }, "Item", tag = "itemName")
        TextInput(quantity, { quantity = it.filter(Char::isDigit).take(3) }, "Quantity")
        AmountInput(estimate, { estimate = it }, "Estimated price (each)")
        SectionLabel("Category"); ChipRow(cats, category, { it }) { category = it }
        SectionLabel("Priority"); ChipRow(Priority.entries, priority, { it.label }) { priority = it }
        SectionLabel("List"); ChipRow(ListKind.entries, list, { it.label }) { list = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Purchased", color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = purchased, onCheckedChange = { purchased = it })
        }
        if (purchased) AmountInput(actual, { actual = it }, "Actual price (each)")
        if (estimateCents != null && qty != null) Text("Total: ${fmt(qty * (actualCents ?: estimateCents))}", color = c.inkSecondary, fontSize = 13.sp)
        PrimaryButton("Save", enabled = valid) { vm.saveItem(build()); close() }
        if (editing != null && purchased) {
            if (editing.expenseID != null) Text("Recorded as an expense", color = c.accent, fontSize = 14.sp)
            else if (LedgerRules.canRecord(editing, ledger.transactions)) {
                TextButton(onClick = { vm.recordPurchase(build()); close() }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
                    Text("Save and record as expense", color = c.accent)
                }
                Text("Each item can be recorded once, so it can never be counted twice.", color = c.inkTertiary, fontSize = 12.sp)
            }
        }
    }
}

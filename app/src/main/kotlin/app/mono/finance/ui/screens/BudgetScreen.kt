package app.mono.finance.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.Books
import app.mono.domain.BudgetLine
import app.mono.domain.BudgetState
import app.mono.domain.BudgetSummary
import app.mono.domain.CategoryWeeks
import app.mono.domain.FinanceEngine
import app.mono.domain.Money
import app.mono.domain.MonthKey
import app.mono.domain.model.BudgetLimit
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.TransactionRecord
import app.mono.finance.MonoViewModel
import app.mono.finance.data.LedgerState
import app.mono.finance.ui.Callout
import app.mono.finance.ui.ChipRow
import app.mono.finance.ui.EmptyState
import app.mono.finance.ui.FormSheet
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.LocalMoneyContext
import app.mono.finance.ui.PrimaryButton
import app.mono.finance.ui.ProgressLine
import app.mono.finance.ui.SectionLabel
import app.mono.finance.ui.SectionTitle
import app.mono.finance.ui.TextInput
import app.mono.finance.ui.Tone
import app.mono.finance.ui.TransactionRow
import app.mono.finance.ui.AmountInput
import app.mono.finance.ui.ConfirmDialog
import app.mono.finance.ui.DateField
import app.mono.finance.ui.StatTile
import app.mono.finance.ui.clickableNoRipple
import app.mono.finance.ui.fmt
import app.mono.finance.ui.progressTint
import app.mono.finance.ui.short
import app.mono.finance.ui.surface
import app.mono.finance.ui.symbolIcon
import app.mono.finance.ui.theme.Mono
import app.mono.finance.ui.theme.figure
import app.mono.finance.ui.toLocalDate
import app.mono.finance.ui.toMillis
import java.time.LocalDate
import kotlin.math.roundToInt

private sealed interface BudgetSheet {
    data object Total : BudgetSheet
    data class Limit(val limit: BudgetLimit?) : BudgetSheet
    data class Planned(val planned: PlannedExpense?) : BudgetSheet
}

@Composable
fun BudgetScreen(ledger: LedgerState?, vm: MonoViewModel) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    var year by rememberSaveable { mutableStateOf(MonthKey.of(LocalDate.now()).year) }
    var monthNo by rememberSaveable { mutableStateOf(MonthKey.of(LocalDate.now()).month) }
    var sheet by remember { mutableStateOf<BudgetSheet?>(null) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val month = MonthKey(year, monthNo)
    val current = MonthKey.of(LocalDate.now())
    fun go(m: MonthKey) { year = m.year; monthNo = m.month }

    if (ledger == null) return
    val books = remember(ledger, money) { Books(ledger.accounts, ledger.rates, ledger.transactions, emptyList(), reporting = money.reporting) }
    val reported = books.reported
    val explicitTotal = ledger.monthBudgets.firstOrNull { it.monthKey == month.string }?.totalCents
    val summary = FinanceEngine.budgetSummary(month, explicitTotal, ledger.limits, reported)
    val prev = month.adding(-1).string
    val previousHasBudget = ledger.monthBudgets.any { it.monthKey == prev } || ledger.limits.any { it.monthKey == prev }
    val symbols = ledger.categories.associate { it.name to it.symbol }

    val open = detail
    if (open != null) {
        val line = summary.lines.firstOrNull { it.category == open }
        if (line == null) { detail = null } else {
            BackHandler { detail = null }
            CategoryDetail(
                month, line, symbols[open] ?: "tag", reported,
                ledger.limits.firstOrNull { it.monthKey == month.string && it.category == open },
                onBack = { detail = null },
                onEdit = { sheet = BudgetSheet.Limit(ledger.limits.firstOrNull { it.monthKey == month.string && it.category == open }) },
            )
            Sheets(sheet, month, ledger, summary, vm) { sheet = null }
            return
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Mono.gutter).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Budget", color = c.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Budget actions", tint = c.inkSecondary) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Set monthly total") }, onClick = { menu = false; sheet = BudgetSheet.Total })
                    DropdownMenuItem(text = { Text("Add category limit") }, onClick = { menu = false; sheet = BudgetSheet.Limit(null) })
                    if (previousHasBudget) {
                        DropdownMenuItem(text = { Text("Copy ${month.adding(-1).shortTitle()} budget") },
                            onClick = { menu = false; vm.copyBudget(month, reported, false) })
                        DropdownMenuItem(text = { Text("Copy ${month.adding(-1).shortTitle()} + unspent") },
                            onClick = { menu = false; vm.copyBudget(month, reported, true) })
                    }
                }
            }
        }

        MonthSelector(month, current, onPrev = { go(month.adding(-1)) }, onNext = { go(month.adding(1)) }, onToday = { go(current) })

        if (summary.total != null) {
            Overview(summary, month, explicitTotal != null)
            Limits(summary, symbols, onOpen = { detail = it }, onAdd = { sheet = BudgetSheet.Limit(null) })
        } else {
            Column(Modifier.fillMaxWidth().surface(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState("No budget for ${month.title()}",
                    "Set a monthly total or limits for categories like Food and Transportation to see what’s left each day.",
                    "Set monthly total") { sheet = BudgetSheet.Total }
                if (previousHasBudget) {
                    TextButton(onClick = { vm.copyBudget(month, reported, false) }) { Text("Copy ${month.adding(-1).title()} budget", color = c.accent) }
                    TextButton(onClick = { vm.copyBudget(month, reported, true) }) { Text("Copy and add what was left unspent", color = c.accent) }
                }
            }
        }

        Planned(ledger.planned, symbols, onAdd = { sheet = BudgetSheet.Planned(null) }, onEdit = { sheet = BudgetSheet.Planned(it) }, onPaid = vm::markPaid)
    }
    Sheets(sheet, month, ledger, summary, vm) { sheet = null }
}

@Composable
private fun Sheets(sheet: BudgetSheet?, month: MonthKey, ledger: LedgerState, summary: BudgetSummary, vm: MonoViewModel, close: () -> Unit) {
    when (sheet) {
        null -> {}
        BudgetSheet.Total -> TotalSheet(month, ledger.monthBudgets.firstOrNull { it.monthKey == month.string }?.totalCents,
            summary.lines.sumOf { it.limit }, vm, close)
        is BudgetSheet.Limit -> LimitSheet(month, sheet.limit, ledger, vm, close)
        is BudgetSheet.Planned -> PlannedSheet(sheet.planned, ledger, vm, close)
    }
}

@Composable
private fun MonthSelector(month: MonthKey, current: MonthKey, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    val c = Mono.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.Outlined.ChevronLeft, "Previous month", tint = c.ink) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(month.title(), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (month != current) Text("Back to this month", color = c.accent, fontSize = 12.sp, modifier = Modifier.clickableNoRipple(onToday))
        }
        IconButton(onClick = onNext, enabled = month < current.adding(12)) { Icon(Icons.Outlined.ChevronRight, "Next month", tint = c.ink) }
    }
}

@Composable
private fun Overview(s: BudgetSummary, month: MonthKey, hasExplicitTotal: Boolean) {
    val c = Mono.colors
    val tint = progressTint(if (s.state == BudgetState.OVER) 1.01 else s.fraction ?: 0.0)
    Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (s.overBy > 0) "Over budget by" else "Left to spend", color = c.inkSecondary, fontSize = 13.sp)
            Text(fmt(if (s.overBy > 0) s.overBy else (s.remaining ?: 0)), color = if (s.overBy > 0) c.danger else c.ink,
                style = figure(36, FontWeight.SemiBold), maxLines = 1)
        }
        ProgressLine(s.fraction ?: 0.0, tint, height = 8.dp)
        Row(Modifier.fillMaxWidth()) {
            Text("${fmt(s.spent)} spent of ${fmt(s.total ?: 0)}", color = c.inkSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${((s.fraction ?: 0.0) * 100).roundToInt()}% used", color = if (s.state == BudgetState.OK) c.inkSecondary else tint,
                fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        s.dailyAllowance?.let {
            Text("About ${fmt(it)} a day for the ${s.daysLeft} ${if (s.daysLeft == 1) "day" else "days"} left.", color = c.inkSecondary, fontSize = 13.sp)
        }
        when (s.state) {
            BudgetState.OVER -> Callout("You’re ${fmt(s.overBy)} over the ${month.shortTitle()} budget.", Tone.DANGER)
            BudgetState.WARNING -> Callout("${((s.fraction ?: 0.0) * 100).roundToInt()}% of the budget is used.", Tone.WARNING)
            else -> {}
        }
        Text("Counts real transactions only. Transfers, planned expenses and goal money are not spending.", color = c.inkTertiary, fontSize = 12.sp)
        if (!hasExplicitTotal && s.lines.isNotEmpty()) {
            Text("Total is the sum of your category limits. Set a monthly total to budget beyond them.", color = c.inkTertiary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Limits(s: BudgetSummary, symbols: Map<String, String>, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val c = Mono.colors
    Column(Modifier.fillMaxWidth().surface()) {
        SectionTitle("Categories", "Add limit", onAdd)
        if (s.lines.isEmpty()) Text("No category limits for this month yet.", color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        s.lines.forEachIndexed { i, line ->
            LimitRow(line, symbols[line.category] ?: "tag", Modifier.clickableNoRipple { onOpen(line.category) })
            if (i < s.lines.size - 1) Hairline()
        }
        if (s.unbudgetedSpent > 0) {
            Text("${fmt(s.unbudgetedSpent)} was spent in categories without a limit.", color = c.inkTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun LimitRow(line: BudgetLine, symbol: String, modifier: Modifier = Modifier) {
    val c = Mono.colors
    val tint = progressTint(if (line.spent > line.limit) 1.01 else line.fraction)
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Icon(symbolIcon(symbol), null, tint = tint, modifier = Modifier.size(22.dp)) }
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(line.category, color = c.ink, fontSize = 16.sp)
                Text("${fmt(line.spent)} of ${fmt(line.limit)}", color = c.inkSecondary, fontSize = 12.sp, maxLines = 1)
            }
            if (line.overBy > 0) Text("${fmt(line.overBy)} over", color = c.danger, style = figure(14))
            else Text("${fmt(line.remaining)} left", color = if (line.state == BudgetState.WARNING) c.warning else c.ink, style = figure(14), maxLines = 1)
            Icon(Icons.Outlined.ChevronRight, null, tint = c.inkTertiary, modifier = Modifier.size(18.dp))
        }
        ProgressLine(line.fraction, tint)
    }
}

@Composable
private fun Planned(
    planned: List<PlannedExpense>, symbols: Map<String, String>, onAdd: () -> Unit, onEdit: (PlannedExpense) -> Unit, onPaid: (PlannedExpense) -> Unit,
) {
    val c = Mono.colors
    val upcoming = FinanceEngine.upcoming(planned)
    val today = LocalDate.now()
    Column(Modifier.fillMaxWidth().surface()) {
        SectionTitle("Planned expenses", "Add", onAdd)
        if (upcoming.isEmpty()) Text("Bills and purchases you know are coming. Mark one paid to record it as an expense.",
            color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        upcoming.forEachIndexed { i, p ->
            val overdue = p.dueDate.toLocalDate().isBefore(today)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickableNoRipple { onEdit(p) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        Icon(symbolIcon(symbols[p.category] ?: "tag"), null, tint = if (overdue) c.warning else c.inkSecondary, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(p.title, color = c.ink, fontSize = 16.sp, maxLines = 1)
                        Text(if (overdue) "Overdue · ${p.dueDate.short()}" else "Due ${p.dueDate.short()}",
                            color = if (overdue) c.warning else c.inkSecondary, fontSize = 12.sp)
                    }
                    Text(fmt(p.amountCents), color = c.ink, style = figure(15))
                }
                IconButton(onClick = { onPaid(p) }) { Icon(Icons.Outlined.CheckCircleOutline, "Mark ${p.title} paid", tint = c.accent) }
            }
            if (i < upcoming.size - 1) Hairline()
        }
    }
}

// MARK: Category detail

@Composable
private fun CategoryDetail(
    month: MonthKey, line: BudgetLine, symbol: String, reported: List<TransactionRecord>, limit: BudgetLimit?,
    onBack: () -> Unit, onEdit: () -> Unit,
) {
    val c = Mono.colors
    val tint = progressTint(if (line.spent > line.limit) 1.01 else line.fraction)
    val mine = reported.filter { it.kind == app.mono.domain.TxKind.EXPENSE && it.category == line.category && MonthKey.of(it.date) == month }.sortedByDescending { it.date }
    val previousSpent = reported.filter { it.kind == app.mono.domain.TxKind.EXPENSE && it.category == line.category && MonthKey.of(it.date) == month.adding(-1) }.sumOf { it.amountCents }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Mono.gutter).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back", tint = c.ink) }
            Text(line.category, color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (line.overBy > 0) "Over by" else "Left", color = c.inkSecondary, fontSize = 13.sp)
            Text(fmt(if (line.overBy > 0) line.overBy else line.remaining), color = if (line.overBy > 0) c.danger else c.ink, style = figure(36, FontWeight.SemiBold))
            ProgressLine(line.fraction, tint, height = 8.dp)
            Row(Modifier.fillMaxWidth()) {
                Text("${fmt(line.spent)} of ${fmt(line.limit)}", color = c.inkSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${(line.fraction * 100).roundToInt()}%", color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("This month", fmt(line.spent), modifier = Modifier.weight(1f))
            StatTile("Last month", fmt(previousSpent), modifier = Modifier.weight(1f))
            StatTile("Change", if (previousSpent == 0L) "—" else fmt(line.spent - previousSpent, signed = true),
                tint = if (line.spent > previousSpent) c.warning else c.ink, modifier = Modifier.weight(1f))
        }
        val weeks = CategoryWeeks.spend(reported, line.category, month)
        if (weeks.any { it > 0 }) {
            Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("By week")
                WeekBars(weeks, month)
                Text("Days 1–7, 8–14, 15–21, 22–28 and 29 to the end of the month.", color = c.inkTertiary, fontSize = 12.sp)
            }
        }
        Column(Modifier.fillMaxWidth()) {
            SectionTitle("Transactions behind this total")
            if (mine.isEmpty()) Text("Nothing recorded in ${month.title()}.", color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            mine.forEachIndexed { i, t ->
                TransactionRow(t, symbol)
                if (i < mine.size - 1) Hairline()
            }
        }
        PrimaryButton(if (limit == null) "Set a limit" else "Adjust limit", onClick = onEdit)
    }
}

@Composable
private fun WeekBars(weeks: List<Long>, month: MonthKey) {
    val c = Mono.colors
    val max = (weeks.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        weeks.forEachIndexed { i, cents ->
            val h = (90 * cents.toFloat() / max).coerceAtLeast(if (cents > 0) 3f else 0f)
            val color: Color = if (CategoryWeeks.started(i, month)) c.accent else c.line
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 28.dp, height = h.dp).background(color, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                Text("Wk ${i + 1}", color = c.inkTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// MARK: Sheets

@Composable
private fun TotalSheet(month: MonthKey, current: Long?, derived: Long, vm: MonoViewModel, close: () -> Unit) {
    val c = Mono.colors
    var text by remember { mutableStateOf(current?.let { Money.editable(it) } ?: "") }
    val parsed = Money.parse(text)
    val valid = text.isEmpty() || (parsed ?: 0) > 0
    FormSheet(month.title(), close) {
        AmountInput(text, { text = it }, "Monthly total")
        Text(if (derived > 0) "Leave empty to use the sum of your category limits (${fmt(derived)})."
        else "Total you plan to spend across all categories in ${month.title()}.", color = c.inkSecondary, fontSize = 13.sp)
        PrimaryButton("Save", enabled = valid) { vm.setMonthTotal(month, if (text.isEmpty()) null else parsed); close() }
        if (current != null) TextButton(onClick = { vm.setMonthTotal(month, null); close() }, modifier = Modifier.fillMaxWidth()) {
            Text("Remove monthly total", color = c.danger)
        }
    }
}

@Composable
private fun LimitSheet(month: MonthKey, editing: BudgetLimit?, ledger: LedgerState, vm: MonoViewModel, close: () -> Unit) {
    val c = Mono.colors
    val available = ledger.categories.filter { cat ->
        cat.kindRaw == "expense" && (cat.name == editing?.category || ledger.limits.none { it.monthKey == month.string && it.category == cat.name })
    }.map { it.name }
    var category by remember { mutableStateOf(editing?.category ?: available.firstOrNull() ?: "") }
    var text by remember { mutableStateOf(editing?.let { Money.editable(it.limitCents) } ?: "") }
    var confirm by remember { mutableStateOf(false) }
    val cents = Money.parse(text)?.takeIf { it > 0 }
    FormSheet(editing?.category ?: "Category limit", close) {
        if (editing == null) { SectionLabel("Category"); ChipRow(available, category, { it }) { category = it } }
        AmountInput(text, { text = it }, "Monthly limit")
        Text("For ${month.title()}.", color = c.inkSecondary, fontSize = 13.sp)
        PrimaryButton("Save", enabled = cents != null && category.isNotBlank()) {
            vm.saveLimit((editing ?: BudgetLimit(monthKey = month.string, category = category)).copy(limitCents = cents ?: 0)); close()
        }
        if (editing != null) TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete limit", color = c.danger) }
    }
    if (confirm && editing != null) ConfirmDialog("Delete this limit?", "Spending in ${editing.category} will no longer be tracked against a limit.", "Delete", true,
        onConfirm = { vm.deleteLimit(editing); close() }, onDismiss = { confirm = false })
}

@Composable
private fun PlannedSheet(editing: PlannedExpense?, ledger: LedgerState, vm: MonoViewModel, close: () -> Unit) {
    val c = Mono.colors
    val cats = ledger.categories.filter { it.kindRaw == "expense" }.map { it.name }
    var title by remember { mutableStateOf(editing?.title ?: "") }
    var text by remember { mutableStateOf(editing?.let { Money.editable(it.amountCents) } ?: "") }
    var due by remember { mutableStateOf(editing?.dueDate?.toLocalDate() ?: LocalDate.now().plusDays(7)) }
    var category by remember { mutableStateOf(editing?.category ?: cats.firstOrNull() ?: "") }
    var confirm by remember { mutableStateOf(false) }
    val cents = Money.parse(text)?.takeIf { it > 0 }
    FormSheet(if (editing == null) "Planned expense" else "Edit planned expense", close) {
        TextInput(title, { title = it }, "What is it for?")
        AmountInput(text, { text = it }, "Amount")
        DateField("Due", due) { due = it }
        SectionLabel("Category"); ChipRow(cats, category, { it }) { category = it }
        PrimaryButton("Save", enabled = cents != null && title.isNotBlank() && category.isNotBlank()) {
            vm.savePlanned((editing ?: PlannedExpense()).copy(title = title.trim(), amountCents = cents ?: 0, dueDate = due.toMillis(), category = category)); close()
        }
        if (editing != null) TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete", color = c.danger) }
    }
    if (confirm && editing != null) ConfirmDialog("Delete this planned expense?", editing.title, "Delete", true,
        onConfirm = { vm.deletePlanned(editing); close() }, onDismiss = { confirm = false })
}

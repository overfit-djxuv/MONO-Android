package app.mono.finance.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.Books
import app.mono.domain.ContributionFrequency
import app.mono.domain.Currency
import app.mono.domain.EstimateBasis
import app.mono.domain.FinanceEngine
import app.mono.domain.FundingStatus
import app.mono.domain.GoalKind
import app.mono.domain.Money
import app.mono.domain.MonthKey
import app.mono.domain.Priority
import app.mono.domain.SavingsPlan
import app.mono.domain.SavingsPlanner
import app.mono.domain.TripCostCategory
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.TripCost
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
import app.mono.finance.ui.Metric
import app.mono.finance.ui.PrimaryButton
import app.mono.finance.ui.ProgressLine
import app.mono.finance.ui.ProgressRow
import app.mono.finance.ui.SecondaryButton
import app.mono.finance.ui.SectionLabel
import app.mono.finance.ui.SectionTitle
import app.mono.finance.ui.StatusChip
import app.mono.finance.ui.TextInput
import app.mono.finance.ui.Tone
import app.mono.finance.ui.clickableNoRipple
import app.mono.finance.ui.fmt
import app.mono.finance.ui.short
import app.mono.finance.ui.statusText
import app.mono.finance.ui.statusTint
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono
import app.mono.finance.ui.theme.figure
import app.mono.finance.ui.toLocalDate
import app.mono.finance.ui.toMillis
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** An amount in the goal's own currency; goals with no currency follow the reporting currency. */
@Composable
fun goalMoney(goal: SavingsGoal, cents: Long, signed: Boolean = false): String =
    fmt(cents, signed = signed, currency = if (goal.currencyCode.isEmpty()) null else goal.currency)

/** "Behind plan · RM 750 each month needed · by 29 Jun 2027": status, the ask, and the date, in one line. */
@Composable
fun progressNote(goal: SavingsGoal, plan: SavingsPlan): String? {
    val parts = ArrayList<String>()
    when (plan.status) {
        FundingStatus.FUNDED -> parts += "Funded"
        FundingStatus.BEHIND -> parts += "Behind plan"
        FundingStatus.OVERDUE -> parts += "Past its date"
        FundingStatus.ON_TRACK -> parts += "On track"
        FundingStatus.OPEN -> {}
    }
    val req = plan.requiredMonthly
    if (plan.status != FundingStatus.FUNDED && req != null) parts += "${goalMoney(goal, req)} each ${goal.frequency.noun} needed"
    goal.deadline?.let { parts += (if (goal.kind == GoalKind.TRIP) "departs " else "by ") + it.short() }
    return if (parts.isEmpty()) goal.details.ifEmpty { null } else parts.joinToString(" · ")
}

private fun ordered(list: List<SavingsGoal>) =
    list.sortedWith(compareByDescending<SavingsGoal> { it.priorityRaw }.thenBy { it.deadline ?: Long.MAX_VALUE })

@Composable
fun GoalsScreen(ledger: LedgerState?, vm: MonoViewModel) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var newKind by remember { mutableStateOf<GoalKind?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showFinished by remember { mutableStateOf(false) }
    if (ledger == null) return

    val open = ledger.goals.firstOrNull { it.id == selected }
    if (open != null) {
        BackHandler { selected = null }
        GoalDetail(open, ledger, vm) { selected = null }
        return
    }

    val books = remember(ledger, money) { Books(ledger.accounts, ledger.rates, emptyList(), emptyList(), reporting = money.reporting) }
    val trips = ordered(ledger.goals.filter { it.isOngoing && it.kind == GoalKind.TRIP })
    val others = ordered(ledger.goals.filter { it.isOngoing && it.kind == GoalKind.GENERAL })
    val finished = ledger.goals.filter { !it.isOngoing }.sortedByDescending { it.completedAt ?: it.createdAt }
    val (savedTotal, unconverted) = books.savedTotal(ledger.goals, ledger.entries)
    val active = ledger.goals.filter { it.isOngoing }.count { SavingsPlanner.plan(it, ledger.costs, ledger.entries).status != FundingStatus.FUNDED }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Mono.gutter).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Goals", color = c.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Add, "New goal", tint = c.ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Plan a trip") }, onClick = { menu = false; newKind = GoalKind.TRIP })
                    DropdownMenuItem(text = { Text("New savings goal") }, onClick = { menu = false; newKind = GoalKind.GENERAL })
                }
            }
        }
        if (ledger.goals.isEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                EmptyState("No goals yet",
                    "Plan a trip or save for something specific. Money you move into a goal leaves your available cash, so nothing is counted twice.",
                    "Plan a trip") { newKind = GoalKind.TRIP }
                TextButton(onClick = { newKind = GoalKind.GENERAL }, modifier = Modifier.fillMaxWidth()) { Text("New savings goal", color = c.accent) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Set aside across $active ${if (active == 1) "goal" else "goals"}", color = c.inkSecondary, fontSize = 13.sp)
                Text(fmt(savedTotal), color = c.ink, style = figure(34, FontWeight.SemiBold))
                Text("An allocation inside MONO. Nothing moves between bank accounts.", color = c.inkTertiary, fontSize = 12.sp)
                if (unconverted.isNotEmpty()) Text("${unconverted.joinToString(", ")} isn’t included: no exchange rate yet.", color = c.warning, fontSize = 12.sp)
            }
            if (trips.isNotEmpty()) GoalSection("Trips", trips, ledger) { selected = it.id }
            if (others.isNotEmpty()) GoalSection("Savings goals", others, ledger) { selected = it.id }
            if (finished.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().surface()) {
                    Row(Modifier.fillMaxWidth().clickableNoRipple { showFinished = !showFinished }, verticalAlignment = Alignment.CenterVertically) {
                        Text("Completed and archived · ${finished.size}", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Icon(if (showFinished) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = c.inkSecondary)
                    }
                    if (showFinished) finished.forEachIndexed { i, g ->
                        Row(Modifier.fillMaxWidth().clickableNoRipple { selected = g.id }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.name, color = c.ink, fontSize = 16.sp, maxLines = 1)
                                Text(g.completedAt?.let { "Completed ${it.short()}" } ?: "Archived", color = c.inkSecondary, fontSize = 13.sp)
                            }
                        }
                        if (i < finished.size - 1) Hairline()
                    }
                }
            }
        }
    }
    newKind?.let { GoalForm(it, null, ledger, vm) { newKind = null } }
}

@Composable
private fun GoalSection(title: String, list: List<SavingsGoal>, ledger: LedgerState, onOpen: (SavingsGoal) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(title)
        Column(Modifier.fillMaxWidth().surface(padding = 16.dp)) {
            list.forEachIndexed { i, g ->
                GoalRow(g, ledger, Modifier.clickableNoRipple { onOpen(g) })
                if (i < list.size - 1) Hairline()
            }
        }
    }
}

@Composable
private fun GoalRow(goal: SavingsGoal, ledger: LedgerState, modifier: Modifier = Modifier) {
    val c = Mono.colors
    val plan = SavingsPlanner.plan(goal, ledger.costs, ledger.entries)
    ProgressRow(
        symbol = if (goal.kind == GoalKind.TRIP) "airplane" else goal.symbol, title = goal.name,
        subtitle = "${goalMoney(goal, plan.saved)} of ${goalMoney(goal, plan.target)}",
        trailing = "${(plan.percentFunded * 100).roundToInt()}%", value = plan.percentFunded,
        tint = when (plan.status) { FundingStatus.OVERDUE -> c.danger; FundingStatus.BEHIND -> c.warning; else -> c.accent },
        note = progressNote(goal, plan),
        noteTint = when (plan.status) { FundingStatus.BEHIND -> c.warning; FundingStatus.OVERDUE -> c.danger; else -> c.inkSecondary },
        modifier = modifier,
    )
}

// MARK: Detail

@Composable
private fun GoalDetail(goal: SavingsGoal, ledger: LedgerState, vm: MonoViewModel, onBack: () -> Unit) {
    val c = Mono.colors
    val plan = SavingsPlanner.plan(goal, ledger.costs, ledger.entries)
    val entries = ledger.entries.filter { it.goalID == goal.id }.sortedByDescending { it.date }
    val costs = ledger.costs.filter { it.goalID == goal.id }
    var menu by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf<Boolean?>(null) } // false = add, true = withdraw
    var cost by remember { mutableStateOf<TripCost?>(null) }
    var addCost by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmComplete by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    var pendingEntry by remember { mutableStateOf<GoalEntry?>(null) }
    var details by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Mono.gutter).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back", tint = c.ink) }
            Text(goal.name, color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = c.inkSecondary) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit plan") }, onClick = { menu = false; edit = true })
                    if (goal.completedAt == null) DropdownMenuItem(text = { Text("Mark as completed") }, onClick = { menu = false; confirmComplete = true })
                    else DropdownMenuItem(text = { Text("Reopen goal") }, onClick = { menu = false; vm.saveGoal(goal.copy(completedAt = null)) })
                    if (goal.isArchived) DropdownMenuItem(text = { Text("Restore from archive") }, onClick = { menu = false; vm.saveGoal(goal.copy(isArchived = false)) })
                    else DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; confirmArchive = true })
                    DropdownMenuItem(text = { Text("Delete goal", color = c.danger) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }

        // Hero
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (goal.kind == GoalKind.TRIP) "Saved for trip" else "Saved", color = c.inkSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                StatusChip(
                    if (goal.completedAt != null) "Completed" else if (goal.isArchived) "Archived" else statusText(plan.status),
                    if (goal.completedAt != null) c.accent else if (goal.isArchived) c.inkSecondary else statusTint(plan.status),
                )
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(goalMoney(goal, plan.saved), color = c.ink, style = figure(34, FontWeight.Light))
                Text("of ${goalMoney(goal, plan.target)}", color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            ProgressLine(plan.percentFunded, if (plan.status == FundingStatus.OVERDUE) c.danger else c.accent)
            goal.deadline?.let { Text(deadlineText(goal, plan, it.toLocalDate()), color = c.inkSecondary, fontSize = 13.sp) }
            if (goal.details.isNotEmpty()) Text(goal.details, color = c.ink, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("${goal.priority.label} priority", if (goal.priority == Priority.HIGH) c.warning else c.inkSecondary)
                if (goal.currencyCode.isNotEmpty() && goal.currencyCode != Money.reporting.code) StatusChip(goal.currency.code, c.inkSecondary)
            }
        }

        // Metrics
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Left to save", goalMoney(goal, plan.remaining), Modifier.weight(1f))
                Metric("Needed each ${goal.frequency.noun}",
                    if (plan.status == FundingStatus.FUNDED) "—" else (plan.requiredMonthly?.let { goalMoney(goal, it) } ?: if (plan.remaining > 0) "Due now" else "—"),
                    Modifier.weight(1f), footnote = if (plan.status == FundingStatus.FUNDED || plan.requiredMonthly == null) null else "to finish by the date")
            }
            if (plan.status != FundingStatus.FUNDED && plan.remaining > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val est = plan.estimatedCompletion
                    val late = est != null && goal.deadline != null && est.isAfter(goal.deadline!!.toLocalDate())
                    Metric("Estimated completion", est?.short() ?: "—", Modifier.weight(1f), tint = if (late) c.warning else c.ink,
                        footnote = when (plan.estimateBasis) {
                            EstimateBasis.PLAN -> "Estimate at your plan"
                            EstimateBasis.ACTUAL_PACE -> "Estimate at your recent pace"
                            EstimateBasis.NONE -> "Add a plan to see an estimate"
                        })
                    Metric("Funded", "${(plan.percentFunded * 100).roundToInt()}%", Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth().clickableNoRipple { details = !details }, verticalAlignment = Alignment.CenterVertically) {
                Text("Plan details", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Icon(if (details) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = c.inkSecondary)
            }
            if (details) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric("Contributions left", if (goal.deadline == null) "—" else "${plan.contributionsRemaining}", Modifier.weight(1f),
                        footnote = if (goal.plannedMonthlyCents > 0) "${goalMoney(goal, goal.plannedMonthlyCents)} each ${goal.frequency.noun}" +
                            (if (goal.frequency == ContributionFrequency.MONTHLY) ", day ${goal.contributionDay}" else "") else "No plan set")
                    val eq = plan.requiredMonthlyEquivalent
                    if (eq != null && plan.status != FundingStatus.FUNDED) {
                        Metric("Monthly equivalent", goalMoney(goal, eq), Modifier.weight(1f),
                            footnote = "left to save ÷ ${plan.monthsRemaining?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} months left")
                    } else Box(Modifier.weight(1f))
                }
                if (goal.deadline != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric(if (goal.kind == GoalKind.TRIP) "Expected at departure" else "Expected at deadline", goalMoney(goal, plan.expectedAtDeadline),
                        Modifier.weight(1f), tint = if (plan.expectedAtDeadline >= plan.target) c.accent else c.ink)
                    Metric(if (plan.shortfall > 0) "Shortfall" else "Surplus", goalMoney(goal, if (plan.shortfall > 0) plan.shortfall else plan.surplus),
                        Modifier.weight(1f), tint = if (plan.shortfall > 0) c.warning else c.ink)
                }
            }
        }

        Messages(goal, plan)

        val growth = FinanceEngine.savingsGrowth(entries, MonthKey.of(LocalDate.now()))
        if (growth.size >= 2) Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Progress"); GrowthChart(growth.map { it.second })
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Add money", modifier = Modifier.weight(1f)) { move = false }
            SecondaryButton("Withdraw", modifier = Modifier.weight(1f), enabled = entries.isNotEmpty()) { move = true }
        }

        if (goal.kind == GoalKind.TRIP) Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Trip costs", "Add cost") { addCost = true }
            if (costs.isEmpty() && goal.bufferCents == 0L) Text("Add flights, stays and food to see the total to save.", color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            costs.sortedBy { it.categoryRaw }.forEachIndexed { i, cst ->
                Row(Modifier.fillMaxWidth().clickableNoRipple { cost = cst }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (cst.label.isEmpty()) cst.category.label else "${cst.category.label} · ${cst.label}", color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(goalMoney(goal, cst.amountCents), color = c.ink, style = figure(15))
                }
                if (i < costs.size - 1 || goal.bufferCents > 0) Hairline()
            }
            if (goal.bufferCents > 0) Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text("Emergency buffer", color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(goalMoney(goal, goal.bufferCents), color = c.ink, style = figure(15))
            }
        }

        Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("History")
            if (entries.isEmpty()) Text("Nothing moved into this goal yet.", color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            entries.forEachIndexed { i, e ->
                Row(Modifier.fillMaxWidth().clickableNoRipple { pendingEntry = e }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (e.amountCents < 0) "Withdrawal" else "Contribution", color = c.ink, fontSize = 16.sp)
                        Text(listOfNotNull(e.date.short(), e.note.ifBlank { null }, if (!e.affectsCash) "already saved" else null).joinToString(" · "),
                            color = c.inkSecondary, fontSize = 12.sp, maxLines = 1)
                    }
                    Text(goalMoney(goal, e.amountCents, signed = true), color = if (e.amountCents < 0) c.ink else c.accent, style = figure(15))
                }
                if (i < entries.size - 1) Hairline()
            }
        }
    }

    if (edit) GoalForm(goal.kind, goal, ledger, vm) { edit = false }
    move?.let { MoveMoneySheet(goal, it, vm) { move = null } }
    if (addCost) CostSheet(goal, null, vm) { addCost = false }
    cost?.let { CostSheet(goal, it, vm) { cost = null } }
    if (confirmDelete) {
        val fromCash = entries.filter { it.affectsCash }.sumOf { it.amountCents }
        ConfirmDialog("Delete “${goal.name}”?",
            if (fromCash > 0) "Its history and costs will be removed, and the ${goalMoney(goal, fromCash)} you moved here from cash returns to your available cash."
            else "Its history and costs will be removed.", "Delete goal", true,
            onConfirm = { vm.deleteGoal(goal); onBack() }, onDismiss = { confirmDelete = false })
    }
    val held = if (plan.saved > 0) "${goalMoney(goal, plan.saved)} stays set aside for it until you withdraw it, which returns it to your available cash. Its history is kept." else "Its history is kept."
    if (confirmComplete) ConfirmDialog("Mark “${goal.name}” as completed?", held, "Mark as completed",
        onConfirm = { vm.saveGoal(goal.copy(completedAt = System.currentTimeMillis()), "Goal completed") }, onDismiss = { confirmComplete = false })
    if (confirmArchive) ConfirmDialog("Archive “${goal.name}”?", "It leaves the active lists and your plans. $held", "Archive",
        onConfirm = { vm.saveGoal(goal.copy(isArchived = true)); onBack() }, onDismiss = { confirmArchive = false })
    pendingEntry?.let { e ->
        ConfirmDialog("Delete this entry?", "${goalMoney(goal, kotlin.math.abs(e.amountCents))} ${if (e.amountCents < 0) "withdrawal" else "contribution"} on ${e.date.short()}. Balances will be recalculated.",
            "Delete", true, onConfirm = { vm.deleteEntry(e) }, onDismiss = { pendingEntry = null })
    }
}

@Composable
private fun deadlineText(goal: SavingsGoal, p: SavingsPlan, date: LocalDate): String {
    val noun = if (goal.kind == GoalKind.TRIP) "Departure" else "Deadline"
    val days = p.daysToDeadline ?: return "$noun ${date.short()}"
    return when {
        p.status == FundingStatus.FUNDED -> "$noun ${date.short()}"
        days < 0 -> "$noun was ${date.short()} · ${-days} ${if (-days == 1) "day" else "days"} ago"
        days == 0 -> "$noun is today"
        else -> "$noun ${date.short()} · $days ${if (days == 1) "day" else "days"} to go"
    }
}

@Composable
private fun Messages(goal: SavingsGoal, p: SavingsPlan) {
    when (p.status) {
        FundingStatus.FUNDED -> Callout("Fully funded. You’ve saved ${goalMoney(goal, p.saved)} of ${goalMoney(goal, p.target)}.")
        FundingStatus.OVERDUE -> Callout("The date has passed with ${goalMoney(goal, p.remaining)} still to save. Move the date or compare plans.", Tone.DANGER)
        FundingStatus.BEHIND -> if (p.needsLumpSum) Callout("No contribution dates remain before then. ${goalMoney(goal, p.remaining)} is still needed — add it now or move the date.", Tone.WARNING)
        else Callout("At ${goalMoney(goal, goal.plannedMonthlyCents)} each ${goal.frequency.noun} you’ll be ${goalMoney(goal, p.shortfall)} short. Contributing ${goalMoney(goal, p.requiredMonthly ?: 0)} each ${goal.frequency.noun} closes the gap.", Tone.WARNING)
        FundingStatus.ON_TRACK -> Callout("On track: planned contributions reach ${goalMoney(goal, p.expectedAtDeadline)} by the date.")
        FundingStatus.OPEN -> p.monthsAtPlannedPace?.let {
            Callout("At ${goalMoney(goal, goal.plannedMonthlyCents)} each ${goal.frequency.noun}, about $it ${if (it == 1) "month" else "months"} to go.")
        }
    }
}

/** A quiet line chart of the running total saved. */
@Composable
fun GrowthChart(values: List<Long>, modifier: Modifier = Modifier) {
    val c = Mono.colors
    LocalMoneyContext.current
    val line = c.accent
    val hidden = app.mono.domain.Money.context.hidden
    Canvas(modifier.fillMaxWidth().height(110.dp)) {
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        val min = minOf(0L, values.minOrNull() ?: 0L).toFloat()
        val range = (max - min).coerceAtLeast(1f)
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = step * i
            val y = size.height - (if (hidden) size.height / 2 else (v - min) / range * size.height * 0.9f) - 4f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

// MARK: Sheets

@Composable
private fun MoveMoneySheet(goal: SavingsGoal, withdraw: Boolean, vm: MonoViewModel, close: () -> Unit) {
    val c = Mono.colors
    val cur = goal.currency
    var text by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var note by remember { mutableStateOf("") }
    var alreadySaved by remember { mutableStateOf(false) }
    val cents = Money.parse(text, cur)?.takeIf { it > 0 }
    FormSheet(if (withdraw) "Withdraw from ${goal.name}" else "Add to ${goal.name}", close) {
        AmountInput(text, { text = it }, "Amount", cur, tag = "moveAmount")
        DateField("Date", date) { date = it }
        TextInput(note, { note = it }, "Note (optional)")
        if (!withdraw) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = alreadySaved, onCheckedChange = { alreadySaved = it })
                Text("This money was already saved before using MONO (doesn’t reduce available cash)", color = c.inkSecondary, fontSize = 13.sp)
            }
        }
        Text(if (withdraw) "Withdrawing returns the money to your available cash." else "Money moved here leaves your available cash, so nothing is counted twice.",
            color = c.inkTertiary, fontSize = 12.sp)
        PrimaryButton(if (withdraw) "Withdraw" else "Add money", enabled = cents != null, modifier = Modifier.testTag("confirmMove")) {
            vm.moveMoney(goal, cents ?: 0, withdraw, date.toMillis(), note.trim(), affectsCash = !alreadySaved); close()
        }
    }
}

@Composable
private fun CostSheet(goal: SavingsGoal, editing: TripCost?, vm: MonoViewModel, close: () -> Unit) {
    val c = Mono.colors
    val cur = goal.currency
    var category by remember { mutableStateOf(editing?.category ?: TripCostCategory.FLIGHTS) }
    var label by remember { mutableStateOf(editing?.label ?: "") }
    var text by remember { mutableStateOf(editing?.let { Money.editable(it.amountCents, cur) } ?: "") }
    var confirm by remember { mutableStateOf(false) }
    val cents = Money.parse(text, cur)?.takeIf { it > 0 }
    FormSheet(if (editing == null) "Add cost" else "Edit cost", close) {
        SectionLabel("Category"); ChipRow(TripCostCategory.entries, category, { it.label }) { category = it }
        TextInput(label, { label = it }, "Label (optional)")
        AmountInput(text, { text = it }, "Amount", cur)
        PrimaryButton("Save", enabled = cents != null) {
            vm.saveCost((editing ?: TripCost(goalID = goal.id)).copy(categoryRaw = category.raw, label = label.trim(), amountCents = cents ?: 0)); close()
        }
        if (editing != null) TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete cost", color = c.danger) }
    }
    if (confirm && editing != null) ConfirmDialog("Delete this cost?", "The trip target will be recalculated.", "Delete", true,
        onConfirm = { vm.deleteCost(editing); close() }, onDismiss = { confirm = false })
}

@Composable
private fun GoalForm(kind: GoalKind, editing: SavingsGoal?, ledger: LedgerState, vm: MonoViewModel, close: () -> Unit) {
    val c = Mono.colors
    val reporting = Money.reporting
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var currencyCode by remember { mutableStateOf(editing?.currencyCode?.ifEmpty { reporting.code } ?: reporting.code) }
    val cur = Currency.find(currencyCode)
    var target by remember { mutableStateOf(editing?.let { Money.editable(it.targetCents, cur) } ?: "") }
    var destination by remember { mutableStateOf(editing?.destination ?: "") }
    var buffer by remember { mutableStateOf(editing?.let { Money.editable(it.bufferCents, cur) } ?: "") }
    var hasDeadline by remember { mutableStateOf(editing?.deadline != null || (editing == null && kind == GoalKind.TRIP)) }
    var deadline by remember { mutableStateOf(editing?.deadline?.toLocalDate() ?: LocalDate.now().plusMonths(6)) }
    var planned by remember { mutableStateOf(editing?.let { Money.editable(it.plannedMonthlyCents, cur) } ?: "") }
    var frequency by remember { mutableStateOf(editing?.frequency ?: ContributionFrequency.MONTHLY) }
    var day by remember { mutableStateOf((editing?.contributionDay ?: 1).toString()) }
    var priority by remember { mutableStateOf(editing?.priority ?: Priority.MEDIUM) }
    var details by remember { mutableStateOf(editing?.details ?: "") }

    val targetCents = Money.parse(target, cur)
    val valid = name.isNotBlank() && (kind == GoalKind.TRIP || (targetCents ?: 0) > 0)
    FormSheet(if (editing == null) (if (kind == GoalKind.TRIP) "Plan a trip" else "New savings goal") else "Edit plan", close) {
        TextInput(name, { name = it }, if (kind == GoalKind.TRIP) "Trip name" else "Goal name", tag = "goalName")
        if (kind == GoalKind.TRIP) {
            TextInput(destination, { destination = it }, "Destination (optional)")
            AmountInput(buffer, { buffer = it }, "Emergency buffer (optional)", cur)
            Text("The target is the sum of your trip costs plus the buffer. Add costs after saving.", color = c.inkTertiary, fontSize = 12.sp)
        } else AmountInput(target, { target = it }, "Target", cur, tag = "goalTarget")

        SectionLabel("Currency"); ChipRow(Currency.all.map { it.code }, currencyCode, { it }) { currencyCode = it }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (kind == GoalKind.TRIP) "Departure date" else "Deadline", color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = hasDeadline, onCheckedChange = { hasDeadline = it })
        }
        if (hasDeadline) DateField(if (kind == GoalKind.TRIP) "Departs" else "By", deadline) { deadline = it }

        AmountInput(planned, { planned = it }, "Planned contribution (optional)", cur)
        SectionLabel("How often"); ChipRow(ContributionFrequency.entries, frequency, { it.label }) { frequency = it }
        if (frequency == ContributionFrequency.MONTHLY) TextInput(day, { day = it.filter(Char::isDigit).take(2) }, "Contribution day (1–28)")
        SectionLabel("Priority"); ChipRow(Priority.entries, priority, { it.label }) { priority = it }
        TextInput(details, { details = it }, "Why this matters (optional)", singleLine = false)

        PrimaryButton("Save", enabled = valid) {
            val g = (editing ?: SavingsGoal(kindRaw = kind.raw, symbol = if (kind == GoalKind.TRIP) "airplane" else "target", createdAt = System.currentTimeMillis()))
            vm.saveGoal(g.copy(
                name = name.trim(), destination = destination.trim(),
                targetCents = if (kind == GoalKind.GENERAL) targetCents ?: 0 else g.targetCents,
                bufferCents = if (kind == GoalKind.TRIP) Money.parse(buffer, cur) ?: 0 else g.bufferCents,
                deadline = if (hasDeadline) deadline.toMillis() else null,
                plannedMonthlyCents = Money.parse(planned, cur) ?: 0,
                frequencyRaw = frequency.raw, contributionDay = (day.toIntOrNull() ?: 1).coerceIn(1, 28),
                priorityRaw = priority.raw, details = details.trim(),
                currencyCode = if (currencyCode == reporting.code) "" else currencyCode,
            ), if (editing == null) "Goal created" else null)
            close()
        }
    }
}

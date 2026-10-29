package app.mono.finance.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.Books
import app.mono.domain.FinanceEngine
import app.mono.domain.Insights
import app.mono.domain.Money
import app.mono.domain.MonthKey
import app.mono.domain.MonthlyReview
import app.mono.domain.PaymentStatus
import app.mono.domain.PurchaseSimulator
import app.mono.domain.SavingsPlanner
import app.mono.domain.TxKind
import app.mono.domain.WhatIf
import app.mono.domain.dueDate
import app.mono.domain.model.TransactionRecord
import app.mono.domain.monthlyCostEstimate
import app.mono.finance.MonoViewModel
import app.mono.finance.data.LedgerState
import app.mono.finance.ui.AmountInput
import app.mono.finance.ui.Callout
import app.mono.finance.ui.ChipRow
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.LocalMoneyContext
import app.mono.finance.ui.Metric
import app.mono.finance.ui.PrimaryButton
import app.mono.finance.ui.SecondaryButton
import app.mono.finance.ui.SectionLabel
import app.mono.finance.ui.SectionTitle
import app.mono.finance.ui.TextInput
import app.mono.finance.ui.Tone
import app.mono.finance.ui.fmt
import app.mono.finance.ui.progressTint
import app.mono.finance.ui.ProgressLine
import app.mono.finance.ui.short
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono
import app.mono.finance.ui.theme.figure
import app.mono.finance.ui.toLocalDate
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private fun reported(ledger: LedgerState, money: app.mono.domain.MoneyContext) =
    Books(ledger.accounts, ledger.rates, ledger.transactions, ledger.entries, reporting = money.reporting)

@Composable
private fun Shift(s: Insights.CategoryShift) {
    val c = Mono.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(s.category, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("${fmt(s.current)} vs ${fmt(s.previous)}", color = c.inkSecondary, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
        Text(fmt(s.delta, signed = true), color = if (s.delta > 0) c.warning else c.accent, style = figure(14))
    }
}

// MARK: Insights

@Composable
fun InsightsScreen(ledger: LedgerState?, onBack: () -> Unit) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    if (ledger == null) return
    val books = remember(ledger, money) { reported(ledger, money) }
    val txs = books.reported
    val today = LocalDate.now()
    val month = MonthKey.of(today)

    ScreenFrame("Insights", onBack) {
        if (txs.none { it.kind == TxKind.EXPENSE }) {
            Callout("Insights appear once you have recorded some spending. Everything here is worked out from your own records, on this device.")
            return@ScreenFrame
        }
        Insights.dailyAverage(txs, month, today)?.let { a ->
            Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Average spent per day", color = c.inkSecondary, fontSize = 13.sp)
                Text(fmt(a.averageCents), color = c.ink, style = figure(30, FontWeight.SemiBold))
                Text(if (a.isPartialMonth) "Over the ${a.days} days of ${month.title()} so far" else "Over all of ${month.title()}", color = c.inkTertiary, fontSize = 12.sp)
            }
        }
        val cmp = Insights.monthToDateComparison(txs, today)
        Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("This month vs the same days last month")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("First ${cmp.days} days of ${month.shortTitle()}", fmt(cmp.current), Modifier.weight(1f))
                Metric("Same days last month", fmt(cmp.previous), Modifier.weight(1f))
                Metric("Change", cmp.percent?.let { "${if (it >= 0) "+" else ""}${(it * 100).roundToInt()}%" } ?: "—", Modifier.weight(1f),
                    tint = if (cmp.delta > 0) c.warning else c.ink)
            }
            cmp.categories.take(5).forEach { Shift(it) }
            if (cmp.previous == 0L) Text("There is no spending in the same days of last month to compare with.", color = c.inkTertiary, fontSize = 12.sp)
        }
        val pattern = Insights.weekdayPattern(txs, 8, today)
        if (pattern.any { it.averageCents > 0 }) Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Spending by day of the week")
            val max = (pattern.maxOf { it.averageCents }).coerceAtLeast(1L)
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                pattern.forEach { w ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(width = 26.dp, height = (90 * w.averageCents.toFloat() / max).coerceAtLeast(if (w.averageCents > 0) 3f else 0f).dp)
                            .background(c.accent, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                        Text(java.time.DayOfWeek.of(if (w.weekday == 1) 7 else w.weekday - 1).getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            color = c.inkTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Text("An average over the last 8 weeks, counting only days since your first record.", color = c.inkTertiary, fontSize = 12.sp)
        }
        Insights.habitChanges(txs, today)?.takeIf { it.isNotEmpty() }?.let { shifts ->
            Column(Modifier.fillMaxWidth().surface()) {
                SectionTitle("Changing habits")
                Text("The last three complete months against the three before.", color = c.inkTertiary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                shifts.forEach { Shift(it) }
            }
        }
        val first = month.start().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val largest = Insights.largestExpenses(txs, first, Long.MAX_VALUE, 5)
        if (largest.isNotEmpty()) Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Largest expenses this month")
            largest.forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title.ifBlank { t.category }, color = c.ink, fontSize = 15.sp, maxLines = 1)
                        Text(t.date.short(), color = c.inkSecondary, fontSize = 12.sp)
                    }
                    Text(fmt(t.amountCents), color = c.ink, style = figure(15))
                }
                if (i < largest.size - 1) Hairline()
            }
        }
        val months = month.adding(-5).monthsThrough(month)
        val scheduled = Insights.scheduledPayments(txs, months)
        if (scheduled.any { it.second > 0 }) Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Subscriptions and bills you paid")
            scheduled.forEach { (m, cents) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(m.title(), color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text(fmt(cents), color = c.ink, style = figure(14))
                }
            }
        }
    }
}

// MARK: Monthly review

@Composable
fun ReviewScreen(ledger: LedgerState?, onBack: () -> Unit) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    val context = LocalContext.current
    var offset by remember { mutableStateOf(0) }
    if (ledger == null) return
    val today = LocalDate.now()
    val books = remember(ledger, money) { reported(ledger, money) }
    val months = MonthlyReview.months(ledger.transactions, ledger.entries, today)
    val index = (months.lastIndex + offset).coerceIn(0, months.lastIndex)
    val month = months[index]
    val txs = books.reported
    val zone = java.time.ZoneId.systemDefault()

    val goalById = ledger.goals.associateBy { it.id }
    val inMonthEntries = ledger.entries.filter { MonthKey.of(it.date.toLocalDate()) == month }
    val moves = inMonthEntries.mapNotNull { e -> goalById[e.goalID]?.let { it.name to (books.converter.convert(e.amountCents, it.currency) ?: 0L) } }
    val progress = ledger.goals.filter { it.isOngoing || ledger.entries.any { e -> e.goalID == it.id } }.mapNotNull { g ->
        val plan = SavingsPlanner.plan(g, ledger.costs, ledger.entries)
        val moved = moves.filter { it.first == g.name }.sumOf { it.second }
        if (moved == 0L && plan.saved == 0L) null else MonthlyReview.GoalProgress(g.name, moved, plan.saved, plan.target)
    }
    val scheduledTx = ledger.transactions.filter { it.occurrenceKey.isNotEmpty() && MonthKey.of(it.date.toLocalDate()) == month }
    val activeRules = ledger.rules.filter { it.status == PaymentStatus.ACTIVE && it.kind == TxKind.EXPENSE }
    val recurring = MonthlyReview.Recurring(scheduledTx.size, scheduledTx.sumOf { it.amountCents }, activeRules.size, activeRules.sumOf { it.monthlyCostEstimate })
    val horizon = today.plusDays(30)
    val upcoming = ledger.rules.filter { it.status == PaymentStatus.ACTIVE && it.kind == TxKind.EXPENSE && !it.dueDate().isAfter(horizon) }
        .map { MonthlyReview.Commitment(it.title, it.dueDate(), it.amountCents, true) } +
        ledger.planned.filter { !it.isPaid && !it.dueDate.toLocalDate().isAfter(horizon) }.map { MonthlyReview.Commitment(it.title, it.dueDate.toLocalDate(), it.amountCents, false) }
    val budget = FinanceEngine.budgetSummary(month, ledger.monthBudgets.firstOrNull { it.monthKey == month.string }?.totalCents, ledger.limits, txs, today).takeIf { it.total != null }
    val review = MonthlyReview.make(month, today, txs, moves, progress, budget, recurring, upcoming.sortedBy { it.date }, { v: Long -> Money.format(v) })

    ScreenFrame("Monthly review", onBack) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { offset -= 1 }, enabled = index > 0) { Icon(Icons.Outlined.ChevronLeft, "Previous month", tint = c.ink) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(month.title(), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (!review.isComplete) Text("In progress · ${review.daysCovered} days so far", color = c.inkSecondary, fontSize = 12.sp)
            }
            IconButton(onClick = { offset += 1 }, enabled = index < months.lastIndex) { Icon(Icons.Outlined.ChevronRight, "Next month", tint = c.ink) }
        }
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Income", fmt(review.income), Modifier.weight(1f), tint = c.income); Metric("Expenses", fmt(review.expense), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Net cash flow", fmt(review.net, signed = true), Modifier.weight(1f), tint = if (review.net < 0) c.danger else c.ink)
                Metric("Saved", review.savingsRate?.let { "${(it * 100).roundToInt()}%" } ?: "—", Modifier.weight(1f), footnote = "of income")
            }
            Metric("Moved to goals", fmt(review.movedToGoals), footnote = "a transfer to savings, not an expense")
        }
        if (review.categories.isNotEmpty()) Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Spending by category")
            review.categories.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(s.category, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f)); Text("${(s.share * 100).roundToInt()}%", color = c.inkTertiary, fontSize = 13.sp, modifier = Modifier.padding(end = 10.dp))
                    Text(fmt(s.cents), color = c.ink, style = figure(15))
                }
            }
        }
        review.budget?.let { b -> Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Budget")
            ProgressLine(b.fraction ?: 0.0, progressTint(b.fraction ?: 0.0), height = 8.dp)
            Text("Spent ${fmt(b.spent)} of ${fmt(b.total ?: 0)}", color = c.inkSecondary, fontSize = 13.sp)
        } }
        review.comparison?.let { cmp -> Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Compared with ${cmp.previous.title()}" + if (cmp.isPartial) " (first ${cmp.days} days)" else "")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Expenses", fmt(cmp.expense), Modifier.weight(1f), footnote = "vs ${fmt(cmp.previousExpense)}")
                Metric("Income", fmt(cmp.income), Modifier.weight(1f), footnote = "vs ${fmt(cmp.previousIncome)}")
            }
            cmp.categoryShifts.take(4).forEach { Shift(it) }
        } }
        if (review.largest.isNotEmpty()) Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Largest expenses")
            review.largest.forEach { t: TransactionRecord ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(t.merchant.ifBlank { t.title.ifBlank { t.category } }, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(fmt(t.amountCents), color = c.ink, style = figure(15))
                }
            }
        }
        if (review.goals.isNotEmpty()) Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Savings goals")
            review.goals.forEach { g ->
                Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) { Text(g.name, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f)); Text("${(g.percent * 100).roundToInt()}%", color = c.inkSecondary, fontSize = 13.sp) }
                    ProgressLine(g.percent, c.accent)
                    Text("${fmt(g.saved)} of ${fmt(g.target)} · ${fmt(g.movedThisMonth, signed = true)} this month", color = c.inkSecondary, fontSize = 12.sp)
                }
            }
        }
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Subscriptions and bills")
            Text("Paid this month: ${review.recurring.paidCount} totalling ${fmt(review.recurring.paidTotal)}", color = c.ink, fontSize = 14.sp)
            Text("Typical month (estimate): ${fmt(review.recurring.monthlyEstimate)}", color = c.inkSecondary, fontSize = 13.sp)
        }
        if (review.upcoming.isNotEmpty()) Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Due in the next 30 days")
            review.upcoming.take(8).forEach { u ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.weight(1f)) { Text(u.title, color = c.ink, fontSize = 15.sp); Text(u.date.short(), color = c.inkSecondary, fontSize = 12.sp) }
                    Text(fmt(u.cents), color = c.ink, style = figure(15))
                }
            }
        }
        if (review.patterns.isNotEmpty()) Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Patterns"); review.patterns.forEach { Text("• $it", color = c.ink, fontSize = 14.sp) }
        }
        if (review.worthALook.isNotEmpty()) Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Worth a look"); review.worthALook.forEach { Text("• $it", color = c.ink, fontSize = 14.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Share as text", Modifier.weight(1f)) {
                share(context, "${month.title()} review", MonthlyReview.markdown(
                    MonthlyReview.make(month, today, txs, moves, progress, budget, recurring, upcoming, { Money.format(it, ignoreHiding = true) }, zone), { Money.format(it, ignoreHiding = true) }, zone), "text/markdown")
            }
            SecondaryButton("Share as CSV", Modifier.weight(1f)) {
                share(context, "${month.title()} review", MonthlyReview.csv(
                    MonthlyReview.make(month, today, txs, moves, progress, budget, recurring, upcoming, { Money.format(it, ignoreHiding = true) }, zone), { Money.format(it, ignoreHiding = true) }, zone), "text/csv")
            }
        }
        Text("Shared files always contain the real figures, even when Hide amounts is on. Worked out on this device from your own records; nothing leaves it unless you share it.",
            color = c.inkTertiary, fontSize = 12.sp)
    }
}

private fun share(context: android.content.Context, subject: String, body: String, mime: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = if (mime == "text/csv") "text/csv" else "text/plain"; putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, body) }
    context.startActivity(Intent.createChooser(send, subject))
}

// MARK: Purchase simulator

@Composable
fun PurchaseSimulatorScreen(ledger: LedgerState?, vm: MonoViewModel, onBack: () -> Unit, initialName: String = "", initialPrice: String = "", initialCategory: String? = null) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    val config by vm.safeConfig.collectAsState()
    val hourly by vm.hourlyRate.collectAsState()
    var name by remember { mutableStateOf(initialName) }
    var priceText by remember { mutableStateOf(initialPrice) }
    var category by remember { mutableStateOf(initialCategory) }
    if (ledger == null) return
    val price = Money.parse(priceText)?.takeIf { it > 0 }
    val books = remember(ledger, money) { reported(ledger, money) }
    val ctx = PurchaseSimulator.makeContext(LocalDate.now(), books, ledger.planned, ledger.rules, ledger.goals, ledger.costs, ledger.entries,
        ledger.categories, ledger.limits, ledger.monthBudgets, ledger.reserved, config)

    ScreenFrame("Purchase simulator", onBack) {
        Text("See what a purchase would do before you buy it. Nothing here is recorded or changes your balances.", color = c.inkSecondary, fontSize = 14.sp)
        TextInput(name, { name = it }, "What is it? (optional)")
        AmountInput(priceText, { priceText = it }, "Price")
        SectionLabel("Category (optional)")
        ChipRow(ledger.categories.filter { it.kindRaw == "expense" }.map { it.name }, category, { it }) { category = if (category == it) null else it }
        TextInput(hourly, { vm.setHourlyRate(it) }, "Your hourly pay (optional, for hours of work)")

        if (price == null) { Callout("Enter a price to see the effect."); return@ScreenFrame }
        val hourlyCents = Money.parse(hourly)
        val r = PurchaseSimulator.evaluate(price, category, hourlyCents, ctx)
        Callout(if (r.fitsSafeToSpend) "${name.ifBlank { "This" }} fits within what you can safely spend." else "${name.ifBlank { "This" }} is ${fmt(r.overSafeBy)} more than you can safely spend.",
            if (r.fitsSafeToSpend) Tone.INFO else Tone.WARNING)
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Your money")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Available cash", fmt(r.cashBefore), Modifier.weight(1f), footnote = "after: ${fmt(r.cashAfter)}")
                Metric("Safe to spend", fmt(r.safeBefore), Modifier.weight(1f), footnote = "after: ${fmt(r.safeAfter)}", tint = if (r.safeAfter < 0) c.danger else c.ink)
            }
        }
        if (r.overall != null || r.category != null) Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Budget")
            r.overall?.let { Text("Monthly budget left: ${fmt(it.remainingBefore)} → ${fmt(it.remainingAfter)}" + if (it.goesOver) " (goes over)" else "", color = if (it.remainingAfter < 0) c.danger else c.ink, fontSize = 14.sp) }
            r.category?.let { Text("${category} budget left: ${fmt(it.remainingBefore)} → ${fmt(it.remainingAfter)}" + if (it.goesOver) " (goes over)" else "", color = if (it.remainingAfter < 0) c.danger else c.ink, fontSize = 14.sp) }
        }
        if (r.savingsRateBefore != null && r.savingsRateAfter != null) Text(
            "This month’s savings rate: ${(r.savingsRateBefore!! * 100).roundToInt()}% → ${(r.savingsRateAfter!! * 100).roundToInt()}%", color = c.ink, fontSize = 14.sp)
        if (r.goalDelays.isNotEmpty()) Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Effect on your goals")
            Text("If the price came out of what each goal would have received.", color = c.inkTertiary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            r.goalDelays.forEach { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(g.name, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(g.delayDays?.let { "about $it days later" + (g.newEstimate?.let { d -> " (${d.short()})" } ?: "") } ?: "no plan to measure", color = c.inkSecondary, fontSize = 13.sp)
                }
            }
        }
        r.hoursOfWork?.let { Text("About ${"%.1f".format(Locale.US, it)} hours of your work.", color = c.ink, fontSize = 14.sp) }
        val w = PurchaseSimulator.waiting30Days(price, ctx)
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("If you wait 30 days")
            Text("By ${w.date.short()} you’d have ${fmt(w.safeToSpend)} safe to spend after what’s already committed, so " +
                (if (w.stillAffordable) "it would still be affordable, leaving ${fmt(w.leftAfterBuying)}." else "it still wouldn’t fit (${fmt(-w.leftAfterBuying)} short)."),
                color = c.ink, fontSize = 14.sp)
            Text("Waiting doesn’t assume any extra income.", color = c.inkTertiary, fontSize = 12.sp)
        }
    }
}

// MARK: What-if lab

@Composable
fun WhatIfScreen(ledger: LedgerState?, vm: MonoViewModel, onBack: () -> Unit) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    val saved by vm.scenarios.collectAsState()
    var s by remember { mutableStateOf(WhatIf.Scenario()) }
    var pct by remember { mutableStateOf("") }
    var incomeAmt by remember { mutableStateOf("") }
    var newCost by remember { mutableStateOf("") }
    var override by remember { mutableStateOf("") }
    var oneTime by remember { mutableStateOf("") }
    var oneMonth by remember { mutableStateOf("1") }
    var extraMonthly by remember { mutableStateOf("") }
    if (ledger == null) return
    val books = remember(ledger, money) { reported(ledger, money) }
    val today = LocalDate.now()
    val lines = ledger.goals.filter { it.isOngoing }.mapNotNull { g ->
        val plan = SavingsPlanner.plan(g, ledger.costs, ledger.entries)
        val remaining = books.converter.convert(plan.remaining, g.currency) ?: return@mapNotNull null
        WhatIf.GoalLine(g.name, remaining, PurchaseSimulator.monthlyPace(g, books))
    }
    val month = MonthKey.of(today)
    val budgetTotal = FinanceEngine.budgetSummary(month, ledger.monthBudgets.firstOrNull { it.monthKey == month.string }?.totalCents, ledger.limits, books.reported, today).total
    val base = WhatIf.baseline(books.reported, today, books.overview.total, lines, budgetTotal)

    val scenario = s.copy(
        incomeChangePercent = pct.replace(',', '.').toDoubleOrNull()?.coerceIn(-100.0, 100.0) ?: 0.0,
        incomeChangeAmount = Money.parse(incomeAmt) ?: 0, newMonthlyCost = Money.parse(newCost) ?: 0,
        spendingOverride = override.takeIf { it.isNotBlank() }?.let { Money.parse(it) },
        oneTimeExpense = Money.parse(oneTime) ?: 0, oneTimeMonth = (oneMonth.toIntOrNull() ?: 1).coerceIn(1, 12),
        extraGoalMonthly = Money.parse(extraMonthly) ?: 0,
    )
    val plan = WhatIf.project(base)
    val alt = WhatIf.project(base, scenario)

    ScreenFrame("What-if lab", onBack) {
        Text("Try an idea on paper. Nothing here changes your records.", color = c.inkSecondary, fontSize = 14.sp)
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Your typical month")
            Text("Worked out from ${base.basis}.", color = c.inkTertiary, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Income", fmt(base.monthlyIncome), Modifier.weight(1f)); Metric("Spending", fmt(base.monthlySpending), Modifier.weight(1f))
                Metric("To goals", fmt(base.monthlyGoalSavings), Modifier.weight(1f))
            }
            Text("Starting cash ${fmt(base.startingCash)}", color = c.inkSecondary, fontSize = 13.sp)
        }
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Your what-if")
            TextInput(s.name, { s = s.copy(name = it) }, "Name")
            TextInput(pct, { pct = it }, "Income change, % (e.g. -20)")
            AmountInput(incomeAmt, { incomeAmt = it }, "Income change, each month")
            AmountInput(newCost, { newCost = it }, "A new monthly cost")
            AmountInput(override, { override = it }, "Spend this much instead of normal")
            AmountInput(oneTime, { oneTime = it }, "A one-time expense")
            TextInput(oneMonth, { oneMonth = it.filter(Char::isDigit).take(2) }, "…in month (1 = next month)")
            if (lines.isNotEmpty()) {
                SectionLabel("Save more for a goal")
                ChipRow(lines.map { it.name }, s.extraGoalName, { it }) { s = s.copy(extraGoalName = if (s.extraGoalName == it) null else it) }
                if (s.extraGoalName != null) AmountInput(extraMonthly, { extraMonthly = it }, "Extra each month")
            }
        }
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("In 12 months")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Current plan", fmt(plan.endingBalance), Modifier.weight(1f))
                Metric("Your what-if", fmt(alt.endingBalance), Modifier.weight(1f), tint = if (alt.endingBalance < plan.endingBalance) c.warning else c.ink,
                    footnote = fmt(alt.endingBalance - plan.endingBalance, signed = true))
            }
            Text("Each month: ${fmt(alt.monthlySurplus, signed = true)} left after spending and goals (current plan ${fmt(plan.monthlySurplus, signed = true)}).", color = c.inkSecondary, fontSize = 13.sp)
            alt.runsOutInMonth?.let { Callout("In this what-if your money would run out in month $it. The lowest it gets is ${fmt(alt.lowestBalance)}.", Tone.DANGER) }
            GrowthChart(alt.balances)
            if (!scenario.isUnchanged) WhatIf.goalOutlook(base, scenario, today).forEach { g ->
                Text("${g.name}: ${g.baselineDate?.short() ?: "no estimate"} → ${g.scenarioDate?.short() ?: "no estimate"}", color = c.inkSecondary, fontSize = 13.sp)
            }
        }
        PrimaryButton("Save this what-if", enabled = !scenario.isUnchanged && (saved.size < WhatIf.MAX_SCENARIOS || saved.any { it.id == s.id })) { vm.saveScenario(scenario) }
        if (saved.isNotEmpty()) Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Saved what-ifs (up to ${WhatIf.MAX_SCENARIOS})")
            saved.forEach { sv ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        s = sv; pct = if (sv.incomeChangePercent == 0.0) "" else sv.incomeChangePercent.toString(); incomeAmt = if (sv.incomeChangeAmount == 0L) "" else Money.editable(sv.incomeChangeAmount.coerceAtLeast(0))
                        newCost = if (sv.newMonthlyCost == 0L) "" else Money.editable(sv.newMonthlyCost); override = sv.spendingOverride?.let { Money.editable(it) } ?: ""
                        oneTime = if (sv.oneTimeExpense == 0L) "" else Money.editable(sv.oneTimeExpense); oneMonth = sv.oneTimeMonth.toString()
                        extraMonthly = if (sv.extraGoalMonthly == 0L) "" else Money.editable(sv.extraGoalMonthly)
                    }, modifier = Modifier.weight(1f)) { Text(sv.name, color = c.accent) }
                    TextButton(onClick = { vm.deleteScenario(sv.id) }) { Text("Delete", color = c.danger) }
                }
            }
        }
        Text("A what-if is only a set of assumptions saved on this phone. It never changes a transaction, a budget or a goal.", color = c.inkTertiary, fontSize = 12.sp)
    }
}


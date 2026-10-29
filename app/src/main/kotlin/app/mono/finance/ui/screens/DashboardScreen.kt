package app.mono.finance.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import app.mono.domain.Books
import app.mono.domain.BudgetState
import app.mono.domain.DashboardInsight
import app.mono.domain.FinanceEngine
import app.mono.domain.SafeToSpend
import app.mono.domain.SavingsPlanner
import app.mono.domain.dueDate
import app.mono.finance.ui.Callout
import app.mono.finance.ui.ProgressRow
import app.mono.finance.ui.Tone
import app.mono.finance.ui.short
import app.mono.finance.ui.toLocalDate
import app.mono.domain.Money
import app.mono.domain.MonthKey
import app.mono.finance.data.LedgerState
import app.mono.finance.ui.CategoryBreakdownList
import app.mono.finance.ui.CategoryDonut
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.IncomeExpenseChart
import app.mono.finance.ui.LocalMoneyContext
import app.mono.finance.ui.RingGauge
import app.mono.finance.ui.SectionTitle
import app.mono.finance.ui.StatTile
import app.mono.finance.ui.Route
import app.mono.finance.ui.TransactionRow
import app.mono.finance.ui.clickableNoRipple
import app.mono.finance.ui.fmt
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono
import app.mono.finance.ui.theme.figure

@Composable
fun DashboardScreen(
    ledger: LedgerState?,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
    onOpenTransactions: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onOpen: (Route) -> Unit = {},
    onOpenGoals: () -> Unit = {},
    config: app.mono.domain.SafeToSpend.Config = app.mono.domain.SafeToSpend.Config(),
) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Mono.gutter).padding(bottom = 96.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Dashboard", color = c.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { onOpen(Route.TOOLS) }) { Icon(Icons.Outlined.GridView, "Tools", tint = c.inkSecondary) }
            IconButton(onClick = onToggleHidden) {
                Icon(if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (hidden) "Show amounts" else "Hide amounts", tint = c.inkSecondary)
            }
        }
        if (ledger == null) return@Column

        val month = remember { MonthKey.of(java.time.LocalDate.now()) }
        val books = remember(ledger, money) { Books(ledger.accounts, ledger.rates, ledger.transactions, ledger.entries, reporting = money.reporting) }
        val reported = books.reported
        val (income, expense) = FinanceEngine.totals(reported, month)
        val rate = FinanceEngine.savingsRate(income, expense)
        val budget = FinanceEngine.budgetSummary(
            month, ledger.monthBudgets.firstOrNull { it.monthKey == month.string }?.totalCents, ledger.limits, reported,
        )
        val saved = books.savedTotal(ledger.goals.filter { it.isOngoing }, ledger.entries).first

        val today = java.time.LocalDate.now()
        val safe = SafeToSpend.make(today, config, books, ledger.planned, ledger.rules, ledger.goals, ledger.costs, ledger.entries,
            ledger.categories, ledger.limits, ledger.monthBudgets, ledger.reserved)
        val horizon = today.plusDays(30)
        val payments = ledger.rules.filter { it.status == app.mono.domain.PaymentStatus.ACTIVE && it.kind == app.mono.domain.TxKind.EXPENSE }
            .map { it.title to it.dueDate() } + FinanceEngine.upcoming(ledger.planned).map { it.title to it.dueDate.toLocalDate() }
        val behind = ledger.goals.filter { it.isOngoing }.filter { SavingsPlanner.plan(it, ledger.costs, ledger.entries).status == app.mono.domain.FundingStatus.BEHIND }.map { it.name }
        val insight = DashboardInsight.pick(budget, payments, behind, today)

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Hero(cash = books.overview.total, saved = saved, safe = safe, onSafe = { onOpen(Route.SAFE) }, empty = ledger.transactions.isEmpty())
            InsightLine(insight, onOpen)

            // Month summary tiles (income, expenses, budget ring, saved %, per day)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(month.title())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    StatTile("Income", fmt(income), tint = c.income, modifier = Modifier.weight(1f).fillMaxSize())
                    StatTile("Expenses", fmt(expense), modifier = Modifier.weight(1f).fillMaxSize())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    val tint = when (budget.state) { BudgetState.OVER -> c.danger; BudgetState.WARNING -> c.warning; else -> c.accent }
                    Column(Modifier.weight(1f).fillMaxSize().surface(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        RingGauge(budget.fraction ?: 0.0, tint, Modifier.size(104.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(budget.fraction?.let { "${(it * 100).roundToInt()}%" } ?: "—", color = c.ink, style = figure(24))
                                Text("used", color = c.inkTertiary, fontSize = 11.sp)
                            }
                        }
                        Text(
                            budget.remaining?.let { if (it < 0) "Over by ${fmt(-it)}" else "${fmt(it)} left" } ?: "No budget set",
                            color = if (budget.state == BudgetState.OVER) c.danger else c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        )
                        Text("Budget", color = c.inkTertiary, fontSize = 12.sp)
                    }
                    Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile(
                            "Saved", rate?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                            tint = if ((rate ?: 0.0) < 0) c.danger else c.ink,
                            footnote = when { rate == null -> "No income yet"; rate < 0 -> "Spending exceeds income"; else -> "of income" },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        StatTile(
                            "Per day", budget.dailyAllowance?.let { fmt(it) } ?: "—",
                            footnote = if (budget.dailyAllowance == null) "No budget set" else "${budget.daysLeft} ${if (budget.daysLeft == 1) "day" else "days"} left",
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
            }

            // Cash flow
            Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Cash flow")
                if (ledger.transactions.isEmpty()) {
                    Text("Add a few transactions to see income and expenses by month.", color = c.inkSecondary, fontSize = 14.sp)
                } else {
                    val first = FinanceEngine.firstRecordMonth(reported)
                    val start = first?.let { maxOf(it, month.adding(-5)) } ?: month
                    IncomeExpenseChart(FinanceEngine.monthlySummaries(reported, start.monthsThrough(month)))
                    if (first != null && first > month.adding(-5)) {
                        Text("Showing records from ${first.title()}. Earlier months aren’t estimated.", color = c.inkTertiary, fontSize = 12.sp)
                    }
                }
            }

            // Spending by category
            val slices = FinanceEngine.expensesByCategory(reported, month..month)
            Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Spending by category")
                if (slices.isEmpty()) {
                    Text("No expenses recorded in ${month.title()}.", color = c.inkSecondary, fontSize = 14.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CategoryDonut(slices) }
                    CategoryBreakdownList(slices, limit = 5)
                }
            }

            // Goals
            val activeGoals = ledger.goals.filter { it.isOngoing }
                .filter { SavingsPlanner.plan(it, ledger.costs, ledger.entries).status != app.mono.domain.FundingStatus.FUNDED }
                .sortedWith(compareByDescending<app.mono.domain.model.SavingsGoal> { it.priorityRaw }.thenBy { it.deadline ?: Long.MAX_VALUE }).take(3)
            Column(Modifier.fillMaxWidth().surface()) {
                SectionTitle("Goals", if (ledger.goals.any { it.isOngoing }) "See all" else null, onOpenGoals)
                if (ledger.goals.isEmpty()) Text("Plan a trip or a purchase to see how much to save each month.", color = c.inkSecondary, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp).clickableNoRipple(onOpenGoals))
                activeGoals.forEachIndexed { i, g ->
                    val plan = SavingsPlanner.plan(g, ledger.costs, ledger.entries)
                    ProgressRow(
                        symbol = if (g.kind == app.mono.domain.GoalKind.TRIP) "airplane" else g.symbol, title = g.name,
                        subtitle = "${goalMoney(g, plan.saved)} of ${goalMoney(g, plan.target)}", trailing = "${(plan.percentFunded * 100).roundToInt()}%",
                        value = plan.percentFunded,
                        tint = when (plan.status) { app.mono.domain.FundingStatus.OVERDUE -> c.danger; app.mono.domain.FundingStatus.BEHIND -> c.warning; else -> c.accent },
                        note = progressNote(g, plan), modifier = Modifier.clickableNoRipple(onOpenGoals),
                    )
                    if (i < activeGoals.size - 1) Hairline()
                }
            }
            val growth = FinanceEngine.savingsGrowth(books.reportableEntries(ledger.goals, ledger.entries), month)
            if (growth.size >= 2) Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Savings growth", "Goals", onOpenGoals); GrowthChart(growth.map { it.second })
            }

            // Coming up
            data class Coming(val title: String, val date: java.time.LocalDate, val amount: Long, val subscription: Boolean)
            val coming = (FinanceEngine.upcoming(ledger.planned).map { Coming(it.title, it.dueDate.toLocalDate(), it.amountCents, false) } +
                ledger.rules.filter { it.status == app.mono.domain.PaymentStatus.ACTIVE && it.kind == app.mono.domain.TxKind.EXPENSE && !it.dueDate().isAfter(today.plusDays(14)) }
                    .map { Coming(it.title, it.dueDate(), it.amountCents, true) }).sortedBy { it.date }.take(4)
            Column(Modifier.fillMaxWidth().surface()) {
                SectionTitle("Coming up", "Budget") { onOpen(Route.BUDGET) }
                if (coming.isEmpty()) Text("Nothing due soon. Add bills in Budget, or subscriptions in Tools.", color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                coming.forEachIndexed { i, p ->
                    val overdue = p.date.isBefore(today)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, color = c.ink, fontSize = 16.sp, maxLines = 1)
                            Text((if (overdue) "Overdue " else "Due ") + p.date.short() + if (p.subscription) " · subscription" else "", color = if (overdue) c.warning else c.inkSecondary, fontSize = 12.sp)
                        }
                        Text(fmt(p.amount), color = c.ink, style = figure(15))
                    }
                    if (i < coming.size - 1) Hairline()
                }
            }

            // Recent
            Column(Modifier.fillMaxWidth()) {
                SectionTitle("Recent", if (ledger.transactions.isEmpty()) null else "All transactions", onOpenTransactions)
                if (ledger.transactions.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Nothing yet", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Your latest income and expenses will show up here.", color = c.inkSecondary, fontSize = 14.sp)
                        Text("Add transaction", color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 6.dp).clickableNoRipple(onAdd))
                    }
                }
                val symbols = ledger.categories.associate { it.name to it.symbol }
                ledger.transactions.take(5).forEachIndexed { i, tx ->
                    TransactionRow(tx, symbols[tx.category] ?: "tag", Modifier.clickableNoRipple { onEdit(tx.id) })
                    if (i < minOf(ledger.transactions.size, 5) - 1) Hairline()
                }
            }
            Row(Modifier.fillMaxWidth().surface().clickableNoRipple { onOpen(Route.INSIGHTS) }, verticalAlignment = Alignment.CenterVertically) {
                Text("All analytics", color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("›", color = c.inkTertiary, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun Hero(cash: Long, saved: Long, safe: SafeToSpend.Result, onSafe: () -> Unit, empty: Boolean) {
    val c = Mono.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Mono.cardRadius))
            .background(Brush.verticalGradient(listOf(c.primary, c.heroBottom))).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Available", color = c.onPrimary.copy(alpha = 0.78f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(fmt(cash), color = c.onPrimary, style = figure(40, FontWeight.SemiBold), maxLines = 1)
        if (!empty) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(0.5.dp).background(c.onPrimary.copy(alpha = 0.22f)))
            Row(Modifier.fillMaxWidth().clickableNoRipple(onSafe), verticalAlignment = Alignment.CenterVertically) {
                Text(if (safe.isShortfall) "Short for bills and plans" else "Safe to spend until ${safe.horizonEnd.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()))}",
                    color = c.onPrimary.copy(alpha = 0.78f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(fmt(kotlin.math.abs(safe.flexible)), color = c.onPrimary, style = figure(15, FontWeight.SemiBold))
                Text("  ›", color = c.onPrimary.copy(alpha = 0.6f), fontSize = 14.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Set aside in goals", color = c.onPrimary.copy(alpha = 0.78f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(fmt(saved), color = c.onPrimary, style = figure(15))
            }
        }
    }
}

@Composable
private fun InsightLine(insight: DashboardInsight?, onOpen: (Route) -> Unit) {
    val i = insight ?: return
    when (i) {
        is DashboardInsight.OverBudget -> Row(Modifier.clickableNoRipple { onOpen(Route.BUDGET) }) { Callout("${i.category} is ${fmt(i.overBy)} over its limit this month.", Tone.DANGER) }
        is DashboardInsight.NearLimit -> Row(Modifier.clickableNoRipple { onOpen(Route.BUDGET) }) { Callout("${i.category} is at ${i.percent}% of its limit, ${fmt(i.remaining)} left.", Tone.WARNING) }
        is DashboardInsight.PaymentDue -> Row(Modifier.clickableNoRipple { onOpen(Route.SUBS) }) { Callout("${i.title} ${if (i.overdue) "was due" else "is due"} ${i.date.short()}.", if (i.overdue) Tone.DANGER else Tone.WARNING) }
        is DashboardInsight.GoalBehind -> Row(Modifier.clickableNoRipple { onOpen(Route.GOALS) }) { Callout("${i.name} is behind its plan.", Tone.WARNING) }
    }
}

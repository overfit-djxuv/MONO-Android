package app.mono.domain

import app.mono.domain.model.BudgetLimit
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.MoneyCategory
import app.mono.domain.model.MonthBudget
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.RecurringRule
import app.mono.domain.model.ReservedFund
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.TripCost
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * "How much can I spend without touching what I've already committed to?"
 *
 *   safe to spend = money in the chosen accounts
 *                 - unpaid bills due before the horizon
 *                 - planned savings contributions due before the horizon
 *                 - remaining budget for essential categories
 *                 - funds you have reserved
 *
 * Every figure is in the reporting currency. Nothing here is a forecast of income: only money you already have counts.
 */
object SafeToSpend {
    sealed interface Horizon {
        data object EndOfMonth : Horizon
        data class Days(val n: Int) : Horizon
    }

    data class Config(
        val horizon: Horizon = Horizon.EndOfMonth,
        val includeBills: Boolean = true,
        val includeSavings: Boolean = true,
        val includeEssentials: Boolean = true,
        val includeReserved: Boolean = true,
        /** Accounts whose money is left out (a savings account you never spend from, for example). */
        val excludedAccountIDs: List<String> = emptyList(),
    )

    // Inputs (already expressed in the reporting currency)

    data class AccountMoney(val id: String, val name: String, val reportingCents: Long?)
    enum class Source { PLANNED, RECURRING }
    data class Commitment(val title: String, val amountCents: Long, val dueDate: LocalDate, val category: String, val source: Source)
    data class GoalPlan(val name: String, val plannedPerContribution: Long, val remaining: Long, val dates: List<LocalDate>)
    data class EssentialBudget(val category: String, val limit: Long, val spent: Long)

    // Output

    data class Item(val id: String, val title: String, val detail: String, val amountCents: Long)

    data class Result(
        val horizonEnd: LocalDate,
        val horizonDays: Int,
        /** ACTUAL money: the included accounts' balances. */
        val accountMoney: Long,
        val includedAccounts: List<AccountMoney>,
        /** Accounts left out because their currency has no exchange rate. Their money is not in [accountMoney]. */
        val unconvertedAccounts: List<String>,
        val bills: List<Item>, val savings: List<Item>, val essentials: List<Item>,
        /** RESERVED: money you set aside by hand. */
        val reserved: List<Item>,
        val billsTotal: Long, val savingsTotal: Long, val essentialsTotal: Long, val reservedTotal: Long,
        /** What remains. Negative means your commitments exceed your money. */
        val flexible: Long,
        val perDay: Long?,
    ) {
        val isShortfall: Boolean get() = flexible < 0
    }

    // Calculation

    fun horizonEnd(h: Horizon, today: LocalDate): LocalDate = when (h) {
        is Horizon.Days -> today.plusDays((maxOf(h.n, 1) - 1).toLong())
        Horizon.EndOfMonth -> today.withDayOfMonth(today.lengthOfMonth())
    }

    private val dayFormat get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    fun calculate(
        today: LocalDate, config: Config, accounts: List<AccountMoney>, commitments: List<Commitment>, goals: List<GoalPlan>,
        essentials: List<EssentialBudget>, reserved: List<Pair<String, Long>>,
    ): Result {
        val end = horizonEnd(config.horizon, today)
        val horizonDays = maxOf(ChronoUnit.DAYS.between(today, end).toInt() + 1, 1)

        // Actual money in the chosen accounts.
        val included = accounts.filter { it.id !in config.excludedAccountIDs }
        val money = included.sumOf { it.reportingCents ?: 0L }
        val unconverted = included.filter { it.reportingCents == null }.map { it.name }

        // Bills: anything unpaid due on or before the horizon. Overdue ones still have to be paid, so they count.
        // Paid bills never appear here: paying one already lowered the account balance.
        val dueBills = commitments.filter { !it.dueDate.isAfter(end) && it.amountCents > 0 }.sortedBy { it.dueDate }
        val bills = dueBills.mapIndexed { i, c ->
            Item("bill-$i", c.title.ifEmpty { c.category },
                (if (c.dueDate.isBefore(today)) "Overdue · " else "Due ") + c.dueDate.format(dayFormat), c.amountCents)
        }

        // Planned savings: only contributions still to come before the horizon, never more than the goal still needs.
        val savings = ArrayList<Item>()
        goals.forEachIndexed { i, g ->
            if (g.remaining > 0 && g.plannedPerContribution > 0) {
                val due = g.dates.count { it.isAfter(today) && !it.isAfter(end) }
                val amount = minOf(due * g.plannedPerContribution, g.remaining)
                if (amount > 0) savings += Item("goal-$i", g.name, "$due ${if (due == 1) "contribution" else "contributions"} planned", amount)
            }
        }

        // Essentials: what is left of each essential category's budget this month, scaled to the horizon.
        // A bill already counted above is taken out of its own category so the same money isn't held back twice.
        val monthEnd = horizonEnd(Horizon.EndOfMonth, today)
        val daysLeftInMonth = maxOf(ChronoUnit.DAYS.between(today, monthEnd).toInt() + 1, 1)
        val factor = minOf(1.0, horizonDays.toDouble() / daysLeftInMonth)
        val essentialItems = ArrayList<Item>()
        essentials.forEachIndexed { i, e ->
            val left = maxOf(e.limit - e.spent, 0)
            val scaled = Math.round(left * factor)
            val alreadyCounted = dueBills.filter { it.category == e.category }.sumOf { it.amountCents }
            val amount = maxOf(scaled - alreadyCounted, 0)
            if (amount > 0) {
                essentialItems += Item("essential-$i", e.category,
                    if (alreadyCounted > 0) "Budget left, less bills already counted" else "Budget left this month", amount)
            }
        }

        val reservedItems = reserved.withIndex().filter { it.value.second > 0 }.map {
            Item("reserved-${it.index}", it.value.first, "Set aside by you", it.value.second)
        }

        fun total(items: List<Item>, on: Boolean) = if (on) items.sumOf { it.amountCents } else 0L
        val billsTotal = total(bills, config.includeBills)
        val savingsTotal = total(savings, config.includeSavings)
        val essentialsTotal = total(essentialItems, config.includeEssentials)
        val reservedTotal = total(reservedItems, config.includeReserved)
        val flexible = money - billsTotal - savingsTotal - essentialsTotal - reservedTotal
        return Result(end, horizonDays, money, included, unconverted, bills, savings, essentialItems, reservedItems,
            billsTotal, savingsTotal, essentialsTotal, reservedTotal, flexible, if (flexible > 0) flexible / horizonDays else null)
    }

    /**
     * Gathers the inputs from what is stored: balances and exchange rates, unpaid planned expenses, recurring bills that haven't
     * posted yet, goal schedules, essential categories with this month's budget, and your reserved funds.
     */
    fun make(
        today: LocalDate, config: Config, books: Books, planned: List<PlannedExpense>, rules: List<RecurringRule>, goals: List<SavingsGoal>,
        costs: List<TripCost>, entries: List<GoalEntry>, categories: List<MoneyCategory>, limits: List<BudgetLimit>,
        monthBudgets: List<MonthBudget>, reserved: List<ReservedFund>, zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val end = horizonEnd(config.horizon, today)
        val accounts = books.accounts.map { AccountMoney(it.id, it.name, books.reportingBalance(it)) }

        val commitments = ArrayList<Commitment>()
        planned.filter { !it.isPaid }.forEach {
            commitments += Commitment(it.title, it.amountCents, day(it.dueDate), it.category, Source.PLANNED)
        }
        // A repeating payment you haven't confirmed yet is still owed: every occurrence up to the horizon counts, overdue ones included.
        // Paused and cancelled payments owe nothing. Amounts are in the paying account's currency, converted with your rates.
        for (r in rules) if (r.status == PaymentStatus.ACTIVE && r.kind == TxKind.EXPENSE) {
            val currency = books.account(r.accountID)?.currency ?: Money.reporting
            val amount = books.converter.convert(r.amountCents, currency)
            if (amount == null || amount <= 0) continue
            for (due in r.dueDates(end, zone)) commitments += Commitment(r.title, amount, due, r.category, Source.RECURRING)
        }

        val goalPlans = ArrayList<GoalPlan>()
        for (g in goals) if (g.isOngoing) {
            val plan = SavingsPlanner.plan(g, costs, entries, today, zone)
            val remaining = if (plan.remaining > 0 && g.plannedMonthlyCents > 0) books.converter.convert(plan.remaining, g.currency) else null
            val perContribution = if (g.plannedMonthlyCents > 0) books.converter.convert(g.plannedMonthlyCents, g.currency) else null
            if (remaining == null || perContribution == null) continue
            val last = g.deadline?.let { minOf(end, day(it)) } ?: end
            val dates = SavingsPlanner.schedule(today, last, g.frequency, g.contributionDay, day(g.scheduleStart))
            goalPlans += GoalPlan(g.name, perContribution, remaining, dates)
        }

        val month = MonthKey.of(today)
        val summary = FinanceEngine.budgetSummary(month, monthBudgets.firstOrNull { it.monthKey == month.string }?.totalCents,
            limits, books.reported, today, zone)
        val essentialNames = categories.filter { it.isEssential && it.kind == TxKind.EXPENSE }.map { it.name }.toSet()
        val essentials = summary.lines.filter { it.category in essentialNames }.map { EssentialBudget(it.category, it.limit, it.spent) }

        return calculate(today, config, accounts, commitments, goalPlans, essentials,
            reserved.filter { it.isActive }.map { it.name to it.amountCents })
    }
}

/** The single most useful thing to say on the Dashboard. Pure, so the choice is tested; the screen only words it. */
sealed interface DashboardInsight {
    data class OverBudget(val category: String, val overBy: Long) : DashboardInsight
    data class NearLimit(val category: String, val remaining: Long, val percent: Int) : DashboardInsight
    data class PaymentDue(val title: String, val date: LocalDate, val overdue: Boolean) : DashboardInsight
    data class GoalBehind(val name: String) : DashboardInsight

    companion object {
        /** Priority: over a limit, a payment that is overdue or due within 3 days, close to a limit, a goal behind plan. */
        fun pick(budget: BudgetSummary, payments: List<Pair<String, LocalDate>>, behindGoals: List<String>, today: LocalDate = LocalDate.now()): DashboardInsight? {
            budget.lines.firstOrNull { it.state == BudgetState.OVER }?.let { return OverBudget(it.category, it.overBy) }
            payments.sortedBy { it.second }.firstOrNull { ChronoUnit.DAYS.between(today, it.second) <= 3 }?.let {
                return PaymentDue(it.first, it.second, it.second.isBefore(today))
            }
            budget.lines.firstOrNull { it.state == BudgetState.WARNING }?.let {
                return NearLimit(it.category, it.remaining, Math.round(it.fraction * 100).toInt())
            }
            behindGoals.firstOrNull()?.let { return GoalBehind(it) }
            return null
        }
    }
}

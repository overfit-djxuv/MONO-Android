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
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.ZoneId

/** "What happens if I buy this?" Everything here is an estimate worked out from your current figures. Nothing is saved or recorded. */
object PurchaseSimulator {
    data class GoalPace(
        val name: String, val priority: Int,
        /** What the plan puts toward the goal each month, in the reporting currency. */
        val monthlyPace: Long, val remaining: Long, val estimatedCompletion: LocalDate?,
    )

    data class CategoryBudget(val limit: Long, val spent: Long)

    data class Context(
        val availableCash: Long,
        /** Safe to spend with your current settings, and over the next 30 days. */
        val safeToSpendNow: Long, val safeToSpend30Days: Long, val thirtyDaysFrom: LocalDate,
        val monthIncome: Long, val monthExpense: Long,
        val budgetTotal: Long?, val budgetSpent: Long,
        val categoryBudgets: Map<String, CategoryBudget>,
        val goals: List<GoalPace>,
    )

    data class GoalDelay(
        val name: String,
        /** If the price is taken out of what this goal would have received. null when the goal has no plan to measure against. */
        val delayDays: Int?, val newEstimate: LocalDate?,
    )

    data class BudgetEffect(val limit: Long, val remainingBefore: Long, val remainingAfter: Long) {
        val goesOver: Boolean get() = remainingAfter < 0 && remainingBefore >= 0
        val alreadyOver: Boolean get() = remainingBefore < 0
    }

    data class Result(
        val price: Long,
        val cashBefore: Long, val cashAfter: Long,
        val safeBefore: Long, val safeAfter: Long,
        val overall: BudgetEffect?, val category: BudgetEffect?,
        val savingsRateBefore: Double?, val savingsRateAfter: Double?,
        val goalDelays: List<GoalDelay>,
        val hoursOfWork: Double?,
    ) {
        /** How far past your safe-to-spend money this would go (0 if it fits). */
        val overSafeBy: Long get() = maxOf(price - safeBefore, 0)
        val fitsSafeToSpend: Boolean get() = price <= safeBefore
    }

    data class WaitOutcome(
        val date: LocalDate,
        /** Safe to spend over the next 30 days, after bills and planned savings that fall due before then. */
        val safeToSpend: Long, val leftAfterBuying: Long, val stillAffordable: Boolean,
    )

    fun evaluate(price: Long, category: String?, hourlyRate: Long?, c: Context): Result {
        val overall = c.budgetTotal?.let { BudgetEffect(it, it - c.budgetSpent, it - c.budgetSpent - price) }
        val cat = category?.let { c.categoryBudgets[it] }?.let { BudgetEffect(it.limit, it.limit - it.spent, it.limit - it.spent - price) }
        fun rate(extra: Long): Double? = if (c.monthIncome > 0) (c.monthIncome - c.monthExpense - extra).toDouble() / c.monthIncome else null
        val delays = c.goals.sortedWith(compareByDescending<GoalPace> { it.priority }.thenBy { it.name }).filter { it.remaining > 0 }.map { g ->
            if (g.monthlyPace <= 0) GoalDelay(g.name, null, null)
            else {
                val days = Math.round(price.toDouble() / g.monthlyPace * (365.2425 / 12)).toInt()
                GoalDelay(g.name, days, g.estimatedCompletion?.plusDays(days.toLong()))
            }
        }
        val hours = hourlyRate?.takeIf { it > 0 }?.let { price.toDouble() / it }
        return Result(price, c.availableCash, c.availableCash - price, c.safeToSpendNow, c.safeToSpendNow - price,
            overall, cat, rate(0), rate(price), delays, hours)
    }

    /** Waiting doesn't assume any income: it only looks at what is already committed over the next 30 days. */
    fun waiting30Days(price: Long, c: Context): WaitOutcome =
        WaitOutcome(c.thirtyDaysFrom, c.safeToSpend30Days, c.safeToSpend30Days - price, price <= c.safeToSpend30Days)

    /** What a goal's plan puts toward it per month, in the reporting currency (a weekly plan counts 52 / 12 payments a month). */
    fun monthlyPace(g: SavingsGoal, books: Books): Long {
        val unit = if (g.frequency == ContributionFrequency.MONTHLY) RecurrenceUnit.MONTH else RecurrenceUnit.WEEK
        val interval = if (g.frequency == ContributionFrequency.BIWEEKLY) 2 else 1
        val perYear = SubscriptionSchedule.timesPerYear(unit, interval)
        val perMonth = BigDecimal(g.plannedMonthlyCents).multiply(perYear).divide(BigDecimal(12), MathContext.DECIMAL128).toLong() // truncates, as iOS
        return books.converter.convert(perMonth, g.currency) ?: 0
    }

    /** Gathers the figures from stored data, using the same engines as the rest of the app. */
    fun makeContext(
        today: LocalDate, books: Books, planned: List<PlannedExpense>, rules: List<RecurringRule>, goals: List<SavingsGoal>,
        costs: List<TripCost>, entries: List<GoalEntry>, categories: List<MoneyCategory>, limits: List<BudgetLimit>,
        monthBudgets: List<MonthBudget>, reserved: List<ReservedFund>, config: SafeToSpend.Config, zone: ZoneId = ZoneId.systemDefault(),
    ): Context {
        fun safe(cfg: SafeToSpend.Config) = SafeToSpend.make(today, cfg, books, planned, rules, goals, costs, entries, categories, limits, monthBudgets, reserved, zone)
        val month = MonthKey.of(today)
        val totals = FinanceEngine.totals(books.reported, month, zone)
        val summary = FinanceEngine.budgetSummary(month, monthBudgets.firstOrNull { it.monthKey == month.string }?.totalCents, limits, books.reported, today, zone)
        val catBudgets = summary.lines.associate { it.category to CategoryBudget(it.limit, it.spent) }
        val paces = goals.filter { it.isOngoing }.mapNotNull { g ->
            val plan = SavingsPlanner.plan(g, costs, entries, today, zone)
            if (plan.remaining <= 0) return@mapNotNull null
            val remaining = books.converter.convert(plan.remaining, g.currency) ?: return@mapNotNull null
            GoalPace(g.name, g.priorityRaw, monthlyPace(g, books), remaining, plan.estimatedCompletion)
        }
        return Context(
            books.overview.total, safe(config).flexible, safe(config.copy(horizon = SafeToSpend.Horizon.Days(30))).flexible, today.plusDays(30),
            totals.first, totals.second, summary.total, summary.spent, catBudgets, paces,
        )
    }
}

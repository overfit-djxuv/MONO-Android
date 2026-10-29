package app.mono.domain

import app.mono.domain.model.TransactionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * A sandbox for "what if?". It projects the next months from your recent averages and compares a hypothetical scenario with
 * your current plan. It reads your records and never writes to them: a scenario is only a set of assumptions.
 */
object WhatIf {
    data class GoalLine(
        val name: String, val remaining: Long,
        /** What your plan puts toward this goal each month (reporting currency). */
        val monthlyPace: Long,
    )

    data class Baseline(
        val monthlyIncome: Long, val monthlySpending: Long,
        /** Money leaving your accounts for goals each month under your current plans. */
        val monthlyGoalSavings: Long,
        val startingCash: Long, val budgetTotal: Long?, val goals: List<GoalLine>,
        /** How the income and spending figures were worked out, shown beside them. */
        val basis: String, val basisMonths: Int,
    )

    data class Scenario(
        val id: String = UUID.randomUUID().toString(),
        val name: String = "My scenario",
        /** -100...+100. -20 means income is a fifth lower every month. */
        val incomeChangePercent: Double = 0.0,
        val incomeChangeAmount: Long = 0,
        /** A new recurring cost each month (a new subscription, higher rent...). */
        val newMonthlyCost: Long = 0,
        /** Replace normal monthly spending with this amount (a tighter or looser budget). null keeps your current spending. */
        val spendingOverride: Long? = null,
        val oneTimeExpense: Long = 0,
        /** Which month (1 = next month) the one-time expense happens in. */
        val oneTimeMonth: Int = 1,
        val extraGoalName: String? = null,
        val extraGoalMonthly: Long = 0,
    ) {
        val isUnchanged: Boolean
            get() = incomeChangePercent == 0.0 && incomeChangeAmount == 0L && newMonthlyCost == 0L && spendingOverride == null &&
                oneTimeExpense == 0L && (extraGoalName == null || extraGoalMonthly == 0L)
    }

    data class GoalOutlook(val name: String, val baselineDate: LocalDate?, val scenarioDate: LocalDate?)

    data class Projection(val balances: List<Long>, val monthlyIncome: Long, val monthlySpending: Long, val monthlyGoalSavings: Long) {
        val monthlySurplus: Long get() = monthlyIncome - monthlySpending - monthlyGoalSavings
        val endingBalance: Long get() = balances.lastOrNull() ?: 0
        val lowestBalance: Long get() = balances.minOrNull() ?: 0
        /** The first month the money would run out (1 = next month), or null if it never does within the projection. */
        val runsOutInMonth: Int? get() = balances.drop(1).indexOfFirst { it < 0 }.takeIf { it >= 0 }?.let { it + 1 }
    }

    /**
     * Typical month from your own records: the average of up to the last three COMPLETED months you have records for.
     * With none yet, it falls back to this month so far and says so.
     */
    fun baseline(
        txs: List<TransactionRecord>, today: LocalDate, startingCash: Long, goals: List<GoalLine>, budgetTotal: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Baseline {
        val cur = MonthKey.of(today)
        val first = txs.minOfOrNull { MonthKey.of(Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate()) }
        val completed = (1..3).map { cur.adding(-it) }.filter { m -> first != null && first <= m }
        val months = completed.ifEmpty { listOf(cur) }
        var income = 0L
        var spending = 0L
        for (m in months) { val t = FinanceEngine.totals(txs, m, zone); income += t.first; spending += t.second }
        val n = maxOf(months.size, 1)
        val basis = if (completed.isEmpty()) "this month so far (there are no completed months yet)"
        else "the average of your last ${completed.size} completed ${if (completed.size == 1) "month" else "months"}"
        return Baseline(income / n, spending / n, goals.sumOf { it.monthlyPace }, startingCash, budgetTotal, goals, basis, completed.size)
    }

    fun project(b: Baseline, scenario: Scenario = Scenario(), months: Int = 12): Projection {
        val n = maxOf(months, 1)
        val income = Math.round(b.monthlyIncome * (1 + scenario.incomeChangePercent / 100)) + scenario.incomeChangeAmount
        val spending = (scenario.spendingOverride ?: b.monthlySpending) + scenario.newMonthlyCost
        val goalSavings = b.monthlyGoalSavings + if (scenario.extraGoalName == null) 0 else maxOf(scenario.extraGoalMonthly, 0)
        val balances = arrayListOf(b.startingCash)
        for (m in 1..n) {
            var next = balances[m - 1] + maxOf(income, 0) - spending - goalSavings
            if (scenario.oneTimeExpense > 0 && scenario.oneTimeMonth == m) next -= scenario.oneTimeExpense
            balances += next
        }
        return Projection(balances, maxOf(income, 0), spending, goalSavings)
    }

    /** When each goal finishes at its monthly pace, with and without the scenario's extra saving. */
    fun goalOutlook(b: Baseline, s: Scenario, today: LocalDate): List<GoalOutlook> {
        fun finish(remaining: Long, pace: Long): LocalDate? =
            if (remaining > 0 && pace > 0) today.plusMonths(ceilDiv(remaining, pace)) else null
        return b.goals.filter { it.remaining > 0 }.map { g ->
            val extra = if (s.extraGoalName == g.name) maxOf(s.extraGoalMonthly, 0) else 0
            GoalOutlook(g.name, finish(g.remaining, g.monthlyPace), finish(g.remaining, g.monthlyPace + extra))
        }
    }

    const val MAX_SCENARIOS = 3

    /** Saved scenarios are capped at three; an existing one is replaced in place. */
    fun upsert(list: List<Scenario>, s: Scenario): List<Scenario> = when {
        list.any { it.id == s.id } -> list.map { if (it.id == s.id) s else it }
        list.size < MAX_SCENARIOS -> list + s
        else -> list
    }
}

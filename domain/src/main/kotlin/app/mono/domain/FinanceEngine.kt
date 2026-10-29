package app.mono.domain

import app.mono.domain.model.BudgetLimit
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.ShoppingItem
import app.mono.domain.model.TransactionRecord
import java.time.LocalDate
import java.time.ZoneId

// Pure calculation logic. No UI, no persistence calls: screens only format what comes back.

data class MonthSummary(val month: MonthKey, val income: Long, val expense: Long, val recordCount: Int) {
    val net: Long get() = income - expense
    /** null when there is no income to measure against (never a made-up 0%). */
    val savingsRate: Double? get() = if (income > 0) (income - expense).toDouble() / income else null
}

enum class BudgetState { OK, WARNING, OVER }

data class BudgetLine(val category: String, val limit: Long, val spent: Long) {
    val remaining: Long get() = limit - spent
    val overBy: Long get() = maxOf(spent - limit, 0)
    val fraction: Double get() = if (limit > 0) spent.toDouble() / limit else if (spent > 0) 1.0 else 0.0
    val state: BudgetState
        get() = when {
            spent > limit -> BudgetState.OVER
            fraction >= FinanceEngine.WARNING_THRESHOLD -> BudgetState.WARNING
            else -> BudgetState.OK
        }
}

data class BudgetSummary(
    val month: MonthKey,
    /** Explicit monthly total, else the sum of category limits, else null. */
    val total: Long?,
    val spent: Long,
    val lines: List<BudgetLine>,
    /** Spending in categories that have no limit. */
    val unbudgetedSpent: Long,
    val daysLeft: Int,
) {
    val remaining: Long? get() = total?.let { it - spent }
    val overBy: Long get() = maxOf(spent - (total ?: spent), 0)
    val fraction: Double? get() = total?.takeIf { it > 0 }?.let { spent.toDouble() / it }
    val state: BudgetState?
        get() {
            val t = total ?: return null
            if (spent > t) return BudgetState.OVER
            return if ((fraction ?: 0.0) >= FinanceEngine.WARNING_THRESHOLD) BudgetState.WARNING else BudgetState.OK
        }

    /** Suggested average daily spend for the rest of the month. null for past months / no budget. */
    val dailyAllowance: Long?
        get() {
            val r = remaining ?: return null
            return if (daysLeft > 0) maxOf(r, 0) / daysLeft else null
        }

    fun dailyAllowance(line: BudgetLine): Long? = if (daysLeft > 0) maxOf(line.remaining, 0) / daysLeft else null
}

data class BudgetRollover(val totalCents: Long?, val limits: List<Pair<String, Long>>)

data class ShoppingSummary(val toBuy: Long, val purchased: Long, val toBuyCount: Int, val purchasedCount: Int)

object FinanceEngine {
    const val WARNING_THRESHOLD = 0.8

    private fun month(t: TransactionRecord, zone: ZoneId) = MonthKey.of(t.date, zone)

    // MARK: Balances

    fun totals(txs: List<TransactionRecord>, month: MonthKey, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        var income = 0L
        var expense = 0L
        for (t in txs) if (month(t, zone) == month) when (t.kind) {
            TxKind.INCOME -> income += t.amountCents
            TxKind.EXPENSE -> expense += t.amountCents
            TxKind.TRANSFER -> {} // moving money between your own accounts is neither income nor spending
        }
        return income to expense
    }

    /**
     * Opening balance + income - expenses - money moved into goals (+ money moved back out).
     * Goal movements are neither income nor expense, so allocating to a goal can never create or destroy money.
     */
    fun availableCash(opening: Long, transactions: List<TransactionRecord>, entries: List<GoalEntry>): Long {
        var cash = opening
        for (t in transactions) when (t.kind) {
            TxKind.INCOME -> cash += t.amountCents
            TxKind.EXPENSE -> cash -= t.amountCents
            TxKind.TRANSFER -> {}
        }
        for (e in entries) if (e.affectsCash) cash -= e.amountCents
        return cash
    }

    fun savedTotal(entries: List<GoalEntry>): Long = entries.sumOf { it.amountCents }

    fun balance(goalID: String, entries: List<GoalEntry>): Long =
        entries.sumOf { if (it.goalID == goalID) it.amountCents else 0L }

    fun savingsRate(income: Long, expense: Long): Double? =
        if (income > 0) (income - expense).toDouble() / income else null

    // MARK: Categories

    fun expensesByCategory(
        txs: List<TransactionRecord>, months: ClosedRange<MonthKey>, zone: ZoneId = ZoneId.systemDefault(),
    ): List<Pair<String, Long>> {
        val sums = HashMap<String, Long>()
        for (t in txs) if (t.kind == TxKind.EXPENSE && month(t, zone) in months) {
            sums[t.category] = (sums[t.category] ?: 0L) + t.amountCents
        }
        return sums.map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
    }

    // MARK: Series

    /** One summary per month in [range]. Months with no records report zeros and recordCount 0. */
    fun monthlySummaries(txs: List<TransactionRecord>, range: List<MonthKey>, zone: ZoneId = ZoneId.systemDefault()): List<MonthSummary> {
        val income = HashMap<MonthKey, Long>()
        val expense = HashMap<MonthKey, Long>()
        val count = HashMap<MonthKey, Int>()
        for (t in txs) if (t.kind != TxKind.TRANSFER) {
            val k = month(t, zone)
            count[k] = (count[k] ?: 0) + 1
            if (t.kind == TxKind.INCOME) income[k] = (income[k] ?: 0L) + t.amountCents
            else expense[k] = (expense[k] ?: 0L) + t.amountCents
        }
        return range.map { MonthSummary(it, income[it] ?: 0, expense[it] ?: 0, count[it] ?: 0) }
    }

    fun firstRecordMonth(
        transactions: List<TransactionRecord>, entries: List<GoalEntry> = emptyList(), zone: ZoneId = ZoneId.systemDefault(),
    ): MonthKey? = (transactions.map { it.date } + entries.map { it.date }).minOrNull()?.let { MonthKey.of(it, zone) }

    /**
     * Cumulative amount held in goals at the end of each month from the first entry to [through].
     * Only recorded movements are used; nothing before the first entry is invented.
     */
    fun savingsGrowth(entries: List<GoalEntry>, through: MonthKey, zone: ZoneId = ZoneId.systemDefault()): List<Pair<MonthKey, Long>> {
        val first = entries.minOfOrNull { it.date } ?: return emptyList()
        val perMonth = HashMap<MonthKey, Long>()
        for (e in entries) { val k = MonthKey.of(e.date, zone); perMonth[k] = (perMonth[k] ?: 0L) + e.amountCents }
        val start = MonthKey.of(first, zone)
        var running = 0L
        return start.monthsThrough(maxOf(through, start)).map {
            running += perMonth[it] ?: 0L
            it to running
        }
    }

    // MARK: Budget

    fun budgetSummary(
        month: MonthKey, totalLimit: Long?, limits: List<BudgetLimit>, transactions: List<TransactionRecord>,
        today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(),
    ): BudgetSummary {
        val spentBy = HashMap<String, Long>()
        var spent = 0L
        for (t in transactions) if (t.kind == TxKind.EXPENSE && month(t, zone) == month) {
            spentBy[t.category] = (spentBy[t.category] ?: 0L) + t.amountCents
            spent += t.amountCents
        }
        val monthLimits = limits.filter { it.monthKey == month.string }
        val lines = monthLimits.map { BudgetLine(it.category, it.limitCents, spentBy[it.category] ?: 0) }
            .sortedByDescending { it.fraction }
        val budgeted = monthLimits.map { it.category }.toSet()
        val unbudgeted = spentBy.filterKeys { it !in budgeted }.values.sum()

        val total: Long? = totalLimit ?: if (monthLimits.isEmpty()) null else monthLimits.sumOf { it.limitCents }

        val current = MonthKey.of(today)
        val days = month.daysInMonth()
        val left = when {
            month == current -> days - today.dayOfMonth + 1
            month > current -> days
            else -> 0
        }
        return BudgetSummary(month, total, spent, lines, unbudgeted, left)
    }

    /**
     * Next month's budget built from [previous]'s: each limit plus whatever was left unspent under it.
     * Overspending is not carried forward (a debt would only make next month harder).
     */
    fun rollover(
        previous: MonthKey, totalLimit: Long?, limits: List<BudgetLimit>, transactions: List<TransactionRecord>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): BudgetRollover {
        val s = budgetSummary(previous, totalLimit, limits, transactions, zone = zone)
        val lines = s.lines.map { it.category to minOf(Money.MAX_CENTS, it.limit + maxOf(0, it.limit - it.spent)) }
        val total = totalLimit?.let { minOf(Money.MAX_CENTS, it + maxOf(0, it - s.spent)) }
        return BudgetRollover(total, lines)
    }

    // MARK: Shopping

    fun shoppingSummary(items: List<ShoppingItem>): ShoppingSummary {
        var toBuy = 0L; var bought = 0L; var n1 = 0; var n2 = 0
        for (i in items) if (i.isPurchased) { bought += i.effectiveTotal; n2++ } else { toBuy += i.estimatedTotal; n1++ }
        return ShoppingSummary(toBuy, bought, n1, n2)
    }

    fun upcoming(planned: List<PlannedExpense>): List<PlannedExpense> =
        planned.filter { !it.isPaid }.sortedBy { it.dueDate }
}

package app.mono.domain

import app.mono.domain.model.GoalEntry
import app.mono.domain.model.TransactionRecord
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A report on one month, worked out entirely from your records on this device. Every number is actual unless it is labelled an estimate,
 * and every observation is a fact computed from your data, never advice.
 *
 *   Net cash flow  = income - expenses   (transfers between your accounts and money moved into goals are not counted)
 *   Savings rate   = net cash flow / income
 *   Moved to goals = money put into goals - money taken back out (a transfer to savings, not an expense)
 */
object MonthlyReview {
    data class CategorySlice(val category: String, val cents: Long, val share: Double)

    data class GoalProgress(val name: String, val movedThisMonth: Long, val saved: Long, val target: Long) {
        val percent: Double get() = if (target > 0) minOf(saved.toDouble() / target, 1.0) else 0.0
    }

    data class Comparison(
        val previous: MonthKey,
        /** Fewer than the previous month's full length: the month is still in progress, so equal numbers of days are compared. */
        val days: Int, val isPartial: Boolean,
        val income: Long, val previousIncome: Long, val expense: Long, val previousExpense: Long,
        val categoryShifts: List<Insights.CategoryShift>,
    ) {
        val net: Long get() = income - expense
        val previousNet: Long get() = previousIncome - previousExpense
    }

    data class Recurring(
        /** Payments you confirmed this month, and what they came to. */
        val paidCount: Int, val paidTotal: Long, val activeCount: Int,
        /** Estimate: what active subscriptions and bills cost in a typical month. */
        val monthlyEstimate: Long,
    )

    data class Commitment(val title: String, val date: LocalDate, val cents: Long, val isSubscription: Boolean)

    data class Review(
        val month: MonthKey, val isComplete: Boolean,
        /** Days the month has covered so far (all of them once it is complete). */
        val daysCovered: Int,
        val income: Long, val expense: Long, val movedToGoals: Long, val savingsRate: Double?, val transactionCount: Int,
        val categories: List<CategorySlice>, val budget: BudgetSummary?, val largest: List<TransactionRecord>,
        val comparison: Comparison?, val goals: List<GoalProgress>, val recurring: Recurring,
        /** Only for the month in progress: what is due in the next 30 days. */
        val upcoming: List<Commitment>, val patterns: List<String>, val worthALook: List<String>,
    ) {
        val net: Long get() = income - expense
    }

    private fun day(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun make(
        month: MonthKey, today: LocalDate, txs: List<TransactionRecord>, goalMoves: List<Pair<String, Long>>, goalProgress: List<GoalProgress>,
        budget: BudgetSummary?, recurring: Recurring, upcoming: List<Commitment>, format: (Long) -> String, zone: ZoneId = ZoneId.systemDefault(),
    ): Review {
        val current = MonthKey.of(today)
        val inMonth = txs.filter { MonthKey.of(day(it.date, zone)) == month && it.kind != TxKind.TRANSFER }
        val income = inMonth.filter { it.kind == TxKind.INCOME }.sumOf { it.amountCents }
        val expenses = inMonth.filter { it.kind == TxKind.EXPENSE }
        val expense = expenses.sumOf { it.amountCents }
        val complete = month < current
        val days = if (complete) month.daysInMonth() else if (month == current) today.dayOfMonth else 0

        val sums = HashMap<String, Long>()
        for (t in expenses) sums[t.category] = (sums[t.category] ?: 0L) + t.amountCents
        val categories = sums.map { CategorySlice(it.key, it.value, if (expense > 0) it.value.toDouble() / expense else 0.0) }
            .sortedWith(compareByDescending<CategorySlice> { it.cents }.thenBy { it.category })

        // Compare with the previous month: the whole of each once the month is finished, the same number of days while it is in progress.
        var comparison: Comparison? = null
        val prev = month.adding(-1)
        if (txs.any { MonthKey.of(day(it.date, zone)) <= prev }) {
            val through = if (complete) 31 else minOf(days, prev.daysInMonth())
            fun sumsFor(m: MonthKey): Triple<Long, Long, Map<String, Long>> {
                var i = 0L; var e = 0L; val cats = HashMap<String, Long>()
                for (t in txs) if (t.kind != TxKind.TRANSFER) {
                    val d = day(t.date, zone)
                    if (MonthKey.of(d) == m && d.dayOfMonth <= through) {
                        if (t.kind == TxKind.INCOME) i += t.amountCents else { e += t.amountCents; cats[t.category] = (cats[t.category] ?: 0L) + t.amountCents }
                    }
                }
                return Triple(i, e, cats)
            }
            val c = sumsFor(month); val p = sumsFor(prev)
            val shifts = (c.third.keys + p.third.keys).map { Insights.CategoryShift(it, c.third[it] ?: 0, p.third[it] ?: 0) }
                .sortedWith(compareByDescending<Insights.CategoryShift> { abs(it.delta) }.thenBy { it.category })
            comparison = Comparison(prev, if (complete) prev.daysInMonth() else through, !complete, c.first, p.first, c.second, p.second, shifts)
        }

        val largest = Insights.largestExpenses(inMonth, Long.MIN_VALUE, Long.MAX_VALUE, 5)
        val moved = goalMoves.sumOf { it.second }

        // Patterns: plain facts about the month.
        val patterns = ArrayList<String>()
        expenses.maxByOrNull { it.amountCents }?.let { biggest ->
            val perDay = HashMap<LocalDate, Long>()
            for (t in expenses) { val d = day(t.date, zone); perDay[d] = (perDay[d] ?: 0L) + t.amountCents }
            perDay.maxByOrNull { it.value }?.let { top ->
                patterns += "Your highest-spending day was ${top.key.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()))}: ${format(top.value)}."
            }
            patterns += "Your largest single expense was ${format(biggest.amountCents)} on ${if (biggest.category.isEmpty()) "an expense" else biggest.category}."
            val weekend = expenses.filter { day(it.date, zone).dayOfWeek.let { w -> w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY } }.sumOf { it.amountCents }
            if (expense > 0) patterns += "${(weekend.toDouble() / expense * 100).roundToInt()}% of your spending was on weekends."
            patterns += "${expenses.size} ${if (expenses.size == 1) "expense" else "expenses"}, averaging ${format(expense / maxOf(expenses.size, 1))}."
        }
        if (categories.size > 1) {
            val top = categories.first()
            patterns += "${top.category} was your biggest category at ${(top.share * 100).roundToInt()}% of spending."
        }

        // Worth a look: thresholds applied to your own numbers, phrased as observations.
        val look = ArrayList<String>()
        comparison?.let { c ->
            for (s in c.categoryShifts) if (s.delta >= 5_000 && s.previous > 0 && s.delta.toDouble() / s.previous >= 0.25) {
                look += "${s.category} is up ${format(s.delta)} (${(s.delta.toDouble() / s.previous * 100).roundToInt()}%) on " +
                    (if (c.isPartial) "the same ${c.days} days of " else "") + "${c.previous.shortTitle()}."
                if (look.size == 2) break
            }
        }
        budget?.let { b ->
            for (l in b.lines) if (l.spent > l.limit) { look += "${l.category} went ${format(l.spent - l.limit)} over its budget."; if (look.size >= 3) break }
        }
        val small = expenses.filter { it.amountCents < 2_000 }
        if (small.size >= 8 && expense > 0 && small.sumOf { it.amountCents }.toDouble() / expense >= 0.10) {
            look += "${small.size} small purchases under ${format(2_000)} added up to ${format(small.sumOf { it.amountCents })}."
        }
        if (recurring.activeCount > 0 && recurring.monthlyEstimate > 0) {
            look += "${recurring.activeCount} active subscriptions and bills cost about ${format(recurring.monthlyEstimate)} a month (an estimate)."
        }

        return Review(month, complete, days, income, expense, moved, FinanceEngine.savingsRate(income, expense), inMonth.size, categories, budget,
            largest, comparison, goalProgress, recurring, if (month == current) upcoming else emptyList(), patterns, look.take(4))
    }

    /** Months that have any record, oldest first, through the current month. */
    fun months(txs: List<TransactionRecord>, entries: List<GoalEntry>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<MonthKey> {
        val cur = MonthKey.of(today)
        val first = FinanceEngine.firstRecordMonth(txs, entries, zone)
        if (first == null || first > cur) return listOf(cur)
        return first.monthsThrough(cur)
    }

    // Export. Always the real numbers: you asked to export it, so Hide amounts doesn't apply to the file.

    private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
    private fun label(t: TransactionRecord) = if (t.merchant.isEmpty()) (if (t.title.isEmpty()) t.category else t.title) else t.merchant

    fun markdown(r: Review, format: (Long) -> String, zone: ZoneId = ZoneId.systemDefault()): String {
        val out = ArrayList<String>()
        out += listOf("# ${r.month.title()} review", if (r.isComplete) "" else "_In progress: ${r.daysCovered} days so far._", "", "## Summary",
            "- Income: ${format(r.income)}", "- Expenses: ${format(r.expense)}", "- Net cash flow: ${format(r.net)}",
            "- Moved to goals: ${format(r.movedToGoals)}", "- Savings rate: ${r.savingsRate?.let(::pct) ?: "n/a"}", "")
        if (r.categories.isNotEmpty()) out += listOf("## Spending by category") + r.categories.map { "- ${it.category}: ${format(it.cents)} (${pct(it.share)})" } + ""
        r.budget?.let { b -> b.total?.let { total ->
            out += listOf("## Budget", "- Spent ${format(b.spent)} of ${format(total)}") + b.lines.map { "- ${it.category}: ${format(it.spent)} of ${format(it.limit)}" } + ""
        } }
        r.comparison?.let { c ->
            out += listOf("## Compared with ${c.previous.title()}${if (c.isPartial) " (first ${c.days} days)" else ""}",
                "- Income: ${format(c.income)} vs ${format(c.previousIncome)}", "- Expenses: ${format(c.expense)} vs ${format(c.previousExpense)}", "")
        }
        if (r.largest.isNotEmpty()) {
            out += listOf("## Largest expenses") + r.largest.map {
                "- ${format(it.amountCents)}: ${label(it)} (${day(it.date, zone).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))})"
            } + ""
        }
        if (r.goals.isNotEmpty()) out += listOf("## Savings goals") + r.goals.map { "- ${it.name}: ${format(it.saved)} of ${format(it.target)} (${format(it.movedThisMonth)} this month)" } + ""
        out += listOf("## Subscriptions and bills", "- Paid this month: ${r.recurring.paidCount} totalling ${format(r.recurring.paidTotal)}",
            "- Typical month (estimate): ${format(r.recurring.monthlyEstimate)}", "")
        if (r.patterns.isNotEmpty()) out += listOf("## Patterns") + r.patterns.map { "- $it" } + ""
        if (r.worthALook.isNotEmpty()) out += listOf("## Worth a look") + r.worthALook.map { "- $it" } + ""
        out += "_Generated on this device from your own records. Net cash flow = income − expenses; savings rate = net ÷ income._"
        return out.joinToString("\n")
    }

    /** A CSV field: quoted when it contains a comma, quote or line break, with quotes doubled. */
    fun csvCell(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun csv(r: Review, format: (Long) -> String, zone: ZoneId = ZoneId.systemDefault()): String {
        fun row(vararg c: String) = c.joinToString(",") { csvCell(it) }
        val rows = arrayListOf("section,item,amount,note")
        rows += row("summary", "Income", format(r.income), ""); rows += row("summary", "Expenses", format(r.expense), "")
        rows += row("summary", "Net cash flow", format(r.net), ""); rows += row("summary", "Moved to goals", format(r.movedToGoals), "")
        rows += r.categories.map { row("category", it.category, format(it.cents), pct(it.share)) }
        rows += r.largest.map { row("largest expense", label(it), format(it.amountCents), day(it.date, zone).toString()) }
        rows += r.goals.map { row("goal", it.name, format(it.saved), "target ${format(it.target)}") }
        return rows.joinToString("\r\n") + "\r\n"
    }
}

package app.mono.domain

import app.mono.domain.model.TransactionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs

/**
 * Spending insights computed only from recorded transactions. Every function takes records already expressed in the reporting
 * currency (transfers excluded; see `Books.reported`) and returns plain numbers. Nothing here forecasts: results are either actual,
 * or compare equivalent periods, and are labelled as such by the screens.
 */
object Insights {
    private fun day(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    data class DailyAverage(val totalCents: Long, val days: Int, val isPartialMonth: Boolean) {
        val averageCents: Long get() = if (days > 0) totalCents / days else 0
    }

    /**
     * Average spent per day in [month]. For the current month only the days that have happened count, so a half-finished month
     * isn't made to look cheap. null for a future month.
     */
    fun dailyAverage(txs: List<TransactionRecord>, month: MonthKey, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DailyAverage? {
        val current = MonthKey.of(today)
        if (month > current) return null
        val total = txs.filter { it.kind == TxKind.EXPENSE && MonthKey.of(day(it.date, zone)) == month }.sumOf { it.amountCents }
        return if (month == current) DailyAverage(total, today.dayOfMonth, true) else DailyAverage(total, month.daysInMonth(), false)
    }

    data class CategoryShift(val category: String, val current: Long, val previous: Long) {
        val delta: Long get() = current - previous
    }

    data class PeriodComparison(val days: Int, val current: Long, val previous: Long, val categories: List<CategoryShift>) {
        val delta: Long get() = current - previous
        val percent: Double? get() = if (previous > 0) delta.toDouble() / previous else null
    }

    private val shiftOrder = compareByDescending<CategoryShift> { abs(it.delta) }.thenBy { it.category }

    fun monthToDateComparison(txs: List<TransactionRecord>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): PeriodComparison {
        val cur = MonthKey.of(today)
        return periodComparison(txs, cur, cur.adding(-1), today.dayOfMonth, zone)
    }

    /** Spending in the first [wanted] days of [cur] against the first [wanted] days of [prev] (capped at the shorter month). */
    fun periodComparison(
        txs: List<TransactionRecord>, cur: MonthKey, prev: MonthKey, wanted: Int, zone: ZoneId = ZoneId.systemDefault(),
    ): PeriodComparison {
        val days = minOf(wanted, prev.daysInMonth(), cur.daysInMonth())
        val curCat = HashMap<String, Long>()
        val prevCat = HashMap<String, Long>()
        for (t in txs) if (t.kind == TxKind.EXPENSE) {
            val d = day(t.date, zone)
            if (d.dayOfMonth > days) continue
            val k = MonthKey.of(d)
            if (k == cur) curCat[t.category] = (curCat[t.category] ?: 0L) + t.amountCents
            else if (k == prev) prevCat[t.category] = (prevCat[t.category] ?: 0L) + t.amountCents
        }
        val shifts = (curCat.keys + prevCat.keys).map { CategoryShift(it, curCat[it] ?: 0, prevCat[it] ?: 0) }.sortedWith(shiftOrder)
        return PeriodComparison(days, curCat.values.sum(), prevCat.values.sum(), shifts)
    }

    /** weekday: 1 = Sunday ... 7 = Saturday (the same numbering as the iOS calendar). */
    data class WeekdayAverage(val weekday: Int, val averageCents: Long, val occurrences: Int)

    private fun weekdayOf(d: LocalDate): Int = d.dayOfWeek.value % 7 + 1

    /** The first weekday of the user's week, in the same 1 = Sunday numbering. */
    fun firstWeekday(locale: Locale = Locale.getDefault()): Int = WeekFields.of(locale).firstDayOfWeek.value % 7 + 1

    /**
     * Average spending on each weekday over the last [weeks] weeks (never before your first record), in the order your calendar shows them.
     */
    fun weekdayPattern(
        txs: List<TransactionRecord>, weeks: Int = 8, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), firstWeekday: Int = firstWeekday(),
    ): List<WeekdayAverage> {
        val wanted = today.minusDays((weeks * 7 - 1).toLong())
        val expenses = txs.filter { it.kind == TxKind.EXPENSE }
        val first = expenses.minOfOrNull { day(it.date, zone) } ?: today
        val start = maxOf(wanted, first)
        val totals = HashMap<Int, Long>()
        val counts = HashMap<Int, Int>()
        var d = start
        while (!d.isAfter(today)) { counts[weekdayOf(d)] = (counts[weekdayOf(d)] ?: 0) + 1; d = d.plusDays(1) }
        for (t in expenses) {
            val dd = day(t.date, zone)
            if (!dd.isBefore(start) && !dd.isAfter(today)) totals[weekdayOf(dd)] = (totals[weekdayOf(dd)] ?: 0L) + t.amountCents
        }
        val order = (0 until 7).map { (firstWeekday - 1 + it) % 7 + 1 }
        return order.map { wd ->
            val n = counts[wd] ?: 0
            WeekdayAverage(wd, if (n > 0) (totals[wd] ?: 0L) / n else 0, n)
        }
    }

    /** Spending in the given categories for each month. */
    fun categoryTotals(
        txs: List<TransactionRecord>, categories: Set<String>, months: List<MonthKey>, zone: ZoneId = ZoneId.systemDefault(),
    ): List<Pair<MonthKey, Long>> {
        val sums = HashMap<MonthKey, Long>()
        for (t in txs) if (t.kind == TxKind.EXPENSE && t.category in categories) {
            val k = MonthKey.of(day(t.date, zone)); sums[k] = (sums[k] ?: 0L) + t.amountCents
        }
        return months.map { it to (sums[it] ?: 0L) }
    }

    fun largestExpenses(txs: List<TransactionRecord>, from: Long, through: Long, limit: Int = 5): List<TransactionRecord> =
        txs.filter { it.kind == TxKind.EXPENSE && it.date in from..through }
            .sortedWith(compareByDescending<TransactionRecord> { it.amountCents }.thenByDescending { it.date })
            .take(limit)

    /**
     * How each category's spending changed between the last three COMPLETE months and the three before them.
     * null unless your records reach back across both windows: three months against one isn't a comparison.
     */
    fun habitChanges(
        txs: List<TransactionRecord>, today: LocalDate, monthsPerWindow: Int = 3, limit: Int = 5, zone: ZoneId = ZoneId.systemDefault(),
    ): List<CategoryShift>? {
        val n = monthsPerWindow
        val cur = MonthKey.of(today)
        val recent = (1..n).map { cur.adding(-it) }
        val before = (n + 1..2 * n).map { cur.adding(-it) }
        val first = txs.minOfOrNull { MonthKey.of(day(it.date, zone)) } ?: return null
        if (first > before.last()) return null
        val a = HashMap<String, Long>()
        val b = HashMap<String, Long>()
        for (t in txs) if (t.kind == TxKind.EXPENSE) {
            val k = MonthKey.of(day(t.date, zone))
            if (k in recent) a[t.category] = (a[t.category] ?: 0L) + t.amountCents
            else if (k in before) b[t.category] = (b[t.category] ?: 0L) + t.amountCents
        }
        return (a.keys + b.keys).map { CategoryShift(it, a[it] ?: 0, b[it] ?: 0) }.filter { it.delta != 0L }.sortedWith(shiftOrder).take(limit)
    }

    /** What you actually paid toward subscriptions and bills each month: only transactions recorded from a schedule. */
    fun scheduledPayments(txs: List<TransactionRecord>, months: List<MonthKey>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<MonthKey, Long>> {
        val sums = HashMap<MonthKey, Long>()
        for (t in txs) if (t.kind == TxKind.EXPENSE && t.occurrenceKey.isNotEmpty()) {
            val k = MonthKey.of(day(t.date, zone)); sums[k] = (sums[k] ?: 0L) + t.amountCents
        }
        return months.map { it to (sums[it] ?: 0L) }
    }

}

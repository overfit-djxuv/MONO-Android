package app.mono.domain

import app.mono.domain.model.RecurringRule
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Dates and costs for repeating payments. Pure functions. */
object SubscriptionSchedule {
    /**
     * The payment after [date]: a number of weeks later, or the same day of a later month or year.
     * Months and years keep [anchorDay], so a payment on the 31st falls on the last day of shorter months and returns to the 31st after.
     */
    fun advance(date: LocalDate, unit: RecurrenceUnit, interval: Int, anchorDay: Int): LocalDate {
        val n = maxOf(interval, 1)
        return when (unit) {
            RecurrenceUnit.WEEK -> date.plusDays(7L * n)
            RecurrenceUnit.MONTH, RecurrenceUnit.YEAR -> {
                val m = MonthKey.of(date).adding(if (unit == RecurrenceUnit.MONTH) n else 12 * n)
                LocalDate.of(m.year, m.month, anchorDay.coerceIn(1, m.daysInMonth()))
            }
        }
    }

    /** Every payment from [next] up to and including [end], oldest first. Includes overdue ones because they are still owed. */
    fun dueDates(next: LocalDate, end: LocalDate, unit: RecurrenceUnit, interval: Int, anchorDay: Int, limit: Int = 60): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = next
        while (!d.isAfter(end) && out.size < limit) {
            out += d
            val following = advance(d, unit, interval, anchorDay)
            if (!following.isAfter(d)) break
            d = following
        }
        return out
    }

    /** The first payment on or after [date] that keeps the schedule's rhythm. Used when resuming, so nothing missed while paused is owed. */
    fun firstOnOrAfter(date: LocalDate, next: LocalDate, unit: RecurrenceUnit, interval: Int, anchorDay: Int): LocalDate {
        var d = next
        var guard = 0
        while (d.isBefore(date) && guard < 5_000) {
            val following = advance(d, unit, interval, anchorDay)
            if (!following.isAfter(d)) break
            d = following; guard++
        }
        return d
    }

    fun timesPerYear(unit: RecurrenceUnit, interval: Int): BigDecimal {
        val n = BigDecimal(maxOf(interval, 1))
        val mc = MathContext.DECIMAL128
        return when (unit) {
            RecurrenceUnit.WEEK -> BigDecimal(52).divide(n, mc)
            RecurrenceUnit.MONTH -> BigDecimal(12).divide(n, mc)
            RecurrenceUnit.YEAR -> BigDecimal.ONE.divide(n, mc)
        }
    }

    private fun rounded(d: BigDecimal): Long = d.setScale(0, RoundingMode.HALF_UP).toLong()

    /** An estimate: the amount spread evenly over twelve months. */
    fun monthlyCost(amountCents: Long, unit: RecurrenceUnit, interval: Int): Long =
        rounded(BigDecimal(amountCents).multiply(timesPerYear(unit, interval)).divide(BigDecimal(12), MathContext.DECIMAL128))

    fun annualCost(amountCents: Long, unit: RecurrenceUnit, interval: Int): Long =
        rounded(BigDecimal(amountCents).multiply(timesPerYear(unit, interval)))

    /** Names a schedule the way people say it. */
    fun describe(unit: RecurrenceUnit, interval: Int): String = when {
        unit == RecurrenceUnit.WEEK && interval == 1 -> "Weekly"
        unit == RecurrenceUnit.WEEK && interval == 2 -> "Every 2 weeks"
        unit == RecurrenceUnit.MONTH && interval == 1 -> "Monthly"
        unit == RecurrenceUnit.MONTH && interval == 3 -> "Every 3 months"
        unit == RecurrenceUnit.MONTH && interval == 6 -> "Every 6 months"
        unit == RecurrenceUnit.YEAR && interval == 1 -> "Yearly"
        interval <= 1 -> "Every ${unit.raw}"
        else -> "Every $interval ${unit.raw}s"
    }
}

// Helpers on the stored rule

val RecurringRule.anchorDay: Int get() = dayOfMonth.coerceIn(1, 31)
val RecurringRule.scheduleText: String get() = SubscriptionSchedule.describe(unit, interval)

/** When the next unconfirmed payment falls due. */
fun RecurringRule.dueDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate {
    nextDueDate?.let { return Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val m = MonthKey.parse(nextMonthKey) ?: return LocalDate.now(zone)
    return LocalDate.of(m.year, m.month, dayOfMonth.coerceIn(1, m.daysInMonth()))
}

/** Unconfirmed payments from the next one through [end]. */
fun RecurringRule.dueDates(end: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<LocalDate> =
    SubscriptionSchedule.dueDates(dueDate(zone), end, unit, interval, anchorDay)

fun RecurringRule.following(date: LocalDate): LocalDate = SubscriptionSchedule.advance(date, unit, interval, anchorDay)

val RecurringRule.monthlyCostEstimate: Long get() = SubscriptionSchedule.monthlyCost(amountCents, unit, interval)
val RecurringRule.annualCostEstimate: Long get() = SubscriptionSchedule.annualCost(amountCents, unit, interval)

/** The key that identifies one occurrence, e.g. "<id>|2026-03-05". */
fun RecurringRule.occurrenceKey(due: LocalDate): String = "$id|%04d-%02d-%02d".format(due.year, due.monthValue, due.dayOfMonth)

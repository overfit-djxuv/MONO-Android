package app.mono.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A calendar month, used to bucket records and budgets. */
data class MonthKey(val year: Int, val month: Int) : Comparable<MonthKey> {

    val string: String get() = "%04d-%02d".format(Locale.ROOT, year, month)

    fun start(): LocalDate = LocalDate.of(year, month, 1)

    fun daysInMonth(): Int = YearMonth.of(year, month).lengthOfMonth()

    fun adding(n: Int): MonthKey {
        val t = year * 12 + (month - 1) + n
        return MonthKey(Math.floorDiv(t, 12), Math.floorMod(t, 12) + 1)
    }

    /** All months from this one through [end], inclusive. Empty if [end] is earlier. */
    fun monthsThrough(end: MonthKey): List<MonthKey> {
        if (this > end) return emptyList()
        val count = (end.year * 12 + end.month) - (year * 12 + month)
        return (0..count).map { adding(it) }
    }

    fun title(locale: Locale = Locale.getDefault()): String =
        start().format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))

    fun shortTitle(locale: Locale = Locale.getDefault()): String =
        start().format(DateTimeFormatter.ofPattern("LLL", locale))

    override fun compareTo(other: MonthKey): Int = compareValuesBy(this, other, { it.year }, { it.month })

    companion object {
        fun of(date: LocalDate) = MonthKey(date.year, date.monthValue)
        fun of(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()) =
            of(java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate())

        fun parse(text: String): MonthKey? {
            val p = text.split("-")
            if (p.size != 2) return null
            val y = p[0].toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            if (m !in 1..12 || y !in 1900..3000) return null
            return MonthKey(y, m)
        }
    }
}

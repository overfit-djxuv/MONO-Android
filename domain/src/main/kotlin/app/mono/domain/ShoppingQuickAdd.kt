package app.mono.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns one typed line into a shopping item: "milk 2 4.50" is milk, quantity 2, RM 4.50 each.
 * One trailing number is a price ("milk 4.50"); two are quantity then price. Anything else stays in the name.
 */
object ShoppingQuickAdd {
    data class Parsed(val name: String, val quantity: Int, val priceCents: Long)

    private val numeric = Regex("""^[+-]?(\d+([.,]\d*)?|[.,]\d+)([eE][+-]?\d+)?$""")

    /** "1,20" is one ringgit twenty in Spanish and Malay typing; "1,200" is a thousand. A comma followed by one or two digits is a decimal. */
    private fun normalised(token: String): String {
        if (token.contains('.') || token.count { it == ',' } != 1) return token
        val after = token.substringAfterLast(',')
        return if (after.length in 1..2) token.replace(',', '.') else token
    }

    fun parse(text: String, currency: Currency? = null): Parsed? {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        val numbers = ArrayList<String>()
        while (words.isNotEmpty() && numbers.size < 2 && words.last().any { it.isDigit() } && numeric.matches(words.last())) {
            numbers.add(0, words.removeAt(words.lastIndex))
        }
        val name = words.joinToString(" ").trim()
        if (name.isEmpty()) return null
        var qty = 1
        var price = 0L
        if (numbers.size == 2) {
            val q = numbers[0].toIntOrNull()?.takeIf { it in 1..999 } ?: return null
            price = Money.parse(normalised(numbers[1]), currency) ?: return null
            qty = q
        } else if (numbers.size == 1) {
            price = Money.parse(normalised(numbers[0]), currency) ?: return null
        }
        return Parsed(name, qty, maxOf(price, 0))
    }
}

/** Spending in a month split into its five calendar weeks (days 1-7, 8-14, 15-21, 22-28, and 29 to the end). */
object CategoryWeeks {
    fun spend(
        txs: List<app.mono.domain.model.TransactionRecord>, category: String, month: MonthKey, zone: ZoneId = ZoneId.systemDefault(),
    ): List<Long> {
        val weeks = LongArray(5)
        for (t in txs) if (t.kind == TxKind.EXPENSE && t.category == category) {
            val d = Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate()
            if (MonthKey.of(d) == month) weeks[minOf((d.dayOfMonth - 1) / 7, 4)] += t.amountCents
        }
        return weeks.toList()
    }

    /** Weeks that have not started yet are shown empty rather than as "no spending". */
    fun started(index: Int, month: MonthKey, now: LocalDate = LocalDate.now()): Boolean {
        val current = MonthKey.of(now)
        if (month < current) return true
        if (month > current) return false
        return now.dayOfMonth > index * 7
    }
}

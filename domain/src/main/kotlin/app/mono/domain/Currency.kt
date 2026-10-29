package app.mono.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** An ISO 4217 currency. Amounts are always whole minor units (cents, sen, yen...), never floating point. */
data class Currency(val code: String, val name: String, val symbol: String, val digits: Int) {
    val scale: Int get() = intArrayOf(1, 10, 100, 1000)[digits.coerceIn(0, 3)]

    companion object {
        val all = listOf(
            Currency("MYR", "Malaysian ringgit", "RM", 2),
            Currency("SGD", "Singapore dollar", "S$", 2),
            Currency("USD", "US dollar", "$", 2),
            Currency("EUR", "Euro", "€", 2),
            Currency("GBP", "British pound", "£", 2),
            Currency("JPY", "Japanese yen", "¥", 0),
            Currency("AUD", "Australian dollar", "A$", 2),
            Currency("NZD", "New Zealand dollar", "NZ$", 2),
            Currency("CAD", "Canadian dollar", "C$", 2),
            Currency("CHF", "Swiss franc", "CHF", 2),
            Currency("CNY", "Chinese yuan", "CN¥", 2),
            Currency("HKD", "Hong Kong dollar", "HK$", 2),
            Currency("TWD", "New Taiwan dollar", "NT$", 2),
            Currency("KRW", "South Korean won", "₩", 0),
            Currency("THB", "Thai baht", "฿", 2),
            Currency("IDR", "Indonesian rupiah", "Rp", 2),
            Currency("PHP", "Philippine peso", "₱", 2),
            Currency("VND", "Vietnamese dong", "₫", 0),
            Currency("INR", "Indian rupee", "₹", 2),
            Currency("BND", "Brunei dollar", "B$", 2),
            Currency("AED", "UAE dirham", "AED", 2),
            Currency("SAR", "Saudi riyal", "SAR", 2),
            Currency("KWD", "Kuwaiti dinar", "KD", 3),
        )

        val default = all[0]

        fun find(code: String): Currency {
            val up = code.uppercase()
            return all.firstOrNull { it.code == up } ?: Currency(up, up, up, 2)
        }
    }
}

/** A user-entered rate. Rounding is half away from zero, as on iOS. */
data class RateEntry(val from: String, val to: String, val micros: Long)

/**
 * Converts between currencies using rates the user typed in. It never guesses: a missing rate returns null.
 * A rate means "1 unit of `from` = `rate` units of `to`", held as an integer of millionths.
 */
class CurrencyConverter(val reporting: Currency, rates: List<RateEntry>) {
    private val toReporting: Map<String, Long>

    init {
        val map = HashMap<String, Long>()
        for (r in rates) if (r.micros > 0 && r.to == reporting.code) map[r.from] = r.micros
        // A rate saved the other way round (reporting -> X) is inverted, so changing the reporting currency keeps working.
        for (r in rates) if (r.micros > 0 && r.from == reporting.code && map[r.to] == null) {
            val inv = BigDecimal(RATE_SCALE).multiply(BigDecimal(RATE_SCALE)).divide(BigDecimal(r.micros), 0, RoundingMode.HALF_UP)
            map[r.to] = inv.toLong()
        }
        toReporting = map
    }

    fun hasRate(currency: Currency) = currency.code == reporting.code || toReporting.containsKey(currency.code)

    fun micros(currency: Currency): Long? = if (currency.code == reporting.code) RATE_SCALE else toReporting[currency.code]

    /** [minor] units of [from], expressed in the reporting currency's minor units. */
    fun convert(minor: Long, from: Currency): Long? {
        if (from.code == reporting.code) return minor
        val micros = toReporting[from.code] ?: return null
        return convert(minor, from, reporting, micros)
    }

    companion object {
        const val RATE_SCALE = 1_000_000L
        private val mc = MathContext.DECIMAL128

        /** Currency-correct conversion of minor units with an explicit rate (micros = rate x 1,000,000). */
        fun convert(minor: Long, from: Currency, to: Currency, micros: Long): Long {
            val major = BigDecimal(minor).divide(BigDecimal(from.scale), mc)
            val target = major.multiply(BigDecimal(micros)).divide(BigDecimal(RATE_SCALE), mc).multiply(BigDecimal(to.scale))
            return target.setScale(0, RoundingMode.HALF_UP).toLong()
        }

        /** The implied rate (millionths) of a transfer where [fromMinor] of one currency became [toMinor] of another. */
        fun impliedMicros(fromMinor: Long, from: Currency, toMinor: Long, to: Currency): Long? {
            if (fromMinor <= 0 || toMinor <= 0) return null
            val fromMajor = BigDecimal(fromMinor).divide(BigDecimal(from.scale), mc)
            val toMajor = BigDecimal(toMinor).divide(BigDecimal(to.scale), mc)
            return toMajor.divide(fromMajor, mc).multiply(BigDecimal(RATE_SCALE)).setScale(0, RoundingMode.HALF_UP).toLong()
        }

        fun parseRate(text: String): Long? {
            val t = text.trim().replace(",", ".")
            if (t.isEmpty()) return null
            val d = t.toBigDecimalOrNull() ?: return null
            if (d.signum() <= 0 || d >= BigDecimal(1_000_000)) return null
            val micros = d.multiply(BigDecimal(RATE_SCALE)).setScale(0, RoundingMode.HALF_UP).toLong()
            return if (micros > 0) micros else null
        }

        fun formatRate(micros: Long): String =
            BigDecimal(micros).movePointLeft(6).stripTrailingZeros().toPlainString()
    }
}

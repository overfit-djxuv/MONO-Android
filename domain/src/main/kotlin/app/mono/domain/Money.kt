package app.mono.domain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the global money helpers need to know. The app keeps this in step with its settings
 * (reporting currency, symbol override, Hide amounts), exactly like iOS's UserDefaults + PrivacyStore.
 */
data class MoneyContext(
    val reporting: Currency = Currency.default,
    /** A user-typed symbol for the reporting currency; blank means the currency's own symbol. */
    val symbolOverride: String = "",
    val hidden: Boolean = false,
)

/**
 * All amounts in MONO are integer minor units (cents, sen, yen...) so sums are exact.
 * Every on-screen amount goes through [format], so Hide amounts masks it everywhere.
 */
object Money {
    const val MAX_CENTS = 99_999_999_999L // 999,999,999.99
    const val MASK = "••••"
    private const val MINUS = "−" // U+2212, same glyph as iOS

    @Volatile
    var context: MoneyContext = MoneyContext()

    val reporting: Currency get() = context.reporting

    val symbol: String get() = context.symbolOverride.trim().ifEmpty { reporting.symbol }

    private fun group(n: Long): String = String.format(Locale.US, "%,d", n)

    /** "RM 1,234.50", negatives as "−RM 1,234.50". [ignoreHiding] is only for files you explicitly export. */
    fun format(
        cents: Long,
        signed: Boolean = false,
        symbol: String? = null,
        currency: Currency? = null,
        ignoreHiding: Boolean = false,
    ): String {
        val cur = currency ?: reporting
        val sym = symbol ?: if (currency == null) this.symbol else cur.symbol
        val a = abs(cents)
        // Hide amounts: the figure becomes dots but the symbol and the sign stay, so income still reads as income.
        if (context.hidden && !ignoreHiding) {
            val masked = "$sym $MASK"
            if (cents < 0) return MINUS + masked
            return if (signed && cents > 0) "+$masked" else masked
        }
        val w = group(a / cur.scale)
        val body = if (cur.digits == 0) "$sym $w"
        else "$sym $w.${(a % cur.scale).toString().padStart(cur.digits, '0')}"
        if (cents < 0) return MINUS + body
        return if (signed && cents > 0) "+$body" else body
    }

    /** Whole-unit rendering for tight spaces: "RM 1,235". */
    fun formatWhole(cents: Long): String {
        if (context.hidden) return (if (cents < 0) MINUS else "") + "$symbol $MASK"
        val rounded = (abs(cents).toDouble() / reporting.scale).roundToLong() // half away from zero on a positive value
        return (if (cents < 0) MINUS else "") + "$symbol ${group(rounded)}"
    }

    /** Chart-axis style: 1.2k, 3.4M. */
    fun compact(value: Double): String {
        if (context.hidden) return "•••"
        val a = abs(value)
        val sign = if (value < 0) MINUS else ""
        return when {
            a >= 1_000_000 -> sign + trim(a / 1_000_000) + "M"
            a >= 1_000 -> sign + trim(a / 1_000) + "k"
            else -> sign + a.roundToLong().toString()
        }
    }

    private fun trim(v: Double): String {
        val s = String.format(Locale.US, "%.1f", v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /**
     * Parses user input such as "12", "12.5", "1,200.00", "RM 40". Returns null for anything ambiguous,
     * including more decimal places than the currency has (2 for ringgit, 0 for yen).
     */
    fun parse(text: String, currency: Currency? = null): Long? {
        val cur = currency ?: reporting
        val sym = if (currency == null) symbol else cur.symbol
        var s = text.trim().replace(",", "").replace(" ", "")
        if (s.lowercase().startsWith(sym.lowercase())) s = s.drop(sym.length)
        if (s.isEmpty()) return null
        val parts = s.split(".")
        if (parts.size > 2) return null
        val intPart = parts[0]
        val fracPart = if (parts.size == 2) parts[1] else ""
        fun digits(x: String) = x.all { it in '0'..'9' }
        if (!digits(intPart) || !digits(fracPart) || fracPart.length > cur.digits || intPart.length > 11) return null
        if (intPart.isEmpty() && fracPart.isEmpty()) return null
        val w = if (intPart.isEmpty()) 0L else intPart.toLong()
        val f = if (cur.digits == 0) 0L else (fracPart + "0".repeat(cur.digits)).take(cur.digits).toLong()
        val total = w * cur.scale + f
        return if (total <= MAX_CENTS) total else null
    }

    /** Plain editable text for a minor-unit value ("12.50"), used to pre-fill forms. */
    fun editable(cents: Long, currency: Currency? = null): String {
        val cur = currency ?: reporting
        if (cents == 0L) return ""
        return if (cur.digits == 0) cents.toString()
        else "${cents / cur.scale}.${(cents % cur.scale).toString().padStart(cur.digits, '0')}"
    }
}

fun ceilDiv(a: Long, b: Long): Long = if (b <= 0) 0 else (a + b - 1) / b

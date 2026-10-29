package app.mono.domain

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate

data class ReceiptItem(val name: String, val cents: Long)

/** What could be read from a receipt's text. [uncertain] names the fields to double-check; nothing here is trusted blindly. */
data class ParsedReceipt(
    val merchant: String = "",
    val date: LocalDate? = null,
    val totalCents: Long? = null,
    val taxCents: Long? = null,
    val currencyCode: String? = null,
    val items: List<ReceiptItem> = emptyList(),
    val uncertain: Set<Field> = emptySet(),
) {
    enum class Field { MERCHANT, DATE, TOTAL, TAX, CURRENCY }
}

/** Reads receipt text in English, Spanish and Malay (and Indonesian words that overlap with Malay). Pure and deterministic. */
object ReceiptParser {
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    private val amountRegex = Regex("""(?<![\d.,])\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{1,2})?(?!\d)|(?<![\d.,])\d+(?:[.,]\d{1,2})?(?!\d)""")

    data class Amount(val cents: Long, val range: IntRange)

    /** Turns "1,234.50", "1.234,50", "12,5" or "25.000" into minor units. A separator followed by exactly three digits is a thousands mark. */
    fun parseAmount(token: String, digits: Int = 2): Long? {
        if (token.none { it.isDigit() }) return null
        val last = token.indexOfLast { it == '.' || it == ',' }
        val whole: String
        var frac = ""
        if (last >= 0) {
            val after = token.substring(last + 1).filter { it.isDigit() }
            val before = token.substring(0, last).filter { it.isDigit() }
            if (after.length == 3 && digits != 3) whole = before + after else { whole = before; frac = after }
        } else whole = token.filter { it.isDigit() }
        if (whole.length >= 12) return null
        val w = (whole.ifEmpty { "0" }).toLongOrNull() ?: return null
        val f = frac.padEnd(digits, '0').take(digits)
        var scale = 1L
        repeat(digits) { scale *= 10 }
        return w * scale + (f.toLongOrNull() ?: 0L)
    }

    /** Amounts on a line, ignoring percentages ("6%") and long digit runs (phone numbers, invoice numbers). */
    fun amounts(line: String, digits: Int = 2): List<Amount> = amountRegex.findAll(line).mapNotNull { m ->
        val token = m.value
        val after = line.substring(m.range.last + 1).dropWhile { it == ' ' }
        if (after.firstOrNull() == '%') return@mapNotNull null
        if (token.length > 10 && token.none { it == '.' || it == ',' }) return@mapNotNull null
        // A bare integer with no decimals is rarely a price on a receipt line (quantities, table numbers, years).
        if (token.none { it == '.' || it == ',' }) return@mapNotNull null
        parseAmount(token, digits)?.let { Amount(it, m.range) }
    }.toList()

    private val totalKeys = listOf("grand total", "total amount", "amount due", "balance due", "total due", "total a pagar", "importe total", "a pagar",
        "jumlah besar", "jumlah bayaran", "jumlah akhir", "total bayar", "net total", "nett total", "jumlah", "amaun", "total")
    private val notTotal = listOf("subtotal", "sub total", "sub-total", "jumlah kecil", "total item", "total qty", "total discount", "diskaun", "discount",
        "descuento", "tax", "cukai", "iva", "impuesto", "sst", "gst", "vat", "ppn", "change", "baki", "cambio", "tunai", "cash", "efectivo",
        "rounding", "pembundaran", "redondeo", "points", "mata", "saving")
    private val strongTotals = listOf("grand total", "total amount", "amount due", "balance due", "total due", "jumlah besar", "jumlah bayaran", "importe total", "total a pagar")
    private val taxKeys = listOf("sst", "gst", "vat", "tax", "iva", "impuesto", "cukai", "ppn", "pajak")
    private val skipItem = listOf("total", "subtotal", "sub total", "tax", "cukai", "iva", "sst", "gst", "vat", "change", "baki", "cambio", "cash", "tunai",
        "efectivo", "rounding", "pembundaran", "redondeo", "discount", "diskaun", "descuento", "amount", "jumlah", "importe",
        "visa", "master", "debit", "credit", "card", "tarjeta", "kad", "paid", "bayar", "pagado", "balance", "tel", "invoice",
        "receipt", "resit", "recibo", "factura", "date", "tarikh", "fecha")

    private fun has(folded: String, keys: List<String>) = keys.any { folded.contains(it) }
    private fun hasWord(folded: String, keys: List<String>): Boolean {
        val words = folded.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }.toSet()
        return keys.any { it in words }
    }

    private val months: Map<String, Int> = buildMap {
        val names = listOf(
            listOf("jan", "january", "ene", "enero", "januari"), listOf("feb", "february", "febrero", "februari", "pebruari"), listOf("mar", "march", "marzo", "mac", "maret"),
            listOf("apr", "april", "abr", "abril"), listOf("may", "mayo", "mei"), listOf("jun", "june", "junio", "juni"), listOf("jul", "july", "julio", "julai", "juli"),
            listOf("aug", "august", "ago", "agosto", "ogos", "agu", "agustus"), listOf("sep", "sept", "september", "septiembre", "setiembre"),
            listOf("oct", "october", "octubre", "oktober", "okt"), listOf("nov", "november", "noviembre"), listOf("dec", "december", "dic", "diciembre", "disember", "des", "desember"),
        )
        names.forEachIndexed { i, list -> list.forEach { put(it, i + 1) } }
    }

    private fun make(y: Int, mo: Int, d: Int, now: LocalDate): LocalDate? {
        val year = if (y < 100) 2000 + y else y
        if (year !in 2000..3000 || mo !in 1..12 || d !in 1..31) return null
        val date = try { LocalDate.of(year, mo, d) } catch (e: DateTimeException) { return null }
        return date.takeIf { it.isBefore(now.plusDays(2)) } // receipts aren't from the future
    }

    private val ci = RegexOption.IGNORE_CASE
    private val isoDate = Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)""", ci)
    private val numDate = Regex("""(?<![\d.])(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})(?!\d)""", ci)
    private val textDayFirst = Regex("""(?<!\d)(\d{1,2})(?:st|nd|rd|th)?[\s.\-]*(?:de\s+)?(\p{L}{3,10})\.?,?[\s\-]*(?:de\s+|del\s+)?(\d{4}|\d{2})(?!\d)""", ci)
    private val textMonthFirst = Regex("""(\p{L}{3,10})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\d)""", ci)

    data class FoundDate(val date: LocalDate, val ambiguous: Boolean)

    /** First plausible date in the text. Numeric dates are read day-first (as in Malaysia and Spain); when that is a guess, `ambiguous` is true. */
    fun findDate(lines: List<String>, now: LocalDate = LocalDate.now()): FoundDate? {
        for (line in lines) {
            isoDate.find(line)?.groupValues?.let { g -> make(g[1].toInt(), g[2].toInt(), g[3].toInt(), now)?.let { return FoundDate(it, false) } }
            numDate.find(line)?.groupValues?.let { g ->
                val a = g[1].toInt(); val b = g[2].toInt(); val y = g[3].toInt()
                make(y, b, a, now)?.let { return FoundDate(it, a <= 12 && b <= 12 && a != b) }
                make(y, a, b, now)?.let { return FoundDate(it, false) } // second number > 12: must be month-first
            }
            textDayFirst.find(line)?.groupValues?.let { g ->
                val mo = months[fold(g[2])]
                if (mo != null) make(g[3].toInt(), mo, g[1].toInt(), now)?.let { return FoundDate(it, false) }
            }
            textMonthFirst.find(line)?.groupValues?.let { g ->
                val mo = months[fold(g[1])]
                if (mo != null) make(g[3].toInt(), mo, g[2].toInt(), now)?.let { return FoundDate(it, false) }
            }
        }
        return null
    }

    private val currencyChecks = listOf(
        Regex("""\bRM\s?\d|\bMYR\b""", ci) to "MYR", Regex("""S\$\s?\d|\bSGD\b""", ci) to "SGD", Regex("""€|\bEUR\b""", ci) to "EUR",
        Regex("""\bRp\.?\s?\d|\bIDR\b""", ci) to "IDR", Regex("""£|\bGBP\b""", ci) to "GBP", Regex("""\bUSD\b|US\$""", ci) to "USD", Regex("""\bTHB\b|฿""", ci) to "THB",
    )

    /** A bare "$" could be several currencies: never guessed. */
    fun findCurrency(text: String): String? = currencyChecks.firstOrNull { it.first.containsMatchIn(text) }?.second

    private val headerSkip = listOf("tel", "phone", "fax", "date", "time", "receipt", "invoice", "resit", "recibo", "factura", "gst", "sst", "reg", "www", "http", "table", "cashier", "kasir", "tarikh", "fecha", "no.", "order")

    fun parse(rawLines: List<String>, digits: Int = 2, now: LocalDate = LocalDate.now()): ParsedReceipt {
        val lines = rawLines.map { it.trim() }.filter { it.isNotEmpty() }
        val folded = lines.map(::fold)
        val uncertain = linkedSetOf<ParsedReceipt.Field>()

        var merchant = ""
        for ((i, line) in lines.take(8).withIndex()) {
            val f = folded[i]
            val letters = line.count { it.isLetter() }; val digitsN = line.count { it.isDigit() }
            if (letters < 3 || letters <= digitsN * 2 || headerSkip.any { f.startsWith(it) }) continue
            merchant = line
            if (i > 2) uncertain += ParsedReceipt.Field.MERCHANT
            break
        }
        if (merchant.isEmpty()) uncertain += ParsedReceipt.Field.MERCHANT

        val found = findDate(lines, now)
        if (found == null || found.ambiguous) uncertain += ParsedReceipt.Field.DATE

        val currency = findCurrency(lines.joinToString("\n"))

        fun amountsNear(i: Int): List<Amount> {
            val own = amounts(lines[i], digits)
            if (own.isNotEmpty()) return own
            return if (i + 1 < lines.size) amounts(lines[i + 1], digits) else emptyList()
        }
        var bestRank = -1; var bestCents = 0L; var haveBest = false
        for ((i, f) in folded.withIndex()) {
            if (has(f, notTotal) && !has(f, strongTotals)) continue
            val rank = totalKeys.indexOfFirst { f.contains(it) }
            if (rank < 0) continue
            val amount = amountsNear(i).lastOrNull() ?: continue
            val r = totalKeys.size - rank
            if (!haveBest || r > bestRank || (r == bestRank && amount.cents > bestCents)) { haveBest = true; bestRank = r; bestCents = amount.cents }
        }
        var total: Long? = null
        if (haveBest) total = bestCents
        else {
            // No "total" line: the biggest amount that isn't tax, change or cash is the likeliest total, but it's only a guess.
            total = lines.indices.filter { !has(folded[it], notTotal) }.flatMap { idx -> amounts(lines[idx], digits).map { it.cents } }.maxOrNull()
            uncertain += ParsedReceipt.Field.TOTAL
        }
        if (total == null) uncertain += ParsedReceipt.Field.TOTAL

        var tax: Long? = null
        for ((i, f) in folded.withIndex()) {
            if (!hasWord(f, taxKeys) || f.contains("subtotal")) continue
            val a = amounts(lines[i], digits).lastOrNull() ?: continue
            if (a.cents > 0 && a.cents < (total ?: Long.MAX_VALUE)) { tax = a.cents; break }
        }

        val items = ArrayList<ReceiptItem>()
        for ((i, line) in lines.withIndex()) {
            val f = folded[i]
            if (items.size >= 60 || hasWord(f, skipItem) || has(f, listOf("sub total", "grand total", "jumlah", "importe"))) continue
            val a = amounts(line, digits).lastOrNull() ?: continue
            if (a.cents <= 0) continue
            var name = line.substring(0, a.range.first)
            for (s in listOf("RM", "MYR", "€", "$", "Rp", "EUR")) name = name.replace(s, "")
            name = name.replace(Regex("""^\s*\d+\s*[xX×]?\s+"""), "").trim { it in " .:-*\t" }
            if (name.count { it.isLetter() } < 2 || a.cents == total) continue
            items += ReceiptItem(name, a.cents)
        }
        return ParsedReceipt(merchant, found?.date, total, tax, currency, items, uncertain)
    }
}

/** Finds receipts and transactions that probably describe the same purchase. */
object ReceiptMatcher {
    data class Existing(val id: String, val merchant: String, val date: LocalDate, val totalCents: Long, val hash: String)
    data class New(val merchant: String, val date: LocalDate, val totalCents: Long, val hash: String)

    fun duplicate(new: New, existing: List<Existing>): Existing? = existing.firstOrNull { e ->
        if (new.hash.isNotEmpty() && e.hash == new.hash) return@firstOrNull true
        if (new.totalCents <= 0 || e.totalCents != new.totalCents || e.date != new.date) return@firstOrNull false
        val a = ReceiptParser.fold(new.merchant); val b = ReceiptParser.fold(e.merchant)
        a.isEmpty() || b.isEmpty() || a == b
    }

    /** Expenses worth offering when attaching a receipt: same amount first, then the closest dates. */
    fun candidates(
        totalCents: Long, date: LocalDate, transactions: List<app.mono.domain.model.TransactionRecord>, limit: Int = 12,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): List<app.mono.domain.model.TransactionRecord> {
        fun gap(t: app.mono.domain.model.TransactionRecord) =
            kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate(), date))
        return transactions.filter { it.kind == TxKind.EXPENSE }
            .sortedWith(compareByDescending<app.mono.domain.model.TransactionRecord> { it.amountCents == totalCents }.thenBy { gap(it) })
            .filter { it.amountCents == totalCents || gap(it) <= 7 }.take(limit)
    }
}

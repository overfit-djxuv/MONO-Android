package app.mono.domain

import app.mono.domain.model.MoneyCategory
import app.mono.domain.model.TransactionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.abs

/** Reads bank-statement style CSV files into transactions. Pure logic; importing only ever adds records, it never edits or deletes. */
object CsvImport {
    const val MAX_ROWS = 20_000
    const val MAX_BYTES = 5_000_000

    class ImportException(message: String) : Exception(message)

    // Parsing

    /** UTF-8 (with or without a BOM), falling back to Latin-1 for older bank exports. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw ImportException("The file is larger than 5 MB.")
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val text = try { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (e: Exception) { String(bytes, Charsets.ISO_8859_1) }
        return text.removePrefix("﻿")
    }

    fun detectDelimiter(text: String): Char {
        val counts = linkedMapOf(',' to 0, ';' to 0, '\t' to 0)
        var inQuotes = false
        for (c in text) {
            if (c == '"') inQuotes = !inQuotes
            else if (!inQuotes) {
                if (c == '\n' || c == '\r') break
                counts[c]?.let { counts[c] = it + 1 }
            }
        }
        val best = counts.entries.maxByOrNull { it.value }
        return if (best != null && best.value > 0) best.key else ','
    }

    /** RFC 4180: quoted fields, doubled quotes, newlines inside quotes. Fully blank lines are dropped. */
    fun parse(text: String, delimiter: Char? = null): List<List<String>> {
        val d = delimiter ?: detectDelimiter(text)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var justClosed = false
        fun endField() { row.add(field.toString()); field.setLength(0); justClosed = false }
        fun endRow() {
            endField()
            if (!(row.size == 1 && row[0].isBlank())) rows.add(row)
            row = ArrayList()
        }
        for (c in text) {
            if (inQuotes) { if (c == '"') { inQuotes = false; justClosed = true } else field.append(c); continue }
            when {
                c == '"' -> if (justClosed) { field.append('"'); inQuotes = true; justClosed = false } else if (field.isEmpty()) inQuotes = true else field.append(c)
                c == d -> endField()
                c == '\n' || c == '\r' -> { endRow(); if (rows.size > MAX_ROWS + 1) throw ImportException("The file has more than $MAX_ROWS rows. Split it and import in parts.") }
                else -> { field.append(c); justClosed = false }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        if (rows.isEmpty()) throw ImportException("The file has no rows.")
        return rows
    }

    // Amounts

    /** Signed cents from text such as "1,234.50", "-12.00", "(12.00)", "RM 40", "1.234,50". null if not a clean amount. */
    fun parseAmount(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val negative = (t.contains('(') && t.contains(')')) || t.startsWith("-") || t.startsWith("−") || t.endsWith("-") || t.endsWith("−")
        var s = t.filter { it in '0'..'9' || it == '.' || it == ',' }
        if (s.none { it in '0'..'9' }) return null
        val dots = s.count { it == '.' }
        val commas = s.count { it == ',' }
        if (dots > 0 && commas > 0) {
            // The separator that comes last is the decimal point.
            val decimal = if (s.lastIndexOf('.') > s.lastIndexOf(',')) '.' else ','
            val thousands = if (decimal == '.') ',' else '.'
            s = s.filter { it != thousands }
            if (decimal == ',') s = s.replace(',', '.')
        } else if (commas > 0) {
            val after = s.substringAfterLast(',').length
            s = if (commas == 1 && after in 1..2) s.replace(',', '.') else s.replace(",", "")
        } else if (dots > 1) {
            s = s.replace(".", "")
        }
        val cents = Money.parse(s) ?: return null
        return if (negative) -cents else cents
    }

    // Dates

    enum class DateFormat(val label: String, val pattern: String) {
        ISO("2026-03-31", "uuuu-M-d"), DMY("31/03/2026", "d/M/uuuu"), MDY("03/31/2026", "M/d/uuuu"), DMONY("31 Mar 2026", "d MMM uuuu");
    }

    fun parseDate(text: String, format: DateFormat): LocalDate? {
        var s = text.trim()
        if (s.isEmpty()) return null
        when (format) {
            DateFormat.ISO, DateFormat.DMY, DateFormat.MDY -> {
                s.indexOfFirst { it == ' ' || it == 'T' }.takeIf { it >= 0 }?.let { s = s.substring(0, it) } // drop a time of day
                val sep = if (format == DateFormat.ISO) "-" else "/"
                s = s.replace(".", sep).replace("-", sep)
                if (format == DateFormat.ISO) s = s.replace("/", "-")
            }
            DateFormat.DMONY -> {
                s = s.replace("-", " ").replace(",", " ")
                s = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(3).joinToString(" ")
            }
        }
        val formatter = DateTimeFormatter.ofPattern(format.pattern, Locale.US).withResolverStyle(ResolverStyle.STRICT)
        val day = try { LocalDate.parse(s, formatter) } catch (e: Exception) { return null }
        return day.takeIf { it.year in 1990..2100 }
    }

    /** The format that reads the most sample cells. Ties go to ISO, then day-first, then month-first. */
    fun detectDateFormat(samples: List<String>): DateFormat {
        val cells = samples.filter { it.isNotBlank() }.take(200)
        val scored = DateFormat.entries.map { f -> f to cells.count { parseDate(it, f) != null } }
        return scored.fold(scored.first()) { best, x -> if (x.second > best.second) x else best }.first
    }

    // Mapping

    enum class AmountMode(val label: String) {
        SIGNED("One amount column (minus = expense)"), DEBIT_CREDIT("Separate debit and credit columns"), TYPE_COLUMN("Amount plus a type column")
    }

    data class Mapping(
        val hasHeader: Boolean = true, val date: Int? = null, val title: Int? = null, val amount: Int? = null, val debit: Int? = null,
        val credit: Int? = null, val type: Int? = null, val category: Int? = null, val notes: Int? = null,
        val mode: AmountMode = AmountMode.SIGNED, val dateFormat: DateFormat = DateFormat.ISO,
        /** For statements where charges are positive (some credit-card exports). */
        val invertSigns: Boolean = false,
    )

    fun looksLikeHeader(row: List<String>): Boolean = row.none { cell -> DateFormat.entries.any { parseDate(cell, it) != null } }

    fun autoMapping(records: List<List<String>>): Mapping {
        val first = records.firstOrNull() ?: return Mapping()
        var m = Mapping(hasHeader = looksLikeHeader(first))
        val names = first.map { it.lowercase().trim() }
        fun find(keys: List<String>, excluding: Set<Int> = emptySet()): Int? = names.indices.firstOrNull { i -> i !in excluding && keys.any { names[i].contains(it) } }
        if (m.hasHeader) {
            val date = find(listOf("date", "posted"))
            val debit = find(listOf("debit", "withdrawal", "paid out", "money out"))
            val credit = find(listOf("credit", "deposit", "paid in", "money in"))
            val type = find(listOf("type"))
            val amount = find(listOf("amount", "value"), setOfNotNull(debit, credit))
            val category = find(listOf("category"))
            val title = find(listOf("description", "details", "payee", "merchant", "narration", "memo", "title", "name"), setOfNotNull(date, category))
            val notes = find(listOf("notes", "note", "reference", "ref"), setOfNotNull(title))
            m = m.copy(date = date, debit = debit, credit = credit, type = type, amount = amount, category = category, title = title, notes = notes)
            if (debit != null && credit != null) m = m.copy(mode = AmountMode.DEBIT_CREDIT)
            else if (type != null && amount != null) m = m.copy(mode = AmountMode.TYPE_COLUMN)
        } else if (first.size >= 3) m = m.copy(date = 0, title = 1, amount = 2)
        val body = records.drop(if (m.hasHeader) 1 else 0)
        m.date?.let { d -> m = m.copy(dateFormat = detectDateFormat(body.mapNotNull { it.getOrNull(d) })) }
        return m
    }

    // Planning

    data class Row(val line: Int, val kind: TxKind, val cents: Long, val date: LocalDate, val title: String, val category: String, val notes: String)
    data class Skipped(val line: Int, val reason: String)
    data class NewCategory(val name: String, val kind: TxKind)
    data class Plan(val rows: List<Row> = emptyList(), val skipped: List<Skipped> = emptyList(), val duplicates: Int = 0, val newCategories: List<NewCategory> = emptyList()) {
        val income: Long get() = rows.filter { it.kind == TxKind.INCOME }.sumOf { it.cents }
        val expense: Long get() = rows.filter { it.kind == TxKind.EXPENSE }.sumOf { it.cents }
    }

    private data class Key(val day: LocalDate, val kind: TxKind, val cents: Long, val title: String)

    /**
     * Maps rows to transactions without touching the database. Rows that match an existing record (same day, type, amount and
     * description) are counted as duplicates and left out, once per existing record, so importing the same file twice adds nothing.
     */
    fun plan(
        records: List<List<String>>, m: Mapping, existing: List<TransactionRecord>, categories: List<MoneyCategory>,
        createMissingCategories: Boolean, zone: ZoneId = ZoneId.systemDefault(),
    ): Plan {
        val rows = ArrayList<Row>()
        val skipped = ArrayList<Skipped>()
        val newCategories = ArrayList<NewCategory>()
        var duplicates = 0
        val seen = HashMap<Key, Int>()
        for (t in existing) {
            val k = Key(Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate(), t.kind, t.amountCents, t.title.lowercase())
            seen[k] = (seen[k] ?: 0) + 1
        }
        val known = categories.groupBy { it.kind }
        val added = HashMap<String, String>() // "kind|lowercased" -> name, for categories created by this import

        fun cell(r: List<String>, i: Int?): String = if (i == null) "" else r.getOrNull(i)?.trim() ?: ""
        fun clean(s: String, limit: Int): String {
            var v = s
            // Undo the apostrophe MONO's own export adds in front of formula-like text.
            if (v.length > 1 && v.startsWith("'") && v[1] in "=+-@") v = v.substring(1)
            return v.take(limit)
        }

        for ((offset, r) in records.withIndex().drop(if (m.hasHeader) 1 else 0)) {
            val line = offset + 1
            if (r.all { it.isBlank() }) continue
            fun skip(why: String) { skipped += Skipped(line, why) }
            val date = parseDate(cell(r, m.date), m.dateFormat) ?: run { skip("date not understood"); null } ?: continue
            val kind: TxKind
            val cents: Long
            when (m.mode) {
                AmountMode.SIGNED -> {
                    val v = parseAmount(cell(r, m.amount)) ?: run { skip("amount not understood"); null } ?: continue
                    if (v == 0L) { skip("amount is zero"); continue }
                    kind = if ((v < 0) != m.invertSigns) TxKind.EXPENSE else TxKind.INCOME
                    cents = abs(v)
                }
                AmountMode.DEBIT_CREDIT -> {
                    val d = abs(parseAmount(cell(r, m.debit)) ?: 0)
                    val c = abs(parseAmount(cell(r, m.credit)) ?: 0)
                    if (d > 0 && c > 0) { skip("has both a debit and a credit"); continue }
                    if (d == 0L && c == 0L) { skip("no amount"); continue }
                    kind = if (d > 0) TxKind.EXPENSE else TxKind.INCOME
                    cents = if (d > 0) d else c
                }
                AmountMode.TYPE_COLUMN -> {
                    val v = parseAmount(cell(r, m.amount))
                    if (v == null || v == 0L) { skip("amount not understood"); continue }
                    val t = cell(r, m.type).lowercase()
                    kind = when {
                        t in listOf("income", "credit", "deposit", "in", "cr") -> TxKind.INCOME
                        t in listOf("expense", "debit", "withdrawal", "out", "dr") -> TxKind.EXPENSE
                        else -> { skip("type “${cell(r, m.type)}” not understood"); continue }
                    }
                    cents = abs(v)
                }
            }
            if (cents > Money.MAX_CENTS) { skip("amount too large"); continue }
            val title = clean(cell(r, m.title), 200)
            val wanted = cell(r, m.category)
            val category = known[kind]?.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.name
                ?: added["${kind.raw}|${wanted.lowercase()}"]
                ?: if (createMissingCategories && wanted.isNotEmpty()) {
                    clean(wanted, 40).also { added["${kind.raw}|${wanted.lowercase()}"] = it; newCategories += NewCategory(it, kind) }
                } else if (kind == TxKind.INCOME) "Other Income" else "Other"
            val key = Key(date, kind, cents, title.lowercase())
            val n = seen[key] ?: 0
            if (n > 0) { seen[key] = n - 1; duplicates++; continue }
            rows += Row(line, kind, cents, date, title, category, clean(cell(r, m.notes), 500))
        }
        return Plan(rows, skipped, duplicates, newCategories)
    }
}

/** MONO's own CSV export (the same columns the iOS app writes), with spreadsheet-formula protection. */
object CsvExport {
    /** RFC 4180 quoting, plus a leading apostrophe on formula-like text so spreadsheets don't execute it. */
    fun cell(s: String): String {
        var v = s
        if (v.isNotEmpty() && v[0] in "=+-@\t\r") v = "'$v"
        if (v.any { it in ",\"\r\n" }) v = "\"" + v.replace("\"", "\"\"") + "\""
        return v
    }

    fun transactions(txs: List<TransactionRecord>, zone: ZoneId = ZoneId.systemDefault()): String {
        val lines = arrayListOf("date,type,category,description,amount,notes")
        for (t in txs.sortedBy { it.date }) {
            val day = Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate().toString()
            val amount = "%d.%02d".format(t.amountCents / 100, t.amountCents % 100)
            lines += listOf(day, t.kindRaw, cell(t.category), cell(t.title), amount, cell(t.notes)).joinToString(",")
        }
        return lines.joinToString("\r\n") + "\r\n"
    }
}

package app.mono.domain

import app.mono.domain.model.MoneyCategory
import app.mono.domain.model.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/** Port of the CSV tests in NewFeatureTests.swift. */
class CsvImportTest {
    private val cats = listOf(MoneyCategory(name = "Food", kindRaw = "expense"), MoneyCategory(name = "Salary", kindRaw = "income"))

    private fun records(text: String) = CsvImport.parse(text)

    private fun plan(
        text: String, tweak: (CsvImport.Mapping) -> CsvImport.Mapping = { it }, existing: List<TransactionRecord> = emptyList(), create: Boolean = false,
    ): CsvImport.Plan {
        val r = records(text)
        return CsvImport.plan(r, tweak(CsvImport.autoMapping(r)), existing, cats, create, utc)
    }

    @Test fun quotesCommasAndNewlinesInsideFields() {
        assertEquals(listOf(listOf("a", "b"), listOf("x, y", "say \"hi\""), listOf("line1\nline2", "z")), records("a,b\r\n\"x, y\",\"say \"\"hi\"\"\"\r\n\"line1\nline2\",z\r\n"))
    }

    @Test fun semicolonAndBom() {
        val text = CsvImport.decode("﻿date;amount\n2026-01-02;5,50\n".toByteArray())
        assertEquals(listOf(listOf("date", "amount"), listOf("2026-01-02", "5,50")), records(text))
    }

    @Test fun latin1FilesFromOlderBanksAreReadToo() {
        assertEquals("Café", CsvImport.decode("Café".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test fun blankLinesDroppedAndEmptyFileRejected() {
        assertEquals(2, records("a\n\n\nb\n").size)
        try { records(""); fail() } catch (e: CsvImport.ImportException) { /* refused */ }
        try { CsvImport.decode(ByteArray(CsvImport.MAX_BYTES + 1)); fail() } catch (e: CsvImport.ImportException) { assertTrue(e.message!!.contains("5 MB")) }
    }

    @Test fun amountFormats() {
        assertEquals(123_450L, CsvImport.parseAmount("1,234.50")); assertEquals(-1_200L, CsvImport.parseAmount("-12.00"))
        assertEquals(-1_200L, CsvImport.parseAmount("(12.00)")); assertEquals(4_000L, CsvImport.parseAmount("RM 40"))
        assertEquals(123_450L, CsvImport.parseAmount("1.234,50")); assertEquals(1_250L, CsvImport.parseAmount("12,50"))
        assertEquals(123_400L, CsvImport.parseAmount("1,234")); assertEquals(123_456_700L, CsvImport.parseAmount("1.234.567"))
        assertNull(CsvImport.parseAmount("abc")); assertNull(CsvImport.parseAmount("")); assertNull(CsvImport.parseAmount("12.345"))
    }

    @Test fun dateFormatsAndDetection() {
        assertNotNull(CsvImport.parseDate("2026-03-31", CsvImport.DateFormat.ISO)); assertNotNull(CsvImport.parseDate("31/03/2026", CsvImport.DateFormat.DMY))
        assertNull(CsvImport.parseDate("31/03/2026", CsvImport.DateFormat.MDY)); assertNotNull(CsvImport.parseDate("31 Mar 2026", CsvImport.DateFormat.DMONY))
        assertNotNull(CsvImport.parseDate("2026-03-31T14:00:00", CsvImport.DateFormat.ISO)); assertNull(CsvImport.parseDate("2026-02-31", CsvImport.DateFormat.ISO))
        assertEquals(CsvImport.DateFormat.DMY, CsvImport.detectDateFormat(listOf("31/03/2026", "01/04/2026")))
        assertEquals(CsvImport.DateFormat.MDY, CsvImport.detectDateFormat(listOf("03/31/2026", "04/01/2026")))
        assertEquals("ambiguous defaults to day-first", CsvImport.DateFormat.DMY, CsvImport.detectDateFormat(listOf("05/03/2026")))
    }

    @Test fun signedAmountsBecomeIncomeAndExpense() {
        val p = plan("Date,Description,Amount\n2026-03-01,Salary,5000.00\n2026-03-02,Lunch,-12.50\n")
        assertEquals(listOf(TxKind.INCOME, TxKind.EXPENSE), p.rows.map { it.kind }); assertEquals(listOf(500_000L, 1_250L), p.rows.map { it.cents })
        assertEquals(listOf("Salary", "Lunch"), p.rows.map { it.title })
    }

    @Test fun invertSigns() {
        assertEquals(TxKind.EXPENSE, plan("Date,Description,Amount\n2026-03-02,Shop,25.00\n", { it.copy(invertSigns = true) }).rows.first().kind)
    }

    @Test fun debitCreditColumns() {
        val p = plan("Date,Details,Debit,Credit\n2026-03-01,Rent,800.00,\n2026-03-02,Refund,,20.00\n2026-03-03,Odd,5.00,5.00\n2026-03-04,Empty,,\n")
        assertEquals(listOf(TxKind.EXPENSE, TxKind.INCOME), p.rows.map { it.kind }); assertEquals(listOf(4, 5), p.skipped.map { it.line })
    }

    @Test fun monosOwnExportRoundTrips() {
        val tx = listOf(
            TransactionRecord(amountCents = 1_250, kindRaw = "expense", category = "Food", date = date(2026, 3, 2), title = "=SUM(A1)", notes = "n,1"),
            TransactionRecord(amountCents = 500_000, kindRaw = "income", category = "Salary", date = date(2026, 3, 1), title = "Pay"),
        )
        val p = plan(CsvExport.transactions(tx, utc))
        assertEquals(2, p.rows.size); assertEquals(listOf(TxKind.INCOME, TxKind.EXPENSE), p.rows.map { it.kind })
        assertEquals(listOf("Salary", "Food"), p.rows.map { it.category })
        assertEquals("export's formula guard apostrophe is removed", "=SUM(A1)", p.rows[1].title); assertEquals("n,1", p.rows[1].notes)
    }

    @Test fun unknownCategoryFallsBackOrIsCreated() {
        val text = "Date,Description,Amount,Category\n2026-03-02,Taxi,-9.00,Transport\n2026-03-03,Bonus,50.00,Transport\n"
        assertEquals(listOf("Other", "Other Income"), plan(text).rows.map { it.category })
        val made = plan(text, create = true)
        assertEquals(listOf("Transport", "Transport"), made.rows.map { it.category }); assertEquals("one per type", 2, made.newCategories.size)
    }

    @Test fun badRowsAreSkippedWithReasons() {
        val p = plan("Date,Description,Amount\nnot a date,A,5\n2026-03-02,B,xyz\n2026-03-02,C,0\n2026-03-02,D,3.00\n")
        assertEquals(1, p.rows.size); assertEquals(listOf(2, 3, 4), p.skipped.map { it.line })
    }

    @Test fun duplicatesAgainstExistingAreSkippedOncePerRecord() {
        val have = listOf(TransactionRecord(amountCents = 1_250, kindRaw = "expense", category = "Food", date = date(2026, 3, 2), title = "Lunch"))
        val p = plan("Date,Description,Amount\n2026-03-02,lunch,-12.50\n2026-03-02,Lunch,-12.50\n", existing = have)
        assertEquals(1, p.duplicates); assertEquals("a second identical purchase on the same day is still genuine", 1, p.rows.size)
    }

    @Test fun reimportingTheSameFileAddsNothing() {
        val r = records("Date,Description,Amount,Category\n2026-03-02,Taxi,-9.00,Transport\n")
        val m = CsvImport.autoMapping(r)
        val first = CsvImport.plan(r, m, emptyList(), emptyList(), true, utc)
        val stored = first.rows.map { TransactionRecord(amountCents = it.cents, kindRaw = it.kind.raw, category = it.category, date = date(it.date.year, it.date.monthValue, it.date.dayOfMonth), title = it.title) }
        val again = CsvImport.plan(r, m, stored, emptyList(), true, utc)
        assertEquals(0, again.rows.size); assertEquals(1, again.duplicates)
    }

    @Test fun headerlessFileUsesPositions() {
        assertEquals(2, plan("2026-03-01,Coffee,-3.00\n2026-03-02,Pay,100.00\n").rows.size)
        assertEquals(LocalDate.of(2026, 3, 1), plan("2026-03-01,Coffee,-3.00\n").rows.first().date)
    }

    @Test fun typeColumnMode() {
        val p = plan("Date,Description,Amount,Type\n2026-03-01,Pay,100.00,credit\n2026-03-02,Shop,20.00,debit\n2026-03-03,Odd,5.00,wat\n")
        assertEquals(CsvImport.AmountMode.TYPE_COLUMN, CsvImport.autoMapping(records("Date,Description,Amount,Type\n")).mode)
        assertEquals(listOf(TxKind.INCOME, TxKind.EXPENSE), p.rows.map { it.kind }); assertEquals(1, p.skipped.size)
    }
}

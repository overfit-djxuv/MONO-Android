package app.mono.domain

import app.mono.domain.ParsedReceipt.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Port of ReceiptParserTests.swift. */
class ReceiptParserTest {
    private val now = LocalDate.of(2026, 10, 29)

    @Test fun amountFormats() {
        assertEquals(1250L, ReceiptParser.parseAmount("12.50")); assertEquals(1250L, ReceiptParser.parseAmount("12,50"))
        assertEquals(123450L, ReceiptParser.parseAmount("1,234.50")); assertEquals(123450L, ReceiptParser.parseAmount("1.234,50"))
        assertEquals("three digits after a separator is a thousands mark", 2_500_000L, ReceiptParser.parseAmount("25.000"))
        assertEquals(750L, ReceiptParser.parseAmount("7,5")); assertEquals(123456789L, ReceiptParser.parseAmount("1,234,567.89"))
        assertEquals(1250L, ReceiptParser.parseAmount("1250", digits = 0))
    }

    @Test fun amountsIgnorePercentagesAndPhoneNumbers() {
        assertEquals(listOf(120L), ReceiptParser.amounts("SST 6.00% 1.20").map { it.cents })
        assertTrue(ReceiptParser.amounts("Tel 0312345678").isEmpty())
    }

    @Test fun englishReceipt() {
        val p = ReceiptParser.parse(listOf("Village Grocer", "Tel: 03-1234 5678", "Date: 12/10/2026 14:03", "Milk 1L            RM 7.90", "2 x Bread          RM 9.00",
            "Subtotal           RM 16.90", "SST 6%             RM 1.01", "TOTAL              RM 17.91", "Cash               RM 20.00", "Change             RM 2.09"), now = now)
        assertEquals("Village Grocer", p.merchant); assertEquals(1791L, p.totalCents); assertEquals(101L, p.taxCents); assertEquals("MYR", p.currencyCode)
        assertEquals(LocalDate.of(2026, 10, 12), p.date); assertTrue("12/10 could be 12 Oct or Dec 10; flag it", Field.DATE in p.uncertain)
        assertEquals(listOf("Milk 1L", "Bread"), p.items.map { it.name })
    }

    @Test fun spanishReceipt() {
        val p = ReceiptParser.parse(listOf("Supermercado Sol", "Fecha: 5 de marzo de 2026", "Pan   1,20", "Leche   0,95", "IVA 10%   0,22", "TOTAL A PAGAR   2,37 €"), now = now)
        assertEquals("Supermercado Sol", p.merchant); assertEquals(LocalDate.of(2026, 3, 5), p.date); assertFalse(Field.DATE in p.uncertain)
        assertEquals(237L, p.totalCents); assertEquals(22L, p.taxCents); assertEquals("EUR", p.currencyCode)
    }

    @Test fun malayReceipt() {
        val p = ReceiptParser.parse(listOf("KEDAI RUNCIT AMAN", "Tarikh: 03 Mei 2026", "Nasi Lemak   RM 5.50", "Teh Tarik   RM 2.00", "Jumlah Kecil   RM 7.50", "Cukai SST   RM 0.45", "Jumlah Besar   RM 7.95"), now = now)
        assertEquals("KEDAI RUNCIT AMAN", p.merchant); assertEquals(LocalDate.of(2026, 5, 3), p.date); assertEquals(795L, p.totalCents); assertEquals(45L, p.taxCents)
    }

    @Test fun totalOnTheNextLineAndDecimalComma() {
        assertEquals(123450L, ReceiptParser.parse(listOf("Cafe Luna", "2026-02-14", "Total", "1.234,50"), now = now).totalCents)
    }

    @Test fun noTotalLineFallsBackToLargestAmountAndSaysSo() {
        val p = ReceiptParser.parse(listOf("Corner Shop", "Item A 3.00", "Item B 4.50"), now = now)
        assertEquals(450L, p.totalCents); assertTrue(Field.TOTAL in p.uncertain)
    }

    @Test fun garbageProducesNothingConfidentNotACrash() {
        val p = ReceiptParser.parse(listOf("", "@@##", "12"), now = now)
        assertNull(p.totalCents); assertNull(p.date); assertTrue(p.uncertain.containsAll(listOf(Field.DATE, Field.TOTAL, Field.MERCHANT)))
    }

    @Test fun futureAndImpossibleDatesAreRejected() {
        assertNull(ReceiptParser.findDate(listOf("31/02/2026"), now)); assertNull(ReceiptParser.findDate(listOf("01/01/2030"), now))
        assertEquals("second number above 12 can only be the day", LocalDate.of(2026, 10, 25), ReceiptParser.findDate(listOf("10/25/2026"), now)?.date)
    }

    @Test fun bareDollarSignIsNeverGuessed() {
        assertNull(ReceiptParser.findCurrency("Total $12.00")); assertEquals("MYR", ReceiptParser.findCurrency("Total RM12.00"))
    }

    @Test fun duplicateDetection() {
        val d = now
        val e = ReceiptMatcher.Existing("1", "Village Grocer", d, 1791, "abc")
        assertNotNull(ReceiptMatcher.duplicate(ReceiptMatcher.New("village grocer", d, 1791, ""), listOf(e)))
        assertNotNull("same photo", ReceiptMatcher.duplicate(ReceiptMatcher.New("", d, 0, "abc"), listOf(e)))
        assertNull(ReceiptMatcher.duplicate(ReceiptMatcher.New("Other", d, 1791, ""), listOf(e)))
        assertNull(ReceiptMatcher.duplicate(ReceiptMatcher.New("Village Grocer", d, 1792, ""), listOf(e)))
    }
}

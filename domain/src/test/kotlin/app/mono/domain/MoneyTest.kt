package app.mono.domain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Port of MONOTests/MoneyTests.swift: same inputs, same expected values. */
class MoneyTest {
    @Before fun reset() { Money.context = MoneyContext() }
    @After fun cleanup() { Money.context = MoneyContext() }

    @Test fun parseAcceptsCommonInput() {
        assertEquals(1200L, Money.parse("12"))
        assertEquals(1250L, Money.parse("12.5"))
        assertEquals(1205L, Money.parse("12.05"))
        assertEquals(120000L, Money.parse("1,200.00"))
        assertEquals(4000L, Money.parse("RM 40"))
        assertEquals(50L, Money.parse(".5"))
        assertEquals(500L, Money.parse("5."))
        assertEquals(0L, Money.parse("0"))
    }

    @Test fun parseRejectsAmbiguousInput() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("."))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("1.234")) // never silently rounds
        assertNull(Money.parse("1.2.3"))
        assertNull(Money.parse("-5"))
        assertNull(Money.parse("1e5"))
        assertNull(Money.parse("999999999999")) // over the cap
    }

    @Test fun format() {
        assertEquals("RM 0.00", Money.format(0))
        assertEquals("RM 1,234.56", Money.format(123456))
        assertEquals("−RM 0.05", Money.format(-5))
        assertEquals("+RM 50.00", Money.format(5000, signed = true))
        assertEquals("RM 1,235", Money.formatWhole(123456))
    }

    @Test fun formatHandlesOtherMinorUnits() {
        assertEquals("¥ 1,200", Money.format(1200, currency = Currency.find("JPY")))
        assertEquals("KD 1.234", Money.format(1234, currency = Currency.find("KWD")))
        assertEquals("$ 0.07", Money.format(7, currency = Currency.find("USD")))
    }

    @Test fun customSymbol() {
        Money.context = MoneyContext(symbolOverride = "SGD")
        assertEquals("SGD 1.00", Money.format(100))
        assertEquals(300L, Money.parse("SGD 3"))
    }

    @Test fun hideAmountsMasksFiguresButKeepsSignAndSymbol() {
        Money.context = MoneyContext(hidden = true)
        assertEquals("RM ••••", Money.format(123456))
        assertEquals("−RM ••••", Money.format(-5))
        assertEquals("+RM ••••", Money.format(5000, signed = true))
        assertEquals("RM ••••", Money.formatWhole(123456))
        assertEquals("•••", Money.compact(1500.0))
        // exported files are the one place that ignores hiding
        assertEquals("RM 1,234.56", Money.format(123456, ignoreHiding = true))
    }

    @Test fun compactAxisLabels() {
        assertEquals("1.2k", Money.compact(1200.0))
        assertEquals("3.4M", Money.compact(3_400_000.0))
        assertEquals("2k", Money.compact(2000.0))
        assertEquals("−5", Money.compact(-5.0))
    }

    @Test fun editableRoundTrip() {
        for (c in longArrayOf(1, 99, 100, 12345, 9_999_999_999)) {
            assertEquals(c, Money.parse(Money.editable(c)))
        }
        assertEquals("", Money.editable(0))
    }

    @Test fun monthKey() {
        assertEquals(MonthKey(2027, 1), MonthKey(2026, 12).adding(1))
        assertEquals(MonthKey(2025, 12), MonthKey(2026, 1).adding(-1))
        assertEquals(MonthKey(2026, 3), MonthKey.parse("2026-03"))
        assertNull(MonthKey.parse("2026-13"))
        assertEquals("2026-03", MonthKey(2026, 3).string)
        assertEquals(4, MonthKey(2026, 10).monthsThrough(MonthKey(2027, 1)).size)
        assertTrue(MonthKey(2026, 10).monthsThrough(MonthKey(2026, 9)).isEmpty())
        assertEquals(29, MonthKey(2028, 2).daysInMonth())
        assertTrue(MonthKey(2026, 9) < MonthKey(2026, 10))
    }
}

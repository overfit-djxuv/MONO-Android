package app.mono.domain

import app.mono.domain.ShoppingQuickAdd.Parsed
import app.mono.domain.model.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Port of ShoppingQuickAddTests.swift and CategoryWeeksTests.swift. */
class ShoppingAndWeeksTest {
    @Test fun nameOnly() = assertEquals(Parsed("Eggs", 1, 0), ShoppingQuickAdd.parse("Eggs"))
    @Test fun oneNumberIsAPrice() = assertEquals(Parsed("Milk", 1, 450), ShoppingQuickAdd.parse("Milk 4.50"))
    @Test fun twoNumbersAreQuantityThenPrice() = assertEquals(Parsed("Milk 1L", 2, 450), ShoppingQuickAdd.parse("Milk 1L 2 4.50"))
    @Test fun numbersInsideTheNameStay() = assertEquals("Rice 5kg", ShoppingQuickAdd.parse("Rice 5kg 25")?.name)
    @Test fun decimalCommaWorks() = assertEquals(120L, ShoppingQuickAdd.parse("Pan 1,20")?.priceCents)
    @Test fun thousandsCommaIsNotADecimal() = assertEquals(120_000L, ShoppingQuickAdd.parse("Sofa 1,200")?.priceCents)
    @Test fun emptyOrNumberOnlyIsRejected() { assertNull(ShoppingQuickAdd.parse("   ")); assertNull(ShoppingQuickAdd.parse("4.50")) }
    @Test fun absurdQuantityIsRejected() = assertNull(ShoppingQuickAdd.parse("Milk 5000 4.50"))
    @Test fun suffixedNumbersAreNotPrices() = assertEquals(Parsed("Milk 4.5f", 1, 0), ShoppingQuickAdd.parse("Milk 4.5f"))

    private fun tx(day: Int, cents: Long, cat: String = "Food", month: Int = 10, kind: String = "expense") =
        TransactionRecord(amountCents = cents, kindRaw = kind, category = cat, date = date(2026, month, day))
    private val oct = MonthKey(2026, 10)

    @Test fun weeksSplitOnTheSeventhFourteenthTwentyFirstAndTwentyEighth() {
        val r = CategoryWeeks.spend(listOf(tx(1, 100), tx(7, 10), tx(8, 200), tx(14, 20), tx(15, 300), tx(21, 30), tx(22, 400), tx(28, 40), tx(29, 500), tx(31, 50)), "Food", oct, utc)
        assertEquals(listOf(110L, 220L, 330L, 440L, 550L), r)
    }

    @Test fun otherCategoriesMonthsAndNonExpensesAreIgnored() {
        val r = CategoryWeeks.spend(listOf(tx(3, 100), tx(3, 999, "Fun"), tx(3, 999, month = 9), tx(3, 999, kind = "income"), tx(3, 999, kind = "transfer")), "Food", oct, utc)
        assertEquals(listOf(100L, 0L, 0L, 0L, 0L), r)
    }

    @Test fun weeksNotYetStartedAreMarked() {
        val now = LocalDate.of(2026, 10, 10)
        assertTrue(CategoryWeeks.started(1, oct, now))
        assertFalse(CategoryWeeks.started(2, oct, now))
        assertTrue(CategoryWeeks.started(4, oct.adding(-1), now))
    }
}

package app.mono.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the currency tests in MONOTests/AccountTests.swift. */
class CurrencyConverterTest {
    private val myr = Currency.find("MYR")
    private val usd = Currency.find("USD")
    private val jpy = Currency.find("JPY")
    private val kwd = Currency.find("KWD")

    private fun converter(vararg rates: Triple<String, String, Long>, reporting: Currency = myr) =
        CurrencyConverter(reporting, rates.map { RateEntry(it.first, it.second, it.third) })

    private fun usdRate() = converter(Triple("USD", "MYR", 4_472_500L))

    @Test fun convertsWithHalfAwayFromZeroRounding() {
        val c = usdRate()
        assertEquals(44_725L, c.convert(10_000, usd)) // 100.00 USD at 4.4725 = RM 447.25
        assertEquals(4L, c.convert(1, usd)) // $0.01 -> RM 0.044725 -> 0.04
        assertEquals(3_000L, c.convert(3_000, myr)) // reporting currency unchanged
        assertEquals(-44_725L, c.convert(-10_000, usd))
    }

    @Test fun differentMinorUnitsConvertCorrectly() {
        val c = converter(Triple("JPY", "MYR", 29_500L), Triple("KWD", "MYR", 14_500_000L))
        assertEquals(2_950L, c.convert(1_000, jpy)) // yen 1,000 at 0.0295 = RM 29.50
        assertEquals(1_789L, c.convert(1_234, kwd)) // 1.234 KWD at 14.5 = RM 17.893 -> 17.89
        val toYen = converter(Triple("MYR", "JPY", 33_900_000L), reporting = jpy)
        assertEquals(3_390L, toYen.convert(10_000, myr)) // RM 100.00 at 33.9 yen = yen 3,390
    }

    @Test fun missingRateIsNullNeverGuessed() {
        val c = usdRate()
        assertNull(c.convert(100, Currency.find("EUR")))
        assertFalse(c.hasRate(Currency.find("EUR")))
        assertTrue(c.hasRate(usd))
        assertTrue(c.hasRate(myr))
    }

    @Test fun inverseRateIsUsedWhenOnlyTheOtherDirectionWasEntered() {
        val c = converter(Triple("MYR", "USD", 223_600L)) // 1 RM = 0.2236 USD
        val micros = c.micros(usd)
        assertNotNull(micros)
        assertEquals(4.4723, micros!! / 1e6, 0.0002)
    }

    @Test fun impliedRateOfATransferAndRateText() {
        assertEquals(4_472_500L, CurrencyConverter.impliedMicros(10_000, usd, 44_725, myr))
        assertNull(CurrencyConverter.impliedMicros(0, usd, 1, myr))
        assertEquals(4_472_500L, CurrencyConverter.parseRate("4.4725"))
        assertEquals(4_470_000L, CurrencyConverter.parseRate("4,47"))
        assertNull(CurrencyConverter.parseRate("0"))
        assertNull(CurrencyConverter.parseRate("abc"))
        assertEquals("4.4725", CurrencyConverter.formatRate(4_472_500))
        assertEquals("1", CurrencyConverter.formatRate(1_000_000))
    }
}

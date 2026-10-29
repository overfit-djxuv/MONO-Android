package app.mono.domain

import app.mono.domain.model.Account
import app.mono.domain.model.TransactionRecord
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

val utc: ZoneId = ZoneOffset.UTC

/** Epoch millis for a UTC date, like `date()` in the iOS TestSupport. */
fun date(y: Int, m: Int, d: Int, hour: Int = 12): Long =
    ZonedDateTime.of(y, m, d, hour, 0, 0, 0, utc).toInstant().toEpochMilli()

fun income(cents: Long, d: Long, cat: String = "Salary") =
    TransactionRecord(amountCents = cents, kindRaw = "income", category = cat, date = d)

fun expense(cents: Long, d: Long, cat: String = "Food") =
    TransactionRecord(amountCents = cents, kindRaw = "expense", category = cat, date = d)

val myr = Currency.find("MYR")
val usd = Currency.find("USD")

fun converter(vararg rates: Triple<String, String, Long>, reporting: Currency = myr): CurrencyConverter =
    CurrencyConverter(reporting, rates.map { RateEntry(it.first, it.second, it.third) })

fun usdConverter() = converter(Triple("USD", "MYR", 4_472_500L))

fun account(name: String, cur: String = "MYR", opening: Long = 0, order: Int = 0) =
    Account(name = name, kindRaw = "bank", currencyCode = cur, openingCents = opening, sortOrder = order)

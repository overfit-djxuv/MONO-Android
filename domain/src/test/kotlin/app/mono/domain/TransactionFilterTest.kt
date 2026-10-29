package app.mono.domain

import app.mono.domain.TransactionFilter.Period
import app.mono.domain.model.Account
import app.mono.domain.model.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class TransactionFilterTest {
    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 29)
    private fun ms(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(12, 0).toInstant(utc).toEpochMilli()
    private val main = Account(id = "m", name = "Main", createdAt = 1)
    private val usd = Account(id = "u", name = "USD wallet", currencyCode = "USD", sortOrder = 1, createdAt = 2)
    private val txs = listOf(
        TransactionRecord(id = "1", amountCents = 1_000, kindRaw = "expense", category = "Food", date = ms(2026, 10, 5), title = "Lunch", tags = "work", accountID = "m"),
        TransactionRecord(id = "2", amountCents = 5_000, kindRaw = "expense", category = "Bills", date = ms(2026, 9, 20), title = "Phone", accountID = "u", notes = "monthly plan"),
        TransactionRecord(id = "3", amountCents = 300_000, kindRaw = "income", category = "Salary", date = ms(2026, 8, 31), title = "Pay", accountID = "m"),
        TransactionRecord(id = "4", amountCents = 2_000, kindRaw = "transfer", date = ms(2026, 10, 6), accountID = "m", toAccountID = "u", toAmountCents = 450),
        TransactionRecord(id = "5", amountCents = 700, kindRaw = "expense", category = "Food", date = ms(2025, 12, 31), title = "Old", accountID = "m"),
    )
    private fun ids(f: TransactionFilter) = f.apply(txs, listOf(main, usd), today, utc).map { it.id }

    @Test fun periods() {
        assertEquals(listOf("1", "4"), ids(TransactionFilter()))
        assertEquals(listOf("2"), ids(TransactionFilter(period = Period.LAST_MONTH)))
        assertEquals(listOf("1", "2", "4"), ids(TransactionFilter(period = Period.LAST3).copy(period = Period.LAST3)).filter { it != "3" })
        assertEquals(listOf("1", "2", "3", "4"), ids(TransactionFilter(period = Period.THIS_YEAR)))
        assertEquals(5, ids(TransactionFilter(period = Period.ALL)).size)
        assertEquals(listOf("1", "4"), ids(TransactionFilter(period = Period.CUSTOM, customFrom = LocalDate.of(2026, 10, 1), customTo = LocalDate.of(2026, 10, 6))))
    }

    @Test fun typeCategoryAccountTagAndAmount() {
        val all = TransactionFilter(period = Period.ALL)
        assertEquals(listOf("3"), ids(all.copy(kind = TxKind.INCOME)))
        assertEquals(listOf("1", "5"), ids(all.copy(category = "Food")))
        assertEquals("a transfer shows under both its accounts", listOf("2", "4"), ids(all.copy(accountID = "u")))
        assertEquals(listOf("1"), ids(all.copy(tag = "work")))
        assertEquals(listOf("2", "4"), ids(all.copy(minCents = 2_000, maxCents = 5_000)))
    }

    @Test fun searchLooksAtTitleNotesCategoryAndAccountNames() {
        val all = TransactionFilter(period = Period.ALL)
        assertEquals(listOf("2"), ids(all.copy(search = "MONTHLY")))
        assertEquals(listOf("2", "4"), ids(all.copy(search = "usd wallet")))
        assertEquals(listOf("3"), ids(all.copy(search = "salary")))
    }

    @Test fun activeAndCleared() {
        assertFalse(TransactionFilter().active); assertTrue(TransactionFilter(period = Period.ALL).active); assertFalse(TransactionFilter(kind = TxKind.INCOME).cleared().active)
    }

    @Test fun duplicatesNeedTheSameTypeAmountDayAccountAndName() {
        val a = TransactionRecord(id = "a", amountCents = 500, category = "Food", date = ms(2026, 10, 5), title = "Coffee", accountID = "m")
        val dupes = TransactionFilter.duplicateIDs(listOf(a, a.copy(id = "b", title = "COFFEE"), a.copy(id = "c", amountCents = 501), a.copy(id = "d", accountID = "u")), utc)
        assertEquals(setOf("a", "b"), dupes)
    }
}

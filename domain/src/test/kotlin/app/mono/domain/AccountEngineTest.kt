package app.mono.domain

import app.mono.domain.model.GoalEntry
import app.mono.domain.model.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the pure parts of MONOTests/AccountTests.swift (balances, transfers, reporting, tags). */
class AccountEngineTest {
    private fun tx(kind: String, cents: Long, acct: String?, day: Int = 5, toAcct: String? = null, toCents: Long = 0) =
        TransactionRecord(amountCents = cents, kindRaw = kind, category = if (kind == "transfer") "" else "Food",
            date = date(2026, 3, day), accountID = acct, toAccountID = toAcct, toAmountCents = toCents)

    @Test fun singleAccountMatchesTheOldAvailableCashFormula() {
        val main = account("Main", opening = 100_000)
        val txs = listOf(tx("income", 500_000, main.id), tx("expense", 12_345, main.id), tx("expense", 700, main.id))
        val e = GoalEntry(goalID = "g", amountCents = 50_000, date = date(2026, 3, 6), accountID = main.id)
        val new = AccountEngine.overview(listOf(main), txs, listOf(e), usdConverter())
        val old = FinanceEngine.availableCash(100_000, txs, listOf(e))
        assertEquals(old, new.total)
        assertEquals(100_000L + 500_000 - 12_345 - 700 - 50_000, new.total)
    }

    @Test fun transferMovesMoneyWithoutCreatingIncomeOrSpending() {
        val bank = account("Bank", opening = 200_000, order = 0)
        val cash = account("Cash", opening = 10_000, order = 1)
        val t = tx("transfer", 50_000, bank.id, toAcct = cash.id, toCents = 50_000)
        val all = listOf(t)
        val o = AccountEngine.overview(listOf(bank, cash), all, emptyList(), usdConverter())
        assertEquals(150_000L, o.balances[bank.id]); assertEquals(60_000L, o.balances[cash.id])
        assertEquals("total money is unchanged", 210_000L, o.total)
        val month = MonthKey(2026, 3)
        assertEquals(0L, FinanceEngine.totals(all, month, utc).first)
        assertEquals(0L, FinanceEngine.totals(all, month, utc).second)
        val s = FinanceEngine.monthlySummaries(all, listOf(month), utc)[0]
        assertEquals(0L, s.income); assertEquals(0L, s.expense); assertEquals(0, s.recordCount)
        assertEquals(0L, FinanceEngine.availableCash(0, all, emptyList()))
        assertEquals(0, FinanceEngine.expensesByCategory(all, month..month, utc).size)
        assertTrue(Reporting.reportable(all, listOf(bank, cash), usdConverter()).records.isEmpty())
    }

    @Test fun crossCurrencyTransferKeepsTotalWorthUnderTheEnteredRate() {
        val bank = account("Bank", opening = 500_000)
        val wallet = account("USD wallet", "USD", order = 1)
        val t = tx("transfer", 44_725, bank.id, toAcct = wallet.id, toCents = 10_000)
        val o = AccountEngine.overview(listOf(bank, wallet), listOf(t), emptyList(), usdConverter())
        assertEquals(455_275L, o.balances[bank.id]); assertEquals(10_000L, o.balances[wallet.id])
        assertEquals("RM 447.25 became \$100.00, worth RM 447.25 at the same rate", 500_000L, o.total)
    }

    @Test fun accountWithoutARateIsExcludedAndFlaggedNeverGuessed() {
        val bank = account("Bank", opening = 100_000)
        val eur = account("Euro", "EUR", opening = 50_000, order = 1)
        val o = AccountEngine.overview(listOf(bank, eur), emptyList(), emptyList(), usdConverter())
        assertEquals(100_000L, o.total)
        assertEquals(listOf(eur.id), o.unconverted)
        assertEquals("its own balance is still shown, in its own currency", 50_000L, o.balances[eur.id])
    }

    @Test fun recordsWithoutAnAccountBelongToTheDefaultAccount() {
        val a = account("A", order = 0)
        val b = account("B", order = 1)
        val inc = TransactionRecord(amountCents = 1_000, kindRaw = "income", category = "Salary", date = date(2026, 3, 1))
        val o = AccountEngine.overview(listOf(b, a), listOf(inc), emptyList(), usdConverter())
        assertEquals(1_000L, o.balances[a.id]); assertEquals(0L, o.balances[b.id])
        assertEquals(a.id, AccountEngine.defaultAccount(listOf(b, a))?.id)
    }

    @Test fun withNoAccountsTheOldFormulaKeepsTheFigureCorrect() {
        val inc = TransactionRecord(amountCents = 10_000, kindRaw = "income", category = "Salary", date = date(2026, 3, 1))
        assertEquals(15_000L, AccountEngine.overview(emptyList(), listOf(inc), emptyList(), usdConverter(), legacyOpening = 5_000).total)
    }

    @Test fun goalContributionsLeaveTheChosenAccountOnly() {
        val a = account("A", opening = 100_000, order = 0)
        val b = account("B", opening = 100_000, order = 1)
        val e = GoalEntry(goalID = "g", amountCents = 30_000, date = date(2026, 3, 1), accountID = b.id)
        val o = AccountEngine.overview(listOf(a, b), emptyList(), listOf(e), usdConverter())
        assertEquals(100_000L, o.balances[a.id]); assertEquals(70_000L, o.balances[b.id])
        val notFromCash = GoalEntry(goalID = "g", amountCents = 30_000, date = date(2026, 3, 1), affectsCash = false)
        assertEquals(100_000L, AccountEngine.overview(listOf(a), emptyList(), listOf(notFromCash), usdConverter()).total)
    }

    @Test fun crossCurrencyGoalContributionUsesTheAccountAmount() {
        val usdW = account("USD", "USD", opening = 50_000)
        val e = GoalEntry(goalID = "g", amountCents = 44_725, date = date(2026, 3, 1), accountID = usdW.id, accountAmountCents = 10_000)
        assertEquals(10_000L, e.accountDelta)
        assertEquals(40_000L, AccountEngine.balance(usdW, usdW.id, emptyList(), listOf(e)))
        val back = e.copy(amountCents = -44_725)
        assertEquals(-10_000L, back.accountDelta)
    }

    @Test fun archivedAccountsStillCountSoMoneyNeverVanishes() {
        val a = account("A", opening = 100_000, order = 0)
        val old = account("Old", opening = 40_000, order = 1).copy(isArchived = true)
        assertEquals(140_000L, AccountEngine.overview(listOf(a, old), emptyList(), emptyList(), usdConverter()).total)
    }

    @Test fun tags() {
        assertEquals("coffee,work,travel", Tags.normalize("Coffee, #Work  coffee;TRAVEL"))
        assertEquals(emptyList<String>(), Tags.list("  ,, "))
        assertEquals(Tags.MAX_LENGTH, Tags.list("a".repeat(40)).first().length)
        assertEquals(Tags.MAX_TAGS, Tags.list((1..30).joinToString(",") { "t$it" }).size)
    }

    @Test fun foreignCurrencyRecordsAreConvertedForReportsAndNeverMixedBlindly() {
        val main = account("Main")
        val usdW = account("USD", "USD", order = 1)
        val spend = TransactionRecord(amountCents = 2_000, kindRaw = "expense", category = "Food", date = date(2026, 3, 5), accountID = usdW.id)
        val local = TransactionRecord(amountCents = 1_000, kindRaw = "expense", category = "Food", date = date(2026, 3, 5), accountID = main.id)
        val withRate = Reporting.reportable(listOf(spend, local), listOf(main, usdW), usdConverter())
        assertEquals("\$20.00 x 4.4725 = RM 89.45", listOf(1_000L, 8_945L), withRate.records.map { it.amountCents }.sorted())
        assertEquals(0, withRate.skipped)
        val noRate = Reporting.reportable(listOf(spend, local), listOf(main, usdW), converter())
        assertEquals(listOf(1_000L), noRate.records.map { it.amountCents }); assertEquals(1, noRate.skipped)
        assertEquals("the stored record is never altered", 2_000L, spend.amountCents)
        val same = Reporting.reportable(listOf(local), listOf(main), usdConverter())
        assertSame("single-currency users get the original records, no copies", local, same.records.first())
    }

    @Test fun booksSnapshot() {
        val main = account("Main", opening = 100_000)
        val usdW = account("USD", "USD", opening = 10_000, order = 1)
        val rate = app.mono.domain.model.ExchangeRate(fromCode = "USD", toCode = "MYR", rateMicros = 4_472_500)
        val books = Books(listOf(main, usdW), listOf(rate), emptyList(), emptyList(), reporting = myr)
        assertEquals(100_000L + 44_725, books.overview.total)
        assertEquals(44_725L, books.reportingBalance(usdW))
        assertTrue(books.hasSeveralAccounts)
        assertEquals(main.id, books.defaultAccount?.id)
    }
}

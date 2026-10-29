package app.mono.domain

import app.mono.domain.model.BudgetLimit
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.ShoppingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Port of MONOTests/FinanceEngineTests.swift. */
class FinanceEngineTest {
    private val oct = MonthKey(2026, 10)
    private fun entry(goal: String, cents: Long, d: Long, affectsCash: Boolean = true) =
        GoalEntry(goalID = goal, amountCents = cents, date = d, affectsCash = affectsCash)

    // Balances

    @Test fun availableCashFromOpeningIncomeAndExpenses() {
        val txs = listOf(income(500_000, date(2026, 10, 1)), expense(12_000, date(2026, 10, 3)), expense(3_050, date(2026, 10, 4)))
        assertEquals(100_000L + 500_000 - 12_000 - 3_050, FinanceEngine.availableCash(100_000, txs, emptyList()))
    }

    @Test fun goalContributionMovesMoneyWithoutCreatingIncomeOrExpense() {
        val txs = listOf(income(500_000, date(2026, 10, 1)), expense(100_000, date(2026, 10, 2)))
        val entries = listOf(entry("g", 150_000, date(2026, 10, 5)))
        val before = FinanceEngine.availableCash(0, txs, emptyList())
        val after = FinanceEngine.availableCash(0, txs, entries)
        assertEquals("cash falls by exactly the amount moved", 150_000L, before - after)
        val t = FinanceEngine.totals(txs, oct, utc)
        assertEquals(500_000L, t.first)
        assertEquals(100_000L, t.second)
        assertEquals("cash + saved is conserved", before, after + FinanceEngine.savedTotal(entries))
    }

    @Test fun withdrawalReturnsCash() {
        val entries = listOf(entry("g", 100_000, date(2026, 10, 5)), entry("g", -30_000, date(2026, 10, 9)))
        assertEquals(130_000L, FinanceEngine.availableCash(200_000, emptyList(), entries))
        assertEquals(70_000L, FinanceEngine.balance("g", entries))
    }

    @Test fun existingSavingsDoNotReduceCash() {
        val entries = listOf(entry("g", 80_000, date(2026, 10, 5), affectsCash = false))
        assertEquals(200_000L, FinanceEngine.availableCash(200_000, emptyList(), entries))
        assertEquals(80_000L, FinanceEngine.savedTotal(entries))
    }

    @Test fun balancePerGoalIsIsolated() {
        val entries = listOf(entry("a", 100, 0), entry("b", 250, 0))
        assertEquals(100L, FinanceEngine.balance("a", entries))
        assertEquals(250L, FinanceEngine.balance("b", entries))
    }

    // Monthly figures

    @Test fun totalsOnlyCountTheRequestedMonth() {
        val txs = listOf(income(100, date(2026, 9, 30)), income(200, date(2026, 10, 1)), expense(50, date(2026, 10, 31)), expense(70, date(2026, 11, 1)))
        val t = FinanceEngine.totals(txs, oct, utc)
        assertEquals(200L, t.first)
        assertEquals(50L, t.second)
    }

    @Test fun savingsRate() {
        assertEquals(0.25, FinanceEngine.savingsRate(1000, 750)!!, 1e-9)
        assertEquals(-0.5, FinanceEngine.savingsRate(1000, 1500)!!, 1e-9)
        assertNull("no income -> no rate, not 0%", FinanceEngine.savingsRate(0, 500))
    }

    @Test fun monthlySummariesReportEmptyMonthsHonestly() {
        val txs = listOf(income(1000, date(2026, 8, 2)), expense(400, date(2026, 10, 2)))
        val s = FinanceEngine.monthlySummaries(txs, MonthKey(2026, 8).monthsThrough(oct), utc)
        assertEquals(listOf(1, 0, 1), s.map { it.recordCount })
        assertEquals(0L, s[1].income)
        assertNull(s[1].savingsRate)
        assertEquals(1.0, s[0].savingsRate!!, 1e-9)
    }

    @Test fun firstRecordMonthIsNullWithoutData() {
        assertNull(FinanceEngine.firstRecordMonth(emptyList(), zone = utc))
        assertEquals(MonthKey(2026, 3), FinanceEngine.firstRecordMonth(listOf(expense(1, date(2026, 3, 9)), expense(1, date(2026, 5, 1))), zone = utc))
    }

    @Test fun savingsGrowthStartsAtFirstEntryAndNeverInventsHistory() {
        val e = listOf(entry("g", 1000, date(2026, 8, 10)), entry("g", 500, date(2026, 10, 3)), entry("g", -200, date(2026, 10, 20)))
        val pts = FinanceEngine.savingsGrowth(e, oct, utc)
        assertEquals(listOf(1000L, 1000L, 1300L), pts.map { it.second })
        assertEquals(MonthKey(2026, 8), pts.first().first)
        assertTrue(FinanceEngine.savingsGrowth(emptyList(), oct, utc).isEmpty())
    }

    @Test fun expensesByCategorySortedAndExcludesIncome() {
        val txs = listOf(expense(300, date(2026, 10, 1), "Food"), expense(900, date(2026, 10, 2), "Shopping"),
            expense(200, date(2026, 10, 3), "Food"), income(5000, date(2026, 10, 1)))
        val r = FinanceEngine.expensesByCategory(txs, oct..oct, utc)
        assertEquals(listOf("Shopping", "Food"), r.map { it.first })
        assertEquals(listOf(900L, 500L), r.map { it.second })
    }

    // Budget

    private fun limit(cat: String, cents: Long) = BudgetLimit(monthKey = "2026-10", category = cat, limitCents = cents)
    private val oct15 = LocalDate.of(2026, 10, 15)

    @Test fun budgetSummaryFigures() {
        val txs = listOf(expense(30_000, date(2026, 10, 2), "Food"), expense(10_000, date(2026, 10, 9), "Food"),
            expense(5_000, date(2026, 10, 9), "Other"), expense(99_999, date(2026, 9, 30), "Food"))
        val s = FinanceEngine.budgetSummary(oct, 100_000, listOf(limit("Food", 50_000)), txs, oct15, utc)
        assertEquals(45_000L, s.spent)
        assertEquals(55_000L, s.remaining)
        assertEquals(0.45, s.fraction!!, 1e-9)
        assertEquals(0L, s.overBy)
        assertEquals(5_000L, s.unbudgetedSpent)
        assertEquals("Oct 15 through Oct 31 inclusive", 17, s.daysLeft)
        assertEquals(55_000L / 17, s.dailyAllowance)
        assertEquals(1, s.lines.size)
        assertEquals(40_000L, s.lines[0].spent)
        assertEquals(10_000L, s.lines[0].remaining)
        assertEquals(0.8, s.lines[0].fraction, 1e-9)
        assertEquals("80% used is a warning", BudgetState.WARNING, s.lines[0].state)
        assertEquals(BudgetState.OK, s.state)
    }

    @Test fun overBudgetState() {
        val txs = listOf(expense(60_000, date(2026, 10, 2), "Food"))
        val s = FinanceEngine.budgetSummary(oct, 50_000, listOf(limit("Food", 40_000)), txs, LocalDate.of(2026, 10, 10), utc)
        assertEquals(BudgetState.OVER, s.state)
        assertEquals(10_000L, s.overBy)
        assertEquals(-10_000L, s.remaining)
        assertEquals("no negative allowance", 0L, s.dailyAllowance)
        assertEquals(BudgetState.OVER, s.lines[0].state)
        assertEquals(20_000L, s.lines[0].overBy)
    }

    @Test fun exactlyAtLimitIsNotOver() {
        val l = BudgetLine("Food", 1000, 1000)
        assertEquals(BudgetState.WARNING, l.state)
        assertEquals(0L, l.overBy)
        assertEquals(BudgetState.OVER, BudgetLine("Food", 1000, 1001).state)
        assertEquals(BudgetState.OK, BudgetLine("Food", 1000, 799).state)
    }

    @Test fun totalFallsBackToSumOfLimitsThenNull() {
        val withLimits = FinanceEngine.budgetSummary(oct, null, listOf(limit("Food", 300), limit("Other", 200)), emptyList(), LocalDate.of(2026, 10, 1), utc)
        assertEquals(500L, withLimits.total)
        val none = FinanceEngine.budgetSummary(oct, null, emptyList(), listOf(expense(10, date(2026, 10, 1))), LocalDate.of(2026, 10, 1), utc)
        assertNull(none.total)
        assertNull(none.remaining)
        assertNull(none.dailyAllowance)
    }

    @Test fun pastAndFutureMonthDays() {
        val past = FinanceEngine.budgetSummary(MonthKey(2026, 9), 1000, emptyList(), emptyList(), oct15, utc)
        assertEquals(0, past.daysLeft)
        assertNull(past.dailyAllowance)
        val future = FinanceEngine.budgetSummary(MonthKey(2026, 11), 3000, emptyList(), emptyList(), oct15, utc)
        assertEquals(30, future.daysLeft)
        assertEquals(100L, future.dailyAllowance)
    }

    @Test fun limitsFromOtherMonthsAreIgnored() {
        val other = BudgetLimit(monthKey = "2026-09", category = "Food", limitCents = 999)
        assertTrue(FinanceEngine.budgetSummary(oct, null, listOf(other), emptyList(), LocalDate.of(2026, 10, 1), utc).lines.isEmpty())
    }

    @Test fun rolloverCarriesUnspentButNeverDebt() {
        val sep = MonthKey(2026, 9)
        val limits = listOf(BudgetLimit(monthKey = "2026-09", category = "Food", limitCents = 50_000),
            BudgetLimit(monthKey = "2026-09", category = "Fun", limitCents = 10_000))
        val txs = listOf(expense(20_000, date(2026, 9, 3), "Food"), expense(15_000, date(2026, 9, 4), "Fun"))
        val r = FinanceEngine.rollover(sep, 100_000, limits, txs, utc)
        assertEquals(mapOf("Food" to 80_000L, "Fun" to 10_000L), r.limits.toMap())
        assertEquals(100_000L + (100_000 - 35_000), r.totalCents)
    }

    // Shopping

    private fun item(name: String, qty: Int, est: Long, purchased: Boolean = false, actual: Long? = null) =
        ShoppingItem(name = name, quantity = qty, estimatedCents = est, isPurchased = purchased, actualCents = actual)

    @Test fun shoppingTotals() {
        val s = FinanceEngine.shoppingSummary(listOf(
            item("Eggs", 2, 1200), item("Rice", 1, 2600),
            item("Milk", 3, 800, purchased = true, actual = 900),
            item("Bread", 1, 500, purchased = true), // no actual price -> falls back to the estimate
        ))
        assertEquals(2L * 1200 + 2600, s.toBuy)
        assertEquals(3L * 900 + 500, s.purchased)
        assertEquals(2, s.toBuyCount)
        assertEquals(2, s.purchasedCount)
    }

    @Test fun upcomingExcludesPaidAndSortsByDate() {
        val p1 = PlannedExpense(title = "B", amountCents = 1, dueDate = date(2026, 11, 2), category = "Bills")
        val p2 = PlannedExpense(title = "A", amountCents = 1, dueDate = date(2026, 10, 30), category = "Bills")
        val p3 = PlannedExpense(title = "C", amountCents = 1, dueDate = date(2026, 10, 1), category = "Bills", isPaid = true)
        assertEquals(listOf("A", "B"), FinanceEngine.upcoming(listOf(p1, p2, p3)).map { it.title })
    }
}

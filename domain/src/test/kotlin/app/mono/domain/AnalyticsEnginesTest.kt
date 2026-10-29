package app.mono.domain

import app.mono.domain.model.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

private fun exp(cents: Long, y: Int, m: Int, day: Int, cat: String = "Food", merchant: String = "", key: String = "") =
    TransactionRecord(amountCents = cents, kindRaw = "expense", category = cat, date = date(y, m, day), merchant = merchant, occurrenceKey = key)

private fun inc(cents: Long, y: Int, m: Int, day: Int = 1) =
    TransactionRecord(amountCents = cents, kindRaw = "income", category = "Salary", date = date(y, m, day))

/** Port of InsightsTests (Stage3Tests.swift). */
class InsightsTest {
    private val now = d(2026, 3, 10)

    @Test fun dailyAverageForTheCurrentMonthUsesOnlyDaysThatHaveHappened() {
        val a = Insights.dailyAverage(listOf(exp(10_000, 2026, 3, 2), exp(5_000, 2026, 3, 9), inc(999_999, 2026, 3, 1)), MonthKey(2026, 3), now, utc)!!
        assertEquals(10, a.days); assertTrue(a.isPartialMonth); assertEquals(15_000L, a.totalCents); assertEquals(1_500L, a.averageCents)
    }

    @Test fun dailyAverageForAFinishedMonthUsesTheWholeMonthAndFutureMonthsHaveNone() {
        val a = Insights.dailyAverage(listOf(exp(31_000, 2026, 1, 15)), MonthKey(2026, 1), now, utc)!!
        assertEquals(31, a.days); assertFalse(a.isPartialMonth); assertEquals(1_000L, a.averageCents)
        assertNull(Insights.dailyAverage(emptyList(), MonthKey(2026, 4), now, utc))
    }

    @Test fun comparisonMatchesEquivalentPeriodsNotAHalfMonthAgainstAWholeOne() {
        val txs = listOf(exp(10_000, 2026, 3, 3, "Food"), exp(4_000, 2026, 3, 9, "Fun"),
            exp(8_000, 2026, 2, 4, "Food"), exp(1_000, 2026, 2, 8, "Fun"), exp(50_000, 2026, 2, 20, "Rent")) // day 20 is outside the window
        val c = Insights.monthToDateComparison(txs, now, utc)
        assertEquals(10, c.days); assertEquals(14_000L, c.current); assertEquals(9_000L, c.previous)
        assertEquals(5_000L, c.delta); assertEquals(5.0 / 9.0, c.percent!!, 0.0001)
        assertEquals(listOf("Food", "Fun"), c.categories.map { it.category }.sorted())
        assertEquals(2_000L, c.categories.first { it.category == "Food" }.delta)
    }

    @Test fun comparisonUsesTheShorterMonthsLengthOnTheThirty1st() {
        val c = Insights.monthToDateComparison(listOf(exp(100, 2026, 3, 30), exp(100, 2026, 2, 27)), d(2026, 3, 31), utc)
        assertEquals("February only has 28 days", 28, c.days)
        assertEquals(0L, c.current); assertEquals(100L, c.previous)
    }

    @Test fun noPercentageWhenThereIsNothingToCompareAgainst() {
        assertNull(Insights.monthToDateComparison(listOf(exp(100, 2026, 3, 2)), now, utc).percent)
    }

    @Test fun weekdayPatternAveragesOverTheDaysThatExist() {
        // Mondays: 2026-03-02 and 2026-03-09; history starts Mar 2 (a Monday). The window ends Mar 10 (Tuesday).
        val txs = listOf(exp(6_000, 2026, 3, 2), exp(2_000, 2026, 3, 9), exp(3_000, 2026, 3, 3))
        val p = Insights.weekdayPattern(txs, 8, now, utc, firstWeekday = 1)
        assertEquals(7, p.size)
        val monday = p.first { it.weekday == 2 }; val tuesday = p.first { it.weekday == 3 }
        assertEquals(2, monday.occurrences); assertEquals("(6,000 + 2,000) / 2 Mondays", 4_000L, monday.averageCents)
        assertEquals(2, tuesday.occurrences); assertEquals("3,000 / 2 Tuesdays", 1_500L, tuesday.averageCents)
        assertEquals("history started Mar 2, so only one Sunday has happened", 1, p.first { it.weekday == 1 }.occurrences)
    }

    @Test fun weekdayPatternWithNoExpensesIsAllZero() {
        assertTrue(Insights.weekdayPattern(listOf(inc(100, 2026, 3, 1)), 8, now, utc, 1).all { it.averageCents == 0L })
    }

    @Test fun weekdayPatternFollowsTheCalendarsFirstDay() {
        val p = Insights.weekdayPattern(listOf(exp(100, 2026, 3, 2)), 8, now, utc, firstWeekday = 2)
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 1), p.map { it.weekday })
    }

    @Test fun categoryTotalsByMonth() {
        val months = listOf(MonthKey(2026, 1), MonthKey(2026, 2), MonthKey(2026, 3))
        val r = Insights.categoryTotals(listOf(exp(100, 2026, 1, 5, "Food"), exp(250, 2026, 1, 9, "Groceries"), exp(999, 2026, 2, 1, "Fun"), exp(70, 2026, 3, 1, "Food")),
            setOf("Food", "Groceries"), months, utc)
        assertEquals(listOf(350L, 0L, 70L), r.map { it.second })
    }

    @Test fun largestExpensesAreInRangeAndOrdered() {
        val txs = listOf(exp(500, 2026, 3, 1), exp(900, 2026, 3, 2), exp(700, 2026, 3, 3), exp(99_999, 2026, 2, 28), inc(50_000, 2026, 3, 1))
        val top = Insights.largestExpenses(txs, date(2026, 3, 1, 0), date(2026, 3, 31), 2)
        assertEquals(listOf(900L, 700L), top.map { it.amountCents })
    }

    @Test fun habitChangesNeedBothWindowsOfHistory() {
        assertNull("only one month of history", Insights.habitChanges(listOf(exp(100, 2026, 1, 5)), now, zone = utc))
        // now = Mar 2026: recent window = Dec, Jan, Feb; before = Sep, Oct, Nov
        val txs = listOf(exp(1_000, 2025, 9, 5), exp(1_000, 2025, 10, 5), exp(1_000, 2025, 11, 5),
            exp(2_000, 2025, 12, 5), exp(2_000, 2026, 1, 5), exp(2_000, 2026, 2, 5),
            exp(300, 2025, 10, 9, "Fun"), exp(900, 2026, 1, 9, "Rent"), exp(555, 2026, 3, 1))
        val c = Insights.habitChanges(txs, now, zone = utc)!!
        assertEquals("Food", c.first().category)
        assertEquals("6,000 vs 3,000; March is not complete so it's left out", 3_000L, c.first().delta)
        assertEquals(-300L, c.first { it.category == "Fun" }.delta)
        assertEquals(900L, c.first { it.category == "Rent" }.delta)
    }

    @Test fun scheduledPaymentsCountOnlyConfirmedScheduleTransactions() {
        val a = exp(1_599, 2026, 2, 5, "Subscriptions", key = "x|2026-02-05")
        val b = exp(5_000, 2026, 2, 6, "Food") // ordinary spending, not a subscription
        val c = exp(1_599, 2026, 3, 5, "Subscriptions", key = "x|2026-03-05")
        val r = Insights.scheduledPayments(listOf(a, b, c), listOf(MonthKey(2026, 2), MonthKey(2026, 3)), utc)
        assertEquals(listOf(1_599L, 1_599L), r.map { it.second })
    }
}

/** Port of PurchaseSimulatorTests. */
class PurchaseSimulatorTest {
    private fun ctx(safe: Long = 80_000, safe30: Long = 30_000, income: Long = 400_000, expense: Long = 150_000, budgetTotal: Long? = 250_000) =
        PurchaseSimulator.Context(200_000, safe, safe30, d(2026, 4, 9), income, expense, budgetTotal, 150_000,
            mapOf("Shopping" to PurchaseSimulator.CategoryBudget(20_000, 15_000)),
            listOf(
                PurchaseSimulator.GoalPace("Tokyo", 2, 60_000, 600_000, d(2027, 1, 5)),
                PurchaseSimulator.GoalPace("Laptop", 1, 20_000, 100_000, d(2026, 9, 1)),
                PurchaseSimulator.GoalPace("No plan", 1, 0, 50_000, null),
                PurchaseSimulator.GoalPace("Done", 3, 10_000, 0, null),
            ))

    private fun run(price: Long, category: String? = null, hourly: Long? = null, c: PurchaseSimulator.Context = ctx()) =
        PurchaseSimulator.evaluate(price, category, hourly, c)

    @Test fun availableMoneyAndSafeToSpendBeforeAndAfter() {
        val r = run(30_000)
        assertEquals(200_000L, r.cashBefore); assertEquals(170_000L, r.cashAfter)
        assertEquals(80_000L, r.safeBefore); assertEquals(50_000L, r.safeAfter); assertTrue(r.fitsSafeToSpend); assertEquals(0L, r.overSafeBy)
        val big = run(100_000)
        assertFalse(big.fitsSafeToSpend); assertEquals(20_000L, big.overSafeBy); assertEquals(-20_000L, big.safeAfter)
    }

    @Test fun budgetEffectOverallAndByCategory() {
        val r = run(12_000, "Shopping")
        assertEquals(100_000L, r.overall?.remainingBefore); assertEquals(88_000L, r.overall?.remainingAfter)
        assertEquals(5_000L, r.category?.remainingBefore); assertEquals(-7_000L, r.category?.remainingAfter)
        assertTrue(r.category!!.goesOver)
        assertNull("a category without a limit has no category effect", run(500, "Food").category)
        assertNull(run(500, null).category)
        assertNull(run(500, c = ctx(budgetTotal = null)).overall)
        assertTrue(run(300_000).overall!!.goesOver)
    }

    @Test fun savingsRateDropsByThePurchase() {
        val r = run(40_000)
        assertEquals(250_000.0 / 400_000.0, r.savingsRateBefore!!, 1e-9)
        assertEquals(210_000.0 / 400_000.0, r.savingsRateAfter!!, 1e-9)
        assertNull("no income this month, no rate", run(1, c = ctx(income = 0)).savingsRateBefore)
    }

    @Test fun goalDelayIsThePriceOverTheMonthlyPaceInDays() {
        val r = run(30_000) // 30,000 / 60,000 a month = half a month, about 15 days
        val tokyo = r.goalDelays.first { it.name == "Tokyo" }
        assertEquals(15, tokyo.delayDays)
        assertEquals(d(2027, 1, 5).plusDays(15), tokyo.newEstimate)
        assertEquals("30,000 / 20,000 = 1.5 months", 46, r.goalDelays.first { it.name == "Laptop" }.delayDays)
        assertNull("no plan, no estimate: not guessed", r.goalDelays.first { it.name == "No plan" }.delayDays)
        assertFalse("funded goals aren't delayed", r.goalDelays.any { it.name == "Done" })
        assertEquals("higher priority first", "Tokyo", r.goalDelays.first().name)
    }

    @Test fun hoursOfWork() {
        assertEquals(15.0, run(30_000, hourly = 2_000).hoursOfWork!!, 1e-9)
        assertNull(run(30_000, hourly = null).hoursOfWork); assertNull(run(30_000, hourly = 0).hoursOfWork)
    }

    @Test fun waitingThirtyDaysUsesOnlyWhatIsAlreadyCommitted() {
        val w = PurchaseSimulator.waiting30Days(20_000, ctx(safe30 = 30_000))
        assertTrue(w.stillAffordable); assertEquals(10_000L, w.leftAfterBuying); assertEquals(d(2026, 4, 9), w.date)
        val no = PurchaseSimulator.waiting30Days(50_000, ctx(safe30 = 30_000))
        assertFalse(no.stillAffordable); assertEquals(-20_000L, no.leftAfterBuying)
    }

    @Test fun aCheaperAlternativeIsJustAnotherEvaluation() {
        val a = run(100_000); val b = run(40_000)
        assertTrue(b.overSafeBy < a.overSafeBy); assertTrue(b.fitsSafeToSpend)
        assertTrue((b.goalDelays[0].delayDays ?: 0) < (a.goalDelays[0].delayDays ?: 0))
    }
}

/** Port of WhatIfTests. */
class WhatIfTest {
    private val now = d(2026, 4, 10)
    private fun e(cents: Long, y: Int, m: Int, day: Int = 5) = exp(cents, y, m, day)
    private fun i(cents: Long, y: Int, m: Int, day: Int = 1) = inc(cents, y, m, day)
    private val goals = listOf(WhatIf.GoalLine("Tokyo", 600_000, 50_000), WhatIf.GoalLine("Done", 0, 10_000))

    private fun base(txs: List<TransactionRecord>? = null, cash: Long = 300_000) = WhatIf.baseline(
        txs ?: listOf(i(500_000, 2026, 1), e(200_000, 2026, 1), i(500_000, 2026, 2), e(250_000, 2026, 2), i(500_000, 2026, 3), e(150_000, 2026, 3)),
        now, cash, goals, 220_000, utc)

    @Test fun baselineIsTheAverageOfTheLastThreeCompletedMonths() {
        val b = base()
        assertEquals(500_000L, b.monthlyIncome); assertEquals("(200k + 250k + 150k) / 3", 200_000L, b.monthlySpending)
        assertEquals(3, b.basisMonths); assertEquals(60_000L, b.monthlyGoalSavings)
        assertTrue(b.basis.contains("3 completed months"))
    }

    @Test fun theCurrentIncompleteMonthIsNotMixedIntoTheAverage() {
        val b = base(listOf(i(500_000, 2026, 3), e(100_000, 2026, 3), e(999_999, 2026, 4, 2))) // April is only 10 days old
        assertEquals(100_000L, b.monthlySpending); assertEquals(1, b.basisMonths)
    }

    @Test fun withNoCompletedMonthItSaysItUsesThisMonthSoFar() {
        val b = base(listOf(i(300_000, 2026, 4, 2), e(80_000, 2026, 4, 3)))
        assertEquals(300_000L, b.monthlyIncome); assertEquals(0, b.basisMonths); assertTrue(b.basis.contains("this month so far"))
    }

    @Test fun anUnchangedScenarioMatchesTheCurrentPlanExactly() {
        val b = base()
        val plan = WhatIf.project(b); val same = WhatIf.project(b, WhatIf.Scenario())
        assertEquals(plan, same); assertEquals(13, plan.balances.size)
        assertEquals(500_000L - 200_000 - 60_000, plan.monthlySurplus)
        assertEquals(300_000L + 12 * 240_000, plan.endingBalance)
        assertTrue(WhatIf.Scenario().isUnchanged)
    }

    @Test fun lowerIncome() {
        val p = WhatIf.project(base(), WhatIf.Scenario(incomeChangePercent = -20.0))
        assertEquals(400_000L, p.monthlyIncome); assertEquals(140_000L, p.monthlySurplus)
        assertEquals(300_000L + 12 * 140_000, p.endingBalance)
        assertEquals("income can't go below zero", 0L, WhatIf.project(base(), WhatIf.Scenario(incomeChangeAmount = -600_000)).monthlyIncome)
    }

    @Test fun aNewSubscriptionAndASpendingOverride() {
        var s = WhatIf.Scenario(newMonthlyCost = 5_000)
        assertEquals(205_000L, WhatIf.project(base(), s).monthlySpending)
        s = s.copy(spendingOverride = 150_000)
        assertEquals("the override replaces normal spending; the new cost is added on top", 155_000L, WhatIf.project(base(), s).monthlySpending)
    }

    @Test fun aOneTimeExpenseHitsOnlyItsMonthAndCanMakeTheMoneyRunOut() {
        val p = WhatIf.project(base(), WhatIf.Scenario(oneTimeExpense = 1_200_000, oneTimeMonth = 3))
        assertEquals("nothing changes before month 3", 300_000L + 2 * 240_000, p.balances[2])
        assertEquals(300_000L + 3 * 240_000 - 1_200_000, p.balances[3])
        assertTrue("it recovers the next month, but it did run out", p.balances[4] > 0)
        assertEquals(3, p.runsOutInMonth); assertTrue(p.lowestBalance < 0)
        assertNull(WhatIf.project(base()).runsOutInMonth)
    }

    @Test fun savingMoreForAGoalLowersCashAndBringsTheGoalForward() {
        val s = WhatIf.Scenario(extraGoalName = "Tokyo", extraGoalMonthly = 50_000)
        val p = WhatIf.project(base(), s)
        assertEquals(110_000L, p.monthlyGoalSavings); assertEquals(190_000L, p.monthlySurplus)
        val outlook = WhatIf.goalOutlook(base(), s, now)
        val o = outlook.first()
        // 600k / 50k = 12 months; 600k / 100k = 6 months
        assertEquals(d(2027, 4, 10), o.baselineDate)
        assertEquals(d(2026, 10, 10), o.scenarioDate)
        assertEquals("funded goals are left out", 1, outlook.size)
    }

    @Test fun scenariosAreCappedAtThreeAndReplaceInPlace() {
        var list = WhatIf.upsert(emptyList(), WhatIf.Scenario(name = "Pay cut", incomeChangePercent = -10.0))
        val first = list.first()
        for (n in 1..5) list = WhatIf.upsert(list, WhatIf.Scenario(name = "S$n"))
        assertEquals(3, list.size)
        list = WhatIf.upsert(list, first.copy(name = "Renamed"))
        assertEquals("Renamed", list.first { it.id == first.id }.name); assertEquals(3, list.size)
    }

    @Test fun runningAProjectionNeverChangesAnyRecord() {
        val txs = listOf(i(500_000, 2026, 3), e(100_000, 2026, 3))
        val before = txs.map { it.amountCents }
        WhatIf.project(base(txs), WhatIf.Scenario(oneTimeExpense = 9_999_999, incomeChangePercent = -100.0))
        assertEquals(before, txs.map { it.amountCents })
    }
}

/** Port of MonthlyReviewTests. */
class MonthlyReviewTest {
    private val noRecurring = MonthlyReview.Recurring(0, 0, 0, 0)
    private fun fmt(c: Long) = "RM ${c / 100}.${"%02d".format(c % 100)}"
    private val feb = MonthKey(2026, 2)
    private val mar = MonthKey(2026, 3)

    private fun tr(cents: Long, y: Int, m: Int, day: Int) =
        TransactionRecord(amountCents = cents, kindRaw = "transfer", category = "", date = date(y, m, day))

    private fun make(
        month: MonthKey, today: LocalDate, txs: List<TransactionRecord>, moves: List<Pair<String, Long>> = emptyList(),
        budget: BudgetSummary? = null, recurring: MonthlyReview.Recurring = noRecurring,
    ) = MonthlyReview.make(month, today, txs, moves, emptyList(), budget, recurring, emptyList(), ::fmt, utc)

    @Test fun totalsNetAndSavingsRateIgnoreTransfersAndGoalMoney() {
        val txs = listOf(inc(500_000, 2026, 3), exp(120_000, 2026, 3, 5), exp(30_000, 2026, 3, 9, "Fun"), tr(999_999, 2026, 3, 7), exp(77_000, 2026, 2, 5))
        val r = make(mar, d(2026, 4, 2), txs, listOf("Trip" to 50_000L, "Trip" to -10_000L))
        assertEquals(500_000L, r.income); assertEquals(150_000L, r.expense); assertEquals(350_000L, r.net)
        assertEquals("contributions minus withdrawals", 40_000L, r.movedToGoals)
        assertEquals(0.7, r.savingsRate!!, 1e-9)
        assertEquals("the transfer and last month's expense aren't in March", 3, r.transactionCount)
        assertTrue(r.isComplete); assertEquals(31, r.daysCovered)
    }

    @Test fun categoriesSumToTheExpensesAndSharesToOne() {
        val r = make(mar, d(2026, 4, 2), listOf(exp(60_000, 2026, 3, 2, "Rent"), exp(30_000, 2026, 3, 3), exp(10_000, 2026, 3, 4), inc(1, 2026, 3)))
        assertEquals(listOf("Rent", "Food"), r.categories.map { it.category }); assertEquals(listOf(60_000L, 40_000L), r.categories.map { it.cents })
        assertEquals(r.expense, r.categories.sumOf { it.cents }); assertEquals(1.0, r.categories.sumOf { it.share }, 1e-9)
    }

    @Test fun aMonthInProgressSaysSoAndOnlyCountsItsDays() {
        val r = make(mar, d(2026, 3, 12), listOf(exp(10_000, 2026, 3, 5)))
        assertFalse(r.isComplete); assertEquals(12, r.daysCovered)
        assertEquals("a future month has covered nothing", 0, make(MonthKey(2026, 5), d(2026, 3, 12), emptyList()).daysCovered)
    }

    @Test fun aFinishedMonthIsComparedWholeAgainstWholeAndCategoriesAgreeWithTheTotals() {
        val txs = listOf(exp(10_000, 2026, 2, 3), exp(5_000, 2026, 2, 28), inc(300_000, 2026, 2), exp(20_000, 2026, 3, 3), exp(7_000, 2026, 3, 30, "Fun"), inc(400_000, 2026, 3))
        val c = make(mar, d(2026, 4, 2), txs).comparison!!
        assertFalse(c.isPartial); assertEquals(feb, c.previous)
        assertEquals(27_000L, c.expense); assertEquals(15_000L, c.previousExpense); assertEquals(400_000L, c.income); assertEquals(300_000L, c.previousIncome)
        assertEquals("March 30th is included, not cut at February's length", c.expense, c.categoryShifts.sumOf { it.current })
        assertEquals(c.previousExpense, c.categoryShifts.sumOf { it.previous })
    }

    @Test fun aMonthInProgressIsComparedOverTheSameNumberOfDays() {
        val c = make(mar, d(2026, 3, 10), listOf(exp(10_000, 2026, 2, 3), exp(90_000, 2026, 2, 20), exp(20_000, 2026, 3, 3))).comparison!!
        assertTrue(c.isPartial); assertEquals(10, c.days)
        assertEquals("February's day 20 is outside the first 10 days", 10_000L, c.previousExpense); assertEquals(20_000L, c.expense)
    }

    @Test fun noComparisonWithoutAPreviousMonthOfHistory() {
        assertNull(make(mar, d(2026, 4, 2), listOf(exp(100, 2026, 3, 3))).comparison)
    }

    @Test fun patternsAreFactsFromTheMonth() {
        val r = make(mar, d(2026, 4, 2), listOf(exp(5_000, 2026, 3, 7), exp(40_000, 2026, 3, 9, "Rent"), exp(1_000, 2026, 3, 10), inc(1, 2026, 3))) // Mar 7 is a Saturday
        assertTrue(r.patterns.any { it.contains("highest-spending day") && it.contains("RM 400.00") })
        assertTrue(r.patterns.any { it.contains("largest single expense was RM 400.00") })
        assertTrue(r.patterns.any { it.contains("3 expenses") })
        assertTrue(r.patterns.any { it.contains("weekends") })
        assertTrue(r.patterns.any { it.contains("Rent was your biggest category") })
        assertTrue("no spending, nothing to say", make(mar, d(2026, 4, 2), emptyList()).patterns.isEmpty())
    }

    @Test fun worthALookFlagsOnlyRealJumpsAndOverBudgetLines() {
        val txs = listOf(exp(10_000, 2026, 2, 3, "Fun"), exp(20_000, 2026, 3, 3, "Fun"), // +100.00 (+100%)
            exp(10_000, 2026, 2, 4), exp(10_500, 2026, 3, 4)) // +5.00: too small
        val r = make(mar, d(2026, 4, 2), txs)
        assertEquals(1, r.worthALook.count { it.contains("is up") })
        assertTrue(r.worthALook.first().contains("Fun is up RM 100.00 (100%)"))
        val b = BudgetSummary(mar, 50_000, 12_000, listOf(BudgetLine("Food", 5_000, 12_000)), 0, 0)
        assertTrue(make(mar, d(2026, 4, 2), txs, budget = b).worthALook.any { it.contains("Food went RM 70.00 over its budget") })
    }

    @Test fun manySmallPurchasesAreCounted() {
        val small = (1..9).map { exp(1_000, 2026, 3, it) } + exp(30_000, 2026, 3, 20, "Rent")
        assertTrue(make(mar, d(2026, 4, 2), small).worthALook.any { it.contains("9 small purchases under RM 20.00 added up to RM 90.00") })
    }

    @Test fun subscriptionSummaryIsLabelledAnEstimate() {
        val r = make(mar, d(2026, 4, 2), listOf(exp(100, 2026, 3, 1)), recurring = MonthlyReview.Recurring(2, 7_080, 3, 9_000))
        assertTrue(r.worthALook.any { it.contains("3 active subscriptions") && it.contains("(an estimate)") })
    }

    @Test fun theTimelineRunsFromTheFirstRecordToNow() {
        val ms = MonthlyReview.months(listOf(exp(100, 2025, 11, 5), exp(100, 2026, 2, 5)), emptyList(), d(2026, 3, 10), utc)
        assertEquals(listOf("2025-11", "2025-12", "2026-01", "2026-02", "2026-03"), ms.map { it.string })
        assertEquals(listOf("2026-03"), MonthlyReview.months(emptyList(), emptyList(), d(2026, 3, 10), utc).map { it.string })
    }

    @Test fun exportsContainTheRealFiguresAndEscapeCsv() {
        Money.context = MoneyContext(hidden = true)
        try {
            val r = make(mar, d(2026, 4, 2), listOf(inc(500_000, 2026, 3), exp(12_345, 2026, 3, 5, "Food, drinks", merchant = "Café \"Zed\"")))
            val md = MonthlyReview.markdown(r, ::fmt, utc)
            assertTrue(md.contains("# March 2026 review")); assertTrue(md.contains("Income: RM 5000.00")); assertTrue(md.contains("Net cash flow: RM 4876.55"))
            assertFalse("an export is the real numbers even with Hide amounts on", md.contains("••••"))
            val csv = MonthlyReview.csv(r, ::fmt, utc)
            assertTrue(csv.contains("\"Food, drinks\"")); assertTrue(csv.contains("\"Café \"\"Zed\"\"\""))
            assertEquals("RM 123.45", Money.format(12_345, ignoreHiding = true)); assertEquals("RM ••••", Money.format(12_345))
        } finally { Money.context = MoneyContext() }
    }

    @Test fun reviewBudgetAndNotNullChecks() { assertNotNull(make(mar, d(2026, 4, 2), emptyList())) }
}

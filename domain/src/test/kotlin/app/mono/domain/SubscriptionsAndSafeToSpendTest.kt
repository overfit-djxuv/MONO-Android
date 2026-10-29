package app.mono.domain

import app.mono.domain.SafeToSpend.AccountMoney
import app.mono.domain.SafeToSpend.Commitment
import app.mono.domain.SafeToSpend.Config
import app.mono.domain.SafeToSpend.EssentialBudget
import app.mono.domain.SafeToSpend.GoalPlan
import app.mono.domain.SafeToSpend.Horizon
import app.mono.domain.SafeToSpend.Source
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.RecurringRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

/** Port of SubscriptionScheduleTests (Stage3Tests.swift). */
class SubscriptionScheduleTest {
    private fun adv(date: LocalDate, u: RecurrenceUnit, n: Int = 1, day: Int) = SubscriptionSchedule.advance(date, u, n, day)

    @Test fun weeklyAndFortnightly() {
        assertEquals(d(2026, 3, 12), adv(d(2026, 3, 5), RecurrenceUnit.WEEK, day = 5))
        assertEquals(d(2026, 3, 19), adv(d(2026, 3, 5), RecurrenceUnit.WEEK, 2, day = 5))
    }

    @Test fun aMonthlyPaymentOnThe31stFollowsShortMonthsAndComesBack() {
        val feb = adv(d(2026, 1, 31), RecurrenceUnit.MONTH, day = 31)
        assertEquals("clamped, not skipped", d(2026, 2, 28), feb)
        assertEquals("returns to the 31st", d(2026, 3, 31), adv(feb, RecurrenceUnit.MONTH, day = 31))
        assertEquals("leap year", d(2028, 2, 29), adv(d(2028, 1, 31), RecurrenceUnit.MONTH, day = 31))
    }

    @Test fun monthlyCrossesTheYearAndQuarterlyAndHalfYearly() {
        assertEquals(d(2027, 1, 5), adv(d(2026, 12, 5), RecurrenceUnit.MONTH, day = 5))
        assertEquals(d(2027, 2, 15), adv(d(2026, 11, 15), RecurrenceUnit.MONTH, 3, day = 15))
        assertEquals(d(2027, 2, 1), adv(d(2026, 8, 1), RecurrenceUnit.MONTH, 6, day = 1))
    }

    @Test fun yearlyIncludingALeapDayBirthday() {
        assertEquals(d(2027, 6, 10), adv(d(2026, 6, 10), RecurrenceUnit.YEAR, day = 10))
        assertEquals(d(2029, 2, 28), adv(d(2028, 2, 29), RecurrenceUnit.YEAR, day = 29))
        assertEquals(d(2030, 2, 28), adv(d(2029, 2, 28), RecurrenceUnit.YEAR, day = 29))
        assertEquals("back to the 29th in a leap year", d(2032, 2, 29), adv(d(2031, 2, 28), RecurrenceUnit.YEAR, 1, day = 29))
    }

    @Test fun dueDatesIncludeOverdueOnesAndStopAtTheEnd() {
        val ds = SubscriptionSchedule.dueDates(d(2026, 1, 5), d(2026, 4, 5), RecurrenceUnit.MONTH, 1, 5)
        assertEquals(listOf(1, 2, 3, 4), ds.map { it.monthValue })
        assertTrue(SubscriptionSchedule.dueDates(d(2026, 6, 1), d(2026, 5, 1), RecurrenceUnit.MONTH, 1, 1).isEmpty())
        assertEquals(5, SubscriptionSchedule.dueDates(d(2026, 3, 2), d(2026, 3, 31), RecurrenceUnit.WEEK, 1, 2).size)
    }

    @Test fun resumingLandsOnTheNextRhythmDateWithoutCatchUp() {
        assertEquals(d(2026, 4, 5), SubscriptionSchedule.firstOnOrAfter(d(2026, 3, 20), d(2026, 1, 5), RecurrenceUnit.MONTH, 1, 5))
        assertEquals("due today stays due today", d(2026, 3, 5), SubscriptionSchedule.firstOnOrAfter(d(2026, 3, 5), d(2026, 3, 5), RecurrenceUnit.MONTH, 1, 5))
    }

    @Test fun costEstimates() {
        assertEquals(1_599L, SubscriptionSchedule.monthlyCost(1_599, RecurrenceUnit.MONTH, 1))
        assertEquals(19_188L, SubscriptionSchedule.annualCost(1_599, RecurrenceUnit.MONTH, 1))
        assertEquals("52 weeks", 520_000L, SubscriptionSchedule.annualCost(10_000, RecurrenceUnit.WEEK, 1))
        assertEquals("52 / 12 payments a month", 43_333L, SubscriptionSchedule.monthlyCost(10_000, RecurrenceUnit.WEEK, 1))
        assertEquals(1_000L, SubscriptionSchedule.monthlyCost(12_000, RecurrenceUnit.YEAR, 1))
        assertEquals(120_000L, SubscriptionSchedule.annualCost(30_000, RecurrenceUnit.MONTH, 3))
        assertEquals(21_667L, SubscriptionSchedule.monthlyCost(10_000, RecurrenceUnit.WEEK, 2))
        assertEquals("Monthly", SubscriptionSchedule.describe(RecurrenceUnit.MONTH, 1))
        assertEquals("Every 3 weeks", SubscriptionSchedule.describe(RecurrenceUnit.WEEK, 3))
    }

    @Test fun ruleHelpersUseTheStoredScheduleAndLegacyFallback() {
        val rule = RecurringRule(title = "Streaming", amountCents = 1_599, dayOfMonth = 5, nextMonthKey = "2026-03")
        assertEquals("a rule with no real date falls back to its month key and day", d(2026, 3, 5), rule.dueDate(utc))
        assertEquals("Monthly", rule.scheduleText)
        assertEquals("${rule.id}|2026-03-05", rule.occurrenceKey(d(2026, 3, 5)))
        assertEquals(d(2026, 4, 5), rule.following(d(2026, 3, 5)))
        assertEquals(1_599L, rule.monthlyCostEstimate)
        val withDate = rule.copy(nextDueDate = date(2026, 3, 9))
        assertEquals(d(2026, 3, 9), withDate.dueDate(utc))
    }
}

/** Port of SafeToSpendTests (Stage2Tests.swift). */
class SafeToSpendTest {
    private val today = d(2026, 3, 10) // 22 days left in March including today
    private val acct = AccountMoney("main", "Main", 200_000)

    private fun bill(cents: Long, due: Int = 20, cat: String = "Bills", title: String = "Bill") =
        Commitment(title, cents, d(2026, 3, due), cat, Source.PLANNED)

    private fun calc(
        cfg: Config = Config(), accounts: List<AccountMoney> = listOf(acct), bills: List<Commitment> = emptyList(),
        goals: List<GoalPlan> = emptyList(), essentials: List<EssentialBudget> = emptyList(), reserved: List<Pair<String, Long>> = emptyList(),
    ) = SafeToSpend.calculate(today, cfg, accounts, bills, goals, essentials, reserved)

    @Test fun theExampleFromTheBrief() {
        // Balance 2,000, bills 300, planned savings 400, essentials 500 -> 800 to spend.
        val r = calc(bills = listOf(bill(30_000)),
            goals = listOf(GoalPlan("Trip", 40_000, 100_000, listOf(d(2026, 3, 25)))),
            essentials = listOf(EssentialBudget("Groceries", 90_000, 40_000)))
        assertEquals(200_000L, r.accountMoney); assertEquals(30_000L, r.billsTotal)
        assertEquals(40_000L, r.savingsTotal); assertEquals(50_000L, r.essentialsTotal)
        assertEquals(80_000L, r.flexible)
    }

    @Test fun nothingCommittedMeansEverythingIsFlexible() {
        val r = calc()
        assertEquals(200_000L, r.flexible)
        assertEquals(200_000L / r.horizonDays, r.perDay)
    }

    @Test fun horizonDefaultsToEndOfMonthAndCountsDaysInclusively() {
        val r = calc()
        assertEquals(31, r.horizonEnd.dayOfMonth); assertEquals(22, r.horizonDays)
        val week = calc(Config(horizon = Horizon.Days(7)))
        assertEquals(7, week.horizonDays); assertEquals(16, week.horizonEnd.dayOfMonth)
    }

    @Test fun billsAfterTheHorizonAreIgnoredAndOverdueBillsStillCount() {
        val r = calc(Config(horizon = Horizon.Days(7)), bills = listOf(bill(10_000, 14), bill(99_000, 28), bill(5_000, 2, title = "Late")))
        assertEquals(15_000L, r.billsTotal)
        assertTrue(r.bills.any { it.detail.startsWith("Overdue") })
    }

    @Test fun savingsCountOnlyContributionsStillToComeAndNeverMoreThanTheGoalNeeds() {
        val dates = listOf(d(2026, 3, 5), d(2026, 3, 15), d(2026, 3, 25), d(2026, 4, 5)) // first is past, last is beyond March
        assertEquals("only Mar 15 and Mar 25", 80_000L, calc(goals = listOf(GoalPlan("Trip", 40_000, 1_000_000, dates))).savingsTotal)
        assertEquals(50_000L, calc(goals = listOf(GoalPlan("Almost", 40_000, 50_000, listOf(d(2026, 3, 15), d(2026, 3, 25))))).savingsTotal)
        assertEquals(0L, calc(goals = listOf(GoalPlan("Done", 40_000, 0, listOf(d(2026, 3, 15))))).savingsTotal)
    }

    @Test fun aBillIsNotHeldBackTwiceWhenItsCategoryIsAlsoEssential() {
        val r = calc(bills = listOf(bill(20_000, cat = "Bills")), essentials = listOf(EssentialBudget("Bills", 60_000, 10_000)))
        assertEquals(20_000L, r.billsTotal)
        assertEquals("50,000 budget left - the 20,000 bill already counted", 30_000L, r.essentialsTotal)
        assertEquals(200_000L - 20_000 - 30_000, r.flexible)
        val covered = calc(bills = listOf(bill(80_000, cat = "Bills")), essentials = listOf(EssentialBudget("Bills", 60_000, 10_000)))
        assertEquals("never negative", 0L, covered.essentialsTotal)
    }

    @Test fun essentialsAreScaledToAShorterHorizon() {
        // 22 days left in the month; an 11-day horizon holds back half of what's left.
        assertEquals(50_000L, calc(Config(horizon = Horizon.Days(11)), essentials = listOf(EssentialBudget("Groceries", 100_000, 0))).essentialsTotal)
    }

    @Test fun reservedFundsAreSubtractedAndOverspentEssentialsCountAsZero() {
        val r = calc(essentials = listOf(EssentialBudget("Food", 10_000, 25_000)), reserved = listOf("Emergency" to 50_000L, "Gift" to 0L))
        assertEquals(50_000L, r.reservedTotal); assertEquals(0L, r.essentialsTotal); assertEquals(1, r.reserved.size)
    }

    @Test fun eachCommitmentCanBeSwitchedOff() {
        val bills = listOf(bill(30_000))
        val goals = listOf(GoalPlan("T", 40_000, 90_000, listOf(d(2026, 3, 25))))
        val ess = listOf(EssentialBudget("G", 90_000, 40_000))
        val res = listOf("R" to 10_000L)
        fun run(c: Config) = calc(c, bills = bills, goals = goals, essentials = ess, reserved = res).flexible
        assertEquals(200_000L - 30_000 - 40_000 - 50_000 - 10_000, run(Config()))
        assertEquals(200_000L - 40_000 - 50_000 - 10_000, run(Config(includeBills = false)))
        assertEquals(200_000L - 30_000 - 50_000 - 10_000, run(Config(includeSavings = false)))
        assertEquals(200_000L - 30_000 - 40_000 - 10_000, run(Config(includeEssentials = false)))
        assertEquals(200_000L - 30_000 - 40_000 - 50_000, run(Config(includeReserved = false)))
        assertEquals(200_000L, run(Config(includeBills = false, includeSavings = false, includeEssentials = false, includeReserved = false)))
    }

    @Test fun excludedAndUnconvertedAccounts() {
        val savings = AccountMoney("savings", "Savings", 500_000)
        val euro = AccountMoney("euro", "Euro", null)
        val r = calc(Config(excludedAccountIDs = listOf(savings.id)), accounts = listOf(acct, savings, euro))
        assertEquals("savings excluded by choice, euro excluded because it has no rate", 200_000L, r.accountMoney)
        assertEquals(listOf("Euro"), r.unconvertedAccounts)
    }

    @Test fun aShortfallIsReportedNotHidden() {
        val r = calc(bills = listOf(bill(250_000)))
        assertEquals(-50_000L, r.flexible); assertTrue(r.isShortfall); assertNull(r.perDay)
    }

    // Building the inputs from stored data

    @Test fun buildsFromStoredDataCountingEveryWeeklyPaymentAndSkippingPausedOnes() {
        val bank = account("Main", opening = 500_000)
        val weekly = RecurringRule(title = "Gym", amountCents = 5_000, kindRaw = "expense", category = "Health", unitRaw = "week",
            dayOfMonth = 10, nextDueDate = date(2026, 3, 11), accountID = bank.id)
        val paused = weekly.copy(id = "paused", isActive = false)
        val income = weekly.copy(id = "income", kindRaw = "income")
        val books = Books(listOf(bank), emptyList(), emptyList(), emptyList(), reporting = myr)
        val planned = listOf(PlannedExpense(title = "Insurance", amountCents = 20_000, dueDate = date(2026, 3, 20), category = "Bills"),
            PlannedExpense(title = "Paid", amountCents = 99_000, dueDate = date(2026, 3, 20), category = "Bills", isPaid = true))
        val r = SafeToSpend.make(today, Config(), books, planned, listOf(weekly, paused, income), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), utc)
        // Mar 11, 18, 25 weekly payments (3 x 5,000) + the unpaid insurance; the paid bill, paused rule and income rule are ignored.
        assertEquals(15_000L + 20_000L, r.billsTotal)
        assertEquals(500_000L - 35_000L, r.flexible)
    }
}

/** Port of DashboardInsight tests (NewFeatureTests.swift). */
class DashboardInsightTest {
    private val month = MonthKey(2026, 3)
    private val today = d(2026, 3, 10)
    private fun budget(vararg lines: Triple<String, Long, Long>) = BudgetSummary(
        month, lines.sumOf { it.second }, lines.sumOf { it.third }, lines.map { BudgetLine(it.first, it.second, it.third) }, 0, 10)

    @Test fun nothingToSayWhenEverythingIsFine() {
        assertNull(DashboardInsight.pick(budget(Triple("Food", 1000, 200)), emptyList(), emptyList(), today))
    }

    @Test fun overALimitWinsOverEverythingElse() {
        val r = DashboardInsight.pick(budget(Triple("Food", 1000, 1200), Triple("Fun", 1000, 900)), listOf("Rent" to today), listOf("Trip"), today)
        assertEquals(DashboardInsight.OverBudget("Food", 200), r)
    }

    @Test fun paymentDueSoonBeatsNearLimitButFarPaymentsAreIgnored() {
        val near = budget(Triple("Food", 1000, 850))
        val soon = DashboardInsight.pick(near, listOf("Rent" to today.plusDays(2)), emptyList(), today) as DashboardInsight.PaymentDue
        assertEquals("Rent", soon.title); assertFalse(soon.overdue)
        assertEquals(DashboardInsight.NearLimit("Food", 150, 85), DashboardInsight.pick(near, listOf("Rent" to today.plusDays(10)), emptyList(), today))
    }

    @Test fun overduePaymentIsFlaggedAndBehindGoalIsLast() {
        val late = DashboardInsight.pick(budget(), listOf("Rent" to today.minusDays(2)), emptyList(), today) as DashboardInsight.PaymentDue
        assertTrue(late.overdue)
        assertEquals(DashboardInsight.GoalBehind("Tokyo"), DashboardInsight.pick(budget(), emptyList(), listOf("Tokyo"), today))
    }
}

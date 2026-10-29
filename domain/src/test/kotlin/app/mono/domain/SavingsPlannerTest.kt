package app.mono.domain

import app.mono.domain.model.GoalEntry
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.TripCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Port of MONOTests/SavingsPlannerTests.swift. */
class SavingsPlannerTest {
    private val today = LocalDate.of(2026, 10, 29)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    private fun input(target: Long = 600_000, saved: Long = 0, monthly: Long = 0, deadline: LocalDate?, day: Int = 5) =
        PlanInput(target, saved, monthly, deadline, day)

    // Schedule

    @Test fun contributionDatesAreStrictlyAfterTodayAndWithinDeadline() {
        val dates = SavingsPlanner.contributionDates(today, d(2026, 12, 31), 5)
        assertEquals(listOf(11, 12), dates.map { it.monthValue })
        assertTrue(dates.all { it.isAfter(today) })
    }

    @Test fun contributionOnTodayIsNotCounted() {
        assertEquals("only Dec 5; today's (Nov 5) is recorded manually", 1, SavingsPlanner.contributionDates(d(2026, 11, 5), d(2026, 12, 31), 5).size)
    }

    @Test fun contributionOnDeadlineDayIsIncluded() {
        assertEquals(2, SavingsPlanner.contributionDates(today, d(2026, 12, 5), 5).size)
    }

    @Test fun deadlineBeforeNextContributionDateHasNone() {
        assertTrue(SavingsPlanner.contributionDates(today, d(2026, 11, 4), 5).isEmpty())
    }

    @Test fun pastOrSameDayDeadlineHasNoDates() {
        assertTrue(SavingsPlanner.contributionDates(today, today, 5).isEmpty())
        assertTrue(SavingsPlanner.contributionDates(today, d(2026, 1, 1), 5).isEmpty())
    }

    @Test fun dayIsClampedToMonthLength() {
        assertEquals(listOf(28, 28), SavingsPlanner.contributionDates(d(2027, 1, 30), d(2027, 3, 31), 28).map { it.dayOfMonth })
    }

    @Test fun scheduleSpansYearBoundary() {
        assertEquals(3, SavingsPlanner.contributionDates(d(2026, 11, 20), d(2027, 2, 10), 1).size) // Dec 1, Jan 1, Feb 1
    }

    @Test fun weeklyScheduleKeepsTheAnchorRhythm() {
        val anchor = d(2026, 10, 1) // a Thursday
        val dates = SavingsPlanner.schedule(today, d(2026, 11, 20), ContributionFrequency.WEEKLY, 5, anchor)
        assertTrue(dates.all { it.dayOfWeek == anchor.dayOfWeek })
        assertEquals(d(2026, 11, 5), dates.first())
        assertEquals(3, dates.size) // Nov 5, 12, 19 (Nov 26 is past the deadline)
        val fortnight = SavingsPlanner.schedule(today, d(2026, 12, 31), ContributionFrequency.BIWEEKLY, 5, anchor)
        assertEquals(14L, java.time.temporal.ChronoUnit.DAYS.between(fortnight[0], fortnight[1]))
    }

    // Plan

    @Test fun onTrackWhenPlannedContributionsReachTarget() {
        // Nov 5 ... Jun 5 = 8 dates; 8 x 60,000 + 120,000 = 600,000
        val p = SavingsPlanner.plan(input(saved = 120_000, monthly = 60_000, deadline = d(2027, 6, 30)), today)
        assertEquals(8, p.contributionsRemaining)
        assertEquals(600_000L, p.expectedAtDeadline)
        assertEquals(FundingStatus.ON_TRACK, p.status)
        assertEquals(480_000L, p.remaining)
        assertEquals(60_000L, p.requiredMonthly)
        assertEquals(0L, p.shortfall)
        assertEquals(0.2, p.percentFunded, 1e-9)
    }

    @Test fun behindReportsShortfallAndRequiredMonthly() {
        val p = SavingsPlanner.plan(input(saved = 120_000, monthly = 40_000, deadline = d(2027, 6, 30)), today)
        assertEquals(FundingStatus.BEHIND, p.status)
        assertEquals(120_000L + 8 * 40_000, p.expectedAtDeadline)
        assertEquals(600_000L - 440_000, p.shortfall)
        assertEquals(60_000L, p.requiredMonthly)
    }

    @Test fun requiredMonthlyRoundsUp() {
        val p = SavingsPlanner.plan(input(target = 100_000, saved = 0, deadline = d(2027, 1, 31)), today)
        // Nov 5, Dec 5, Jan 5 -> 3 dates; 100,000 / 3 = 33,333.33 -> 33,334
        assertEquals(3, p.contributionsRemaining)
        assertEquals(33_334L, p.requiredMonthly)
        assertTrue(3 * p.requiredMonthly!! >= 100_000)
    }

    @Test fun fullyFundedIgnoresDeadline() {
        val p = SavingsPlanner.plan(input(target = 100_000, saved = 130_000, monthly = 10_000, deadline = d(2026, 1, 1)), today)
        assertEquals("funded wins even if the date has passed", FundingStatus.FUNDED, p.status)
        assertEquals(0L, p.remaining)
        assertEquals(1.0, p.percentFunded, 0.0)
        assertNull(p.requiredMonthly)
    }

    @Test fun overdueWhenDeadlinePassedAndNotFunded() {
        val p = SavingsPlanner.plan(input(saved = 100_000, monthly = 50_000, deadline = d(2026, 10, 1)), today)
        assertEquals(FundingStatus.OVERDUE, p.status)
        assertEquals(0, p.contributionsRemaining)
        assertEquals(-28, p.daysToDeadline)
        assertEquals(500_000L, p.remaining)
        assertNull(p.requiredMonthly)
    }

    @Test fun deadlineTodayIsNotOverdueButNeedsLumpSum() {
        val p = SavingsPlanner.plan(input(saved = 100_000, monthly = 50_000, deadline = today), today)
        assertEquals(FundingStatus.BEHIND, p.status)
        assertEquals(0, p.daysToDeadline)
        assertTrue(p.needsLumpSum)
    }

    @Test fun noScheduledDatesLeftNeedsLumpSum() {
        val p = SavingsPlanner.plan(input(saved = 0, monthly = 50_000, deadline = d(2026, 11, 3)), today)
        assertEquals(0, p.contributionsRemaining)
        assertNull(p.requiredMonthly)
        assertTrue(p.needsLumpSum)
        assertEquals(FundingStatus.BEHIND, p.status)
    }

    @Test fun openGoalHasNoDeadlineMath() {
        val p = SavingsPlanner.plan(input(target = 100_000, saved = 25_000, monthly = 10_000, deadline = null), today)
        assertEquals(FundingStatus.OPEN, p.status)
        assertNull(p.daysToDeadline)
        assertEquals(0, p.contributionsRemaining)
        assertEquals(8, p.monthsAtPlannedPace)
        assertEquals(0L, p.shortfall)
        assertFalse(p.needsLumpSum)
    }

    @Test fun zeroTargetIsSafe() {
        val p = SavingsPlanner.plan(input(target = 0, saved = 0, deadline = d(2027, 1, 1)), today)
        assertEquals(0.0, p.percentFunded, 0.0)
        assertEquals(0L, p.remaining)
        assertNotEquals("an empty plan isn’t ‘funded’", FundingStatus.FUNDED, p.status)
    }

    @Test fun overfundedCapsPercentButKeepsSurplus() {
        val p = SavingsPlanner.plan(input(target = 100_000, saved = 90_000, monthly = 20_000, deadline = d(2027, 1, 31)), today)
        assertEquals(FundingStatus.ON_TRACK, p.status)
        assertEquals(90_000L + 3 * 20_000 - 100_000, p.surplus)
        assertTrue(p.percentFunded <= 1)
    }

    @Test fun estimatedCompletionUsesThePlanThenTheRecentPace() {
        val planned = SavingsPlanner.plan(input(target = 100_000, saved = 25_000, monthly = 25_000, deadline = null), today)
        assertEquals(EstimateBasis.PLAN, planned.estimateBasis)
        assertEquals(d(2027, 1, 5), planned.estimatedCompletion) // Nov, Dec, Jan contributions of 25,000 finish 75,000
        val pace = SavingsPlanner.plan(input(target = 100_000, saved = 40_000, monthly = 0, deadline = null).copy(recentNet90Days = 30_000), today)
        assertEquals(EstimateBasis.ACTUAL_PACE, pace.estimateBasis)
        assertEquals(today.plusMonths(6), pace.estimatedCompletion) // 60,000 left at 10,000 a month
        val none = SavingsPlanner.plan(input(target = 100_000, saved = 0, monthly = 0, deadline = null), today)
        assertEquals(EstimateBasis.NONE, none.estimateBasis)
        assertNull(none.estimatedCompletion)
    }

    @Test fun requiredMonthlyEquivalentUsesWholeCalendarMonthsAtLeastOne() {
        // 7 months 2 days left: remaining 480,000 / 7 whole months
        val p = SavingsPlanner.plan(input(saved = 120_000, deadline = d(2027, 5, 31)), today)
        assertEquals(480_000L / 7 + 1, p.requiredMonthlyEquivalent)
        val soon = SavingsPlanner.plan(input(saved = 120_000, deadline = d(2026, 11, 10)), today)
        assertEquals("under a month counts as one month", 480_000L, soon.requiredMonthlyEquivalent)
    }

    // Goal wiring

    @Test fun tripTargetIsCostsPlusBufferForThisTripOnly() {
        val trip = SavingsGoal(name = "Tokyo", kindRaw = "trip", symbol = "airplane", bufferCents = 50_000)
        val other = SavingsGoal(name = "Bali", kindRaw = "trip", symbol = "airplane")
        val costs = listOf(
            TripCost(goalID = trip.id, categoryRaw = "flights", amountCents = 220_000),
            TripCost(goalID = trip.id, categoryRaw = "food", amountCents = 80_000),
            TripCost(goalID = other.id, categoryRaw = "flights", amountCents = 999_999),
        )
        assertEquals(350_000L, SavingsPlanner.tripTarget(costs, trip))
        assertEquals(350_000L, SavingsPlanner.target(trip, costs))
        val general = SavingsGoal(name = "Laptop", kindRaw = "general", targetCents = 600_000)
        assertEquals(600_000L, SavingsPlanner.target(general, costs))
    }

    @Test fun planForGoalUsesItsOwnEntriesOnly() {
        val deadline = d(2027, 6, 30).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val trip = SavingsGoal(name = "Tokyo", kindRaw = "trip", symbol = "airplane", deadline = deadline, plannedMonthlyCents = 10_000,
            createdAt = d(2026, 10, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        val costs = listOf(TripCost(goalID = trip.id, categoryRaw = "flights", amountCents = 100_000))
        val now = d(2026, 10, 29).atTime(12, 0).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val entries = listOf(GoalEntry(goalID = trip.id, amountCents = 30_000, date = now), GoalEntry(goalID = "other", amountCents = 77_000, date = now))
        val p = SavingsPlanner.plan(trip, costs, entries, today, java.time.ZoneOffset.UTC)
        assertEquals(30_000L, p.saved)
        assertEquals(100_000L, p.target)
    }

    @Test fun alternativeScenarioCanBeComparedWithoutMutation() {
        val base = input(saved = 100_000, monthly = 30_000, deadline = d(2027, 3, 31))
        val alt = base.copy(plannedMonthly = 70_000, deadline = d(2027, 6, 30)) // 8 dates x 70,000 + 100,000 = 660,000
        val a = SavingsPlanner.plan(base, today)
        val b = SavingsPlanner.plan(alt, today)
        assertTrue(b.expectedAtDeadline > a.expectedAtDeadline)
        assertEquals(FundingStatus.ON_TRACK, b.status)
        assertEquals(FundingStatus.BEHIND, a.status)
    }
}

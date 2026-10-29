package app.mono.domain

import app.mono.domain.model.GoalEntry
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.TripCost
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class FundingStatus {
    /** saved >= target */ FUNDED,
    /** planned contributions reach the target by the deadline */ ON_TRACK,
    /** planned contributions fall short by the deadline */ BEHIND,
    /** deadline has passed and the target is not met */ OVERDUE,
    /** no deadline set */ OPEN,
}

enum class EstimateBasis { PLAN, ACTUAL_PACE, NONE }

data class PlanInput(
    val target: Long,
    val saved: Long,
    /** The planned amount of each contribution (per week, fortnight or month, depending on [frequency]). */
    val plannedMonthly: Long,
    val deadline: LocalDate?,
    val contributionDay: Int,
    val frequency: ContributionFrequency = ContributionFrequency.MONTHLY,
    /** Schedule start. Weekly and fortnightly contributions keep this date's rhythm; nothing is scheduled before it. */
    val anchor: LocalDate? = null,
    /** Net amount actually contributed over the last 90 days (excluding money already saved), used only when no plan exists. */
    val recentNet90Days: Long = 0,
)

data class SavingsPlan(
    val target: Long,
    val saved: Long,
    val remaining: Long,
    val percentFunded: Double,
    /** Scheduled contribution dates strictly after today and on/before the deadline. */
    val contributionsRemaining: Int,
    /** Even amount needed over the remaining scheduled dates. null if funded or no dates remain. */
    val requiredMonthly: Long?,
    /** Saved + planned x remaining contributions. */
    val expectedAtDeadline: Long,
    val shortfall: Long,
    val surplus: Long,
    val status: FundingStatus,
    /** Whole days until the deadline (negative once passed). null without a deadline. */
    val daysToDeadline: Int?,
    /** Months to finish at the planned pace, ignoring the deadline. null if no planned amount. */
    val monthsAtPlannedPace: Int?,
    /** (target - saved) / whole calendar months left (at least one). null when funded or no deadline. */
    val requiredMonthlyEquivalent: Long?,
    val monthsRemaining: Double?,
    /** When the goal is expected to be funded at the planned (or recent actual) pace. An estimate, not a promise. */
    val estimatedCompletion: LocalDate?,
    val estimateBasis: EstimateBasis,
    val frequency: ContributionFrequency,
) {
    /** True when money is still needed but no scheduled contribution date remains before the deadline. */
    val needsLumpSum: Boolean get() = remaining > 0 && contributionsRemaining == 0 && status != FundingStatus.OPEN
}

object SavingsPlanner {
    /** Monthly schedule: the chosen day each month (clamped to month end), strictly after [today], up to and including [deadline]. */
    fun contributionDates(today: LocalDate, deadline: LocalDate, day: Int): List<LocalDate> {
        if (!deadline.isAfter(today)) return emptyList()
        val d = day.coerceIn(1, 28)
        val out = ArrayList<LocalDate>()
        var m = MonthKey.of(today)
        val last = MonthKey.of(deadline)
        while (m <= last && out.size < 600) {
            val date = LocalDate.of(m.year, m.month, minOf(d, m.daysInMonth()))
            if (date.isAfter(today) && !date.isAfter(deadline)) out += date
            m = m.adding(1)
        }
        return out
    }

    /**
     * Scheduled contribution dates strictly after [today], never before [anchor], up to [limit] dates and (if given) the deadline.
     * Monthly uses the chosen day of the month; weekly and fortnightly keep the rhythm that starts on [anchor].
     */
    fun schedule(
        today: LocalDate, deadline: LocalDate?, frequency: ContributionFrequency, day: Int, anchor: LocalDate?, limit: Int = 600,
    ): List<LocalDate> {
        val to = deadline ?: today.plusYears(100)
        if (!to.isAfter(today)) return emptyList()
        val step = frequency.stepDays
        if (step != null) {
            var d = anchor ?: today
            if (!d.isAfter(today)) {
                val gap = ChronoUnit.DAYS.between(d, today).toInt()
                d = d.plusDays(((gap / step + 1) * step).toLong())
            }
            val out = ArrayList<LocalDate>()
            while (!d.isAfter(to) && out.size < limit) { out += d; d = d.plusDays(step.toLong()) }
            return out
        }
        return contributionDates(today, to, day).filter { anchor == null || !it.isBefore(anchor) }.take(limit)
    }

    fun plan(i: PlanInput, today: LocalDate = LocalDate.now()): SavingsPlan {
        val remaining = maxOf(i.target - i.saved, 0)
        val funded = i.target > 0 && i.saved >= i.target
        val pct = if (i.target > 0) (i.saved.toDouble() / i.target).coerceIn(0.0, 1.0) else 0.0

        val dates = i.deadline?.let { schedule(today, it, i.frequency, i.contributionDay, i.anchor) } ?: emptyList()
        val n = dates.size
        val expected = i.saved + n * i.plannedMonthly
        val required: Long? = if (remaining > 0 && n > 0) ceilDiv(remaining, n.toLong()) else null

        val status: FundingStatus
        var days: Int? = null
        val dl = i.deadline
        if (dl != null) {
            days = ChronoUnit.DAYS.between(today, dl).toInt()
            status = when {
                funded -> FundingStatus.FUNDED
                days < 0 -> FundingStatus.OVERDUE
                expected >= i.target -> FundingStatus.ON_TRACK
                else -> FundingStatus.BEHIND
            }
        } else {
            status = if (funded) FundingStatus.FUNDED else FundingStatus.OPEN
        }

        // (target - saved) / whole calendar months left; under a month left counts as one so the figure never explodes.
        var months: Double? = null
        var monthlyEquivalent: Long? = null
        if (days != null && days >= 0 && dl != null) {
            months = days / (365.2425 / 12)
            val whole = ChronoUnit.MONTHS.between(today, dl).toInt()
            if (remaining > 0) monthlyEquivalent = ceilDiv(remaining, maxOf(whole, 1).toLong())
        }

        val (completion, basis) = estimate(remaining, i, today)

        return SavingsPlan(
            target = i.target, saved = i.saved, remaining = remaining, percentFunded = pct,
            contributionsRemaining = n, requiredMonthly = required, expectedAtDeadline = expected,
            shortfall = if (dl == null) 0 else maxOf(i.target - expected, 0),
            surplus = maxOf(expected - i.target, 0), status = status, daysToDeadline = days,
            monthsAtPlannedPace = if (remaining > 0 && i.plannedMonthly > 0) ceilDiv(remaining, i.plannedMonthly).toInt() else null,
            requiredMonthlyEquivalent = monthlyEquivalent, monthsRemaining = months,
            estimatedCompletion = completion, estimateBasis = basis, frequency = i.frequency,
        )
    }

    /**
     * When the goal will be funded: the date of the contribution that finishes it at the planned amount, or, with no plan,
     * the month it would finish at the recent actual pace. null if there is neither (or it is already funded).
     */
    fun estimate(remaining: Long, i: PlanInput, today: LocalDate): Pair<LocalDate?, EstimateBasis> {
        if (remaining <= 0) return null to EstimateBasis.NONE
        if (i.plannedMonthly > 0) {
            val needed = ceilDiv(remaining, i.plannedMonthly).toInt()
            val dates = schedule(today, null, i.frequency, i.contributionDay, i.anchor, limit = needed)
            if (dates.size == needed && dates.isNotEmpty()) return dates.last() to EstimateBasis.PLAN
            return null to EstimateBasis.NONE
        }
        val perMonth = i.recentNet90Days / 3
        if (perMonth > 0) {
            val months = ceilDiv(remaining, perMonth)
            return today.plusMonths(months) to EstimateBasis.ACTUAL_PACE
        }
        return null to EstimateBasis.NONE
    }

    // Goal conveniences

    fun tripTarget(costs: List<TripCost>, goal: SavingsGoal): Long =
        costs.filter { it.goalID == goal.id }.sumOf { it.amountCents } + goal.bufferCents

    fun target(goal: SavingsGoal, costs: List<TripCost>): Long =
        if (goal.kind == GoalKind.TRIP) tripTarget(costs, goal) else goal.targetCents

    private fun day(millis: Long, zone: ZoneId) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun input(
        goal: SavingsGoal, costs: List<TripCost>, entries: List<GoalEntry>,
        today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(),
    ): PlanInput {
        val since = today.minusDays(90)
        // Money you already had before MONO (not taken from cash) is a starting point, not a saving pace.
        val recent = entries.filter { it.goalID == goal.id && it.affectsCash && day(it.date, zone).let { d -> !d.isBefore(since) && !d.isAfter(today) } }
            .sumOf { it.amountCents }
        return PlanInput(
            target = target(goal, costs),
            saved = FinanceEngine.balance(goal.id, entries),
            plannedMonthly = goal.plannedMonthlyCents,
            deadline = goal.deadline?.let { day(it, zone) },
            contributionDay = goal.contributionDay,
            frequency = goal.frequency,
            anchor = day(goal.scheduleStart, zone),
            recentNet90Days = recent,
        )
    }

    fun plan(
        goal: SavingsGoal, costs: List<TripCost>, entries: List<GoalEntry>,
        today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(),
    ): SavingsPlan = plan(input(goal, costs, entries, today, zone), today)
}

package app.mono.domain

import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.RecurringRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class RemindersTest {
    private fun at(y: Int, m: Int, d: Int, h: Int = 0) = LocalDateTime.of(y, m, d, h, 0)
    private val fmt: (Long) -> String = { "RM ${it / 100}" }

    private fun planned(title: String, due: Long, paid: Boolean = false) =
        PlannedExpense(title = title, amountCents = 98_000, dueDate = due, category = "Bills", isPaid = paid)

    @Test fun plannedRemindersEveAndDayOnlyForFuture() {
        val now = at(2026, 3, 9, 10)
        val r = Reminders.plannedReminders(listOf(
            planned("Insurance", date(2026, 3, 12)), planned("Tomorrow", date(2026, 3, 10)), planned("Today", date(2026, 3, 9)),
            planned("Paid", date(2026, 3, 20), paid = true), planned("Late", date(2026, 3, 1))), now, 9, utc, fmt)
        assertEquals(2, r.map { it.body }.toSet().size)
        val byTitle = r.groupBy { it.title }
        assertEquals(2, byTitle["Insurance"]?.size)
        assertEquals("9:00 the day before is already past; only the day-of remains", 1, byTitle["Tomorrow"]?.size)
        assertNull("09:00 today has passed", byTitle["Today"]); assertNull(byTitle["Paid"]); assertNull(byTitle["Late"])
    }

    private fun rule(tweak: (RecurringRule) -> RecurringRule = { it }) = tweak(
        RecurringRule(title = "Netflix", amountCents = 1_599, kindRaw = "expense", category = "Subscriptions", dayOfMonth = 20, nextMonthKey = "2026-03",
            nextDueDate = date(2026, 3, 20), remindDaysBefore = 3))

    @Test fun remindersFollowTheLeadTimeAndTheChosenHour() {
        val out = Reminders.subscriptionReminders(listOf(rule()), at(2026, 3, 10, 8), 7, utc) { _, c -> fmt(c) }
        assertEquals("the next two payments", 2, out.size)
        assertEquals(at(2026, 3, 17, 7), out[0].fire); assertEquals(at(2026, 4, 17, 7), out[1].fire)
        assertTrue(out[0].body.contains("due in 3 days")); assertTrue(out[0].id.startsWith("sub-"))
        val today = Reminders.subscriptionReminders(listOf(rule { it.copy(remindDaysBefore = 0) }), at(2026, 3, 10, 8), 7, utc) { _, c -> fmt(c) }
        assertTrue(today[0].body.contains("due today"))
    }

    @Test fun noRemindersForPausedCancelledOrIncomeOrAlreadyPastTimes() {
        val now = at(2026, 3, 10)
        fun run(r: RecurringRule) = Reminders.subscriptionReminders(listOf(r), now, 9, utc) { _, c -> fmt(c) }
        assertTrue(run(rule { it.copy(isActive = false) }).isEmpty())
        assertTrue(run(rule { it.copy(isCancelled = true, isActive = false) }).isEmpty())
        assertTrue(run(rule { it.copy(kindRaw = "income") }).isEmpty())
        val late = Reminders.subscriptionReminders(listOf(rule { it.copy(remindDaysBefore = 1) }), at(2026, 3, 19, 12), 9, utc) { _, c -> fmt(c) }
        assertEquals("March's 09:00 has already passed, so the next two future reminders are April and May", listOf(4, 5), late.map { it.fire.monthValue })
    }

    @Test fun budgetAlertLevels() {
        assertEquals(0, Reminders.budgetLevel(null)); assertEquals(0, Reminders.budgetLevel(0.79)); assertEquals(1, Reminders.budgetLevel(0.8))
        assertEquals(1, Reminders.budgetLevel(1.0)); assertEquals(2, Reminders.budgetLevel(1.01))
    }

    @Test fun budgetAlertFiresOncePerLevelAndRearmsWhenSpendingDrops() {
        val month = MonthKey(2026, 3)
        fun summary(spent: Long) = BudgetSummary(month, 100_000, spent, emptyList(), 0, 10)
        var known = 0
        fun check(spent: Long): String? { val (lvl, msg) = Reminders.checkBudget(summary(spent), known, fmt); known = lvl; return msg }
        assertNull(check(50_000)); assertNotNull(check(85_000))
        assertNull("same level, no repeat", check(90_000)); assertNotNull("over is a new level", check(110_000))
        assertNull(check(40_000)); assertNotNull("re-arms after dropping", check(85_000))
        assertTrue(Reminders.checkBudget(BudgetSummary(month, null, 5, emptyList(), 0, 10), 0, fmt).second == null)
    }

    @Test fun quickActionsDefaultToAllAndHonourOrderAndTheLimit() {
        assertEquals(QuickAction.entries, QuickActionSettings.enabled(null))
        assertEquals(4, QuickActionSettings.shown(null).size)
        var raw: String? = QuickActionSettings.toggle(null, QuickAction.ADD_EXPENSE, false)
        assertTrue(QuickAction.ADD_EXPENSE !in QuickActionSettings.enabled(raw))
        raw = QuickActionSettings.toggle(raw, QuickAction.ADD_EXPENSE, true)
        assertEquals(QuickAction.ADD_EXPENSE, QuickActionSettings.enabled(raw).last())
        assertEquals("garbage and duplicates are ignored", listOf(QuickAction.TRANSFER), QuickActionSettings.enabled("nonsense,transfer,transfer"))
    }
}

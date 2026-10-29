package app.mono.domain

import app.mono.domain.model.GoalEntry
import app.mono.domain.model.RecurringRule
import java.time.LocalDate
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.ShoppingItem
import app.mono.domain.model.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LedgerRulesTest {
    private fun expectError(error: LedgerError, block: () -> Unit) {
        try { block(); fail("expected $error") } catch (e: LedgerException) { assertEquals(error, e.error) }
    }

    // Transfers (AccountTests.testTransferRules)

    @Test fun transferRules() {
        val a = account("A", opening = 100_000)
        val b = account("B", order = 1)
        val usdW = account("W", "USD", order = 2)
        expectError(LedgerError.SAME_ACCOUNT) { LedgerRules.validateTransfer(a, a, 100, 100) }
        expectError(LedgerError.INVALID_AMOUNT) { LedgerRules.validateTransfer(a, b, 0, 0) }
        expectError(LedgerError.TRANSFER_AMOUNT_MISMATCH) { LedgerRules.validateTransfer(a, b, 100, 99) }
        expectError(LedgerError.ARCHIVED_ACCOUNT) { LedgerRules.validateTransfer(a, b.copy(isArchived = true), 100, 100) }
        LedgerRules.validateTransfer(a, usdW, 4_472, 1_000) // different currencies: the amount received fixes the rate
    }

    @Test fun transferIsOneRecordThatMovesMoneyBothWays() {
        val bank = account("Bank", opening = 200_000)
        val cash = account("Cash", opening = 10_000, order = 1)
        val t = LedgerRules.transfer(bank, cash, 50_000, 50_000, date(2026, 3, 5))
        assertEquals("transfer", t.kindRaw)
        assertEquals(bank.id, t.accountID)
        assertEquals(cash.id, t.toAccountID)
        val o = AccountEngine.overview(listOf(bank, cash), listOf(t), emptyList(), usdConverter())
        assertEquals(210_000L, o.total)
    }

    // Duplicate warning (AccountTests.testDuplicateWarning)

    @Test fun duplicateWarning() {
        val main = account("Main")
        val other = account("Other", order = 1)
        val existing = TransactionRecord(amountCents = 1_250, kindRaw = "expense", category = "Food", date = date(2026, 3, 5, 9), title = "Lunch", accountID = main.id)
        fun dupe(cents: Long = 1_250, day: Int = 5, title: String = "lunch", acct: String? = null, id: String? = null) =
            LedgerRules.possibleDuplicate(TxKind.EXPENSE, cents, date(2026, 3, day, 20), title, accountID = acct ?: main.id,
                defaultAccountID = main.id, excluding = id, existing = listOf(existing), zone = utc) != null
        assertTrue(dupe())
        assertFalse(dupe(1_251)); assertFalse(dupe(day = 6)); assertFalse(dupe(title = "Dinner")); assertFalse(dupe(acct = other.id))
        assertFalse("editing a record doesn't flag itself", dupe(id = existing.id))
    }

    // Shopping -> expense

    private fun item(qty: Int = 2, est: Long = 1_250, actual: Long? = null) =
        ShoppingItem(name = "Milk", quantity = qty, estimatedCents = est, actualCents = actual, category = "Groceries")

    @Test fun recordingAPurchaseCreatesOneExpenseAndLinksIt() {
        val milk = item(actual = 1_300)
        val (tx, updated) = LedgerRules.recordPurchase(milk, emptyList(), 1_000)
        assertEquals(2_600L, tx.amountCents)
        assertEquals("Milk ×2", tx.title)
        assertEquals(milk.id, tx.sourceID)
        assertTrue(updated.isPurchased)
        assertEquals(tx.id, updated.expenseID)
        assertFalse(LedgerRules.canRecord(updated, listOf(tx)))
        expectError(LedgerError.ALREADY_RECORDED) { LedgerRules.recordPurchase(updated, listOf(tx), 2_000) }
    }

    @Test fun anItemWithoutAPriceCannotBeRecorded() {
        expectError(LedgerError.INVALID_AMOUNT) { LedgerRules.recordPurchase(item(est = 0), emptyList(), 1) }
    }

    @Test fun markingAPlannedExpensePaidCreatesOneExpense() {
        val p = PlannedExpense(title = "Rent", amountCents = 120_000, dueDate = 0, category = "Bills")
        val (tx, paid) = LedgerRules.markPaid(p, emptyList(), 5)
        assertEquals(120_000L, tx.amountCents)
        assertEquals(p.id, tx.sourceID)
        assertTrue(paid.isPaid)
        assertEquals(tx.id, paid.paidTransactionID)
        expectError(LedgerError.ALREADY_RECORDED) { LedgerRules.markPaid(paid, listOf(tx), 6) }
        expectError(LedgerError.ALREADY_RECORDED) { LedgerRules.markPaid(p, listOf(tx), 6) } // a stale copy is still refused
    }

    // Goal money

    @Test fun movingMoneyIntoAndOutOfAGoal() {
        val goal = SavingsGoal(name = "Laptop")
        val add = LedgerRules.moveMoney(goal, 100_000, false, 1, "", emptyList())
        assertEquals(100_000L, add.amountCents)
        val out = LedgerRules.moveMoney(goal, 30_000, true, 2, "", listOf(add))
        assertEquals(-30_000L, out.amountCents)
        expectError(LedgerError.INSUFFICIENT_GOAL_BALANCE) { LedgerRules.moveMoney(goal, 200_000, true, 3, "", listOf(add)) }
        expectError(LedgerError.INVALID_AMOUNT) { LedgerRules.moveMoney(goal, 0, false, 3, "", emptyList()) }
        // a withdrawal can only draw on this goal's own money
        val other = GoalEntry(goalID = "someone-else", amountCents = 500_000, date = 1)
        expectError(LedgerError.INSUFFICIENT_GOAL_BALANCE) { LedgerRules.moveMoney(goal, 100, true, 3, "", listOf(other)) }
    }

    @Test fun repeatCopiesToTodayWithoutTheSourceLink() {
        val original = TransactionRecord(amountCents = 500, kindRaw = "expense", category = "Food", date = 1, title = "Coffee", sourceID = "shopping-item", tags = "a,b")
        val copy = LedgerRules.repeatOf(original, 99)
        assertEquals(99L, copy.date)
        assertEquals(500L, copy.amountCents)
        assertEquals("a,b", copy.tags)
        assertNull(copy.sourceID)
        assertTrue(copy.id != original.id)
        assertNotNull(copy.id)
    }
}

/** Port of RecordPaymentTests (Stage3Tests.swift). */
class RecordPaymentTest {
    private fun rule(due: Long = date(2026, 3, 5), cents: Long = 1_599, unit: String = "month") =
        RecurringRule(title = "Netflix", amountCents = cents, kindRaw = "expense", category = "Subscriptions", dayOfMonth = 5,
            nextMonthKey = "2026-03", nextDueDate = due, unitRaw = unit)

    private fun expectError(error: LedgerError, block: () -> Unit) {
        try { block(); fail("expected $error") } catch (e: LedgerException) { assertEquals(error, e.error) }
    }

    @Test fun confirmingCreatesOneTransactionAndMovesToTheNextPayment() {
        val r = rule()
        val (tx, next) = LedgerRules.recordPayment(r, date(2026, 3, 6), existing = emptyList(), zone = utc)
        assertEquals(1_599L, tx.amountCents); assertEquals(TxKind.EXPENSE, tx.kind); assertEquals("Subscriptions", tx.category); assertEquals("Netflix", tx.title)
        assertEquals(date(2026, 3, 6), tx.date); assertEquals("${r.id}|2026-03-05", tx.occurrenceKey)
        assertEquals(LocalDate.of(2026, 4, 5), next.dueDate(utc)); assertEquals("2026-04", next.nextMonthKey)
    }

    @Test fun theSameOccurrenceCanNeverBeRecordedTwice() {
        val r = rule()
        val (tx, _) = LedgerRules.recordPayment(r, 1, existing = emptyList(), zone = utc)
        // pretend the schedule was rewound (an edit, or a restored older copy)
        expectError(LedgerError.ALREADY_RECORDED) { LedgerRules.recordPayment(r, 2, existing = listOf(tx), zone = utc) }
    }

    @Test fun theActualAmountAndAccountCanDifferFromThePlan() {
        val r = rule()
        val (tx, next) = LedgerRules.recordPayment(r, 1, amountCents = 1_750, accountID = "card", existing = emptyList(), zone = utc)
        assertEquals(1_750L, tx.amountCents); assertEquals("card", tx.accountID); assertEquals("the plan keeps its usual amount", 1_599L, next.amountCents)
        expectError(LedgerError.INVALID_AMOUNT) { LedgerRules.recordPayment(rule(), 1, amountCents = 0, existing = emptyList(), zone = utc) }
    }

    @Test fun skippingMovesOnWithoutRecordingAnything() {
        assertEquals(LocalDate.of(2026, 4, 5), LedgerRules.skipPayment(rule(), utc).dueDate(utc))
    }

    @Test fun pausingAndResumingDoesNotOweWhatWasMissed() {
        val paused = LedgerRules.pause(rule(due = date(2026, 1, 5)))
        assertEquals(PaymentStatus.PAUSED, paused.status)
        val resumed = LedgerRules.resume(paused, LocalDate.of(2026, 3, 20), utc)
        assertEquals(PaymentStatus.ACTIVE, resumed.status); assertEquals(LocalDate.of(2026, 4, 5), resumed.dueDate(utc))
    }

    @Test fun cancelledPaymentsCannotBeRecordedUntilRestarted() {
        val r = LedgerRules.cancel(rule())
        assertEquals(PaymentStatus.CANCELLED, r.status); assertFalse(r.isActive)
        expectError(LedgerError.CANCELLED_PAYMENT) { LedgerRules.recordPayment(r, 1, existing = emptyList(), zone = utc) }
        expectError(LedgerError.CANCELLED_PAYMENT) { LedgerRules.skipPayment(r, utc) }
        assertEquals(PaymentStatus.ACTIVE, LedgerRules.resume(r, LocalDate.of(2026, 3, 1), utc).status)
    }

    @Test fun aWeeklyScheduleStepsBySevenDays() {
        val (_, next) = LedgerRules.recordPayment(rule(due = date(2026, 3, 2), unit = "week"), 1, existing = emptyList(), zone = utc)
        assertEquals(9, next.dueDate(utc).dayOfMonth)
    }

    @Test fun rulesSavedBeforeRealDatesGetOneWithoutChangingAnythingElse() {
        val old = RecurringRule(title = "Gym", amountCents = 8_000, kindRaw = "expense", category = "Health", dayOfMonth = 25, nextMonthKey = "2026-03")
        assertNull(old.nextDueDate)
        val up = LedgerRules.upgradeLegacy(old, "acct", utc)
        assertEquals(25, up.dueDate(utc).dayOfMonth); assertEquals("acct", up.accountID)
        assertEquals(8_000L, up.amountCents); assertEquals(PaymentStatus.ACTIVE, up.status)
        assertEquals("idempotent", up, LedgerRules.upgradeLegacy(up, "acct", utc))
    }

    @Test fun statusesAndDueDateFallbackForLegacyRules() {
        val r = RecurringRule(title = "x", amountCents = 1, kindRaw = "expense", category = "c", dayOfMonth = 31, nextMonthKey = "2026-02")
        assertEquals(PaymentStatus.ACTIVE, r.status)
        assertEquals("day 31 clamps to February's length", 28, r.dueDate(utc).dayOfMonth)
        assertEquals(PaymentStatus.PAUSED, r.copy(isActive = false).status)
        assertEquals(PaymentStatus.CANCELLED, r.copy(isActive = false, isCancelled = true).status)
    }
}

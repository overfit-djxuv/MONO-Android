package app.mono.domain

import app.mono.domain.model.GoalEntry
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

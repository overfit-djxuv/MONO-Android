package app.mono.domain

import app.mono.domain.model.Account
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.RecurringRule
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.ShoppingItem
import app.mono.domain.model.TransactionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class LedgerError(val text: String) {
    ALREADY_RECORDED("This has already been recorded."),
    INVALID_AMOUNT("Enter an amount greater than zero."),
    INSUFFICIENT_GOAL_BALANCE("That is more than the goal currently holds."),
    SAME_ACCOUNT("Choose two different accounts."),
    ARCHIVED_ACCOUNT("That account is archived. Choose another one."),
    TRANSFER_AMOUNT_MISMATCH("Both accounts use the same currency, so the amount received must equal the amount sent."),
    CANCELLED_PAYMENT("This payment is cancelled. Restart it first."),
}

class LedgerException(val error: LedgerError) : Exception(error.text)

/**
 * The rules for mutations that touch more than one record, kept pure so they can be tested without a database.
 * The repository applies the results inside one database transaction.
 */
object LedgerRules {
    // Shopping -> expense

    fun canRecord(item: ShoppingItem, existing: List<TransactionRecord>): Boolean =
        item.expenseID == null && existing.none { it.sourceID == item.id }

    /** The expense a purchased item produces, and the item updated to point at it. Refuses a second recording. */
    fun recordPurchase(item: ShoppingItem, existing: List<TransactionRecord>, nowMillis: Long): Pair<TransactionRecord, ShoppingItem> {
        if (!canRecord(item, existing)) throw LedgerException(LedgerError.ALREADY_RECORDED)
        if (item.effectiveTotal <= 0) throw LedgerException(LedgerError.INVALID_AMOUNT)
        val title = if (item.quantity > 1) "${item.name} ×${item.quantity}" else item.name
        val tx = TransactionRecord(
            amountCents = item.effectiveTotal, kindRaw = "expense", category = item.category,
            date = item.purchasedAt ?: nowMillis, title = title, sourceID = item.id,
        )
        return tx to item.copy(isPurchased = true, purchasedAt = item.purchasedAt ?: nowMillis, expenseID = tx.id)
    }

    // Planned expense -> expense

    fun markPaid(planned: PlannedExpense, existing: List<TransactionRecord>, nowMillis: Long): Pair<TransactionRecord, PlannedExpense> {
        if (planned.isPaid || planned.paidTransactionID != null || existing.any { it.sourceID == planned.id }) {
            throw LedgerException(LedgerError.ALREADY_RECORDED)
        }
        if (planned.amountCents <= 0) throw LedgerException(LedgerError.INVALID_AMOUNT)
        val tx = TransactionRecord(
            amountCents = planned.amountCents, kindRaw = "expense", category = planned.category,
            date = nowMillis, title = planned.title, sourceID = planned.id,
        )
        return tx to planned.copy(isPaid = true, paidTransactionID = tx.id)
    }

    // Transfers

    /**
     * A transfer is ONE record, so it can never be counted as income or spending and both balances change by matching amounts.
     * For different currencies the amount received is explicit, which fixes the exchange rate used for that transfer.
     */
    fun validateTransfer(from: Account, to: Account, sentCents: Long, receivedCents: Long, allowArchived: Boolean = false) {
        if (from.id == to.id) throw LedgerException(LedgerError.SAME_ACCOUNT)
        if (!allowArchived && (from.isArchived || to.isArchived)) throw LedgerException(LedgerError.ARCHIVED_ACCOUNT)
        if (sentCents !in 1..Money.MAX_CENTS || receivedCents !in 1..Money.MAX_CENTS) throw LedgerException(LedgerError.INVALID_AMOUNT)
        if (from.currencyCode == to.currencyCode && sentCents != receivedCents) throw LedgerException(LedgerError.TRANSFER_AMOUNT_MISMATCH)
    }

    fun transfer(
        from: Account, to: Account, sentCents: Long, receivedCents: Long, dateMillis: Long, title: String = "", notes: String = "",
    ): TransactionRecord {
        validateTransfer(from, to, sentCents, receivedCents)
        return TransactionRecord(
            amountCents = sentCents, kindRaw = "transfer", category = "", date = dateMillis, title = title, notes = notes,
            accountID = from.id, toAccountID = to.id, toAmountCents = receivedCents,
        )
    }

    // Duplicates

    /**
     * An existing record that looks like the one about to be added: same day, type, amount, account and description.
     * Used to warn (never to block), because buying two identical coffees in a day is legitimate.
     */
    fun possibleDuplicate(
        kind: TxKind, cents: Long, dateMillis: Long, title: String, merchant: String = "", accountID: String?,
        defaultAccountID: String?, excluding: String? = null, existing: List<TransactionRecord>, zone: ZoneId = ZoneId.systemDefault(),
    ): TransactionRecord? {
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val d = day(dateMillis)
        val label = (if (merchant.isEmpty()) title else merchant).trim().lowercase()
        return existing.firstOrNull { t ->
            t.id != excluding && t.kind == kind && t.amountCents == cents && day(t.date) == d &&
                (t.accountID ?: defaultAccountID) == (accountID ?: defaultAccountID) &&
                (if (t.merchant.isEmpty()) t.title else t.merchant).trim().lowercase() == label
        }
    }

    // Repeat

    /** Copies a transaction to today. The copy is a plain transaction: it doesn't inherit a shopping or planned-expense link. */
    fun repeatOf(tx: TransactionRecord, nowMillis: Long): TransactionRecord = TransactionRecord(
        amountCents = tx.amountCents, kindRaw = tx.kindRaw, category = tx.category, date = nowMillis, title = tx.title,
        notes = tx.notes, accountID = tx.accountID, toAccountID = tx.toAccountID, toAmountCents = tx.toAmountCents,
        merchant = tx.merchant, tags = tx.tags,
    )

    // Goals

    /** Moves money between available cash and a goal. Never touches transactions. */
    fun moveMoney(
        goal: SavingsGoal, cents: Long, withdraw: Boolean, dateMillis: Long, note: String, entries: List<GoalEntry>,
        affectsCash: Boolean = true, accountID: String? = null, accountAmountCents: Long = 0,
    ): GoalEntry {
        if (cents <= 0 || cents > Money.MAX_CENTS) throw LedgerException(LedgerError.INVALID_AMOUNT)
        if (withdraw && cents > FinanceEngine.balance(goal.id, entries)) throw LedgerException(LedgerError.INSUFFICIENT_GOAL_BALANCE)
        return GoalEntry(
            goalID = goal.id, amountCents = if (withdraw) -cents else cents, date = dateMillis, note = note,
            affectsCash = affectsCash, accountID = accountID, accountAmountCents = accountAmountCents,
        )
    }

    // Subscriptions and recurring payments

    private fun noon(d: LocalDate, zone: ZoneId) = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /** Moves the schedule to its next payment and keeps the older `nextMonthKey` field in step with the real date. */
    private fun withNextDue(rule: RecurringRule, due: LocalDate, zone: ZoneId): RecurringRule =
        rule.copy(nextDueDate = noon(due, zone), nextMonthKey = MonthKey.of(due).string)

    private fun advance(rule: RecurringRule, zone: ZoneId): RecurringRule = withNextDue(rule, rule.following(rule.dueDate(zone)), zone)

    /**
     * Records one due payment as a real transaction. This is the ONLY way a schedule creates money movement: nothing is deducted
     * until you confirm. The occurrence key makes confirming the same payment twice impossible, and the schedule moves to the next
     * payment only as part of the same change.
     */
    fun recordPayment(
        rule: RecurringRule, paidOnMillis: Long, amountCents: Long? = null, accountID: String? = null,
        existing: List<TransactionRecord>, zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<TransactionRecord, RecurringRule> {
        if (rule.isCancelled) throw LedgerException(LedgerError.CANCELLED_PAYMENT)
        val key = rule.occurrenceKey(rule.dueDate(zone))
        if (existing.any { it.occurrenceKey == key }) throw LedgerException(LedgerError.ALREADY_RECORDED)
        val cents = amountCents ?: rule.amountCents
        if (cents !in 1..Money.MAX_CENTS) throw LedgerException(LedgerError.INVALID_AMOUNT)
        val tx = TransactionRecord(
            amountCents = cents, kindRaw = rule.kindRaw, category = rule.category, date = paidOnMillis, title = rule.title, notes = rule.notes,
            accountID = accountID ?: rule.accountID, occurrenceKey = key,
        )
        return tx to advance(rule, zone)
    }

    /** Skips one payment (you didn't pay it, or it was waived): the schedule moves on and no transaction is created. */
    fun skipPayment(rule: RecurringRule, zone: ZoneId = ZoneId.systemDefault()): RecurringRule {
        if (rule.isCancelled) throw LedgerException(LedgerError.CANCELLED_PAYMENT)
        return advance(rule, zone)
    }

    fun pause(rule: RecurringRule): RecurringRule = rule.copy(isActive = false)

    /** Resumes a paused or cancelled payment from its next occurrence on or after [today]. Nothing missed in between is owed or recorded. */
    fun resume(rule: RecurringRule, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): RecurringRule {
        val next = SubscriptionSchedule.firstOnOrAfter(today, rule.dueDate(zone), rule.unit, rule.interval, rule.anchorDay)
        return withNextDue(rule.copy(isCancelled = false, isActive = true), next, zone)
    }

    /** Cancels a payment. It stays in your list for history, but never comes due again and isn't counted in forecasts. */
    fun cancel(rule: RecurringRule): RecurringRule = rule.copy(isCancelled = true, isActive = false)

    /** Fills `nextDueDate` for rules saved before schedules had real dates, and gives them an account. Changes nothing else. */
    fun upgradeLegacy(rule: RecurringRule, defaultAccountID: String?, zone: ZoneId = ZoneId.systemDefault()): RecurringRule {
        var r = rule
        if (r.nextDueDate == null) r = r.copy(nextDueDate = noon(r.dueDate(zone), zone))
        if (r.accountID == null && defaultAccountID != null) r = r.copy(accountID = defaultAccountID)
        return r
    }
}

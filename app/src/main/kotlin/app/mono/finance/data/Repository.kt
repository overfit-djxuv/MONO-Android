package app.mono.finance.data

import androidx.room.withTransaction
import app.mono.finance.backup.BackupFile
import kotlinx.coroutines.flow.first
import app.mono.domain.FinanceEngine
import app.mono.domain.LedgerRules
import app.mono.domain.MonthKey
import app.mono.domain.model.Account
import app.mono.domain.model.BudgetLimit
import app.mono.domain.model.ExchangeRate
import app.mono.domain.model.RecurringRule
import app.mono.domain.model.ReservedFund
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.MonthBudget
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.ShoppingItem
import app.mono.domain.model.TransactionRecord
import app.mono.domain.model.TripCost
import java.time.LocalDate

/**
 * Every write that touches more than one row runs inside one database transaction, using the pure rules in :domain,
 * so a failure never leaves half of a change behind.
 */
class Repository(private val db: MonoDatabase) {
    private val dao get() = db.dao()

    // Transactions

    suspend fun saveTransaction(t: TransactionRecord) = dao.put(t)

    /** Deleting a generated expense unlinks its source so it can be recorded again deliberately; receipts just become unlinked. */
    suspend fun deleteTransaction(tx: TransactionRecord, items: List<ShoppingItem>, planned: List<PlannedExpense>) = db.withTransaction {
        items.filter { it.expenseID == tx.id }.forEach { dao.put(it.copy(expenseID = null)) }
        planned.filter { it.paidTransactionID == tx.id }.forEach { dao.put(it.copy(paidTransactionID = null, isPaid = false)) }
        dao.unlinkReceipts(tx.id)
        dao.delete(tx)
    }

    // Shopping

    suspend fun saveItem(i: ShoppingItem) = dao.put(i)
    suspend fun deleteItems(items: List<ShoppingItem>) { if (items.isNotEmpty()) dao.delete(*items.toTypedArray()) }

    suspend fun recordPurchase(item: ShoppingItem, existing: List<TransactionRecord>, now: Long = System.currentTimeMillis()) =
        db.withTransaction {
            val (tx, updated) = LedgerRules.recordPurchase(item, existing, now)
            dao.put(tx); dao.put(updated)
            tx
        }

    // Budget

    suspend fun setMonthTotal(month: MonthKey, existing: MonthBudget?, cents: Long?) {
        when {
            cents == null && existing != null -> dao.delete(existing)
            cents != null -> dao.put((existing ?: MonthBudget(monthKey = month.string)).copy(totalCents = cents))
        }
    }

    suspend fun saveLimit(l: BudgetLimit) = dao.put(l)
    suspend fun deleteLimit(l: BudgetLimit) = dao.delete(l)
    suspend fun savePlanned(p: PlannedExpense) = dao.put(p)
    suspend fun deletePlanned(p: PlannedExpense) = dao.delete(p)

    suspend fun markPaid(p: PlannedExpense, existing: List<TransactionRecord>, now: Long = System.currentTimeMillis()) = db.withTransaction {
        val (tx, paid) = LedgerRules.markPaid(p, existing, now)
        dao.put(tx); dao.put(paid)
        tx
    }

    /**
     * Copies last month's budget into [month]. With [carryOver], each limit (and an explicit total) also gains whatever was left
     * unspent under it. Existing limits for [month] are never overwritten.
     */
    suspend fun copyBudget(
        month: MonthKey, totals: List<MonthBudget>, limits: List<BudgetLimit>, reported: List<TransactionRecord>, carryOver: Boolean,
    ) = db.withTransaction {
        val prev = month.adding(-1)
        val prevTotal = totals.firstOrNull { it.monthKey == prev.string }?.totalCents
        val plan = if (carryOver) FinanceEngine.rollover(prev, prevTotal, limits, reported)
        else app.mono.domain.BudgetRollover(prevTotal, limits.filter { it.monthKey == prev.string }.map { it.category to it.limitCents })
        if (plan.totalCents != null && totals.none { it.monthKey == month.string }) dao.put(MonthBudget(monthKey = month.string, totalCents = plan.totalCents!!))
        for ((cat, cents) in plan.limits) {
            if (limits.none { it.monthKey == month.string && it.category == cat }) dao.put(BudgetLimit(monthKey = month.string, category = cat, limitCents = cents))
        }
    }

    // Goals

    suspend fun saveGoal(g: SavingsGoal) = dao.put(g)
    suspend fun saveCost(c: TripCost) = dao.put(c)
    suspend fun deleteCost(c: TripCost) = dao.delete(c)
    suspend fun deleteEntry(e: GoalEntry) = dao.delete(e)

    suspend fun moveMoney(
        goal: SavingsGoal, cents: Long, withdraw: Boolean, date: Long, note: String, entries: List<GoalEntry>,
        affectsCash: Boolean = true, accountID: String? = null, accountAmountCents: Long = 0,
    ) = db.withTransaction {
        val e = LedgerRules.moveMoney(goal, cents, withdraw, date, note, entries, affectsCash, accountID, accountAmountCents)
        dao.put(e)
        e
    }

    suspend fun deleteGoal(goal: SavingsGoal, entries: List<GoalEntry>, costs: List<TripCost>) = db.withTransaction {
        val mine = entries.filter { it.goalID == goal.id }
        if (mine.isNotEmpty()) dao.delete(*mine.toTypedArray())
        val myCosts = costs.filter { it.goalID == goal.id }
        if (myCosts.isNotEmpty()) dao.delete(*myCosts.toTypedArray())
        dao.delete(goal)
    }

    // Subscriptions and bills

    suspend fun saveRule(r: RecurringRule) = dao.put(r)
    suspend fun deleteRule(r: RecurringRule) = dao.delete(r)

    suspend fun recordPayment(rule: RecurringRule, existing: List<TransactionRecord>, paidOn: Long, amountCents: Long?, accountID: String?) =
        db.withTransaction {
            val (tx, next) = LedgerRules.recordPayment(rule, paidOn, amountCents, accountID, existing)
            dao.put(tx); dao.put(next)
            tx
        }

    suspend fun skipPayment(rule: RecurringRule) = dao.put(LedgerRules.skipPayment(rule))
    suspend fun pause(rule: RecurringRule) = dao.put(LedgerRules.pause(rule))
    suspend fun resume(rule: RecurringRule, today: LocalDate = LocalDate.now()) = dao.put(LedgerRules.resume(rule, today))
    suspend fun cancel(rule: RecurringRule) = dao.put(LedgerRules.cancel(rule))

    // Reserved funds

    suspend fun saveReserved(f: ReservedFund) = dao.put(f)
    suspend fun deleteReserved(f: ReservedFund) = dao.delete(f)

    // Accounts, rates and transfers

    suspend fun saveAccount(a: Account) = dao.put(a)

    /** One rate per currency pair: saving a pair again replaces the old one. */
    suspend fun saveRate(from: String, to: String, micros: Long, existing: List<ExchangeRate>) = db.withTransaction {
        val old = existing.firstOrNull { it.fromCode == from && it.toCode == to }
        dao.put((old ?: ExchangeRate(fromCode = from, toCode = to)).copy(rateMicros = micros, updatedAt = System.currentTimeMillis()))
    }
    suspend fun deleteRate(r: ExchangeRate) = dao.delete(r)

    suspend fun transfer(from: Account, to: Account, sent: Long, received: Long, date: Long, title: String, notes: String) = db.withTransaction {
        val tx = LedgerRules.transfer(from, to, sent, received, date, title, notes)
        dao.put(tx)
        tx
    }

    /** One consistent read of everything the screens show (used where no screen is observing). */
    suspend fun ledgerNow(): LedgerState = dao.ledgerState().first()

    // Backup and restore

    suspend fun hasAnyRecords(): Boolean = dao.transactionCount() > 0 || dao.goalCount() > 0 || dao.shoppingCount() > 0

    /** Reads every table into one backup structure (the same data the iOS backup holds). */
    suspend fun snapshot(openingBalanceCents: Long, currencySymbol: String, reportingCurrency: String, nowMillis: Long): BackupFile = db.withTransaction {
        BackupFile(
            exportedAt = nowMillis, openingBalanceCents = openingBalanceCents, currencySymbol = currencySymbol,
            categories = dao.categories().first(), transactions = dao.transactions().first().sortedBy { it.date },
            monthBudgets = dao.monthBudgets().first(), budgetLimits = dao.budgetLimits().first(), plannedExpenses = dao.plannedExpenses().first(),
            shoppingItems = dao.shoppingItems().first(), goals = dao.goals().first(), goalEntries = dao.goalEntries().first().sortedBy { it.date },
            tripCosts = dao.tripCosts().first(), recurringRules = dao.recurringRules().first(), accounts = dao.accounts().first(),
            exchangeRates = dao.exchangeRates().first(), reportingCurrency = reportingCurrency, reservedFunds = dao.reservedFunds().first(),
            receipts = dao.receipts().first(),
        )
    }

    /**
     * Replaces every record with the file's, all or nothing: if anything fails, the transaction rolls back and nothing changes.
     * A file with no accounts (an old backup) gets a Main account holding its opening balance, so no record is left without one.
     */
    suspend fun restore(file: BackupFile, nowMillis: Long = System.currentTimeMillis()) = db.withTransaction {
        dao.clearTransactions(); dao.clearMonthBudgets(); dao.clearBudgetLimits(); dao.clearPlanned(); dao.clearShopping(); dao.clearGoalEntries()
        dao.clearTripCosts(); dao.clearGoals(); dao.clearRules(); dao.clearAccounts(); dao.clearRates(); dao.clearReserved(); dao.clearCategories(); dao.clearReceipts()
        val accounts = file.accounts?.takeIf { it.isNotEmpty() }
            ?: listOf(Account(name = "Main", kindRaw = "bank", currencyCode = file.reportingCurrency ?: "MYR", openingCents = file.openingBalanceCents, createdAt = nowMillis))
        dao.put(*accounts.toTypedArray())
        file.exchangeRates?.let { dao.put(*it.map { r -> r.copy(pk = 0) }.toTypedArray()) }
        file.reservedFunds?.let { dao.put(*it.toTypedArray()) }
        dao.put(*file.categories.map { it.copy(pk = 0) }.toTypedArray())
        dao.put(*file.transactions.toTypedArray())
        dao.put(*file.monthBudgets.map { it.copy(pk = 0) }.toTypedArray())
        dao.put(*file.budgetLimits.map { it.copy(pk = 0) }.toTypedArray())
        dao.put(*file.plannedExpenses.toTypedArray())
        dao.put(*file.shoppingItems.toTypedArray())
        dao.put(*file.goals.toTypedArray())
        dao.put(*file.goalEntries.toTypedArray())
        dao.put(*file.tripCosts.toTypedArray())
        file.recurringRules?.let { dao.put(*it.toTypedArray()) }
        file.receipts?.let { dao.put(*it.toTypedArray()) }
    }
}

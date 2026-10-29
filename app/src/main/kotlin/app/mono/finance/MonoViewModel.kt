package app.mono.finance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mono.domain.LedgerException
import app.mono.domain.MoneyContext
import app.mono.domain.MonthKey
import app.mono.domain.model.BudgetLimit
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.MonthBudget
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.ShoppingItem
import app.mono.domain.model.TransactionRecord
import app.mono.domain.model.TripCost
import app.mono.finance.data.LedgerState
import app.mono.finance.data.Repository
import app.mono.finance.data.ledgerState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One ViewModel for the money screens. Screens read [ledger] and send intents; no maths happens here. */
class MonoViewModel(app: Application) : AndroidViewModel(app) {
    private val mono = app as MonoApp
    private val dao = mono.db.dao()
    private val repo = Repository(mono.db)

    val ledger: StateFlow<LedgerState?> = dao.ledgerState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val moneyContext: StateFlow<MoneyContext> =
        mono.settings.moneyContext.stateIn(viewModelScope, SharingStarted.Eagerly, MoneyContext())

    /** One-line messages for a snackbar: confirmations and the reason something couldn't be saved. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Runs a write; a rule violation or database error becomes a message instead of a crash. */
    private fun run(failure: String, success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); success?.let { _messages.tryEmit(it) } }
            catch (e: LedgerException) { _messages.tryEmit(e.error.text) }
            catch (e: Exception) { _messages.tryEmit("$failure: ${e.message ?: "unexpected error"}") }
        }
    }

    fun toggleHidden() { viewModelScope.launch { mono.settings.setHidden(!moneyContext.value.hidden) } }

    // Transactions
    fun saveTransaction(t: TransactionRecord) = run("Couldn’t save the transaction") { repo.saveTransaction(t) }
    fun deleteTransaction(id: String) {
        val l = ledger.value ?: return
        val tx = l.transactions.firstOrNull { it.id == id } ?: return
        run("Couldn’t delete the transaction", "Transaction deleted") { repo.deleteTransaction(tx, l.items, l.planned) }
    }

    // Shopping
    fun saveItem(i: ShoppingItem) = run("Couldn’t save the item") { repo.saveItem(i) }
    fun deleteItems(items: List<ShoppingItem>) = run("Couldn’t delete") { repo.deleteItems(items) }
    fun recordPurchase(item: ShoppingItem) {
        val existing = ledger.value?.transactions ?: return
        run("Couldn’t record the purchase", "Recorded as an expense") { repo.recordPurchase(item, existing) }
    }
    fun recordPurchases(items: List<ShoppingItem>) {
        val existing = ledger.value?.transactions ?: return
        run("Couldn’t record the purchases", "Recorded ${items.size} as expenses") {
            var seen = existing
            for (i in items) { val tx = repo.recordPurchase(i, seen); seen = seen + tx }
        }
    }

    // Budget
    fun setMonthTotal(month: MonthKey, cents: Long?) {
        val existing = ledger.value?.monthBudgets?.firstOrNull { it.monthKey == month.string }
        run("Couldn’t save the budget") { repo.setMonthTotal(month, existing, cents) }
    }
    fun saveLimit(l: BudgetLimit) = run("Couldn’t save the limit") { repo.saveLimit(l) }
    fun deleteLimit(l: BudgetLimit) = run("Couldn’t delete the limit") { repo.deleteLimit(l) }
    fun savePlanned(p: PlannedExpense) = run("Couldn’t save") { repo.savePlanned(p) }
    fun deletePlanned(p: PlannedExpense) = run("Couldn’t delete") { repo.deletePlanned(p) }
    fun markPaid(p: PlannedExpense) {
        val existing = ledger.value?.transactions ?: return
        run("Couldn’t record the payment", "Recorded as an expense") { repo.markPaid(p, existing) }
    }
    fun copyBudget(month: MonthKey, reported: List<TransactionRecord>, carryOver: Boolean) {
        val l = ledger.value ?: return
        run("Couldn’t copy the budget", "Budget copied") { repo.copyBudget(month, l.monthBudgets, l.limits, reported, carryOver) }
    }

    // Goals
    fun saveGoal(g: SavingsGoal, message: String? = null) = run("Couldn’t save the goal", message) { repo.saveGoal(g) }
    fun saveCost(c: TripCost) = run("Couldn’t save the cost") { repo.saveCost(c) }
    fun deleteCost(c: TripCost) = run("Couldn’t delete the cost") { repo.deleteCost(c) }
    fun deleteEntry(e: GoalEntry) = run("Couldn’t delete the entry") { repo.deleteEntry(e) }
    fun deleteGoal(g: SavingsGoal) {
        val l = ledger.value ?: return
        run("Couldn’t delete the goal", "Goal deleted") { repo.deleteGoal(g, l.entries, l.costs) }
    }
    fun moveMoney(goal: SavingsGoal, cents: Long, withdraw: Boolean, date: Long, note: String, affectsCash: Boolean = true) {
        val entries = ledger.value?.entries ?: return
        run("Couldn’t move the money", if (withdraw) "Withdrawn from goal" else "Added to goal") {
            repo.moveMoney(goal, cents, withdraw, date, note, entries, affectsCash)
        }
    }
}

package app.mono.finance.data

import app.mono.domain.model.Account
import app.mono.domain.model.BudgetLimit
import app.mono.domain.model.ExchangeRate
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.MonthBudget
import app.mono.domain.model.MoneyCategory
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.ShoppingItem
import app.mono.domain.model.TripCost
import app.mono.domain.model.TransactionRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Everything the money screens read, observed together so every screen sees one consistent snapshot. */
data class LedgerState(
    val transactions: List<TransactionRecord>,
    val categories: List<MoneyCategory>,
    val accounts: List<Account>,
    val rates: List<ExchangeRate>,
    val entries: List<GoalEntry>,
    val goals: List<SavingsGoal>,
    val monthBudgets: List<MonthBudget>,
    val limits: List<BudgetLimit>,
    val planned: List<PlannedExpense>,
    val items: List<ShoppingItem>,
    val costs: List<TripCost>,
)

@Suppress("UNCHECKED_CAST")
fun MonoDao.ledgerState(): Flow<LedgerState> = combine(
    transactions(), categories(), accounts(), exchangeRates(), goalEntries(), goals(), monthBudgets(), budgetLimits(), plannedExpenses(), shoppingItems(), tripCosts(),
) { a ->
    LedgerState(
        a[0] as List<TransactionRecord>, a[1] as List<MoneyCategory>, a[2] as List<Account>, a[3] as List<ExchangeRate>,
        a[4] as List<GoalEntry>, a[5] as List<SavingsGoal>, a[6] as List<MonthBudget>, a[7] as List<BudgetLimit>,
        a[8] as List<PlannedExpense>, a[9] as List<ShoppingItem>, a[10] as List<TripCost>,
    )
}

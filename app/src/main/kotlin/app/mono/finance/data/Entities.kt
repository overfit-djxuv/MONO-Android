package app.mono.finance.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/*
 * One table per SwiftData model in the iOS app, with the same field names and defaults.
 * IDs are UUID strings, dates are epoch milliseconds (UTC), enums are stored as their iOS raw strings,
 * so a record keeps its identity and meaning across platforms. Every new column needs a default.
 */

private fun newId() = UUID.randomUUID().toString()

@Entity(tableName = "MoneyCategory", indices = [Index("name", "kindRaw")])
data class MoneyCategory(
    @PrimaryKey(autoGenerate = true) val pk: Long = 0,
    val name: String = "",
    val symbol: String = "tag",
    val kindRaw: String = "expense",
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    /** Essential spending: its remaining budget is held back by Safe to spend. */
    val isEssential: Boolean = false,
)

@Entity(tableName = "TransactionRecord", indices = [Index("date"), Index("accountID"), Index("occurrenceKey")])
data class TransactionRecord(
    @PrimaryKey val id: String = newId(),
    val amountCents: Long = 0,
    val kindRaw: String = "expense",
    val category: String = "",
    val date: Long = 0,
    val title: String = "",
    val notes: String = "",
    /** The shopping item or planned expense this was generated from (duplicate guard). */
    val sourceID: String? = null,
    /** The account this belongs to; for a transfer, the account the money left. null means the default account. */
    val accountID: String? = null,
    /** Transfers only: the account the money arrived in, and how much arrived in that account's own currency. */
    val toAccountID: String? = null,
    val toAmountCents: Long = 0,
    val merchant: String = "",
    /** Comma-separated, lower-case tags. */
    val tags: String = "",
    /** "<rule id>|<due date>" when this records a scheduled payment; one key can exist only once. */
    val occurrenceKey: String = "",
)

@Entity(tableName = "MonthBudget", indices = [Index("monthKey", unique = true)])
data class MonthBudget(
    @PrimaryKey(autoGenerate = true) val pk: Long = 0,
    val monthKey: String = "",
    val totalCents: Long = 0,
)

@Entity(tableName = "BudgetLimit", indices = [Index("monthKey", "category", unique = true)])
data class BudgetLimit(
    @PrimaryKey(autoGenerate = true) val pk: Long = 0,
    val monthKey: String = "",
    val category: String = "",
    val limitCents: Long = 0,
)

@Entity(tableName = "PlannedExpense")
data class PlannedExpense(
    @PrimaryKey val id: String = newId(),
    val title: String = "",
    val amountCents: Long = 0,
    val dueDate: Long = 0,
    val category: String = "",
    val isPaid: Boolean = false,
    val paidTransactionID: String? = null,
)

@Entity(tableName = "ShoppingItem")
data class ShoppingItem(
    @PrimaryKey val id: String = newId(),
    val name: String = "",
    val quantity: Int = 1,
    /** Prices are per unit. */
    val estimatedCents: Long = 0,
    val actualCents: Long? = null,
    val category: String = "Groceries",
    val priorityRaw: Int = 1,
    val isPurchased: Boolean = false,
    val listKindRaw: String = "grocery",
    val expenseID: String? = null,
    val createdAt: Long = 0,
    val purchasedAt: Long? = null,
)

@Entity(tableName = "SavingsGoal")
data class SavingsGoal(
    @PrimaryKey val id: String = newId(),
    val name: String = "",
    val kindRaw: String = "general",
    val symbol: String = "target",
    /** Target for general goals. Trips derive their target from costs + buffer. */
    val targetCents: Long = 0,
    val deadline: Long? = null,
    val destination: String = "",
    val bufferCents: Long = 0,
    val plannedMonthlyCents: Long = 0,
    /** Day of month on which planned contributions are made (1-28). */
    val contributionDay: Int = 1,
    val createdAt: Long = 0,
    val details: String = "",
    val priorityRaw: Int = 1,
    /** Empty means "the reporting currency". */
    val currencyCode: String = "",
    val frequencyRaw: String = "monthly",
    val startDate: Long? = null,
    /** Set when the goal is marked completed; money already set aside stays where it is. */
    val completedAt: Long? = null,
    val isArchived: Boolean = false,
)

/** A signed movement into (+) or out of (-) a goal. */
@Entity(tableName = "GoalEntry", indices = [Index("goalID")])
data class GoalEntry(
    @PrimaryKey val id: String = newId(),
    val goalID: String = "",
    val amountCents: Long = 0,
    val date: Long = 0,
    val note: String = "",
    /** False for money that was already saved before using MONO (does not reduce available cash). */
    val affectsCash: Boolean = true,
    val accountID: String? = null,
    /** When the account's currency differs from the goal's: how much the account paid, in its own currency. 0 = same as amountCents. */
    val accountAmountCents: Long = 0,
)

@Entity(tableName = "TripCost", indices = [Index("goalID")])
data class TripCost(
    @PrimaryKey val id: String = newId(),
    val goalID: String = "",
    val categoryRaw: String = "other",
    val label: String = "",
    val amountCents: Long = 0,
)

/** A schedule (subscription, bill, repeating income), not a transaction: nothing is recorded until you confirm a payment. */
@Entity(tableName = "RecurringRule")
data class RecurringRule(
    @PrimaryKey val id: String = newId(),
    val title: String = "",
    val amountCents: Long = 0,
    val kindRaw: String = "expense",
    val category: String = "",
    val dayOfMonth: Int = 1,
    val nextMonthKey: String = "",
    val isActive: Boolean = true,
    val unitRaw: String = "month",
    val interval: Int = 1,
    val nextDueDate: Long? = null,
    val isCancelled: Boolean = false,
    val notes: String = "",
    val accountID: String? = null,
    val remindDaysBefore: Int = 1,
)

/** Where money lives. Balances are always in the account's own currency. */
@Entity(tableName = "Account")
data class Account(
    @PrimaryKey val id: String = newId(),
    val name: String = "",
    val kindRaw: String = "bank",
    val currencyCode: String = "MYR",
    val openingCents: Long = 0,
    val sortOrder: Int = 0,
    /** Archived accounts are hidden from pickers but their balance still counts. */
    val isArchived: Boolean = false,
    val createdAt: Long = 0,
)

/** 1 `fromCode` = `rateMicros / 1,000,000` `toCode`. The app never fetches rates. */
@Entity(tableName = "ExchangeRate", indices = [Index("fromCode", "toCode", unique = true)])
data class ExchangeRate(
    @PrimaryKey(autoGenerate = true) val pk: Long = 0,
    val fromCode: String = "",
    val toCode: String = "",
    val rateMicros: Long = 0,
    val updatedAt: Long = 0,
)

/** Money kept out of Safe to spend even though it sits in an account. Amounts are in the reporting currency. */
@Entity(tableName = "ReservedFund")
data class ReservedFund(
    @PrimaryKey val id: String = newId(),
    val name: String = "",
    val amountCents: Long = 0,
    val isActive: Boolean = true,
    val createdAt: Long = 0,
)

/** A captured receipt; the photo lives in a file, everything here is searchable, editable text. */
@Entity(tableName = "Receipt", indices = [Index("transactionID")])
data class Receipt(
    @PrimaryKey val id: String = newId(),
    val createdAt: Long = 0,
    val merchant: String = "",
    val date: Long = 0,
    val totalCents: Long = 0,
    val taxCents: Long = 0,
    val currencyCode: String = "",
    val languageRaw: String = "auto",
    val imageFile: String = "",
    val imageHash: String = "",
    val ocrText: String = "",
    /** JSON of possible line items found by the scan. Informational only. */
    val itemsJSON: String = "",
    val notes: String = "",
    val category: String = "",
    val transactionID: String? = null,
    val needsReview: Boolean = false,
)

/** Categories seeded on first launch, in the same order as iOS. */
object DefaultCategories {
    val expense = listOf(
        "Food" to "fork.knife", "Groceries" to "cart", "Transportation" to "car",
        "Education" to "book", "Shopping" to "bag", "Entertainment" to "film",
        "Subscriptions" to "arrow.triangle.2.circlepath", "Travel" to "airplane",
        "Health" to "cross.case", "Bills" to "doc.text", "Other" to "ellipsis",
    )
    val income = listOf(
        "Salary" to "banknote", "Freelance" to "laptopcomputer", "Gift" to "gift", "Other Income" to "plus.circle",
    )
    /** Spending most people cannot skip. Only a starting point: every category's switch is yours to change. */
    val essentials = setOf("Groceries", "Transportation", "Bills", "Health")

    fun seed(): List<MoneyCategory> =
        expense.mapIndexed { i, (n, s) ->
            MoneyCategory(name = n, symbol = s, kindRaw = "expense", isDefault = true, sortOrder = i, isEssential = n in essentials)
        } + income.mapIndexed { i, (n, s) ->
            MoneyCategory(name = n, symbol = s, kindRaw = "income", isDefault = true, sortOrder = 100 + i)
        }
}

package app.mono.finance.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.mono.domain.model.*
import kotlinx.coroutines.flow.Flow

/** Stage 1 surface: observe every table and insert/replace rows. Targeted queries arrive with each screen. */
@Dao
interface MonoDao {
    @Query("SELECT * FROM MoneyCategory ORDER BY sortOrder") fun categories(): Flow<List<MoneyCategory>>
    @Query("SELECT * FROM TransactionRecord ORDER BY date DESC") fun transactions(): Flow<List<TransactionRecord>>
    @Query("SELECT * FROM MonthBudget") fun monthBudgets(): Flow<List<MonthBudget>>
    @Query("SELECT * FROM BudgetLimit") fun budgetLimits(): Flow<List<BudgetLimit>>
    @Query("SELECT * FROM PlannedExpense ORDER BY dueDate") fun plannedExpenses(): Flow<List<PlannedExpense>>
    @Query("SELECT * FROM ShoppingItem ORDER BY createdAt") fun shoppingItems(): Flow<List<ShoppingItem>>
    @Query("SELECT * FROM SavingsGoal") fun goals(): Flow<List<SavingsGoal>>
    @Query("SELECT * FROM GoalEntry ORDER BY date") fun goalEntries(): Flow<List<GoalEntry>>
    @Query("SELECT * FROM TripCost") fun tripCosts(): Flow<List<TripCost>>
    @Query("SELECT * FROM RecurringRule") fun recurringRules(): Flow<List<RecurringRule>>
    @Query("SELECT * FROM Account ORDER BY sortOrder") fun accounts(): Flow<List<Account>>
    @Query("SELECT * FROM ExchangeRate") fun exchangeRates(): Flow<List<ExchangeRate>>
    @Query("SELECT * FROM ReservedFund") fun reservedFunds(): Flow<List<ReservedFund>>
    @Query("SELECT * FROM Receipt ORDER BY createdAt DESC") fun receipts(): Flow<List<Receipt>>

    @Query("SELECT COUNT(*) FROM MoneyCategory") suspend fun categoryCount(): Int
    @Query("SELECT COUNT(*) FROM Account") suspend fun accountCount(): Int
    @Query("SELECT COUNT(*) FROM TransactionRecord") suspend fun transactionCount(): Int
    @Query("SELECT * FROM TransactionRecord ORDER BY rowid DESC LIMIT 1") suspend fun latestTransaction(): TransactionRecord

    @Delete suspend fun delete(row: TransactionRecord)
    @Query("DELETE FROM TransactionRecord WHERE id = :id") suspend fun deleteTransaction(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: MoneyCategory)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: TransactionRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: MonthBudget)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: BudgetLimit)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: PlannedExpense)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: ShoppingItem)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: SavingsGoal)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: GoalEntry)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: TripCost)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: RecurringRule)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: Account)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: ExchangeRate)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: ReservedFund)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vararg row: Receipt)
}

@Database(
    entities = [
        MoneyCategory::class, TransactionRecord::class, MonthBudget::class, BudgetLimit::class, PlannedExpense::class,
        ShoppingItem::class, SavingsGoal::class, GoalEntry::class, TripCost::class, RecurringRule::class,
        Account::class, ExchangeRate::class, ReservedFund::class, Receipt::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MonoDatabase : RoomDatabase() {
    abstract fun dao(): MonoDao

    companion object {
        fun open(context: Context): MonoDatabase =
            Room.databaseBuilder(context.applicationContext, MonoDatabase::class.java, "mono.db").build()
    }
}

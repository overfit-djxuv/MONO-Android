package app.mono.finance.data

import app.mono.domain.MonthKey
import app.mono.domain.model.BudgetLimit
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.ShoppingItem
import app.mono.domain.model.TripCost
import app.mono.domain.model.MonthBudget
import app.mono.domain.model.TransactionRecord
import java.time.LocalDate
import java.time.ZoneId

/** Sample records for screenshots and manual checks. Only reachable from debuggable builds (see MainActivity). */
object DemoData {
    suspend fun load(dao: MonoDao) {
        loadTransactions(dao)
        loadGoalsAndLists(dao)
    }

    private suspend fun loadGoalsAndLists(dao: MonoDao) {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.now()
        fun ms(d: LocalDate) = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        if (dao.goalCount() == 0) {
            val trip = SavingsGoal(name = "Tokyo trip", kindRaw = "trip", symbol = "airplane", destination = "Japan", bufferCents = 50_000,
                deadline = ms(now.plusMonths(8)), plannedMonthlyCents = 60_000, priorityRaw = 2, createdAt = ms(now.minusMonths(2)))
            val laptop = SavingsGoal(name = "New laptop", kindRaw = "general", symbol = "laptopcomputer", targetCents = 600_000,
                deadline = ms(now.plusMonths(5)), plannedMonthlyCents = 90_000, createdAt = ms(now.minusMonths(1)))
            val fund = SavingsGoal(name = "Emergency fund", kindRaw = "general", symbol = "target", targetCents = 1_500_000, createdAt = ms(now.minusMonths(3)))
            dao.put(trip, laptop, fund)
            dao.put(TripCost(goalID = trip.id, categoryRaw = "flights", amountCents = 220_000), TripCost(goalID = trip.id, categoryRaw = "accommodation", amountCents = 180_000),
                TripCost(goalID = trip.id, categoryRaw = "food", label = "Street food", amountCents = 80_000))
            dao.put(GoalEntry(goalID = trip.id, amountCents = 120_000, date = ms(now.minusMonths(2))), GoalEntry(goalID = trip.id, amountCents = 60_000, date = ms(now.minusMonths(1))),
                GoalEntry(goalID = laptop.id, amountCents = 150_000, date = ms(now.minusDays(20))), GoalEntry(goalID = fund.id, amountCents = 200_000, date = ms(now.minusMonths(3)), affectsCash = false))
            dao.put(PlannedExpense(title = "Car insurance", amountCents = 95_000, dueDate = ms(now.plusDays(9)), category = "Bills"),
                PlannedExpense(title = "Dentist", amountCents = 30_000, dueDate = ms(now.minusDays(2)), category = "Health"))
        }
        if (dao.shoppingCount() == 0) {
            dao.put(ShoppingItem(name = "Eggs", quantity = 2, estimatedCents = 1_200, category = "Groceries", priorityRaw = 2, createdAt = 1),
                ShoppingItem(name = "Rice 5kg", quantity = 1, estimatedCents = 2_600, category = "Groceries", createdAt = 2),
                ShoppingItem(name = "Milk", quantity = 3, estimatedCents = 800, actualCents = 900, isPurchased = true, category = "Groceries", createdAt = 3, purchasedAt = 3),
                ShoppingItem(name = "Headphones", quantity = 1, estimatedCents = 25_000, category = "Shopping", listKindRaw = "electronics", createdAt = 4))
        }
    }

    private suspend fun loadTransactions(dao: MonoDao) {
        if (dao.transactionCount() > 0) return
        val zone = ZoneId.systemDefault()
        val now = LocalDate.now()
        fun at(monthsAgo: Int, day: Int): Long {
            val m = now.minusMonths(monthsAgo.toLong())
            return m.withDayOfMonth(minOf(day, m.lengthOfMonth())).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        }
        val rows = mutableListOf<TransactionRecord>()
        for (back in 0..4) {
            rows += TransactionRecord(amountCents = 450_000 + back * 15_000L, kindRaw = "income", category = "Salary", title = "Salary", date = at(back, 1))
            rows += TransactionRecord(amountCents = 120_000, kindRaw = "expense", category = "Bills", title = "Rent share", date = at(back, 3))
            rows += TransactionRecord(amountCents = 38_000 + back * 2_100L, kindRaw = "expense", category = "Groceries", title = "Groceries", date = at(back, 6))
            rows += TransactionRecord(amountCents = 24_500, kindRaw = "expense", category = "Food", title = "Dinner out", date = at(back, 12))
            rows += TransactionRecord(amountCents = 9_900, kindRaw = "expense", category = "Subscriptions", title = "Streaming", date = at(back, 15))
            rows += TransactionRecord(amountCents = 15_000 + back * 1_000L, kindRaw = "expense", category = "Transportation", title = "Fuel", date = at(back, 18))
        }
        rows += TransactionRecord(amountCents = 7_800, kindRaw = "expense", category = "Entertainment", title = "Cinema", date = at(0, now.dayOfMonth.coerceAtMost(9)))
        dao.put(*rows.toTypedArray())
        val key = MonthKey.of(now).string
        dao.put(MonthBudget(monthKey = key, totalCents = 300_000))
        dao.put(BudgetLimit(monthKey = key, category = "Groceries", limitCents = 80_000))
        dao.put(BudgetLimit(monthKey = key, category = "Food", limitCents = 40_000))
    }
}

package app.mono.finance.data

import app.mono.domain.MonthKey
import app.mono.domain.model.BudgetLimit
import app.mono.domain.model.MonthBudget
import app.mono.domain.model.TransactionRecord
import java.time.LocalDate
import java.time.ZoneId

/** Sample records for screenshots and manual checks. Only reachable from debuggable builds (see MainActivity). */
object DemoData {
    suspend fun load(dao: MonoDao) {
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

package app.mono.domain

import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.RecurringRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** What to remind about and when. Pure: the app turns these into notifications. Local only: nothing leaves the phone. */
object Reminders {
    const val MAX_PLANNED = 15
    const val MAX_SUBSCRIPTIONS = 12

    data class Pending(val id: String, val title: String, val body: String, val fire: LocalDateTime)

    private fun day(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** One reminder the morning before and one on the day, for unpaid expenses that haven't passed. */
    fun plannedReminders(
        planned: List<PlannedExpense>, now: LocalDateTime, hour: Int, zone: ZoneId = ZoneId.systemDefault(), money: (Long) -> String,
    ): List<Pending> {
        val today = now.toLocalDate()
        val out = ArrayList<Pending>()
        for (p in planned.filter { !it.isPaid && !day(it.dueDate, zone).isBefore(today) }.sortedBy { it.dueDate }.take(MAX_PLANNED)) {
            val due = day(p.dueDate, zone)
            val amount = money(p.amountCents)
            val title = p.title.ifEmpty { "Planned expense" }
            val eve = due.minusDays(1).atTime(hour, 0)
            if (eve.isAfter(now)) out += Pending("planned-${p.id}-eve", title, "$amount is due tomorrow.", eve)
            val onDay = due.atTime(hour, 0)
            if (onDay.isAfter(now)) out += Pending("planned-${p.id}-due", title, "$amount is due today.", onDay)
        }
        return out
    }

    /** Reminders for subscriptions and bills: the next two payments of each active one, `remindDaysBefore` days ahead. */
    fun subscriptionReminders(
        rules: List<RecurringRule>, now: LocalDateTime, hour: Int, zone: ZoneId = ZoneId.systemDefault(), money: (RecurringRule, Long) -> String,
    ): List<Pending> {
        val out = ArrayList<Pending>()
        for (r in rules.filter { it.status == PaymentStatus.ACTIVE && it.kind == TxKind.EXPENSE }.sortedBy { it.dueDate(zone) }.take(MAX_SUBSCRIPTIONS)) {
            var due = r.dueDate(zone)
            var scheduled = 0
            var guard = 0
            while (scheduled < 2 && guard < 6) {
                guard++
                val lead = maxOf(r.remindDaysBefore, 0)
                val fire = due.minusDays(lead.toLong()).atTime(hour, 0)
                if (fire.isAfter(now)) {
                    val whenText = when (lead) { 0 -> "is due today"; 1 -> "is due tomorrow"; else -> "is due in $lead days" }
                    out += Pending("sub-${r.id}-${due.year}${due.monthValue}${due.dayOfMonth}", r.title.ifEmpty { "Payment due" },
                        "${money(r, r.amountCents)} $whenText. Open the app to confirm it when you’ve paid.", fire)
                    scheduled++
                }
                due = r.following(due)
            }
        }
        return out
    }

    /** 0 = fine, 1 = 80% used, 2 = over budget. */
    fun budgetLevel(fraction: Double?): Int = when { fraction == null -> 0; fraction > 1 -> 2; fraction >= 0.8 -> 1; else -> 0 }

    /**
     * One alert when spending crosses 80% or 100% of the month's budget, never twice for the same level. [known] is the level
     * already announced; the new level is returned to store. If spending falls back below a level, it can alert again later.
     */
    fun checkBudget(s: BudgetSummary, known: Int, money: (Long) -> String): Pair<Int, String?> {
        if (s.total == null) return known to null
        val level = budgetLevel(s.fraction)
        if (level <= known) return level to null
        val message = if (level == 2) "You’re ${money(s.overBy)} over your ${s.month.shortTitle()} budget."
        else "${Math.round((s.fraction ?: 0.0) * 100)}% of your ${s.month.shortTitle()} budget is used" +
            (s.dailyAllowance?.let { ". About ${money(it)} a day keeps you within it." } ?: ".")
        return level to message
    }
}

/** Things you can start straight from the launcher icon's long-press menu. */
enum class QuickAction(val raw: String, val title: String) {
    ADD_EXPENSE("addExpense", "Add expense"),
    SCAN_RECEIPT("scanReceipt", "Scan receipt"),
    ADD_INCOME("addIncome", "Add income"),
    TRANSFER("transfer", "Transfer"),
    SAFE_TO_SPEND("safeToSpend", "Safe to spend");

    companion object {
        fun of(raw: String) = entries.firstOrNull { it.raw == raw }
    }
}

/** Which actions the icon shows, and in what order. Android shows up to four. */
object QuickActionSettings {
    const val MAX_SHOWN = 4

    /** null (never set) shows every action. Unknown names and duplicates are ignored. */
    fun enabled(raw: String?): List<QuickAction> =
        if (raw == null) QuickAction.entries else raw.split(',').mapNotNull { QuickAction.of(it) }.distinct()

    fun serialize(actions: List<QuickAction>): String = actions.joinToString(",") { it.raw }

    fun toggle(raw: String?, action: QuickAction, on: Boolean): String {
        val list = enabled(raw).toMutableList()
        if (on) { if (action !in list) list += action } else list.remove(action)
        return serialize(list)
    }

    fun shown(raw: String?): List<QuickAction> = enabled(raw).take(MAX_SHOWN)
}

package app.mono.domain

import app.mono.domain.model.Account
import app.mono.domain.model.TransactionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The Transactions list's period, type, category, account, tag, amount and text filters, kept out of the UI so they can be tested. */
data class TransactionFilter(
    val period: Period = Period.THIS_MONTH,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null,
    val kind: TxKind? = null,
    val category: String? = null,
    val accountID: String? = null,
    val tag: String? = null,
    val minCents: Long? = null,
    val maxCents: Long? = null,
    val search: String = "",
) {
    enum class Period(val label: String) {
        ALL("All time"), THIS_MONTH("This month"), LAST_MONTH("Last month"), LAST3("Last 3 months"), THIS_YEAR("This year"), CUSTOM("Custom range");

        fun contains(day: LocalDate, today: LocalDate, from: LocalDate? = null, to: LocalDate? = null): Boolean {
            val cur = MonthKey.of(today); val k = MonthKey.of(day)
            return when (this) {
                ALL -> true
                THIS_MONTH -> k == cur
                LAST_MONTH -> k == cur.adding(-1)
                LAST3 -> k >= cur.adding(-2) && k <= cur
                THIS_YEAR -> k.year == cur.year && k <= cur
                CUSTOM -> (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to))
            }
        }
    }

    /** True when anything other than the default "This month" view is set (search excluded, it has its own box). */
    val active: Boolean get() = kind != null || category != null || accountID != null || tag != null || minCents != null || maxCents != null || period != Period.THIS_MONTH

    fun cleared() = copy(period = Period.THIS_MONTH, kind = null, category = null, accountID = null, tag = null, minCents = null, maxCents = null)

    fun apply(txs: List<TransactionRecord>, accounts: List<Account>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<TransactionRecord> {
        val dir = Books(accounts, emptyList(), emptyList(), emptyList())
        val q = search.trim().lowercase()
        return txs.filter { t ->
            val day = Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate()
            val from = dir.account(t.accountID); val to = t.toAccountID?.let { id -> accounts.firstOrNull { it.id == id } }
            (kind == null || t.kind == kind) && (category == null || t.category == category) &&
                (accountID == null || from?.id == accountID || to?.id == accountID) &&
                (tag == null || tag in t.tagList) &&
                (minCents == null || t.amountCents >= minCents) && (maxCents == null || t.amountCents <= maxCents) &&
                period.contains(day, today, customFrom, customTo) &&
                (q.isEmpty() || listOf(t.title, t.category, t.merchant, t.tags, t.notes, from?.name ?: "", to?.name ?: "").any { it.lowercase().contains(q) })
        }
    }

    companion object {
        /** Records that look like the same purchase entered twice: same type, amount, day, account and name. */
        fun duplicateIDs(txs: List<TransactionRecord>, zone: ZoneId = ZoneId.systemDefault()): Set<String> =
            txs.filter { it.kind != TxKind.TRANSFER }.groupBy { t ->
                listOf(t.kindRaw, t.amountCents, Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate(), t.accountID ?: "", (t.merchant.ifEmpty { t.title }).lowercase())
            }.values.filter { it.size > 1 }.flatMap { g -> g.map { it.id } }.toSet()
    }
}

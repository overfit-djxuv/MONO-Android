package app.mono.domain

import app.mono.domain.model.Account
import app.mono.domain.model.ExchangeRate
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.TransactionRecord

/** Balances across accounts. Pure functions: every "how much money do I have" number comes from here. */
object AccountEngine {
    private val order = compareBy<Account>({ it.sortOrder }, { it.createdAt })

    /** The account used for records created before accounts existed, and the default for new entries. */
    fun defaultAccount(accounts: List<Account>): Account? {
        val ordered = accounts.sortedWith(order)
        return ordered.firstOrNull { !it.isArchived } ?: ordered.firstOrNull()
    }

    /**
     * Balance in the account's own currency:
     * opening + income - expenses - transfers out + transfers in - money moved into goals from this account.
     * Records with no account belong to the default account.
     */
    fun balance(account: Account, defaultID: String?, transactions: List<TransactionRecord>, entries: List<GoalEntry>): Long {
        var b = account.openingCents
        for (t in transactions) {
            val from = t.accountID ?: defaultID
            when (t.kind) {
                TxKind.INCOME -> if (from == account.id) b += t.amountCents
                TxKind.EXPENSE -> if (from == account.id) b -= t.amountCents
                TxKind.TRANSFER -> {
                    if (from == account.id) b -= t.amountCents
                    if (t.toAccountID == account.id) b += t.toAmountCents
                }
            }
        }
        for (e in entries) if (e.affectsCash && (e.accountID ?: defaultID) == account.id) b -= e.accountDelta
        return b
    }

    data class Overview(
        /** Each account's balance in its own currency. */
        val balances: Map<String, Long>,
        /** Sum of every convertible account, in the reporting currency. */
        val total: Long,
        /** Accounts whose currency has no exchange rate yet. Their money is NOT in [total]. */
        val unconverted: List<String>,
    )

    /**
     * With no accounts at all, fall back to the original single-balance formula using [legacyOpening],
     * so the figure on screen is never wrong while accounts are missing.
     */
    fun overview(
        accounts: List<Account>, transactions: List<TransactionRecord>, entries: List<GoalEntry>,
        converter: CurrencyConverter, legacyOpening: Long = 0,
    ): Overview {
        if (accounts.isEmpty()) {
            return Overview(emptyMap(), FinanceEngine.availableCash(legacyOpening, transactions, entries), emptyList())
        }
        val defaultID = defaultAccount(accounts)?.id
        val balances = LinkedHashMap<String, Long>()
        var total = 0L
        val missing = ArrayList<String>()
        for (a in accounts) {
            val b = balance(a, defaultID, transactions, entries)
            balances[a.id] = b
            val converted = converter.convert(b, a.currency)
            if (converted != null) total += converted else missing += a.id
        }
        return Overview(balances, total, missing)
    }
}

object Reporting {
    data class Result(val records: List<TransactionRecord>, val skipped: Int)

    /**
     * Income and expense records expressed in the reporting currency, ready for the analytics.
     * Transfers are left out on purpose. Records in a currency with no exchange rate are not guessed at; they are counted in `skipped`.
     */
    fun reportable(txs: List<TransactionRecord>, accounts: List<Account>, converter: CurrencyConverter): Result {
        val real = txs.filter { it.kind != TxKind.TRANSFER }
        val foreign = accounts.any { it.currencyCode != converter.reporting.code }
        if (!foreign) return Result(real, 0)
        val defaultID = AccountEngine.defaultAccount(accounts)?.id
        val byID = accounts.associateBy { it.id }
        val out = ArrayList<TransactionRecord>()
        var skipped = 0
        for (t in real) {
            val account = (t.accountID ?: defaultID)?.let { byID[it] }
            if (account == null || account.currencyCode == converter.reporting.code) { out += t; continue }
            val cents = converter.convert(t.amountCents, account.currency)
            if (cents == null || cents <= 0) { skipped++; continue }
            // A detached copy used only for calculation; it is never stored.
            out += t.copy(amountCents = cents)
        }
        return Result(out, skipped)
    }
}

object Tags {
    const val MAX_TAGS = 12
    const val MAX_LENGTH = 24

    /** "Coffee, #Work  coffee" -> "coffee,work". Lower-case, de-duplicated, order kept. */
    fun normalize(text: String): String = list(text).joinToString(",")

    fun list(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in text.split(',', ' ', '\n', ';')) {
            val t = raw.trim('#').lowercase()
            if (t.isEmpty()) continue
            seen += t.take(MAX_LENGTH)
            if (seen.size == MAX_TAGS) break
        }
        return seen.toList()
    }
}

/** A read-only snapshot of "how the money stands", built from the stored accounts, exchange rates and records. */
class Books(
    val accounts: List<Account>,
    rates: List<ExchangeRate>,
    transactions: List<TransactionRecord>,
    entries: List<GoalEntry>,
    legacyOpening: Long = 0,
    reporting: Currency = Money.reporting,
) {
    val converter = CurrencyConverter(reporting, rates.map { RateEntry(it.fromCode, it.toCode, it.rateMicros) })
    val overview = AccountEngine.overview(accounts, transactions, entries, converter, legacyOpening)
    private val reportable = Reporting.reportable(transactions, accounts, converter)

    /** Income and expense records in the reporting currency (transfers excluded). Feed these to the analytics. */
    val reported: List<TransactionRecord> get() = reportable.records
    /** Records left out of [reported] because their currency has no exchange rate yet. */
    val skipped: Int get() = reportable.skipped

    /** Accounts to offer in pickers, in the user's order. */
    val active: List<Account> get() = accounts.filter { !it.isArchived }.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
    val defaultAccount: Account? get() = AccountEngine.defaultAccount(accounts)
    val hasSeveralAccounts: Boolean get() = active.size > 1

    fun account(id: String?): Account? {
        val key = id ?: defaultAccount?.id ?: return null
        return accounts.firstOrNull { it.id == key }
    }

    fun balance(account: Account): Long = overview.balances[account.id] ?: 0
    fun reportingBalance(account: Account): Long? = converter.convert(balance(account), account.currency)
    fun needsRate(account: Account): Boolean = !converter.hasRate(account.currency)

    /** What is saved across all goals, in the reporting currency. A goal with no exchange rate is left out and named. */
    fun savedTotal(goals: List<SavingsGoal>, entries: List<GoalEntry>): Pair<Long, List<String>> {
        var total = 0L
        val missing = ArrayList<String>()
        for (g in goals) {
            val c = converter.convert(FinanceEngine.balance(g.id, entries), g.currency)
            if (c != null) total += c else missing += g.name
        }
        return total to missing
    }

    /** Goal movements in the reporting currency, for charts. Movements of a goal with no exchange rate are skipped. */
    fun reportableEntries(goals: List<SavingsGoal>, entries: List<GoalEntry>): List<GoalEntry> {
        val byID = goals.associateBy { it.id }
        return entries.mapNotNull { e ->
            val g = byID[e.goalID] ?: return@mapNotNull e
            if (g.currency.code == converter.reporting.code) return@mapNotNull e
            val cents = converter.convert(e.amountCents, g.currency) ?: return@mapNotNull null
            e.copy(amountCents = cents)
        }
    }
}

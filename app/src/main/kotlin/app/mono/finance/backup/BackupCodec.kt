package app.mono.finance.backup

import app.mono.domain.AccountKind
import app.mono.domain.GoalKind
import app.mono.domain.ListKind
import app.mono.domain.Money
import app.mono.domain.MonthKey
import app.mono.domain.Priority
import app.mono.domain.RecurrenceUnit
import app.mono.domain.ContributionFrequency
import app.mono.domain.TripCostCategory
import app.mono.domain.TxKind
import app.mono.domain.model.Account
import app.mono.domain.model.BudgetLimit
import app.mono.domain.model.ExchangeRate
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.MoneyCategory
import app.mono.domain.model.MonthBudget
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.Receipt
import app.mono.domain.model.RecurringRule
import app.mono.domain.model.ReservedFund
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.ShoppingItem
import app.mono.domain.model.TransactionRecord
import app.mono.domain.model.TripCost
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Everything in a MONO backup, in the same shape as the iOS `BackupFile` (format 6). Dates are epoch milliseconds in memory. */
data class BackupFile(
    val app: String = "MONO",
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long = 0,
    val openingBalanceCents: Long = 0,
    val currencySymbol: String = "RM",
    val categories: List<MoneyCategory> = emptyList(),
    val transactions: List<TransactionRecord> = emptyList(),
    val monthBudgets: List<MonthBudget> = emptyList(),
    val budgetLimits: List<BudgetLimit> = emptyList(),
    val plannedExpenses: List<PlannedExpense> = emptyList(),
    val shoppingItems: List<ShoppingItem> = emptyList(),
    val goals: List<SavingsGoal> = emptyList(),
    val goalEntries: List<GoalEntry> = emptyList(),
    val tripCosts: List<TripCost> = emptyList(),
    /** Optional in the format so backups made before these existed still restore. */
    val recurringRules: List<RecurringRule>? = null,
    val accounts: List<Account>? = null,
    val exchangeRates: List<ExchangeRate>? = null,
    val reportingCurrency: String? = null,
    val reservedFunds: List<ReservedFund>? = null,
    val receipts: List<Receipt>? = null,
) {
    val recordCount: Int
        get() = transactions.size + monthBudgets.size + budgetLimits.size + plannedExpenses.size + shoppingItems.size + goals.size +
            goalEntries.size + tripCosts.size + (recurringRules?.size ?: 0) + (accounts?.size ?: 0) + (reservedFunds?.size ?: 0)

    companion object { const val CURRENT_VERSION = 6 }
}

class BackupException(message: String) : Exception(message)

/** Reads and writes the iOS backup JSON. Dates are ISO-8601 with fractional seconds; whole-second dates are accepted when reading. */
object BackupCodec {
    private val fractional = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val whole = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun date(ms: Long): String = fractional.format(Instant.ofEpochMilli(ms))
    fun dateWhole(ms: Long): String = whole.format(Instant.ofEpochMilli(ms))
    fun parseDate(s: String): Long = try { Instant.parse(s).toEpochMilli() } catch (e: Exception) { throw BackupException("Invalid date $s") }

    // Reading helpers: a missing key or JSON null is "absent".
    private fun JSONObject.str(k: String, d: String = ""): String = if (has(k) && !isNull(k)) getString(k) else d
    private fun JSONObject.strOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONObject.long(k: String, d: Long = 0): Long = if (has(k) && !isNull(k)) getLong(k) else d
    private fun JSONObject.longOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
    private fun JSONObject.int(k: String, d: Int = 0): Int = if (has(k) && !isNull(k)) getInt(k) else d
    private fun JSONObject.bool(k: String, d: Boolean = false): Boolean = if (has(k) && !isNull(k)) getBoolean(k) else d
    private fun JSONObject.dateOrNull(k: String): Long? = strOrNull(k)?.let(::parseDate)
    private fun JSONObject.dateOf(k: String): Long = parseDate(getString(k))
    private fun JSONObject.put2(k: String, v: Any?): JSONObject = put(k, v ?: JSONObject.NULL)
    private fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }
    private fun <T> JSONObject.list(k: String, f: (JSONObject) -> T): List<T>? = optJSONArray(k)?.map(f)

    // Encoding

    fun encode(f: BackupFile): String = JSONObject().apply {
        put("app", f.app); put("version", f.version); put("exportedAt", date(f.exportedAt))
        put("openingBalanceCents", f.openingBalanceCents); put("currencySymbol", f.currencySymbol)
        put("categories", JSONArray(f.categories.map { c ->
            JSONObject().put("name", c.name).put("symbol", c.symbol).put("kind", c.kindRaw).put("isDefault", c.isDefault)
                .put("sortOrder", c.sortOrder).put("isEssential", c.isEssential)
        }))
        put("transactions", JSONArray(f.transactions.map { t ->
            JSONObject().put("id", t.id).put("amountCents", t.amountCents).put("kind", t.kindRaw).put("category", t.category)
                .put("date", date(t.date)).put("title", t.title).put("notes", t.notes).put2("sourceID", t.sourceID)
                .put2("accountID", t.accountID).put2("toAccountID", t.toAccountID).put("toAmountCents", t.toAmountCents)
                .put("merchant", t.merchant).put("tags", t.tags).put("occurrenceKey", t.occurrenceKey)
        }))
        put("monthBudgets", JSONArray(f.monthBudgets.map { JSONObject().put("monthKey", it.monthKey).put("totalCents", it.totalCents) }))
        put("budgetLimits", JSONArray(f.budgetLimits.map { JSONObject().put("monthKey", it.monthKey).put("category", it.category).put("limitCents", it.limitCents) }))
        put("plannedExpenses", JSONArray(f.plannedExpenses.map { p ->
            JSONObject().put("id", p.id).put("title", p.title).put("amountCents", p.amountCents).put("dueDate", date(p.dueDate))
                .put("category", p.category).put("isPaid", p.isPaid).put2("paidTransactionID", p.paidTransactionID)
        }))
        put("shoppingItems", JSONArray(f.shoppingItems.map { s ->
            JSONObject().put("id", s.id).put("name", s.name).put("quantity", s.quantity).put("estimatedCents", s.estimatedCents)
                .put2("actualCents", s.actualCents).put("category", s.category).put("priority", s.priorityRaw).put("isPurchased", s.isPurchased)
                .put("listKind", s.listKindRaw).put2("expenseID", s.expenseID).put("createdAt", date(s.createdAt))
                .put2("purchasedAt", s.purchasedAt?.let(::date))
        }))
        put("goals", JSONArray(f.goals.map { g ->
            JSONObject().put("id", g.id).put("name", g.name).put("kind", g.kindRaw).put("symbol", g.symbol).put("targetCents", g.targetCents)
                .put2("deadline", g.deadline?.let(::date)).put("destination", g.destination).put("bufferCents", g.bufferCents)
                .put("plannedMonthlyCents", g.plannedMonthlyCents).put("contributionDay", g.contributionDay).put("createdAt", date(g.createdAt))
                .put("details", g.details).put("priority", g.priorityRaw).put("currencyCode", g.currencyCode).put("frequency", g.frequencyRaw)
                .put2("startDate", g.startDate?.let(::date)).put2("completedAt", g.completedAt?.let(::date)).put("isArchived", g.isArchived)
        }))
        put("goalEntries", JSONArray(f.goalEntries.map { e ->
            JSONObject().put("id", e.id).put("goalID", e.goalID).put("amountCents", e.amountCents).put("date", date(e.date)).put("note", e.note)
                .put("affectsCash", e.affectsCash).put2("accountID", e.accountID).put("accountAmountCents", e.accountAmountCents)
        }))
        put("tripCosts", JSONArray(f.tripCosts.map { c ->
            JSONObject().put("id", c.id).put("goalID", c.goalID).put("category", c.categoryRaw).put("label", c.label).put("amountCents", c.amountCents)
        }))
        f.recurringRules?.let { rules -> put("recurringRules", JSONArray(rules.map { r ->
            JSONObject().put("id", r.id).put("title", r.title).put("amountCents", r.amountCents).put("kind", r.kindRaw).put("category", r.category)
                .put("dayOfMonth", r.dayOfMonth).put("nextMonthKey", r.nextMonthKey).put("isActive", r.isActive).put("unit", r.unitRaw)
                .put("interval", r.interval).put2("nextDueDate", r.nextDueDate?.let(::date)).put("isCancelled", r.isCancelled).put("notes", r.notes)
                .put2("accountID", r.accountID).put("remindDaysBefore", r.remindDaysBefore)
        })) }
        f.accounts?.let { accounts -> put("accounts", JSONArray(accounts.map { a ->
            JSONObject().put("id", a.id).put("name", a.name).put("kind", a.kindRaw).put("currencyCode", a.currencyCode).put("openingCents", a.openingCents)
                .put("sortOrder", a.sortOrder).put("isArchived", a.isArchived).put("createdAt", date(a.createdAt))
        })) }
        f.exchangeRates?.let { rates -> put("exchangeRates", JSONArray(rates.map { r ->
            JSONObject().put("fromCode", r.fromCode).put("toCode", r.toCode).put("rateMicros", r.rateMicros).put("updatedAt", date(r.updatedAt))
        })) }
        f.reportingCurrency?.let { put("reportingCurrency", it) }
        f.reservedFunds?.let { funds -> put("reservedFunds", JSONArray(funds.map { r ->
            JSONObject().put("id", r.id).put("name", r.name).put("amountCents", r.amountCents).put("isActive", r.isActive).put("createdAt", date(r.createdAt))
        })) }
        f.receipts?.let { receipts -> put("receipts", JSONArray(receipts.map { r ->
            JSONObject().put("id", r.id).put("createdAt", date(r.createdAt)).put("merchant", r.merchant).put("date", date(r.date))
                .put("totalCents", r.totalCents).put("taxCents", r.taxCents).put("currencyCode", r.currencyCode).put("language", r.languageRaw)
                .put("category", r.category).put("notes", r.notes).put("ocrText", r.ocrText).put("itemsJSON", r.itemsJSON)
                .put2("transactionID", r.transactionID).put("needsReview", r.needsReview).put("imageFile", r.imageFile).put("imageHash", r.imageHash)
        })) }
    }.toString(2)

    // Decoding

    fun decode(text: String): BackupFile {
        val o = try { JSONObject(text) } catch (e: Exception) { throw BackupException("The file could not be read as a MONO backup.") }
        if (o.str("app") != "MONO") throw BackupException("This file is not a MONO backup.")
        val version = o.int("version", 0)
        if (version > BackupFile.CURRENT_VERSION) throw BackupException("This backup was made by a newer version of MONO (format $version). Update the app and try again.")
        val file = try {
            BackupFile(
                app = "MONO", version = version, exportedAt = o.strOrNull("exportedAt")?.let(::parseDate) ?: 0,
                openingBalanceCents = o.long("openingBalanceCents"), currencySymbol = o.str("currencySymbol", "RM"),
                categories = o.list("categories") { c ->
                    MoneyCategory(name = c.str("name"), symbol = c.str("symbol", "tag"), kindRaw = c.str("kind", "expense"), isDefault = c.bool("isDefault"),
                        sortOrder = c.int("sortOrder"), isEssential = c.bool("isEssential"))
                } ?: emptyList(),
                transactions = o.list("transactions") { t ->
                    TransactionRecord(id = t.getString("id"), amountCents = t.getLong("amountCents"), kindRaw = t.getString("kind"), category = t.str("category"),
                        date = t.dateOf("date"), title = t.str("title"), notes = t.str("notes"), sourceID = t.strOrNull("sourceID"), accountID = t.strOrNull("accountID"),
                        toAccountID = t.strOrNull("toAccountID"), toAmountCents = t.long("toAmountCents"), merchant = t.str("merchant"), tags = t.str("tags"),
                        occurrenceKey = t.str("occurrenceKey"))
                } ?: emptyList(),
                monthBudgets = o.list("monthBudgets") { MonthBudget(monthKey = it.getString("monthKey"), totalCents = it.getLong("totalCents")) } ?: emptyList(),
                budgetLimits = o.list("budgetLimits") { BudgetLimit(monthKey = it.getString("monthKey"), category = it.getString("category"), limitCents = it.getLong("limitCents")) } ?: emptyList(),
                plannedExpenses = o.list("plannedExpenses") { p ->
                    PlannedExpense(id = p.getString("id"), title = p.str("title"), amountCents = p.getLong("amountCents"), dueDate = p.dateOf("dueDate"),
                        category = p.str("category"), isPaid = p.bool("isPaid"), paidTransactionID = p.strOrNull("paidTransactionID"))
                } ?: emptyList(),
                shoppingItems = o.list("shoppingItems") { s ->
                    ShoppingItem(id = s.getString("id"), name = s.str("name"), quantity = s.int("quantity", 1), estimatedCents = s.long("estimatedCents"),
                        actualCents = s.longOrNull("actualCents"), category = s.str("category", "Groceries"), priorityRaw = s.int("priority", 1),
                        isPurchased = s.bool("isPurchased"), listKindRaw = s.str("listKind", "grocery"), expenseID = s.strOrNull("expenseID"),
                        createdAt = s.dateOf("createdAt"), purchasedAt = s.dateOrNull("purchasedAt"))
                } ?: emptyList(),
                goals = o.list("goals") { g ->
                    SavingsGoal(id = g.getString("id"), name = g.str("name"), kindRaw = g.str("kind", "general"), symbol = g.str("symbol", "target"),
                        targetCents = g.long("targetCents"), deadline = g.dateOrNull("deadline"), destination = g.str("destination"), bufferCents = g.long("bufferCents"),
                        plannedMonthlyCents = g.long("plannedMonthlyCents"), contributionDay = g.int("contributionDay", 1), createdAt = g.dateOf("createdAt"),
                        details = g.str("details"), priorityRaw = g.int("priority", 1), currencyCode = g.str("currencyCode"), frequencyRaw = g.str("frequency", "monthly"),
                        startDate = g.dateOrNull("startDate"), completedAt = g.dateOrNull("completedAt"), isArchived = g.bool("isArchived"))
                } ?: emptyList(),
                goalEntries = o.list("goalEntries") { e ->
                    GoalEntry(id = e.getString("id"), goalID = e.getString("goalID"), amountCents = e.getLong("amountCents"), date = e.dateOf("date"),
                        note = e.str("note"), affectsCash = e.bool("affectsCash", true), accountID = e.strOrNull("accountID"), accountAmountCents = e.long("accountAmountCents"))
                } ?: emptyList(),
                tripCosts = o.list("tripCosts") { c ->
                    TripCost(id = c.getString("id"), goalID = c.getString("goalID"), categoryRaw = c.str("category", "other"), label = c.str("label"), amountCents = c.getLong("amountCents"))
                } ?: emptyList(),
                recurringRules = o.list("recurringRules") { r ->
                    RecurringRule(id = r.getString("id"), title = r.str("title"), amountCents = r.getLong("amountCents"), kindRaw = r.str("kind", "expense"),
                        category = r.str("category"), dayOfMonth = r.int("dayOfMonth", 1), nextMonthKey = r.str("nextMonthKey"), isActive = r.bool("isActive", true),
                        unitRaw = r.str("unit", "month"), interval = r.int("interval", 1), nextDueDate = r.dateOrNull("nextDueDate"), isCancelled = r.bool("isCancelled"),
                        notes = r.str("notes"), accountID = r.strOrNull("accountID"), remindDaysBefore = r.int("remindDaysBefore", 1))
                },
                accounts = o.list("accounts") { a ->
                    Account(id = a.getString("id"), name = a.str("name"), kindRaw = a.str("kind", "other"), currencyCode = a.str("currencyCode", "MYR"),
                        openingCents = a.long("openingCents"), sortOrder = a.int("sortOrder"), isArchived = a.bool("isArchived"), createdAt = a.dateOf("createdAt"))
                },
                exchangeRates = o.list("exchangeRates") { r ->
                    ExchangeRate(fromCode = r.getString("fromCode"), toCode = r.getString("toCode"), rateMicros = r.getLong("rateMicros"), updatedAt = r.dateOf("updatedAt"))
                },
                reportingCurrency = o.strOrNull("reportingCurrency"),
                reservedFunds = o.list("reservedFunds") { r ->
                    ReservedFund(id = r.getString("id"), name = r.str("name"), amountCents = r.getLong("amountCents"), isActive = r.bool("isActive", true), createdAt = r.dateOf("createdAt"))
                },
                receipts = o.list("receipts") { r ->
                    Receipt(id = r.getString("id"), createdAt = r.dateOf("createdAt"), merchant = r.str("merchant"), date = r.dateOf("date"), totalCents = r.long("totalCents"),
                        taxCents = r.long("taxCents"), currencyCode = r.str("currencyCode"), languageRaw = r.str("language", "auto"), imageFile = r.str("imageFile"),
                        imageHash = r.str("imageHash"), ocrText = r.str("ocrText"), itemsJSON = r.str("itemsJSON"), notes = r.str("notes"), category = r.str("category"),
                        transactionID = r.strOrNull("transactionID"), needsReview = r.bool("needsReview"))
                },
            )
        } catch (e: BackupException) { throw e } catch (e: Exception) { throw BackupException("The file could not be read as a MONO backup.") }
        validate(file)
        return file
    }

    // Validation: every rule the iOS importer applies, so a damaged file is refused before anything is touched.

    fun validate(f: BackupFile) {
        fun fail(why: String): Nothing = throw BackupException("The backup is damaged: $why")
        val cap = Money.MAX_CENTS
        if (f.version < 1) fail("unknown format version")
        if (kotlin.math.abs(f.openingBalanceCents) > cap) fail("opening balance is out of range")

        if (f.transactions.map { it.id }.toSet().size != f.transactions.size) fail("duplicate transaction ids")
        for (t in f.transactions) {
            if (TxKind.entries.none { it.raw == t.kindRaw }) fail("transaction with unknown type “${t.kindRaw}”")
            if (t.amountCents !in 1..cap) fail("transaction amount out of range")
        }
        for (b in f.monthBudgets) if (MonthKey.parse(b.monthKey) == null || b.totalCents !in 0..cap) fail("invalid monthly budget")
        for (l in f.budgetLimits) if (MonthKey.parse(l.monthKey) == null || l.limitCents !in 0..cap) fail("invalid category limit")
        for (p in f.plannedExpenses) if (p.amountCents !in 0..cap) fail("invalid planned expense")
        for (s in f.shoppingItems) {
            if (ListKind.entries.none { it.raw == s.listKindRaw } || Priority.entries.none { it.raw == s.priorityRaw } || s.quantity !in 1..9999 ||
                s.estimatedCents !in 0..cap || (s.actualCents?.let { it !in 0..cap } == true)) fail("invalid shopping item “${s.name}”")
        }
        val goalIDs = f.goals.map { it.id }.toSet()
        if (goalIDs.size != f.goals.size) fail("duplicate goal ids")
        for (g in f.goals) {
            if (GoalKind.entries.none { it.raw == g.kindRaw } || g.targetCents !in 0..cap || g.bufferCents !in 0..cap ||
                g.plannedMonthlyCents !in 0..cap || g.contributionDay !in 1..28) fail("invalid goal “${g.name}”")
            if (g.currencyCode.isNotEmpty() && g.currencyCode.length != 3) fail("goal “${g.name}” has an invalid currency")
            if (Priority.entries.none { it.raw == g.priorityRaw }) fail("goal “${g.name}” has an invalid priority")
            if (ContributionFrequency.entries.none { it.raw == g.frequencyRaw }) fail("goal “${g.name}” has an invalid schedule")
        }
        for (e in f.goalEntries) {
            if (e.goalID !in goalIDs) fail("a contribution refers to a goal that is not in the file")
            if (kotlin.math.abs(e.amountCents) > cap || kotlin.math.abs(e.accountAmountCents) > cap) fail("contribution out of range")
        }
        for (c in f.tripCosts) if (c.goalID !in goalIDs || TripCostCategory.entries.none { it.raw == c.categoryRaw } || c.amountCents !in 0..cap) fail("invalid trip cost")

        val accountIDs = f.accounts?.map { it.id }?.toSet() ?: emptySet()
        f.accounts?.let { accounts ->
            if (accountIDs.size != accounts.size) fail("duplicate account ids")
            for (a in accounts) if (AccountKind.entries.none { it.raw == a.kindRaw } || kotlin.math.abs(a.openingCents) > cap || a.currencyCode.length != 3 || a.name.isEmpty()) fail("invalid account “${a.name}”")
            for (t in f.transactions) t.accountID?.let { if (it !in accountIDs) fail("a transaction refers to an account that is not in the file") }
            for (e in f.goalEntries) e.accountID?.let { if (it !in accountIDs) fail("a contribution refers to an account that is not in the file") }
        }
        for (t in f.transactions) if (t.kindRaw == "transfer") {
            val from = t.accountID; val to = t.toAccountID
            if (from == null || to == null || from == to || t.toAmountCents !in 1..cap || (f.accounts != null && (from !in accountIDs || to !in accountIDs))) fail("invalid transfer")
        }
        for (r in f.exchangeRates.orEmpty()) if (r.fromCode.length != 3 || r.toCode.length != 3 || r.fromCode == r.toCode || r.rateMicros <= 0) fail("invalid exchange rate")
        for (r in f.reservedFunds.orEmpty()) if (r.amountCents !in 0..cap || r.name.isEmpty()) fail("invalid reserved fund")
        val keys = f.transactions.map { it.occurrenceKey }.filter { it.isNotEmpty() }
        if (keys.toSet().size != keys.size) fail("the same scheduled payment is recorded twice")
        val ruleIDs = f.recurringRules.orEmpty().map { it.id }
        if (ruleIDs.toSet().size != ruleIDs.size) fail("duplicate recurring ids")
        for (r in f.recurringRules.orEmpty()) {
            if (TxKind.entries.none { it.raw == r.kindRaw } || r.amountCents !in 1..cap || r.dayOfMonth !in 1..31 || MonthKey.parse(r.nextMonthKey) == null ||
                RecurrenceUnit.entries.none { it.raw == r.unitRaw } || r.interval !in 1..520 || r.remindDaysBefore !in 0..30 ||
                (r.accountID != null && f.accounts != null && r.accountID !in accountIDs)) fail("invalid recurring transaction “${r.title}”")
        }
        // Every goal's balance must be non-negative, otherwise the file would invent debt.
        val balances = HashMap<String, Long>()
        for (e in f.goalEntries) balances[e.goalID] = (balances[e.goalID] ?: 0L) + e.amountCents
        if (balances.values.any { it < 0 }) fail("a goal would have a negative balance")
    }
}

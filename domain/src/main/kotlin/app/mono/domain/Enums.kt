package app.mono.domain

/**
 * Raw values match the iOS app exactly, so records and backups can move between platforms.
 * `symbol` strings are SF Symbol names (stored in data); the Android UI maps them to its own icons.
 */

enum class TxKind(val raw: String, val label: String) {
    INCOME("income", "Income"),
    EXPENSE("expense", "Expense"),
    /** Money moving between two of your own accounts. Neither income nor expense; never in either total. */
    TRANSFER("transfer", "Transfer");

    companion object {
        /** Kinds that belong to a category (transfers have none). */
        val categoryKinds = listOf(INCOME, EXPENSE)
        fun of(raw: String, default: TxKind = EXPENSE) = entries.firstOrNull { it.raw == raw } ?: default
    }
}

enum class AccountKind(val raw: String, val label: String, val symbol: String) {
    CASH("cash", "Cash", "banknote"),
    BANK("bank", "Bank", "building.columns"),
    EWALLET("ewallet", "E-wallet", "iphone"),
    OTHER("other", "Other", "square.stack");

    companion object {
        fun of(raw: String) = entries.firstOrNull { it.raw == raw } ?: OTHER
    }
}

enum class Priority(val raw: Int, val label: String) {
    LOW(0, "Low"), MEDIUM(1, "Medium"), HIGH(2, "High");

    companion object {
        fun of(raw: Int) = entries.firstOrNull { it.raw == raw } ?: MEDIUM
    }
}

enum class ListKind(val raw: String, val label: String, val symbol: String) {
    GROCERY("grocery", "Groceries", "cart"),
    WISHLIST("wishlist", "Wishlist", "gift"),
    PERSONAL("personal", "Personal", "person"),
    ELECTRONICS("electronics", "Electronics", "headphones"),
    TRIP("trip", "Trip essentials", "suitcase");

    companion object {
        fun of(raw: String) = entries.firstOrNull { it.raw == raw } ?: GROCERY
    }
}

/** How often a goal's planned contribution is made. */
enum class ContributionFrequency(val raw: String, val label: String, val noun: String, val stepDays: Int?) {
    WEEKLY("weekly", "Every week", "week", 7),
    BIWEEKLY("biweekly", "Every 2 weeks", "fortnight", 14),
    MONTHLY("monthly", "Every month", "month", null);

    companion object {
        fun of(raw: String) = entries.firstOrNull { it.raw == raw } ?: MONTHLY
    }
}

/** The unit a recurring payment repeats in. "Every 3 months" is MONTH with interval 3. */
enum class RecurrenceUnit(val raw: String, val label: String) {
    WEEK("week", "Week"), MONTH("month", "Month"), YEAR("year", "Year");

    companion object {
        fun of(raw: String) = entries.firstOrNull { it.raw == raw } ?: MONTH
    }
}

enum class PaymentStatus(val raw: String, val label: String) {
    ACTIVE("active", "Active"), PAUSED("paused", "Paused"), CANCELLED("cancelled", "Cancelled")
}

enum class GoalKind(val raw: String) {
    TRIP("trip"), GENERAL("general");

    companion object {
        fun of(raw: String) = entries.firstOrNull { it.raw == raw } ?: GENERAL
    }
}

enum class TripCostCategory(val raw: String, val label: String, val symbol: String) {
    FLIGHTS("flights", "Flights", "airplane"),
    ACCOMMODATION("accommodation", "Accommodation", "bed.double"),
    FOOD("food", "Food", "fork.knife"),
    TRANSPORTATION("transportation", "Transportation", "tram"),
    ACTIVITIES("activities", "Activities", "ticket"),
    OTHER("other", "Other", "ellipsis");

    companion object {
        fun of(raw: String) = entries.firstOrNull { it.raw == raw } ?: OTHER
    }
}

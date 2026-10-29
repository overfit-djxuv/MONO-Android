package app.mono.finance.ui

/** Screens reached from the Dashboard and the Tools hub; the five tabs themselves are in [AppTab]. */
enum class Route(val title: String) {
    TOOLS("Tools"), SAFE("Safe to spend"), SUBS("Subscriptions & bills"), INSIGHTS("Insights"), REVIEW("Monthly review"),
    SIMULATOR("Purchase simulator"), WHATIF("What-if lab"), ACCOUNTS("Accounts & currency"), SETTINGS("Settings"), HISTORY("Version history"), BACKUP("Backup & recovery"), RECEIPTS("Receipts"),
    // Tab shortcuts, used by Dashboard links
    BUDGET("Budget"), GOALS("Goals"),
}

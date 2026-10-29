package app.mono.domain

/** What changed in each release, newest first. The same text lives in CHANGELOG.md; a test keeps the two in step. */
object VersionHistory {
    data class Release(
        val version: String, val date: String, val title: String,
        val notes: List<String>, val improvements: List<String> = emptyList(), val fixes: List<String> = emptyList(),
    )

    val releases: List<Release> = listOf(
        Release(
            "0.5.0", "29 Oct 2026", "Import, receipts and your own colours",
            notes = listOf(
                "Import a bank statement or spreadsheet (CSV): choose the columns, check a preview, then import. Rows already in MONO are skipped, so importing the same file twice adds nothing.",
                "Receipts: take a photo or choose one, MONO reads it on the phone (English, Spanish, Malay; Burmese is photo and manual entry), you check it, then save it as an expense, attach it to a transaction, or keep it on its own. Photos travel inside backups.",
                "Theme editor: pick a palette or make your own, with contrast checks and a live preview. Saved in the same shape as the iPhone app.",
                "Transactions: switch period, search, filter by type, category, account, tag and amount, open a transaction for its details, repeat it for today, and see possible duplicates.",
                "The transaction form now has account and merchant, recent suggestions, a duplicate warning and a shortcut to Transfer.",
            ),
        ),
        Release(
            "0.4.0", "29 Oct 2026", "Lock, reminders and shortcuts",
            notes = listOf(
                "Lock MONO with your fingerprint, face or phone PIN. While it is on, the app is hidden from recent apps and screenshots.",
                "Reminders for planned expenses and bills before they fall due, budget alerts at 80% and when you go over, and a monthly review notice. They stay on the phone and never record or deduct anything.",
                "Launcher shortcuts: touch and hold the icon for Add expense, Add income, Transfer, Scan receipt or Safe to spend. A shortcut waits for the lock before opening anything.",
            ),
        ),
        Release(
            "0.3.0", "29 Oct 2026", "Backup and recovery",
            notes = listOf(
                "Back up to a file you choose, and restore from one. Backups made on an iPhone open here, and the other way round.",
                "Optional passphrase: backups are encrypted, and the passphrase is kept encrypted by Android, never inside a backup.",
                "Every backup is read back and checked before it is reported as made. A safety copy of your data is kept before any restore.",
                "A daily automatic copy kept in the app, and a reminder on the Dashboard when nothing has been saved outside MONO for a week.",
            ),
        ),
        Release(
            "0.2.0", "29 Oct 2026", "Tools, analysis and accounts",
            notes = listOf(
                "Safe to spend: what you can spend after bills, planned savings, essentials and reserved funds, with a look-ahead of your choice.",
                "Subscriptions and bills: schedules you confirm one payment at a time. Nothing is deducted automatically.",
                "Insights, Monthly review (shareable as text or CSV), Purchase simulator and What-if lab.",
                "Accounts, transfers between them, and exchange rates you enter yourself.",
                "Settings for the reporting currency and symbol, and Hide amounts.",
            ),
        ),
        Release(
            "0.1.0", "29 Oct 2026", "First build",
            notes = listOf(
                "Dashboard, Transactions, Budget, Goals and Shopping, ported from the iPhone app with the same rules and numbers.",
                "Everything stays on this phone: the app has no internet permission.",
            ),
        ),
    )

    val current: Release get() = releases.first()
}

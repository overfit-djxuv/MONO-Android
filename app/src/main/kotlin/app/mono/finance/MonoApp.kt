package app.mono.finance

import android.app.Application
import app.mono.domain.Money
import app.mono.domain.model.Account
import app.mono.domain.model.DefaultCategories
import app.mono.finance.data.MonoDatabase
import app.mono.finance.backup.BackupManager
import app.mono.finance.data.Repository
import app.mono.finance.notify.ReminderScheduler
import app.mono.finance.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Holds the few app-wide singletons. Manual wiring is plenty for an app this size. */
class MonoApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var db: MonoDatabase
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var repository: Repository
        private set
    lateinit var backups: BackupManager
        private set
    lateinit var reminders: ReminderScheduler
        private set
    lateinit var receiptStore: app.mono.finance.receipts.ReceiptStore
        private set

    override fun onCreate() {
        super.onCreate()
        db = MonoDatabase.open(this)
        settings = SettingsStore(this)
        repository = Repository(db)
        receiptStore = app.mono.finance.receipts.ReceiptStore(this)
        backups = BackupManager(this)
        reminders = ReminderScheduler(this).also { it.createChannel() }

        // Keep the global money formatter in step with the settings (reporting currency, symbol, Hide amounts).
        scope.launch { settings.moneyContext.collect { Money.context = it } }

        // First launch only: seed the default categories, once.
        scope.launch {
            if (!settings.didSeedCategories.first() && db.dao().categoryCount() == 0) {
                db.dao().put(*DefaultCategories.seed().toTypedArray())
            }
            settings.markCategoriesSeeded()
            // A fresh install gets one account so every record has somewhere to live (opening balance is editable later).
            if (db.dao().accountCount() == 0) {
                db.dao().put(Account(name = "Main", kindRaw = "bank", currencyCode = Money.reporting.code, createdAt = System.currentTimeMillis()))
            }
        }
    
        // One automatic in-app backup a day (never fatal: a failure here must not stop the app opening).
        scope.launch { try { backups.autoIfDue() } catch (e: Exception) { android.util.Log.w("MONO", "automatic backup skipped", e) } }
    }
}

package app.mono.finance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mono.domain.CsvImport
import app.mono.domain.LedgerRules
import app.mono.domain.LedgerException
import app.mono.domain.MoneyContext
import app.mono.domain.QuickAction
import app.mono.domain.theme.ThemeModel
import app.mono.domain.theme.ThemePalette
import app.mono.domain.QuickActionSettings
import app.mono.finance.notify.Shortcuts
import app.mono.domain.MonthKey
import app.mono.domain.SafeToSpend
import app.mono.domain.WhatIf
import app.mono.domain.model.Account
import app.mono.domain.model.BudgetLimit
import app.mono.domain.model.ExchangeRate
import app.mono.domain.model.RecurringRule
import app.mono.domain.model.ReservedFund
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.MonthBudget
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.ShoppingItem
import app.mono.domain.model.TransactionRecord
import app.mono.domain.model.TripCost
import app.mono.finance.backup.BackupFile
import app.mono.finance.backup.BackupManager
import app.mono.finance.data.LedgerState
import app.mono.finance.data.Repository
import app.mono.finance.data.ledgerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One ViewModel for the money screens. Screens read [ledger] and send intents; no maths happens here. */
class MonoViewModel(app: Application) : AndroidViewModel(app) {
    private val mono = app as MonoApp
    private val dao = mono.db.dao()
    private val repo = mono.repository

    val ledger: StateFlow<LedgerState?> = dao.ledgerState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val moneyContext: StateFlow<MoneyContext> =
        mono.settings.moneyContext.stateIn(viewModelScope, SharingStarted.Eagerly, MoneyContext())

    val safeConfig: StateFlow<SafeToSpend.Config> = mono.settings.safeConfig.stateIn(viewModelScope, SharingStarted.Eagerly, SafeToSpend.Config())
    val scenarios: StateFlow<List<WhatIf.Scenario>> = mono.settings.scenarios.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hourlyRate: StateFlow<String> = mono.settings.hourlyRate.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setSafeConfig(c: SafeToSpend.Config) { viewModelScope.launch { mono.settings.setSafeConfig(c) } }
    fun setHourlyRate(t: String) { viewModelScope.launch { mono.settings.setHourlyRate(t) } }
    fun saveScenario(s: WhatIf.Scenario) { viewModelScope.launch { mono.settings.setScenarios(WhatIf.upsert(scenarios.value, s)) } }
    fun deleteScenario(id: String) { viewModelScope.launch { mono.settings.setScenarios(scenarios.value.filter { it.id != id }) } }

    /** One-line messages for a snackbar: confirmations and the reason something couldn't be saved. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Runs a write; a rule violation or database error becomes a message instead of a crash. */
    private fun run(failure: String, success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); success?.let { _messages.tryEmit(it) } }
            catch (e: LedgerException) { _messages.tryEmit(e.error.text) }
            catch (e: Exception) {
                android.util.Log.w("MONO", failure, e)
                _messages.tryEmit("$failure: ${e.message ?: "unexpected error"}")
            }
        }
    }

    fun toggleHidden() { viewModelScope.launch { mono.settings.setHidden(!moneyContext.value.hidden) } }

    // Transactions
    fun saveTransaction(t: TransactionRecord) = run("Couldn’t save the transaction") { repo.saveTransaction(t) }
    fun repeatTransaction(tx: TransactionRecord) =
        run("Couldn’t repeat the transaction", "Repeated for today") { repo.saveTransaction(LedgerRules.repeatOf(tx, System.currentTimeMillis())) }
    fun deleteTransaction(id: String) {
        val l = ledger.value ?: return
        val tx = l.transactions.firstOrNull { it.id == id } ?: return
        run("Couldn’t delete the transaction", "Transaction deleted") { repo.deleteTransaction(tx, l.items, l.planned) }
    }

    // Shopping
    val receipts: StateFlow<List<app.mono.domain.model.Receipt>> = dao.receipts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val receiptStore get() = mono.receiptStore

    fun saveReceipt(r: app.mono.domain.model.Receipt, transaction: TransactionRecord? = null, onDone: () -> Unit = {}) =
        run("Couldn’t save the receipt", "Receipt saved") { repo.saveReceipt(r, transaction); onDone() }
    fun deleteReceipt(r: app.mono.domain.model.Receipt, onDone: () -> Unit = {}) =
        run("Couldn’t delete the receipt", "Receipt deleted") { repo.deleteReceipt(r); mono.receiptStore.delete(r.imageFile); onDone() }

    fun importCsv(plan: CsvImport.Plan, accountID: String?, onDone: () -> Unit = {}) {
        val cats = ledger.value?.categories ?: return
        run("Couldn’t import") {
            val n = repo.applyCsvImport(plan, cats, accountID)
            _messages.tryEmit("Imported $n ${if (n == 1) "transaction" else "transactions"}")
            onDone()
        }
    }
    fun saveItem(i: ShoppingItem) = run("Couldn’t save the item") { repo.saveItem(i) }
    fun deleteItems(items: List<ShoppingItem>) = run("Couldn’t delete") { repo.deleteItems(items) }
    fun recordPurchase(item: ShoppingItem) {
        val existing = ledger.value?.transactions ?: return
        run("Couldn’t record the purchase", "Recorded as an expense") { repo.recordPurchase(item, existing) }
    }
    fun recordPurchases(items: List<ShoppingItem>) {
        val existing = ledger.value?.transactions ?: return
        run("Couldn’t record the purchases", "Recorded ${items.size} as expenses") {
            var seen = existing
            for (i in items) { val tx = repo.recordPurchase(i, seen); seen = seen + tx }
        }
    }

    // Budget
    fun setMonthTotal(month: MonthKey, cents: Long?) {
        val existing = ledger.value?.monthBudgets?.firstOrNull { it.monthKey == month.string }
        run("Couldn’t save the budget") { repo.setMonthTotal(month, existing, cents) }
    }
    fun saveLimit(l: BudgetLimit) = run("Couldn’t save the limit") { repo.saveLimit(l) }
    fun deleteLimit(l: BudgetLimit) = run("Couldn’t delete the limit") { repo.deleteLimit(l) }
    fun savePlanned(p: PlannedExpense) = run("Couldn’t save") { repo.savePlanned(p) }
    fun deletePlanned(p: PlannedExpense) = run("Couldn’t delete") { repo.deletePlanned(p) }
    fun markPaid(p: PlannedExpense) {
        val existing = ledger.value?.transactions ?: return
        run("Couldn’t record the payment", "Recorded as an expense") { repo.markPaid(p, existing) }
    }
    fun copyBudget(month: MonthKey, reported: List<TransactionRecord>, carryOver: Boolean) {
        val l = ledger.value ?: return
        run("Couldn’t copy the budget", "Budget copied") { repo.copyBudget(month, l.monthBudgets, l.limits, reported, carryOver) }
    }

    // Goals
    fun saveGoal(g: SavingsGoal, message: String? = null) = run("Couldn’t save the goal", message) { repo.saveGoal(g) }
    fun saveCost(c: TripCost) = run("Couldn’t save the cost") { repo.saveCost(c) }
    fun deleteCost(c: TripCost) = run("Couldn’t delete the cost") { repo.deleteCost(c) }
    fun deleteEntry(e: GoalEntry) = run("Couldn’t delete the entry") { repo.deleteEntry(e) }
    fun deleteGoal(g: SavingsGoal) {
        val l = ledger.value ?: return
        run("Couldn’t delete the goal", "Goal deleted") { repo.deleteGoal(g, l.entries, l.costs) }
    }
    fun moveMoney(goal: SavingsGoal, cents: Long, withdraw: Boolean, date: Long, note: String, affectsCash: Boolean = true) {
        val entries = ledger.value?.entries ?: return
        run("Couldn’t move the money", if (withdraw) "Withdrawn from goal" else "Added to goal") {
            repo.moveMoney(goal, cents, withdraw, date, note, entries, affectsCash)
        }
    }

    // Subscriptions and bills
    fun saveRule(r: RecurringRule) = run("Couldn’t save") { repo.saveRule(r) }
    fun deleteRule(r: RecurringRule) = run("Couldn’t delete") { repo.deleteRule(r) }
    fun recordPayment(rule: RecurringRule, paidOn: Long, amountCents: Long?, accountID: String?) {
        val existing = ledger.value?.transactions ?: return
        run("Couldn’t record the payment", "Payment recorded") { repo.recordPayment(rule, existing, paidOn, amountCents, accountID) }
    }
    fun skipPayment(rule: RecurringRule) = run("Couldn’t skip", "Payment skipped") { repo.skipPayment(rule) }
    fun pause(rule: RecurringRule) = run("Couldn’t pause", "Paused") { repo.pause(rule) }
    fun resume(rule: RecurringRule) = run("Couldn’t resume", "Resumed") { repo.resume(rule) }
    fun cancel(rule: RecurringRule) = run("Couldn’t cancel", "Cancelled") { repo.cancel(rule) }

    // Reserved funds
    fun saveReserved(f: ReservedFund) = run("Couldn’t save") { repo.saveReserved(f) }
    fun deleteReserved(f: ReservedFund) = run("Couldn’t delete") { repo.deleteReserved(f) }

    // Accounts, rates and transfers
    fun saveAccount(a: Account) = run("Couldn’t save the account") { repo.saveAccount(a) }
    fun saveRate(from: String, to: String, micros: Long) {
        val existing = ledger.value?.rates ?: emptyList()
        run("Couldn’t save the rate", "Rate saved") { repo.saveRate(from, to, micros, existing) }
    }
    fun deleteRate(r: ExchangeRate) = run("Couldn’t delete the rate") { repo.deleteRate(r) }
    fun transfer(from: Account, to: Account, sent: Long, received: Long, date: Long, title: String, notes: String) =
        run("Couldn’t record the transfer", "Transfer recorded") { repo.transfer(from, to, sent, received, date, title, notes) }
    fun setReportingCurrency(code: String) { viewModelScope.launch { mono.settings.setReportingCurrency(code) } }
    fun setCurrencySymbol(symbol: String) { viewModelScope.launch { mono.settings.setCurrencySymbol(symbol) } }

    // Backup and restore

    val lastBackupAt: StateFlow<Long> = mono.settings.lastBackupAt.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val autoBackup: StateFlow<Boolean> = mono.settings.autoBackup.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val hasPassphrase: StateFlow<Boolean> = mono.settings.sealedPassphrase.map { it.isNotBlank() }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _entries = MutableStateFlow<List<BackupManager.Entry>>(emptyList())
    val backupEntries: StateFlow<List<BackupManager.Entry>> = _entries
    fun refreshBackups() { viewModelScope.launch(Dispatchers.IO) { _entries.value = mono.backups.list() } }
    fun setAutoBackup(on: Boolean) { viewModelScope.launch { mono.settings.setAutoBackup(on) } }

    fun setPassphrase(pass: String?) = run("Couldn’t save the passphrase", if (pass.isNullOrEmpty()) "Passphrase removed" else "Passphrase saved") {
        if (pass.isNullOrEmpty()) mono.backups.clearPassphrase() else mono.backups.savePassphrase(pass)
    }

    /** Builds a verified backup (encrypted if a passphrase is saved) for the caller to write to a file the user picks. */
    fun prepareExport(onReady: (ByteArray, String) -> Unit) {
        viewModelScope.launch {
            try {
                val pass = mono.backups.loadPassphrase()
                val bytes = mono.backups.createBytes("manual", pass)
                onReady(bytes, mono.backups.suggestedName(encrypted = pass != null))
            } catch (e: Exception) { _messages.tryEmit(e.message ?: "The backup couldn’t be made.") }
        }
    }

    fun markExported() { viewModelScope.launch { mono.settings.setLastBackupAt(System.currentTimeMillis()); _messages.tryEmit("Backup saved") } }

    fun backupNowInApp() = run("The backup couldn’t be made", "Backup saved in the app") {
        val pass = mono.backups.loadPassphrase()
        mono.backups.saveInternal(mono.backups.createBytes("manual", pass), "manual", pass != null)
        _entries.value = mono.backups.list()
    }

    suspend fun inspectBackup(bytes: ByteArray, passphrase: String?): BackupManager.Opened = withContext(Dispatchers.Default) { mono.backups.inspect(bytes, passphrase) }

    fun restoreBackup(file: BackupFile) = run("The backup couldn’t be restored", "Backup restored") {
        mono.backups.restore(file)
        _entries.value = mono.backups.list()
    }

    fun deleteBackup(e: BackupManager.Entry) { mono.backups.delete(e); refreshBackups() }

    // Reminders: kept in step with the data, the on/off switch and the hour

    val remindersEnabled: StateFlow<Boolean> = mono.settings.reminders.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val reminderHour: StateFlow<Int> = mono.settings.reminderHour.stateIn(viewModelScope, SharingStarted.Eagerly, 9)
    fun setReminders(on: Boolean) { viewModelScope.launch { mono.settings.setReminders(on) } }
    fun setReminderHour(h: Int) { viewModelScope.launch { mono.settings.setReminderHour(h) } }

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(ledger, remindersEnabled, reminderHour) { l, e, h -> Triple(l, e, h) }.collectLatest { (l, e, h) ->
                if (l == null) return@collectLatest
                kotlinx.coroutines.delay(800) // let a burst of edits settle
                withContext(Dispatchers.Default) { try { mono.reminders.sync(l, e, h) } catch (ex: Exception) { android.util.Log.w("MONO", "reminders", ex) } }
            }
        }
    }

    // Theme: the saved choice plus an unsaved draft that the whole app previews

    private val _theme = MutableStateFlow(ThemeModel())
    val theme: StateFlow<ThemeModel> = _theme
    init { viewModelScope.launch { mono.settings.theme.collect { saved -> _theme.value = ThemeModel(saved, _theme.value.draft) } } }
    private fun change(f: (ThemeModel) -> ThemeModel, persist: Boolean = true) {
        val next = f(_theme.value); _theme.value = next
        if (persist) viewModelScope.launch { mono.settings.setTheme(next.saved) }
    }
    fun themeSelect(id: String) = change({ it.select(id) })
    fun themeEdit(f: (ThemePalette) -> ThemePalette) = change({ it.edit(f) }, persist = false)
    fun themeSave(name: String?) = change({ it.saveDraft(name) })
    fun themeDiscard() = change({ it.discardDraft() }, persist = false)
    fun themeDelete(id: String) = change({ it.delete(id) })
    fun themeDuplicate(id: String) = change({ it.duplicate(id) })
    fun themeReset() = change({ it.resetAll() })

    // Launcher shortcuts

    val quickActions: StateFlow<String?> = mono.settings.quickActions.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun setQuickAction(a: QuickAction, on: Boolean) { viewModelScope.launch { mono.settings.setQuickActions(QuickActionSettings.toggle(quickActions.value, a, on)) } }
    init { viewModelScope.launch { mono.settings.quickActions.collect { Shortcuts.publish(app, it) } } }

    // App lock and shortcuts

    /** null until the setting has loaded; the app stays hidden until then so nothing flashes before the lock. */
    val lockEnabled: StateFlow<Boolean?> = mono.settings.appLock.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _locked = MutableStateFlow(true)
    /** Locked at launch (until the setting says otherwise) and again whenever the app leaves the screen. */
    val locked: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(_locked, lockEnabled) { l, e -> e == null || (e && l) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    var unlocking = false
    fun unlock() { _locked.value = false }
    fun lockIfEnabled() { if (lockEnabled.value == true) _locked.value = true }
    fun setLockEnabled(on: Boolean) { viewModelScope.launch { mono.settings.setAppLock(on); if (!on) _locked.value = false } }

    /** An action requested from the launcher; held until the app is unlocked, so a shortcut can never open a screen with money past the lock. */
    val pendingAction = MutableStateFlow<QuickAction?>(null)
    fun request(a: QuickAction) { pendingAction.value = a }
    fun takeAction(): QuickAction? = if (locked.value) null else pendingAction.value.also { pendingAction.value = null }
}

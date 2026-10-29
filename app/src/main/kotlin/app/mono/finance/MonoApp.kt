package app.mono.finance

import android.app.Application
import app.mono.domain.Money
import app.mono.finance.data.DefaultCategories
import app.mono.finance.data.MonoDatabase
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

    override fun onCreate() {
        super.onCreate()
        db = MonoDatabase.open(this)
        settings = SettingsStore(this)

        // Keep the global money formatter in step with the settings (reporting currency, symbol, Hide amounts).
        scope.launch { settings.moneyContext.collect { Money.context = it } }

        // First launch only: seed the default categories, once.
        scope.launch {
            if (!settings.didSeedCategories.first() && db.dao().categoryCount() == 0) {
                db.dao().put(*DefaultCategories.seed().toTypedArray())
            }
            settings.markCategoriesSeeded()
        }
    }
}

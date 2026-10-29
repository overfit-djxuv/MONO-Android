package app.mono.finance.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.mono.domain.Currency
import app.mono.domain.MoneyContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** The settings that live in UserDefaults on iOS, under the same keys. */
class SettingsStore(private val context: Context) {
    private object Keys {
        val reportingCurrency = stringPreferencesKey("reportingCurrency")
        val currencySymbol = stringPreferencesKey("currencySymbol")
        val hideAmounts = booleanPreferencesKey("hideAmounts")
        val didSeedCategories = booleanPreferencesKey("didSeedCategories")
    }

    val moneyContext: Flow<MoneyContext> = context.dataStore.data.map { p ->
        MoneyContext(
            reporting = Currency.find(p[Keys.reportingCurrency] ?: Currency.default.code),
            symbolOverride = p[Keys.currencySymbol] ?: "",
            hidden = p[Keys.hideAmounts] ?: false,
        )
    }

    val didSeedCategories: Flow<Boolean> = context.dataStore.data.map { it[Keys.didSeedCategories] ?: false }

    suspend fun setHidden(hidden: Boolean) { context.dataStore.edit { it[Keys.hideAmounts] = hidden } }
    suspend fun setReportingCurrency(code: String) { context.dataStore.edit { it[Keys.reportingCurrency] = code } }
    suspend fun setCurrencySymbol(symbol: String) { context.dataStore.edit { it[Keys.currencySymbol] = symbol } }
    suspend fun markCategoriesSeeded() { context.dataStore.edit { it[Keys.didSeedCategories] = true } }
}

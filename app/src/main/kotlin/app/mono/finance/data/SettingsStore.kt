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
import app.mono.domain.SafeToSpend
import app.mono.domain.WhatIf
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** The settings that live in UserDefaults on iOS, under the same keys. */
class SettingsStore(private val context: Context) {
    private object Keys {
        val reportingCurrency = stringPreferencesKey("reportingCurrency")
        val currencySymbol = stringPreferencesKey("currencySymbol")
        val hideAmounts = booleanPreferencesKey("hideAmounts")
        val didSeedCategories = booleanPreferencesKey("didSeedCategories")
        val safeConfig = stringPreferencesKey("safeToSpendConfig.v1")
        val scenarios = stringPreferencesKey("whatIfScenarios.v1")
        val hourlyRateCents = stringPreferencesKey("hourlyRateCents")
        val lastBackupAt = androidx.datastore.preferences.core.longPreferencesKey("lastBackupAt")
        val autoBackup = booleanPreferencesKey("autoBackup")
        val sealedPassphrase = stringPreferencesKey("backupPassphraseSealed")
    }

    val moneyContext: Flow<MoneyContext> = context.dataStore.data.map { p ->
        MoneyContext(
            reporting = Currency.find(p[Keys.reportingCurrency] ?: Currency.default.code),
            symbolOverride = p[Keys.currencySymbol] ?: "",
            hidden = p[Keys.hideAmounts] ?: false,
        )
    }

    val didSeedCategories: Flow<Boolean> = context.dataStore.data.map { it[Keys.didSeedCategories] ?: false }

    // Safe to spend settings (stored as JSON under the same key iOS uses)

    val safeConfig: Flow<SafeToSpend.Config> = context.dataStore.data.map { SettingsJson.decodeConfig(it[Keys.safeConfig]) }
    suspend fun setSafeConfig(c: SafeToSpend.Config) { context.dataStore.edit { it[Keys.safeConfig] = SettingsJson.encodeConfig(c) } }

    // Saved What-if scenarios (at most three)

    val scenarios: Flow<List<WhatIf.Scenario>> = context.dataStore.data.map { SettingsJson.decodeScenarios(it[Keys.scenarios]) }
    suspend fun setScenarios(list: List<WhatIf.Scenario>) { context.dataStore.edit { it[Keys.scenarios] = SettingsJson.encodeScenarios(list) } }

    /** What an hour of your work is worth, for the Purchase simulator's "hours of work" line. Empty means not set. */
    val hourlyRate: Flow<String> = context.dataStore.data.map { it[Keys.hourlyRateCents] ?: "" }
    suspend fun setHourlyRate(text: String) { context.dataStore.edit { it[Keys.hourlyRateCents] = text } }

    // Backup settings

    /** When a copy was last saved outside the app (the reminder to back up counts from here). 0 = never. */
    val lastBackupAt: Flow<Long> = context.dataStore.data.map { it[Keys.lastBackupAt] ?: 0L }
    suspend fun setLastBackupAt(ms: Long) { context.dataStore.edit { it[Keys.lastBackupAt] = ms } }
    val autoBackup: Flow<Boolean> = context.dataStore.data.map { it[Keys.autoBackup] ?: true }
    suspend fun setAutoBackup(on: Boolean) { context.dataStore.edit { it[Keys.autoBackup] = on } }
    suspend fun autoBackupNow(): Boolean = autoBackup.first()
    val sealedPassphrase: Flow<String> = context.dataStore.data.map { it[Keys.sealedPassphrase] ?: "" }
    suspend fun sealedPassphraseNow(): String = sealedPassphrase.first()
    suspend fun setSealedPassphrase(text: String) { context.dataStore.edit { it[Keys.sealedPassphrase] = text } }

    suspend fun setHidden(hidden: Boolean) { context.dataStore.edit { it[Keys.hideAmounts] = hidden } }
    suspend fun setReportingCurrency(code: String) { context.dataStore.edit { it[Keys.reportingCurrency] = code } }
    suspend fun setCurrencySymbol(symbol: String) { context.dataStore.edit { it[Keys.currencySymbol] = symbol } }
    suspend fun markCategoriesSeeded() { context.dataStore.edit { it[Keys.didSeedCategories] = true } }
}

/** Small JSON codecs for the structured settings. Tolerant of missing or damaged data: it falls back to defaults. */
object SettingsJson {
    fun encodeConfig(c: SafeToSpend.Config): String = JSONObject().apply {
        put("horizonDays", (c.horizon as? SafeToSpend.Horizon.Days)?.n ?: 0)
        put("includeBills", c.includeBills); put("includeSavings", c.includeSavings)
        put("includeEssentials", c.includeEssentials); put("includeReserved", c.includeReserved)
        put("excluded", JSONArray(c.excludedAccountIDs))
    }.toString()

    fun decodeConfig(text: String?): SafeToSpend.Config = try {
        if (text.isNullOrBlank()) SafeToSpend.Config() else {
            val o = JSONObject(text)
            val days = o.optInt("horizonDays", 0)
            val ex = o.optJSONArray("excluded")
            SafeToSpend.Config(
                horizon = if (days > 0) SafeToSpend.Horizon.Days(days) else SafeToSpend.Horizon.EndOfMonth,
                includeBills = o.optBoolean("includeBills", true), includeSavings = o.optBoolean("includeSavings", true),
                includeEssentials = o.optBoolean("includeEssentials", true), includeReserved = o.optBoolean("includeReserved", true),
                excludedAccountIDs = (0 until (ex?.length() ?: 0)).map { ex!!.getString(it) },
            )
        }
    } catch (e: Exception) { SafeToSpend.Config() }

    fun encodeScenarios(list: List<WhatIf.Scenario>): String = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("incomeChangePercent", s.incomeChangePercent)
                put("incomeChangeAmount", s.incomeChangeAmount); put("newMonthlyCost", s.newMonthlyCost)
                s.spendingOverride?.let { put("spendingOverride", it) }
                put("oneTimeExpense", s.oneTimeExpense); put("oneTimeMonth", s.oneTimeMonth)
                s.extraGoalName?.let { put("extraGoalName", it) }; put("extraGoalMonthly", s.extraGoalMonthly)
            })
        }
    }.toString()

    fun decodeScenarios(text: String?): List<WhatIf.Scenario> = try {
        if (text.isNullOrBlank()) emptyList() else {
            val a = JSONArray(text)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                WhatIf.Scenario(
                    id = o.getString("id"), name = o.optString("name", "My scenario"), incomeChangePercent = o.optDouble("incomeChangePercent", 0.0),
                    incomeChangeAmount = o.optLong("incomeChangeAmount", 0), newMonthlyCost = o.optLong("newMonthlyCost", 0),
                    spendingOverride = if (o.has("spendingOverride")) o.getLong("spendingOverride") else null,
                    oneTimeExpense = o.optLong("oneTimeExpense", 0), oneTimeMonth = o.optInt("oneTimeMonth", 1),
                    extraGoalName = if (o.has("extraGoalName")) o.getString("extraGoalName") else null, extraGoalMonthly = o.optLong("extraGoalMonthly", 0),
                )
            }.take(WhatIf.MAX_SCENARIOS)
        }
    } catch (e: Exception) { emptyList() }
}

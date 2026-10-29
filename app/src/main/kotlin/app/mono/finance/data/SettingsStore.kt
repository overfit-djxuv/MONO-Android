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
import app.mono.domain.theme.ThemeColors
import app.mono.domain.theme.ThemePalette
import app.mono.domain.theme.ThemeSaved
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
        val appLock = booleanPreferencesKey("appLockEnabled")
        val reminders = booleanPreferencesKey("remindersEnabled")
        val reminderHour = androidx.datastore.preferences.core.intPreferencesKey("reminderHour")
        val quickActions = stringPreferencesKey("quickActions.v1")
        val themeState = stringPreferencesKey("themeState.v1")
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

    // App lock and reminders. The lock setting is deliberately not part of any backup.

    val appLock: Flow<Boolean> = context.dataStore.data.map { it[Keys.appLock] ?: false }
    suspend fun setAppLock(on: Boolean) { context.dataStore.edit { it[Keys.appLock] = on } }
    val reminders: Flow<Boolean> = context.dataStore.data.map { it[Keys.reminders] ?: false }
    suspend fun setReminders(on: Boolean) { context.dataStore.edit { it[Keys.reminders] = on } }
    /** The hour of day reminders arrive (default 9). */
    val reminderHour: Flow<Int> = context.dataStore.data.map { (it[Keys.reminderHour] ?: 9).coerceIn(0, 23) }
    suspend fun setReminderHour(h: Int) { context.dataStore.edit { it[Keys.reminderHour] = h.coerceIn(0, 23) } }
    /** null until you change them: every action is shown. */
    val quickActions: Flow<String?> = context.dataStore.data.map { it[Keys.quickActions] }
    suspend fun setQuickActions(raw: String) { context.dataStore.edit { it[Keys.quickActions] = raw } }

    // Theme: the same JSON shape the iPhone app writes (selected palette and your own palettes)

    val theme: Flow<ThemeSaved> = context.dataStore.data.map { SettingsJson.decodeTheme(it[Keys.themeState]) }
    suspend fun setTheme(t: ThemeSaved) { context.dataStore.edit { it[Keys.themeState] = SettingsJson.encodeTheme(t) } }

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

    private fun colors(c: ThemeColors) = JSONObject().put("primary", c.primary).put("secondary", c.secondary).put("background", c.background)
        .put("surface", c.surface).put("accent", c.accent).put("text", c.text).apply {
            c.secondaryText?.let { put("secondaryText", it) }; c.border?.let { put("border", it) }
        }

    private fun colors(o: JSONObject) = ThemeColors(
        o.getInt("primary"), o.getInt("secondary"), o.getInt("background"), o.getInt("surface"), o.getInt("accent"), o.getInt("text"),
        if (o.has("secondaryText")) o.getInt("secondaryText") else null, if (o.has("border")) o.getInt("border") else null,
    )

    fun encodeTheme(t: ThemeSaved): String = JSONObject().put("selectedID", t.selectedID).put("glass", 0.55)
        .put("custom", JSONArray(t.custom.map { JSONObject().put("id", it.id).put("name", it.name).put("light", colors(it.light)).put("dark", colors(it.dark)) })).toString()

    /** Palettes saved before the optional colours existed still load; damaged data falls back to the default theme. */
    fun decodeTheme(text: String?): ThemeSaved = try {
        if (text.isNullOrBlank()) ThemeSaved() else {
            val o = JSONObject(text)
            val a = o.optJSONArray("custom")
            ThemeSaved(o.optString("selectedID", ThemePalette.default.id),
                (0 until (a?.length() ?: 0)).map { val p = a!!.getJSONObject(it); ThemePalette(p.getString("id"), p.getString("name"), colors(p.getJSONObject("light")), colors(p.getJSONObject("dark"))) })
        }
    } catch (e: Exception) { ThemeSaved() }
}

package app.mono.finance.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.mono.domain.Books
import app.mono.domain.FinanceEngine
import app.mono.domain.Money
import app.mono.domain.MonthKey
import app.mono.domain.Reminders
import app.mono.domain.model.Account
import app.mono.finance.MainActivity
import app.mono.finance.R
import app.mono.finance.data.LedgerState
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Local notifications only: nothing leaves the phone. Every sync replaces all scheduled reminders, so edits, payments and
 * deletions are always reflected. Alarms are inexact (the system may shift them by a few minutes to save battery).
 */
class ReminderScheduler(private val context: Context) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val alarms get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Bills and planned expenses coming up, budget alerts and your monthly review"
                })
        }
    }

    fun canNotify(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private class Stored(val id: String, val title: String, val body: String, val fireMillis: Long)

    private fun load(): List<Stored> = try {
        val a = JSONArray(prefs.getString("scheduled", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Stored(o.getString("id"), o.getString("title"), o.getString("body"), o.getLong("fire")) }
    } catch (e: Exception) { emptyList() }

    private fun save(list: List<Stored>) {
        prefs.edit().putString("scheduled", JSONArray(list.map { JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("fire", it.fireMillis) }).toString()).apply()
    }

    private fun intentFor(s: Stored) = PendingIntent.getBroadcast(
        context, s.id.hashCode(),
        Intent(context, ReminderReceiver::class.java).putExtra("id", s.id).putExtra("title", s.title).putExtra("body", s.body),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun cancelAll() { load().forEach { alarms.cancel(intentFor(it)) }; save(emptyList()) }

    /** Arms alarms for everything still in the future (used after a restart, when the system has forgotten them). */
    fun rearmFromStore() {
        val now = System.currentTimeMillis()
        load().filter { it.fireMillis > now }.forEach { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.fireMillis, intentFor(it)) }
    }

    /** Replaces every scheduled reminder with the current set, and raises a budget alert if spending just crossed a level. */
    fun sync(ledger: LedgerState, enabled: Boolean, hour: Int, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()) {
        cancelAll()
        val books = Books(ledger.accounts, ledger.rates, ledger.transactions, ledger.entries, reporting = Money.reporting)
        val month = MonthKey.of(LocalDate.from(now))
        val summary = FinanceEngine.budgetSummary(month, ledger.monthBudgets.firstOrNull { it.monthKey == month.string }?.totalCents, ledger.limits, books.reported, now.toLocalDate(), zone)
        val key = "budgetAlertLevel-${month.string}"
        if (!enabled) { prefs.edit().putInt(key, Reminders.budgetLevel(summary.fraction)).apply(); return }
        if (!canNotify()) return

        // Budget: one alert per level; falling back below a level re-arms it.
        val (level, message) = Reminders.checkBudget(summary, prefs.getInt(key, Reminders.budgetLevel(summary.fraction)), Money::format)
        prefs.edit().putInt(key, level).apply()
        if (message != null) post("budget-${System.currentTimeMillis()}", "Budget", message)

        val byId: Map<String, Account> = ledger.accounts.associateBy { it.id }
        val defaultId = app.mono.domain.AccountEngine.defaultAccount(ledger.accounts)?.id
        val all = Reminders.plannedReminders(ledger.planned, now, hour, zone, Money::format) +
            Reminders.subscriptionReminders(ledger.rules, now, hour, zone) { r, cents ->
                Money.format(cents, currency = (r.accountID ?: defaultId)?.let { byId[it] }?.currency)
            }
        // On the 1st of each month: last month's review is ready. No amounts in the text.
        val first = now.toLocalDate().withDayOfMonth(1).let { if (it.atTime(hour, 0).isAfter(now)) it else it.plusMonths(1) }
        val review = Reminders.Pending("review-monthly-${first.year}${first.monthValue}", "Monthly review", "Your review of last month is ready.", first.atTime(hour, 0))

        val stored = (all + review).map { Stored(it.id, it.title, it.body, it.fire.atZone(zone).toInstant().toEpochMilli()) }
        save(stored)
        rearmFromStore()
    }

    fun post(id: String, title: String, body: String) {
        if (!canNotify()) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(context).notify(id.hashCode(), n) } catch (e: SecurityException) { /* permission revoked meanwhile */ }
    }

    companion object { const val CHANNEL = "reminders" }
}

package app.mono.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.Currency
import app.mono.domain.VersionHistory
import app.mono.finance.MonoViewModel
import app.mono.finance.ui.ChipRow
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.LocalMoneyContext
import app.mono.finance.ui.SectionLabel
import app.mono.finance.ui.SectionTitle
import app.mono.finance.ui.TextInput
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono
import app.mono.finance.ui.clickableNoRipple

@Composable
fun SettingsScreen(vm: MonoViewModel, onBack: () -> Unit, onVersionHistory: () -> Unit, onBackup: () -> Unit = {}, onTheme: () -> Unit = {}) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    var symbol by remember(money.symbolOverride) { mutableStateOf(money.symbolOverride) }

    ScreenFrame("Settings", onBack) {
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Currency")
            SectionLabel("Totals are shown in")
            ChipRow(Currency.all.map { it.code }, money.reporting.code, { it }) { vm.setReportingCurrency(it) }
            Text("Money in other currencies is converted with the exchange rates you enter under Accounts. Changing this changes only how totals are shown.",
                color = c.inkTertiary, fontSize = 12.sp)
            TextInput(symbol, { symbol = it.take(4); vm.setCurrencySymbol(it.take(4)) }, "Symbol override (optional)")
        }
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Privacy")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hide amounts", color = c.ink, fontSize = 16.sp)
                    Text("Figures show as dots everywhere; the eye on the Dashboard does the same.", color = c.inkSecondary, fontSize = 12.sp)
                }
                Switch(checked = money.hidden, onCheckedChange = { vm.toggleHidden() })
            }
            Text("MONO has no internet permission: your records stay on this phone unless you share a file yourself.", color = c.inkTertiary, fontSize = 12.sp)
        }
        Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Appearance")
            Row(Modifier.fillMaxWidth().clickableNoRipple(onTheme).padding(vertical = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Theme", color = c.ink, fontSize = 16.sp)
                    Text("Three built-in palettes, or make your own", color = c.inkSecondary, fontSize = 12.sp)
                }
                Text("›", color = c.inkTertiary, fontSize = 20.sp)
            }
        }
        SecuritySection(vm)
        RemindersSection(vm)
        ShortcutsSection(vm)
        Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Your data")
            Row(Modifier.fillMaxWidth().clickableNoRipple(onBackup).padding(vertical = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Backup & recovery", color = c.ink, fontSize = 16.sp)
                    Text("Save a copy, restore, and protect it with a passphrase", color = c.inkSecondary, fontSize = 12.sp)
                }
                Text("›", color = c.inkTertiary, fontSize = 20.sp)
            }
        }
        Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("About")
            Row(Modifier.fillMaxWidth().clickableNoRipple(onVersionHistory).padding(vertical = 10.dp)) {
                Text("Version history", color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(VersionHistory.current.version, color = c.inkSecondary, fontSize = 14.sp)
            }
            Hairline()
            Text("MONO for Android. Private, offline personal finance.", color = c.inkTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
fun VersionHistoryScreen(onBack: () -> Unit) {
    val c = Mono.colors
    ScreenFrame("Version history", onBack) {
        VersionHistory.releases.forEach { r ->
            Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${r.version} · ${r.date}", color = c.inkSecondary, fontSize = 13.sp)
                Text(r.title, color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                r.notes.forEach { Text("• $it", color = c.ink, fontSize = 14.sp) }
                if (r.improvements.isNotEmpty()) {
                    Text("Improvements", color = c.inkSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    r.improvements.forEach { Text("• $it", color = c.ink, fontSize = 14.sp) }
                }
                if (r.fixes.isNotEmpty()) {
                    Text("Fixes", color = c.inkSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    r.fixes.forEach { Text("• $it", color = c.ink, fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
private fun SecuritySection(vm: MonoViewModel) {
    val c = Mono.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val authenticate = app.mono.finance.security.LocalAuthenticate.current
    val enabled by vm.lockEnabled.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Lock")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lock MONO", color = c.ink, fontSize = 16.sp)
                Text("Ask for your fingerprint, face or phone PIN each time you open it.", color = c.inkSecondary, fontSize = 12.sp)
            }
            Switch(checked = enabled == true, onCheckedChange = { want ->
                if (!want) { vm.setLockEnabled(false); note = null } else {
                    val why = app.mono.finance.security.Authenticator.unavailableReason(context)
                    if (why != null) note = why else authenticate("Turn on the MONO lock") { ok -> if (ok) { vm.setLockEnabled(true); note = null } }
                }
            })
        }
        note?.let { Text(it, color = c.warning, fontSize = 12.sp) }
        Text("While the lock is on, MONO’s screen is hidden from recent apps and screenshots. The lock setting is never saved in a backup.", color = c.inkTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun RemindersSection(vm: MonoViewModel) {
    val c = Mono.colors
    val on by vm.remindersEnabled.collectAsState()
    val hour by vm.reminderHour.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { vm.setReminders(true); note = null } else note = "Notifications are off for MONO. Allow them in the phone’s settings to get reminders."
    }
    Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Reminders")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remind me", color = c.ink, fontSize = 16.sp)
                Text("Planned expenses and bills before they fall due, budget alerts at 80% and over, and your monthly review.", color = c.inkSecondary, fontSize = 12.sp)
            }
            Switch(checked = on, onCheckedChange = { want ->
                if (!want) { vm.setReminders(false); note = null }
                else if (android.os.Build.VERSION.SDK_INT >= 33) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) else vm.setReminders(true)
            })
        }
        if (on) {
            SectionLabel("Time of day")
            ChipRow((7..21).toList(), hour, { "%d:00".format(it) }) { vm.setReminderHour(it) }
        }
        note?.let { Text(it, color = c.warning, fontSize = 12.sp) }
        Text("Notifications stay on this phone. They never record or deduct anything: confirming a payment is always your tap.", color = c.inkTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun ShortcutsSection(vm: MonoViewModel) {
    val c = Mono.colors
    val raw by vm.quickActions.collectAsState()
    val shown = app.mono.domain.QuickActionSettings.enabled(raw)
    Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Launcher shortcuts")
        Text("Touch and hold MONO’s icon for these. Up to ${app.mono.domain.QuickActionSettings.MAX_SHOWN} show, in this order.", color = c.inkSecondary, fontSize = 12.sp)
        app.mono.domain.QuickAction.entries.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.title, color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(checked = a in shown, onCheckedChange = { vm.setQuickAction(a, it) })
            }
        }
    }
}

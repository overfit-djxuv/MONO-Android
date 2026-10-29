package app.mono.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun SettingsScreen(vm: MonoViewModel, onBack: () -> Unit, onVersionHistory: () -> Unit, onBackup: () -> Unit = {}) {
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

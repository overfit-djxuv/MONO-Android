package app.mono.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.Route
import app.mono.finance.ui.clickableNoRipple
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono

private data class Tool(val route: Route, val subtitle: String)

private val money = listOf(
    Tool(Route.SAFE, "What you can spend after bills, savings and essentials"),
    Tool(Route.SUBS, "Repeating payments you confirm one at a time"),
    Tool(Route.ACCOUNTS, "Accounts, transfers and exchange rates"),
)
private val understand = listOf(
    Tool(Route.INSIGHTS, "Averages, comparisons and patterns from your records"),
    Tool(Route.REVIEW, "A report on any month, shareable as text or CSV"),
)
private val tryIt = listOf(
    Tool(Route.SIMULATOR, "What a purchase would do before you buy it"),
    Tool(Route.WHATIF, "Try an idea on paper; nothing is changed"),
)
private val app = listOf(
    Tool(Route.RECEIPTS, "Scan or photograph a receipt; it is read on this phone"),
    Tool(Route.CSV, "Bring in a bank statement or spreadsheet; duplicates are skipped"),
    Tool(Route.BACKUP, "Save a copy, restore, and protect it with a passphrase"),
    Tool(Route.SETTINGS, "Currency, privacy and more"),
)

@Composable
fun ToolsScreen(onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val c = Mono.colors
    ScreenFrame("Tools", onBack) {
        listOf("Your money" to money, "Understand it" to understand, "Try it first" to tryIt, "App" to app).forEach { (title, tools) ->
            Column(Modifier.fillMaxWidth().surface()) {
                Text(title, color = c.inkSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp))
                tools.forEachIndexed { i, t ->
                    Row(Modifier.fillMaxWidth().clickableNoRipple { onOpen(t.route) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t.route.title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(t.subtitle, color = c.inkSecondary, fontSize = 13.sp)
                        }
                        Text("›", color = c.inkTertiary, fontSize = 20.sp)
                    }
                    if (i < tools.size - 1) Hairline()
                }
            }
        }
    }
}

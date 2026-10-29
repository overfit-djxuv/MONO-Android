package app.mono.finance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.Money
import app.mono.finance.ui.theme.Mono

/** Same tab order as the iOS app: Dashboard, Transactions, Budget, Goals, Shopping. */
enum class AppTab(val title: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Outlined.Home),
    TRANSACTIONS("Transactions", Icons.Outlined.AccountBalanceWallet),
    BUDGET("Budget", Icons.Outlined.PieChart),
    GOALS("Goals", Icons.Outlined.Flag),
    SHOPPING("Shopping", Icons.Outlined.ShoppingCart),
}

@Composable
fun MainShell() {
    var tab by rememberSaveable { mutableStateOf(AppTab.DASHBOARD) }
    val c = Mono.colors
    Scaffold(
        containerColor = c.bg,
        bottomBar = {
            NavigationBar(containerColor = c.surface) {
                AppTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.title) },
                        label = { Text(t.title, maxLines = 1, softWrap = false, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = c.accent, selectedTextColor = c.accent,
                            unselectedIconColor = c.inkSecondary, unselectedTextColor = c.inkSecondary,
                            indicatorColor = c.accentSoft,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        // Stage 1 placeholders. Each tab is replaced by its real screen in stage 3.
        Column(
            Modifier.fillMaxSize().padding(padding).padding(Mono.gutter),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(tab.title, color = c.ink, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
            Text(Money.format(0), color = c.inkSecondary, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

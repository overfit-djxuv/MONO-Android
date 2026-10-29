package app.mono.finance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.TxKind
import app.mono.finance.MonoViewModel
import app.mono.finance.ui.screens.DashboardScreen
import app.mono.finance.ui.screens.TransactionForm
import app.mono.finance.ui.screens.TransactionsScreen
import app.mono.finance.ui.theme.Mono

/** Same tab order as the iOS app: Dashboard, Transactions, Budget, Goals, Shopping. */
enum class AppTab(val title: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Outlined.Home),
    TRANSACTIONS("Transactions", Icons.Outlined.AccountBalanceWallet),
    BUDGET("Budget", Icons.Outlined.PieChart),
    GOALS("Goals", Icons.Outlined.Flag),
    SHOPPING("Shopping", Icons.Outlined.ShoppingCart),
}

/** What the add/edit sheet is doing right now. */
private sealed interface FormTarget {
    data object None : FormTarget
    data object New : FormTarget
    data class Edit(val id: String) : FormTarget
}

@Composable
fun MainShell(vm: MonoViewModel) {
    var tab by rememberSaveable { mutableStateOf(AppTab.DASHBOARD) }
    var form by androidx.compose.runtime.remember { mutableStateOf<FormTarget>(FormTarget.None) }
    val ledger by vm.ledger.collectAsState()
    val money by vm.moneyContext.collectAsState()
    val c = Mono.colors

    Scaffold(
        containerColor = c.bg,
        floatingActionButton = {
            if (tab == AppTab.DASHBOARD || tab == AppTab.TRANSACTIONS) {
                FloatingActionButton(onClick = { form = FormTarget.New }, containerColor = c.primary, contentColor = c.onPrimary) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add transaction")
                }
            }
        },
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AppTab.DASHBOARD -> DashboardScreen(
                    ledger = ledger, hidden = money.hidden, onToggleHidden = vm::toggleHidden,
                    onOpenTransactions = { tab = AppTab.TRANSACTIONS }, onAdd = { form = FormTarget.New },
                    onEdit = { form = FormTarget.Edit(it) },
                )
                AppTab.TRANSACTIONS -> TransactionsScreen(ledger, onEdit = { form = FormTarget.Edit(it) })
                else -> ComingSoon(tab.title)
            }
        }
    }

    val l = ledger
    if (l != null) when (val f = form) {
        FormTarget.None -> {}
        FormTarget.New -> TransactionForm(l, null, TxKind.EXPENSE, vm::saveTransaction, vm::deleteTransaction) { form = FormTarget.None }
        is FormTarget.Edit -> l.transactions.firstOrNull { it.id == f.id }?.let {
            TransactionForm(l, it, it.kind, vm::saveTransaction, vm::deleteTransaction) { form = FormTarget.None }
        } ?: run { form = FormTarget.None }
    }
}

@Composable
private fun ComingSoon(title: String) {
    val c = Mono.colors
    Column(Modifier.fillMaxSize().padding(Mono.gutter), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("This screen is being ported from the iPhone app.", color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

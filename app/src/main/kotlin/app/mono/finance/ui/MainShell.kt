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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.QuickAction
import app.mono.domain.TxKind
import app.mono.finance.MonoViewModel
import app.mono.finance.ui.screens.CsvImportScreen
import app.mono.finance.ui.screens.AccountsScreen
import app.mono.finance.ui.screens.BackupScreen
import app.mono.finance.ui.screens.BudgetScreen
import app.mono.finance.ui.screens.InsightsScreen
import app.mono.finance.ui.screens.PurchaseSimulatorScreen
import app.mono.finance.ui.screens.ReviewScreen
import app.mono.finance.ui.screens.SafeToSpendScreen
import app.mono.finance.ui.screens.SettingsScreen
import app.mono.finance.ui.screens.SubscriptionsScreen
import app.mono.finance.ui.screens.ToolsScreen
import app.mono.finance.ui.screens.VersionHistoryScreen
import app.mono.finance.ui.screens.WhatIfScreen
import app.mono.finance.ui.screens.DashboardScreen
import app.mono.finance.ui.screens.GoalsScreen
import app.mono.finance.ui.screens.ShoppingScreen
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
    data class New(val kind: TxKind = TxKind.EXPENSE) : FormTarget
    data class Edit(val id: String) : FormTarget
}

@Composable
fun MainShell(vm: MonoViewModel) {
    var tab by rememberSaveable { mutableStateOf(AppTab.DASHBOARD) }
    var form by remember { mutableStateOf<FormTarget>(FormTarget.None) }
    // Screens opened from the Dashboard or Tools sit on a small stack above the tabs; Back pops one.
    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = stackText.split(",").filter { it.isNotEmpty() }.mapNotNull { n -> Route.entries.firstOrNull { it.name == n } }
    fun push(r: Route) {
        when (r) {
            Route.BUDGET -> { stackText = ""; tab = AppTab.BUDGET }
            Route.GOALS -> { stackText = ""; tab = AppTab.GOALS }
            else -> stackText = (stack + r).joinToString(",") { it.name }
        }
    }
    fun pop() { stackText = stack.dropLast(1).joinToString(",") { it.name } }
    BackHandler(enabled = stack.isNotEmpty()) { pop() }
    val config by vm.safeConfig.collectAsState()
    var openTransfer by remember { mutableStateOf(false) }
    // A launcher shortcut's request is carried out once the app is unlocked.
    val locked by vm.locked.collectAsState()
    val pendingAction by vm.pendingAction.collectAsState()
    LaunchedEffect(locked, pendingAction) {
        val a = vm.takeAction() ?: return@LaunchedEffect
        when (a) {
            QuickAction.ADD_EXPENSE -> { stackText = ""; form = FormTarget.New(TxKind.EXPENSE) }
            QuickAction.ADD_INCOME -> { stackText = ""; form = FormTarget.New(TxKind.INCOME) }
            QuickAction.TRANSFER -> { openTransfer = true; stackText = Route.ACCOUNTS.name }
            QuickAction.SAFE_TO_SPEND -> stackText = Route.SAFE.name
            QuickAction.SCAN_RECEIPT -> stackText = Route.RECEIPTS.name
        }
    }
    val lastBackup by vm.lastBackupAt.collectAsState()
    val ledger by vm.ledger.collectAsState()
    val money by vm.moneyContext.collectAsState()
    val c = Mono.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (stack.isEmpty() && (tab == AppTab.DASHBOARD || tab == AppTab.TRANSACTIONS)) {
                FloatingActionButton(onClick = { form = FormTarget.New() }, containerColor = c.primary, contentColor = c.onPrimary) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add transaction")
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = c.surface) {
                AppTab.entries.forEach { t ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("tab-${t.title}"),
                        selected = tab == t,
                        onClick = { stackText = ""; tab = t },
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
            val top = stack.lastOrNull()
            if (top != null) when (top) {
                Route.TOOLS -> ToolsScreen(onOpen = ::push, onBack = ::pop)
                Route.SAFE -> SafeToSpendScreen(ledger, vm, ::pop)
                Route.SUBS -> SubscriptionsScreen(ledger, vm, ::pop)
                Route.INSIGHTS -> InsightsScreen(ledger, ::pop)
                Route.REVIEW -> ReviewScreen(ledger, ::pop)
                Route.SIMULATOR -> PurchaseSimulatorScreen(ledger, vm, ::pop)
                Route.WHATIF -> WhatIfScreen(ledger, vm, ::pop)
                Route.ACCOUNTS -> AccountsScreen(ledger, vm, { openTransfer = false; pop() }, initialTransfer = openTransfer)
                Route.SETTINGS -> SettingsScreen(vm, ::pop, { push(Route.HISTORY) }, { push(Route.BACKUP) }, { push(Route.THEME) })
                Route.HISTORY -> VersionHistoryScreen(::pop)
                Route.BACKUP -> BackupScreen(vm, ::pop)
                Route.THEME -> app.mono.finance.ui.screens.ThemeScreen(vm, ::pop)
                Route.CSV -> CsvImportScreen(ledger, vm, ::pop)
                Route.RECEIPTS -> ComingSoon("Receipts", ::pop)
                Route.BUDGET, Route.GOALS -> {}
            } else when (tab) {
                AppTab.DASHBOARD -> DashboardScreen(
                    ledger = ledger, hidden = money.hidden, onToggleHidden = vm::toggleHidden,
                    onOpenTransactions = { tab = AppTab.TRANSACTIONS }, onAdd = { form = FormTarget.New() },
                    onEdit = { form = FormTarget.Edit(it) }, onOpen = ::push, onOpenGoals = { push(Route.GOALS) }, config = config, lastBackupAt = lastBackup,
                )
                AppTab.TRANSACTIONS -> TransactionsScreen(ledger, onEdit = { form = FormTarget.Edit(it) })
                AppTab.BUDGET -> BudgetScreen(ledger, vm)
                AppTab.GOALS -> GoalsScreen(ledger, vm)
                AppTab.SHOPPING -> ShoppingScreen(ledger, vm)
            }
        }
    }

    val l = ledger
    if (l != null) when (val f = form) {
        FormTarget.None -> {}
        is FormTarget.New -> TransactionForm(l, null, f.kind, vm::saveTransaction, vm::deleteTransaction) { form = FormTarget.None }
        is FormTarget.Edit -> l.transactions.firstOrNull { it.id == f.id }?.let {
            TransactionForm(l, it, it.kind, vm::saveTransaction, vm::deleteTransaction) { form = FormTarget.None }
        } ?: run { form = FormTarget.None }
    }
}

@Composable
private fun ComingSoon(title: String, onBack: () -> Unit) {
    app.mono.finance.ui.screens.ScreenFrame(title, onBack) {
        androidx.compose.material3.Text("This screen is on its way.", color = Mono.colors.inkSecondary)
    }
}

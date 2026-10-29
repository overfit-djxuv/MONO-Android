package app.mono.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.Books
import app.mono.domain.FinanceEngine
import app.mono.domain.MonthKey
import app.mono.finance.data.LedgerState
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.LocalMoneyContext
import app.mono.finance.ui.StatTile
import app.mono.finance.ui.TransactionRow
import app.mono.finance.ui.clickableNoRipple
import app.mono.finance.ui.fmt
import app.mono.finance.ui.theme.Mono
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface Row2 {
    data class Header(val label: String, val net: Long) : Row2
    data class Item(val id: String) : Row2
}

@Composable
fun TransactionsScreen(ledger: LedgerState?, onEdit: (String) -> Unit) {
    val c = Mono.colors
    val money = LocalMoneyContext.current
    Column(Modifier.fillMaxSize()) {
        Text("Transactions", color = c.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = Mono.gutter).padding(top = 12.dp, bottom = 8.dp))
        if (ledger == null) return@Column
        val month = remember { MonthKey.of(LocalDate.now()) }
        val books = remember(ledger, money) { Books(ledger.accounts, ledger.rates, ledger.transactions, ledger.entries, reporting = money.reporting) }
        val (income, expense) = FinanceEngine.totals(books.reported, month)

        Row(Modifier.fillMaxWidth().padding(horizontal = Mono.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Income", fmt(income), tint = c.income, modifier = Modifier.weight(1f), valueSize = 15)
            StatTile("Spent", fmt(expense), modifier = Modifier.weight(1f), valueSize = 15)
            StatTile("Net", fmt(income - expense, signed = true), modifier = Modifier.weight(1f), valueSize = 15)
        }

        if (ledger.transactions.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(Mono.gutter), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No transactions yet", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text("Tap + to add your first income or expense.", color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            return@Column
        }

        val zone = ZoneId.systemDefault()
        val byId = ledger.transactions.associateBy { it.id }
        val symbols = ledger.categories.associate { it.name to it.symbol }
        val rows = remember(ledger.transactions) {
            val out = ArrayList<Row2>()
            var lastDay: LocalDate? = null
            val days = ledger.transactions.groupBy { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }
            for (t in ledger.transactions) {
                val d = Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate()
                if (d != lastDay) {
                    val net = days[d].orEmpty().sumOf {
                        when (it.kind) { app.mono.domain.TxKind.INCOME -> it.amountCents; app.mono.domain.TxKind.EXPENSE -> -it.amountCents; else -> 0L }
                    }
                    out += Row2.Header(d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())), net)
                    lastDay = d
                }
                out += Row2.Item(t.id)
            }
            out
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = Mono.gutter), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp, top = 8.dp)) {
            items(rows, key = { when (it) { is Row2.Header -> "h-${it.label}"; is Row2.Item -> it.id } }) { r ->
                when (r) {
                    is Row2.Header -> Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp)) {
                        Text(r.label, color = c.inkSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(fmt(r.net, signed = true), color = c.inkTertiary, fontSize = 13.sp)
                    }
                    is Row2.Item -> byId[r.id]?.let { tx ->
                        Column {
                            TransactionRow(tx, symbols[tx.category] ?: "tag", Modifier.clickableNoRipple { onEdit(tx.id) })
                            Hairline()
                        }
                    }
                }
            }
        }
    }
}

package app.mono.finance

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import app.mono.domain.CsvImport
import app.mono.domain.model.TransactionRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TransactionsExtrasTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonoApp
    private val dao get() = app.db.dao()

    @Test fun searchOpensDetailsAndRepeatAddsATodayCopy() {
        val tx = TransactionRecord(amountCents = 1_234, kindRaw = "expense", category = "Food", date = System.currentTimeMillis(), title = "ZZ Search Lunch", accountID = null)
        runBlocking { dao.put(tx) }
        try {
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Transactions").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("tab-Transactions").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Search transactions").fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasText("Search transactions")).performTextInput("zz search")
            rule.waitUntil(10_000) { rule.onAllNodesWithText("ZZ Search Lunch").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("ZZ Search Lunch").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Repeat for today").fetchSemanticsNodes().isNotEmpty() }
            val before = runBlocking { dao.transactionCount() }
            rule.onNodeWithText("Repeat for today").performClick()
            rule.waitUntil(10_000) { runBlocking { dao.transactionCount() } == before + 1 }
        } finally {
            runBlocking { dao.transactions().first().filter { it.title == "ZZ Search Lunch" }.forEach { dao.delete(it) } }
        }
    }

    @Test fun aCsvImportAddsRowsOnceAndTheSameFileAgainAddsNothing() {
        val records = CsvImport.parse("Date,Description,Amount,Category\n2026-03-02,ZZ Csv Taxi,-9.00,ZZ Transport\n2026-03-03,ZZ Csv Bonus,50.00,ZZ Transport\n")
        val m = CsvImport.autoMapping(records)
        try {
            runBlocking {
                val cats = dao.categories().first(); val txs = dao.transactions().first()
                val plan = CsvImport.plan(records, m, txs, cats, createMissingCategories = true)
                assertEquals(2, plan.rows.size); assertEquals(2, plan.newCategories.size)
                assertEquals(2, app.repository.applyCsvImport(plan, cats, null))
                val again = CsvImport.plan(records, m, dao.transactions().first(), dao.categories().first(), createMissingCategories = true)
                assertEquals(0, again.rows.size); assertEquals(2, again.duplicates); assertEquals(0, again.newCategories.size)
                assertEquals(2, dao.transactions().first().count { it.title.startsWith("ZZ Csv") })
            }
        } finally {
            runBlocking {
                dao.transactions().first().filter { it.title.startsWith("ZZ Csv") }.forEach { dao.delete(it) }
                dao.categories().first().filter { it.name == "ZZ Transport" }.forEach { app.db.openHelper.writableDatabase.execSQL("DELETE FROM MoneyCategory WHERE pk = ?", arrayOf(it.pk)) }
            }
        }
    }
}

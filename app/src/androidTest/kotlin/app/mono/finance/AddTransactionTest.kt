package app.mono.finance

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/** Drives the real UI on a device: add an expense through the form and check it reached the database. */
class AddTransactionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val dao get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonoApp).db.dao()

    @Test fun addingAnExpenseSavesItAndRejectsBadInput() {
        val before = runBlocking { dao.transactionCount() }

        rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Add transaction").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Add transaction").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("New transaction").fetchSemanticsNodes().isNotEmpty() }

        // Nothing can be saved until there is a valid amount and a category.
        rule.onNodeWithText("Save").assertIsNotEnabled()

        rule.onNodeWithTag("amount").performTextInput("1.234") // too many decimals: rejected, never rounded
        rule.onNode(hasText("Food") and hasClickAction()).performClick() // the chip, not the Dashboard row behind the sheet
        rule.onNodeWithText("Save").assertIsNotEnabled()

        rule.onNodeWithTag("amount").performTextReplacement("12.50")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Save").performScrollTo().performClick() // the keyboard may cover it until it is scrolled into view
        rule.waitUntil(5_000) { rule.onAllNodesWithText("New transaction").fetchSemanticsNodes().isEmpty() } // the sheet closes on save

        rule.waitUntil(10_000) { runBlocking { dao.transactionCount() } == before + 1 }
        val saved = runBlocking { dao.latestTransaction() }
        assertEquals(1250L, saved.amountCents)
        assertEquals("expense", saved.kindRaw)
        assertEquals("Food", saved.category)
        assertNotNull("belongs to the default account", saved.accountID)
        runBlocking { dao.deleteTransaction(saved.id) } // leave the shared emulator data as it was
    }
}

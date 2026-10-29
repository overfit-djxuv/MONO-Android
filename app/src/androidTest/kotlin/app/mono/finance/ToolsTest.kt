package app.mono.finance

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import app.mono.finance.data.DemoData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Opens each Tools screen with sample data (Hide amounts off) and checks that real content renders. */
class ToolsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonoApp

    @Before fun sampleData() = runBlocking { app.settings.setHidden(false); DemoData.load(app.db.dao()) }

    private fun waitFor(text: String, timeout: Long = 15_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun openTool(name: String) {
        rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Tools").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Tools").performClick()
        waitFor("Your money")
        rule.onNodeWithText(name).performScrollTo().performClick() // lower entries start below the visible area
    }

    @Test fun safeToSpendExplainsTheSum() {
        openTool("Safe to spend")
        waitFor("How it adds up")
        waitFor("Money in your accounts")
        waitFor("Bills and payments due")
        rule.onNodeWithText("Reserved funds").performScrollTo()
    }

    @Test fun insightsAndReviewWorkFromTheRecords() {
        openTool("Insights")
        waitFor("Average spent per day")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("Your money")
        rule.onNodeWithText("Monthly review").performScrollTo().performClick()
        waitFor("Net cash flow")
        waitFor("Spending by category")
    }

    @Test fun purchaseSimulatorGivesAVerdict() {
        openTool("Purchase simulator")
        waitFor("Enter a price")
        rule.onNodeWithText("Price (RM)", substring = true).performTextInput("50")
        waitFor("Your money")
        waitFor("If you wait 30 days")
    }

    @Test fun whatIfAndAccountsAndSettingsOpen() {
        openTool("What-if lab")
        waitFor("Your typical month")
        waitFor("In 12 months")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("Your money")
        rule.onNodeWithText("Accounts & currency").performScrollTo().performClick()
        waitFor("Exchange rates")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("Your money")
        rule.onNodeWithText("Settings").performScrollTo().performClick()
        waitFor("Hide amounts")
    }

    @Test fun aSubscriptionIsAddedButNothingIsDeductedUntilConfirmed() {
        openTool("Subscriptions & bills")
        waitFor("Nothing is deducted automatically")
        val txBefore = runBlocking { app.db.dao().transactionCount() }
        rule.onNodeWithText("Add one").performClick()
        waitFor("New subscription or bill")
        rule.onNodeWithText("Name").performTextInput("Test streaming")
        rule.onNodeWithText("Amount each time", substring = true).performTextInput("15.99")
        rule.onNode(hasText("Save") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { app.db.dao().ruleByTitle("Test streaming") } != null }
        assertEquals("a schedule is not a transaction", txBefore, runBlocking { app.db.dao().transactionCount() })
        runBlocking { app.db.dao().ruleByTitle("Test streaming")?.let { app.db.dao().delete(it) } }
    }
}

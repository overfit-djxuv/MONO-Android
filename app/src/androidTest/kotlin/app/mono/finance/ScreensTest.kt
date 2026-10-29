package app.mono.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import app.mono.finance.data.DemoData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Walks the Budget, Goals and Shopping tabs with sample data (Hide amounts off) and exercises one write on each. */
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonoApp

    @Before fun sampleData() = runBlocking {
        app.settings.setHidden(false)
        DemoData.load(app.db.dao())
    }

    private fun tab(name: String) {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("tab-$name").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tab-$name").performClick()
    }

    private fun waitFor(text: String, timeout: Long = 10_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun budgetShowsTheMonthAndItsCategories() {
        tab("Budget")
        waitFor("Left to spend")
        rule.onNodeWithText("Categories").assertIsDisplayed()
        waitFor("of RM 400.00") // the Food limit from the sample data (spent varies with other tests)
        rule.onNodeWithText("Planned expenses").performScrollTo().assertIsDisplayed()
        waitFor("Car insurance")
    }

    @Test fun goalsShowTargetsFromCostsAndMoneyCanBeAdded() {
        tab("Goals")
        waitFor("Set aside across")
        waitFor("RM 1,800.00 of RM 5,300.00") // trip: 2,200 + 1,800 + 800 costs + 500 buffer; 1,800 saved
        waitFor("RM 2,000.00 of RM 15,000.00")
        val before = runBlocking { app.db.dao().latestGoalEntryTotal() }

        rule.onNodeWithText("New laptop").performClick()
        waitFor("Plan details")
        rule.onNode(hasText("Add money") and hasClickAction()).performClick()
        rule.onNodeWithTag("moveAmount").performTextInput("250")
        rule.onNodeWithTag("confirmMove").performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { app.db.dao().latestGoalEntryTotal() } == before + 25_000L }
        assertEquals(before + 25_000L, runBlocking { app.db.dao().latestGoalEntryTotal() })
    }

    @Test fun shoppingQuickAddParsesQuantityAndPrice() {
        tab("Shopping")
        waitFor("Eggs")
        rule.onNodeWithTag("quickAddItem").performTextInput("Bread 2 3.50")
        rule.onNodeWithTag("quickAddItem").performImeAction()
        waitFor("Bread")
        waitFor("2 × RM 3.50")
        val bread = runBlocking { app.db.dao().itemByName("Bread") }
        assertEquals(2, bread.quantity)
        assertEquals(350L, bread.estimatedCents)
        runBlocking { app.db.dao().delete(bread) } // leave the sample list as it was
    }
}

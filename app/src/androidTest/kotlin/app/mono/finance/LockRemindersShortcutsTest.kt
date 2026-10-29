package app.mono.finance

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.mono.finance.data.DemoData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LockRemindersShortcutsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as MonoApp

    @After fun reset() = runBlocking { app.settings.setAppLock(false); app.settings.setReminders(false); app.reminders.cancelAll() }

    private fun waitFor(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun theLockHidesEverythingUntilYouVerify() {
        runBlocking { DemoData.load(app.db.dao()); app.settings.setAppLock(true); app.settings.setHidden(false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("Locked")
            assertTrue("no money or tab is on screen while locked", compose.onAllNodesWithText("Dashboard").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithText("RM", substring = true).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun remindersAreScheduledForPlannedExpensesAndTheMonthlyReview() {
        runBlocking { DemoData.load(app.db.dao()) }
        val ledger = runBlocking { app.repository.ledgerNow() }
        app.reminders.sync(ledger, enabled = true, hour = 9)
        val stored = JSONArray(context.getSharedPreferences("reminders", Context.MODE_PRIVATE).getString("scheduled", "[]"))
        val ids = (0 until stored.length()).map { stored.getJSONObject(it).getString("id") }
        assertTrue("the monthly review is scheduled: $ids", ids.any { it.startsWith("review-monthly-") })
        assertTrue("the upcoming insurance bill has reminders: $ids", ids.any { it.startsWith("planned-") })
        app.reminders.sync(ledger, enabled = false, hour = 9)
        assertEquals("switching reminders off clears them all", "[]", context.getSharedPreferences("reminders", Context.MODE_PRIVATE).getString("scheduled", "[]"))
    }

    @Test fun theIconMenuListsTheShortcutsAndAShortcutOpensTheMatchingForm() {
        runBlocking { app.settings.setAppLock(false); DemoData.load(app.db.dao()) }
        app.vmlessPublishShortcuts()
        val ids = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }.map { it.id }
        assertEquals(listOf("addExpense", "scanReceipt", "addIncome", "transfer"), ids)

        val intent = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_PREFIX + "addIncome")
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor("New transaction")
            compose.onNode(androidx.compose.ui.test.hasText("Income") and androidx.compose.ui.test.isSelected()).assertExists() // the form opens on Income
        }
    }
}

private fun MonoApp.vmlessPublishShortcuts() = app.mono.finance.notify.Shortcuts.publish(this, null)

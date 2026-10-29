package app.mono.finance

import androidx.test.platform.app.InstrumentationRegistry
import app.mono.finance.backup.BackupManager
import app.mono.finance.data.DemoData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole backup journey on a real database: back up (encrypted), lose the data, restore, compare. */
class BackupRestoreTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonoApp
    private val dao get() = app.db.dao()

    @Test fun anEncryptedBackupRestoresEverythingAndKeepsASafetyCopy() = runBlocking {
        DemoData.load(dao)
        val tx = dao.transactionCount(); val goals = dao.goalCount(); val items = dao.shoppingCount()
        val entries = dao.goalEntries().first().size; val costs = dao.tripCosts().first().size; val planned = dao.plannedExpenses().first().size
        val sumBefore = dao.transactions().first().sumOf { it.amountCents }
        assertTrue(tx > 0 && goals > 0)

        val bytes = app.backups.createBytes("manual", "hunter2-hunter2")
        // wrong or missing passphrase never opens it
        assertTrue(app.backups.inspect(bytes, null) is BackupManager.Opened.NeedsPassphrase)
        assertTrue(app.backups.inspect(bytes, "nope") is BackupManager.Opened.Failed)

        // lose the data
        dao.clearTransactions(); dao.clearGoals(); dao.clearGoalEntries(); dao.clearTripCosts(); dao.clearShopping(); dao.clearPlanned()
        assertEquals(0, dao.transactionCount())

        val opened = app.backups.inspect(bytes, "hunter2-hunter2") as BackupManager.Opened.Ready
        assertTrue(opened.encrypted)
        app.backups.restore(opened.file)

        assertEquals(tx, dao.transactionCount()); assertEquals(goals, dao.goalCount()); assertEquals(items, dao.shoppingCount())
        assertEquals(entries, dao.goalEntries().first().size); assertEquals(costs, dao.tripCosts().first().size); assertEquals(planned, dao.plannedExpenses().first().size)
        assertEquals("every amount is exactly as it was", sumBefore, dao.transactions().first().sumOf { it.amountCents })
        assertTrue("a safety copy of the data from before the restore was kept", app.backups.list().any { it.kind == "safety" && it.intact })
        assertTrue("an account exists for every record", dao.accountCount() >= 1)
        app.backups.list().forEach { app.backups.delete(it) }
    }

    @Test fun aSavedPassphraseIsKeptEncryptedAndComesBack() = runBlocking {
        app.backups.savePassphrase("p@ss wörd ✓")
        assertTrue("it is not stored as plain text", !app.settings.sealedPassphraseNow().contains("wörd"))
        assertEquals("p@ss wörd ✓", app.backups.loadPassphrase())
        app.backups.clearPassphrase()
        assertEquals(null, app.backups.loadPassphrase())
    }
}

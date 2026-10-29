package app.mono.finance.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Cross-platform check, run only when MONO_INTEROP_DIR is set: writes backups for the iOS code to open, and opens the ones the iOS code
 * wrote. (The files come from a standalone Swift program built from the iOS app's own backup code.)
 */
class InteropTest {
    private val dir = System.getenv("MONO_INTEROP_DIR")?.let(::File)

    @Test fun writeBackupsForIos() {
        assumeTrue(dir != null)
        val file = BackupTest().let { it.javaClass.getDeclaredMethod("sample").apply { isAccessible = true }.invoke(it) as BackupFile }
        File(dir, "android-plain.monobackup").writeBytes(BackupVault.encode(BackupVault.makeEnvelope(file, "manual", null, 1_793_000_000_000, "0.2.0")))
        File(dir, "android-locked.monobackup").writeBytes(BackupVault.encode(BackupVault.makeEnvelope(file, "manual", "pässword ✓", 1_793_000_000_000, "0.2.0", iterations = 20_000)))
        File(dir, "android-plain.json").writeText(BackupCodec.encode(file))
    }

    @Test fun openBackupsWrittenByIos() {
        assumeTrue(dir != null && File(dir, "ios-locked.monobackup").exists())
        val plain = BackupVault.open(File(dir, "ios-plain.monobackup").readBytes(), null)
        val locked = BackupVault.open(File(dir, "ios-locked.monobackup").readBytes(), "pässword ✓")
        assertEquals(2, plain.transactions.size)
        assertEquals(plain.transactions, locked.transactions)
        assertEquals("Lunch, with “quotes”", plain.transactions.first { it.kindRaw == "expense" }.title)
        assertEquals(1_793_000_000_123L, plain.transactions[0].date) // milliseconds survive the Swift encoder too
        assertTrue(plain.goals.isNotEmpty())
        assertEquals(1, plain.accounts?.size)
    }
}

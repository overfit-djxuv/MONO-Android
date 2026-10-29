package app.mono.finance.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.mono.domain.Money
import app.mono.domain.VersionHistory
import app.mono.finance.MonoApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Creates, proves, stores and restores backups.
 *  - A backup is only reported as made after it has been read back and compared with what went in.
 *  - Files are written to a temporary name and renamed into place, so a crash can't leave half a backup.
 *  - Backups kept inside the app disappear if the app is uninstalled; "Back up now" saves a copy wherever you choose.
 *  - The optional passphrase is kept encrypted by the Android Keystore, never in a backup file.
 */
class BackupManager(private val app: MonoApp) {
    private val dir get() = File(app.filesDir, "backups").also { it.mkdirs() }
    private val repo get() = app.repository

    data class Entry(
        val file: File, val createdAt: Long, val kind: String, val encrypted: Boolean, val summary: BackupVault.Summary, val intact: Boolean,
    ) { val name: String get() = file.name; val size: Long get() = file.length() }

    // Creating

    /** A complete, verified backup as file bytes. Throws if the backup could not be proven restorable. */
    suspend fun createBytes(kind: String, passphrase: String?, nowMillis: Long = System.currentTimeMillis()): ByteArray = withContext(Dispatchers.Default) {
        val snap = repo.snapshot(0, Money.symbol, Money.reporting.code, nowMillis)
        val photos = snap.receipts.orEmpty().mapNotNull { r -> app.receiptStore.data(r.imageFile)?.let { r.imageFile to it } }.toMap()
        val file = snap.copy(receiptPhotos = photos)
        val bad = BackupVault.roundTripMismatches(file)
        if (bad.isNotEmpty()) throw BackupVault.VaultException(BackupVault.Code.RESTORE_CHECK_FAILED,
            "The backup didn’t pass its restore test, so it was not kept: ${bad.joinToString("; ")}")
        BackupVault.encode(BackupVault.makeEnvelope(file, kind, passphrase, nowMillis, VersionHistory.current.version))
    }

    fun suggestedName(nowMillis: Long = System.currentTimeMillis(), encrypted: Boolean): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis))
        return "MONO-backup-$stamp${if (encrypted) "-locked" else ""}.${BackupVault.EXTENSION}"
    }

    // In-app storage

    /** Writes atomically, reads back and verifies. On any problem nothing is left behind. */
    fun saveInternal(bytes: ByteArray, kind: String, encrypted: Boolean, nowMillis: Long = System.currentTimeMillis()): File {
        val prefix = if (kind == "safety") "MONO-before-restore" else "MONO-backup"
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis))
        val target = File(dir, "$prefix-$stamp${if (encrypted) "-locked" else ""}.${BackupVault.EXTENSION}")
        val tmp = File(dir, target.name + ".tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.readBytes().contentEquals(bytes)) throw BackupVault.VaultException(BackupVault.Code.CORRUPT, "what was written doesn’t match what was saved")
            BackupVault.readEnvelope(tmp.readBytes())
            if (!tmp.renameTo(target)) throw BackupVault.VaultException(BackupVault.Code.CORRUPT, "the backup couldn’t be saved")
        } finally { tmp.delete() }
        return target
    }

    fun list(): List<Entry> = (dir.listFiles { f -> f.name.endsWith(".${BackupVault.EXTENSION}") } ?: emptyArray()).mapNotNull { f ->
        try {
            val e = BackupVault.readEnvelope(f.readBytes())
            Entry(f, e.createdAt, e.kind, e.encrypted, e.summary, true)
        } catch (ex: Exception) { Entry(f, f.lastModified(), "damaged", false, BackupVault.Summary(), false) }
    }.sortedByDescending { it.createdAt }

    fun delete(entry: Entry) { entry.file.delete() }

    /** Keeps the newest [keepAuto] automatic backups; manual ones and the safety copy before a restore are never pruned. */
    fun prune(keepAuto: Int = 7) { list().filter { it.kind == "auto" }.drop(keepAuto).forEach { delete(it) } }

    /** Called when the app opens: one automatic in-app backup a day, with the saved passphrase if there is one. */
    suspend fun autoIfDue(nowMillis: Long = System.currentTimeMillis()) {
        if (!app.settings.autoBackupNow()) return
        val last = list().firstOrNull { it.kind == "auto" }?.createdAt ?: 0
        if (nowMillis - last < 20L * 3_600_000) return
        if (app.repository.hasAnyRecords().not()) return
        val pass = loadPassphrase()
        val bytes = createBytes("auto", pass, nowMillis)
        saveInternal(bytes, "auto", pass != null, nowMillis)
        prune()
    }

    // Restoring

    sealed interface Opened {
        data class Ready(val file: BackupFile, val encrypted: Boolean) : Opened
        data object NeedsPassphrase : Opened
        data class Failed(val message: String) : Opened
    }

    fun inspect(bytes: ByteArray, passphrase: String?): Opened = try {
        val encrypted = try { BackupVault.readEnvelope(bytes).encrypted } catch (e: BackupVault.VaultException) { if (e.code == BackupVault.Code.NOT_A_BACKUP) false else throw e }
        Opened.Ready(BackupVault.open(bytes, passphrase), encrypted)
    } catch (e: BackupVault.VaultException) {
        if (e.code == BackupVault.Code.PASSPHRASE_REQUIRED) Opened.NeedsPassphrase else Opened.Failed(e.message ?: "The backup couldn’t be opened.")
    } catch (e: BackupException) { Opened.Failed(e.message ?: "The backup couldn’t be opened.") }
    catch (e: Exception) { Opened.Failed("The backup couldn’t be opened.") }

    /** Makes a safety copy of the current data, then replaces it with the file's. If the copy can't be made, nothing is replaced. */
    suspend fun restore(file: BackupFile) {
        val pass = loadPassphrase()
        saveInternal(createBytes("safety", pass), "safety", pass != null)
        repo.restore(file)
        file.receiptPhotos.forEach { (name, bytes) -> try { app.receiptStore.store(bytes, name) } catch (e: Exception) { android.util.Log.w("MONO", "receipt photo skipped", e) } }
        app.receiptStore.pruneOrphans(file.receipts.orEmpty().map { it.imageFile }.toSet())
        file.currencySymbol.takeIf { it.isNotBlank() }?.let { app.settings.setCurrencySymbol(if (it == Money.reporting.symbol) "" else it) }
        file.reportingCurrency?.let { app.settings.setReportingCurrency(it) }
        app.settings.markCategoriesSeeded()
    }

    // Passphrase (kept encrypted by the Android Keystore)

    private val alias = "mono_backup_passphrase"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    suspend fun savePassphrase(pass: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = c.iv + c.doFinal(pass.toByteArray(Charsets.UTF_8))
        app.settings.setSealedPassphrase(Base64.getEncoder().encodeToString(sealed))
    }

    suspend fun loadPassphrase(): String? = try {
        val text = app.settings.sealedPassphraseNow()
        if (text.isBlank()) null else {
            val b = Base64.getDecoder().decode(text)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12))) }
            String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
        }
    } catch (e: Exception) { null }

    suspend fun clearPassphrase() { app.settings.setSealedPassphrase("") }
}

package app.mono.finance.backup

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * MONO's own backups: a checksummed, optionally encrypted file around the same JSON the plain backup holds.
 * The envelope matches the iOS `.monobackup` format, so a backup made on one platform opens on the other:
 * AES-GCM with a key from PBKDF2-HMAC-SHA256, the checksum covers the payload, and the passphrase is never written into any file.
 */
object BackupVault {
    const val EXTENSION = "monobackup"
    const val DEFAULT_ITERATIONS = 200_000
    private const val MAGIC = "MONO-BACKUP"
    private val AAD = "MONO-BACKUP|1".toByteArray(Charsets.UTF_8)

    enum class Code { NOT_A_BACKUP, PASSPHRASE_REQUIRED, WRONG_PASSPHRASE, CORRUPT, NEWER_FORMAT, RESTORE_CHECK_FAILED }

    class VaultException(val code: Code, message: String) : Exception(message)

    data class Summary(val transactions: Int = 0, val accounts: Int = 0, val goals: Int = 0, val receipts: Int = 0)

    class Envelope(
        val createdAt: Long, val appVersion: String,
        /** "manual", "auto" or "safety" (made automatically before a restore). */
        val kind: String,
        val encrypted: Boolean, val salt: ByteArray?, val iterations: Int?, val payload: ByteArray,
        /** SHA-256 of [payload], to detect damage before anything is decrypted or decoded. */
        val sha256: String, val summary: Summary, val dataFormat: Int,
    )

    fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)).encoded

    private fun seal(plain: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        return nonce + cipher.doFinal(plain) // the same layout as CryptoKit's `combined`: nonce, ciphertext, tag
    }

    private fun open(combined: ByteArray, key: ByteArray): ByteArray {
        require(combined.size > 12 + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        cipher.updateAAD(AAD)
        return cipher.doFinal(combined, 12, combined.size - 12)
    }

    fun makeEnvelope(
        file: BackupFile, kind: String, passphrase: String?, nowMillis: Long, appVersion: String, iterations: Int = DEFAULT_ITERATIONS,
    ): Envelope {
        val plain = BackupCodec.encode(file).toByteArray(Charsets.UTF_8)
        var payload = plain
        var salt: ByteArray? = null
        var iters: Int? = null
        if (!passphrase.isNullOrEmpty()) {
            salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            payload = seal(plain, deriveKey(passphrase, salt, iterations))
            iters = iterations
        }
        return Envelope(nowMillis, appVersion, kind, salt != null, salt, iters, payload, sha256Hex(payload),
            Summary(file.transactions.size, file.accounts?.size ?: 1, file.goals.size, file.receipts?.size ?: 0), file.version)
    }

    fun encode(e: Envelope): ByteArray = JSONObject().apply {
        put("magic", MAGIC); put("envelopeVersion", 1); put("createdAt", BackupCodec.dateWhole(e.createdAt)); put("appVersion", e.appVersion)
        put("kind", e.kind); put("encrypted", e.encrypted)
        e.salt?.let { put("salt", Base64.getEncoder().encodeToString(it)) }
        e.iterations?.let { put("iterations", it) }
        put("payload", Base64.getEncoder().encodeToString(e.payload)); put("sha256", e.sha256)
        put("summary", JSONObject().put("transactions", e.summary.transactions).put("accounts", e.summary.accounts)
            .put("goals", e.summary.goals).put("receipts", e.summary.receipts))
        put("dataFormat", e.dataFormat)
    }.toString().toByteArray(Charsets.UTF_8)

    private fun parseEnvelope(data: ByteArray): Envelope? = try {
        val o = JSONObject(String(data, Charsets.UTF_8))
        if (o.optString("magic") != MAGIC) null else {
            val s = o.optJSONObject("summary")
            Envelope(
                BackupCodec.parseDate(o.getString("createdAt")), o.optString("appVersion"), o.optString("kind", "manual"), o.optBoolean("encrypted"),
                if (o.has("salt")) Base64.getDecoder().decode(o.getString("salt")) else null, if (o.has("iterations")) o.getInt("iterations") else null,
                Base64.getDecoder().decode(o.getString("payload")), o.getString("sha256"),
                Summary(s?.optInt("transactions") ?: 0, s?.optInt("accounts") ?: 0, s?.optInt("goals") ?: 0, s?.optInt("receipts") ?: 0), o.optInt("dataFormat", 0),
            )
        }
    } catch (e: Exception) { null }

    /** Reads a file's header and checks its checksum. Doesn't decrypt. */
    fun readEnvelope(data: ByteArray): Envelope {
        val e = parseEnvelope(data) ?: throw VaultException(Code.NOT_A_BACKUP, "This file isn’t a MONO backup.")
        if (sha256Hex(e.payload) != e.sha256) throw VaultException(Code.CORRUPT,
            "This backup is damaged and can’t be used: its contents don’t match its checksum (the file was changed or cut short).")
        return e
    }

    /** Opens a backup (decrypting if needed), validates it and returns the data. Also accepts a plain `.json` backup. */
    fun open(data: ByteArray, passphrase: String?): BackupFile {
        val e = parseEnvelope(data)
        if (e == null) {
            return try { BackupCodec.decode(String(data, Charsets.UTF_8)) } catch (ex: BackupException) {
                if (ex.message?.startsWith("The file could not be read") == true) throw VaultException(Code.NOT_A_BACKUP, "This file isn’t a MONO backup.")
                throw ex
            }
        }
        readEnvelope(data)
        var plain = e.payload
        if (e.encrypted) {
            if (passphrase.isNullOrEmpty()) throw VaultException(Code.PASSPHRASE_REQUIRED, "This backup is encrypted. Enter its passphrase.")
            val salt = e.salt; val iters = e.iterations
            if (salt == null || iters == null || iters <= 0 || iters > 5_000_000) throw VaultException(Code.CORRUPT, "This backup is damaged and can’t be used: its encryption details are missing.")
            plain = try { open(e.payload, deriveKey(passphrase, salt, iters)) } catch (ex: Exception) {
                throw VaultException(Code.WRONG_PASSPHRASE, "That passphrase doesn’t open this backup.")
            }
        }
        return try { BackupCodec.decode(String(plain, Charsets.UTF_8)) } catch (ex: BackupException) {
            if (ex.message?.contains("newer version") == true) throw VaultException(Code.NEWER_FORMAT, ex.message!!)
            throw VaultException(Code.CORRUPT, "This backup is damaged and can’t be used: ${ex.message}")
        }
    }

    /**
     * The restore test: what comes out of encoding and decoding the data must equal what went in. A backup that can't be read back
     * is never reported as a success. Returns what differs (empty when all is well).
     */
    fun roundTripMismatches(file: BackupFile): List<String> {
        val back = try { BackupCodec.decode(BackupCodec.encode(file)) } catch (e: Exception) { return listOf("the backup could not be read back: ${e.message}") }
        val bad = ArrayList<String>()
        fun <T> same(name: String, a: List<T>?, b: List<T>?) { if ((a ?: emptyList<T>()) != (b ?: emptyList<T>())) bad += "$name differ" }
        same("transactions", file.transactions, back.transactions)
        same("categories", file.categories.map { it.copy(pk = 0) }, back.categories.map { it.copy(pk = 0) })
        same("budgets", file.monthBudgets.map { it.copy(pk = 0) }, back.monthBudgets.map { it.copy(pk = 0) })
        same("category limits", file.budgetLimits.map { it.copy(pk = 0) }, back.budgetLimits.map { it.copy(pk = 0) })
        same("planned expenses", file.plannedExpenses, back.plannedExpenses)
        same("shopping items", file.shoppingItems, back.shoppingItems)
        same("goals", file.goals, back.goals)
        same("goal contributions", file.goalEntries, back.goalEntries)
        same("trip costs", file.tripCosts, back.tripCosts)
        same("subscriptions", file.recurringRules, back.recurringRules)
        same("accounts", file.accounts, back.accounts)
        same("exchange rates", file.exchangeRates?.map { it.copy(pk = 0) }, back.exchangeRates?.map { it.copy(pk = 0) })
        same("reserved funds", file.reservedFunds, back.reservedFunds)
        same("receipts", file.receipts, back.receipts)
        return bad
    }
}

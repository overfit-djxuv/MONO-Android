package app.mono.finance.backup

import app.mono.domain.model.Account
import app.mono.domain.model.BudgetLimit
import app.mono.domain.model.ExchangeRate
import app.mono.domain.model.GoalEntry
import app.mono.domain.model.MoneyCategory
import app.mono.domain.model.MonthBudget
import app.mono.domain.model.PlannedExpense
import app.mono.domain.model.RecurringRule
import app.mono.domain.model.ReservedFund
import app.mono.domain.model.SavingsGoal
import app.mono.domain.model.ShoppingItem
import app.mono.domain.model.TransactionRecord
import app.mono.domain.model.TripCost
import app.mono.finance.backup.BackupVault.Code
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Round trips, encryption, damage detection and validation of the iOS-compatible backup format. */
class BackupTest {
    private val t0 = 1_793_000_000_123L // a moment with milliseconds, to prove they survive

    private fun sample(): BackupFile {
        val main = Account(id = "11111111-1111-1111-1111-111111111111", name = "Main", kindRaw = "bank", currencyCode = "MYR", openingCents = 100_000, createdAt = t0)
        val usd = Account(id = "22222222-2222-2222-2222-222222222222", name = "USD wallet", kindRaw = "ewallet", currencyCode = "USD", sortOrder = 1, createdAt = t0)
        val goal = SavingsGoal(id = "33333333-3333-3333-3333-333333333333", name = "Tokyo", kindRaw = "trip", symbol = "airplane", bufferCents = 50_000,
            deadline = t0 + 86_400_000L * 200, plannedMonthlyCents = 60_000, priorityRaw = 2, createdAt = t0, currencyCode = "")
        val rule = RecurringRule(id = "44444444-4444-4444-4444-444444444444", title = "Streaming", amountCents = 1_599, category = "Subscriptions", dayOfMonth = 5,
            nextMonthKey = "2026-11", nextDueDate = t0, accountID = main.id)
        return BackupFile(
            exportedAt = t0, openingBalanceCents = 100_000, currencySymbol = "RM",
            categories = listOf(MoneyCategory(name = "Food", symbol = "fork.knife", kindRaw = "expense", isDefault = true, sortOrder = 0, isEssential = false),
                MoneyCategory(name = "Salary", symbol = "banknote", kindRaw = "income", isDefault = true, sortOrder = 100)),
            transactions = listOf(
                TransactionRecord(id = "a0000000-0000-0000-0000-000000000001", amountCents = 450_000, kindRaw = "income", category = "Salary", date = t0, title = "Pay", accountID = main.id),
                TransactionRecord(id = "a0000000-0000-0000-0000-000000000002", amountCents = 1_250, kindRaw = "expense", category = "Food", date = t0 + 1, title = "Lunch, with “quotes”",
                    notes = "line\nbreak", merchant = "Café", tags = "work,coffee", accountID = main.id),
                TransactionRecord(id = "a0000000-0000-0000-0000-000000000003", amountCents = 44_725, kindRaw = "transfer", category = "", date = t0 + 2,
                    accountID = main.id, toAccountID = usd.id, toAmountCents = 10_000),
                TransactionRecord(id = "a0000000-0000-0000-0000-000000000004", amountCents = 1_599, kindRaw = "expense", category = "Subscriptions", date = t0 + 3,
                    title = "Streaming", accountID = main.id, occurrenceKey = "${rule.id}|2026-10-05"),
            ),
            monthBudgets = listOf(MonthBudget(monthKey = "2026-10", totalCents = 300_000)),
            budgetLimits = listOf(BudgetLimit(monthKey = "2026-10", category = "Food", limitCents = 40_000)),
            plannedExpenses = listOf(PlannedExpense(id = "55555555-5555-5555-5555-555555555555", title = "Dentist", amountCents = 30_000, dueDate = t0, category = "Health")),
            shoppingItems = listOf(ShoppingItem(id = "66666666-6666-6666-6666-666666666666", name = "Milk", quantity = 3, estimatedCents = 800, actualCents = 900,
                isPurchased = true, listKindRaw = "grocery", createdAt = t0, purchasedAt = t0 + 5)),
            goals = listOf(goal),
            goalEntries = listOf(GoalEntry(id = "77777777-7777-7777-7777-777777777777", goalID = goal.id, amountCents = 120_000, date = t0, note = "start", accountID = main.id)),
            tripCosts = listOf(TripCost(id = "88888888-8888-8888-8888-888888888888", goalID = goal.id, categoryRaw = "flights", label = "KUL–NRT", amountCents = 220_000)),
            recurringRules = listOf(rule), accounts = listOf(main, usd),
            exchangeRates = listOf(ExchangeRate(fromCode = "USD", toCode = "MYR", rateMicros = 4_472_500, updatedAt = t0)),
            reportingCurrency = "MYR",
            reservedFunds = listOf(ReservedFund(id = "99999999-9999-9999-9999-999999999999", name = "Buffer", amountCents = 50_000, createdAt = t0)),
        )
    }

    @Test fun everythingSurvivesAJsonRoundTripIncludingMilliseconds() {
        val file = sample()
        assertTrue(BackupVault.roundTripMismatches(file).isEmpty())
        val back = BackupCodec.decode(BackupCodec.encode(file))
        assertEquals(file.transactions, back.transactions)
        assertEquals(t0, back.transactions[0].date)
        assertEquals("Lunch, with “quotes”", back.transactions[1].title)
        assertEquals("line\nbreak", back.transactions[1].notes)
        assertEquals(file.goals, back.goals)
    }

    @Test fun receiptPhotosTravelInsideTheBackupAndPathsInFileNamesAreHarmless() {
        val photo = byteArrayOf(1, 2, 3, 4, 5, -1, -2)
        val r = app.mono.domain.model.Receipt(id = "b0000000-0000-0000-0000-000000000001", createdAt = t0, merchant = "Shop", date = t0, totalCents = 1234, imageFile = "ABC.jpg", imageHash = "h")
        val file = sample().copy(receipts = listOf(r), receiptPhotos = mapOf("ABC.jpg" to photo))
        val text = BackupCodec.encode(file)
        assertTrue("written as imageData, like the iOS backup", text.contains("\"imageData\""))
        val back = BackupCodec.decode(text)
        assertEquals(listOf(r), back.receipts)
        assertTrue(back.receiptPhotos["ABC.jpg"]!!.contentEquals(photo))
        assertTrue("a backup without photos still decodes", BackupCodec.decode(BackupCodec.encode(sample().copy(receipts = listOf(r)))).receiptPhotos.isEmpty())
    }

    @Test fun datesUseTheSameTextAsIosAndWholeSecondDatesAreAccepted() {
        assertEquals("2026-10-29T13:37:28.123Z", BackupCodec.date(BackupCodec.parseDate("2026-10-29T13:37:28.123Z")))
        assertEquals(BackupCodec.parseDate("2026-10-29T13:37:28Z") + 123, BackupCodec.parseDate("2026-10-29T13:37:28.123Z"))
        assertEquals("2026-10-29T13:37:28Z", BackupCodec.dateWhole(BackupCodec.parseDate("2026-10-29T13:37:28.999Z")))
    }

    @Test fun anIosFileWithOnlyTheOlderFieldsStillRestoresWithDefaults() {
        val ios = """
            {"app":"MONO","version":3,"exportedAt":"2026-03-01T10:00:00.000Z","openingBalanceCents":5000,"currencySymbol":"RM",
             "categories":[{"name":"Food","symbol":"fork.knife","kind":"expense","isDefault":true,"sortOrder":0}],
             "transactions":[{"id":"A0000000-0000-0000-0000-000000000001","amountCents":1200,"kind":"expense","category":"Food","date":"2026-03-01T09:00:00.000Z","title":"x","notes":"","sourceID":null}],
             "monthBudgets":[],"budgetLimits":[],"plannedExpenses":[],"shoppingItems":[],"goals":[],"goalEntries":[],"tripCosts":[]}
        """.trimIndent()
        val f = BackupCodec.decode(ios)
        assertEquals(1, f.transactions.size)
        assertEquals("A0000000-0000-0000-0000-000000000001", f.transactions[0].id) // iOS upper-case ids are kept as they are
        assertEquals(null, f.accounts)
        assertEquals(false, f.categories[0].isEssential)
    }

    @Test fun anEncryptedEnvelopeOpensOnlyWithTheRightPassphrase() {
        val file = sample()
        val env = BackupVault.makeEnvelope(file, "manual", "correct horse", t0, "0.2.0", iterations = 2_000)
        assertTrue(env.encrypted)
        val bytes = BackupVault.encode(env)
        assertFalse("the passphrase never appears in the file", String(bytes).contains("correct horse"))
        assertEquals(file.transactions, BackupVault.open(bytes, "correct horse").transactions)
        try { BackupVault.open(bytes, "wrong"); fail() } catch (e: BackupVault.VaultException) { assertEquals(Code.WRONG_PASSPHRASE, e.code) }
        try { BackupVault.open(bytes, null); fail() } catch (e: BackupVault.VaultException) { assertEquals(Code.PASSPHRASE_REQUIRED, e.code) }
    }

    @Test fun aPlainEnvelopeAndAPlainJsonFileBothOpen() {
        val file = sample()
        val bytes = BackupVault.encode(BackupVault.makeEnvelope(file, "auto", null, t0, "0.2.0"))
        assertEquals(file.goals, BackupVault.open(bytes, null).goals)
        assertEquals(file.goals, BackupVault.open(BackupCodec.encode(file).toByteArray(), null).goals)
        assertEquals(4, BackupVault.readEnvelope(bytes).summary.transactions)
    }

    @Test fun aDamagedFileIsRefusedBeforeAnythingIsDecoded() {
        val bytes = BackupVault.encode(BackupVault.makeEnvelope(sample(), "manual", null, t0, "0.2.0"))
        val text = String(bytes)
        val tampered = text.replace("\"transactions\":4", "\"transactions\":4").replaceFirst(Regex("\"payload\":\"(.)"), "\"payload\":\"A")
        if (tampered == text) return // the first payload character was already 'A'
        try { BackupVault.open(tampered.toByteArray(), null); fail() } catch (e: BackupVault.VaultException) { assertEquals(Code.CORRUPT, e.code) }
        try { BackupVault.open("not a backup".toByteArray(), null); fail() } catch (e: BackupVault.VaultException) { assertEquals(Code.NOT_A_BACKUP, e.code) }
        try { BackupVault.open("{\"app\":\"Other\"}".toByteArray(), null); fail() } catch (e: Exception) { /* refused */ }
    }

    @Test fun aBackupFromANewerFormatIsRefusedWithAClearMessage() {
        try { BackupCodec.decode(BackupCodec.encode(sample()).replace("\"version\": 6", "\"version\": 99")); fail() }
        catch (e: BackupException) { assertTrue(e.message!!.contains("newer version")) }
    }

    @Test fun validationRejectsDamagedData() {
        fun bad(change: (BackupFile) -> BackupFile, expected: String) {
            try { BackupCodec.validate(change(sample())); fail("expected: $expected") } catch (e: BackupException) { assertTrue(e.message, e.message!!.contains(expected)) }
        }
        bad({ it.copy(transactions = it.transactions + it.transactions[0]) }, "duplicate transaction ids")
        bad({ it.copy(transactions = it.transactions.map { t -> if (t.kindRaw == "expense") t.copy(amountCents = 0) else t }) }, "amount out of range")
        bad({ it.copy(goalEntries = it.goalEntries.map { e -> e.copy(goalID = "ffffffff-ffff-ffff-ffff-ffffffffffff") }) }, "goal that is not in the file")
        bad({ it.copy(goalEntries = it.goalEntries + it.goalEntries[0].copy(id = "x", amountCents = -500_000)) }, "negative balance")
        bad({ it.copy(transactions = it.transactions.map { t -> if (t.accountID != null) t.copy(accountID = "00000000-0000-0000-0000-00000000dead") else t }) }, "account that is not in the file")
        bad({ it.copy(transactions = it.transactions.map { t -> if (t.kindRaw == "transfer") t.copy(toAccountID = t.accountID) else t }) }, "invalid transfer")
        bad({ it.copy(transactions = it.transactions + it.transactions[3].copy(id = "a0000000-0000-0000-0000-0000000000ff")) }, "recorded twice")
        bad({ it.copy(exchangeRates = listOf(ExchangeRate(fromCode = "USD", toCode = "USD", rateMicros = 1))) }, "invalid exchange rate")
        bad({ it.copy(monthBudgets = listOf(MonthBudget(monthKey = "2026-13", totalCents = 1))) }, "invalid monthly budget")
    }

    @Test fun twoEnvelopesOfTheSameDataDifferAndKeepTheirOwnChecksums() {
        val a = BackupVault.makeEnvelope(sample(), "manual", "pw", t0, "0.2.0", iterations = 1_000)
        val b = BackupVault.makeEnvelope(sample(), "manual", "pw", t0, "0.2.0", iterations = 1_000)
        assertNotEquals("a fresh salt and nonce every time", a.sha256, b.sha256)
        assertEquals(BackupVault.sha256Hex(a.payload), a.sha256)
    }
}

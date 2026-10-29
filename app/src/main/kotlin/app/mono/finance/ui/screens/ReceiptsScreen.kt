package app.mono.finance.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.AccountEngine
import app.mono.domain.LedgerRules
import app.mono.domain.Money
import app.mono.domain.ParsedReceipt
import app.mono.domain.ReceiptItem
import app.mono.domain.ReceiptMatcher
import app.mono.domain.ReceiptParser
import app.mono.domain.TxKind
import app.mono.domain.model.Receipt
import app.mono.domain.model.TransactionRecord
import app.mono.finance.MonoViewModel
import app.mono.finance.data.LedgerState
import app.mono.finance.receipts.ReceiptOcr
import app.mono.finance.ui.AmountInput
import app.mono.finance.ui.Callout
import app.mono.finance.ui.ChipRow
import app.mono.finance.ui.ConfirmDialog
import app.mono.finance.ui.DateField
import app.mono.finance.ui.EmptyState
import app.mono.finance.ui.FormSheet
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.PrimaryButton
import app.mono.finance.ui.SecondaryButton
import app.mono.finance.ui.SectionTitle
import app.mono.finance.ui.TextInput
import app.mono.finance.ui.Tone
import app.mono.finance.ui.clickableNoRipple
import app.mono.finance.ui.short
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono
import app.mono.finance.ui.toLocalDate
import app.mono.finance.ui.toMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private class Draft(val image: Bitmap, val parsed: ParsedReceipt, val ocrText: String, val language: String, val notice: String?)

private sealed interface Mode {
    data object List : Mode
    data object Choose : Mode
    class New(val draft: Draft) : Mode
    class Edit(val receipt: Receipt) : Mode
}

/** Every saved receipt, a scan flow (camera or gallery, read on the phone) and an editor to review before anything is saved. */
@Composable
fun ReceiptsScreen(ledger: LedgerState?, vm: MonoViewModel, startScanning: Boolean, attachTo: String? = null, onBack: () -> Unit) {
    val attachTx = attachTo?.let { id -> ledger?.transactions?.firstOrNull { it.id == id } }
    val receipts by vm.receipts.collectAsState()
    var mode by remember { mutableStateOf<Mode>(if (startScanning) Mode.Choose else Mode.List) }
    when (val m = mode) {
        Mode.List -> ReceiptList(receipts, vm, onScan = { mode = Mode.Choose }, onOpen = { mode = Mode.Edit(it) }, onBack = onBack)
        Mode.Choose -> ScanChooser(vm, onDraft = { mode = Mode.New(it) }, onBack = { if (attachTx != null) onBack() else mode = Mode.List })
        is Mode.New -> if (ledger != null) ReceiptEditor(ledger, vm, receipts, m.draft, null, attachTx) { if (attachTx != null) onBack() else mode = Mode.List } else ScreenFrame("Review receipt", onBack) {}
        is Mode.Edit -> if (ledger != null) ReceiptEditor(ledger, vm, receipts, null, m.receipt, null) { mode = Mode.List } else ScreenFrame("Receipt", onBack) {}
    }
}

@Composable
private fun ReceiptList(receipts: List<Receipt>, vm: MonoViewModel, onScan: () -> Unit, onOpen: (Receipt) -> Unit, onBack: () -> Unit) {
    val c = Mono.colors
    var search by remember { mutableStateOf("") }
    val q = ReceiptParser.fold(search).trim()
    val shown = if (q.isEmpty()) receipts.sortedByDescending { it.date }
    else receipts.filter { r -> listOf(r.merchant, r.notes, r.category, r.ocrText).any { ReceiptParser.fold(it).contains(q) } }.sortedByDescending { it.date }
    ScreenFrame("Receipts", onBack) {
        if (receipts.isEmpty()) {
            Column(Modifier.fillMaxWidth().surface()) {
                EmptyState("No receipts yet", "Scan a paper receipt or pick a photo. MONO reads it on this phone, you check it, then choose what to do with it.", "Scan a receipt", onScan)
            }
        } else {
            PrimaryButton("Scan a receipt", modifier = Modifier.fillMaxWidth(), onClick = onScan)
            TextInput(search, { search = it }, "Search shop, note or text on the receipt")
            Column(Modifier.fillMaxWidth().surface()) {
                if (shown.isEmpty()) Text("No receipts match “$search”.", color = c.inkSecondary, fontSize = 14.sp)
                shown.forEachIndexed { i, r ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().clickableNoRipple { onOpen(r) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val thumb = remember(r.imageFile) { vm.receiptStore.thumbnail(r.imageFile) }
                        Box(Modifier.size(44.dp, 56.dp).clip(RoundedCornerShape(8.dp)).background(c.accentSoft)) {
                            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.size(44.dp, 56.dp), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(r.merchant.ifBlank { "Untitled receipt" }, color = c.ink, fontSize = 15.sp, maxLines = 1)
                            Text(r.date.short() + (if (r.transactionID != null) " · linked" else "") + (if (r.needsReview) " · check" else ""),
                                color = if (r.needsReview) c.warning else c.inkSecondary, fontSize = 12.sp)
                        }
                        if (r.totalCents > 0) Text(Money.format(r.totalCents, currency = app.mono.domain.Currency.find(r.currencyCode.ifEmpty { Money.reporting.code })), color = c.ink, fontSize = 15.sp)
                    }
                }
            }
            Text("${receipts.size} ${if (receipts.size == 1) "receipt" else "receipts"} · photos are stored privately on this phone and included in backups.", color = c.inkTertiary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ScanChooser(vm: MonoViewModel, onDraft: (Draft) -> Unit, onBack: () -> Unit) {
    val c = Mono.colors
    val scope = rememberCoroutineScope()
    var burmese by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var shot by remember { mutableStateOf<java.io.File?>(null) }
    val store = vm.receiptStore

    fun process(uri: android.net.Uri) {
        error = null; working = true
        scope.launch {
            val image = withContext(Dispatchers.IO) { store.decode(uri) }
            if (image == null) { working = false; error = "That photo couldn’t be opened."; return@launch }
            var parsed = ParsedReceipt(); var text = ""; var notice: String? = null
            if (burmese) notice = "Burmese receipts aren’t read automatically. Enter the details below; the photo is kept."
            else try {
                val r = withContext(Dispatchers.Default) { ReceiptOcr.recognize(image) }
                text = r.text; parsed = ReceiptParser.parse(r.lines)
                if (r.lines.isEmpty()) notice = "No text was found in this photo. You can type the details yourself."
            } catch (e: Exception) { notice = "Reading the photo failed. You can type the details yourself." }
            working = false
            onDraft(Draft(image, parsed, text, if (burmese) "my" else "auto", notice))
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = shot; shot = null
        if (ok && f != null) {
            process(android.net.Uri.fromFile(f))
        } else f?.delete()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) process(uri) }

    ScreenFrame("Scan a receipt", onBack) {
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Receipt language")
            ChipRow(listOf(false, true), burmese, { if (it) "Burmese (photo only)" else "English, Spanish, Malay" }) { burmese = it }
            Text(if (!burmese) "Automatic reads English, Spanish and Malay." else "Text recognition can’t read Burmese. MONO keeps the photo and you type the shop, date and total yourself.",
                color = c.inkSecondary, fontSize = 13.sp)
        }
        error?.let { Callout(it, Tone.WARNING) }
        PrimaryButton("Take a photo", enabled = !working, modifier = Modifier.fillMaxWidth()) {
            val (f, uri) = store.cameraTarget(); shot = f; camera.launch(uri)
        }
        SecondaryButton("Choose from gallery", Modifier.fillMaxWidth(), enabled = !working) {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        if (working) Text("Reading the receipt on this phone…", color = c.inkSecondary, fontSize = 13.sp)
        Text("Photos and text stay on this phone. Nothing is uploaded, and reading works offline.", color = c.inkTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun ReceiptEditor(ledger: LedgerState, vm: MonoViewModel, receipts: List<Receipt>, draft: Draft?, receipt: Receipt?, attachTx: TransactionRecord?, onFinish: () -> Unit) {
    val c = Mono.colors
    val store = vm.receiptStore
    val accounts = ledger.accounts.filter { !it.isArchived }.sortedBy { it.sortOrder }
    val defaultAccount = AccountEngine.defaultAccount(ledger.accounts)
    val linked = receipt?.transactionID?.let { id -> ledger.transactions.firstOrNull { it.id == id } }
    val expenseCategories = ledger.categories.filter { it.kind == TxKind.EXPENSE }.sortedBy { it.sortOrder }

    var accountID by remember { mutableStateOf(linked?.accountID ?: accounts.firstOrNull { it.currencyCode == receipt?.currencyCode }?.id ?: defaultAccount?.id) }
    val account = accounts.firstOrNull { it.id == accountID } ?: defaultAccount
    val currency = account?.currency ?: Money.reporting
    var merchant by remember { mutableStateOf(receipt?.merchant ?: draft?.parsed?.merchant?.ifBlank { null } ?: attachTx?.let { it.merchant.ifEmpty { it.title } } ?: "") }
    var date by remember { mutableStateOf(receipt?.date?.toLocalDate() ?: draft?.parsed?.date ?: attachTx?.date?.toLocalDate() ?: LocalDate.now()) }
    var totalText by remember { mutableStateOf((receipt?.totalCents ?: draft?.parsed?.totalCents)?.takeIf { it > 0 }?.let { Money.editable(it, currency) } ?: "") }
    var taxText by remember { mutableStateOf((receipt?.taxCents ?: draft?.parsed?.taxCents)?.takeIf { it > 0 }?.let { Money.editable(it, currency) } ?: "") }
    var category by remember {
        mutableStateOf(receipt?.category ?: attachTx?.category ?: run {
            val key = ReceiptParser.fold(draft?.parsed?.merchant.orEmpty())
            if (key.isEmpty()) "" else ledger.transactions.sortedByDescending { it.date }.firstOrNull { it.kind == TxKind.EXPENSE && ReceiptParser.fold(it.merchant) == key }?.category ?: ""
        })
    }
    var notes by remember { mutableStateOf(receipt?.notes ?: "") }
    var uncertain by remember { mutableStateOf(draft?.parsed?.uncertain ?: if (receipt?.needsReview == true) setOf(ParsedReceipt.Field.TOTAL) else emptySet()) }
    var attach by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDuplicate by remember { mutableStateOf(false) }
    var enlarge by remember { mutableStateOf(false) }
    var showItems by remember { mutableStateOf(false) }

    val total = Money.parse(totalText, currency)?.takeIf { it > 0 }
    val tax = Money.parse(taxText, currency) ?: 0L
    val image = remember { draft?.image ?: receipt?.let { store.bitmap(it.imageFile) } }
    val items: List<ReceiptItem> = remember {
        if (draft != null) draft.parsed.items else try {
            val a = org.json.JSONArray(receipt?.itemsJSON?.ifBlank { "[]" } ?: "[]")
            (0 until a.length()).map { ReceiptItem(a.getJSONObject(it).getString("name"), a.getJSONObject(it).getLong("cents")) }
        } catch (e: Exception) { emptyList() }
    }
    val photoHash = remember { draft?.let { store.hash(store.prepared(it.image)) } ?: "" }

    val duplicateReceipt = if (total != null && draft != null) ReceiptMatcher.duplicate(ReceiptMatcher.New(merchant, date, total, photoHash),
        receipts.map { ReceiptMatcher.Existing(it.id, it.merchant, it.date.toLocalDate(), it.totalCents, it.imageHash) }) else null
    val duplicateTx = if (total != null && receipt?.transactionID == null) LedgerRules.possibleDuplicate(TxKind.EXPENSE, total, date.toMillis(), merchant, merchant,
        account?.id, defaultAccount?.id, existing = ledger.transactions) else null
    val mismatch = draft?.parsed?.currencyCode?.takeIf { it != currency.code }

    fun write(link: String?, unlink: Boolean = false, tx: TransactionRecord? = null) {
        val r: Receipt = if (receipt != null) receipt else {
            val d = draft!!
            val data = store.prepared(d.image)
            val file = try { store.store(data) } catch (e: Exception) { return }
            Receipt(createdAt = System.currentTimeMillis(), imageFile = file, imageHash = store.hash(data), ocrText = d.ocrText, languageRaw = d.language,
                itemsJSON = org.json.JSONArray(d.parsed.items.map { org.json.JSONObject().put("name", it.name).put("cents", it.cents) }).toString())
        }
        val saved = r.copy(
            merchant = merchant.trim(), date = date.toMillis(), totalCents = total ?: 0, taxCents = tax, currencyCode = currency.code, category = category, notes = notes,
            needsReview = total == null || ParsedReceipt.Field.TOTAL in uncertain || ParsedReceipt.Field.DATE in uncertain,
            transactionID = when { link != null -> link; unlink -> null; else -> r.transactionID },
        )
        vm.saveReceipt(saved, tx, onFinish)
    }

    fun saveAsExpense(confirmed: Boolean) {
        val cents = total ?: return
        val acct = account ?: return
        if (category.isEmpty()) return
        if (!confirmed && duplicateTx != null) { confirmDuplicate = true; return }
        val name = merchant.trim()
        val tx = TransactionRecord(amountCents = cents, kindRaw = "expense", category = category, date = date.toMillis(), title = name, notes = notes, accountID = acct.id, merchant = name)
        write(tx.id, tx = tx)
    }

    ScreenFrame(if (receipt == null) "Review receipt" else "Receipt", onFinish) {
        draft?.notice?.let { Callout(it, Tone.WARNING) }
        if (image != null) {
            Image(image.asImageBitmap(), "Receipt photo, tap to enlarge", Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(16.dp)).clickableNoRipple { enlarge = true },
                contentScale = ContentScale.Fit)
        }
        mismatch?.let { Callout("This receipt looks like $it, but the account is in ${currency.code}. Choose an account in $it, or enter the amount in ${currency.code} yourself.", Tone.WARNING) }
        duplicateReceipt?.let { Callout("You already saved a receipt that looks the same (${it.merchant.ifEmpty { "no name" }}, ${it.date.short()}).", Tone.WARNING) }

        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fun flag(f: ParsedReceipt.Field) = if (f in uncertain) " · check" else ""
            TextInput(merchant, { merchant = it; uncertain = uncertain - ParsedReceipt.Field.MERCHANT }, "Shop or merchant" + flag(ParsedReceipt.Field.MERCHANT))
            DateField("Date" + flag(ParsedReceipt.Field.DATE), date) { date = it; uncertain = uncertain - ParsedReceipt.Field.DATE }
            AmountInput(totalText, { totalText = it; uncertain = uncertain - ParsedReceipt.Field.TOTAL }, "Total" + flag(ParsedReceipt.Field.TOTAL), currency, tag = "receiptTotal")
            AmountInput(taxText, { taxText = it }, "Tax (optional)", currency)
            if (accounts.size > 1) { Text("Account", color = c.inkSecondary, fontSize = 13.sp); ChipRow(accounts, account, { it.name }) { accountID = it.id } }
            Text("Category", color = c.inkSecondary, fontSize = 13.sp)
            ChipRow(expenseCategories, expenseCategories.firstOrNull { it.name == category }, { it.name }) { category = it.name }
            TextInput(notes, { notes = it }, "Notes (optional)", singleLine = false)
        }

        if (items.isNotEmpty()) Column(Modifier.fillMaxWidth().surface()) {
            Row(Modifier.fillMaxWidth().clickableNoRipple { showItems = !showItems }, verticalAlignment = Alignment.CenterVertically) {
                Text("${items.size} possible items", color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f)); Text(if (showItems) "Hide" else "Show", color = c.accent, fontSize = 13.sp)
            }
            if (showItems) {
                items.forEach { Row(Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(it.name, color = c.ink, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f)); Text(Money.format(it.cents, currency = currency), color = c.ink, fontSize = 13.sp) } }
                Text("Possible items found by the scan. They’re for reference and aren’t saved as separate transactions.", color = c.inkTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (attachTx != null) {
                Callout("Attaching to ${attachTx.merchant.ifEmpty { attachTx.title.ifEmpty { attachTx.category } }} · ${Money.format(attachTx.amountCents, currency = currency)} on ${attachTx.date.short()}. The transaction keeps its own amount.", Tone.INFO)
                PrimaryButton("Attach to this transaction", modifier = Modifier.fillMaxWidth()) {
                    write(attachTx.id, tx = if (attachTx.merchant.isEmpty() && merchant.isNotBlank()) attachTx.copy(merchant = merchant.trim()) else null)
                }
            } else if (linked != null) {
                Callout("Linked to ${linked.merchant.ifEmpty { linked.title }} · ${Money.format(linked.amountCents, currency = currency)} on ${linked.date.short()}.", Tone.INFO)
                PrimaryButton("Save changes", modifier = Modifier.fillMaxWidth()) { write(null) }
                SecondaryButton("Unlink from transaction", Modifier.fillMaxWidth()) { write(null, unlink = true) }
            } else {
                PrimaryButton("Save as new expense", enabled = total != null && category.isNotEmpty() && account != null, modifier = Modifier.fillMaxWidth()) { saveAsExpense(false) }
                SecondaryButton("Attach to an existing transaction…", Modifier.fillMaxWidth(), enabled = ledger.transactions.isNotEmpty()) { attach = true }
                SecondaryButton("Save receipt only", Modifier.fillMaxWidth()) { write(null) }
            }
            if (receipt != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete receipt…", color = c.danger) }
            if (total == null || category.isEmpty()) Text(if (total == null) "Enter the total to save an expense." else "Choose a category to save an expense.", color = c.inkTertiary, fontSize = 12.sp)
        }
    }

    if (attach) FormSheet("Attach to", { attach = false }) {
        val candidates = ReceiptMatcher.candidates(total ?: 0, date, ledger.transactions)
        if (candidates.isEmpty()) Text("No expenses with the same amount or from the last week were found.", color = c.inkSecondary, fontSize = 14.sp)
        candidates.forEach { t ->
            Row(Modifier.fillMaxWidth().clickableNoRipple {
                attach = false
                // The photo is evidence, so the transaction keeps its own amount; only an empty merchant is filled in.
                if (merchant.isBlank()) merchant = t.merchant.ifEmpty { t.title }
                if (category.isEmpty()) category = t.category
                val filled = if (t.merchant.isEmpty() && merchant.isNotBlank()) t.copy(merchant = merchant.trim()) else null
                write(t.id, tx = filled)
            }.padding(vertical = 10.dp)) {
                Column(Modifier.weight(1f)) { Text(t.merchant.ifEmpty { t.title.ifEmpty { t.category } }, color = c.ink, fontSize = 15.sp); Text(t.date.short(), color = c.inkSecondary, fontSize = 12.sp) }
                Text(Money.format(t.amountCents, currency = currency), color = c.ink, fontSize = 15.sp)
            }
        }
        Text("Same amount first, then the closest dates. The transaction keeps its own amount; the receipt is added as proof.", color = c.inkTertiary, fontSize = 12.sp)
    }
    if (enlarge && image != null) FormSheet("Receipt photo", { enlarge = false }) { Image(image.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
    if (confirmDuplicate) ConfirmDialog("Looks like a duplicate",
        "An expense for ${Money.format(duplicateTx?.amountCents ?: 0, currency = currency)} on this day already exists. Attach the receipt to it instead, or save a second expense.",
        "Save anyway", false, { saveAsExpense(true) }, { confirmDuplicate = false })
    if (confirmDelete && receipt != null) ConfirmDialog("Delete this receipt?", "The photo is deleted. A linked transaction, if any, is kept.",
        "Delete", true, { vm.deleteReceipt(receipt, onFinish) }, { confirmDelete = false })
}

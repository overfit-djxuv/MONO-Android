package app.mono.finance.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.finance.MonoViewModel
import app.mono.finance.backup.BackupFile
import app.mono.finance.backup.BackupManager
import app.mono.finance.ui.Callout
import app.mono.finance.ui.ConfirmDialog
import app.mono.finance.ui.FormSheet
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.PrimaryButton
import app.mono.finance.ui.SecondaryButton
import app.mono.finance.ui.SectionTitle
import app.mono.finance.ui.Tone
import app.mono.finance.ui.fieldColors
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private sealed interface RestoreStep {
    data object Idle : RestoreStep
    data class AskPassphrase(val bytes: ByteArray, val wrong: Boolean) : RestoreStep
    data class Confirm(val file: BackupFile) : RestoreStep
    data class Failed(val message: String) : RestoreStep
}

@Composable
fun BackupScreen(vm: MonoViewModel, onBack: () -> Unit) {
    val c = Mono.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val last by vm.lastBackupAt.collectAsState()
    val auto by vm.autoBackup.collectAsState()
    val hasPass by vm.hasPassphrase.collectAsState()
    val entries by vm.backupEntries.collectAsState()
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var step by remember { mutableStateOf<RestoreStep>(RestoreStep.Idle) }
    var setPass by remember { mutableStateOf(false) }
    var restoreEntry by remember { mutableStateOf<BackupManager.Entry?>(null) }
    var confirmDelete by remember { mutableStateOf<BackupManager.Entry?>(null) }
    LaunchedEffect(Unit) { vm.refreshBackups() }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pending; pending = null
        if (uri != null && bytes != null) {
            try { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }; vm.markExported() } catch (e: Exception) { /* the system picker already reported it */ }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = try { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
            if (bytes == null || bytes.isEmpty() || bytes.size > 100_000_000) { step = RestoreStep.Failed("That file couldn’t be read."); return@launch }
            step = when (val r = vm.inspectBackup(bytes, null)) {
                is BackupManager.Opened.Ready -> RestoreStep.Confirm(r.file)
                BackupManager.Opened.NeedsPassphrase -> RestoreStep.AskPassphrase(bytes, false)
                is BackupManager.Opened.Failed -> RestoreStep.Failed(r.message)
            }
        }
    }

    val days = if (last == 0L) null else ((System.currentTimeMillis() - last) / 86_400_000L).toInt()
    ScreenFrame("Backup & recovery", onBack) {
        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (last == 0L) "No copy saved outside MONO yet" else "Last copy saved " + (if (days == 0) "today" else "$days ${if (days == 1) "day" else "days"} ago"),
                color = c.ink, fontSize = 17.sp,
            )
            if (days == null || days >= 7) Callout("Keep a copy somewhere other than this phone, like Drive or a computer, so losing the phone doesn’t lose your records.", Tone.WARNING)
            PrimaryButton("Back up now") { vm.prepareExport { bytes, name -> pending = bytes; export.launch(name) } }
            Text(if (hasPass) "The file is encrypted with your passphrase." else "The file isn’t encrypted. Set a passphrase below to lock it.", color = c.inkSecondary, fontSize = 13.sp)
        }

        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Restore")
            Text("Restoring replaces everything in MONO with the backup. A safety copy of what you have now is kept first. Backups made on an iPhone open here too.", color = c.inkSecondary, fontSize = 13.sp)
            SecondaryButton("Restore from a file", Modifier.fillMaxWidth()) { open.launch(arrayOf("*/*")) }
        }

        Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Protection")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Passphrase", color = c.ink, fontSize = 16.sp)
                    Text(if (hasPass) "Set. Backups are encrypted." else "Not set", color = c.inkSecondary, fontSize = 12.sp)
                }
                TextButton(onClick = { setPass = true }) { Text(if (hasPass) "Change" else "Set", color = c.accent) }
                if (hasPass) TextButton(onClick = { vm.setPassphrase(null) }) { Text("Remove", color = c.danger) }
            }
            Text("Keep the passphrase somewhere safe. Without it an encrypted backup can’t be opened, and MONO can’t recover it. It is stored encrypted by Android and never written into a backup file.",
                color = c.inkTertiary, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic backup", color = c.ink, fontSize = 16.sp)
                    Text("A copy kept inside the app once a day, newest 7.", color = c.inkSecondary, fontSize = 12.sp)
                }
                Switch(checked = auto, onCheckedChange = vm::setAutoBackup)
            }
        }

        Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Kept in the app", "Back up here") { vm.backupNowInApp() }
            Text("These disappear if you uninstall MONO. Use “Back up now” for a copy that survives.", color = c.inkTertiary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            if (entries.isEmpty()) Text("Nothing yet.", color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            entries.forEachIndexed { i, e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.createdAt)), color = c.ink, fontSize = 15.sp)
                        Text(
                            when { !e.intact -> "Damaged"; e.kind == "safety" -> "Safety copy before a restore"; e.kind == "auto" -> "Automatic"; else -> "Manual" } +
                                (if (e.encrypted) " · encrypted" else "") + (if (e.intact) " · ${e.summary.transactions} transactions" else ""),
                            color = if (e.intact) c.inkSecondary else c.danger, fontSize = 12.sp,
                        )
                    }
                    if (e.intact) TextButton(onClick = { restoreEntry = e }) { Text("Restore", color = c.accent) }
                    TextButton(onClick = { confirmDelete = e }) { Text("Delete", color = c.danger) }
                }
                if (i < entries.size - 1) Hairline()
            }
        }
    }

    if (setPass) PassphraseSheet(vm) { setPass = false }

    when (val s = step) {
        RestoreStep.Idle -> {}
        is RestoreStep.Failed -> AlertDialog(onDismissRequest = { step = RestoreStep.Idle }, title = { Text("Can’t use this file") }, text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { step = RestoreStep.Idle }) { Text("OK") } })
        is RestoreStep.AskPassphrase -> AskPassphrase(s.wrong, onCancel = { step = RestoreStep.Idle }) { pass ->
            scope.launch {
                step = when (val r = vm.inspectBackup(s.bytes, pass)) {
                    is BackupManager.Opened.Ready -> RestoreStep.Confirm(r.file)
                    BackupManager.Opened.NeedsPassphrase -> RestoreStep.AskPassphrase(s.bytes, true)
                    is BackupManager.Opened.Failed -> if (r.message.contains("passphrase")) RestoreStep.AskPassphrase(s.bytes, true) else RestoreStep.Failed(r.message)
                }
            }
        }
        is RestoreStep.Confirm -> ConfirmDialog("Replace everything with this backup?",
            "It has ${s.file.transactions.size} transactions, ${s.file.goals.size} goals and ${s.file.accounts?.size ?: 1} accounts. Everything you have now is replaced; a safety copy is kept first.",
            "Restore", true, onConfirm = { vm.restoreBackup(s.file) }, onDismiss = { step = RestoreStep.Idle })
    }
    restoreEntry?.let { e ->
        LaunchedEffect(e) {
            val bytes = e.file.readBytes()
            step = when (val r = vm.inspectBackup(bytes, null)) {
                is BackupManager.Opened.Ready -> RestoreStep.Confirm(r.file)
                BackupManager.Opened.NeedsPassphrase -> RestoreStep.AskPassphrase(bytes, false)
                is BackupManager.Opened.Failed -> RestoreStep.Failed(r.message)
            }
            restoreEntry = null
        }
    }
    confirmDelete?.let { e -> ConfirmDialog("Delete this backup?", e.name, "Delete", true, onConfirm = { vm.deleteBackup(e) }, onDismiss = { confirmDelete = null }) }
}

@Composable
private fun AskPassphrase(wrong: Boolean, onCancel: () -> Unit, onOpen: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel, title = { Text("This backup is encrypted") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (wrong) "That passphrase doesn’t open it. Try again." else "Enter its passphrase.")
                androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = fieldColors())
            }
        },
        confirmButton = { TextButton(onClick = { onOpen(text) }, enabled = text.isNotEmpty()) { Text("Open") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PassphraseSheet(vm: MonoViewModel, close: () -> Unit) {
    val c = Mono.colors
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    FormSheet("Backup passphrase", close) {
        androidx.compose.material3.OutlinedTextField(value = a, onValueChange = { a = it }, label = { Text("Passphrase") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        androidx.compose.material3.OutlinedTextField(value = b, onValueChange = { b = it }, label = { Text("Repeat it") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        if (a.isNotEmpty() && a.length < 8) Text("Use at least 8 characters.", color = c.warning, fontSize = 12.sp)
        if (b.isNotEmpty() && a != b) Text("The two don’t match.", color = c.danger, fontSize = 12.sp)
        Text("There is no way to recover a forgotten passphrase.", color = c.inkTertiary, fontSize = 12.sp)
        PrimaryButton("Save passphrase", enabled = a.length >= 8 && a == b) { vm.setPassphrase(a); close() }
    }
}

package app.mono.finance.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.theme.ThemeChecks
import app.mono.domain.theme.ThemeColors
import app.mono.domain.theme.ThemePalette
import app.mono.finance.MonoViewModel
import app.mono.finance.ui.ChipRow
import app.mono.finance.ui.ConfirmDialog
import app.mono.finance.ui.Hairline
import app.mono.finance.ui.PrimaryButton
import app.mono.finance.ui.SecondaryButton
import app.mono.finance.ui.SectionTitle
import app.mono.finance.ui.TextInput
import app.mono.finance.ui.clickableNoRipple
import app.mono.finance.ui.surface
import app.mono.finance.ui.theme.Mono

private fun swatch(hex: Int) = Color(0xFF000000.toInt() or hex)

@Composable
private fun Swatches(c: ThemeColors) {
    val line = Mono.colors.line
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(c.primary, c.secondary, c.accent, c.background, c.surface).forEach {
            Box(Modifier.size(18.dp).background(swatch(it), CircleShape).border(0.5.dp, line, CircleShape))
        }
    }
}

@Composable
fun ThemeScreen(vm: MonoViewModel, onBack: () -> Unit) {
    val c = Mono.colors
    val model by vm.theme.collectAsState()
    var confirmDelete by remember { mutableStateOf<ThemePalette?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val dark = c.dark

    ScreenFrame("Theme", onBack) {
        Text("Pick a palette, or make your own. Changes preview across the whole app as you make them. No neon: keep colours muted and readable.", color = c.inkSecondary, fontSize = 13.sp)

        Column(Modifier.fillMaxWidth().surface()) {
            SectionTitle("Palettes")
            model.palettes.forEachIndexed { i, p ->
                val isSelected = !model.hasDraft && p.id == model.selected.id
                Row(Modifier.fillMaxWidth().clickableNoRipple { vm.themeSelect(p.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.name + if (model.isPreset(p.id)) "" else "  ·  yours", color = c.ink, fontSize = 16.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        Swatches(p.colors(dark))
                    }
                    if (isSelected) Text("In use", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.themeSelect(p.id); vm.themeEdit { it } }) { Text(if (model.isPreset(p.id)) "Make my own from this" else "Edit", color = c.accent, fontSize = 13.sp) }
                    TextButton(onClick = { vm.themeDuplicate(p.id) }) { Text("Duplicate", color = c.accent, fontSize = 13.sp) }
                    if (!model.isPreset(p.id)) TextButton(onClick = { confirmDelete = p }) { Text("Delete", color = c.danger, fontSize = 13.sp) }
                }
                if (i < model.palettes.size - 1) Hairline()
            }
        }

        val draft = model.draft
        if (draft != null) Editor(draft, vm)

        if (model.saved.custom.isNotEmpty() || model.saved.selectedID != ThemePalette.default.id) {
            SecondaryButton("Reset theme", Modifier.fillMaxWidth()) { confirmReset = true }
        }
    }
    confirmDelete?.let { p -> ConfirmDialog("Delete “${p.name}”?", "The app goes back to Sage & Cream.", "Delete", true, onConfirm = { vm.themeDelete(p.id) }, onDismiss = { confirmDelete = null }) }
    if (confirmReset) ConfirmDialog("Reset the theme?", "Your own palettes are removed and Sage & Cream is used again.", "Reset", true, onConfirm = { vm.themeReset() }, onDismiss = { confirmReset = false })
}

@Composable
private fun Editor(draft: ThemePalette, vm: MonoViewModel) {
    val c = Mono.colors
    var tabDark by remember { mutableStateOf(c.dark) }
    var name by remember(draft.id) { mutableStateOf(draft.name) }
    val colors = if (tabDark) draft.dark else draft.light
    fun update(f: (ThemeColors) -> ThemeColors) = vm.themeEdit { p -> if (tabDark) p.copy(dark = f(p.dark)) else p.copy(light = f(p.light)) }

    Column(Modifier.fillMaxWidth().surface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Editing")
        TextInput(name, { name = it.take(32); vm.themeEdit { p -> p.copy(name = it.take(32)) } }, "Palette name")
        ChipRow(listOf(false, true), tabDark, { if (it) "Dark colours" else "Light colours" }) { tabDark = it }
        ColorRow("Main colour", colors.primary) { v -> update { it.copy(primary = v) } }
        ColorRow("Second colour", colors.secondary) { v -> update { it.copy(secondary = v) } }
        ColorRow("Background", colors.background) { v -> update { it.copy(background = v) } }
        ColorRow("Cards", colors.surface) { v -> update { it.copy(surface = v) } }
        ColorRow("Accent", colors.accent) { v -> update { it.copy(accent = v) } }
        ColorRow("Text", colors.text) { v -> update { it.copy(text = v) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Choose secondary text and borders myself", color = c.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = colors.secondaryText != null, onCheckedChange = { on ->
                update { if (on) it.copy(secondaryText = it.textSecondary, border = it.line) else it.copy(secondaryText = null, border = null) }
            })
        }
        colors.secondaryText?.let { ColorRow("Secondary text", it) { v -> update { c2 -> c2.copy(secondaryText = v) } } }
        colors.border?.let { ColorRow("Borders", it) { v -> update { c2 -> c2.copy(border = v) } } }

        Text("Readability", color = c.inkSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        ThemeChecks.of(colors).forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k.label, color = c.ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("%.1f:1".format(k.ratio) + if (k.passes) "  ✓" else "  needs ${k.minimum}:1", color = if (k.passes) c.accent else c.warning, fontSize = 13.sp)
            }
        }
        PrimaryButton("Save palette") { vm.themeSave(name) }
        SecondaryButton("Discard changes", Modifier.fillMaxWidth()) { vm.themeDiscard() }
    }
}

@Composable
private fun ColorRow(label: String, value: Int, onChange: (Int) -> Unit) {
    val c = Mono.colors
    var text by remember(value) { mutableStateOf(ThemeChecks.hex(value)) }
    val parsed = ThemeChecks.parseHex(text)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).background(swatch(value), CircleShape).border(0.5.dp, c.line, CircleShape))
        Text(label, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.OutlinedTextField(
            value = text, onValueChange = { text = it.take(7); ThemeChecks.parseHex(it)?.let(onChange) }, singleLine = true, isError = parsed == null,
            modifier = Modifier.weight(1.1f), colors = app.mono.finance.ui.fieldColors(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        )
    }
}


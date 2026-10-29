package app.mono.domain.theme

import java.util.UUID

/** The saved theme choices: which palette is active and the user's own palettes. */
data class ThemeSaved(val selectedID: String = ThemePalette.default.id, val custom: List<ThemePalette> = emptyList())

/**
 * Which palette is active, the user's saved palettes, and an unsaved edit (the draft) that the whole app previews while it exists.
 * Immutable: every change returns a new model. Presets are never modified; editing one makes a copy.
 */
data class ThemeModel(val saved: ThemeSaved = ThemeSaved(), val draft: ThemePalette? = null) {
    val palettes: List<ThemePalette> get() = ThemePalette.presets + saved.custom
    val selected: ThemePalette get() = palettes.firstOrNull { it.id == saved.selectedID } ?: ThemePalette.default
    /** What the interface renders right now. */
    val active: ThemePalette get() = draft ?: selected
    val hasDraft: Boolean get() = draft != null

    fun isPreset(id: String) = ThemePalette.presets.any { it.id == id }

    fun select(id: String): ThemeModel = if (palettes.none { it.id == id }) this else copy(saved = saved.copy(selectedID = id), draft = null)

    /** Starts (or continues) an edit. A preset is copied into a new custom palette first. */
    fun edit(change: (ThemePalette) -> ThemePalette): ThemeModel {
        var d = draft ?: selected
        if (draft == null && isPreset(d.id)) d = d.copy(id = "custom-${UUID.randomUUID()}", name = uniqueName("My palette"))
        return copy(draft = change(d))
    }

    fun rename(name: String) = edit { it.copy(name = name) }

    /** Saves the draft as a custom palette and selects it. */
    fun saveDraft(name: String? = null): ThemeModel {
        var d = draft ?: return this
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { d = d.copy(name = it.take(32)) }
        val custom = if (saved.custom.any { it.id == d.id }) saved.custom.map { if (it.id == d.id) d else it } else saved.custom + d
        return ThemeModel(ThemeSaved(d.id, custom), null)
    }

    fun discardDraft() = copy(draft = null)

    fun delete(id: String): ThemeModel {
        if (isPreset(id)) return this
        val custom = saved.custom.filter { it.id != id }
        return ThemeModel(ThemeSaved(if (saved.selectedID == id) ThemePalette.default.id else saved.selectedID, custom), if (draft?.id == id) null else draft)
    }

    fun duplicate(id: String): ThemeModel {
        val p = palettes.firstOrNull { it.id == id } ?: return this
        val copy = p.copy(id = "custom-${UUID.randomUUID()}", name = uniqueName(p.name + " copy"))
        return ThemeModel(ThemeSaved(copy.id, saved.custom + copy), null)
    }

    fun resetAll() = ThemeModel()

    private fun uniqueName(base: String): String {
        val names = palettes.map { it.name }.toSet()
        if (base !in names) return base
        var n = 2
        while ("$base $n" in names) n++
        return "$base $n"
    }
}

/** A readable-colours check shown in the editor: each pair needs at least [minimum]:1 contrast. */
data class ContrastCheck(val label: String, val ratio: Double, val minimum: Double) {
    val passes: Boolean get() = ratio >= minimum
}

object ThemeChecks {
    fun of(c: ThemeColors): List<ContrastCheck> = listOf(
        ContrastCheck("Text on background", ColorMath.contrast(c.text, c.background), 7.0),
        ContrastCheck("Text on cards", ColorMath.contrast(c.text, c.surface), 7.0),
        ContrastCheck("Secondary text on background", ColorMath.contrast(c.textSecondary, c.background), 4.5),
        ContrastCheck("Text on the main colour", ColorMath.contrast(c.onPrimary, c.primary), 4.5),
        ContrastCheck("Accent on cards", ColorMath.contrast(c.accent, c.surface), 3.0),
    )

    /** "#RRGGBB" (the # is optional) to 0xRRGGBB, or null for anything that isn't exactly six hex digits. */
    fun parseHex(text: String): Int? {
        val t = text.trim().removePrefix("#")
        return if (t.length == 6 && t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) t.toInt(16) else null
    }

    fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)
}

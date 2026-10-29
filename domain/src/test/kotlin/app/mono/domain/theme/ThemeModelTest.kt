package app.mono.domain.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the ThemeStore tests in NewFeatureTests.swift. */
class ThemeModelTest {
    @Test fun defaultsAndSelection() {
        var m = ThemeModel()
        assertEquals(ThemePalette.sageCream, m.selected)
        m = m.select("midnight-elegance")
        assertEquals("Midnight Elegance", m.active.name)
        m = m.select("does-not-exist")
        assertEquals("unknown ids are ignored", "midnight-elegance", m.active.id)
    }

    @Test fun editingAPresetMakesACopyAndNeverChangesThePreset() {
        var m = ThemeModel().edit { it.copy(light = it.light.copy(accent = 0x123456)) }
        assertTrue(m.hasDraft)
        assertEquals(0x123456, m.active.light.accent)
        assertTrue(m.active.id.startsWith("custom-"))
        assertEquals("My palette", m.active.name)
        assertEquals(ThemePalette.sageCream, ThemePalette.presets.first { it.id == "sage-cream" })
        m = m.discardDraft()
        assertEquals("discarding restores the palette", ThemePalette.sageCream, m.active)
    }

    @Test fun savingKeepsTheCustomPaletteAndNeverSavesAnUnsavedDraft() {
        var m = ThemeModel().edit { it.copy(dark = it.dark.copy(background = 0x0A0A0A)) }
        m = m.saveDraft("  Evening  ")
        assertEquals("Evening", m.selected.name); assertEquals(0x0A0A0A, m.selected.dark.background)
        assertFalse(m.hasDraft)
        assertEquals("only the saved part is persisted", 1, m.saved.custom.size)
        assertNull(ThemeModel(m.saved).draft)
    }

    @Test fun namesAreUniqueAndDeleteFallsBackToDefault() {
        var m = ThemeModel().edit { it.copy(light = it.light.copy(text = 0x111111)) }.saveDraft()
        m = m.select("sage-cream").edit { it.copy(light = it.light.copy(text = 0x222222)) }
        assertEquals("My palette 2", m.active.name)
        m = m.saveDraft()
        m = m.delete(m.selected.id)
        assertEquals(ThemePalette.sageCream, m.selected)
        assertEquals(1, m.saved.custom.size)
        assertEquals("presets can't be deleted", 4, m.delete("sage-cream").palettes.size)
    }

    @Test fun duplicateAndReset() {
        var m = ThemeModel().duplicate("warm-minimal")
        assertEquals("Warm Minimal copy", m.selected.name)
        assertEquals(ThemePalette.warmMinimal.light, m.selected.light)
        m = m.resetAll()
        assertEquals(ThemePalette.sageCream, m.selected); assertTrue(m.saved.custom.isEmpty())
    }

    @Test fun secondaryTextAndBorderFollowYourColoursUntilYouPickOne() {
        var c = ThemePalette.sageCream.light
        val auto = Triple(c.textSecondary, c.line, c.cardLine)
        assertNull(c.secondaryText); assertNull(c.border)
        c = c.copy(secondaryText = 0x445566, border = 0xAABBCC)
        assertEquals(0x445566, c.textSecondary); assertEquals(0xAABBCC, c.line); assertEquals(0xAABBCC, c.cardLine)
        c = c.copy(secondaryText = null, border = null)
        assertEquals(auto, Triple(c.textSecondary, c.line, c.cardLine))
    }

    @Test fun contrastChecksPassForEveryPresetAndFlagABadPair() {
        for (p in ThemePalette.presets) for (dark in listOf(false, true)) {
            val failing = ThemeChecks.of(p.colors(dark)).filter { !it.passes }
            assertTrue("${p.name} dark=$dark fails: ${failing.map { it.label }}", failing.isEmpty())
        }
        val bad = ThemePalette.sageCream.light.copy(text = 0xC8C8B8) // pale text on a pale background
        assertFalse(ThemeChecks.of(bad).first { it.label == "Text on background" }.passes)
    }

    @Test fun hexParsingIsStrict() {
        assertEquals(0x56705A, ThemeChecks.parseHex("#56705A")); assertEquals(0x56705A, ThemeChecks.parseHex("56705a"))
        assertNull(ThemeChecks.parseHex("#56705")); assertNull(ThemeChecks.parseHex("#GGGGGG")); assertNull(ThemeChecks.parseHex(""))
        assertEquals("#56705A", ThemeChecks.hex(0x56705A)); assertEquals("#00000A", ThemeChecks.hex(10))
    }
}

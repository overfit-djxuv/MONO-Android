package app.mono.finance

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.mono.domain.theme.ThemeModel
import app.mono.domain.theme.ThemePalette
import app.mono.domain.theme.ThemeSaved
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonoApp

    @After fun reset() = runBlocking { app.settings.setTheme(ThemeSaved()) }

    private fun until(cond: () -> Boolean) { val end = System.currentTimeMillis() + 10_000; while (!cond() && System.currentTimeMillis() < end) Thread.sleep(100); assertTrue(cond()) }

    @Test fun aChosenAndEditedPaletteIsSavedAndComesBack() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: MonoViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[MonoViewModel::class.java] }
            vm.themeSelect("warm-minimal")
            until { runBlocking { app.settings.theme.first().selectedID } == "warm-minimal" }

            vm.themeEdit { it.copy(light = it.light.copy(accent = 0x123456)) }
            assertTrue("a preset is copied, never changed", vm.theme.value.active.id.startsWith("custom-"))
            assertEquals("the saved choice is untouched until you save", "warm-minimal", runBlocking { app.settings.theme.first().selectedID })
            vm.themeSave("  Evening  ")
            until { runBlocking { app.settings.theme.first().custom.size } == 1 }
            val saved = runBlocking { app.settings.theme.first() }
            assertEquals("Evening", saved.custom[0].name); assertEquals(0x123456, saved.custom[0].light.accent)
            assertEquals(saved.custom[0].id, saved.selectedID)
            assertEquals("untouched presets stay as they were", ThemePalette.warmMinimal.light.accent, ThemeModel(saved).palettes.first { it.id == "warm-minimal" }.light.accent)

            vm.themeDelete(saved.selectedID)
            until { runBlocking { app.settings.theme.first().custom.isEmpty() } }
            assertEquals("deleting the active palette falls back to the default", ThemePalette.default.id, runBlocking { app.settings.theme.first().selectedID })
        }
    }
}

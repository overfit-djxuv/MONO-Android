package app.mono.domain.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class PaletteTest {
    @Test fun contrastOfBlackOnWhiteIs21() {
        assertEquals(21.0, ColorMath.contrast(0x000000, 0xFFFFFF), 0.001)
    }

    @Test fun mixMovesTowardTheSecondColour() {
        assertEquals(0x808080, ColorMath.mix(0x000000, 0xFFFFFF, 0.5))
        assertEquals(0x102030, ColorMath.mix(0x102030, 0xFFFFFF, 0.0))
    }

    @Test fun textReadsOnBackgroundInEveryPreset() {
        for (p in ThemePalette.presets) for (dark in listOf(false, true)) {
            val c = p.colors(dark)
            assertTrue("${p.name} dark=$dark text", ColorMath.contrast(c.text, c.background) >= 7.0)
            assertTrue("${p.name} dark=$dark text on surface", ColorMath.contrast(c.text, c.surface) >= 7.0)
            assertTrue("${p.name} dark=$dark secondary text", ColorMath.contrast(c.textSecondary, c.background) >= 4.5)
            assertTrue("${p.name} dark=$dark on primary", ColorMath.contrast(c.onPrimary, c.primary) >= 4.5)
        }
    }

    /** MONO has no neon: every preset colour keeps a low OKLCH chroma (the iOS suite uses < 0.30). */
    @Test fun noNeonInAnyPreset() {
        for (p in ThemePalette.presets) for (dark in listOf(false, true)) {
            val c = p.colors(dark)
            for (hex in listOf(c.primary, c.secondary, c.accent, c.heroBottom)) {
                assertTrue("${p.name} #${hex.toString(16)} chroma", oklchChroma(hex) < 0.30)
            }
        }
    }

    private fun oklchChroma(hex: Int): Double {
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        val (r0, g0, b0) = ColorMath.components(hex)
        val r = lin(r0); val g = lin(g0); val b = lin(b0)
        val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        return sqrt(a * a + bb * bb)
    }

    @Test fun derivedTokensAreStable() {
        val c = ThemePalette.sageCream.light
        assertEquals(ColorMath.mix(c.text, c.background, 0.30), c.textSecondary)
        assertTrue(abs(ColorMath.contrast(c.heroBottom, c.onPrimary)) >= 4.5)
    }
}

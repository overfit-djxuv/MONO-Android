package app.mono.domain.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Small, dependency-free colour helpers working on 0xRRGGBB values. Port of ColorMath in ThemeModels.swift. */
object ColorMath {
    fun components(hex: Int): Triple<Double, Double, Double> =
        Triple(((hex shr 16) and 0xFF) / 255.0, ((hex shr 8) and 0xFF) / 255.0, (hex and 0xFF) / 255.0)

    /** WCAG relative luminance. */
    fun luminance(hex: Int): Double {
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        val (r, g, b) = components(hex)
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    /** WCAG contrast ratio, 1..21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [a] moved [t] (0..1) of the way toward [b]. */
    fun mix(a: Int, b: Int, t: Double): Int {
        val x = components(a)
        val y = components(b)
        fun ch(p: Double, q: Double): Int = ((p + (q - p) * t).coerceIn(0.0, 1.0) * 255).roundToInt()
        return (ch(x.first, y.first) shl 16) or (ch(x.second, y.second) shl 8) or ch(x.third, y.third)
    }

    /** Text colour (white or near-black) that reads best on [hex]. */
    fun onColor(hex: Int): Int = if (contrast(hex, 0xFFFFFF) >= contrast(hex, 0x1A1A1A)) 0xFFFFFF else 0x1A1A1A
}

/** The six colours a user can edit; everything else is derived. All values are 0xRRGGBB. */
data class ThemeColors(
    val primary: Int,
    val secondary: Int,
    val background: Int,
    val surface: Int,
    val accent: Int,
    val text: Int,
    val secondaryText: Int? = null,
    val border: Int? = null,
) {
    val textSecondary: Int get() = secondaryText ?: ColorMath.mix(text, background, 0.30)
    val textTertiary: Int get() = ColorMath.mix(secondaryText ?: text, background, if (secondaryText == null) 0.44 else 0.30)
    val line: Int get() = border ?: ColorMath.mix(background, text, 0.12)
    val cardLine: Int get() = border ?: ColorMath.mix(surface, text, 0.10)
    val accentSoft: Int get() = ColorMath.mix(surface, accent, 0.16)
    val onPrimary: Int get() = ColorMath.onColor(primary)
    /** A gentle shade, never a harsh gradient. */
    val heroBottom: Int get() = ColorMath.mix(primary, text, 0.14)
}

data class ThemePalette(val id: String, val name: String, val light: ThemeColors, val dark: ThemeColors) {
    fun colors(dark: Boolean) = if (dark) this.dark else light

    companion object {
        val warmMinimal = ThemePalette(
            "warm-minimal", "Warm Minimal",
            ThemeColors(0x6B5444, 0xD9C7AE, 0xF7F1E7, 0xFFFBF4, 0x7A5A41, 0x2E2823),
            ThemeColors(0x7A5E48, 0x4A3F35, 0x171310, 0x221D19, 0xCDAE90, 0xF1E9DE),
        )
        val midnightElegance = ThemePalette(
            "midnight-elegance", "Midnight Elegance",
            ThemeColors(0x3D4A59, 0xC7CED6, 0xF1F3F5, 0xFFFFFF, 0x4A5C70, 0x1C2128),
            ThemeColors(0x4A5A6D, 0x4B5663, 0x111316, 0x1A1E23, 0xB9C2CC, 0xE6E9ED),
        )
        val sageCream = ThemePalette(
            "sage-cream", "Sage & Cream",
            ThemeColors(0x56705A, 0x7C7A48, 0xF5F0E3, 0xFFFCF4, 0x4C6A52, 0x242A23),
            ThemeColors(0x4D6A53, 0x8A8758, 0x131814, 0x1C231E, 0xA9C2AC, 0xEDEFE5),
        )
        val presets = listOf(sageCream, warmMinimal, midnightElegance)
        val default = sageCream
    }
}

/** Status colours that stay fixed across palettes: (light, dark). */
object StatusColors {
    val warning = 0xA2640F to 0xD9A441
    val danger = 0xA3402B to 0xE07A63
}

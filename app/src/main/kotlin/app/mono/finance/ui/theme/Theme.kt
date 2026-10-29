package app.mono.finance.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.domain.theme.StatusColors
import app.mono.domain.theme.ThemeColors
import app.mono.domain.theme.ThemePalette

/**
 * The only place colours are defined. Screens read `Mono.colors.*`; never hard-code a colour elsewhere.
 * Every token derives from the active palette, as `Theme.*` does on iOS. Muted tones only: no neon.
 */
@androidx.compose.runtime.Immutable
class MonoColors(
    val primary: Color, val secondary: Color, val bg: Color, val surface: Color, val accent: Color, val ink: Color,
    val inkSecondary: Color, val inkTertiary: Color, val line: Color, val cardLine: Color, val accentSoft: Color,
    val onPrimary: Color, val heroBottom: Color, val warning: Color, val danger: Color, val dark: Boolean,
) {
    val income: Color get() = accent
    val expense: Color get() = ink
}

private fun Int.c() = Color(0xFF000000.toInt() or this)

fun monoColors(t: ThemeColors, dark: Boolean) = MonoColors(
    primary = t.primary.c(), secondary = t.secondary.c(), bg = t.background.c(), surface = t.surface.c(),
    accent = t.accent.c(), ink = t.text.c(), inkSecondary = t.textSecondary.c(), inkTertiary = t.textTertiary.c(),
    line = t.line.c(), cardLine = t.cardLine.c(), accentSoft = t.accentSoft.c(), onPrimary = t.onPrimary.c(),
    heroBottom = t.heroBottom.c(),
    warning = (if (dark) StatusColors.warning.second else StatusColors.warning.first).c(),
    danger = (if (dark) StatusColors.danger.second else StatusColors.danger.first).c(),
    dark = dark,
)

object Mono {
    val radius = 12.dp
    val cardRadius = 24.dp
    val gutter = 20.dp

    val colors: MonoColors
        @Composable get() = LocalMonoColors.current
}

val LocalMonoColors = staticCompositionLocalOf { monoColors(ThemePalette.default.light, false) }

/** Rounded figures for money, like SF Rounded on iOS. */
val FigureFont = FontFamily.SansSerif

fun figure(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = FigureFont, fontSize = size.sp, fontWeight = weight)

@Composable
fun MonoTheme(
    palette: ThemePalette = ThemePalette.default,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = monoColors(palette.colors(dark), dark)
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.primary, onPrimary = colors.onPrimary, secondary = colors.secondary,
            background = colors.bg, onBackground = colors.ink, surface = colors.surface, onSurface = colors.ink,
            outline = colors.line, error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.primary, onPrimary = colors.onPrimary, secondary = colors.secondary,
            background = colors.bg, onBackground = colors.ink, surface = colors.surface, onSurface = colors.ink,
            outline = colors.line, error = colors.danger,
        )
    }
    CompositionLocalProvider(LocalMonoColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}

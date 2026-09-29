@file:OptIn(ExperimentalTextApi::class)

package com.joenet.mixtape.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joenet.mixtape.R

/** Which theme the app follows. Persisted by [com.joenet.mixtape.AppSettings] under [key]. */
enum class ThemeMode(val key: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        /** Unknown or missing keys fall back to following the phone. */
        fun fromKey(key: String?): ThemeMode = ThemeMode.entries.firstOrNull { it.key == key } ?: ThemeMode.System
    }
}

/** One complete set of theme-following colours. Swapped as a whole so a reader never sees a half-changed palette. */
private class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val hairline: Color,
    val fg: Color,
    val fgMuted: Color,
    val accent: Color,
)

/** The espresso deck: the original look. */
private val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF14110E),
    surface = Color(0xFF1D1915),
    surfaceHigh = Color(0xFF28221C),
    hairline = Color(0xFF3A3129),
    fg = Color(0xFFF3E9DC),
    fgMuted = Color(0xFFA3927F),
    accent = Color(0xFFFF6B35),
)

/** Warm paper. */
private val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF7F1E7),
    surface = Color(0xFFEFE6D8),
    surfaceHigh = Color(0xFFE4D8C6),
    hairline = Color(0xFFD6C8B2),
    fg = Color(0xFF241E18),
    fgMuted = Color(0xFF665645),
    accent = Color(0xFFA43D0B),
)

/** Snapshot state, so a read in composition or in a draw lambda re-runs when the theme flips. */
private val palette = mutableStateOf(DarkPalette)

/**
 * Cassette-deck palette: espresso-black shell (dark) or warm paper (light), cream/ink text, tape-orange accent.
 * Orange means "live": the playing song, progress, the play key's light, REC. Nothing else.
 *
 * The theme-following tokens ([Bg] ... [Accent], [isDark]) are plain getters over snapshot state, so they can be
 * read anywhere (composition, draw and event lambdas). [MixtapeTheme] sets them before its content is composed.
 * Anything built from them in a `remember {}` must be keyed on [isDark].
 *
 * Contrast ratios (WCAG 2.x relative luminance, checked with a small script when the palette was tuned).
 * Light palette, minimum required in brackets:
 *  - Fg on Bg 14.67, on Surface 13.33, on SurfaceHigh 11.72 [7.0]
 *  - FgMuted on Bg 6.27, on Surface 5.69, on SurfaceHigh 5.01 [4.5]
 *  - Accent (as text/icon) on Bg 5.75, on Surface 5.22, on SurfaceHigh 4.59 [4.5]
 *  - Material outline (light, 0xFF857560) on Bg 3.97, Surface 3.60, SurfaceHigh 3.17 [3.0, non-text parts]
 *  - Bg on Fg (content on a Fg-filled button) 14.67 [4.5]
 *
 * Dark palette:
 *  - Fg on Bg 15.68, on Surface 14.56, on SurfaceHigh 13.10 [7.0]
 *  - FgMuted on Bg 6.25, on Surface 5.80, on SurfaceHigh 5.22 [4.5]
 *  - Accent on Bg 6.64, on Surface 6.16, on SurfaceHigh 5.54 [4.5]
 *
 * Fixed colours: OnAccent on Orange 6.64, Mustard 10.63, Teal 8.68, Sage 6.81, Sky 7.45, Brick 4.31 (large or bold
 * text only there); OnCover on the dark Bg 15.68.
 */
object Tape {
    // ---- follow the theme ----
    /** Page background. */
    val Bg: Color get() = palette.value.bg
    /** Cards, rows, sheets base. */
    val Surface: Color get() = palette.value.surface
    /** Raised or selected surface. */
    val SurfaceHigh: Color get() = palette.value.surfaceHigh
    /** Dividers and outlines. */
    val Hairline: Color get() = palette.value.hairline
    /** Primary text and icons. */
    val Fg: Color get() = palette.value.fg
    /** Secondary text and icons. */
    val FgMuted: Color get() = palette.value.fgMuted
    /** The "live" orange as text or icon colour (a darker orange on light so it stays readable). */
    val Accent: Color get() = palette.value.accent
    val isDark: Boolean get() = palette.value.dark

    // ---- fixed: never change with the theme ----
    /** Text and icons drawn over cover art, scrims and dark gradients. */
    val OnCover = Color(0xFFF3E9DC)
    /** Content on orange, label-colour and Fg-filled buttons. */
    val OnAccent = Color(0xFF14110E)

    val Orange = Color(0xFFFF6B35)
    val Mustard = Color(0xFFF4B942)
    val Teal = Color(0xFF2EC4B6)
    val Brick = Color(0xFFD1495B)
    val Sage = Color(0xFF8AA37B)
    val Sky = Color(0xFF5DA9E9)

    // The physical cassette looks the same in both themes.
    val Paper = Color(0xFFEFE4D0)
    val PaperInk = Color(0xFF2A231D)
    val TapeBrown = Color(0xFF3F2718)
    val TapeEdge = Color(0xFF5E3C25)

    val labels = listOf(Orange, Mustard, Teal, Brick, Sage, Sky)

    /** Stable per-playlist label color, so each tape keeps its look. */
    fun labelColor(key: String): Color = labels[(key.lowercase().hashCode() and 0x7fffffff) % labels.size]

    // LEGACY: delete once every screen is migrated
    // Fixed dark values, kept only so screens that have not moved to the tokens above still compile.
    @Deprecated("Use Tape.Bg", ReplaceWith("Tape.Bg"))
    val Ink = Color(0xFF14110E)
    @Deprecated("Use Tape.Surface", ReplaceWith("Tape.Surface"))
    val Deck = Color(0xFF1D1915)
    @Deprecated("Use Tape.SurfaceHigh", ReplaceWith("Tape.SurfaceHigh"))
    val DeckHigh = Color(0xFF28221C)
    @Deprecated("Use Tape.Hairline", ReplaceWith("Tape.Hairline"))
    val Line = Color(0xFF3A3129)
    @Deprecated("Use Tape.Fg (or Tape.OnCover over art)", ReplaceWith("Tape.Fg"))
    val Cream = Color(0xFFF3E9DC)
    @Deprecated("Use Tape.FgMuted", ReplaceWith("Tape.FgMuted"))
    val Dust = Color(0xFFA3927F)
    // END LEGACY
}

/**
 * The app's typeface. Spotify Mix is proprietary and cannot be bundled, so this is Figtree (SIL OFL), a geometric
 * sans close to Circular / Spotify Mix, as one variable font file (res/font/figtree.ttf, weights 300-900).
 * To use licensed Spotify Mix files instead, add them to res/font and change only this definition.
 */
val Mix = FontFamily(
    Font(R.font.figtree, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.figtree, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.figtree, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.figtree, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.figtree, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

// LEGACY: delete once every screen is migrated
@Deprecated("Use Mix", ReplaceWith("Mix"))
val Marker = Mix

@Deprecated("Use Mix", ReplaceWith("Mix"))
val Barlow = Mix
// END LEGACY

/** The tape counter. */
val Mono = FontFamily.Monospace

/**
 * Text that would be printed on real hardware: SIDE A, C-90, REC, key legends. The only place
 * all-caps and wide letter spacing are allowed; everything else is sentence case.
 */
val Legend = TextStyle(fontFamily = Mix, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.5.sp)

private fun TextStyle.withMix(weight: FontWeight) = copy(fontFamily = Mix, fontWeight = weight)

// Spotify-like scale: titles Bold, body Regular, labels Medium. Every style uses Mix.
private val type = Typography().run {
    copy(
        displayLarge = displayLarge.withMix(FontWeight.Bold),
        displayMedium = displayMedium.withMix(FontWeight.Bold),
        displaySmall = displaySmall.withMix(FontWeight.Bold).copy(fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = 2.sp),
        headlineLarge = headlineLarge.withMix(FontWeight.Bold),
        headlineMedium = headlineMedium.withMix(FontWeight.Bold).copy(fontSize = 30.sp, lineHeight = 34.sp),
        headlineSmall = headlineSmall.withMix(FontWeight.Bold).copy(fontSize = 26.sp, lineHeight = 30.sp),
        titleLarge = titleLarge.withMix(FontWeight.Bold).copy(fontSize = 22.sp, lineHeight = 26.sp),
        titleMedium = titleMedium.withMix(FontWeight.Bold),
        titleSmall = titleSmall.withMix(FontWeight.Bold),
        bodyLarge = bodyLarge.withMix(FontWeight.Normal),
        bodyMedium = bodyMedium.withMix(FontWeight.Normal),
        bodySmall = bodySmall.withMix(FontWeight.Normal),
        labelLarge = labelLarge.withMix(FontWeight.Medium),
        labelMedium = labelMedium.withMix(FontWeight.Medium),
        labelSmall = labelSmall.withMix(FontWeight.Medium),
    )
}

// primary is Fg (cream on dark, ink on light), not orange: Material paints buttons, focus and switches
// with it, and none of those are "live". Orange is applied explicitly where something is happening right now.
private val darkColors = darkColorScheme(
    primary = DarkPalette.fg,
    onPrimary = DarkPalette.bg,
    primaryContainer = DarkPalette.surfaceHigh,
    onPrimaryContainer = DarkPalette.fg,
    secondary = Tape.Mustard,
    onSecondary = Tape.OnAccent,
    secondaryContainer = DarkPalette.surfaceHigh,
    onSecondaryContainer = DarkPalette.fg,
    tertiary = Tape.Teal,
    onTertiary = Tape.OnAccent,
    background = DarkPalette.bg,
    onBackground = DarkPalette.fg,
    surface = DarkPalette.bg,
    onSurface = DarkPalette.fg,
    surfaceVariant = DarkPalette.surfaceHigh,
    onSurfaceVariant = DarkPalette.fgMuted,
    inverseSurface = DarkPalette.fg,
    inverseOnSurface = DarkPalette.bg,
    inversePrimary = DarkPalette.accent,
    surfaceContainerLowest = DarkPalette.bg,
    surfaceContainerLow = DarkPalette.surface,
    surfaceContainer = DarkPalette.surface,
    surfaceContainerHigh = DarkPalette.surfaceHigh,
    surfaceContainerHighest = DarkPalette.surfaceHigh,
    outline = DarkPalette.hairline,
    outlineVariant = DarkPalette.hairline,
    error = Tape.Brick,
    onError = Tape.OnAccent,
    errorContainer = Color(0xFF3B1A1E),
    onErrorContainer = Color(0xFFFFB3B9),
)

private val lightColors = lightColorScheme(
    primary = LightPalette.fg,
    onPrimary = LightPalette.bg,
    primaryContainer = LightPalette.surfaceHigh,
    onPrimaryContainer = LightPalette.fg,
    secondary = Tape.Mustard,
    onSecondary = Tape.OnAccent,
    secondaryContainer = LightPalette.surfaceHigh,
    onSecondaryContainer = LightPalette.fg,
    tertiary = Tape.Teal,
    onTertiary = Tape.OnAccent,
    background = LightPalette.bg,
    onBackground = LightPalette.fg,
    surface = LightPalette.bg,
    onSurface = LightPalette.fg,
    surfaceVariant = LightPalette.surfaceHigh,
    onSurfaceVariant = LightPalette.fgMuted,
    inverseSurface = LightPalette.fg,
    inverseOnSurface = LightPalette.bg,
    inversePrimary = DarkPalette.accent,
    surfaceContainerLowest = LightPalette.bg,
    surfaceContainerLow = LightPalette.surface,
    surfaceContainer = LightPalette.surface,
    surfaceContainerHigh = LightPalette.surfaceHigh,
    surfaceContainerHighest = LightPalette.surfaceHigh,
    // Hairline is too faint for control outlines (switch, text field, segmented control) on paper, so the
    // light scheme uses a darker outline (3:1 or better on Bg, Surface and SurfaceHigh).
    outline = Color(0xFF857560),
    outlineVariant = LightPalette.hairline,
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Resolves [mode] (System follows the phone), points the [Tape] tokens at the matching palette and provides the
 * Material colour scheme, typography and shapes. The palette is written here, before [content] is composed, so
 * everything below reads the right colours on its first pass. The write is guarded so it only happens on a flip.
 */
@Composable
fun MixtapeTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    if (Tape.isDark != dark) palette.value = if (dark) DarkPalette else LightPalette
    MaterialTheme(colorScheme = if (dark) darkColors else lightColors, typography = type, shapes = shapes) {
        // Text and icons that do not name a colour follow the theme instead of Material's default black.
        CompositionLocalProvider(LocalContentColor provides Tape.Fg) {
            ProvideReduceMotion(content)
        }
    }
}

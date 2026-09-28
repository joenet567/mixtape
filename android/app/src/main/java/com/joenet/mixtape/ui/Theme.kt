package com.joenet.mixtape.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joenet.mixtape.R

/**
 * Cassette-deck palette: espresso-black shell, cream paper labels, tape-orange accent. Always dark.
 * Orange means "live": the playing song, progress, the play key's light, REC. Nothing else.
 */
object Tape {
    val Ink = Color(0xFF14110E)
    val Deck = Color(0xFF1D1915)
    val DeckHigh = Color(0xFF28221C)
    val Line = Color(0xFF3A3129)
    val Cream = Color(0xFFF3E9DC)
    val Dust = Color(0xFFA3927F)
    val Paper = Color(0xFFEFE4D0)
    val PaperInk = Color(0xFF2A231D)
    val Orange = Color(0xFFFF6B35)
    val Mustard = Color(0xFFF4B942)
    val Teal = Color(0xFF2EC4B6)
    val Brick = Color(0xFFD1495B)
    val Sage = Color(0xFF8AA37B)
    val Sky = Color(0xFF5DA9E9)
    val TapeBrown = Color(0xFF3F2718)
    val TapeEdge = Color(0xFF5E3C25)

    val labels = listOf(Orange, Mustard, Teal, Brick, Sage, Sky)

    /** Stable per-playlist label color, so each tape keeps its look. */
    fun labelColor(key: String): Color = labels[(key.lowercase().hashCode() and 0x7fffffff) % labels.size]
}

/** Biro on a tape label: cassette labels, the Side B spine, folder names. (Reenie Beanie, OFL) */
val Marker = FontFamily(Font(R.font.reenie_beanie))

/** Printed hardware legends and headings. (Barlow Condensed, OFL) */
val Barlow = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

/** The tape counter. */
val Mono = FontFamily.Monospace

/**
 * Text that would be printed on real hardware: SIDE A, C-90, REC, key legends. The only place
 * all-caps and wide letter spacing are allowed; everything else is sentence case.
 */
val Legend = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.5.sp)

// primary is cream, not orange: Material paints buttons, focus and switches with it, and none of
// those are "live". Orange is applied explicitly where something is happening right now.
private val colors = darkColorScheme(
    primary = Tape.Cream,
    onPrimary = Tape.Ink,
    primaryContainer = Tape.DeckHigh,
    onPrimaryContainer = Tape.Cream,
    secondary = Tape.Mustard,
    onSecondary = Tape.Ink,
    secondaryContainer = Tape.DeckHigh,
    onSecondaryContainer = Tape.Cream,
    tertiary = Tape.Teal,
    onTertiary = Tape.Ink,
    background = Tape.Ink,
    onBackground = Tape.Cream,
    surface = Tape.Ink,
    onSurface = Tape.Cream,
    surfaceVariant = Tape.DeckHigh,
    onSurfaceVariant = Tape.Dust,
    surfaceContainerLowest = Tape.Ink,
    surfaceContainerLow = Tape.Deck,
    surfaceContainer = Tape.Deck,
    surfaceContainerHigh = Tape.DeckHigh,
    surfaceContainerHighest = Tape.DeckHigh,
    outline = Tape.Line,
    outlineVariant = Tape.Line,
    error = Tape.Brick,
    onError = Tape.Ink,
    errorContainer = Color(0xFF3B1A1E),
    onErrorContainer = Color(0xFFFFB3B9),
)

// Headings in Barlow Condensed; body and labels stay on the system sans (Roboto) in sentence case.
private val type = Typography().run {
    copy(
        displaySmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 4.sp),
        headlineMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp),
        headlineSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp),
        titleLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

@Composable
fun MixtapeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, shapes = shapes) {
        ProvideReduceMotion(content)
    }
}

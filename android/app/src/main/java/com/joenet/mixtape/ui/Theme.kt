package com.joenet.mixtape.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Cassette-deck palette: espresso-black shell, cream paper labels, tape-orange accent. Always dark. */
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

    private val labels = listOf(Orange, Mustard, Teal, Brick, Sage, Sky)

    /** Stable per-playlist label color, so each tape keeps its look. */
    fun labelColor(key: String): Color = labels[(key.lowercase().hashCode() and 0x7fffffff) % labels.size]
}

/** Condensed "printed label" face, a marker hand for handwritten labels, monospace for the tape counter. */
val Condensed = FontFamily(
    Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Bold),
)
val Marker = FontFamily(Font(DeviceFontFamilyName("casual")))
val Mono = FontFamily.Monospace

private val colors = darkColorScheme(
    primary = Tape.Orange,
    onPrimary = Tape.Ink,
    primaryContainer = Color(0xFF4A2112),
    onPrimaryContainer = Color(0xFFFFB59A),
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

private val type = Typography().run {
    copy(
        displaySmall = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 5.sp),
        headlineSmall = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp),
        titleLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.5.sp),
        labelMedium = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp),
        labelSmall = TextStyle(fontFamily = Condensed, fontSize = 11.sp, letterSpacing = 1.5.sp),
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
    MaterialTheme(colorScheme = colors, typography = type, shapes = shapes, content = content)
}

package com.joenet.mixtape.ui

import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * A compact cassette drawn in Compose. All geometry is in fractions of the width W (height = W / RATIO).
 * The reels show real tape transport: tape moves from the left pack to the right one as [progress]
 * goes 0 -> 1, and at constant tape speed the emptier reel turns faster.
 */

const val CASSETTE_RATIO = 1.58f
private const val HUB = 0.045f
private const val PACK_MAX = 0.1f
private const val TAPE_SPEED = 8f // degrees per second x reel radius (in W units)

private val Shell = Color(0xFF342D26)
private val ShellDark = Color(0xFF1F1A16)
private val Hole = Color(0xFF0B0908)
private val TapeBrown = Tape.TapeBrown
private val TapeEdge = Tape.TapeEdge
private val Rule = Color(0xFFCDBFA6)

private fun packRadius(fill: Float) =
    sqrt(HUB * HUB + (PACK_MAX * PACK_MAX - HUB * HUB) * fill.coerceIn(0f, 1f))

@Composable
fun Cassette(
    label: String,
    labelColor: Color,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    spinning: Boolean = false,
    art: Uri? = null,
    footLeft: String = "",
    footRight: String = "",
) {
    val context = LocalContext.current
    val sticker by produceState<ImageBitmap?>(null, art) {
        value = art?.let { ArtCache.load(context, it, 256) }
    }
    val p by rememberUpdatedState(progress.coerceIn(0f, 1f))
    var angleL by remember { mutableFloatStateOf(0f) }
    var angleR by remember { mutableFloatStateOf(33f) }
    // "Remove animations" on: the reels stand still, but the tape packs still show progress.
    val animate = !rememberReduceMotion()
    LaunchedEffect(spinning, animate) {
        if (!spinning || !animate) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                last = now
                angleL = (angleL + TAPE_SPEED * dt / packRadius(1f - p)) % 360f
                angleR = (angleR + TAPE_SPEED * dt / packRadius(p)) % 360f
            }
        }
    }

    BoxWithConstraints(
        modifier
            .aspectRatio(CASSETTE_RATIO)
            .semantics { contentDescription = "Cassette: $label" }
    ) {
        val w = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        fun sp(f: Float) = with(density) { (w * f).toSp() }
        fun dp(f: Float) = with(density) { (w * f).toDp() }
        fun at(x: Float, y: Float) = Modifier.offset { IntOffset((w * x).roundToInt(), (w * y).roundToInt()) }

        Canvas(Modifier.fillMaxSize()) {
            drawShell(labelColor)
            drawWindow(p, angleL, angleR)
            sticker?.let { drawSticker(it) }
        }
        // TextStyle.Default: the theme's fixed body line height would push small label text off the paper
        Text(
            label,
            style = TextStyle.Default,
            fontFamily = Marker,
            fontSize = sp(0.092f),
            color = Tape.PaperInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = at(0.09f, 0.052f).width(dp(0.82f)),
        )
        Text(
            "A",
            style = TextStyle.Default,
            fontFamily = Barlow,
            fontWeight = FontWeight.Bold,
            fontSize = sp(0.11f),
            color = Tape.PaperInk.copy(alpha = 0.85f),
            modifier = at(0.095f, 0.214f),
        )
        if (footLeft.isNotEmpty() || footRight.isNotEmpty()) {
            val foot = Legend.copy(fontSize = sp(0.03f), letterSpacing = sp(0.004f), color = Tape.PaperInk.copy(alpha = 0.7f))
            Row(at(0f, 0.394f).width(dp(1f)).padding(horizontal = dp(0.09f))) {
                Text(footLeft, style = foot, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(footRight, style = foot, maxLines = 1)
            }
        }
    }
}

/**
 * Side B of the tape: the cover art as the insert, cropped to the cassette's own frame, with the
 * title and artist written along the spine. Shown by flipping the tape over (see [FlipTape]).
 */
@Composable
fun TapeSideB(title: String, artist: String, art: Uri?, labelColor: Color, modifier: Modifier = Modifier, artworkData: ByteArray? = null) {
    BoxWithConstraints(
        modifier
            .aspectRatio(CASSETTE_RATIO)
            .semantics { contentDescription = "Side B: cover of $title by $artist" }
    ) {
        val w = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        fun sp(f: Float) = with(density) { (w * f).toSp() }
        fun dp(f: Float) = with(density) { (w * f).toDp() }
        val shape = RoundedCornerShape(dp(0.045f))
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Shell, ShellDark)))
                .padding(dp(0.03f))
        ) {
            Column(Modifier.fillMaxSize()) {
                // the insert: cover art, center-cropped to fill
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(dp(0.015f)))
                ) {
                    SongArt(art, Modifier.fillMaxSize(), px = 1024, corner = 0.dp, seed = title, artworkData = artworkData)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.25f)))
                    )
                }
                // the spine, written on in biro
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = dp(0.02f))
                        .clip(RoundedCornerShape(dp(0.012f)))
                        .background(Tape.Paper)
                        .padding(horizontal = dp(0.03f), vertical = dp(0.004f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(dp(0.05f))
                            .background(labelColor, RoundedCornerShape(dp(0.008f))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("B", style = TextStyle.Default, fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = sp(0.036f), color = Tape.PaperInk)
                    }
                    Text(
                        "$title — $artist",
                        style = TextStyle.Default,
                        fontFamily = Marker,
                        fontSize = sp(0.075f),
                        color = Tape.PaperInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = dp(0.025f)),
                    )
                }
            }
        }
    }
}

/**
 * Turns the tape over in 3D: [front] (Side A, the cassette) when [flipped] is false, [back]
 * (Side B) when true. The back is pre-rotated so it isn't mirrored.
 */
@Composable
fun FlipTape(
    flipped: Boolean,
    modifier: Modifier = Modifier,
    front: @Composable () -> Unit,
    back: @Composable () -> Unit,
) {
    val still = rememberReduceMotion()
    val rotation by animateFloatAsState(
        if (flipped) 180f else 0f,
        tween(if (still) 0 else 520, easing = FastOutSlowInEasing),
        label = "flip",
    )
    Box(
        modifier.graphicsLayer {
            rotationY = rotation
            cameraDistance = 14f * density
        }
    ) {
        if (rotation <= 90f) {
            front()
        } else {
            Box(Modifier.graphicsLayer { rotationY = 180f }) { back() }
        }
    }
}

internal fun DrawScope.drawShell(labelColor: Color) {
    val w = size.width
    val h = size.height
    fun f(x: Float) = x * w

    drawRoundRect(Brush.verticalGradient(listOf(Shell, ShellDark)), cornerRadius = CornerRadius(f(0.045f)))
    drawRoundRect(
        Color.White.copy(alpha = 0.07f),
        topLeft = Offset(f(0.006f), f(0.006f)),
        size = Size(w - f(0.012f), h - f(0.012f)),
        cornerRadius = CornerRadius(f(0.04f)),
        style = Stroke(f(0.004f)),
    )
    // paper label with a ruled writing line
    drawRoundRect(Tape.Paper, topLeft = Offset(f(0.055f), f(0.041f)), size = Size(f(0.89f), f(0.402f)), cornerRadius = CornerRadius(f(0.02f)))
    drawLine(Rule, Offset(f(0.09f), f(0.162f)), Offset(f(0.91f), f(0.162f)), strokeWidth = f(0.003f))
    // coloured band behind the window, with darker pin-stripes
    drawRect(labelColor, topLeft = Offset(f(0.055f), f(0.185f)), size = Size(f(0.89f), f(0.205f)))
    drawRect(Color.Black.copy(alpha = 0.2f), topLeft = Offset(f(0.055f), f(0.185f)), size = Size(f(0.89f), f(0.008f)))
    drawRect(Color.Black.copy(alpha = 0.2f), topLeft = Offset(f(0.055f), f(0.382f)), size = Size(f(0.89f), f(0.008f)))
    // head opening along the bottom edge
    val head = Path().apply {
        moveTo(f(0.21f), h)
        lineTo(f(0.26f), f(0.49f))
        lineTo(f(0.74f), f(0.49f))
        lineTo(f(0.79f), h)
        close()
    }
    drawPath(head, Color(0xFF191511))
    for (x in listOf(0.37f, 0.63f)) drawCircle(Hole, radius = f(0.017f), center = Offset(f(x), f(0.565f)))
    drawRect(Hole, topLeft = Offset(f(0.47f), f(0.537f)), size = Size(f(0.06f), f(0.045f)))
    for ((x, y) in listOf(0.035f to 0.035f, 0.965f to 0.035f, 0.035f to 0.598f, 0.965f to 0.598f)) {
        screw(Offset(f(x), f(y)), f(0.013f))
    }
}

private fun DrawScope.screw(c: Offset, r: Float) {
    drawCircle(Color(0xFF4A4038), r, c)
    drawLine(Color(0xFF221D19), Offset(c.x - r * 0.6f, c.y - r * 0.6f), Offset(c.x + r * 0.6f, c.y + r * 0.6f), strokeWidth = r * 0.35f)
}

internal fun DrawScope.drawWindow(progress: Float, angleL: Float, angleR: Float) {
    val w = size.width
    fun f(x: Float) = x * w
    val win = Rect(f(0.27f), f(0.21f), f(0.73f), f(0.365f))
    val winPath = Path().apply { addRoundRect(RoundRect(win, CornerRadius(f(0.03f)))) }
    drawPath(winPath, Color(0xFF0E0C0A))
    clipPath(winPath) {
        reel(Offset(f(0.365f), win.center.y), packRadius(1f - progress) * w, angleL, w)
        reel(Offset(f(0.635f), win.center.y), packRadius(progress) * w, angleR, w)
        val glare = Path().apply {
            moveTo(win.left + win.width * 0.20f, win.top)
            lineTo(win.left + win.width * 0.32f, win.top)
            lineTo(win.left + win.width * 0.18f, win.bottom)
            lineTo(win.left + win.width * 0.06f, win.bottom)
            close()
        }
        drawPath(glare, Color.White.copy(alpha = 0.06f))
    }
    drawPath(winPath, Tape.Line, style = Stroke(f(0.004f)))
}

private fun DrawScope.reel(c: Offset, packR: Float, angle: Float, w: Float) {
    drawCircle(TapeBrown, packR, c)
    drawCircle(TapeEdge, packR, c, style = Stroke(w * 0.004f))
    val hub = w * 0.042f
    val hole = w * 0.025f
    drawCircle(Tape.Paper, hub, c)
    drawCircle(Hole, hole, c)
    rotate(angle, c) {
        for (i in 0 until 6) {
            rotate(i * 60f, c) {
                drawLine(Tape.Paper, Offset(c.x, c.y - hole), Offset(c.x, c.y - hole * 0.5f), strokeWidth = w * 0.008f, cap = StrokeCap.Round)
            }
        }
        drawCircle(Color(0xFFB5A58B), w * 0.005f, Offset(c.x + (hub + hole) / 2f, c.y))
    }
}

internal fun DrawScope.drawSticker(img: ImageBitmap) {
    val w = size.width
    val side = w * 0.155f
    val topLeft = Offset(w * 0.765f, w * 0.2875f - side / 2f)
    val border = w * 0.006f
    drawRoundRect(
        Color.White,
        topLeft = Offset(topLeft.x - border, topLeft.y - border),
        size = Size(side + 2 * border, side + 2 * border),
        cornerRadius = CornerRadius(w * 0.01f),
    )
    val s = min(img.width, img.height)
    drawImage(
        img,
        srcOffset = IntOffset((img.width - s) / 2, (img.height - s) / 2),
        srcSize = IntSize(s, s),
        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
        dstSize = IntSize(side.roundToInt(), side.roundToInt()),
        filterQuality = FilterQuality.Medium,
    )
}

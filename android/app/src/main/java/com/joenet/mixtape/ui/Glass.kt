package com.joenet.mixtape.ui

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * Liquid Glass, implemented by hand: a translucent material that blurs and lightly saturates whatever
 * scrolls beneath it, with a bright specular rim and a soft floating shadow.
 *
 * ## Layering (everything happens in the draw phase, no extra layouts)
 *
 * 1. **Source layer.** [glassSource] goes on the full-screen content that the glass floats over. It records
 *    that content into [GlassBackdrop.layer] (a Compose `GraphicsLayer` = one RenderNode) and draws the layer
 *    back, so the app looks exactly as before and the same pixels are available to the glass surfaces.
 * 2. **Per-surface blurred layer.** Each [glass] surface owns a `GraphicsLayer` with a `BlurEffect` and a
 *    saturation `ColorFilter`. Every draw it records "the source layer, shifted so the part under this
 *    surface lands in the layer" into it, then draws it clipped to the surface's shape. The layer is padded
 *    by twice the blur radius (clamped to the source bounds) so edges blur against the real neighbouring
 *    pixels instead of smearing. The blur is a RenderEffect, so it needs API 31; below that, or when no
 *    backdrop is given, the blur is skipped and the tint alpha is raised so text stays readable.
 * 3. **Tint.** Theme-aware translucent fill over the blur (warm dark surface in dark mode, warm white in
 *    light mode), followed by a soft white sheen over the top 45 percent.
 * 4. **Rim.** The content is drawn, then a 1dp diagonal specular stroke (bright top-left, faint middle,
 *    medium bottom-right) on top. It brightens while an [interactive] surface is pressed.
 * 5. **Shadow.** `Modifier.shadow` (elevation shadow, black at low alpha) sits behind everything and is
 *    cut out under the shape by the platform, so it never muddies the glass itself.
 *
 * ## Window limitation
 *
 * The source layer can only be drawn by nodes of the same window. A `Dialog`, `AlertDialog`,
 * `ModalBottomSheet` or `DropdownMenu` lives in its own window and cannot sample the app's layer, so pass
 * `backdrop = null` there (the surface then falls back to the higher-alpha tint, which is what
 * [glassContainerColor] gives Material containers) and use [glassRim] for the highlight.
 *
 * ## Wiring
 *
 * ```
 * val b = rememberGlassBackdrop()
 * Box(Modifier.fillMaxSize()) {
 *     Box(Modifier.fillMaxSize().glassSource(b).background(Tape.Bg)) { screens, scrolling lists }
 *     CompositionLocalProvider(LocalGlassBackdrop provides b) { floating chrome }
 * }
 * ```
 *
 * The floating chrome (nav bar, mini player, top rows) picks the backdrop up through
 * `LocalGlassBackdrop.current`: [GlassSurface] and [GlassIconButton] do that by default, and for
 * [Modifier.glass] you pass `backdrop = LocalGlassBackdrop.current`.
 *
 * Rules: the chrome must be a **sibling after** the source, never inside it (a surface drawn inside the
 * source would sample itself; it is detected and drawn without blur, but it is a bug). Give the source an
 * opaque background inside [glassSource] (as above) so the recorded layer has no holes. A surface reads its
 * own position when the layout pass reports it, so a parent that moves it only through `graphicsLayer {}`
 * translation (not `offset {}` / layout) will show the blur one frame late.
 *
 * Text and icons on glass should use `Tape.Fg`; [GlassSurface] and [GlassIconButton] already provide it as
 * the content colour.
 *
 * Holds the recorded layer of the content that scrolls beneath the glass. Create it with
 * [rememberGlassBackdrop].
 */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    /** Root-space position of the source's top-left corner. State: glass surfaces redraw when it changes. */
    internal var origin: Offset by mutableStateOf(Offset.Unspecified)

    /** True once the source has recorded at least one frame into [layer]. */
    internal var ready: Boolean = false

    /** True while the source is recording; surfaces must not draw the layer then (cycle guard). */
    internal var recording: Boolean = false

    internal var sourceWidth: Float = 0f
    internal var sourceHeight: Float = 0f
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Put on the full-screen content that glass floats over. See [GlassBackdrop] for the wiring. */
fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier =
    this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            val w = ceil(size.width).toInt()
            val h = ceil(size.height).toInt()
            if (w <= 0 || h <= 0) {
                backdrop.ready = false
                drawContent()
            } else {
                backdrop.recording = true
                try {
                    backdrop.layer.record(this, layoutDirection, IntSize(w, h)) {
                        this@drawWithContent.drawContent()
                    }
                    backdrop.sourceWidth = size.width
                    backdrop.sourceHeight = size.height
                    backdrop.ready = true
                } finally {
                    backdrop.recording = false
                }
                drawLayer(backdrop.layer)
            }
        }

/** The app-level backdrop for floating chrome; null outside a [glassSource] tree (and in dialog windows). */
val LocalGlassBackdrop = compositionLocalOf<GlassBackdrop?> { null }

/**
 * How much of the screen's bottom edge the floating nav bar and mini player cover (system navigation-bar inset
 * included). [MixtapeApp] measures the chrome and provides it; every scrolling screen adds it (plus a little air)
 * as bottom content padding so its last row can scroll clear of the glass.
 */
val LocalChromeInset = compositionLocalOf { 0.dp }

/** How much the glass tints and blurs: Thin for small chips/lenses, Regular for bars, Thick for big panels. */
enum class GlassStrength(internal val blurDp: Float) {
    Thin(10f),
    Regular(16f),
    Thick(24f),
}

/**
 * Turns this element into a Liquid Glass surface (see [GlassBackdrop] for the full recipe). Apply it to the
 * element that defines the glass area (`size`/`fillMaxWidth` before it, `clip`/`clickable`/padding after it),
 * and put nothing behind it that should show through except the [backdrop].
 *
 * @param backdrop what to blur; null means no blur (use this inside Dialog/ModalBottomSheet windows).
 * Pass `LocalGlassBackdrop.current` for floating chrome.
 * @param elevation floating shadow; 0.dp draws none (use that for glass nested inside other glass).
 * @param interactive press feedback: scales to 0.97 with a spring and brightens the rim. It only observes
 * touches (it never consumes them), so combine it with `clickable`. Skipped when "Remove animations" is on.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(28.dp),
    backdrop: GlassBackdrop? = null,
    strength: GlassStrength = GlassStrength.Regular,
    elevation: Dp = 12.dp,
    interactive: Boolean = false,
): Modifier = composed {
    val dark = Tape.isDark
    val density = LocalDensity.current
    val still = rememberReduceMotion()
    val canBlur = backdrop != null && Build.VERSION.SDK_INT >= 31
    val blurPx = with(density) { strength.blurDp.dp.toPx() }
    val layer = rememberGraphicsLayer()
    val saturation = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.35f) }) }
    val blur = remember(canBlur, backdrop, layer, blurPx, saturation) {
        if (canBlur && backdrop != null) {
            GlassBlur(backdrop, layer, BlurEffect(blurPx, blurPx, TileMode.Clamp), saturation, blurPx)
        } else {
            null
        }
    }
    val tintBlur = glassTint(strength, dark, blurred = true)
    val tintFlat = glassTint(strength, dark, blurred = false)
    val spot = Color.Black.copy(alpha = if (dark) 0.55f else 0.28f)
    val ambient = Color.Black.copy(alpha = if (dark) 0.30f else 0.14f)

    val pos = remember { mutableStateOf(Offset.Unspecified) }
    val pressed = remember { mutableStateOf(false) }
    val down = interactive && pressed.value
    val scale = animateFloatAsState(
        targetValue = if (down) 0.97f else 1f,
        animationSpec = if (still) snap<Float>() else spring<Float>(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "glassScale",
    )
    val boost = animateFloatAsState(
        targetValue = if (down) 1f else 0f,
        animationSpec = if (still) snap<Float>() else spring<Float>(stiffness = Spring.StiffnessMedium),
        label = "glassRim",
    )

    Modifier
        .then(
            if (interactive) {
                Modifier.graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
            } else {
                Modifier
            }
        )
        .then(
            if (elevation > 0.dp) {
                Modifier.shadow(elevation = elevation, shape = shape, clip = false, ambientColor = ambient, spotColor = spot)
            } else {
                Modifier
            }
        )
        .onGloballyPositioned { pos.value = it.positionInRoot() }
        .drawWithContent { drawGlass(shape, dark, tintBlur, tintFlat, blur, pos.value, boost.value) }
        .then(
            if (interactive) {
                // Observe in the Initial pass so a clickable inside/outside that consumes the touch can't hide it.
                Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        pressed.value = true
                        try {
                            waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        } finally {
                            pressed.value = false
                        }
                    }
                }
            } else {
                Modifier
            }
        )
}

/**
 * Only the specular highlight and rim of the glass, drawn over the content. For Material containers that
 * bring their own fill (`containerColor = glassContainerColor()` on dialogs, sheets, menus), where blurring
 * is not possible.
 */
fun Modifier.glassRim(shape: Shape = RoundedCornerShape(28.dp)): Modifier =
    this.drawWithContent {
        drawContent()
        if (size.width >= 1f && size.height >= 1f) {
            val dark = Tape.isDark
            drawGlassSheen(shape.createOutline(size, layoutDirection, this), dark, 0.6f)
            drawGlassRim(shape, dark, 0f)
        }
    }

/** A glass panel: [Modifier.glass] on a Box, with `Tape.Fg` as the content colour. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    strength: GlassStrength = GlassStrength.Regular,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
    elevation: Dp = 12.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, backdrop, strength, elevation), contentAlignment = contentAlignment) {
        CompositionLocalProvider(LocalContentColor provides Tape.Fg) { content() }
    }
}

/**
 * A round glass button (back, close, share...). [contentDescription] is what TalkBack reads; [content] is
 * normally an `Icon` (it gets `Tape.Fg` by default). Pressing springs it in.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
    size: Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    val label = contentDescription
    Box(
        modifier
            .size(size)
            .glass(CircleShape, backdrop, GlassStrength.Thin, elevation = 8.dp, interactive = true)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides Tape.Fg, content = content)
    }
}

/**
 * Translucent fill for Material containers (dialogs, sheets, menus, cards) that live in their own window
 * and so cannot blur behind them. Nearly opaque so text stays readable; pair it with [glassRim]. Call it
 * from composition (or a draw lambda) so it follows the theme toggle.
 */
fun glassContainerColor(): Color =
    if (Tape.isDark) {
        lerp(Tape.Surface, Color.White, 0.06f).copy(alpha = 0.90f)
    } else {
        lerp(Color.White, Tape.Bg, 0.2f).copy(alpha = 0.92f)
    }

// ---------------------------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------------------------

/** Everything one glass surface needs to blur the backdrop; null when blur is unavailable. */
private class GlassBlur(
    val backdrop: GlassBackdrop,
    val layer: GraphicsLayer,
    val effect: RenderEffect,
    val filter: ColorFilter,
    val radiusPx: Float,
)

/**
 * Tint over the blur. Alphas are chosen so Tape.Fg text stays near 4.5:1 even over a light or busy cover:
 * without a blur behind, the fill is much more opaque.
 */
private fun glassTint(strength: GlassStrength, dark: Boolean, blurred: Boolean): Color {
    val alpha = when (strength) {
        GlassStrength.Thin -> if (blurred) (if (dark) 0.56f else 0.54f) else (if (dark) 0.78f else 0.76f)
        GlassStrength.Regular -> if (blurred) (if (dark) 0.64f else 0.62f) else 0.84f
        GlassStrength.Thick -> if (blurred) (if (dark) 0.72f else 0.70f) else 0.90f
    }
    val base = if (dark) lerp(Tape.Surface, Color.White, 0.06f) else lerp(Color.White, Tape.Bg, 0.2f)
    return base.copy(alpha = alpha)
}

private fun outlinePath(outline: Outline): Path = Path().apply {
    when (outline) {
        is Outline.Rectangle -> addRect(outline.rect)
        is Outline.Rounded -> addRoundRect(outline.roundRect)
        is Outline.Generic -> addPath(outline.path)
    }
}

private fun ContentDrawScope.drawGlass(
    shape: Shape,
    dark: Boolean,
    tintBlur: Color,
    tintFlat: Color,
    blur: GlassBlur?,
    pos: Offset,
    boost: Float,
) {
    if (size.width < 1f || size.height < 1f) {
        drawContent()
        return
    }
    val outline = shape.createOutline(size, layoutDirection, this)
    val blurred = blur != null && drawGlassBackdrop(outline, blur, pos)
    drawOutline(outline, if (blurred) tintBlur else tintFlat)
    drawGlassSheen(outline, dark, 1f)
    if (boost > 0f) {
        drawOutline(outline, if (dark) Color.White.copy(alpha = 0.10f * boost) else Color.Black.copy(alpha = 0.05f * boost))
    }
    drawContent()
    drawGlassRim(shape, dark, boost)
}

/**
 * Draws the blurred backdrop clipped to [outline]. Returns false (and draws nothing) when the source has
 * not recorded yet, is recording right now, or a position is not known yet.
 */
private fun ContentDrawScope.drawGlassBackdrop(outline: Outline, blur: GlassBlur, pos: Offset): Boolean {
    val src = blur.backdrop
    val origin = src.origin
    if (!src.ready || src.recording || !pos.isSpecified || !origin.isSpecified) return false
    val w = size.width
    val h = size.height
    val rel = pos - origin // top-left of this surface in the source's coordinates
    val pad = ceil(blur.radiusPx * 2f)
    // Pad by the blur reach on every side, but never past the source: where the layer ends at the source
    // edge, BlurEffect's Clamp tiling repeats the edge pixels, which is what the blur should see there.
    val padL = pad.coerceAtMost(rel.x).coerceAtLeast(0f)
    val padT = pad.coerceAtMost(rel.y).coerceAtLeast(0f)
    val padR = pad.coerceAtMost(src.sourceWidth - rel.x - w).coerceAtLeast(0f)
    val padB = pad.coerceAtMost(src.sourceHeight - rel.y - h).coerceAtLeast(0f)
    val lw = ceil(w + padL + padR).toInt()
    val lh = ceil(h + padT + padB).toInt()
    if (lw <= 0 || lh <= 0) return false
    val layer = blur.layer
    layer.renderEffect = blur.effect
    layer.colorFilter = blur.filter
    layer.record(this, layoutDirection, IntSize(lw, lh)) {
        translate(left = padL - rel.x, top = padT - rel.y) { drawLayer(src.layer) }
    }
    clipPath(outlinePath(outline)) {
        translate(left = -padL, top = -padT) { drawLayer(layer) }
    }
    return true
}

/** White sheen fading out over the top 45 percent of the surface. */
private fun DrawScope.drawGlassSheen(outline: Outline, dark: Boolean, scale: Float) {
    val top = (if (dark) 0.16f else 0.22f) * scale
    drawOutline(
        outline,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = top),
            1f to Color.White.copy(alpha = 0f),
            startY = 0f,
            endY = size.height * 0.45f,
        ),
    )
}

/** 1dp specular rim: bright top-left, faint through the middle, medium bottom-right. */
private fun DrawScope.drawGlassRim(shape: Shape, dark: Boolean, boost: Float) {
    val stroke = 1.dp.toPx()
    if (size.minDimension <= stroke * 2f) return
    inset(stroke / 2f) {
        val outline = shape.createOutline(size, layoutDirection, this)
        if (!dark) {
            // white on light glass is nearly invisible; a hairline of shade gives the edge something to catch
            drawOutline(outline, Color.Black.copy(alpha = 0.07f), style = Stroke(stroke))
        }
        val k = 1f + 0.6f * boost
        val aTopLeft = (if (dark) 0.50f else 0.75f) * k
        val aMiddle = (if (dark) 0.05f else 0.08f) * k
        val aBottomRight = (if (dark) 0.28f else 0.45f) * k
        val brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = aTopLeft.coerceIn(0f, 1f)),
            0.5f to Color.White.copy(alpha = aMiddle.coerceIn(0f, 1f)),
            1f to Color.White.copy(alpha = aBottomRight.coerceIn(0f, 1f)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        drawOutline(outline, brush = brush, style = Stroke(stroke))
    }
}

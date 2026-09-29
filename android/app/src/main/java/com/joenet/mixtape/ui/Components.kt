package com.joenet.mixtape.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.joenet.mixtape.PlayerUi
import com.joenet.mixtape.R
import com.joenet.mixtape.Song
import com.joenet.mixtape.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt

/** Cover art thumbnails from MediaStore (it reads the picture the importer embeds in each MP3). */
object ArtCache {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val missing = Collections.synchronizedSet(HashSet<String>())

    fun peek(uri: Uri, px: Int): ImageBitmap? = cache.get("$uri@$px")

    suspend fun load(context: Context, uri: Uri, px: Int): ImageBitmap? {
        val key = "$uri@$px"
        cache.get(key)?.let { return it }
        if (key in missing) return null
        val bmp = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(px, px), null).asImageBitmap() }.getOrNull()
        }
        if (bmp != null) cache.put(key, bmp) else missing += key
        return bmp
    }
}

/**
 * The cover's muted color, used faintly behind Now Playing like light falling on the deck.
 * (Muted, not vibrant: a vibrant wash is the stock streaming look and fights the tape orange.)
 */
@Composable
fun rememberArtTint(uri: Uri?): Color {
    val context = LocalContext.current
    // keyed on isDark so the no-art fallback follows the theme toggle
    val tint by produceState(Tape.SurfaceHigh, uri, Tape.isDark) {
        val img = uri?.let { ArtCache.load(context, it, 144) }
        if (img == null) {
            value = Tape.SurfaceHigh
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            runCatching {
                var bmp = img.asAndroidBitmap()
                if (bmp.config == Bitmap.Config.HARDWARE) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
                val palette = Palette.from(bmp).generate()
                val swatch = palette.mutedSwatch ?: palette.darkMutedSwatch ?: palette.dominantSwatch
                swatch?.let { Color(it.rgb) }
            }.getOrNull() ?: Tape.SurfaceHigh
        }
    }
    return tint
}

@Composable
fun SongArt(
    uri: Uri?,
    modifier: Modifier = Modifier,
    px: Int = 144,
    corner: Dp = 6.dp,
    seed: String = "",
    artworkData: ByteArray? = null,
) {
    val context = LocalContext.current
    val art by produceState(uri?.let { ArtCache.peek(it, px) }, uri, px, artworkData) {
        value = uri?.let { ArtCache.peek(it, px) }
        if (value == null && uri != null) value = ArtCache.load(context, uri, px)
        if (value == null && artworkData != null) {
            value = withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    val bmp = art
    if (bmp != null) {
        Image(
            bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(corner)),
        )
    } else {
        // No cover: a little tape in the playlist's label color
        val c = Tape.labelColor(seed)
        Box(
            modifier
                .clip(RoundedCornerShape(corner))
                .background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.55f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_cassette),
                contentDescription = null,
                tint = Tape.OnAccent.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxSize(0.55f),
            )
        }
    }
}

/** A round badge with an artist's initial, in a stable label colour. */
@Composable
fun ArtistAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    val c = Tape.labelColor(name)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.6f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            fontFamily = Mix,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.45f).sp,
            color = Tape.OnAccent.copy(alpha = 0.8f),
        )
    }
}

/** Three bouncing bars next to the song that's playing; frozen while paused. */
@Composable
fun EqualizerBars(animating: Boolean, modifier: Modifier = Modifier, color: Color = Tape.Accent) {
    val t = rememberInfiniteTransition(label = "eq")
    val bars = listOf(430, 330, 520).map { ms ->
        t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar")
    }
    val rest = floatArrayOf(0.45f, 0.8f, 0.3f)
    val live = animating && !rememberReduceMotion()
    Canvas(modifier.size(18.dp, 16.dp)) {
        val bw = size.width / 5f
        bars.forEachIndexed { i, anim ->
            val hf = if (live) anim.value else rest[i]
            val bh = size.height * hf
            drawRoundRect(color, topLeft = Offset(i * 2 * bw, size.height - bh), size = androidx.compose.ui.geometry.Size(bw, bh), cornerRadius = CornerRadius(bw / 2))
        }
    }
}

/**
 * Track-listing row: optional "01" number, cover, title/artist, mono duration (or bars while
 * current). Long-press opens the song's actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    current: Boolean,
    playing: Boolean,
    number: Int? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = if (onLongClick != null) "More actions" else null,
                onLongClick = onLongClick?.let { l ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        l()
                    }
                },
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text(
                "%02d".format(number),
                fontFamily = Mono,
                fontSize = 13.sp,
                color = if (current) Tape.Accent else Tape.FgMuted,
                modifier = Modifier.width(32.dp),
            )
        }
        SongArt(song.uri, Modifier.size(48.dp), seed = song.folder)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (current) Tape.Accent else Tape.Fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = Tape.FgMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        when {
            trailing != null -> trailing()
            current -> EqualizerBars(animating = playing)
            else -> Text(formatDuration(song.durationMs), fontFamily = Mono, fontSize = 12.sp, color = Tape.FgMuted)
        }
    }
}

/** Section title for shelves and result groups. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Tape.Fg, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** A horizontal row of cards, as in the streaming apps' home shelves. */
@Composable
fun <T> Shelf(items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items.size, key = { key(items[it]) }) { content(items[it]) }
    }
}

/** Current position, ticking 4x a second while playing. */
@Composable
fun rememberPlaybackPosition(player: PlayerUi): Long {
    var pos by remember { mutableLongStateOf(player.position()) }
    LaunchedEffect(player.controller, player.isPlaying, player.tick) {
        pos = player.position()
        while (player.isPlaying) {
            delay(250)
            pos = player.position()
        }
    }
    return pos
}

/**
 * The mini player: a floating glass capsule above the tab bar. Tap or drag up to open Now Playing (it
 * follows the finger); swipe left / right for next / previous. A thin line along its bottom shows the
 * progress through the song. Picks the blur backdrop up from [LocalGlassBackdrop].
 */
@Composable
fun MiniPlayer(
    player: PlayerUi,
    onOpen: () -> Unit,
    onSheetDrag: (dy: Float) -> Unit,
    onSheetRelease: (velocityY: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!player.hasSong) return
    val md = player.metadata
    val pos = rememberPlaybackPosition(player)
    val fraction = if (player.durationMs > 0) (pos.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f
    val scope = rememberCoroutineScope()
    val slide = remember { Animatable(0f) }
    val drag by rememberUpdatedState(onSheetDrag)
    val release by rememberUpdatedState(onSheetRelease)
    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier
            .fillMaxWidth()
            .glass(shape, LocalGlassBackdrop.current, GlassStrength.Regular)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape) // keeps the press ripple inside the capsule
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Next song") { player.next(); true },
                        CustomAccessibilityAction("Previous song") { player.previous(); true },
                    )
                }
                .clickable(onClickLabel = "Open player", onClick = onOpen)
                .pointerInput(Unit) {
                    var axis = 0 // 0 undecided, 1 horizontal (skip), 2 vertical (open)
                    var dx = 0f
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = {
                            axis = 0
                            dx = 0f
                            tracker.resetTracking()
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (axis == 0) axis = if (abs(amount.x) > abs(amount.y)) 1 else 2
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (axis == 1) {
                                dx += amount.x
                                scope.launch { slide.snapTo(dx) }
                            } else {
                                drag(amount.y)
                            }
                        },
                        onDragEnd = {
                            if (axis == 1) {
                                val threshold = size.width * 0.22f
                                when {
                                    dx < -threshold -> player.next()
                                    dx > threshold -> player.previous()
                                }
                                scope.launch { slide.animateTo(0f, tween(220)) }
                            } else if (axis == 2) {
                                release(tracker.calculateVelocity().y)
                            }
                        },
                        onDragCancel = {
                            scope.launch { slide.animateTo(0f, tween(220)) }
                            if (axis == 2) release(0f)
                        },
                    )
                }
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .offset { IntOffset(slide.value.roundToInt(), 0) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongArt(
                    player.mediaId?.let(Uri::parse),
                    Modifier.size(44.dp),
                    corner = 12.dp,
                    seed = player.currentFolder,
                    artworkData = md.artworkData,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        md.title?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tape.Fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        md.artist?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tape.FgMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = player::playPause) {
                Icon(
                    if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (player.isPlaying) "Pause" else "Play",
                    tint = Tape.Fg,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = { player.next() }) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Tape.Fg)
            }
        }
        // progress: a thin rounded line inside the capsule's bottom edge (it sits in the row's 8dp bottom padding)
        MiniProgress(
            fraction,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 26.dp, vertical = 3.dp),
        )
    }
}

/** The mini player's progress line: Hairline track, Accent for the part already played. */
@Composable
private fun MiniProgress(fraction: Float, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(3.dp)
    ) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(Tape.Hairline, cornerRadius = r)
        val played = size.width * fraction.coerceIn(0f, 1f)
        if (played > 0f) {
            drawRoundRect(Tape.Accent, size = androidx.compose.ui.geometry.Size(played, size.height), cornerRadius = r)
        }
    }
}

/**
 * A barely-there brushed-plastic texture for the deck's background: horizontal streaks of noise,
 * generated once and tiled.
 */
@Composable
fun rememberBrushedPlastic(): Brush = remember {
    val w = 256
    val h = 64
    val rnd = java.util.Random(7)
    val px = IntArray(w * h)
    for (y in 0 until h) {
        val row = rnd.nextFloat()                   // each row gets its own sheen...
        for (x in 0 until w) {
            val v = 0.6f * row + 0.4f * rnd.nextFloat() // ...plus fine grain, so it reads as brushed
            val a = (v * 14).toInt()                 // alpha 0..14 of 255: almost invisible
            px[y * w + x] = (a shl 24) or 0xFFFFFF
        }
    }
    val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
}

/**
 * The mini player's progress: a thin strip of brown tape. The played part is orange (it's live),
 * and faint splice marks drift left to right while playing, like tape running between the reels.
 */
@Composable
fun TapeStrip(fraction: Float, moving: Boolean, modifier: Modifier = Modifier) {
    val animate = moving && !rememberReduceMotion()
    val phase = if (animate) {
        val t = rememberInfiniteTransition(label = "tape")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "phase")
    } else null
    Canvas(
        modifier
            .fillMaxWidth()
            .height(3.dp)
    ) {
        drawRect(Tape.TapeBrown)
        val gap = 14.dp.toPx()
        val shift = (phase?.value ?: 0f) * gap
        var x = -gap + shift
        while (x < size.width) {
            drawRect(Tape.TapeEdge, topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
            x += gap
        }
        drawRect(Tape.Orange, size = androidx.compose.ui.geometry.Size(size.width * fraction.coerceIn(0f, 1f), size.height))
    }
}

/** Thin tape-colored seek bar: tap to jump, drag to scrub ([onScrub] reports the preview, null when done). */
@Composable
fun TapeSeekBar(
    fraction: Float,
    enabled: Boolean,
    onScrub: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    val scrub by rememberUpdatedState(onScrub)
    val seek by rememberUpdatedState(onSeek)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var f = 0f
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        f = (o.x / size.width).coerceIn(0f, 1f)
                        scrub(f)
                    },
                    onDragEnd = {
                        dragging = false
                        seek(f)
                        scrub(null)
                    },
                    onDragCancel = {
                        dragging = false
                        scrub(null)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        f = (change.position.x / size.width).coerceIn(0f, 1f)
                        scrub(f)
                    },
                )
            }
    ) {
        val y = size.height / 2
        val stroke = 3.dp.toPx()
        val x = size.width * fraction.coerceIn(0f, 1f)
        // The track is FgMuted at low alpha rather than Hairline: Hairline is too faint on the light paper
        // (and on Now Playing's tinted wash) for a control you have to find with your thumb.
        drawLine(Tape.FgMuted.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        drawLine(Tape.Accent, Offset(0f, y), Offset(x, y), stroke, StrokeCap.Round)
        drawCircle(Tape.Accent, if (dragging) 9.dp.toPx() else 6.dp.toPx(), Offset(x, y))
    }
}

/**
 * Pill toggle used for section switches (Tapes / Artists / Songs). Selected is a Fg-filled pill; the
 * others are thin glass chips (no blur: they sit on the page, not floating over content).
 */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = if (selected) {
            Modifier
        } else {
            Modifier.glass(CircleShape, backdrop = null, strength = GlassStrength.Thin, elevation = 0.dp)
        },
        shape = CircleShape,
        color = if (selected) Tape.Fg else Color.Transparent,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Tape.Bg else Tape.Fg,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

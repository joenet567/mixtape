package com.joenet.mixtape.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.joenet.mixtape.PlayerUi
import com.joenet.mixtape.R
import com.joenet.mixtape.Song
import com.joenet.mixtape.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Collections

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

/** A color pulled from the cover (for the now-playing backdrop); falls back to tape orange. */
@Composable
fun rememberArtTint(uri: Uri?): Color {
    val context = LocalContext.current
    val tint by produceState(Tape.Orange, uri) {
        val img = uri?.let { ArtCache.load(context, it, 144) }
        if (img == null) {
            value = Tape.Orange
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            runCatching {
                var bmp = img.asAndroidBitmap()
                if (bmp.config == Bitmap.Config.HARDWARE) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
                val palette = Palette.from(bmp).generate()
                val swatch = palette.vibrantSwatch ?: palette.dominantSwatch
                swatch?.let { Color(it.rgb) }
            }.getOrNull() ?: Tape.Orange
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
                tint = Tape.Ink.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxSize(0.55f),
            )
        }
    }
}

/** Three bouncing bars next to the song that's playing; frozen while paused. */
@Composable
fun EqualizerBars(animating: Boolean, modifier: Modifier = Modifier, color: Color = Tape.Orange) {
    val t = rememberInfiniteTransition(label = "eq")
    val bars = listOf(430, 330, 520).map { ms ->
        t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar")
    }
    val rest = floatArrayOf(0.45f, 0.8f, 0.3f)
    Canvas(modifier.size(18.dp, 16.dp)) {
        val bw = size.width / 5f
        bars.forEachIndexed { i, anim ->
            val hf = if (animating) anim.value else rest[i]
            val bh = size.height * hf
            drawRoundRect(color, topLeft = Offset(i * 2 * bw, size.height - bh), size = androidx.compose.ui.geometry.Size(bw, bh), cornerRadius = CornerRadius(bw / 2))
        }
    }
}

/** Track-listing row: optional "01" number, cover, title/artist, mono duration (or bars while current). */
@Composable
fun SongRow(song: Song, current: Boolean, playing: Boolean, number: Int? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text(
                "%02d".format(number),
                fontFamily = Mono,
                fontSize = 13.sp,
                color = if (current) Tape.Orange else Tape.Dust,
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
                color = if (current) Tape.Orange else Tape.Cream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = Tape.Dust,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (current) {
            EqualizerBars(animating = playing)
        } else {
            Text(formatDuration(song.durationMs), fontFamily = Mono, fontSize = 12.sp, color = Tape.Dust)
        }
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

@Composable
fun MiniPlayer(player: PlayerUi, onOpen: () -> Unit) {
    if (!player.hasSong) return
    val md = player.metadata
    val pos = rememberPlaybackPosition(player)
    val fraction = if (player.durationMs > 0) (pos.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f
    Surface(color = Tape.Deck, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Tape.Line)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(Tape.Orange)
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongArt(
                    player.mediaId?.let(Uri::parse),
                    Modifier.size(44.dp),
                    seed = md.extras?.getString(Song.EXTRA_FOLDER).orEmpty(),
                    artworkData = md.artworkData,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        md.title?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tape.Cream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        md.artist?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tape.Dust,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledIconButton(
                    onClick = player::playPause,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Tape.Orange, contentColor = Tape.Ink),
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (player.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = { player.next() }) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Tape.Cream)
                }
            }
        }
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
        drawLine(Tape.Line, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        drawLine(Tape.Orange, Offset(0f, y), Offset(x, y), stroke, StrokeCap.Round)
        drawCircle(Tape.Orange, if (dragging) 9.dp.toPx() else 6.dp.toPx(), Offset(x, y))
    }
}

/** Pill toggle used for the Songs / Playlists switch. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) Tape.Cream else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, Tape.Line),
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Tape.Ink else Tape.Dust,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

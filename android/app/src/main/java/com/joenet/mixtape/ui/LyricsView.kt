package com.joenet.mixtape.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joenet.mixtape.Lyrics
import kotlinx.coroutines.delay

/**
 * Lyrics scrolling under the cassette: the line being sung in cream, the rest in dust, and the
 * list follows the song (until you scroll it yourself; it picks up again 3 s later). Tap a line to
 * play from there. Unsynced lyrics just scroll as text.
 */
@Composable
fun LyricsView(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val current = lyrics.indexAt(positionMs + 250) // a little early reads better than a little late
    val dragged by state.interactionSource.collectIsDraggedAsState()
    var follow by remember { mutableStateOf(true) }
    val still = rememberReduceMotion()

    LaunchedEffect(dragged) {
        if (dragged) {
            follow = false
        } else if (!follow) {
            delay(3_000)
            follow = true
        }
    }
    LaunchedEffect(current, follow, lyrics) {
        if (!follow || !lyrics.synced) return@LaunchedEffect
        val target = current.coerceAtLeast(0)
        if (still) state.scrollToItem(target) else state.animateScrollToItem(target)
    }

    BoxWithConstraints(modifier) {
        // The current line sits a third of the way down; the edges fade out.
        val lead = maxHeight / 3
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(top = if (lyrics.synced) lead else 12.dp, bottom = maxHeight - lead),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.14f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            itemsIndexed(lyrics.lines) { i, line ->
                val color by animateColorAsState(
                    when {
                        !lyrics.synced -> Tape.Cream.copy(alpha = 0.85f)
                        i == current -> Tape.Cream
                        i < current -> Tape.Dust.copy(alpha = 0.55f)
                        else -> Tape.Dust
                    },
                    tween(if (still) 0 else 250),
                    label = "lyric",
                )
                Text(
                    line.text.ifBlank { "♪" },
                    fontFamily = Barlow,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (lyrics.synced) 24.sp else 20.sp,
                    lineHeight = if (lyrics.synced) 29.sp else 26.sp,
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (lyrics.synced) Modifier.clickable(onClickLabel = "Play from this line") { onSeek(line.timeMs) }
                            else Modifier
                        )
                        .padding(vertical = if (lyrics.synced) 6.dp else 2.dp),
                )
            }
        }
    }
}

package com.joenet.mixtape.ui

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.joenet.mixtape.PlayerUi
import com.joenet.mixtape.R
import com.joenet.mixtape.Song
import com.joenet.mixtape.formatDuration
import com.joenet.mixtape.songCount

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(player: PlayerUi, onClose: () -> Unit) {
    var showQueue by remember { mutableStateOf(false) }
    var showCover by rememberSaveable { mutableStateOf(false) }
    val md = player.metadata
    val uri = player.mediaId?.let(Uri::parse)
    val folder = md.extras?.getString(Song.EXTRA_FOLDER) ?: md.albumTitle?.toString().orEmpty()
    val pos = rememberPlaybackPosition(player)
    val duration = player.durationMs
    var scrub by remember { mutableStateOf<Float?>(null) }
    val fraction = scrub ?: if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownPos = (fraction * duration).toLong()
    val tint by animateColorAsState(rememberArtTint(uri), tween(700), label = "tint")

    Box(
        Modifier
            .fillMaxSize()
            .background(Tape.Ink)
            .background(Brush.verticalGradient(0f to tint.copy(alpha = 0.45f), 0.7f to Color.Transparent))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player", tint = Tape.Cream)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, color = Tape.Dust)
                    Text(
                        folder,
                        fontFamily = Marker,
                        fontSize = 17.sp,
                        color = Tape.Cream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { showCover = !showCover }) {
                    if (showCover) {
                        Icon(painterResource(R.drawable.ic_cassette), contentDescription = "Show cassette", tint = Tape.Cream)
                    } else {
                        Icon(Icons.Rounded.Album, contentDescription = "Show cover", tint = Tape.Cream)
                    }
                }
                IconButton(onClick = { showQueue = true }, enabled = player.hasSong) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Queue", tint = Tape.Cream)
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Hero: the tape turning (default) or the cover art; tap to flip.
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .aspectRatio(1f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            showCover = !showCover
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(showCover, animationSpec = tween(350), label = "hero") { cover ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (cover) {
                                SongArt(uri, Modifier.fillMaxSize(), px = 1024, corner = 14.dp, seed = folder, artworkData = md.artworkData)
                            } else {
                                Cassette(
                                    label = folder.ifEmpty { "Mixtape" },
                                    labelColor = Tape.labelColor(folder),
                                    modifier = Modifier.fillMaxWidth(),
                                    progress = fraction,
                                    spinning = player.isPlaying,
                                    art = uri,
                                    footLeft = "SIDE A",
                                    footRight = formatDuration(duration),
                                )
                            }
                        }
                    }
                }

                Column(Modifier.fillMaxWidth()) {
                    Text(
                        md.title?.toString() ?: "Nothing playing",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Tape.Cream,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        md.artist?.toString().orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Tape.Orange,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    TapeSeekBar(
                        fraction = fraction,
                        enabled = duration > 0,
                        onScrub = { scrub = it },
                        onSeek = { player.seekTo((it * duration).toLong()) },
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatDuration(shownPos), fontFamily = Mono, fontSize = 13.sp, color = Tape.Orange)
                        Spacer(Modifier.weight(1f))
                        Text("-" + formatDuration(duration - shownPos), fontFamily = Mono, fontSize = 13.sp, color = Tape.Dust)
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeButton(Icons.Rounded.Shuffle, "Shuffle", on = player.shuffle, onClick = player::toggleShuffle)
                    IconButton(onClick = { player.previous() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = Tape.Cream, modifier = Modifier.size(38.dp))
                    }
                    FilledIconButton(
                        onClick = player::playPause,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Tape.Orange, contentColor = Tape.Ink),
                        modifier = Modifier.size(78.dp),
                    ) {
                        Icon(
                            if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (player.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(42.dp),
                        )
                    }
                    IconButton(onClick = { player.next() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Tape.Cream, modifier = Modifier.size(38.dp))
                    }
                    ModeButton(
                        if (player.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Repeat",
                        on = player.repeatMode != Player.REPEAT_MODE_OFF,
                        onClick = player::cycleRepeat,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showQueue) QueueSheet(player, onDismiss = { showQueue = false })
}

/** Shuffle / repeat toggle: orange with a dot underneath when on, like a lit deck button. */
@Composable
private fun ModeButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = if (on) Tape.Orange else Tape.Dust)
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .size(4.dp)
                    .background(if (on) Tape.Orange else Color.Transparent, CircleShape)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(player: PlayerUi, onDismiss: () -> Unit) {
    val queue = remember(player.tick) { player.queue() }
    val currentIndex = remember(player.tick) { player.controller?.currentMediaItemIndex ?: -1 }
    val currentRow = queue.indexOfFirst { it.first == currentIndex }.coerceAtLeast(0)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tape.Deck) {
        Text(
            "QUEUE · ${songCount(queue.size).uppercase()}",
            style = MaterialTheme.typography.labelLarge,
            color = Tape.Dust,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = (currentRow - 2).coerceAtLeast(0))) {
            items(queue, key = { it.first }) { (index, item) ->
                val current = index == currentIndex
                val folder = item.mediaMetadata.extras?.getString(Song.EXTRA_FOLDER).orEmpty()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { player.jumpTo(index) }
                        .padding(horizontal = 20.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SongArt(Uri.parse(item.mediaId), Modifier.size(42.dp), seed = folder)
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 14.dp)
                    ) {
                        Text(
                            item.mediaMetadata.title?.toString().orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current) Tape.Orange else Tape.Cream,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.mediaMetadata.artist?.toString().orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tape.Dust,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                        )
                    }
                    if (current) EqualizerBars(animating = player.isPlaying)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

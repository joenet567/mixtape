package com.joenet.mixtape.ui

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joenet.mixtape.Lyrics
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.PlaybackService
import com.joenet.mixtape.QueueEntry
import com.joenet.mixtape.Route
import com.joenet.mixtape.TapeRef
import com.joenet.mixtape.folder
import com.joenet.mixtape.formatDuration
import com.joenet.mixtape.isQueued
import com.joenet.mixtape.qid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    vm: MainViewModel,
    onClose: () -> Unit,
    onSheetDrag: (Float) -> Unit,
    onSheetRelease: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = vm.player
    var showQueue by remember { mutableStateOf(false) }
    var flipped by rememberSaveable { mutableStateOf(false) }
    val md = player.metadata
    val uri = player.mediaId?.let(Uri::parse)
    val song = vm.songFor(player.currentKey)
    val folder = player.currentFolder.ifEmpty { md.albumTitle?.toString().orEmpty() }
    val pos = rememberPlaybackPosition(player)
    val duration = player.durationMs
    var scrub by remember { mutableStateOf<Float?>(null) }
    val fraction = scrub ?: if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownPos = (fraction * duration).toLong()
    val tint by animateColorAsState(rememberArtTint(uri), tween(700), label = "tint")
    val plastic = rememberBrushedPlastic()
    val drag by rememberUpdatedState(onSheetDrag)
    val release by rememberUpdatedState(onSheetRelease)
    val lyrics by produceState<Lyrics?>(null, player.currentKey) { value = vm.lyricsFor(player.currentKey) }
    val compact = vm.showLyrics && lyrics != null
    val tapeWidth by animateFloatAsState(if (compact) 0.52f else 1f, tween(if (rememberReduceMotion()) 0 else 300), label = "tape")
    var showSleep by remember { mutableStateOf(false) }

    // Lyrics scroll on their own; pulling well past the first line folds the player away. (The sheet
    // doesn't follow the finger here: the list sits inside the layer that would move, so its drag and
    // fling readings shrink as the sheet slides. The list's own stretch shows the pull instead.)
    val close by rememberUpdatedState(onClose)
    val pullToClose = with(LocalDensity.current) { 96.dp.toPx() }
    val sheetScroll = remember(pullToClose) {
        object : NestedScrollConnection {
            var pulled = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0) pulled += available.y
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulled > pullToClose) close()
                pulled = 0f
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Tape.Ink)
            .background(plastic)
            .background(Brush.verticalGradient(0f to tint.copy(alpha = 0.2f), 0.75f to Color.Transparent))
            // drag down anywhere to fold the player back into the mini player
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        drag(dy)
                    },
                    onDragEnd = { release(tracker.calculateVelocity().y) },
                    onDragCancel = { release(0f) },
                )
            }
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
                if (lyrics != null) {
                    IconButton(onClick = { vm.updateShowLyrics(!vm.showLyrics) }) {
                        Icon(
                            Icons.Rounded.Lyrics,
                            contentDescription = if (vm.showLyrics) "Hide lyrics" else "Show lyrics",
                            tint = if (vm.showLyrics) Tape.Cream else Tape.Dust,
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp)) // keeps the tape name centred
                }
                Text(
                    folder,
                    fontFamily = Marker,
                    fontSize = 26.sp,
                    color = Tape.Cream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = song != null, onClickLabel = "Open this tape") {
                            song?.let { vm.open(Route.Tape(TapeRef.Folder(it.tapeKey))) }
                        }
                        .semantics { contentDescription = "Playing from $folder" },
                )
                if (player.sleepOn) {
                    SleepCounter(player.sleepAt, player.sleepEndOfSong) { showSleep = true }
                } else {
                    IconButton(onClick = { showSleep = true }) {
                        Icon(Icons.Rounded.Bedtime, contentDescription = "Sleep timer", tint = Tape.Dust)
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
                SwipeableTape(
                    onNext = { player.controller?.seekToNextMediaItem() },
                    onPrevious = { player.controller?.seekToPreviousMediaItem() },
                ) {
                    // Hero: the tape in its own shape. Tap to turn it over: Side B is the cover art.
                    FlipTape(
                        flipped = flipped,
                        modifier = Modifier
                            .fillMaxWidth(tapeWidth)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = if (flipped) "Show side A" else "Turn the tape over to see the cover",
                            ) { flipped = !flipped },
                        front = {
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
                        },
                        back = {
                            TapeSideB(
                                title = md.title?.toString().orEmpty(),
                                artist = md.artist?.toString().orEmpty(),
                                art = uri,
                                labelColor = Tape.labelColor(folder),
                                modifier = Modifier.fillMaxWidth(),
                                artworkData = md.artworkData,
                            )
                        },
                    )
                }

                lyrics?.takeIf { compact }?.let {
                    LyricsView(
                        it,
                        positionMs = pos,
                        onSeek = { ms -> player.seekTo(ms) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .nestedScroll(sheetScroll)
                            .padding(vertical = 8.dp),
                    )
                }

                Column(Modifier.fillMaxWidth()) {
                    val still = rememberReduceMotion()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                md.title?.toString() ?: "Nothing playing",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Tape.Cream,
                                maxLines = 1,
                                overflow = if (still) TextOverflow.Ellipsis else TextOverflow.Clip,
                                modifier = if (still) Modifier else Modifier.basicMarquee(),
                            )
                            Text(
                                md.artist?.toString().orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                color = Tape.Dust,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(enabled = song != null, onClickLabel = "Open artist") {
                                    song?.artists?.firstOrNull()?.let { vm.open(Route.Artist(it)) }
                                },
                            )
                        }
                        if (song != null) {
                            val liked = vm.isLiked(song)
                            IconButton(onClick = { vm.toggleLike(song) }) {
                                Icon(
                                    if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = if (liked) "Remove from Liked songs" else "Like",
                                    tint = if (liked) Tape.Cream else Tape.Dust,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    TapeSeekBar(
                        fraction = fraction,
                        enabled = duration > 0,
                        onScrub = { scrub = it },
                        onSeek = { player.seekTo((it * duration).toLong()) },
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatDuration(shownPos), fontFamily = Mono, fontSize = 13.sp, color = Tape.Cream)
                        Spacer(Modifier.weight(1f))
                        Text("-" + formatDuration(duration - shownPos), fontFamily = Mono, fontSize = 13.sp, color = Tape.Dust)
                    }
                }

                DeckKeys(
                    playing = player.isPlaying,
                    shuffle = player.shuffle,
                    repeatMode = player.repeatMode,
                    onPlayPause = player::playPause,
                    onPrevious = { player.previous() },
                    onNext = { player.next() },
                    onScrub = { forward -> player.scrub(forward) },
                    onShuffle = player::toggleShuffle,
                    onRepeat = player::cycleRepeat,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showQueue) QueueSheet(vm, onDismiss = { showQueue = false })
    if (showSleep) SleepSheet(vm, onDismiss = { showSleep = false })
}

/** The sleep timer as a deck's tape counter: minutes and seconds left, or END for "end of this song". */
@Composable
private fun SleepCounter(sleepAt: Long, endOfSong: Boolean, onClick: () -> Unit) {
    val now by produceState(System.currentTimeMillis(), sleepAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000 - value % 1_000)
        }
    }
    val left = ((sleepAt - now + 999) / 1000).coerceAtLeast(0)
    val shown = if (endOfSong) "END" else "%02d:%02d".format(left / 60, left % 60)
    Row(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClickLabel = "Change the sleep timer", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = if (endOfSong) "Sleep timer: stops after this song" else "Sleep timer: ${left / 60} minutes ${left % 60} seconds left"
            }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = Tape.Cream, modifier = Modifier.size(16.dp))
        Row(
            Modifier
                .padding(start = 5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Tape.Ink)
                .border(1.dp, Tape.Line, RoundedCornerShape(3.dp))
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            shown.forEach { ch ->
                Text(
                    ch.toString(),
                    fontFamily = Mono,
                    fontSize = 13.sp,
                    color = Tape.Cream,
                    textAlign = TextAlign.Center,
                    modifier = if (ch == ':') Modifier else Modifier
                        .background(Tape.DeckHigh, RoundedCornerShape(2.dp))
                        .width(10.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val player = vm.player
    val set = { minutes: Int, note: String ->
        player.setSleepTimer(minutes)
        vm.notify(note)
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tape.Deck) {
        Text(
            "Sleep timer",
            style = MaterialTheme.typography.titleLarge,
            color = Tape.Cream,
            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
        )
        Text(
            "The music fades out over the last few seconds, then stops.",
            style = MaterialTheme.typography.bodyMedium,
            color = Tape.Dust,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        for (m in listOf(15, 30, 45, 60)) {
            val label = if (m == 60) "1 hour" else "$m minutes"
            SleepOption(label) { set(m, "Stopping in $label") }
        }
        SleepOption("End of this song") { set(PlaybackService.SLEEP_END_OF_SONG, "Stopping after this song") }
        if (player.sleepOn) SleepOption("Turn off the timer", Tape.Dust) { set(0, "Sleep timer off") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SleepOption(label: String, color: Color = Tape.Cream, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

/**
 * Swipe the cassette left for the next song, right for the previous one: it slides out, the next
 * tape slides in from the other side.
 */
@Composable
private fun SwipeableTape(onNext: () -> Unit, onPrevious: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val still = rememberReduceMotion()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = offset.value
                    alpha = 1f - (abs(offset.value) / width * 0.7f).coerceIn(0f, 0.7f)
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d -> scope.launch { offset.snapTo(offset.value + d) } },
                    onDragStopped = { v ->
                        val go = when {
                            offset.value < -width * 0.25f || v < -1500f -> -1
                            offset.value > width * 0.25f || v > 1500f -> 1
                            else -> 0
                        }
                        if (go == 0) {
                            offset.animateTo(0f, tween(200))
                        } else {
                            if (!still) offset.animateTo(go * width, tween(160))
                            if (go < 0) onNext() else onPrevious()
                            offset.snapTo(-go * width)
                            if (still) offset.snapTo(0f) else offset.animateTo(0f, tween(220))
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/**
 * The queue, split the way streaming apps split it: what's on, what you queued yourself, and
 * what the tape plays next. Drag the handle to reorder, swipe a song away to remove it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val player = vm.player
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    val view = remember(player.tick) { player.queueView() }
    // Local copy while dragging; committed to the player when the drag ends.
    var upcoming by remember(view) { mutableStateOf(view.queued + view.rest) }
    val queuedCount = upcoming.takeWhile { it.item.isQueued }.size
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var savingAsTape by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = upcoming.indexOfFirst { it.item.qid == from.key }
        val b = upcoming.indexOfFirst { it.item.qid == to.key }
        if (a >= 0 && b >= 0) {
            upcoming = upcoming.toMutableList().apply { add(b, removeAt(a)) }
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Tape.Deck) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Up next", style = MaterialTheme.typography.titleLarge, color = Tape.Cream, modifier = Modifier.weight(1f))
            if (view.queued.isNotEmpty()) {
                TextButton(onClick = { player.clearQueued() }) { Text("Clear queue", color = Tape.Dust) }
            }
            TextButton(onClick = { savingAsTape = true }) { Text("Save as tape", color = Tape.Dust) }
        }
        if (savingAsTape) {
            TapeEditorDialog(
                title = "Save the queue as a tape",
                confirm = "Record",
                initialColor = Tape.labels.indices.random(),
                onDismiss = { savingAsTape = false },
                onConfirm = { name, color ->
                    savingAsTape = false
                    val songs = player.queueKeys().mapNotNull { vm.songFor(it) }
                    vm.createTape(name, color, songs, openIt = false)
                },
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            view.current?.let { cur ->
                item(key = "h:now") { QueueHeader("Now playing") }
                item(key = "now") { QueueRow(vm, cur, current = true, handle = null) }
            }
            upcoming.forEachIndexed { i, entry ->
                if (i == 0 && queuedCount > 0) item(key = "h:queued") { QueueHeader("Next in queue") }
                if (i == queuedCount) item(key = "h:rest") { QueueHeader(view.restFrom?.let { "Next from: $it" } ?: "Next up") }
                item(key = entry.item.qid) {
                    ReorderableItem(reorder, key = entry.item.qid) { dragging ->
                        val lift by animateDpAsState(if (dragging) 6.dp else 0.dp, label = "lift")
                        val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                            if (v != SwipeToDismissBoxValue.Settled) {
                                player.remove(entry.index)
                                true
                            } else false
                        })
                        SwipeToDismissBox(
                            state = dismiss,
                            backgroundContent = {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Tape.Brick.copy(alpha = 0.35f))
                                        .padding(horizontal = 24.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Tape.Cream) }
                            },
                        ) {
                            Surface(color = Tape.Deck, shadowElevation = lift) {
                                QueueRow(
                                    vm, entry, current = false,
                                    handle = Modifier.draggableHandle(
                                        onDragStarted = {
                                            dragFrom = entry.index
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragStopped = {
                                            val from = dragFrom
                                            dragFrom = null
                                            val newPos = upcoming.indexOfFirst { it.item.qid == entry.item.qid }
                                            val target = (view.current?.index ?: -1) + 1 + newPos
                                            if (from != null && newPos >= 0) player.move(from, target)
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun QueueHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Tape.Dust,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun QueueRow(vm: MainViewModel, entry: QueueEntry, current: Boolean, handle: Modifier?) {
    val item = entry.item
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.player.jumpTo(entry.index) }
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongArt(Uri.parse(item.mediaId), Modifier.size(42.dp), seed = item.folder)
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
            )
        }
        if (current) {
            EqualizerBars(animating = vm.player.isPlaying, modifier = Modifier.padding(end = 12.dp))
        } else if (handle != null) {
            IconButton(onClick = {}, modifier = handle) {
                Icon(Icons.Rounded.DragHandle, contentDescription = "Reorder", tint = Tape.Dust)
            }
        }
    }
}

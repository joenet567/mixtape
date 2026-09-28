package com.joenet.mixtape.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.PlayCtx
import com.joenet.mixtape.TapeHit
import com.joenet.mixtape.TapeRef
import com.joenet.mixtape.data.TapeTrack
import com.joenet.mixtape.formatLong
import com.joenet.mixtape.songCount
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun BackRow(vm: MainViewModel, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.back() }, modifier = Modifier.offset(x = 4.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Tape.Cream)
        }
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onPlay) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Play")
        }
        OutlinedButton(onClick = onShuffle) {
            Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Shuffle")
        }
    }
}

@Composable
fun TapeScreen(vm: MainViewModel, ref: TapeRef) {
    if (ref is TapeRef.User) {
        UserTapeScreen(vm, ref.id)
        return
    }
    val hit = vm.tape(ref)
    if (hit == null) {
        Column(Modifier.fillMaxSize()) {
            BackRow(vm)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This tape isn't on the phone any more.", color = Tape.Dust)
            }
        }
        return
    }
    val songs = hit.songs
    val ctx = vm.ctxFor(hit)
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            TapeHeader(vm, hit, meta = "${songCount(songs.size)}, ${formatLong(songs.sumOf { it.durationMs })}")
        }
        if (songs.isEmpty() && ref == TapeRef.Liked) {
            item(key = "empty") {
                Text(
                    "Songs you like end up here. Tap the heart on the player, or long-press any song and choose Like.",
                    color = Tape.Dust,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
        itemsIndexed(songs, key = { i, s -> "${s.id}#$i" }) { i, song ->
            SongRow(
                song,
                current = song.mediaId == vm.player.mediaId && vm.isPlayingFrom(hit),
                playing = vm.player.isPlaying,
                number = i + 1,
                onLongClick = { vm.actionsFor = song },
            ) { vm.player.play(songs, i, ctx) }
        }
    }
}

@Composable
private fun TapeHeader(vm: MainViewModel, hit: TapeHit, meta: String, footRight: String? = null, actions: @Composable () -> Unit = {}) {
    val songs = hit.songs
    val ctx = vm.ctxFor(hit)
    Column(Modifier.fillMaxWidth()) {
        BackRow(vm, actions)
        Column(Modifier.padding(horizontal = 16.dp)) {
            TapeCassette(
                vm, hit,
                Modifier
                    .fillMaxWidth(0.84f)
                    .align(Alignment.CenterHorizontally),
                footLeft = "SIDE A",
                footRight = footRight ?: songCount(songs.size).uppercase(),
            )
            Spacer(Modifier.height(20.dp))
            Text(hit.name, style = MaterialTheme.typography.headlineSmall, color = Tape.Cream)
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = Tape.Dust)
            Spacer(Modifier.height(14.dp))
            if (songs.isNotEmpty()) {
                PlayShuffleButtons(
                    onPlay = { vm.player.playInOrder(songs, ctx) },
                    onShuffle = { vm.player.shuffleAll(songs, ctx) },
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * A tape you recorded: rename, recolour, share or erase it from the menu; Edit to drag songs into
 * order, drop them, or move them to the other side. Two-sided tapes show Side A and Side B and warn
 * when a side runs longer than the cassette allows (30 min on a C-60, 45 on a C-90).
 */
@Composable
private fun UserTapeScreen(vm: MainViewModel, id: Long) {
    val t = vm.userTape(id)
    if (t == null) {
        Column(Modifier.fillMaxSize()) { BackRow(vm) }
        return
    }
    val tape = t.tape
    val hit = vm.userTapeHit(t)
    val haptics = LocalHapticFeedback.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var tracks by remember(t.tracks) { mutableStateOf(t.tracks) }
    val share = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        uri?.let { vm.exportTape(id, it) }
    }

    fun durationOf(list: List<TapeTrack>) = list.sumOf { vm.songFor(it.key)?.durationMs ?: 0L }
    val sideLimitMs = tape.length / 2 * 60_000L
    val sideA = tracks.filter { it.side == 0 }
    val sideB = tracks.filter { it.side == 1 }
    val total = durationOf(tracks)
    val meta = buildString {
        append("${songCount(tracks.size)}, ${formatLong(total)}")
        if (tape.twoSided) append(", C-${tape.length}")
    }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = tracks.indexOfFirst { trackKey(it) == from.key }
        val b = tracks.indexOfFirst { trackKey(it) == to.key }
        if (a >= 0 && b >= 0) {
            val moved = tracks[a].copy(side = tracks[b].side) // dragging past a Side B song puts it on side B
            tracks = tracks.toMutableList().apply {
                removeAt(a)
                add(b, moved)
            }
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "header") {
            TapeHeader(vm, hit, meta, footRight = if (tape.twoSided) "C-${tape.length}" else null) {
                if (tracks.isNotEmpty()) {
                    TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit", color = Tape.Cream) }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Tape options", tint = Tape.Cream)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Tape.DeckHigh) {
                        DropdownMenuItem(text = { Text("Rename or recolour") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(
                            text = { Text(if (tape.twoSided) "Make it one-sided" else "Split into Side A and B") },
                            onClick = {
                                menu = false
                                vm.updateTape(tape.copy(twoSided = !tape.twoSided))
                                if (tape.twoSided) vm.setTapeTracks(id, tracks.map { it.copy(side = 0) })
                            },
                        )
                        if (tape.twoSided) {
                            DropdownMenuItem(
                                text = { Text(if (tape.length == 90) "Use a C-60 (30 min a side)" else "Use a C-90 (45 min a side)") },
                                onClick = {
                                    menu = false
                                    vm.updateTape(tape.copy(length = if (tape.length == 90) 60 else 90))
                                },
                            )
                        }
                        DropdownMenuItem(text = { Text("Share as a playlist file") }, onClick = {
                            menu = false
                            share.launch("${tape.name}.m3u8")
                        })
                        DropdownMenuItem(text = { Text("Erase tape", color = Tape.Brick) }, onClick = { menu = false; erasing = true })
                    }
                }
            }
        }
        if (tracks.isEmpty()) {
            item(key = "empty") {
                Text(
                    "A blank tape. Long-press any song, anywhere, and choose Add to tape.",
                    color = Tape.Dust,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
        val sides = if (tape.twoSided) listOf(0 to sideA, 1 to sideB) else listOf(0 to tracks)
        var number = 0
        for ((side, list) in sides) {
            if (tape.twoSided) {
                item(key = "side$side") {
                    val len = durationOf(list)
                    SideHeader(if (side == 0) "SIDE A" else "SIDE B", len, over = len > sideLimitMs, limitMin = tape.length / 2)
                }
            }
            for (track in list) {
                val n = ++number
                item(key = trackKey(track)) {
                    val song = vm.songFor(track.key)
                    ReorderableItem(reorder, key = trackKey(track), enabled = editing) { dragging ->
                        val lift by animateDpAsState(if (dragging) 6.dp else 0.dp, label = "lift")
                        Surface(color = Tape.Ink, shadowElevation = lift) {
                            if (song == null) {
                                MissingTrackRow(track, editing) { tracks = tracks - track; vm.setTapeTracks(id, tracks) }
                            } else {
                                val songs = hit.songs
                                SongRow(
                                    song,
                                    current = song.mediaId == vm.player.mediaId && vm.isPlayingFrom(hit),
                                    playing = vm.player.isPlaying,
                                    number = n,
                                    onLongClick = if (editing) null else ({ vm.actionsFor = song }),
                                    trailing = if (!editing) null else ({
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (tape.twoSided) {
                                                IconButton(onClick = {
                                                    tracks = tracks.map { if (it === track) it.copy(side = 1 - it.side) else it }
                                                    vm.setTapeTracks(id, tracks)
                                                }) { Icon(Icons.Rounded.SwapVert, contentDescription = "Move to side ${if (track.side == 0) "B" else "A"}", tint = Tape.Dust) }
                                            }
                                            IconButton(onClick = {
                                                tracks = tracks - track
                                                vm.setTapeTracks(id, tracks)
                                            }) { Icon(Icons.Rounded.Close, contentDescription = "Remove from tape", tint = Tape.Dust) }
                                            IconButton(
                                                onClick = {},
                                                modifier = Modifier.draggableHandle(
                                                    onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                                    onDragStopped = { vm.setTapeTracks(id, tracks) },
                                                ),
                                            ) { Icon(Icons.Rounded.DragHandle, contentDescription = "Reorder", tint = Tape.Dust) }
                                        }
                                    }),
                                ) {
                                    if (!editing) vm.player.play(songs, songs.indexOf(song).coerceAtLeast(0), vm.ctxFor(hit))
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }

    if (renaming) {
        TapeEditorDialog(
            title = "Rename tape",
            confirm = "Save",
            initialName = tape.name,
            initialColor = tape.color,
            onDismiss = { renaming = false },
            onConfirm = { name, color ->
                vm.updateTape(tape.copy(name = name, color = color))
                renaming = false
            },
        )
    }
    if (erasing) {
        AlertDialog(
            onDismissRequest = { erasing = false },
            containerColor = Tape.Deck,
            title = { Text("Erase “${tape.name}”?") },
            text = { Text("The tape goes; the songs stay in your library.", color = Tape.Dust) },
            confirmButton = { TextButton(onClick = { erasing = false; vm.deleteTape(id) }) { Text("Erase", color = Tape.Brick) } },
            dismissButton = { TextButton(onClick = { erasing = false }) { Text("Keep it", color = Tape.Dust) } },
        )
    }
}

private fun trackKey(t: TapeTrack) = "t:${t.tapeId}:${t.key}:${t.position}"

@Composable
private fun SideHeader(label: String, lengthMs: Long, over: Boolean, limitMin: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Legend, color = Tape.Cream)
        Spacer(Modifier.weight(1f))
        Text(
            if (over) "${formatLong(lengthMs)}, over the $limitMin min a side fits" else formatLong(lengthMs),
            style = MaterialTheme.typography.bodySmall,
            color = if (over) Tape.Mustard else Tape.Dust,
        )
    }
}

@Composable
private fun MissingTrackRow(track: TapeTrack, editing: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, color = Tape.Dust, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Not on the phone. Sync it from your PC", style = MaterialTheme.typography.bodySmall, color = Tape.Dust)
        }
        if (editing) {
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove from tape", tint = Tape.Dust) }
        }
    }
}

@Composable
fun ArtistScreen(vm: MainViewModel, name: String) {
    val songs = vm.library.artist(name)
    val tapes = vm.libraryTapes().filter { t -> t.ref is TapeRef.Folder && t.songs.any { name in it.artists } }
    val ctx = PlayCtx.artist(name)
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth()) {
                BackRow(vm)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ArtistAvatar(name, 120.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(name, style = MaterialTheme.typography.headlineMedium, color = Tape.Cream)
                    Text(songCount(songs.size), style = MaterialTheme.typography.bodyMedium, color = Tape.Dust)
                    Spacer(Modifier.height(14.dp))
                    if (songs.isNotEmpty()) {
                        PlayShuffleButtons(
                            onPlay = { vm.player.playInOrder(songs, ctx) },
                            onShuffle = { vm.player.shuffleAll(songs, ctx) },
                        )
                    }
                }
                SectionTitle("Songs")
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongRow(
                song,
                current = song.mediaId == vm.player.mediaId,
                playing = vm.player.isPlaying,
                onLongClick = { vm.actionsFor = song },
            ) { vm.player.play(songs, i, ctx) }
        }
        if (tapes.isNotEmpty()) {
            item(key = "tapes") {
                Column {
                    SectionTitle("On these tapes")
                    Shelf(tapes, key = { it.ref.toString() }) { TapeCard(vm, it, width = 160.dp) }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

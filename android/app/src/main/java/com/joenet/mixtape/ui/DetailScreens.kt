package com.joenet.mixtape.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.TapeRef
import com.joenet.mixtape.formatLong
import com.joenet.mixtape.songCount

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
    val player = vm.player
    val songs = hit.songs
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth()) {
                BackRow(vm) { TapeMenu(vm, hit) }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    TapeCassette(
                        vm, hit,
                        Modifier
                            .fillMaxWidth(0.84f)
                            .align(Alignment.CenterHorizontally),
                        footLeft = "SIDE A",
                        footRight = songCount(songs.size).uppercase(),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(hit.name, style = MaterialTheme.typography.headlineSmall, color = Tape.Cream)
                    Text(
                        "${songCount(songs.size)}, ${formatLong(songs.sumOf { it.durationMs })}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tape.Dust,
                    )
                    Spacer(Modifier.height(14.dp))
                    if (songs.isNotEmpty()) {
                        PlayShuffleButtons(
                            onPlay = { player.playInOrder(songs, hit.name) },
                            onShuffle = { player.shuffleAll(songs, hit.name) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        tapeTracks(vm, hit)
    }
}

/** The track list of a tape page (your own tapes override it to allow editing). */
private fun androidx.compose.foundation.lazy.LazyListScope.tapeTracks(vm: MainViewModel, hit: com.joenet.mixtape.TapeHit) {
    val songs = hit.songs
    itemsIndexed(songs, key = { i, s -> "${s.id}#$i" }) { i, song ->
        SongRow(
            song,
            current = song.mediaId == vm.player.mediaId && vm.isPlayingFrom(hit),
            playing = vm.player.isPlaying,
            number = i + 1,
            onLongClick = { vm.actionsFor = song },
        ) { vm.player.play(songs, i, hit.name) }
    }
}

/** Per-tape overflow actions. Folder tapes have none; your own tapes get them in phase 4. */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun TapeMenu(vm: MainViewModel, hit: com.joenet.mixtape.TapeHit) = Unit

@Composable
fun ArtistScreen(vm: MainViewModel, name: String) {
    val songs = vm.library.artist(name)
    val tapes = vm.libraryTapes().filter { t -> t.songs.any { name in it.artists } }
    val ctx = name
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

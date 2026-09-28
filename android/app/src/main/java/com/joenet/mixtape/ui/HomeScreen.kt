package com.joenet.mixtape.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.Route
import com.joenet.mixtape.Song
import com.joenet.mixtape.formatLong
import com.joenet.mixtape.songCount

@Composable
fun HomeScreen(vm: MainViewModel) {
    val songs = vm.songs
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "brand") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
                Text("MIXTAPE", style = MaterialTheme.typography.displaySmall, color = Tape.Cream)
                if (songs.isNotEmpty()) {
                    val tapes = vm.library.tapes.size
                    Text(
                        "${songCount(songs.size)}, $tapes ${if (tapes == 1) "tape" else "tapes"}, ${formatLong(songs.sumOf { it.durationMs })}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tape.Dust,
                    )
                }
            }
        }
        when {
            !vm.loaded -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Tape.Cream)
                }
            }
            songs.isEmpty() -> item(key = "empty") {
                Box(Modifier.fillMaxWidth().height(520.dp)) { EmptyLibrary(onSync = { vm.open(Route.Sync) }) }
            }
            else -> {
                item(key = "shuffle") { ShuffleAllTape(vm) }
                val fresh = vm.newSongs()
                if (fresh.isNotEmpty()) {
                    item(key = "new") {
                        Column {
                            SectionTitle("New from your PC")
                            Shelf(fresh, key = { it.id }) { song ->
                                SongCard(vm, song) { vm.player.play(fresh, fresh.indexOf(song), "New from your PC") }
                            }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** A single tape of everything: tap to shuffle the whole library. */
@Composable
private fun ShuffleAllTape(vm: MainViewModel) {
    val songs = vm.songs
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Shuffle all your music") { vm.player.shuffleAll(songs, "All songs") }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cassette(
            label = "Shuffle all",
            labelColor = Tape.Mustard,
            modifier = Modifier.fillMaxWidth(0.78f),
            art = songs.firstOrNull()?.uri,
            footLeft = "C-90",
            footRight = songCount(songs.size).uppercase(),
        )
        Text(
            "Everything, shuffled",
            style = MaterialTheme.typography.bodyMedium,
            color = Tape.Dust,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A song on a home shelf: cover, title, artist. Long-press for actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongCard(vm: MainViewModel, song: Song, onClick: () -> Unit) {
    Column(
        Modifier
            .width(132.dp)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "More actions",
                onLongClick = { vm.actionsFor = song },
            )
            .semantics(mergeDescendants = true) { contentDescription = "${song.title} by ${song.artist}" }
    ) {
        SongArt(song.uri, Modifier.size(132.dp), corner = 8.dp, seed = song.folder)
        Text(
            song.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (song.mediaId == vm.player.mediaId) Tape.Orange else Tape.Cream,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Tape.Dust, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RewindScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) { BackRow(vm) }
}

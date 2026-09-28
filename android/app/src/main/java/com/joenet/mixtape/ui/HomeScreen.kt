package com.joenet.mixtape.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.sp
import com.joenet.mixtape.CtxTarget
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.PlayCtx
import com.joenet.mixtape.ResumeCard
import com.joenet.mixtape.Route
import com.joenet.mixtape.Song
import com.joenet.mixtape.formatLong
import com.joenet.mixtape.songCount

@Composable
fun HomeScreen(vm: MainViewModel) {
    val songs = vm.songs
    val home = vm.home
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "brand") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
                Text("MIXTAPE", style = MaterialTheme.typography.displaySmall, color = Tape.Cream)
                if (songs.isNotEmpty()) {
                    val tapes = vm.library.tapes.size + vm.userTapes.size
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
                if (home.jumpBackIn.isNotEmpty()) {
                    item(key = "jump") {
                        Column {
                            SectionTitle("Jump back in")
                            Shelf(home.jumpBackIn, key = { it.resume.ctxRef }) { ResumeTile(vm, it) }
                        }
                    }
                }
                if (home.newSongs.isNotEmpty()) {
                    item(key = "new") {
                        Column {
                            SectionTitle("New from your PC")
                            if (home.newFromLastSync) {
                                Text(
                                    "${songCount(home.newSongs.size)} from your last sync",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Tape.Dust,
                                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                                )
                            }
                            SongShelf(vm, home.newSongs, PlayCtx.oneOff("New from your PC"))
                        }
                    }
                }
                if (home.onRepeat.isNotEmpty()) {
                    item(key = "repeat") {
                        Column {
                            SectionTitle("On repeat")
                            SongShelf(vm, home.onRepeat, PlayCtx.oneOff("On repeat"))
                        }
                    }
                }
                if (home.forgotten.isNotEmpty()) {
                    item(key = "forgotten") {
                        Column {
                            SectionTitle("Forgotten favourites")
                            SongShelf(vm, home.forgotten, PlayCtx.oneOff("Forgotten favourites"))
                        }
                    }
                }
                home.rewind?.let { r ->
                    item(key = "rewind") {
                        Column {
                            SectionTitle("Your Rewind")
                            Column(
                                Modifier
                                    .padding(horizontal = 16.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable(onClickLabel = "Open your ${r.monthLabel} Rewind") { vm.open(Route.Rewind) }
                                    .padding(4.dp)
                            ) {
                                Cassette(
                                    label = "Rewind: ${r.monthLabel}",
                                    labelColor = Tape.Sky,
                                    modifier = Modifier.width(220.dp),
                                    art = r.topSongs.firstOrNull()?.first?.uri,
                                    footLeft = "SIDE A",
                                    footRight = playsLegend(r.plays),
                                )
                                Text(
                                    "${formatLong(r.listenedMs)} of music this month",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Tape.Dust,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
                item(key = "shuffle") {
                    Column {
                        SectionTitle("Everything")
                        ShuffleAllTape(vm)
                    }
                }
                item(key = "end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SongShelf(vm: MainViewModel, list: List<Song>, ctx: PlayCtx) {
    Shelf(list, key = { it.id }) { song ->
        SongCard(vm, song) { vm.player.play(list, list.indexOf(song), ctx) }
    }
}

/** A "Jump back in" tile: the tape (or artist) and the song it stopped on. Tap to carry on. */
@Composable
private fun ResumeTile(vm: MainViewModel, card: ResumeCard) {
    val song = vm.songFor(card.resume.lastKey)
    Column(
        Modifier
            .width(160.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Carry on with ${card.name}") { vm.resume(card) }
            .semantics(mergeDescendants = true) { contentDescription = "${card.name}, carry on from ${song?.title.orEmpty()}" }
            .padding(4.dp)
    ) {
        when (val t = card.target) {
            is CtxTarget.OfArtist -> Box(Modifier.fillMaxWidth().height(101.dp), contentAlignment = Alignment.Center) {
                ArtistAvatar(t.name, 96.dp)
            }
            else -> {
                val hit = card.tape
                if (hit != null) {
                    TapeCassette(vm, hit, Modifier.fillMaxWidth())
                } else {
                    Cassette(card.name, Tape.Mustard, Modifier.fillMaxWidth(), art = card.songs.firstOrNull()?.uri)
                }
            }
        }
        Text(card.name, style = MaterialTheme.typography.bodyMedium, color = Tape.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (song != null) {
            Text(song.title, style = MaterialTheme.typography.bodySmall, color = Tape.Dust, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Shuffle all your music") { vm.player.shuffleAll(songs, PlayCtx.ALL) }
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

private fun playCount(n: Int) = if (n == 1) "1 play" else "$n plays"
private fun playsLegend(n: Int) = playCount(n).uppercase()

/** This month on the deck: time listened, top songs and top artists, and a tape of the top songs. */
@Composable
fun RewindScreen(vm: MainViewModel) {
    val r = vm.home.rewind
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Column {
                BackRow(vm)
                if (r == null) {
                    Text("Play some music this month and your Rewind shows up here.", color = Tape.Dust, modifier = Modifier.padding(20.dp))
                    return@Column
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Cassette(
                        label = "Rewind: ${r.monthLabel}",
                        labelColor = Tape.Sky,
                        modifier = Modifier
                            .fillMaxWidth(0.84f)
                            .align(Alignment.CenterHorizontally),
                        art = r.topSongs.firstOrNull()?.first?.uri,
                        footLeft = "SIDE A",
                        footRight = playsLegend(r.plays),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text("Your ${r.monthLabel}", style = MaterialTheme.typography.headlineMedium, color = Tape.Cream)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(formatLong(r.listenedMs), fontFamily = Barlow, fontSize = 44.sp, color = Tape.Cream)
                        Text(" listened, ${playCount(r.plays)}", style = MaterialTheme.typography.bodyLarge, color = Tape.Dust, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    if (r.topSongs.isNotEmpty()) {
                        Button(onClick = {
                            val list = r.topSongs.map { it.first }
                            vm.player.play(list, 0, PlayCtx.oneOff("Rewind: ${r.monthLabel}"))
                        }) { Text("Play your top songs") }
                    }
                }
            }
        }
        if (r != null) {
            item(key = "songsTitle") { SectionTitle("Top songs") }
            itemsIndexed(r.topSongs, key = { _, it -> "s:${it.first.id}" }) { i, (song, plays) ->
                SongRow(
                    song,
                    current = song.mediaId == vm.player.mediaId,
                    playing = vm.player.isPlaying,
                    number = i + 1,
                    onLongClick = { vm.actionsFor = song },
                    trailing = { Text(playCount(plays), style = MaterialTheme.typography.bodySmall, color = Tape.Dust) },
                ) { vm.player.play(r.topSongs.map { it.first }, i, PlayCtx.oneOff("Rewind: ${r.monthLabel}")) }
            }
            if (r.topArtists.isNotEmpty()) {
                item(key = "artistsTitle") { SectionTitle("Top artists") }
                itemsIndexed(r.topArtists, key = { _, it -> "a:${it.first}" }) { i, (artist, plays) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.library.artists.keys.firstOrNull { it == artist || artist.startsWith(it) }?.let { vm.open(Route.Artist(it)) } }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("%02d".format(i + 1), fontFamily = Mono, color = Tape.Dust, modifier = Modifier.width(32.dp))
                        ArtistAvatar(artist, 44.dp)
                        Text(artist, color = Tape.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 14.dp))
                        Text(playCount(plays), style = MaterialTheme.typography.bodySmall, color = Tape.Dust)
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

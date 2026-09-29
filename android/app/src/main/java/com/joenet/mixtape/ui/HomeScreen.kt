package com.joenet.mixtape.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontWeight
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
    val chromeInset = LocalChromeInset.current
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "brand") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
                Text("MIXTAPE", style = MaterialTheme.typography.displaySmall, color = Tape.Fg)
                if (songs.isNotEmpty()) {
                    val tapes = vm.library.tapes.size + vm.userTapes.size
                    Text(
                        "${songCount(songs.size)}, $tapes ${if (tapes == 1) "tape" else "tapes"}, ${formatLong(songs.sumOf { it.durationMs })}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tape.FgMuted,
                    )
                }
            }
        }
        when {
            !vm.loaded -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Tape.Fg)
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
                                    color = Tape.FgMuted,
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
                                CoverWithTape(
                                    art = r.topSongs.firstOrNull()?.first?.uri,
                                    labelColor = Tape.Sky,
                                    seed = "Rewind",
                                    modifier = Modifier.width(220.dp),
                                )
                                Text(
                                    "Rewind: ${r.monthLabel}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Tape.Fg,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Text(
                                    "${formatLong(r.listenedMs)} of music this month",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Tape.FgMuted,
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
                item(key = "end") { Spacer(Modifier.height(24.dp + chromeInset)) }
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
            // square like a cover, so artist and tape tiles line up on the shelf
            is CtxTarget.OfArtist -> Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                ArtistAvatar(t.name, 120.dp)
            }
            else -> {
                val hit = card.tape
                if (hit != null) {
                    TapeCover(vm, hit, Modifier.fillMaxWidth())
                } else {
                    CoverWithTape(
                        art = card.songs.firstOrNull()?.uri,
                        labelColor = Tape.Mustard,
                        seed = card.name,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Text(card.name, style = MaterialTheme.typography.titleSmall, color = Tape.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        if (song != null) {
            Text(song.title, style = MaterialTheme.typography.bodySmall, color = Tape.FgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        CoverWithTape(
            art = songs.firstOrNull()?.uri,
            labelColor = Tape.Mustard,
            seed = "Shuffle all",
            modifier = Modifier.fillMaxWidth(0.6f),
        )
        Text(
            "Everything, shuffled",
            style = MaterialTheme.typography.titleSmall,
            color = Tape.Fg,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "${songCount(songs.size)}, ${formatLong(songs.sumOf { it.durationMs })}",
            style = MaterialTheme.typography.bodySmall,
            color = Tape.FgMuted,
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
            color = if (song.mediaId == vm.player.mediaId) Tape.Accent else Tape.Fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Tape.FgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun playCount(n: Int) = if (n == 1) "1 play" else "$n plays"

/** This month on the deck: time listened, top songs and top artists, and a tape of the top songs. */
@Composable
fun RewindScreen(vm: MainViewModel) {
    val r = vm.home.rewind
    val chromeInset = LocalChromeInset.current
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Column {
                BackRow(vm)
                if (r == null) {
                    Text("Play some music this month and your Rewind shows up here.", color = Tape.FgMuted, modifier = Modifier.padding(20.dp))
                    return@Column
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    CoverWithTape(
                        art = r.topSongs.firstOrNull()?.first?.uri,
                        labelColor = Tape.Sky,
                        seed = "Rewind",
                        modifier = Modifier
                            .fillMaxWidth(0.66f)
                            .align(Alignment.CenterHorizontally),
                        px = 768,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text("Your ${r.monthLabel}", style = MaterialTheme.typography.headlineMedium, color = Tape.Fg)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(formatLong(r.listenedMs), fontFamily = Mix, fontWeight = FontWeight.Bold, fontSize = 44.sp, color = Tape.Fg)
                        Text(" listened, ${playCount(r.plays)}", style = MaterialTheme.typography.bodyLarge, color = Tape.FgMuted, modifier = Modifier.padding(bottom = 8.dp))
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
                    trailing = { Text(playCount(plays), style = MaterialTheme.typography.bodySmall, color = Tape.FgMuted) },
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
                        Text("%02d".format(i + 1), fontFamily = Mono, color = Tape.FgMuted, modifier = Modifier.width(32.dp))
                        ArtistAvatar(artist, 44.dp)
                        Text(artist, color = Tape.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 14.dp))
                        Text(playCount(plays), style = MaterialTheme.typography.bodySmall, color = Tape.FgMuted)
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp + chromeInset)) }
        }
    }
}

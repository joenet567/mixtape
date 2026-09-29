package com.joenet.mixtape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.PlayCtx
import com.joenet.mixtape.Route
import com.joenet.mixtape.SearchResults
import com.joenet.mixtape.Searcher
import com.joenet.mixtape.TopResult
import com.joenet.mixtape.songCount

@Composable
fun SearchScreen(vm: MainViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, vm.library, vm.searchExtras) { Searcher.search(vm.library, query, vm.searchExtras) }
    val focus = LocalFocusManager.current
    val used = { vm.rememberSearch(query) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Search",
            style = MaterialTheme.typography.headlineMedium,
            color = Tape.Fg,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 12.dp),
        )
        val fieldShape = RoundedCornerShape(50)
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, artists, tapes", color = Tape.FgMuted) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Tape.FgMuted) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = Tape.FgMuted) } }
            } else null,
            singleLine = true,
            shape = fieldShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                used()
                focus.clearFocus()
            }),
            // the glass pill is drawn by the modifier, so the field's own container stays transparent
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Tape.Fg,
                focusedTextColor = Tape.Fg,
                unfocusedTextColor = Tape.Fg,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // in-content glass: this screen is the glass source, so no backdrop (flat translucent fill + rim)
                .glass(fieldShape, backdrop = null, strength = GlassStrength.Thin, elevation = 0.dp),
        )
        when {
            query.isBlank() -> SearchStart(vm) { query = it }
            results.isEmpty -> Text(
                "Nothing matches “$query”. Every word has to appear in a song, artist or tape name.",
                color = Tape.FgMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
            )
            else -> Results(vm, results, used)
        }
    }
}

/** An empty box shows your recent searches (and what you played recently). */
@Composable
private fun SearchStart(vm: MainViewModel, onPick: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp + LocalChromeInset.current)) {
        if (vm.recentSearches.isEmpty()) {
            item {
                Text(
                    "Find songs, artists and tapes. Accents don't matter, and words can come in any order: " +
                        "“son tung” finds “Sơn Tùng”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tape.FgMuted,
                    modifier = Modifier.padding(20.dp),
                )
            }
        } else {
            item {
                SectionTitle("Recent searches") {
                    TextButton(onClick = vm::clearRecentSearches) { Text("Clear", color = Tape.FgMuted) }
                }
            }
            items(vm.recentSearches, key = { "r:$it" }) { q ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(q) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null, tint = Tape.FgMuted, modifier = Modifier.size(20.dp))
                    Text(q, color = Tape.Fg, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
        val recent = vm.recentlyPlayed()
        if (recent.isNotEmpty()) {
            item(key = "played") { SectionTitle("Recently played") }
            itemsIndexed(recent, key = { _, s -> "p:${s.id}" }) { i, song ->
                SongRow(
                    song,
                    current = song.mediaId == vm.player.mediaId,
                    playing = vm.player.isPlaying,
                    onLongClick = { vm.actionsFor = song },
                ) { vm.player.play(recent, i, PlayCtx.oneOff("Recently played")) }
            }
        }
    }
}

@Composable
private fun Results(vm: MainViewModel, r: SearchResults, used: () -> Unit) {
    var allSongs by rememberSaveable(r.query) { mutableStateOf(false) }
    val ctx = PlayCtx.oneOff("Search: “${r.query.trim()}”")
    val playSong = { index: Int ->
        used()
        vm.player.play(r.songs, index, ctx) // the rest of the matches follow it, like Spotify
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + LocalChromeInset.current)) {
        r.top?.let { top ->
            item(key = "top") {
                SectionTitle("Top result")
                TopResultCard(vm, top) {
                    used()
                    when (top) {
                        is TopResult.OfSong -> playSong(r.songs.indexOf(top.song).coerceAtLeast(0))
                        is TopResult.OfArtist -> vm.open(Route.Artist(top.name))
                        is TopResult.OfTape -> vm.open(Route.Tape(top.tape.ref))
                    }
                }
            }
        }
        if (r.songs.isNotEmpty()) {
            item(key = "songs") {
                SectionTitle("Songs") {
                    if (r.songs.size > 4) {
                        TextButton(onClick = { allSongs = !allSongs }) {
                            Text(if (allSongs) "Show fewer" else "Show all ${r.songs.size}", color = Tape.FgMuted)
                        }
                    }
                }
            }
            val shown = if (allSongs) r.songs else r.songs.take(4)
            itemsIndexed(shown, key = { _, s -> "s:${s.id}" }) { i, song ->
                SongRow(
                    song,
                    current = song.mediaId == vm.player.mediaId,
                    playing = vm.player.isPlaying,
                    onLongClick = { vm.actionsFor = song },
                ) { playSong(i) }
            }
        }
        if (r.artists.isNotEmpty()) {
            item(key = "artists") {
                Column {
                    SectionTitle("Artists")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(r.artists, key = { it.first }) { (name, songs) ->
                            Column(
                                Modifier
                                    .width(96.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        used()
                                        vm.open(Route.Artist(name))
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                ArtistAvatar(name, 84.dp)
                                Text(name, color = Tape.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                Text(songCount(songs.size), style = MaterialTheme.typography.bodySmall, color = Tape.FgMuted)
                            }
                        }
                    }
                }
            }
        }
        if (r.tapes.isNotEmpty()) {
            item(key = "tapes") {
                Column {
                    SectionTitle("Tapes")
                    Shelf(r.tapes, key = { it.ref.toString() }) { TapeCard(vm, it, width = 160.dp) }
                }
            }
        }
    }
}

@Composable
private fun TopResultCard(vm: MainViewModel, top: TopResult, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Tape.Surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (top) {
            is TopResult.OfSong -> SongArt(top.song.uri, Modifier.size(84.dp), corner = 8.dp, seed = top.song.folder)
            is TopResult.OfArtist -> ArtistAvatar(top.name, 84.dp)
            is TopResult.OfTape -> TapeCover(vm, top.tape, Modifier.width(120.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                when (top) {
                    is TopResult.OfSong -> top.song.title
                    is TopResult.OfArtist -> top.name
                    is TopResult.OfTape -> top.tape.name
                },
                style = MaterialTheme.typography.titleLarge,
                color = Tape.Fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when (top) {
                    is TopResult.OfSong -> "Song · ${top.song.artist}"
                    is TopResult.OfArtist -> "Artist · ${songCount(top.songs.size)}"
                    is TopResult.OfTape -> "Tape · ${songCount(top.tape.songs.size)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Tape.FgMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

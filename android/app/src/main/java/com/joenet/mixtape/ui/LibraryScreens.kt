package com.joenet.mixtape.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.PlayerUi
import com.joenet.mixtape.Playlist
import com.joenet.mixtape.Screen
import com.joenet.mixtape.Song
import com.joenet.mixtape.formatDuration
import com.joenet.mixtape.songCount

@Composable
fun MixtapeApp(vm: MainViewModel, onRequestPermission: () -> Unit) {
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }
    val saved = rememberSaveableStateHolder() // keeps each screen's tab / scroll position across navigation
    Box(
        Modifier
            .fillMaxSize()
            .background(Tape.Ink)
    ) {
        if (!vm.hasPermission && vm.screen != Screen.Sync) {
            PermissionScreen(onRequestPermission)
            return@Box
        }
        AnimatedContent(
            targetState = vm.screen,
            transitionSpec = {
                when {
                    targetState == Screen.NowPlaying ->
                        slideInVertically(tween(320)) { it } togetherWith fadeOut(tween(250))
                    initialState == Screen.NowPlaying ->
                        (fadeIn(tween(200)) togetherWith slideOutVertically(tween(280)) { it })
                            .apply { targetContentZIndex = -1f } // player slides away on top
                    else ->
                        (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 8 }) togetherWith fadeOut(tween(150))
                }
            },
            label = "screens",
        ) { screen ->
            saved.SaveableStateProvider(screen.toString()) {
                when (screen) {
                    Screen.Library -> LibraryScreen(vm)
                    is Screen.PlaylistDetail -> PlaylistScreen(vm, screen.key)
                    Screen.NowPlaying -> NowPlayingScreen(vm.player, onClose = { vm.back() })
                    Screen.Sync -> SyncScreen(vm)
                }
            }
        }
    }
}

private fun formatLong(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60}H ${minutes % 60}M" else "$minutes MIN"
}

@Composable
private fun LibraryScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val songs = vm.songs
    val filtered = remember(songs, query) {
        if (query.isBlank()) songs
        else songs.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) || it.folder.contains(query, true)
        }
    }
    val stats = remember(songs, vm.playlists) {
        "${songs.size} tracks · ${vm.playlists.size} tapes · ${formatLong(songs.sumOf { it.durationMs })}".uppercase()
    }

    Scaffold(
        containerColor = Tape.Ink,
        bottomBar = { MiniPlayer(vm.player) { vm.open(Screen.NowPlaying) } },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searching) {
                    SearchField(query, { query = it }, Modifier.weight(1f))
                } else {
                    Column(Modifier.weight(1f)) {
                        Text("MIXTAPE", style = MaterialTheme.typography.displaySmall, color = Tape.Orange)
                        Text(stats, style = MaterialTheme.typography.labelMedium, color = Tape.Dust)
                    }
                }
                IconButton(onClick = {
                    searching = !searching
                    if (!searching) query = ""
                }) {
                    Icon(
                        if (searching) Icons.Rounded.Close else Icons.Rounded.Search,
                        contentDescription = if (searching) "Close search" else "Search",
                        tint = Tape.Cream,
                    )
                }
                IconButton(onClick = { vm.open(Screen.Sync) }) {
                    Icon(Icons.Rounded.Wifi, contentDescription = "Sync from PC", tint = Tape.Cream)
                }
            }
            if (!searching && songs.isNotEmpty()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill("Songs", tab == 0) { tab = 0 }
                    Pill("Playlists", tab == 1) { tab = 1 }
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
            when {
                !vm.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Tape.Orange)
                }
                songs.isEmpty() -> EmptyLibrary(onSync = { vm.open(Screen.Sync) })
                searching || tab == 0 -> SongList(filtered, vm.player, searching)
                else -> PlaylistGrid(vm.playlists, vm.player) { vm.open(Screen.PlaylistDetail(it.key)) }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Songs, artists, playlists", color = Tape.Dust) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Tape.Dust) },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Tape.DeckHigh,
            unfocusedContainerColor = Tape.DeckHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Tape.Orange,
            focusedTextColor = Tape.Cream,
            unfocusedTextColor = Tape.Cream,
        ),
        modifier = modifier.focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun SongList(songs: List<Song>, player: PlayerUi, searching: Boolean) {
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searching) {
                    Text("${songs.size} RESULTS", style = MaterialTheme.typography.labelMedium, color = Tape.Dust)
                } else {
                    Button(onClick = { player.shuffleAll(songs) }) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("SHUFFLE ALL")
                    }
                }
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongRow(song, current = song.mediaId == player.mediaId, playing = player.isPlaying) { player.play(songs, i) }
        }
    }
}

@Composable
private fun PlaylistGrid(playlists: List<Playlist>, player: PlayerUi, onOpen: (Playlist) -> Unit) {
    val currentFolder = player.metadata.extras?.getString(Song.EXTRA_FOLDER)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(playlists, key = { it.key }) { pl ->
            Column(
                Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onOpen(pl) }
                    .padding(4.dp)
            ) {
                PlaylistTape(pl, player, current = currentFolder == pl.name, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(pl.name, style = MaterialTheme.typography.bodyLarge, color = Tape.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${songCount(pl.songs.size)} · ${formatLong(pl.durationMs)}".uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Tape.Dust,
                )
            }
        }
    }
}

/** A playlist drawn as its cassette; if it's the one playing, the reels turn with the current song. */
@Composable
private fun PlaylistTape(
    pl: Playlist,
    player: PlayerUi,
    current: Boolean,
    modifier: Modifier,
    footLeft: String = "",
    footRight: String = "",
) {
    val pos = if (current) rememberPlaybackPosition(player) else 0L
    val progress = if (current && player.durationMs > 0) pos.toFloat() / player.durationMs else 0f
    Cassette(
        label = pl.name,
        labelColor = Tape.labelColor(pl.name),
        modifier = modifier,
        progress = progress,
        spinning = current && player.isPlaying,
        art = pl.songs.first().uri,
        footLeft = footLeft,
        footRight = footRight,
    )
}

@Composable
private fun PlaylistScreen(vm: MainViewModel, key: String) {
    val pl = vm.playlist(key)
    val player = vm.player
    Scaffold(
        containerColor = Tape.Ink,
        bottomBar = { MiniPlayer(player) { vm.open(Screen.NowPlaying) } },
    ) { pad ->
        if (pl == null) {
            Box(
                Modifier
                    .padding(pad)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("This playlist is gone from the phone.", color = Tape.Dust)
            }
            return@Scaffold
        }
        val current = player.metadata.extras?.getString(Song.EXTRA_FOLDER) == pl.name
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
            item(key = "header") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    IconButton(onClick = { vm.back() }, modifier = Modifier.offset(x = (-12).dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Tape.Cream)
                    }
                    PlaylistTape(
                        pl, player, current,
                        Modifier
                            .fillMaxWidth(0.84f)
                            .align(Alignment.CenterHorizontally),
                        footLeft = "SIDE A",
                        footRight = songCount(pl.songs.size).uppercase(),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(pl.name, style = MaterialTheme.typography.headlineSmall, color = Tape.Cream)
                    Text(
                        "${songCount(pl.songs.size)} · ${formatLong(pl.durationMs)}".uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Tape.Dust,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { player.playInOrder(pl.songs) }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("PLAY")
                        }
                        OutlinedButton(onClick = { player.shuffleAll(pl.songs) }) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("SHUFFLE")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            itemsIndexed(pl.songs, key = { _, s -> s.id }) { i, song ->
                SongRow(
                    song,
                    current = song.mediaId == player.mediaId,
                    playing = player.isPlaying,
                    number = i + 1,
                ) { player.play(pl.songs, i) }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onSync: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cassette("Blank tape", Tape.Mustard, Modifier.fillMaxWidth(0.8f), footLeft = "C-60", footRight = "SIDE A")
        Spacer(Modifier.height(28.dp))
        Text("NO MUSIC YET", style = MaterialTheme.typography.titleLarge, color = Tape.Cream)
        Spacer(Modifier.height(8.dp))
        Text(
            "Import a YouTube playlist with import.bat on your PC, then sync it here over Wi-Fi. " +
                "MP3s copied into the phone's Music folder show up too.",
            textAlign = TextAlign.Center,
            color = Tape.Dust,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSync) { Text("SYNC FROM PC") }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    val context = LocalContext.current
    Scaffold(containerColor = Tape.Ink) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Cassette("Mixtape", Tape.Orange, Modifier.fillMaxWidth(0.8f), footLeft = "C-90", footRight = "SIDE A")
            Spacer(Modifier.height(28.dp))
            Text("LET'S FIND YOUR MUSIC", style = MaterialTheme.typography.titleLarge, color = Tape.Cream)
            Spacer(Modifier.height(8.dp))
            Text(
                "Mixtape plays the MP3 files stored on this phone, so it needs permission to read them.",
                textAlign = TextAlign.Center,
                color = Tape.Dust,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("ALLOW ACCESS") }
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                )
            }) { Text("OPEN APP SETTINGS", color = Tape.Dust) }
        }
    }
}

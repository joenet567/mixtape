package com.joenet.mixtape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.PlayCtx
import com.joenet.mixtape.Route
import com.joenet.mixtape.TapeHit
import com.joenet.mixtape.TapeRef
import com.joenet.mixtape.formatLong
import com.joenet.mixtape.songCount

@Composable
fun LibraryScreen(vm: MainViewModel) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val lib = vm.library
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Library", style = MaterialTheme.typography.headlineMedium, color = Tape.Fg, modifier = Modifier.weight(1f))
            // One tap flips explicit Light <-> Dark (System stays available in Settings).
            IconButton(onClick = { vm.setThemeMode(if (Tape.isDark) ThemeMode.Light else ThemeMode.Dark) }) {
                Icon(
                    if (Tape.isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    contentDescription = if (Tape.isDark) "Switch to light theme" else "Switch to dark theme",
                    tint = Tape.Fg,
                )
            }
            IconButton(onClick = { vm.open(Route.Settings) }) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = Tape.Fg)
            }
        }
        if (lib.songs.isNotEmpty()) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill("Tapes", section == 0) { section = 0 }
                Pill("Artists", section == 1) { section = 1 }
                Pill("Songs", section == 2) { section = 2 }
            }
        }
        when {
            !vm.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Tape.Fg)
            }
            lib.songs.isEmpty() -> EmptyLibrary(onSync = { vm.open(Route.Sync) })
            section == 0 -> TapesGrid(vm)
            section == 1 -> ArtistList(vm)
            else -> AllSongs(vm)
        }
    }
}

@Composable
private fun TapesGrid(vm: MainViewModel) {
    val tapes = vm.libraryTapes()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        // the last row scrolls clear of the floating nav bar and mini player
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp + LocalChromeInset.current),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "get") {
            GetNewSongsRow { vm.open(Route.Sync) }
        }
        item(key = "record") { RecordTapeTile(vm) }
        items(tapes, key = { it.ref.toString() }) { hit ->
            TapeCard(vm, hit, Modifier.fillMaxWidth())
        }
    }
}

/**
 * A blank tape: record your own mixtape. Same footprint as a [TapeCard] cover: a square tile with a dashed
 * outline, a plus in the middle and a small blank cassette badge in the corner.
 */
@Composable
private fun RecordTapeTile(vm: MainViewModel) {
    var naming by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Record a new tape") { naming = true }
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    drawRoundRect(
                        color = Tape.Hairline,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(16.dp.toPx()),
                        style = Stroke(
                            width = stroke,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())),
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Tape.Fg),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, contentDescription = null, tint = Tape.Bg) }
            TapeBadge(
                Tape.SurfaceHigh,
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 10.dp)
                    .fillMaxWidth(0.38f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Record a tape", style = MaterialTheme.typography.titleSmall, color = Tape.Fg)
    }
    if (naming) {
        TapeEditorDialog(
            title = "Record a tape",
            confirm = "Record",
            initialColor = Tape.labels.indices.random(),
            onDismiss = { naming = false },
            onConfirm = { name, color ->
                naming = false
                vm.createTape(name, color)
            },
        )
    }
}

/** "Get new songs": sync lives in the library, where new music arrives. */
@Composable
private fun GetNewSongsRow(onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            // in-content glass: this screen is the glass source, so no backdrop (flat translucent fill + rim)
            .glass(shape, backdrop = null, strength = GlassStrength.Thin, elevation = 0.dp)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Wifi, contentDescription = null, tint = Tape.Fg)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp)
        ) {
            Text("Get new songs", style = MaterialTheme.typography.titleMedium, color = Tape.Fg)
            Text("Sync from your PC over Wi-Fi", style = MaterialTheme.typography.bodySmall, color = Tape.FgMuted)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Tape.FgMuted)
    }
}

/**
 * A tape in a grid or on a shelf: the cover with the small cassette badge, then the tape's name and
 * "N songs, X min" as normal text. The whole card is one TalkBack element named after the tape.
 */
@Composable
fun TapeCard(vm: MainViewModel, hit: TapeHit, modifier: Modifier = Modifier, width: Dp? = null) {
    Column(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Open ${hit.name}") { vm.open(Route.Tape(hit.ref)) }
            .semantics(mergeDescendants = true) { contentDescription = hit.name }
            .padding(4.dp)
    ) {
        TapeCover(vm, hit, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            hit.name,
            style = MaterialTheme.typography.titleSmall,
            color = Tape.Fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${songCount(hit.songs.size)}, ${formatLong(hit.songs.sumOf { it.durationMs })}",
            style = MaterialTheme.typography.bodySmall,
            color = Tape.FgMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A tape as its cover art with the small cassette badge in the corner. If it's the tape playing, the badge's
 * reels turn and its tape packs follow the current song.
 */
@Composable
fun TapeCover(
    vm: MainViewModel,
    hit: TapeHit,
    modifier: Modifier = Modifier,
    tapeFraction: Float = 0.38f,
    px: Int = 512,
    corner: Dp = 16.dp,
) {
    val player = vm.player
    val current = vm.isPlayingFrom(hit)
    val pos = if (current) rememberPlaybackPosition(player) else 0L
    val progress = if (current && player.durationMs > 0) pos.toFloat() / player.durationMs else 0f
    CoverWithTape(
        art = hit.songs.firstOrNull()?.uri,
        labelColor = vm.labelColor(hit),
        seed = hit.name,
        modifier = modifier,
        progress = progress,
        spinning = current && player.isPlaying,
        tapeFraction = tapeFraction,
        px = px,
        corner = corner,
    )
}

@Composable
private fun ArtistList(vm: MainViewModel) {
    val artists = vm.library.artists.entries.toList()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp + LocalChromeInset.current)) {
        items(artists, key = { it.key }) { (name, songs) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.open(Route.Artist(name)) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtistAvatar(name, 48.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                ) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, color = Tape.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(songCount(songs.size), style = MaterialTheme.typography.bodyMedium, color = Tape.FgMuted)
                }
            }
        }
    }
}

@Composable
private fun AllSongs(vm: MainViewModel) {
    val songs = vm.songs
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp + LocalChromeInset.current)) {
        item(key = "header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { vm.player.shuffleAll(songs, PlayCtx.ALL) }) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Shuffle all")
                }
                Spacer(Modifier.weight(1f))
                Text(songCount(songs.size), style = MaterialTheme.typography.bodyMedium, color = Tape.FgMuted)
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongRow(
                song,
                current = song.mediaId == vm.player.mediaId,
                playing = vm.player.isPlaying,
                onLongClick = { vm.actionsFor = song },
            ) { vm.player.play(songs, i, PlayCtx.ALL) }
        }
    }
}

@Composable
fun EmptyLibrary(onSync: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cassette("Blank tape", Tape.Mustard, Modifier.fillMaxWidth(0.8f), footLeft = "C-60", footRight = "SIDE A")
        Spacer(Modifier.height(28.dp))
        Text("No music yet", style = MaterialTheme.typography.titleLarge, color = Tape.Fg)
        Spacer(Modifier.height(8.dp))
        Text(
            "Import a YouTube playlist with import.bat on your PC, then get the songs here over Wi-Fi. " +
                "MP3s copied into the phone's Music folder show up too.",
            textAlign = TextAlign.Center,
            color = Tape.FgMuted,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSync) { Text("Get new songs") }
    }
}

package com.joenet.mixtape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.R
import com.joenet.mixtape.Route
import com.joenet.mixtape.Song
import com.joenet.mixtape.TapeRef

/** The sheet's own shape (the Material default, spelled out so the glass rim can match it exactly). */
private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 0.dp, bottomStart = 0.dp)

/** Long-press on any song: its actions, in a sheet styled like a tape insert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionsSheet(vm: MainViewModel, song: Song, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val done = { onDismiss() }
    // The sheet lives in its own window and cannot blur the app behind it, so it is a near-opaque glass tint plus a
    // rim highlight. The drag handle is drawn inside the rim container (dragHandle = null) so the highlight runs
    // along the sheet's real top edge; the whole sheet still drags.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = SheetShape,
        containerColor = glassContainerColor(),
        contentColor = Tape.Fg,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .glassRim(SheetShape)
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 18.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Tape.FgMuted.copy(alpha = 0.55f)),
            )
            InsertHeader(song)
            Spacer(Modifier.height(6.dp))
            ActionRow(rememberVectorPainter(Icons.AutoMirrored.Rounded.PlaylistPlay), "Play next") {
                vm.player.playNext(song)
                vm.notify("Plays next")
                done()
            }
            ActionRow(rememberVectorPainter(Icons.AutoMirrored.Rounded.QueueMusic), "Add to queue") {
                vm.player.addToQueue(song)
                vm.notify("Added to queue")
                done()
            }
            ActionRow(rememberVectorPainter(Icons.Rounded.Radio), "Start radio") {
                vm.startRadio(song)
                done()
            }
            val liked = vm.isLiked(song)
            ActionRow(
                rememberVectorPainter(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder),
                if (liked) "Remove from Liked songs" else "Like",
            ) {
                vm.toggleLike(song)
                done()
            }
            ActionRow(rememberVectorPainter(Icons.AutoMirrored.Rounded.PlaylistAdd), "Add to tape…") {
                vm.addToTape = listOf(song)
                done()
            }
            for (artist in song.artists) {
                ActionRow(
                    rememberVectorPainter(Icons.Rounded.Person),
                    if (song.artists.size == 1) "Go to artist" else "Go to $artist",
                ) {
                    vm.open(Route.Artist(artist))
                    done()
                }
            }
            ActionRow(painterResource(R.drawable.ic_cassette), "Go to tape") {
                vm.open(Route.Tape(TapeRef.Folder(song.tapeKey)))
                done()
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** A strip of J-card: the cover and the song printed on ruled paper (the paper is physical, so it never changes with the theme). */
@Composable
private fun InsertHeader(song: Song) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Tape.Paper)
            .drawBehind {
                // Rules sit on the 26dp line boxes of the two text lines below (row padding 12dp + 6dp centring).
                val rule = Color(0xFFCDBFA6)
                var y = 18.dp.toPx()
                while (y < size.height) {
                    drawLine(rule, Offset(92.dp.toPx(), y), Offset(size.width - 12.dp.toPx(), y), strokeWidth = 1.dp.toPx())
                    y += 26.dp.toPx()
                }
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongArt(song.uri, Modifier.size(64.dp), corner = 4.dp, seed = song.folder)
        Column(Modifier.padding(start = 16.dp)) {
            Text(
                song.title,
                fontFamily = Mix,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                color = Tape.PaperInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                fontFamily = Mix,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 26.sp,
                color = Tape.PaperInk.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ActionRow(icon: Painter, label: String, tint: Color = Tape.Fg, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Tape.Fg, modifier = Modifier.padding(start = 20.dp))
    }
}

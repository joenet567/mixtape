package com.joenet.mixtape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.Song
import com.joenet.mixtape.songCount

private val colorNames = listOf("Orange", "Mustard", "Teal", "Brick", "Sage", "Sky")

/** Name a tape by writing on its label, and pick the label colour. Used for new tapes and renames. */
@Composable
fun TapeEditorDialog(
    title: String,
    confirm: String,
    initialName: String = "",
    initialColor: Int = 0,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: Int) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var color by rememberSaveable { mutableIntStateOf(initialColor) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tape.Deck,
        title = { Text(title, color = Tape.Cream) },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), color) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Tape.Dust) } },
        text = {
            Column {
                // the label: paper with a coloured band, written on in biro
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Tape.Paper)
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it.take(60) },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = Marker, fontSize = 34.sp, color = Tape.PaperInk),
                        cursorBrush = SolidColor(Tape.PaperInk),
                        decorationBox = { inner ->
                            Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                if (name.isEmpty()) {
                                    Text("Name your tape", fontFamily = Marker, fontSize = 34.sp, color = Tape.PaperInk.copy(alpha = 0.35f))
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .semantics { contentDescription = "Tape name" },
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .background(Tape.labels[color])
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tape.labels.forEachIndexed { i, c ->
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(c)
                                .then(if (i == color) Modifier.border(3.dp, Tape.Cream, CircleShape) else Modifier)
                                .clickable { color = i }
                                .semantics {
                                    role = Role.RadioButton
                                    selected = i == color
                                    contentDescription = "${colorNames[i]} label"
                                }
                        )
                    }
                }
            }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** "Add to tape…": pick one of your tapes, or record a new one with these songs on it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToTapeSheet(vm: MainViewModel, songs: List<Song>, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Tape.Deck) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                if (songs.size == 1) "Add “${songs.first().title}” to a tape" else "Add ${songCount(songs.size)} to a tape",
                style = MaterialTheme.typography.titleLarge,
                color = Tape.Cream,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { creating = true }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(width = 56.dp, height = 36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Tape.DeckHigh),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Add, contentDescription = null, tint = Tape.Cream) }
                        Text("Record a new tape", color = Tape.Cream, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                items(vm.userTapes, key = { it.tape.id }) { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.addSongsToTape(t.tape.id, songs)
                                onDismiss()
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val hit = vm.userTapeHit(t)
                        Box(Modifier.width(56.dp)) { TapeCassette(vm, hit, Modifier.fillMaxWidth()) }
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(t.tape.name, color = Tape.Cream, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(songCount(t.tracks.size), color = Tape.Dust, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
    if (creating) {
        TapeEditorDialog(
            title = "Record a tape",
            confirm = "Record",
            initialColor = Tape.labels.indices.random(),
            onDismiss = { creating = false },
            onConfirm = { name, color ->
                vm.createTape(name, color, songs, openIt = false)
                creating = false
                onDismiss()
            },
        )
    }
}

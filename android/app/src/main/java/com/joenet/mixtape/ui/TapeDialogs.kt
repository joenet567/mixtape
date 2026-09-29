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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.Song
import com.joenet.mixtape.songCount

private val colorNames = listOf("Orange", "Mustard", "Teal", "Brick", "Sage", "Sky")

/** Name a tape (typed onto its paper label) and pick the label colour. Used for new tapes and renames. */
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
    val dialogShape = RoundedCornerShape(28.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.glassRim(dialogShape),
        shape = dialogShape,
        containerColor = glassContainerColor(),
        title = { Text(title, color = Tape.Fg) },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), color) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Tape.FgMuted) } },
        text = {
            Column {
                // the label: paper with a coloured band (fixed colours, like the physical tape)
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
                        textStyle = TextStyle(fontFamily = Mix, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Tape.PaperInk),
                        cursorBrush = SolidColor(Tape.PaperInk),
                        decorationBox = { inner ->
                            Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                if (name.isEmpty()) {
                                    Text("Name your tape", fontFamily = Mix, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Tape.PaperInk.copy(alpha = 0.35f))
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
                                .then(if (i == color) Modifier.border(3.dp, Tape.Fg, CircleShape) else Modifier)
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
    // The rim and the drag handle are drawn inside the sheet by GlassSheetBody (which also applies the navigation-bar
    // padding): a rim passed through ModalBottomSheet's modifier would be drawn at the un-offset position, not on the sheet.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = GlassSheetShape,
        containerColor = glassContainerColor(),
        dragHandle = null,
    ) {
        GlassSheetBody {
            Text(
                if (songs.size == 1) "Add “${songs.first().title}” to a tape" else "Add ${songCount(songs.size)} to a tape",
                style = MaterialTheme.typography.titleLarge,
                color = Tape.Fg,
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
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Tape.SurfaceHigh),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Add, contentDescription = null, tint = Tape.Fg) }
                        Text("Record a new tape", color = Tape.Fg, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
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
                        // cover preview with a bigger cassette badge, so the tape's colour still reads at 56.dp
                        TapeCover(vm, hit, Modifier.width(56.dp), tapeFraction = 0.5f, px = 192, corner = 8.dp)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(t.tape.name, color = Tape.Fg, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(songCount(t.tracks.size), color = Tape.FgMuted, style = MaterialTheme.typography.bodyMedium)
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

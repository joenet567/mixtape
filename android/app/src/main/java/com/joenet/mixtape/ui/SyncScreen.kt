package com.joenet.mixtape.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.SyncManager
import com.joenet.mixtape.SyncManager.State
import com.joenet.mixtape.songCount

@Composable
fun SyncScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val state by SyncManager.state.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(SyncManager.savedAddress(context)) }
    val busy = state is State.Working || state is State.Searching

    // Stay awake while copying; the sync itself lives outside this screen and survives leaving it.
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { SyncManager.reset() } }

    val working = state as? State.Working
    val tapeProgress = when {
        working != null && working.total > 0 -> (working.done + working.fileFraction) / working.total
        state is State.Finished -> 1f
        else -> 0f
    }

    Scaffold(containerColor = Tape.Ink) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            IconButton(onClick = { vm.back() }, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Tape.Cream)
            }
            Text("SYNC FROM PC", style = MaterialTheme.typography.displaySmall, color = Tape.Orange)
            Text("PULL NEW SONGS OVER WI-FI", style = MaterialTheme.typography.labelMedium, color = Tape.Dust)
            Spacer(Modifier.height(20.dp))
            Cassette(
                label = "From my PC",
                labelColor = Tape.Teal,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .align(Alignment.CenterHorizontally),
                progress = tapeProgress,
                spinning = busy,
                footLeft = if (working != null) "● REC" else "SIDE A",
                footRight = working?.takeIf { it.total > 0 }?.let { "${it.done}/${it.total}" }.orEmpty(),
            )
            Spacer(Modifier.height(24.dp))
            Step("01", "Run sync.bat on your PC (music-player\\importer).")
            Step("02", "Keep this phone on the same Wi-Fi as the PC.")
            Step("03", "Tap Find PC (or type the address sync.bat shows), then Sync now.")
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("PC address") },
                placeholder = { Text("192.168.1.20:${SyncManager.DEFAULT_PORT}") },
                singleLine = true,
                enabled = !busy,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Tape.Orange,
                    unfocusedBorderColor = Tape.Line,
                    focusedLabelColor = Tape.Orange,
                    cursorColor = Tape.Orange,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { SyncManager.findPc(context) { address = it } }, enabled = !busy) {
                    Text("FIND PC")
                }
                Button(onClick = { SyncManager.sync(context, address) { vm.reloadLibrary() } }, enabled = !busy) {
                    Text("SYNC NOW")
                }
            }
            Spacer(Modifier.height(16.dp))
            StatusCard(state)
            Spacer(Modifier.height(12.dp))
            Text(
                "New songs go to Music/<playlist name>/. Songs you already have are skipped, " +
                    "and nothing on the phone is ever deleted.",
                style = MaterialTheme.typography.bodySmall,
                color = Tape.Dust,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(number, fontFamily = Mono, fontSize = 14.sp, color = Tape.Orange, modifier = Modifier.width(34.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Tape.Cream)
    }
}

@Composable
private fun StatusCard(state: State) {
    if (state == State.Idle) return
    val isError = state is State.Failed
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else Tape.Deck,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                State.Idle -> Unit
                State.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Tape.Orange)
                    Spacer(Modifier.width(12.dp))
                    Text("Looking for your PC…", color = Tape.Cream)
                }
                is State.Working -> {
                    if (state.total > 0) {
                        Text(
                            "RECORDING ${state.done + 1} OF ${state.total}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Tape.Orange,
                        )
                        LinearProgressIndicator(
                            progress = { ((state.done + state.fileFraction) / state.total).coerceIn(0f, 1f) },
                            color = Tape.Orange,
                            trackColor = Tape.Line,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tape.Orange, trackColor = Tape.Line)
                    }
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = Tape.Cream, maxLines = 2)
                    TextButton(onClick = SyncManager::cancel) { Text("CANCEL") }
                }
                is State.Finished -> {
                    Text(
                        when (state.added) {
                            0 -> "ALREADY UP TO DATE"
                            1 -> "ADDED 1 NEW SONG"
                            else -> "ADDED ${state.added} NEW SONGS"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = Tape.Orange,
                    )
                    if (state.alreadyHad > 0) {
                        Text(
                            "${songCount(state.alreadyHad)} ${if (state.alreadyHad == 1) "was" else "were"} already on the phone.",
                            color = Tape.Cream,
                        )
                    }
                    if (state.failed.isNotEmpty()) {
                        Text("${state.failed.size} failed:", color = MaterialTheme.colorScheme.error)
                        state.failed.take(20).forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall, color = Tape.Dust)
                        }
                    }
                }
                is State.Failed -> Text(state.message, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

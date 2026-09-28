package com.joenet.mixtape.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.Route

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val equaliser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    var license by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        BackRow(vm)
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = Tape.Cream,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        SettingsHeader("Music")
        SettingsRow("Get new songs", "Sync from your PC over Wi-Fi") { vm.open(Route.Sync) }
        SettingsSwitch(
            "Sync automatically",
            "Get new songs from your PC while the phone charges on Wi-Fi",
            checked = vm.autoSync,
            onChange = vm::updateAutoSync,
        )
        SettingsHeader("Playback")
        SettingsSwitch(
            "Even out loudness",
            "Turn loud songs down so everything plays at about the same volume",
            checked = vm.evenLoudness,
            onChange = vm::updateEvenLoudness,
        )
        SettingsRow("Equaliser", "Your phone's sound settings, applied to Mixtape") {
            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, vm.player.audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            try {
                equaliser.launch(intent) // for-result: equaliser apps close when they can't see who called
            } catch (e: ActivityNotFoundException) {
                vm.notify("This phone has no built-in equaliser")
            }
        }
        SettingsHeader("About")
        SettingsRow("Reenie Beanie font", "SIL Open Font License") { license = "ReenieBeanie-OFL.txt" }
        SettingsRow("Barlow Condensed font", "SIL Open Font License") { license = "BarlowCondensed-OFL.txt" }
    }
    license?.let { file -> LicenseDialog(file) { license = null } }
}

@Composable
fun SettingsHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Tape.Dust,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun SettingsRow(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Tape.Cream)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tape.Dust)
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingsSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Tape.Cream)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tape.Dust)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun LicenseDialog(file: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(file) {
        runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }.getOrDefault("")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(file.substringBefore('-')) },
        text = {
            Text(
                text,
                fontSize = 12.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        containerColor = Tape.Deck,
    )
}

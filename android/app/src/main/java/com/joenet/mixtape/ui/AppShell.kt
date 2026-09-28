package com.joenet.mixtape.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.joenet.mixtape.MainViewModel
import com.joenet.mixtape.Route
import com.joenet.mixtape.Tab
import kotlinx.coroutines.launch

/**
 * The app: the current tab's screen, the mini player and the deck's function selector at the
 * bottom, and the Now Playing sheet that slides up over everything (following the finger).
 */
@Composable
fun MixtapeApp(vm: MainViewModel, onRequestPermission: () -> Unit) {
    val player = vm.player
    BackHandler(enabled = vm.canGoBack) { vm.back() }

    if (!vm.hasPermission && vm.route != Route.Sync) {
        PermissionScreen(onRequestPermission)
        return
    }

    val saved = rememberSaveableStateHolder() // keeps each screen's scroll / tab state across navigation
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Tape.Ink)
    ) {
        val fullHeight = constraints.maxHeight.toFloat()
        // 0 = Now Playing fully open, 1 = collapsed into the mini player
        val sheet = remember { Animatable(if (vm.playerExpanded) 0f else 1f) }
        val still = rememberReduceMotion()
        LaunchedEffect(vm.playerExpanded) {
            sheet.animateTo(if (vm.playerExpanded) 0f else 1f, tween(if (still) 0 else 320, easing = FastOutSlowInEasing))
        }
        val onSheetDrag: (Float) -> Unit = { dy ->
            scope.launch { sheet.snapTo((sheet.value + dy / fullHeight).coerceIn(0f, 1f)) }
        }
        val onSheetRelease: (Float) -> Unit = { vy ->
            val open = when {
                vy < -900f -> true
                vy > 900f -> false
                else -> sheet.value < 0.5f
            }
            if (open == vm.playerExpanded) {
                scope.launch { sheet.animateTo(if (open) 0f else 1f, tween(if (still) 0 else 240)) }
            } else {
                vm.playerExpanded = open
            }
        }

        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .statusBarsPadding()
            ) {
                AnimatedContent(
                    targetState = vm.tab to vm.route,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(110)) },
                    label = "screens",
                ) { (tab, route) ->
                    saved.SaveableStateProvider("$tab/$route") { ScreenFor(vm, tab, route) }
                }
                vm.notice?.let {
                    NoticePill(
                        it,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                    )
                }
            }
            MiniPlayer(
                player,
                onOpen = { vm.playerExpanded = true },
                onSheetDrag = onSheetDrag,
                onSheetRelease = onSheetRelease,
                modifier = Modifier.graphicsLayer { alpha = sheet.value },
            )
            DeckNavBar(vm.tab, vm::select)
        }

        if (player.hasSong && (sheet.value < 0.999f || sheet.isRunning)) {
            NowPlayingScreen(
                vm,
                onClose = { vm.playerExpanded = false },
                onSheetDrag = onSheetDrag,
                onSheetRelease = onSheetRelease,
                modifier = Modifier.graphicsLayer { translationY = sheet.value * fullHeight },
            )
        }

        vm.actionsFor?.let { SongActionsSheet(vm, it, onDismiss = { vm.actionsFor = null }) }
        vm.addToTape?.let { AddToTapeSheet(vm, it, onDismiss = { vm.addToTape = null }) }
    }
}

@Composable
private fun ScreenFor(vm: MainViewModel, tab: Tab, route: Route) {
    when (route) {
        Route.Root -> when (tab) {
            Tab.Home -> HomeScreen(vm)
            Tab.Search -> SearchScreen(vm)
            Tab.Library -> LibraryScreen(vm)
        }
        is Route.Tape -> TapeScreen(vm, route.ref)
        is Route.Artist -> ArtistScreen(vm, route.name)
        Route.Sync -> SyncScreen(vm)
        Route.Settings -> SettingsScreen(vm)
        Route.Rewind -> RewindScreen(vm)
    }
}

@Composable
private fun NoticePill(text: String, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = Tape.Cream, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = Tape.Ink,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Tape.Ink)
            .statusBarsPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cassette("Mixtape", Tape.Orange, Modifier.fillMaxWidth(0.8f), footLeft = "C-90", footRight = "SIDE A")
        Spacer(Modifier.height(28.dp))
        Text("Let's find your music", style = MaterialTheme.typography.titleLarge, color = Tape.Cream)
        Spacer(Modifier.height(8.dp))
        Text(
            "Mixtape plays the MP3 files stored on this phone, so it needs permission to read them.",
            textAlign = TextAlign.Center,
            color = Tape.Dust,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Allow access") }
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            )
        }) { Text("Open app settings", color = Tape.Dust) }
    }
}

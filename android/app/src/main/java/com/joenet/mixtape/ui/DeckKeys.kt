package com.joenet.mixtape.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The transport as one bank of joined piano keys, like a tape deck:
 *
 *   | ⇄ | ◀◀ |  ▶ ▮▮  | ▶▶ | ⟲ |
 *              ●  <- orange light while playing
 *
 * Play stays pressed down while music plays; shuffle and repeat latch down while on.
 * Tap ◀◀ / ▶▶ to skip, hold them to scrub (cue / review).
 */

private val Steel = Color(0xFFBDB6AB)
private val SteelDark = Color(0xFF8E877C)
private val SteelLip = Color(0xFF5F584F)
private val CreamFace = Color(0xFFEDE3D1)
private val CreamDark = Color(0xFFD6CAB4)
private val CreamLip = Color(0xFF9C8F7A)
private val Bezel = Color(0xFF0D0B09)
private val LegendInk = Color(0xFF2A231D)

private val KeyHeight = 64.dp
private val LipUp = 7.dp
private val LipDown = 4.dp

@Composable
fun DeckKeys(
    playing: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onScrub: (forward: Boolean) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // The bezel the keys sit in; keys touch each other, separated only by a hairline.
        Row(
            Modifier
                .fillMaxWidth()
                .height(KeyHeight + 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Bezel)
                .padding(3.dp),
        ) {
            ToggleKey(
                icon = Icons.Rounded.Shuffle,
                label = "Shuffle",
                on = shuffle,
                state = if (shuffle) "On" else "Off",
                onToggle = onShuffle,
            )
            TransportKey(Icons.Rounded.FastRewind, "Previous", forward = false, onTap = onPrevious, onScrub = onScrub)
            PlayKey(playing, onPlayPause)
            TransportKey(Icons.Rounded.FastForward, "Next", forward = true, onTap = onNext, onScrub = onScrub)
            ToggleKey(
                icon = Icons.Rounded.Repeat,
                label = "Repeat",
                on = repeatMode != Player.REPEAT_MODE_OFF,
                state = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Off"
                },
                stamp = if (repeatMode == Player.REPEAT_MODE_ONE) "1" else null,
                onToggle = onRepeat,
            )
        }
        // The play light: under the play key, lit while music plays.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Box(Modifier.weight(2f))
            Box(Modifier.weight(1.6f), contentAlignment = Alignment.Center) { PlayLight(playing) }
            Box(Modifier.weight(2f))
        }
    }
}

@Composable
private fun PlayLight(on: Boolean) {
    Canvas(Modifier.size(width = 22.dp, height = 8.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        if (on) {
            drawCircle(Brush.radialGradient(listOf(Tape.Orange.copy(alpha = 0.55f), Color.Transparent), c, size.width / 2), size.width / 2, c)
            drawCircle(Tape.Orange, 3.dp.toPx(), c)
        } else {
            drawCircle(Color(0xFF3A2A20), 3.dp.toPx(), c)
        }
    }
}

@Composable
private fun RowScope.ToggleKey(
    icon: ImageVector,
    label: String,
    on: Boolean,
    state: String,
    onToggle: () -> Unit,
    stamp: String? = null,
) {
    val toggle by rememberUpdatedState(onToggle)
    KeyFace(
        weight = 1f,
        cream = false,
        latched = on,
        onPress = { toggle() },
        semantics = Modifier.clearAndSetSemantics {
            role = Role.Switch
            contentDescription = label
            stateDescription = state
            toggleableState = ToggleableState(on)
            onClick(label = "Change ${label.lowercase()}") { toggle(); true }
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = LegendInk, modifier = Modifier.size(24.dp))
            if (stamp != null) {
                Text(
                    stamp,
                    style = androidx.compose.ui.text.TextStyle(fontFamily = Barlow, fontSize = 11.sp, color = LegendInk),
                    modifier = Modifier.offset(x = 13.dp, y = (-11).dp),
                )
            }
        }
    }
}

@Composable
private fun RowScope.TransportKey(
    icon: ImageVector,
    label: String,
    forward: Boolean,
    onTap: () -> Unit,
    onScrub: (Boolean) -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val scrub by rememberUpdatedState(onScrub)
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var scrubbed by remember { mutableStateOf(false) }
    KeyFace(
        weight = 1f,
        cream = false,
        latched = false,
        onPress = {
            // Hold for cue / review: after a short delay, keep scrubbing until release.
            scrubbed = false
            holdJob = scope.launch {
                delay(350)
                scrubbed = true
                while (true) {
                    scrub(forward)
                    delay(110)
                }
            }
        },
        onRelease = { completed ->
            holdJob?.cancel()
            holdJob = null
            if (completed && !scrubbed) tap()
        },
        semantics = Modifier.clearAndSetSemantics {
            role = Role.Button
            contentDescription = label
            onClick(label = label) { tap(); true }
            customActions = listOf(
                CustomAccessibilityAction(if (forward) "Fast-forward 10 seconds" else "Rewind 10 seconds") {
                    repeat(5) { scrub(forward) }
                    true
                }
            )
        },
    ) {
        Icon(icon, contentDescription = null, tint = LegendInk, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun RowScope.PlayKey(playing: Boolean, onPlayPause: () -> Unit) {
    val press by rememberUpdatedState(onPlayPause)
    KeyFace(
        weight = 1.6f,
        cream = true,
        latched = playing,
        onPress = { press() },
        semantics = Modifier.clearAndSetSemantics {
            role = Role.Button
            contentDescription = if (playing) "Pause" else "Play"
            onClick(label = if (playing) "Pause" else "Play") { press(); true }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = LegendInk, modifier = Modifier.size(30.dp))
            Icon(Icons.Rounded.Pause, contentDescription = null, tint = LegendInk, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * One key: a face that sits on a darker front lip. Pressed (or latched) keys drop ~3dp and the lip
 * shrinks by the same amount, as on a real deck.
 * [onPress] fires on touch-down (tape decks act on the press, not the release).
 */
@Composable
private fun RowScope.KeyFace(
    weight: Float,
    cream: Boolean,
    latched: Boolean,
    onPress: () -> Unit,
    semantics: Modifier,
    onRelease: (completed: Boolean) -> Unit = {},
    legend: @Composable () -> Unit,
) {
    val view = LocalView.current
    var down by remember { mutableStateOf(false) }
    val pressed = down || latched
    val still = rememberReduceMotion()
    val lip by animateDpAsState(if (pressed) LipDown else LipUp, tween(if (still) 0 else 60), label = "lip")
    val face = if (cream) CreamFace else Steel
    val faceDark = if (cream) CreamDark else SteelDark
    val lipColor = if (cream) CreamLip else SteelLip
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)

    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .padding(horizontal = 0.5.dp)
            .then(semantics)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    down = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    press()
                    val completed = tryAwaitRelease()
                    down = false
                    release(completed)
                })
            },
    ) {
        val topGap = LipUp - lip // how far the face has dropped
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.height(topGap))
            // face
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(Brush.verticalGradient(listOf(face, faceDark))),
                contentAlignment = Alignment.Center,
            ) { legend() }
            // front lip
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(lip)
                    .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                    .background(lipColor)
            )
        }
    }
}


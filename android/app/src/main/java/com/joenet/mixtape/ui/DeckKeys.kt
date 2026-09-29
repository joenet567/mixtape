package com.joenet.mixtape.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The transport as one row of round glass keys:
 *
 *   ( ⇄ )  ( ◀◀ )  (  ▶  )  ( ▶▶ )  ( ⟲ )
 *                    ^ orange ring while playing
 *
 * The big play key carries the "live" light: an orange ring while music plays. Shuffle and repeat get a
 * filled disc and a brighter icon while on. Tap ◀◀ / ▶▶ to skip, hold them to scrub (cue / review).
 * The keys are glass on top of the glass panel that Now Playing puts them on, so they have no blur or
 * shadow of their own.
 */

private val PlayKeySize = 72.dp
private val SkipKeySize = 56.dp
private val ToggleKeySize = 48.dp

/** Width the five keys are laid out for at full size (they need 280.dp); narrower rows scale every key down so all five always fit. */
private val FullRowWidth = 300.dp

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
    BoxWithConstraints(modifier) {
        val k = (maxWidth / FullRowWidth).coerceIn(0.75f, 1f)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToggleKey(
                icon = Icons.Rounded.Shuffle,
                label = "Shuffle",
                on = shuffle,
                state = if (shuffle) "On" else "Off",
                onToggle = onShuffle,
                diameter = ToggleKeySize * k,
            )
            TransportKey(Icons.Rounded.FastRewind, "Previous", forward = false, onTap = onPrevious, onScrub = onScrub, diameter = SkipKeySize * k)
            PlayKey(playing, onPlayPause, diameter = PlayKeySize * k)
            TransportKey(Icons.Rounded.FastForward, "Next", forward = true, onTap = onNext, onScrub = onScrub, diameter = SkipKeySize * k)
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
                diameter = ToggleKeySize * k,
            )
        }
    }
}

@Composable
private fun ToggleKey(
    icon: ImageVector,
    label: String,
    on: Boolean,
    state: String,
    onToggle: () -> Unit,
    diameter: Dp,
    stamp: String? = null,
) {
    val toggle by rememberUpdatedState(onToggle)
    GlassKey(
        diameter = diameter,
        strength = GlassStrength.Thin,
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
            Icon(icon, contentDescription = null, tint = if (on) Tape.Fg else Tape.FgMuted, modifier = Modifier.size(diameter * 0.5f))
            if (stamp != null) {
                Text(
                    stamp,
                    style = TextStyle(fontFamily = Mix, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Tape.Fg),
                    modifier = Modifier.offset(x = 11.dp, y = (-10).dp),
                )
            }
        }
    }
}

@Composable
private fun TransportKey(
    icon: ImageVector,
    label: String,
    forward: Boolean,
    onTap: () -> Unit,
    onScrub: (Boolean) -> Unit,
    diameter: Dp,
) {
    val tap by rememberUpdatedState(onTap)
    val scrub by rememberUpdatedState(onScrub)
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var scrubbed by remember { mutableStateOf(false) }
    GlassKey(
        diameter = diameter,
        strength = GlassStrength.Regular,
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
        Icon(icon, contentDescription = null, tint = Tape.Fg, modifier = Modifier.size(diameter * 0.5f))
    }
}

@Composable
private fun PlayKey(playing: Boolean, onPlayPause: () -> Unit, diameter: Dp) {
    val press by rememberUpdatedState(onPlayPause)
    val still = rememberReduceMotion()
    val live by animateFloatAsState(if (playing) 1f else 0f, tween(if (still) 0 else 220), label = "playLight")
    GlassKey(
        diameter = diameter,
        strength = GlassStrength.Thick,
        latched = false,
        onPress = { press() },
        semantics = Modifier.clearAndSetSemantics {
            role = Role.Button
            contentDescription = if (playing) "Pause" else "Play"
            onClick(label = if (playing) "Pause" else "Play") { press(); true }
        },
    ) {
        // The play light: an orange ring (and a faint orange glow inside it) while music plays.
        Canvas(Modifier.fillMaxSize()) {
            val a = live
            if (a > 0f) {
                val stroke = 2.5.dp.toPx()
                drawCircle(Tape.Orange.copy(alpha = 0.12f * a))
                drawCircle(
                    Tape.Orange.copy(alpha = a),
                    radius = (this.size.minDimension - stroke) / 2f,
                    style = Stroke(stroke),
                )
            }
        }
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = Tape.Fg,
            modifier = Modifier.size(diameter * 0.5f),
        )
    }
}

/**
 * One round glass key. [onPress] fires on touch-down (tape decks act on the press, not the release); a
 * [latched] key shows a filled disc. The glass is [Modifier.glass] without a backdrop or shadow, since it
 * sits on Now Playing's glass panel; `interactive` gives the springy press feedback.
 */
@Composable
private fun GlassKey(
    diameter: Dp,
    strength: GlassStrength,
    latched: Boolean,
    onPress: () -> Unit,
    semantics: Modifier,
    onRelease: (completed: Boolean) -> Unit = {},
    legend: @Composable () -> Unit,
) {
    val view = LocalView.current
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)

    Box(
        Modifier
            .size(diameter)
            .glass(CircleShape, backdrop = null, strength = strength, elevation = 0.dp, interactive = true)
            .then(semantics)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    press()
                    val completed = tryAwaitRelease()
                    release(completed)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (latched) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Tape.Fg.copy(alpha = 0.18f))
            )
        }
        legend()
    }
}

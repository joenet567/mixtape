package com.joenet.mixtape.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joenet.mixtape.Tab

/**
 * The tab bar: a floating glass capsule with three equal tabs. A second, thinner glass capsule (the
 * "lens") slides behind the selected tab with a spring. The capsule floats over the screens, so
 * [MixtapeApp] places it (side margins, bottom inset) and provides the blur backdrop through
 * [LocalGlassBackdrop]; this composable adds no window-inset padding of its own.
 */
@Composable
fun DeckNavBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val still = rememberReduceMotion()
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = CircleShape,
        strength = GlassStrength.Regular,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            val slot = maxWidth / Tab.entries.size
            val lensX by animateDpAsState(
                targetValue = slot * selected.ordinal,
                animationSpec = if (still) {
                    snap<Dp>()
                } else {
                    spring<Dp>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                },
                label = "lens",
            )
            // the lens: glass on glass, so no blur and no shadow of its own
            Box(
                Modifier
                    .offset { IntOffset(lensX.roundToPx(), 0) }
                    .width(slot)
                    .fillMaxHeight()
                    .glass(CircleShape, backdrop = null, strength = GlassStrength.Thin, elevation = 0.dp)
            )
            Row(Modifier.fillMaxSize()) {
                for (t in Tab.entries) {
                    val on = t == selected
                    val tint by animateColorAsState(
                        targetValue = if (on) Tape.Fg else Tape.FgMuted,
                        animationSpec = tween(if (still) 0 else 200),
                        label = "tabTint",
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .selectable(selected = on, role = Role.Tab, onClick = { onSelect(t) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            when (t) {
                                Tab.Home -> Icons.Rounded.Home
                                Tab.Search -> Icons.Rounded.Search
                                Tab.Library -> Icons.Rounded.LibraryMusic
                            },
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            t.legend,
                            style = Legend.copy(fontSize = 11.sp),
                            color = tint,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

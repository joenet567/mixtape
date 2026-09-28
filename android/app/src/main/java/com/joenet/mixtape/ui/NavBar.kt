package com.joenet.mixtape.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joenet.mixtape.Tab

/**
 * The tab bar as the deck's function selector: printed legends, and a small raised slider that
 * moves along a groove to sit under the active function. No Material pill indicator.
 */
@Composable
fun DeckNavBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val still = rememberReduceMotion()
    Surface(color = Tape.Deck) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                for (t in Tab.entries) {
                    val on = t == selected
                    Column(
                        Modifier
                            .weight(1f)
                            .height(54.dp)
                            .selectable(selected = on, role = Role.Tab, onClick = { onSelect(t) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(
                            when (t) {
                                Tab.Home -> Icons.Rounded.Home
                                Tab.Search -> Icons.Rounded.Search
                                Tab.Library -> Icons.Rounded.LibraryMusic
                            },
                            contentDescription = null,
                            tint = if (on) Tape.Cream else Tape.Dust,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            t.legend,
                            style = Legend.copy(fontSize = 11.sp),
                            color = if (on) Tape.Cream else Tape.Dust,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            // groove + slider knob
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
            ) {
                val slot = maxWidth / Tab.entries.size
                val knobW = 30.dp
                val x by animateDpAsState(
                    slot * selected.ordinal + (slot - knobW) / 2,
                    tween(if (still) 0 else 220),
                    label = "knob",
                )
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = slot / 2 - 4.dp, y = 4.dp)
                        .width(slot * (Tab.entries.size - 1) + 8.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF0D0B09))
                )
                Box(
                    Modifier
                        .offset(x = x, y = 1.dp)
                        .size(width = knobW, height = 10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFFD9D2C6), Color(0xFF8E877C))))
                )
            }
        }
    }
}

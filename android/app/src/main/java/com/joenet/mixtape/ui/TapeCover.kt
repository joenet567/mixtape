package com.joenet.mixtape.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Cover-first tape: the cover art is the main element and the cassette is a small live badge that keeps the
 * tape's identity (its label colour) in the corner.
 *
 * A square cover ([SongArt], so the no-art gradient fallback keeps working) with a [TapeBadge] overlapping
 * its bottom-end corner, inset by ~6% of the cover width. The width comes from the caller's [modifier]
 * (`fillMaxWidth()`, `width(...)`); the height follows (1:1). When the incoming width is unbounded (a bare
 * item in a horizontal scroller) a 160.dp cover is used instead of crashing.
 *
 * The badge has no text and the component adds no semantics of its own: put the name and "N songs, X min"
 * under it as normal text, and give the wrapping container the merged semantics (content description = tape
 * name).
 *
 * @param seed stable key for the no-art gradient fallback (the tape's name or folder).
 * @param progress drives the badge's reels (0..1 through the tape).
 * @param spinning true while this tape is the one playing, so the reels turn.
 * @param tapeFraction badge width as a fraction of the cover width.
 * @param px thumbnail size requested for the cover; the default suits large covers.
 */
@Composable
fun CoverWithTape(
    art: Uri?,
    labelColor: Color,
    seed: String,
    modifier: Modifier = Modifier,
    artworkData: ByteArray? = null,
    progress: Float = 0f,
    spinning: Boolean = false,
    corner: Dp = 16.dp,
    tapeFraction: Float = 0.38f,
    showTape: Boolean = true,
    px: Int = 512,
) {
    BoxWithConstraints(modifier) {
        val bounded = constraints.hasBoundedWidth
        val coverWidth: Dp = if (bounded) maxWidth else 160.dp
        Box(if (bounded) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.width(coverWidth).aspectRatio(1f)) {
            SongArt(
                art,
                Modifier.fillMaxSize(),
                px = px,
                corner = corner,
                seed = seed,
                artworkData = artworkData,
            )
            if (showTape) {
                val inset = coverWidth * 0.06f
                TapeBadge(
                    labelColor,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = inset, bottom = inset)
                        .width(coverWidth * tapeFraction.coerceIn(0.1f, 0.9f)),
                    progress = progress,
                    spinning = spinning,
                )
            }
        }
    }
}

/**
 * Just the small cassette: no label text, no sticker, a soft shadow so it lifts off whatever it sits on.
 * The reels turn while [spinning] and the tape packs follow [progress]. Give it a width (it derives its own
 * height); its accessibility semantics are cleared so the container's merged description speaks for it.
 */
@Composable
fun TapeBadge(
    labelColor: Color,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    spinning: Boolean = false,
) {
    Cassette(
        label = "",
        labelColor = labelColor,
        modifier = modifier
            .shadow(
                elevation = 6.dp,
                // the shell's corner radius is 0.045 of the width = about 7% of the (shorter) height
                shape = RoundedCornerShape(percent = 7),
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.60f),
            )
            .clearAndSetSemantics { },
        progress = progress,
        spinning = spinning,
        art = null,
    )
}

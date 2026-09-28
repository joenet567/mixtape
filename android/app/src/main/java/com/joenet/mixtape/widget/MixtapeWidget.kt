package com.joenet.mixtape.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.joenet.mixtape.MainActivity
import com.joenet.mixtape.PlaybackService
import com.joenet.mixtape.R
import com.joenet.mixtape.ui.ArtCache
import com.joenet.mixtape.ui.CASSETTE_RATIO
import com.joenet.mixtape.ui.Tape
import com.joenet.mixtape.ui.drawShell
import com.joenet.mixtape.ui.drawSticker
import com.joenet.mixtape.ui.drawWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * The home-screen widget: the tape that's in the deck (a still frame of the app's cassette, cover
 * sticker and all) with the song and two deck keys, play/pause and next. Tap the tape to open
 * the player.
 */
class MixtapeWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val now = WidgetState.from(currentState<Preferences>()) ?: WidgetState.read(context)
            // Redrawn when the song changes; the previous tape stays up while the new one is drawn.
            val tape by produceState<Bitmap?>(null, now.mediaId, now.folder, now.progress) {
                val art = now.mediaId?.let { ArtCache.load(context, Uri.parse(it), 256) }
                value = cassetteBitmap(context, now.folder.ifEmpty { "Mixtape" }, Tape.labelColor(now.folder), art, now.progress)
            }
            Deck(context, now, tape)
        }
    }

    @Composable
    private fun Deck(context: Context, now: WidgetState, tape: Bitmap?) {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, now.mediaId != null)
        Row(
            GlanceModifier
                .fillMaxSize()
                .background(Tape.Deck)
                .cornerRadius(20.dp)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tapeModifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity(open))
            if (tape != null) {
                Image(
                    ImageProvider(tape),
                    contentDescription = "Cassette: ${now.folder.ifEmpty { "Mixtape" }}. Open the player",
                    contentScale = ContentScale.Fit,
                    modifier = tapeModifier,
                )
            } else {
                Box(tapeModifier) {}
            }
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.width(118.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    now.title.ifEmpty { "Mixtape" },
                    maxLines = 2,
                    style = TextStyle(color = ColorProvider(Tape.Cream), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    now.artist.ifEmpty { "Tap the tape to start" },
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Tape.Dust), fontSize = 12.sp),
                )
                Spacer(GlanceModifier.height(10.dp))
                Row {
                    DeckKey(
                        if (now.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                        if (now.playing) "Pause" else "Play",
                        KEY_PLAY_PAUSE,
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    DeckKey(R.drawable.ic_widget_next, "Next song", KEY_NEXT)
                }
            }
        }
    }

    @Composable
    private fun DeckKey(icon: Int, label: String, key: String) {
        Box(
            GlanceModifier
                .size(48.dp)
                .background(ImageProvider(R.drawable.widget_key))
                .clickable(actionRunCallback<DeckKeyAction>(actionParametersOf(KeyParam to key))),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(icon), contentDescription = label, colorFilter = ColorFilter.tint(ColorProvider(Tape.Ink)), modifier = GlanceModifier.size(24.dp))
        }
    }

    companion object {
        const val KEY_PLAY_PAUSE = "play_pause"
        const val KEY_NEXT = "next"
        val KeyParam = ActionParameters.Key<String>("key")

        /** From the playback service: hand every placed widget the new state and redraw it. */
        suspend fun push(context: Context, state: WidgetState) {
            WidgetState.save(context, state)
            val widget = MixtapeWidget()
            for (id in GlanceAppWidgetManager(context).getGlanceIds(MixtapeWidget::class.java)) {
                updateAppWidgetState(context, id) { state.writeTo(it) }
                widget.update(context, id)
            }
        }

        /** The app's cassette drawn once into a bitmap: same shell, window and sticker, reels at rest. */
        fun cassetteBitmap(context: Context, label: String, labelColor: Color, art: ImageBitmap?, progress: Float, width: Int = 560): Bitmap {
            val height = (width / CASSETTE_RATIO).roundToInt()
            val image = ImageBitmap(width, height)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
                drawShell(labelColor)
                drawWindow(progress, 0f, 33f)
                art?.let { drawSticker(it) }
            }
            val bitmap = image.asAndroidBitmap()
            val canvas = android.graphics.Canvas(bitmap)
            val w = width.toFloat()
            // The label, handwritten, and the side letter, as Cassette() lays them out.
            val marker = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = ResourcesCompat.getFont(context, R.font.reenie_beanie)
                textSize = w * 0.092f
                color = Tape.PaperInk.toArgb()
            }
            val shown = TextUtils.ellipsize(label, marker, w * 0.82f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, w * 0.09f, w * 0.052f - marker.fontMetrics.ascent, marker)
            val side = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = ResourcesCompat.getFont(context, R.font.barlow_condensed_bold)
                textSize = w * 0.11f
                color = Tape.PaperInk.copy(alpha = 0.85f).toArgb()
            }
            canvas.drawText("A", w * 0.095f, w * 0.214f - side.fontMetrics.ascent, side)
            return bitmap
        }
    }
}

class MixtapeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MixtapeWidget()
}

/**
 * The widget's deck keys talk to the playback service like the app does, through a short-lived
 * controller (binding is allowed from the background, and the tap lets playback go foreground).
 */
class DeckKeyAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext // this runs in a broadcast receiver, whose own context can't bind
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        withContext(Dispatchers.Main) {
            val controller = suspendCancellableCoroutine { cont ->
                val future = MediaController.Builder(app, token).setApplicationLooper(Looper.getMainLooper()).buildAsync()
                future.addListener({
                    runCatching { future.get() }.onSuccess { cont.resume(it) }.onFailure { cont.resumeWithException(it) }
                }, ContextCompat.getMainExecutor(app))
                cont.invokeOnCancellation { MediaController.releaseFuture(future) }
            }
            try {
                when (parameters[MixtapeWidget.KeyParam]) {
                    MixtapeWidget.KEY_PLAY_PAUSE -> if (controller.isPlaying) {
                        controller.pause()
                    } else {
                        if (controller.playbackState == androidx.media3.common.Player.STATE_IDLE) controller.prepare()
                        if (controller.playbackState == androidx.media3.common.Player.STATE_ENDED) controller.seekToDefaultPosition()
                        controller.play()
                    }
                    MixtapeWidget.KEY_NEXT -> controller.seekToNext()
                }
            } finally {
                controller.release()
            }
        }
    }
}

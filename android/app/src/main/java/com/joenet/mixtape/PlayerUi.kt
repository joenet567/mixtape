package com.joenet.mixtape

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController

/**
 * Compose-observable mirror of the PlaybackService's player, reached through a MediaController.
 * The activity attaches a controller in onStart and detaches it in onStop.
 */
class PlayerUi {
    var controller by mutableStateOf<MediaController?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var mediaId by mutableStateOf<String?>(null)
        private set
    var metadata by mutableStateOf(MediaMetadata.EMPTY)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    /** Bumped on seeks / track changes / queue edits so position and queue views re-read. */
    var tick by mutableIntStateOf(0)
        private set

    val hasSong: Boolean get() = mediaId != null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
    }

    fun attach(c: MediaController, context: Context) {
        controller = c
        c.addListener(listener)
        if (c.mediaItemCount == 0) {
            QueueStore.load(context)?.let { saved ->
                c.shuffleModeEnabled = saved.shuffle
                c.repeatMode = saved.repeatMode
                c.setMediaItems(saved.items, saved.index, saved.positionMs)
                c.prepare()
            }
        }
        refresh(c)
    }

    fun detach() {
        controller?.removeListener(listener)
        controller = null
    }

    private fun refresh(p: Player) {
        isPlaying = p.isPlaying
        val item = p.currentMediaItem
        mediaId = item?.mediaId
        metadata = if (item != null) p.mediaMetadata else MediaMetadata.EMPTY
        durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        shuffle = p.shuffleModeEnabled
        repeatMode = p.repeatMode
        tick++
    }

    fun position(): Long = controller?.currentPosition ?: 0L

    fun play(songs: List<Song>, startIndex: Int) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.setMediaItems(songs.map { it.toMediaItem() }, startIndex.coerceIn(0, songs.lastIndex), 0L)
        c.prepare()
        c.play()
    }

    fun playInOrder(songs: List<Song>) {
        controller?.shuffleModeEnabled = false
        play(songs, 0)
    }

    fun shuffleAll(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.shuffleModeEnabled = true
        play(songs, songs.indices.random())
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun jumpTo(index: Int) = controller?.run { seekToDefaultPosition(index); play() }

    /** Cue / review: nudge the position while ◀◀ or ▶▶ is held down. */
    fun scrub(forward: Boolean, stepMs: Long = 2_000) {
        val c = controller ?: return
        val dur = c.duration.takeIf { it != C.TIME_UNSET } ?: return
        c.seekTo((c.currentPosition + if (forward) stepMs else -stepMs).coerceIn(0, (dur - 500).coerceAtLeast(0)))
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    /** Whole queue as (index, item) in the order it actually plays, which differs from list order when shuffled. */
    fun queue(): List<Pair<Int, MediaItem>> {
        val c = controller ?: return emptyList()
        val timeline = c.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = ArrayList<Pair<Int, MediaItem>>()
        var i = timeline.getFirstWindowIndex(c.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && out.size < timeline.windowCount) {
            out += i to c.getMediaItemAt(i)
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        }
        return out
    }
}

package com.joenet.mixtape

import android.os.Bundle
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
import androidx.media3.session.SessionCommand

data class QueueEntry(val index: Int, val item: MediaItem)

/** The queue the way streaming apps show it: what's on, what you queued, what the tape plays next. */
data class QueueView(
    val current: QueueEntry?,
    val queued: List<QueueEntry>,
    val rest: List<QueueEntry>,
    val restFrom: String?,
)

/**
 * Compose-observable mirror of the PlaybackService's player, reached through a MediaController.
 * The activity attaches a controller in onStart and detaches it in onStop. Play order is list
 * order (shuffle physically reorders the queue, see MixPlayer), so indices mean what they show.
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
    var currentIndex by mutableIntStateOf(-1)
        private set
    var currentItem by mutableStateOf<MediaItem?>(null)
        private set
    /** Bumped on seeks / track changes / queue edits so position and queue views re-read. */
    var tick by mutableIntStateOf(0)
        private set

    /** Sleep timer: when it stops the music (wall clock, 0 = off), or at the end of this song. */
    var sleepAt by mutableLongStateOf(0L)
        private set
    var sleepEndOfSong by mutableStateOf(false)
        private set
    val sleepOn: Boolean get() = sleepAt > 0 || sleepEndOfSong

    /** The player's audio session, for opening the phone's equaliser on it. */
    var audioSessionId = 0
        private set

    val hasSong: Boolean get() = mediaId != null
    val currentKey: String? get() = currentItem?.songKey
    val currentFolder: String get() = currentItem?.folder.orEmpty()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
    }

    fun attach(c: MediaController) {
        controller = c
        c.addListener(listener)
        refresh(c)
        onExtras(c.sessionExtras)
    }

    /** The service publishes the sleep timer and audio session as session extras. */
    fun onExtras(extras: Bundle) {
        sleepAt = extras.getLong(PlaybackService.EXTRA_SLEEP_AT)
        sleepEndOfSong = extras.getBoolean(PlaybackService.EXTRA_SLEEP_END_OF_SONG)
        audioSessionId = extras.getInt(PlaybackService.EXTRA_AUDIO_SESSION)
    }

    /** Minutes until the music stops; 0 turns the timer off, [PlaybackService.SLEEP_END_OF_SONG] waits for this song. */
    fun setSleepTimer(minutes: Int) {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.ACTION_SLEEP, Bundle.EMPTY),
            Bundle().apply { putInt(PlaybackService.ARG_MINUTES, minutes) },
        )
    }

    fun detach() {
        controller?.removeListener(listener)
        controller = null
    }

    private fun refresh(p: Player) {
        isPlaying = p.isPlaying
        val item = p.currentMediaItem
        currentItem = item
        mediaId = item?.mediaId
        metadata = if (item != null) p.mediaMetadata else MediaMetadata.EMPTY
        durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        shuffle = p.shuffleModeEnabled
        repeatMode = p.repeatMode
        currentIndex = if (item != null) p.currentMediaItemIndex else -1
        tick++
    }

    fun position(): Long = controller?.currentPosition ?: 0L

    /**
     * Play [songs] from [startIndex]. [ctx] names the source for "Next from: …". With shuffle on,
     * the tapped song plays first and the rest is dealt shuffled (MixPlayer does that).
     */
    fun play(songs: List<Song>, startIndex: Int, ctx: PlayCtx, startPositionMs: Long = 0L) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.setMediaItems(songs.map { it.toMediaItem(ctx) }, startIndex.coerceIn(0, songs.lastIndex), startPositionMs)
        c.prepare()
        c.play()
    }

    fun playInOrder(songs: List<Song>, ctx: PlayCtx) {
        controller?.shuffleModeEnabled = false
        play(songs, 0, ctx)
    }

    fun shuffleAll(songs: List<Song>, ctx: PlayCtx) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        play(songs, songs.indices.random(), ctx)
        c.shuffleModeEnabled = true
    }

    /** Right after the current song. */
    fun playNext(song: Song) = insert(listOf(song), (currentIndex + 1).coerceAtLeast(0))

    /** After the current song and anything already queued, before the rest of the tape. */
    fun addToQueue(song: Song) = addToQueue(listOf(song))

    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        var at = currentIndex + 1
        while (at in 0 until c.mediaItemCount && c.getMediaItemAt(at).isQueued) at++
        insert(songs, at.coerceAtLeast(0))
    }

    private fun insert(songs: List<Song>, at: Int) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            play(songs, 0, PlayCtx.oneOff("Your queue"))
            return
        }
        c.addMediaItems(at.coerceIn(0, c.mediaItemCount), songs.map { it.toMediaItem(ctx = null, queued = true) })
    }

    fun move(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to || from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount) return
        c.moveMediaItem(from, to)
    }

    fun remove(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount && index != c.currentMediaItemIndex) c.removeMediaItem(index)
    }

    /** Clears what you queued (Play next / Add to queue); the tape itself carries on. */
    fun clearQueued() {
        val c = controller ?: return
        for (i in c.mediaItemCount - 1 downTo c.currentMediaItemIndex + 1) {
            if (c.getMediaItemAt(i).isQueued) c.removeMediaItem(i)
        }
    }

    fun queueView(): QueueView {
        val c = controller ?: return QueueView(null, emptyList(), emptyList(), null)
        if (c.mediaItemCount == 0) return QueueView(null, emptyList(), emptyList(), null)
        val cur = c.currentMediaItemIndex
        val current = QueueEntry(cur, c.getMediaItemAt(cur))
        val queued = ArrayList<QueueEntry>()
        var i = cur + 1
        while (i < c.mediaItemCount && c.getMediaItemAt(i).isQueued) {
            queued += QueueEntry(i, c.getMediaItemAt(i))
            i++
        }
        val rest = (i until c.mediaItemCount).map { QueueEntry(it, c.getMediaItemAt(it)) }
        val from = rest.firstNotNullOfOrNull { it.item.ctx } ?: current.item.ctx
        return QueueView(current, queued, rest, from)
    }

    /** Every song in the queue, in play order (for "Save queue as tape"). */
    fun queueKeys(): List<String> {
        val c = controller ?: return emptyList()
        return (0 until c.mediaItemCount).map { c.getMediaItemAt(it).songKey }
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
}

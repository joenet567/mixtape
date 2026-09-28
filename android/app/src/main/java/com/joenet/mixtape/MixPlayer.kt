package com.joenet.mixtape

import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.random.Random

/**
 * The session's player. Everything is ExoPlayer except shuffle, which is done by physically
 * reordering the queue instead of ExoPlayer's hidden shuffle order:
 *
 *  - play order is always list order, so "Play next" / "Add to queue" / drag-to-reorder mean what
 *    they say even while shuffled (ExoPlayer's own shuffle drops inserted songs at random spots);
 *  - the order is "smart": no two songs by the same artist back to back where avoidable, and
 *    liked songs come up slightly more often;
 *  - the unshuffled order is remembered, so turning shuffle off puts the tape back in order.
 *
 * Headsets, the notification and Android Auto toggle shuffle through the normal player API, so
 * they all get the same behaviour.
 */
class MixPlayer(
    private val exo: ExoPlayer,
    private val likedKeys: () -> Set<String> = { emptySet() },
) : ForwardingPlayer(exo) {

    private var shuffleOn = false

    /** Queue-entry ids in the unshuffled order, while shuffle is on. */
    var originalOrder: List<String> = emptyList()
        private set

    // MediaSession listens through us; we notify it ourselves about our own shuffle flag.
    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    override fun getShuffleModeEnabled(): Boolean = shuffleOn

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled == shuffleOn) return
        if (shuffleModeEnabled) shuffleAroundCurrent() else unshuffle()
        shuffleOn = shuffleModeEnabled
        if (!shuffleOn) originalOrder = emptyList()
        notifyShuffle()
    }

    /** A new queue while shuffle is on: start with the chosen song, deal the rest smart-shuffled. */
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (!shuffleOn || mediaItems.size <= 1) {
            if (!shuffleOn) originalOrder = emptyList()
            super.setMediaItems(mediaItems, startIndex, startPositionMs)
            return
        }
        originalOrder = mediaItems.map { it.qid }
        val start = if (startIndex in mediaItems.indices) startIndex else Random.nextInt(mediaItems.size)
        val first = mediaItems[start]
        val rest = mediaItems.filterIndexed { i, _ -> i != start }
        super.setMediaItems((listOf(first) + smartShuffle(rest, first)).toMutableList(), 0, startPositionMs)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        if (shuffleOn) setMediaItems(mediaItems, 0, C.TIME_UNSET) else super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) = setMediaItems(mediaItems, 0, C.TIME_UNSET)

    override fun setMediaItem(mediaItem: MediaItem) = setMediaItems(mutableListOf(mediaItem), 0, C.TIME_UNSET)
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = setMediaItems(mutableListOf(mediaItem), 0, startPositionMs)
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = setMediaItems(mutableListOf(mediaItem), 0, C.TIME_UNSET)

    /** Put back a saved queue as-is (it's already in its shuffled order, if it was shuffled). */
    fun restore(items: List<MediaItem>, index: Int, positionMs: Long, shuffled: Boolean, original: List<String>) {
        shuffleOn = false
        super.setMediaItems(items.toMutableList(), index, positionMs)
        shuffleOn = shuffled && items.size > 1
        originalOrder = if (shuffleOn) original else emptyList()
        if (shuffleOn) notifyShuffle()
    }

    private fun items(): List<MediaItem> = (0 until mediaItemCount).map { getMediaItemAt(it) }

    private fun shuffleAroundCurrent() {
        val all = items()
        if (all.size <= 1) {
            originalOrder = all.map { it.qid }
            return
        }
        originalOrder = all.map { it.qid }
        val cur = currentMediaItemIndex.coerceIn(0, all.lastIndex)
        val current = all[cur]
        val rest = all.filterIndexed { i, _ -> i != cur }
        // current stays put and keeps playing; everything else follows it, shuffled
        if (cur > 0) exo.removeMediaItems(0, cur)
        exo.replaceMediaItems(1, exo.mediaItemCount, smartShuffle(rest, current))
    }

    private fun unshuffle() {
        val all = items()
        if (all.size <= 1 || originalOrder.isEmpty()) return
        val byQid = all.associateBy { it.qid }
        val ordered = originalOrder.mapNotNull { byQid[it] }
        val known = originalOrder.toHashSet()
        val added = all.filter { it.qid !in known } // queued while shuffled: keep them next
        val cur = currentMediaItemIndex.coerceIn(0, all.lastIndex)
        val current = all[cur]
        val at = ordered.indexOfFirst { it.qid == current.qid }
        val before = if (at >= 0) ordered.subList(0, at) else emptyList()
        val after = (added - current) + if (at >= 0) ordered.subList(at + 1, ordered.size) else ordered
        exo.replaceMediaItems(cur + 1, exo.mediaItemCount, after)
        exo.replaceMediaItems(0, cur, before)
    }

    /**
     * Shuffle that avoids the same artist twice in a row (when there's any other choice) and gives
     * liked songs a 1.4x weight, so they tend to come up a bit sooner.
     */
    fun smartShuffle(items: List<MediaItem>, after: MediaItem? = null): List<MediaItem> {
        val liked = likedKeys()
        val pool = items.toMutableList()
        val out = ArrayList<MediaItem>(items.size)
        var lastArtist = after?.mediaMetadata?.artist?.toString()
        while (pool.isNotEmpty()) {
            val candidates = pool.filter { it.mediaMetadata.artist?.toString() != lastArtist }.ifEmpty { pool }
            val weights = candidates.map { if (it.songKey in liked) 1.4 else 1.0 }
            var r = Random.nextDouble() * weights.sum()
            var pick = candidates.last()
            for (i in candidates.indices) {
                r -= weights[i]
                if (r <= 0) {
                    pick = candidates[i]
                    break
                }
            }
            out += pick
            pool.remove(pick)
            lastArtist = pick.mediaMetadata.artist?.toString()
        }
        return out
    }

    private fun notifyShuffle() {
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED).build())
        for (l in listeners) {
            l.onShuffleModeEnabledChanged(shuffleOn)
            l.onEvents(this, events)
        }
    }
}

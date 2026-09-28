package com.joenet.mixtape

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Owns the player. Media3 turns this into a foreground service with a media notification
 * (lock screen, Bluetooth and headset controls included) whenever something is playing,
 * which is what keeps the music going after the app is backgrounded or swiped away.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private lateinit var player: MixPlayer

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true, // pause for calls / other apps
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)    // keep the CPU awake while playing with the screen off
            .build()
        player = MixPlayer(exo)

        // Reopen where we left off: the queue is back (paused) before any screen asks for it.
        QueueStore.load(this)?.let { saved ->
            player.restore(saved.items, saved.index, saved.positionMs, saved.shuffle, saved.original)
            player.repeatMode = saved.repeatMode
            player.prepare()
        }
        player.addListener(Watcher())

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(Callback())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** App swiped away from recents: keep going if music is playing, otherwise shut down. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            if (player.mediaItemCount > 0) QueueStore.savePosition(this@PlaybackService, this@PlaybackService.player)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class Callback : MediaSession.Callback {
        /** Controllers send items by id; rebuild the playable URI (the id is the content:// URI). */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { it.buildUpon().setUri(Uri.parse(it.mediaId)).build() }.toMutableList()
        )

        /** Play pressed on a headset / system media controls while the app wasn't running. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = QueueStore.load(this@PlaybackService)
                ?: return Futures.immediateFailedFuture(IllegalStateException("nothing to resume"))
            mediaSession.player.repeatMode = saved.repeatMode
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(saved.items, saved.index, saved.positionMs)
            )
        }
    }

    private inner class Watcher : Player.Listener {
        private var errorsInARow = 0

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                QueueStore.saveQueue(this@PlaybackService, player)
            }
        }

        override fun onEvents(p: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                QueueStore.savePosition(this@PlaybackService, player)
            }
            if (p.isPlaying) errorsInARow = 0
        }

        /** A file was deleted or is unreadable: skip it instead of stopping the whole queue. */
        override fun onPlayerError(error: PlaybackException) {
            if (++errorsInARow < player.mediaItemCount && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
            }
        }
    }
}

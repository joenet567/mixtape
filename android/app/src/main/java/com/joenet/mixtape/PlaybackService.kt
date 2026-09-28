package com.joenet.mixtape

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.joenet.mixtape.data.Like
import com.joenet.mixtape.data.MixtapeDb
import com.joenet.mixtape.data.Play
import com.joenet.mixtape.data.Resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the player. Media3 turns this into a foreground service with a media notification
 * (lock screen, Bluetooth and headset controls included) whenever something is playing,
 * which is what keeps the music going after the app is backgrounded or swiped away.
 *
 * It also keeps the listening history (a play counts at 30 s or half the song, whichever comes
 * first), the resume point of each tape, and a Like button in the notification.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private lateinit var player: MixPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val db by lazy { MixtapeDb.get(this) }
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var liked: Set<String> = emptySet()

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
        player = MixPlayer(exo) { liked }

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

        scope.launch {
            db.likes().all().collect { list ->
                liked = list.mapTo(HashSet()) { it.key }
                refreshLikeButton()
            }
        }
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
        handler.removeCallbacksAndMessages(null)
        session?.run {
            if (player.mediaItemCount > 0) {
                QueueStore.savePosition(this@PlaybackService, this@PlaybackService.player)
                saveResume()
            }
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // ---- Like button in the notification / lock screen ----

    private val likeCommand = SessionCommand(ACTION_LIKE, Bundle.EMPTY)

    private fun refreshLikeButton() {
        val key = player.currentMediaItem?.songKey
        val on = key != null && key in liked
        val button = CommandButton.Builder(if (on) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (on) "Remove from Liked songs" else "Add to Liked songs")
            .setSessionCommand(likeCommand)
            .build()
        session?.setCustomLayout(listOf(button))
    }

    private fun toggleLike() {
        val item = player.currentMediaItem ?: return
        val key = item.songKey
        scope.launch {
            if (key in liked) {
                db.likes().unlike(key)
            } else {
                val md = item.mediaMetadata
                db.likes().like(Like(key, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(), System.currentTimeMillis()))
            }
        }
    }

    // ---- history ----

    private var loggedQid: String? = null
    private var ticks = 0

    private val listenCheck = object : Runnable {
        override fun run() {
            checkListen()
            if (++ticks % 15 == 0) saveResume() // keep "Jump back in" close to where you are
            handler.postDelayed(this, 1000)
        }
    }

    private fun checkListen() {
        val item = player.currentMediaItem ?: return
        if (!player.isPlaying || item.qid == loggedQid) return
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        if (pos >= 30_000 || (dur > 0 && pos >= dur / 2)) {
            loggedQid = item.qid
            logPlay(item, dur)
        }
    }

    private fun logPlay(item: MediaItem, durationMs: Long) {
        val md = item.mediaMetadata
        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            val dao = db.history()
            val last = dao.last()
            val session = if (last != null && now - last.playedAt < 30 * 60_000L) last.session else now
            dao.insert(
                Play(
                    key = item.songKey,
                    title = md.title?.toString().orEmpty(),
                    artist = md.artist?.toString().orEmpty(),
                    folder = item.folder,
                    durationMs = durationMs,
                    ctxRef = item.ctxRef,
                    playedAt = now,
                    session = session,
                )
            )
        }
    }

    private fun saveResume() {
        val item = player.currentMediaItem ?: return
        val ref = item.ctxRef ?: return
        val resume = Resume(ref, item.ctx.orEmpty(), item.songKey, player.currentPosition.coerceAtLeast(0), System.currentTimeMillis())
        scope.launch(Dispatchers.IO) { db.history().saveResume(resume) }
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(likeCommand).build()
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_LIKE) toggleLike()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

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

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            loggedQid = null // a repeat of the same entry counts as a new listen
            refreshLikeButton()
            saveResume()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(listenCheck)
            if (isPlaying) handler.postDelayed(listenCheck, 1000) else saveResume()
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

    companion object {
        const val ACTION_LIKE = "com.joenet.mixtape.LIKE"
    }
}

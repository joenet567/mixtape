package com.joenet.mixtape

import android.content.Context
import android.net.Uri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers the queue, current song, position and shuffle/repeat across app restarts, so the
 * app reopens where it left off and a headset play button can resume after the app was killed.
 * The queue itself is only rewritten when it changes; position is cheap to save often.
 */
object QueueStore {

    class Saved(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
    )

    private fun prefs(context: Context) = context.getSharedPreferences("queue", Context.MODE_PRIVATE)

    fun saveQueue(context: Context, player: Player) {
        val arr = JSONArray()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            val md = item.mediaMetadata
            arr.put(
                JSONObject()
                    .put("id", item.mediaId)
                    .put("t", md.title?.toString().orEmpty())
                    .put("a", md.artist?.toString().orEmpty())
                    .put("al", md.albumTitle?.toString().orEmpty())
                    .put("f", md.extras?.getString(Song.EXTRA_FOLDER).orEmpty())
            )
        }
        prefs(context).edit().putString("items", arr.toString()).apply()
        savePosition(context, player)
    }

    fun savePosition(context: Context, player: Player) {
        val ended = player.playbackState == Player.STATE_ENDED // finished queue: reopen at the start, not the last second
        prefs(context).edit()
            .putInt("index", if (ended) 0 else player.currentMediaItemIndex)
            .putLong("pos", if (ended) 0 else player.currentPosition.coerceAtLeast(0))
            .putBoolean("shuffle", player.shuffleModeEnabled)
            .putInt("repeat", player.repeatMode)
            .apply()
    }

    fun load(context: Context): Saved? {
        val p = prefs(context)
        val arr = runCatching { JSONArray(p.getString("items", null) ?: return null) }.getOrNull() ?: return null
        if (arr.length() == 0) return null
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            MediaItem.Builder()
                .setMediaId(id)
                .setUri(Uri.parse(id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(o.optString("t"))
                        .setArtist(o.optString("a"))
                        .setAlbumTitle(o.optString("al"))
                        .setExtras(bundleOf(Song.EXTRA_FOLDER to o.optString("f")))
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build()
                )
                .build()
        }
        return Saved(
            items = items,
            index = p.getInt("index", 0).coerceIn(0, items.lastIndex),
            positionMs = p.getLong("pos", 0),
            shuffle = p.getBoolean("shuffle", false),
            repeatMode = p.getInt("repeat", Player.REPEAT_MODE_OFF),
        )
    }
}

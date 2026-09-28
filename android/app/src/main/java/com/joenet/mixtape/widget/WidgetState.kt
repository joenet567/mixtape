package com.joenet.mixtape.widget

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * What the widget shows, pushed by the playback service whenever the song or play state changes.
 * It lives in each widget's Glance state (so a running widget recomposes) and in plain prefs
 * (so a widget added mid-song starts out right).
 */
data class WidgetState(
    val title: String,
    val artist: String,
    val folder: String,
    val mediaId: String?,
    val playing: Boolean,
    val progress: Float,
) {
    fun writeTo(p: MutablePreferences) {
        p[TITLE] = title
        p[ARTIST] = artist
        p[FOLDER] = folder
        if (mediaId != null) p[MEDIA_ID] = mediaId else p.remove(MEDIA_ID)
        p[PLAYING] = playing
        p[PROGRESS] = progress
    }

    companion object {
        private val TITLE = stringPreferencesKey("title")
        private val ARTIST = stringPreferencesKey("artist")
        private val FOLDER = stringPreferencesKey("folder")
        private val MEDIA_ID = stringPreferencesKey("mediaId")
        private val PLAYING = booleanPreferencesKey("playing")
        private val PROGRESS = floatPreferencesKey("progress")

        fun from(p: Preferences): WidgetState? = p[TITLE]?.let {
            WidgetState(it, p[ARTIST].orEmpty(), p[FOLDER].orEmpty(), p[MEDIA_ID], p[PLAYING] ?: false, p[PROGRESS] ?: 0f)
        }

        private fun prefs(context: Context) = context.getSharedPreferences("widget", Context.MODE_PRIVATE)

        fun save(context: Context, s: WidgetState) = prefs(context).edit()
            .putString("title", s.title)
            .putString("artist", s.artist)
            .putString("folder", s.folder)
            .putString("mediaId", s.mediaId)
            .putBoolean("playing", s.playing)
            .putFloat("progress", s.progress)
            .apply()

        fun read(context: Context): WidgetState = prefs(context).let {
            WidgetState(
                title = it.getString("title", "").orEmpty(),
                artist = it.getString("artist", "").orEmpty(),
                folder = it.getString("folder", "").orEmpty(),
                mediaId = it.getString("mediaId", null),
                playing = it.getBoolean("playing", false),
                progress = it.getFloat("progress", 0f),
            )
        }
    }
}

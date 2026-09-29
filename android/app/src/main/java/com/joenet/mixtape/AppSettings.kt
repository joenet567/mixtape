package com.joenet.mixtape

import android.content.Context
import android.content.SharedPreferences

/** The few switches in Settings. The playback service listens to them too. */
object AppSettings {
    const val EVEN_LOUDNESS = "even_loudness"
    const val AUTO_SYNC = "auto_sync"
    const val SHOW_LYRICS = "show_lyrics"
    /** "system" (follow the phone), "light" or "dark". */
    const val THEME_MODE = "theme_mode"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun evenLoudness(context: Context) = prefs(context).getBoolean(EVEN_LOUDNESS, true)
    fun autoSync(context: Context) = prefs(context).getBoolean(AUTO_SYNC, true)
    fun showLyrics(context: Context) = prefs(context).getBoolean(SHOW_LYRICS, false)
    fun themeMode(context: Context): String = prefs(context).getString(THEME_MODE, "system") ?: "system"

    fun set(context: Context, key: String, on: Boolean) = prefs(context).edit().putBoolean(key, on).apply()
    fun setThemeMode(context: Context, mode: String) = prefs(context).edit().putString(THEME_MODE, mode).apply()
}

package com.joenet.mixtape

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * What the PC importer knows about a song beyond the MP3 itself: its lyrics and its loudness
 * (ReplayGain). Both arrive with a Wi-Fi sync, for songs the phone already had too, and live in
 * app-private files keyed by YouTube video id: lyrics/<id>.lrc and loudness.json.
 */
object Sidecar {
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    fun isVideoId(id: String) = VIDEO_ID.matches(id)

    fun lyricsFile(context: Context, videoId: String) = File(context.filesDir, "lyrics/$videoId.lrc")

    /** The YouTube video id behind a song key ("yt:<id>"), if it has one. */
    fun videoIdOf(songKey: String?): String? =
        if (songKey != null && songKey.startsWith("yt:")) songKey.substring(3) else null

    // ---- loudness: video id -> ReplayGain track gain in dB ----

    private var gains: Map<String, Float>? = null
    private var gainsStamp = -1L

    private fun gainsFile(context: Context) = AtomicFile(File(context.filesDir, "loudness.json"))

    @Synchronized
    fun gain(context: Context, videoId: String): Float? = loadGains(context)[videoId]

    @Synchronized
    fun saveGains(context: Context, update: Map<String, Float>) {
        if (update.isEmpty()) return
        val merged = loadGains(context) + update
        if (merged == gains) return
        val file = gainsFile(context)
        val out = file.startWrite()
        try {
            out.write(JSONObject(merged.mapValues { it.value.toDouble() }).toString().toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        gains = merged
        gainsStamp = file.baseFile.lastModified()
    }

    private fun loadGains(context: Context): Map<String, Float> {
        val file = gainsFile(context)
        val stamp = file.baseFile.lastModified()
        gains?.let { if (stamp == gainsStamp) return it }
        val loaded = runCatching {
            val json = JSONObject(String(file.readFully()))
            json.keys().asSequence().associateWith { json.getDouble(it).toFloat() }
        }.getOrDefault(emptyMap())
        gains = loaded
        gainsStamp = stamp
        return loaded
    }
}

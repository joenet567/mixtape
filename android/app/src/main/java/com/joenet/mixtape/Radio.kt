package com.joenet.mixtape

import android.content.Context
import com.joenet.mixtape.data.MixtapeDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Song radio without a server, from your own library and habits: songs by the same artists,
 * songs that share a tape with the seed, and songs you often play in the same sitting, each
 * weighted by how much you play it. Anything played in the last hour sits this one out.
 */
object Radio {
    private const val HOUR = 3_600_000L

    suspend fun build(context: Context, seed: Song, index: LibraryIndex, tapes: List<TapeHit>, size: Int = 40): List<Song> =
        withContext(Dispatchers.IO) {
            val history = MixtapeDb.get(context).history()
            val justPlayed = history.playedSince(System.currentTimeMillis() - HOUR).toHashSet()
            val plays = history.allCounts().associate { it.key to it.plays }
            val together = history.playedWith(seed.key, 30)

            val weight = HashMap<String, Double>()
            fun add(song: Song, w: Double) {
                if (song.key != seed.key && song.key !in justPlayed) weight.merge(song.key, w, Double::plus)
            }
            for (artist in seed.artists) index.artist(artist).forEach { add(it, 3.0) }
            tapes.filter { t -> t.songs.any { it.key == seed.key } }.forEach { t -> t.songs.forEach { add(it, 2.0) } }
            for (kc in together) index.byKey[kc.key]?.let { add(it, 2.0 + min(kc.plays, 5)) }
            // A small library runs dry fast: the rest of it keeps the radio going, faintly.
            if (weight.size < size) index.songs.forEach { add(it, 0.3) }

            // Weighted draw without replacement: each song's key is u^(1/w), highest keys win.
            val drawn = weight.entries
                .map { (key, w) -> key to Random.nextDouble().pow(1.0 / (w * (1 + ln(1.0 + (plays[key] ?: 0))))) }
                .sortedByDescending { it.second }
                .take(size)
                .mapNotNull { index.byKey[it.first] }
            listOf(seed) + spaceArtists(drawn, seed.artist)
        }

    /** No artist twice in a row where it can be helped. */
    private fun spaceArtists(songs: List<Song>, after: String): List<Song> {
        val pool = songs.toMutableList()
        val out = ArrayList<Song>(songs.size)
        var last = after
        while (pool.isNotEmpty()) {
            val next = pool.firstOrNull { it.artist != last } ?: pool.first()
            pool.remove(next)
            out += next
            last = next.artist
        }
        return out
    }
}

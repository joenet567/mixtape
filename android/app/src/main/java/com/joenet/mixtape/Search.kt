package com.joenet.mixtape

import java.text.Normalizer

/** Accent- and case-insensitive text for matching: "Sơn Tùng" and "son tung" fold to the same thing. */
object Fold {
    private val marks = Regex("\\p{Mn}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    fun key(s: String): String {
        val lower = s.lowercase()
        val stripped = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(marks, "")
        return stripped
            .replace('đ', 'd').replace('ð', 'd').replace('ł', 'l').replace('ø', 'o')
            .replace("ß", "ss").replace("æ", "ae").replace("œ", "oe")
            .replace(nonWord, " ")
            .trim()
    }

    fun tokens(query: String): List<String> = key(query).split(' ').filter { it.isNotEmpty() }
}

/** Anything tape-shaped that search can find: folder tapes now, your own tapes and Liked later. */
data class TapeHit(val ref: TapeRef, val name: String, val songs: List<Song>)

sealed interface TopResult {
    data class OfSong(val song: Song) : TopResult
    data class OfArtist(val name: String, val songs: List<Song>) : TopResult
    data class OfTape(val tape: TapeHit) : TopResult
}

data class SearchResults(
    val query: String,
    val top: TopResult?,
    val songs: List<Song>,
    val artists: List<Pair<String, List<Song>>>,
    val tapes: List<TapeHit>,
) {
    val isEmpty get() = top == null && songs.isEmpty() && artists.isEmpty() && tapes.isEmpty()

    companion object {
        val NONE = SearchResults("", null, emptyList(), emptyList(), emptyList())
    }
}

/** Something to play: the songs, where in them to start, and where they're from. */
data class PlayPick(val songs: List<Song>, val start: Int, val ctx: PlayCtx, val positionMs: Long = 0L)

/**
 * Spotify-style search over the library: every word of the query must appear somewhere (title,
 * artist, album or tape), in any order, ignoring accents. Results come grouped, with the single
 * best match pulled out as the top result.
 */
object Searcher {

    // Spoken requests carry words that aren't in any title: "play Helena by My Chemical Romance".
    private val SPOKEN_FILLER = Regex("""\b(by|songs?|music|playlist|tape)\b""", RegexOption.IGNORE_CASE)

    /**
     * What "play [query]" means, for voice (Assistant, Android Auto): an artist or tape by that
     * name plays in full; otherwise the matching songs play, best match first.
     */
    fun pick(index: LibraryIndex, query: String, extraTapes: List<TapeHit> = emptyList()): PlayPick? {
        val r = search(index, query.replace(SPOKEN_FILLER, " "), extraTapes)
        val pick = when (val top = r.top) {
            is TopResult.OfArtist -> PlayPick(top.songs, 0, PlayCtx.artist(top.name))
            is TopResult.OfTape -> PlayPick(top.tape.songs, 0, PlayCtx.of(top.tape.ref, top.tape.name))
            is TopResult.OfSong -> PlayPick(r.songs, r.songs.indexOf(top.song).coerceAtLeast(0), PlayCtx.oneOff("Search: “${query.trim()}”"))
            null -> null
        }
        return pick?.takeIf { it.songs.isNotEmpty() }
    }

    fun search(index: LibraryIndex, query: String, extraTapes: List<TapeHit> = emptyList()): SearchResults {
        val tokens = Fold.tokens(query)
        if (tokens.isEmpty()) return SearchResults.NONE
        val phrase = tokens.joinToString(" ")

        fun songScore(s: Song): Int {
            val a = Fold.key(s.artist)
            return when {
                s.titleKey == phrase -> 100
                s.titleKey.startsWith(phrase) -> 70
                s.titleKey.contains(phrase) -> 50
                a == phrase -> 45
                a.contains(phrase) -> 30
                tokens.all { s.titleKey.contains(it) } -> 20
                else -> 0
            }
        }

        val songs = index.songs
            .filter { s -> tokens.all { s.searchKey.contains(it) } }
            .map { it to songScore(it) }
            .sortedWith(compareByDescending<Pair<Song, Int>> { it.second }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.title })
            .map { it.first }

        fun nameScore(name: String): Int {
            val k = Fold.key(name)
            return when {
                k == phrase -> 3
                k.startsWith(phrase) -> 2
                tokens.all { k.contains(it) } -> 1
                else -> 0
            }
        }

        val artists = index.artists.entries
            .map { Triple(it.key, it.value, nameScore(it.key)) }
            .filter { it.third > 0 }
            .sortedWith(compareByDescending<Triple<String, List<Song>, Int>> { it.third }.thenByDescending { it.second.size })
            .map { it.first to it.second }

        val tapes = (extraTapes + index.tapes.map { TapeHit(TapeRef.Folder(it.key), it.name, it.songs) })
            .map { it to nameScore(it.name) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }

        val bestSong = songs.firstOrNull()
        val top: TopResult? = when {
            tapes.firstOrNull()?.let { Fold.key(it.name) == phrase } == true -> TopResult.OfTape(tapes.first())
            artists.firstOrNull()?.let { Fold.key(it.first) == phrase } == true ->
                TopResult.OfArtist(artists.first().first, artists.first().second)
            bestSong != null && songScore(bestSong) >= 50 -> TopResult.OfSong(bestSong)
            artists.isNotEmpty() -> TopResult.OfArtist(artists.first().first, artists.first().second)
            bestSong != null -> TopResult.OfSong(bestSong)
            tapes.isNotEmpty() -> TopResult.OfTape(tapes.first())
            else -> null
        }
        return SearchResults(query, top, songs, artists, tapes)
    }
}

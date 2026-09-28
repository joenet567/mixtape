package com.joenet.mixtape

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.joenet.mixtape.data.MixtapeDb
import com.joenet.mixtape.data.Resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The library as the playback service shows it to Android Auto, Assistant and anything else that
 * browses media apps: Home, Tapes and Artists, like the app's own tabs.
 *
 * Ids: "home", "tapes", "artists"; "all"; "tape:<ref token>", "artist:<name>", "shelf:repeat",
 * "shelf:new", "resume:<ctx ref>", "search:<query>". A song is "song:<key>@<parent id>", so
 * playing it from a list plays the rest of that list after it, as in the app.
 */
class Catalog(private val context: Context) {

    /** Songs from MediaStore plus your tapes and Liked songs from Room, at one moment. */
    class Snapshot(
        val index: LibraryIndex,
        val liked: TapeHit,
        val userTapes: List<TapeHit>,
        val resumes: List<Resume>,
        val onRepeat: List<Song>,
        val newSongs: List<Song>,
    ) {
        val tapes: List<TapeHit> get() = listOf(liked) + userTapes + index.tapes.map { TapeHit(TapeRef.Folder(it.key), it.name, it.songs) }

        fun tape(ref: TapeRef): TapeHit? = when (ref) {
            TapeRef.Liked -> liked
            is TapeRef.User -> userTapes.firstOrNull { it.ref == ref }
            is TapeRef.Folder -> index.tapeByKey[ref.key]?.let { TapeHit(ref, it.name, it.songs) }
        }

        fun songsFor(target: CtxTarget): List<Song> = when (target) {
            CtxTarget.Everything -> index.songs
            is CtxTarget.OfArtist -> index.artist(target.name)
            is CtxTarget.OfTape -> tape(target.ref)?.songs.orEmpty()
        }
    }

    private val db = MixtapeDb.get(context)
    private val lock = Mutex()
    private var cached: Snapshot? = null
    private var cachedAt = 0L

    /** A fresh-enough snapshot (re-read at most every 30 s: browsing asks for it a lot). */
    suspend fun snapshot(): Snapshot = lock.withLock {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - cachedAt < 30_000 }?.let { return it }
        withContext(Dispatchers.IO) {
            val index = LibraryIndex(MusicLibrary.load(context))
            val liked = TapeHit(TapeRef.Liked, "Liked songs", db.likes().keys().mapNotNull { index.byKey[it] })
            val tracks = db.tapes().trackList().groupBy { it.tapeId }
            val userTapes = db.tapes().tapeList().map { t ->
                TapeHit(TapeRef.User(t.id), t.name, tracks[t.id].orEmpty().mapNotNull { index.byKey[it.key] })
            }
            val lastSync = context.getSharedPreferences("sync", Context.MODE_PRIVATE).getLong("lastAddedAt", 0L) / 1000
            val since = if (lastSync > 0) lastSync - 60 else now / 1000 - 14 * 86_400L
            Snapshot(
                index = index,
                liked = liked,
                userTapes = userTapes,
                resumes = db.history().resumeList(6),
                onRepeat = db.history().top(now - 30 * 86_400_000L, 2, 20).mapNotNull { index.byKey[it.key] },
                newSongs = index.songs.filter { it.dateAdded >= since }.sortedByDescending { it.dateAdded }.take(30),
            )
        }.also {
            cached = it
            cachedAt = now
        }
    }

    // ---- browsing ----

    fun root(): MediaItem = node(ROOT, "Mixtape", browsable = true, playable = false, type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun children(parentId: String, s: Snapshot): List<MediaItem>? = when {
        parentId == ROOT -> listOf(
            node(HOME, "Home", browsable = true, playable = false, type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            node(TAPES, "Tapes", browsable = true, playable = false, type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, gridChildren = true),
            node(ARTISTS, "Artists", browsable = true, playable = false, type = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        )
        parentId == HOME -> buildList {
            if (s.index.songs.isNotEmpty()) add(node(ALL, "Shuffle everything", songCount(s.index.songs.size), browsable = false, playable = true, type = MediaMetadata.MEDIA_TYPE_PLAYLIST))
            for (r in s.resumes) {
                val target = CtxTarget.parse(r.ctxRef) ?: continue
                if (s.songsFor(target).isEmpty()) continue
                val name = (target as? CtxTarget.OfTape)?.let { s.tape(it.ref)?.name } ?: r.title
                val song = s.index.byKey[r.lastKey]?.title
                add(node("resume:${r.ctxRef}", name, song?.let { "Jump back in: $it" } ?: "Jump back in", browsable = false, playable = true, type = MediaMetadata.MEDIA_TYPE_PLAYLIST))
            }
            if (s.onRepeat.isNotEmpty()) add(node(SHELF_REPEAT, "On repeat", songCount(s.onRepeat.size), browsable = true, playable = true, type = MediaMetadata.MEDIA_TYPE_PLAYLIST))
            if (s.newSongs.isNotEmpty()) add(node(SHELF_NEW, "New from your PC", songCount(s.newSongs.size), browsable = true, playable = true, type = MediaMetadata.MEDIA_TYPE_PLAYLIST))
        }
        parentId == TAPES -> s.tapes.filter { it.songs.isNotEmpty() }.map { tapeNode(it) }
        parentId == ARTISTS -> s.index.artists.map { (name, songs) ->
            node("artist:$name", name, songCount(songs.size), browsable = true, playable = true, type = MediaMetadata.MEDIA_TYPE_ARTIST)
        }
        else -> pick(parentId, s)?.songs?.map { songItem(it, parentId) }
    }

    fun item(id: String, s: Snapshot): MediaItem? = when {
        id == ROOT -> root()
        id.startsWith(SONG) -> songOf(id, s)?.let { songItem(it, parentOf(id)) }
        else -> children(ROOT, s)?.firstOrNull { it.mediaId == id }
            ?: children(HOME, s)?.firstOrNull { it.mediaId == id }
            ?: s.tapes.firstOrNull { "tape:${it.ref.token()}" == id }?.let { tapeNode(it) }
            ?: id.removePrefix("artist:").takeIf { id.startsWith("artist:") && s.index.artist(it).isNotEmpty() }
                ?.let { node(id, it, songCount(s.index.artist(it).size), browsable = true, playable = true, type = MediaMetadata.MEDIA_TYPE_ARTIST) }
    }

    /** In-app search from Android Auto: artists and tapes by that name first, then songs. */
    fun search(query: String, s: Snapshot): List<MediaItem> {
        val r = Searcher.search(s.index, query, listOf(s.liked) + s.userTapes)
        return r.artists.take(3).map { (name, songs) -> node("artist:$name", name, songCount(songs.size), browsable = true, playable = true, type = MediaMetadata.MEDIA_TYPE_ARTIST) } +
            r.tapes.take(3).map { tapeNode(it) } +
            r.songs.map { songItem(it, "search:$query") }
    }

    // ---- playing ----

    /** What playing [id] means: a whole tape or shelf, or a song followed by the rest of its list. */
    fun pick(id: String, s: Snapshot): PlayPick? = when {
        id == ALL -> PlayPick(s.index.songs.shuffled(), 0, PlayCtx.ALL)
        id == SHELF_REPEAT -> PlayPick(s.onRepeat, 0, PlayCtx.oneOff("On repeat"))
        id == SHELF_NEW -> PlayPick(s.newSongs, 0, PlayCtx.oneOff("New from your PC"))
        id.startsWith("tape:") -> (CtxTarget.parse(id.removePrefix("tape:")) as? CtxTarget.OfTape)
            ?.let { s.tape(it.ref) }
            ?.let { PlayPick(it.songs, 0, PlayCtx.of(it.ref, it.name)) }
        id.startsWith("artist:") -> id.removePrefix("artist:").let { PlayPick(s.index.artist(it), 0, PlayCtx.artist(it)) }
        id.startsWith("search:") -> Searcher.search(s.index, id.removePrefix("search:"), listOf(s.liked) + s.userTapes).songs
            .let { PlayPick(it, 0, PlayCtx.oneOff("Search: “${id.removePrefix("search:")}”")) }
        id.startsWith("resume:") -> {
            val ref = id.removePrefix("resume:")
            val r = s.resumes.firstOrNull { it.ctxRef == ref }
            CtxTarget.parse(ref)?.let { target ->
                val songs = s.songsFor(target)
                val i = songs.indexOfFirst { it.key == r?.lastKey }
                val name = (target as? CtxTarget.OfTape)?.let { s.tape(it.ref)?.name } ?: r?.title ?: "Mixtape"
                PlayPick(songs, i.coerceAtLeast(0), PlayCtx(name, ref), if (i >= 0) r?.positionMs ?: 0L else 0L)
            }
        }
        id.startsWith(SONG) -> {
            val song = songOf(id, s)
            val list = pick(parentOf(id), s)
            when {
                song == null -> null
                list != null && song in list.songs -> list.copy(start = list.songs.indexOf(song), positionMs = 0L)
                else -> PlayPick(listOf(song), 0, PlayCtx.oneOff(song.title))
            }
        }
        else -> null
    }?.takeIf { it.songs.isNotEmpty() }

    fun isOurs(id: String) = id == ALL || PREFIXES.any { id.startsWith(it) }

    private fun songOf(id: String, s: Snapshot): Song? = s.index.byKey[id.removePrefix(SONG).substringBefore('@')]
    private fun parentOf(id: String) = id.substringAfter('@', "")

    private fun tapeNode(t: TapeHit) =
        node("tape:${t.ref.token()}", t.name, songCount(t.songs.size), browsable = true, playable = true, type = MediaMetadata.MEDIA_TYPE_PLAYLIST)

    private fun songItem(song: Song, parentId: String): MediaItem = MediaItem.Builder()
        .setMediaId("$SONG${song.key}@$parentId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.folder)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    private fun node(
        id: String,
        title: String,
        subtitle: String? = null,
        browsable: Boolean,
        playable: Boolean,
        type: Int,
        gridChildren: Boolean = false,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(browsable)
                .setIsPlayable(playable)
                .setMediaType(type)
                .setExtras(if (gridChildren) gridExtras() else null)
                .build()
        )
        .build()

    companion object {
        const val ROOT = "root"
        const val HOME = "home"
        const val TAPES = "tapes"
        const val ARTISTS = "artists"
        const val ALL = "all"
        const val SHELF_REPEAT = "shelf:repeat"
        const val SHELF_NEW = "shelf:new"
        private const val SONG = "song:"
        private val PREFIXES = listOf(SONG, "tape:", "artist:", "shelf:", "resume:", "search:")

        /** Root hints for Android Auto: tapes as a grid, songs as a list, and search is supported. */
        fun rootExtras(): Bundle = bundleOf(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE to MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE to MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            "android.media.browse.SEARCH_SUPPORTED" to true,
        )

        private fun gridExtras(): Bundle = bundleOf(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE to MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
        )
    }
}

package com.joenet.mixtape

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class Song(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    /** Folder the file lives in; the importer puts each YouTube playlist in its own folder. */
    val folder: String,
    val relativePath: String,
    val durationMs: Long,
    val track: Int,
    /** Seconds since epoch, from MediaStore (when the file arrived on the phone). */
    val dateAdded: Long,
    val displayName: String,
) {
    val mediaId: String get() = uri.toString()

    /** The YouTube video id the importer puts in every file name ("Title [dQw4w9WgXcQ].mp3"). */
    val videoId: String? = VIDEO_ID.find(displayName)?.groupValues?.get(1)

    /**
     * Stable identity for history, likes and tapes. The video id survives a rescan or re-sync;
     * MediaStore ids don't. Files without one (copied by USB) fall back to their content URI.
     */
    val key: String = videoId?.let { "yt:$it" } ?: mediaId

    /** Individual artists: "A, B feat. C" credits each of them. */
    val artists: List<String> = splitArtists(artist)

    /** Accent-free lowercase text for search: "son tung" finds "Sơn Tùng". */
    val searchKey: String = Fold.key("$title $artist $album $folder")
    val titleKey: String = Fold.key(title)

    val tapeKey: String get() = relativePath.lowercase()

    /**
     * A queue entry. [ctx] names where it's playing from ("Tape: Chill"); [queued] marks songs
     * added with Play next / Add to queue; every entry gets its own id so duplicates stay distinct.
     */
    fun toMediaItem(ctx: PlayCtx? = null, queued: Boolean = false): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(
                    bundleOf(
                        EXTRA_FOLDER to folder,
                        EXTRA_KEY to key,
                        EXTRA_CTX to ctx?.name,
                        EXTRA_CTX_REF to ctx?.ref,
                        EXTRA_QID to UUID.randomUUID().toString(),
                        EXTRA_QUEUED to queued,
                    )
                )
                .build()
        )
        .build()

    companion object {
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_KEY = "key"
        const val EXTRA_CTX = "ctx"
        const val EXTRA_CTX_REF = "ctxRef"
        const val EXTRA_QID = "qid"
        const val EXTRA_QUEUED = "queued"
        private val VIDEO_ID = Regex("""\[([A-Za-z0-9_-]{11})]\.[A-Za-z0-9]+$""")

        // Only commas and "feat.": the importer joins real multi-artist credits with ", ", while
        // "&" and "/" are often part of a single name (Simon & Garfunkel, AC/DC).
        fun splitArtists(credit: String): List<String> =
            credit.split(Regex("""\s*(?:,|\bfeat\.|\bfeat\b|\bft\.|\bft\b|\bfeaturing\b)\s*""", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf(credit) }
                .distinct()
    }
}

val MediaItem.qid: String get() = mediaMetadata.extras?.getString(Song.EXTRA_QID) ?: mediaId
val MediaItem.songKey: String get() = mediaMetadata.extras?.getString(Song.EXTRA_KEY) ?: mediaId
val MediaItem.isQueued: Boolean get() = mediaMetadata.extras?.getBoolean(Song.EXTRA_QUEUED) == true
val MediaItem.ctx: String? get() = mediaMetadata.extras?.getString(Song.EXTRA_CTX)
val MediaItem.ctxRef: String? get() = mediaMetadata.extras?.getString(Song.EXTRA_CTX_REF)
val MediaItem.folder: String get() = mediaMetadata.extras?.getString(Song.EXTRA_FOLDER).orEmpty()

/** A folder of songs, i.e. one imported YouTube playlist (or any album folder copied to the phone). */
data class Playlist(val key: String, val name: String, val songs: List<Song>) {
    val durationMs: Long = songs.sumOf { it.durationMs }
}

/** Everything the screens look songs up by, rebuilt whenever MediaStore is re-read. */
class LibraryIndex(val songs: List<Song>) {
    val tapes: List<Playlist> = MusicLibrary.playlists(songs)
    val byKey: Map<String, Song> = songs.associateBy { it.key }
    val byMediaId: Map<String, Song> = songs.associateBy { it.mediaId }
    val tapeByKey: Map<String, Playlist> = tapes.associateBy { it.key }

    /** Artist name -> their songs, crediting every artist on a song. */
    val artists: Map<String, List<Song>> = buildMap<String, MutableList<Song>> {
        for (s in songs) for (a in s.artists) getOrPut(a) { mutableListOf() }.add(s)
    }.toSortedMap(String.CASE_INSENSITIVE_ORDER)

    fun artist(name: String): List<Song> = artists[name].orEmpty()

    companion object {
        val EMPTY = LibraryIndex(emptyList())
    }
}

object MusicLibrary {

    val collection: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    suspend fun load(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 5000"
        val songs = ArrayList<Song>()
        runCatching {
            context.contentResolver.query(collection, projection, selection, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iPath = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val iBucket = c.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val fileName = c.getString(iName).orEmpty()
                    val title = c.getString(iTitle)?.takeIf { it.isNotBlank() }
                        ?: fileName.substringBeforeLast('.')
                    val artist = c.getString(iArtist)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                        ?: "Unknown artist"
                    val relPath = c.getString(iPath).orEmpty()
                    songs += Song(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = title,
                        artist = artist,
                        album = c.getString(iAlbum).orEmpty(),
                        folder = c.getString(iBucket) ?: relPath.trimEnd('/').substringAfterLast('/'),
                        relativePath = relPath,
                        durationMs = c.getLong(iDur),
                        track = c.getInt(iTrack) % 1000, // MediaStore stores disc * 1000 + track
                        dateAdded = c.getLong(iAdded),
                        displayName = fileName,
                    )
                }
            }
        }
        songs.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        songs
    }

    fun playlists(songs: List<Song>): List<Playlist> =
        songs.groupBy { it.tapeKey }
            .map { (key, list) ->
                Playlist(
                    key = key,
                    name = list.first().folder,
                    songs = list.sortedWith(compareBy<Song> { if (it.track > 0) it.track else Int.MAX_VALUE }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "6 h 12 min" / "48 min" */
fun formatLong(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

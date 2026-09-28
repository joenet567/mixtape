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
    val dateAdded: Long,
) {
    val mediaId: String get() = uri.toString()

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(bundleOf(EXTRA_FOLDER to folder))
                .build()
        )
        .build()

    companion object {
        const val EXTRA_FOLDER = "folder"
    }
}

/** A folder of songs, i.e. one imported YouTube playlist (or any album folder copied to the phone). */
data class Playlist(val key: String, val name: String, val songs: List<Song>) {
    val durationMs: Long = songs.sumOf { it.durationMs }
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
                )
            }
        }
        songs.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        songs
    }

    fun playlists(songs: List<Song>): List<Playlist> =
        songs.groupBy { it.relativePath.lowercase() }
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

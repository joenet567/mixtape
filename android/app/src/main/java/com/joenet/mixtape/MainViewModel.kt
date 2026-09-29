package com.joenet.mixtape

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.joenet.mixtape.data.Like
import com.joenet.mixtape.data.MixtapeDb
import com.joenet.mixtape.data.Play
import com.joenet.mixtape.data.Resume
import com.joenet.mixtape.data.TapeTrack
import com.joenet.mixtape.data.TapeWithTracks
import com.joenet.mixtape.data.UserTape
import com.joenet.mixtape.ui.Tape
import com.joenet.mixtape.ui.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A "Jump back in" card: a tape (or artist, or everything) and where you stopped in it. */
data class ResumeCard(val resume: Resume, val target: CtxTarget, val name: String, val songs: List<Song>, val tape: TapeHit?)

/** This month on the deck, Wrapped-style but all year round. */
data class RewindSummary(
    val monthLabel: String,
    val listenedMs: Long,
    val plays: Int,
    val topSongs: List<Pair<Song, Int>>,
    val topArtists: List<Pair<String, Int>>,
)

data class HomeShelves(
    val jumpBackIn: List<ResumeCard> = emptyList(),
    val newSongs: List<Song> = emptyList(),
    val newFromLastSync: Boolean = false,
    val onRepeat: List<Song> = emptyList(),
    val forgotten: List<Song> = emptyList(),
    val rewind: RewindSummary? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val player = PlayerUi()
    private val db = MixtapeDb.get(app)

    var library by mutableStateOf(LibraryIndex.EMPTY)
        private set
    val songs: List<Song> get() = library.songs
    var loaded by mutableStateOf(false)
        private set
    var hasPermission by mutableStateOf(hasAudioPermission(app))
        private set

    // ---- your data (Room), mirrored as Compose state ----

    var likedKeys by mutableStateOf<List<String>>(emptyList())
        private set
    var likedSet by mutableStateOf<Set<String>>(emptySet())
        private set
    var userTapes by mutableStateOf<List<TapeWithTracks>>(emptyList())
        private set
    var recentPlays by mutableStateOf<List<Play>>(emptyList())
        private set
    private var resumes by mutableStateOf<List<Resume>>(emptyList())
    var home by mutableStateOf(HomeShelves())
        private set

    init {
        viewModelScope.launch {
            db.likes().all().collect { list ->
                likedKeys = list.map { it.key }
                likedSet = likedKeys.toHashSet()
            }
        }
        viewModelScope.launch {
            combine(db.tapes().tapes(), db.tapes().tracks()) { tapes, tracks ->
                val byTape = tracks.groupBy { it.tapeId }
                tapes.map { TapeWithTracks(it, byTape[it.id].orEmpty().sortedWith(compareBy({ t -> t.side }, { t -> t.position }))) }
            }.collect { userTapes = it }
        }
        viewModelScope.launch {
            db.history().recent(60).collect {
                recentPlays = it
                refreshHome()
            }
        }
        viewModelScope.launch {
            db.history().resumes(6).collect {
                resumes = it
                refreshHome()
            }
        }
        AutoSync.schedule(app)
    }

    // ---- settings ----

    var evenLoudness by mutableStateOf(AppSettings.evenLoudness(app))
        private set
    var autoSync by mutableStateOf(AppSettings.autoSync(app))
        private set
    /** Now Playing shows the lyrics under a smaller cassette (remembered between sessions). */
    var showLyrics by mutableStateOf(AppSettings.showLyrics(app))
        private set
    // Backing state is separate from the read-only `themeMode`: a `var themeMode` would generate a JVM
    // setThemeMode(ThemeMode) that clashes with the public setter function below.
    private var themeModeState by mutableStateOf(ThemeMode.fromKey(AppSettings.themeMode(app)))

    /** Light, dark, or follow the phone (remembered between sessions). Change it with [setThemeMode]. */
    val themeMode: ThemeMode get() = themeModeState

    fun setThemeMode(mode: ThemeMode) {
        themeModeState = mode
        AppSettings.setThemeMode(getApplication(), mode.key)
    }

    fun updateEvenLoudness(on: Boolean) {
        evenLoudness = on
        AppSettings.set(getApplication(), AppSettings.EVEN_LOUDNESS, on)
    }

    fun updateAutoSync(on: Boolean) {
        autoSync = on
        AppSettings.set(getApplication(), AppSettings.AUTO_SYNC, on)
        AutoSync.schedule(getApplication())
    }

    fun updateShowLyrics(on: Boolean) {
        showLyrics = on
        AppSettings.set(getApplication(), AppSettings.SHOW_LYRICS, on)
    }

    /** Lyrics the PC sent for this song, if any. */
    suspend fun lyricsFor(songKey: String?): Lyrics? = withContext(Dispatchers.IO) {
        val id = Sidecar.videoIdOf(songKey) ?: return@withContext null
        val file = Sidecar.lyricsFile(getApplication(), id)
        if (file.isFile) runCatching { Lyrics.parse(file.readText()) }.getOrNull() else null
    }

    // ---- navigation: three tabs, each with its own back stack ----

    var tab by mutableStateOf(Tab.Home)
        private set
    private val stacks: Map<Tab, SnapshotStateList<Route>> = Tab.entries.associateWith { mutableStateListOf<Route>(Route.Root) }
    fun stack(t: Tab): List<Route> = stacks.getValue(t)
    val route: Route get() = stacks.getValue(tab).last()

    /** The Now Playing sheet: pulled up over everything, or collapsed into the mini player. */
    var playerExpanded by mutableStateOf(false)

    /** Long-pressed song whose action sheet is showing. */
    var actionsFor by mutableStateOf<Song?>(null)

    /** Songs waiting for the "Add to tape…" picker. */
    var addToTape by mutableStateOf<List<Song>?>(null)

    fun open(route: Route) {
        val s = stacks.getValue(tab)
        if (s.last() != route) s += route
        playerExpanded = false
    }

    /** Tapping the tab you're on goes back to its top, like Spotify. */
    fun select(t: Tab) {
        if (t == tab) {
            val s = stacks.getValue(t)
            while (s.size > 1) s.removeAt(s.lastIndex)
        } else {
            tab = t
        }
    }

    val canGoBack: Boolean get() = playerExpanded || stacks.getValue(tab).size > 1 || tab != Tab.Home

    fun back(): Boolean {
        if (playerExpanded) {
            playerExpanded = false
            return true
        }
        val s = stacks.getValue(tab)
        if (s.size > 1) {
            s.removeAt(s.lastIndex)
            return true
        }
        if (tab != Tab.Home) {
            tab = Tab.Home
            return true
        }
        return false
    }

    // ---- a short confirmation line ("Added to queue") ----

    var notice by mutableStateOf<String?>(null)
        private set
    private var noticeJob: Job? = null

    fun notify(text: String) {
        notice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(2200)
            notice = null
        }
    }

    // ---- library ----

    fun onPermissionResult() {
        hasPermission = hasAudioPermission(getApplication())
        reloadLibrary()
    }

    fun reloadLibrary() {
        if (!hasPermission) return
        viewModelScope.launch {
            library = LibraryIndex(MusicLibrary.load(getApplication()))
            loaded = true
            refreshHome()
        }
    }

    fun songFor(key: String?): Song? = key?.let { library.byKey[it] ?: library.byMediaId[it] }

    private fun songsFor(keys: List<String>): List<Song> = keys.mapNotNull { library.byKey[it] }

    // ---- tapes: folders, Liked songs, and the ones you record ----

    val likedTape: TapeHit get() = TapeHit(TapeRef.Liked, "Liked songs", songsFor(likedKeys))

    fun userTapeHit(t: TapeWithTracks): TapeHit = TapeHit(TapeRef.User(t.tape.id), t.tape.name, songsFor(t.tracks.map { it.key }))

    fun userTape(id: Long): TapeWithTracks? = userTapes.firstOrNull { it.tape.id == id }

    fun tape(ref: TapeRef): TapeHit? = when (ref) {
        is TapeRef.Folder -> library.tapeByKey[ref.key]?.let { TapeHit(ref, it.name, it.songs) }
        is TapeRef.User -> userTape(ref.id)?.let { userTapeHit(it) }
        TapeRef.Liked -> likedTape
    }

    /** Tapes search should find besides the folder tapes. */
    val searchExtras: List<TapeHit> get() = listOf(likedTape) + userTapes.map { userTapeHit(it) }

    /** Every tape in the library: Liked songs, then yours, then the synced folders. */
    fun libraryTapes(): List<TapeHit> =
        listOf(likedTape) + userTapes.map { userTapeHit(it) } +
            library.tapes.map { TapeHit(TapeRef.Folder(it.key), it.name, it.songs) }

    /** Is the current song playing from [hit]? (Its reels turn.) */
    fun isPlayingFrom(hit: TapeHit): Boolean = when (val ref = hit.ref) {
        // by file, not by key: the same video can sit in two playlists' folders
        is TapeRef.Folder -> player.currentItem?.ctxRef?.let { it == ref.token() }
            ?: (player.mediaId?.let { library.byMediaId[it] }?.tapeKey == ref.key)
        else -> player.currentItem?.ctxRef == ref.token()
    }

    fun labelColor(hit: TapeHit): Color = when (val ref = hit.ref) {
        is TapeRef.Folder -> Tape.labelColor(hit.name)
        is TapeRef.User -> Tape.labels[(userTape(ref.id)?.tape?.color ?: 0).mod(Tape.labels.size)]
        TapeRef.Liked -> Tape.Brick
    }

    fun ctxFor(hit: TapeHit) = PlayCtx.of(hit.ref, hit.name)

    // ---- likes ----

    fun isLiked(song: Song) = song.key in likedSet

    fun toggleLike(song: Song) {
        val was = isLiked(song)
        viewModelScope.launch(Dispatchers.IO) {
            if (was) db.likes().unlike(song.key)
            else db.likes().like(Like(song.key, song.title, song.artist, System.currentTimeMillis()))
        }
        notify(if (was) "Removed from Liked songs" else "Added to Liked songs")
    }

    // ---- recording your own tapes ----

    fun createTape(name: String, color: Int, songs: List<Song> = emptyList(), openIt: Boolean = true) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = withContext(Dispatchers.IO) {
                val id = db.tapes().insert(UserTape(name = name.trim().ifEmpty { "Untitled tape" }, color = color, createdAt = now, updatedAt = now))
                if (songs.isNotEmpty()) db.tapes().setTracks(id, songs.map { it.track(id) })
                id
            }
            notify(if (songs.isEmpty()) "Recorded a blank tape" else "Recorded ${songCount(songs.size)} onto “${name.trim()}”")
            if (openIt) open(Route.Tape(TapeRef.User(id)))
        }
    }

    private fun Song.track(tapeId: Long, side: Int = 0) = TapeTrack(tapeId, 0, key, side, title, artist)

    fun addSongsToTape(tapeId: Long, songs: List<Song>) {
        viewModelScope.launch {
            val t = userTape(tapeId) ?: return@launch
            // new songs go on the last side in use
            val side = t.tracks.maxOfOrNull { it.side } ?: 0
            withContext(Dispatchers.IO) { db.tapes().setTracks(tapeId, t.tracks + songs.map { it.track(tapeId, side) }) }
            notify("Added to “${t.tape.name}”")
        }
    }

    fun setTapeTracks(tapeId: Long, tracks: List<TapeTrack>) {
        viewModelScope.launch(Dispatchers.IO) { db.tapes().setTracks(tapeId, tracks) }
    }

    fun updateTape(tape: UserTape) {
        viewModelScope.launch(Dispatchers.IO) { db.tapes().update(tape.copy(updatedAt = System.currentTimeMillis())) }
    }

    fun deleteTape(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { db.tapes().delete(id) }
        back()
        notify("Tape erased")
    }

    fun saveQueueAsTape(name: String) {
        val songs = player.queueKeys().mapNotNull { songFor(it) }
        if (songs.isEmpty()) return
        createTape(name, Tape.labels.indices.random(), songs, openIt = false)
    }

    /**
     * Share a tape as an .m3u8 of YouTube links: the PC importer can import it as a playlist
     * (`import.bat friend.m3u8`). Songs without a video id are listed as comments.
     */
    fun exportTape(tapeId: Long, uri: Uri) {
        val t = userTape(tapeId) ?: return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val text = buildString {
                append("#EXTM3U\n#PLAYLIST:${t.tape.name}\n")
                for (track in t.tracks) {
                    val song = library.byKey[track.key]
                    val secs = (song?.durationMs ?: 0) / 1000
                    append("#EXTINF:$secs,${track.artist} - ${track.title}\n")
                    val id = track.key.removePrefix("yt:").takeIf { track.key.startsWith("yt:") }
                    append(if (id != null) "https://www.youtube.com/watch?v=$id\n" else "# (not from YouTube) ${track.title}\n")
                }
            }
            runCatching { app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
        }
        notify("Tape shared as a playlist file")
    }

    // ---- home: shelves that know your habits ----

    fun songsFor(target: CtxTarget): List<Song> = when (target) {
        CtxTarget.Everything -> songs
        is CtxTarget.OfArtist -> library.artist(target.name)
        is CtxTarget.OfTape -> tape(target.ref)?.songs.orEmpty()
    }

    /** A queue that grows out of one song: its artists, its tapes, what you play alongside it. */
    fun startRadio(song: Song) {
        viewModelScope.launch {
            val songs = Radio.build(getApplication(), song, library, libraryTapes())
            player.play(songs, 0, PlayCtx.oneOff("${song.title} radio"))
            notify("Radio from “${song.title}”")
        }
    }

    /** Continue a tape where it stopped. */
    fun resume(card: ResumeCard) {
        val i = card.songs.indexOfFirst { it.key == card.resume.lastKey }
        player.play(card.songs, i.coerceAtLeast(0), PlayCtx(card.name, card.resume.ctxRef), if (i >= 0) card.resume.positionMs else 0L)
    }

    private var homeJob: Job? = null

    fun refreshHome() {
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            val lib = library
            if (lib.songs.isEmpty()) {
                home = HomeShelves()
                return@launch
            }
            val dao = db.history()
            val now = System.currentTimeMillis()
            val day = 86_400_000L
            val shelves = withContext(Dispatchers.IO) {
                val jump = resumes.mapNotNull { r ->
                    val target = CtxTarget.parse(r.ctxRef) ?: return@mapNotNull null
                    val list = songsFor(target)
                    if (list.isEmpty()) return@mapNotNull null
                    val tape = (target as? CtxTarget.OfTape)?.let { tape(it.ref) }
                    ResumeCard(r, target, tape?.name ?: r.title, list, tape)
                }
                val sync = getApplication<Application>().getSharedPreferences("sync", Context.MODE_PRIVATE)
                val lastSync = sync.getLong("lastAddedAt", 0L) / 1000
                val fromSync = if (lastSync > 0) lib.songs.filter { it.dateAdded >= lastSync - 60 } else emptyList()
                val fresh = fromSync.ifEmpty {
                    val since = now / 1000 - 14 * 86_400L
                    lib.songs.filter { it.dateAdded >= since }
                }.sortedByDescending { it.dateAdded }.take(20)
                HomeShelves(
                    jumpBackIn = jump,
                    newSongs = fresh,
                    newFromLastSync = fromSync.isNotEmpty(),
                    onRepeat = dao.top(now - 30 * day, 2, 12).mapNotNull { lib.byKey[it.key] },
                    forgotten = dao.forgotten(3, now - 60 * day, 12).mapNotNull { lib.byKey[it.key] },
                    rewind = rewindFor(now),
                )
            }
            home = shelves
        }
    }

    /** This calendar month's listening, for the Rewind tape. */
    private suspend fun rewindFor(now: Long): RewindSummary? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val dao = db.history()
        val plays = dao.playsBetween(start, now + 1)
        if (plays == 0) return null
        return RewindSummary(
            monthLabel = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(start)),
            listenedMs = dao.listenedMs(start, now + 1),
            plays = plays,
            topSongs = dao.topBetween(start, now + 1, 5).mapNotNull { k -> library.byKey[k.key]?.let { it to k.plays } },
            topArtists = dao.topArtistsBetween(start, now + 1, 3).map { it.artist to it.plays },
        )
    }

    /** Distinct songs from your recent plays, newest first (Search's "Recently played"). */
    fun recentlyPlayed(limit: Int = 10): List<Song> =
        recentPlays.map { it.key }.distinct().mapNotNull { library.byKey[it] }.take(limit)

    // ---- search ----

    private val prefs get() = getApplication<Application>().getSharedPreferences("search", Context.MODE_PRIVATE)

    var recentSearches by mutableStateOf(loadRecent())
        private set

    private fun loadRecent(): List<String> = runCatching {
        val a = JSONArray(getApplication<Application>().getSharedPreferences("search", Context.MODE_PRIVATE).getString("recent", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun rememberSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        recentSearches = (listOf(q) + recentSearches.filterNot { it.equals(q, ignoreCase = true) }).take(10)
        prefs.edit().putString("recent", JSONArray(recentSearches).toString()).apply()
    }

    fun clearRecentSearches() {
        recentSearches = emptyList()
        prefs.edit().remove("recent").apply()
    }

    companion object {
        val audioPermission: String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE

        fun hasAudioPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
    }
}

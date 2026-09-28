package com.joenet.mixtape

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val player = PlayerUi()

    var library by mutableStateOf(LibraryIndex.EMPTY)
        private set
    val songs: List<Song> get() = library.songs
    var loaded by mutableStateOf(false)
        private set
    var hasPermission by mutableStateOf(hasAudioPermission(app))
        private set

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
        }
    }

    fun tape(ref: TapeRef): TapeHit? = when (ref) {
        is TapeRef.Folder -> library.tapeByKey[ref.key]?.let { TapeHit(ref, it.name, it.songs) }
        else -> null
    }

    /** Tapes search should find besides the folder tapes (your own tapes, Liked songs). */
    val searchExtras: List<TapeHit> get() = emptyList()

    /** Every tape in the library, in the order the Library grid shows them. */
    fun libraryTapes(): List<TapeHit> = library.tapes.map { TapeHit(TapeRef.Folder(it.key), it.name, it.songs) }

    /** Is the current song playing from [hit]? (Its reels turn.) */
    fun isPlayingFrom(hit: TapeHit): Boolean = when (val ref = hit.ref) {
        // by file, not by key: the same video can sit in two playlists' folders
        is TapeRef.Folder -> player.mediaId?.let { library.byMediaId[it] }?.tapeKey == ref.key
        else -> player.currentItem?.ctx == hit.name
    }

    fun labelColor(hit: TapeHit): androidx.compose.ui.graphics.Color = com.joenet.mixtape.ui.Tape.labelColor(hit.name)

    fun songFor(key: String?): Song? = key?.let { library.byKey[it] ?: library.byMediaId[it] }

    /** Songs added to the phone recently (newest first): the "New from your PC" shelf. */
    fun newSongs(days: Int = 14, limit: Int = 20): List<Song> {
        val since = System.currentTimeMillis() / 1000 - days * 86_400L
        return songs.filter { it.dateAdded >= since }.sortedByDescending { it.dateAdded }.take(limit)
    }

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

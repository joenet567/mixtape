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
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Library : Screen
    data class PlaylistDetail(val key: String) : Screen
    data object NowPlaying : Screen
    data object Sync : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val player = PlayerUi()

    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var hasPermission by mutableStateOf(hasAudioPermission(app))
        private set

    /** Tiny back stack instead of a navigation library; the last entry is on screen. */
    val backStack = mutableStateListOf<Screen>(Screen.Library)
    val screen: Screen get() = backStack.last()

    fun open(screen: Screen) {
        if (backStack.last() != screen) backStack += screen
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun onPermissionResult() {
        hasPermission = hasAudioPermission(getApplication())
        reloadLibrary()
    }

    fun reloadLibrary() {
        if (!hasPermission) return
        viewModelScope.launch {
            val list = MusicLibrary.load(getApplication())
            songs = list
            playlists = MusicLibrary.playlists(list)
            loaded = true
        }
    }

    fun playlist(key: String): Playlist? = playlists.firstOrNull { it.key == key }

    companion object {
        val audioPermission: String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE

        fun hasAudioPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
    }
}

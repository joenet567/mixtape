package com.joenet.mixtape

import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.joenet.mixtape.ui.MixtapeApp
import com.joenet.mixtape.ui.MixtapeTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermissionResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so always use light system-bar icons (the default follows the system theme).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            MixtapeTheme {
                MixtapeApp(vm, onRequestPermission = ::requestAudioPermission)
            }
        }
        if (savedInstanceState == null) {
            if (!vm.hasPermission) requestAudioPermission()
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Voice request waiting for the player connection. */
    private var pendingSearch: String? = null

    /**
     * Tapping the media notification (or the widget's tape) opens straight to the player screen;
     * "Hey Google, play … on Mixtape" arrives as a search to play.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) vm.playerExpanded = true
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            pendingSearch = intent.getStringExtra(SearchManager.QUERY).orEmpty()
            playPendingSearch()
        }
    }

    private fun playPendingSearch() {
        val query = pendingSearch ?: return
        if (vm.player.controller == null) return // runs again once the controller connects
        pendingSearch = null
        vm.player.playFromSearch(query)
        vm.playerExpanded = true
    }

    private fun requestAudioPermission() = permissionLauncher.launch(MainViewModel.audioPermission)

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) = vm.player.onExtras(extras)
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture === future && !future.isCancelled) {
                runCatching { future.get() }.getOrNull()?.let {
                    vm.player.attach(it)
                    playPendingSearch()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        vm.onPermissionResult() // picks up a permission granted from Settings, and new files
    }

    override fun onStop() {
        vm.player.detach()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

package com.joenet.mixtape

import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.joenet.mixtape.ui.MixtapeApp
import com.joenet.mixtape.ui.MixtapeTheme
import com.joenet.mixtape.ui.Tape
import com.joenet.mixtape.ui.ThemeMode

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermissionResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge with the right icon colours before the first frame; SystemBarsFollowTheme keeps them right after that.
        applySystemBars(isDark(vm.themeMode))
        setContent {
            MixtapeTheme(vm.themeMode) {
                SystemBarsFollowTheme()
                MixtapeApp(vm, onRequestPermission = ::requestAudioPermission)
            }
        }
        if (savedInstanceState == null) {
            if (!vm.hasPermission) requestAudioPermission()
            handleIntent(intent)
        }
    }

    /** The theme the app will resolve to: System follows the phone's night mode. */
    private fun isDark(mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System ->
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Transparent system bars with light icons on a dark theme and dark icons on a light one, and a window
     * background that matches the theme (it shows behind the app while it resizes, and after a rotation).
     * SystemBarStyle.dark/light rather than .auto: they also switch off the extra nav-bar scrim on API 29.
     */
    private fun applySystemBars(dark: Boolean) {
        val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        window.setBackgroundDrawable(ColorDrawable(if (dark) DARK_WINDOW else LIGHT_WINDOW))
    }

    /** Re-applies the bars whenever the resolved theme flips (Settings, the header toggle, or the phone's night mode). */
    @Composable
    private fun SystemBarsFollowTheme() {
        val dark = Tape.isDark
        DisposableEffect(dark) {
            applySystemBars(dark)
            onDispose {}
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

        // Tape.Bg in each theme, used as the window background
        private val DARK_WINDOW = 0xFF14110E.toInt()
        private val LIGHT_WINDOW = 0xFFF7F1E7.toInt()
    }
}

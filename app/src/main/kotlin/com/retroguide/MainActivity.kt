package com.retroguide

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.retroguide.ui.RetroGuideViewModel
import com.retroguide.ui.Screen
import com.retroguide.ui.guide.GuideScreen
import com.retroguide.ui.login.ImportScreen
import com.retroguide.ui.login.LoginScreen
import com.retroguide.ui.player.WatchingOverlay
import com.retroguide.ui.settings.SettingsScreen
import com.retroguide.ui.theme.GuideTheme
import com.retroguide.ui.theme.RetroGuideTheme
import kotlinx.coroutines.delay

/**
 * The only activity.
 *
 * Everything is a Compose destination inside it rather than a separate activity, for one concrete
 * reason: the ExoPlayer instance lives in [AppContainer] and its surface lives in this composition.
 * Splitting the guide into its own activity would tear the surface down and rebuild it on every
 * trip between watching and browsing, which is the single most common thing a viewer does.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps the screen on while watching. A television with no input for ten minutes should
        // not blank out in the middle of a film.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { RetroGuideRoot() }
    }
}

@Composable
private fun RetroGuideRoot() {
    val viewModel: RetroGuideViewModel = viewModel()
    val ui by viewModel.ui.collectAsState()
    val guide by viewModel.guide.collectAsState()
    val playback by viewModel.playback.collectAsState()
    val banner by viewModel.banner.collectAsState()

    val theme = GuideTheme.Default
    val focusRequester = remember { FocusRequester() }

    // The clock in the guide and the banner's progress bar are only honest if something moves
    // them. Once a minute is enough and costs nothing.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            viewModel.tick()
        }
    }

    LaunchedEffect(ui.screen) {
        if (ui.screen != Screen.Login) focusRequester.requestFocus()
    }

    RetroGuideTheme(theme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.background)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event -> handleKey(event, ui.screen, viewModel) }
        ) {
            when (ui.screen) {
                Screen.Starting -> Unit

                Screen.Login -> LoginScreen(
                    theme = theme,
                    error = ui.loginError,
                    isSigningIn = ui.isSigningIn,
                    credentialsEncrypted = ui.credentialsEncrypted,
                    onSubmit = viewModel::signIn,
                )

                Screen.Importing -> ImportScreen(
                    theme = theme,
                    message = ui.importMessage.orEmpty(),
                    detail = ui.importDetail,
                )

                Screen.Watching -> {
                    VideoSurface(Modifier.fillMaxSize())
                    WatchingOverlay(
                        playback = playback,
                        banner = banner,
                        nowMs = guide.nowMs,
                        theme = theme,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Screen.Guide -> GuideScreen(
                    state = guide,
                    theme = theme,
                    previewContent = { modifier -> VideoSurface(modifier) },
                    modifier = Modifier.fillMaxSize(),
                )

                Screen.Settings -> SettingsScreen(
                    ui = ui,
                    theme = theme,
                    onCountries = viewModel::setCountries,
                    onMarkets = viewModel::setMarkets,
                    onExclusions = viewModel::setExclusions,
                    onOffset = viewModel::setEpgOffsetHours,
                    onFormat = viewModel::setStreamFormat,
                    onRefreshEpg = viewModel::refreshEpgNow,
                    onSignOut = viewModel::signOut,
                    onClose = viewModel::closeSettings,
                )
            }

            if (ui.isFetchingMore) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    com.retroguide.ui.player.ReconnectingLabel(theme)
                }
            }
        }
    }
}

/**
 * The video surface.
 *
 * Bound to the one shared ExoPlayer. Used both full-screen and as the guide's preview window: the
 * same player, never a second stream, because an Xtream account's connection limit is commonly one
 * and opening two would lock the user out of their own service.
 */
@Composable
private fun VideoSurface(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                setKeepContentOnPlayerReset(true)
                player = container.player.exoPlayer
            }
        },
        update = { view -> view.player = container.player.exoPlayer },
    )

    DisposableEffect(Unit) {
        onDispose { /* the player outlives the surface; nothing to release here */ }
    }
}

/**
 * Remote-control routing, in one place.
 *
 * The rules are section 5.3 of the spec. The cursor logic they drive lives in
 * `core`'s GuideNavigator, where it is unit-tested; this only decides which method a key calls.
 */
private fun handleKey(
    event: KeyEvent,
    screen: Screen,
    viewModel: RetroGuideViewModel,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    return when (screen) {
        Screen.Watching -> when (event.key) {
            Key.DirectionUp -> { viewModel.channelUp(); true }
            Key.DirectionDown -> { viewModel.channelDown(); true }
            Key.DirectionCenter, Key.Enter -> { viewModel.watchingSelect(); true }
            Key.Menu -> { viewModel.openSettings(); true }
            // The Fire remote has no number pad, so Play/Pause carries "last channel" instead.
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { viewModel.lastChannel(); true }
            else -> false
        }

        Screen.Guide -> when (event.key) {
            Key.DirectionUp -> { viewModel.guideUp(); true }
            Key.DirectionDown -> { viewModel.guideDown(); true }
            Key.DirectionLeft -> { viewModel.guideLeft(); true }
            Key.DirectionRight -> { viewModel.guideRight(); true }
            Key.DirectionCenter, Key.Enter -> { viewModel.guideSelect(); true }
            Key.MediaRewind -> { viewModel.guidePageBack(); true }
            Key.MediaFastForward -> { viewModel.guidePageForward(); true }
            Key.Menu -> { viewModel.openSettings(); true }
            // Back closes the details dialog first when one is open, and only then the guide.
            Key.Back, Key.Escape -> { viewModel.guideBack(); true }
            else -> false
        }

        Screen.Settings -> when (event.key) {
            Key.Back, Key.Escape -> { viewModel.closeSettings(); true }
            else -> false
        }

        else -> false
    }
}

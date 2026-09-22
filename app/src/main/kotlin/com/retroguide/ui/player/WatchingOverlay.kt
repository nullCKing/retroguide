package com.retroguide.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retroguide.player.PlaybackState
import com.retroguide.ui.BannerState
import com.retroguide.ui.theme.GuideTheme
import kotlinx.coroutines.delay

/**
 * What sits on top of full-screen video: the channel banner after a change, a small
 * "Reconnecting…" label while a stalled stream is being retried, and an error panel once the
 * retry budget is spent.
 *
 * Nothing here is ever on screen for long. Full-screen playback is the app's primary job, and the
 * overlay's business is to get out of the way.
 */
@Composable
fun WatchingOverlay(
    playback: PlaybackState,
    banner: BannerState,
    nowMs: Long,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
) {
    var bannerVisible by remember { mutableStateOf(false) }

    // Restarts on every tune, including a re-tune to the same channel, because showToken changes.
    LaunchedEffect(banner.showToken) {
        if (banner.showToken == 0L) return@LaunchedEffect
        bannerVisible = true
        delay(theme.bannerVisibleMillis)
        bannerVisible = false
    }

    Box(modifier = modifier) {
        if (playback.isReconnecting) {
            ReconnectingLabel(
                theme = theme,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp),
            )
        }

        playback.error?.let { message ->
            PlaybackErrorPanel(
                message = message,
                theme = theme,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = bannerVisible && playback.error == null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            val channel = banner.channel
            if (channel != null) {
                ChannelBanner(
                    number = channel.number,
                    name = channel.name,
                    now = banner.now,
                    next = banner.next,
                    nowMs = nowMs,
                    theme = theme,
                    logoUrl = channel.logoUrl,
                    isFavorite = channel.isFavorite,
                )
            }
        }
    }
}

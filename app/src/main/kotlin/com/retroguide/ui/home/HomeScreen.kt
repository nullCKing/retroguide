package com.retroguide.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.retroguide.ui.guide.headerClock
import com.retroguide.ui.theme.GuideTheme

/**
 * 2000s DirecTV / Cable-styled interactive Home Screen.
 * Provides entry points to Live TV, On-Demand Movies, Series, Channel Guide, Favorites, and Settings.
 */
@Composable
fun HomeScreen(
    channelCount: Int,
    favoriteChannelCount: Int,
    nowMs: Long,
    theme: GuideTheme,
    onNavigateLiveTv: () -> Unit,
    onNavigateMovies: () -> Unit,
    onNavigateSeries: () -> Unit,
    onNavigateGuide: () -> Unit,
    onNavigateFavorites: () -> Unit,
    onNavigateSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val liveTvFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { liveTvFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(horizontal = 48.dp, vertical = 28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Header Banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(theme.panel)
                    .border(1.dp, theme.panelEdge, RoundedCornerShape(8.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "RETRO",
                            color = theme.highlight,
                            fontSize = theme.titleSize,
                            fontFamily = theme.fontFamily,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "★GUIDE",
                            color = theme.infoTitle,
                            fontSize = theme.titleSize,
                            fontFamily = theme.fontFamily,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "INTERACTIVE ENTERTAINMENT SYSTEM",
                        color = theme.infoDetail,
                        fontSize = theme.sectionSize,
                        fontFamily = theme.fontFamily,
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = headerClock(nowMs),
                        color = theme.clock,
                        fontSize = theme.clockSize,
                        fontFamily = theme.fontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "$channelCount CHANNELS LOADED",
                        color = theme.highlight,
                        fontSize = theme.sectionSize,
                        fontFamily = theme.fontFamily,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Main Menu 2x3 Grid
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Row 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HomeMenuTile(
                        title = "LIVE TV",
                        subtitle = "Browse categories, groups & channels",
                        badge = "TV",
                        theme = theme,
                        onClick = onNavigateLiveTv,
                        focusRequester = liveTvFocus,
                        modifier = Modifier.weight(1f),
                    )

                    HomeMenuTile(
                        title = "ON DEMAND MOVIES",
                        subtitle = "Feature films by category",
                        badge = "VOD",
                        theme = theme,
                        onClick = onNavigateMovies,
                        modifier = Modifier.weight(1f),
                    )

                    HomeMenuTile(
                        title = "TV SHOWS / SERIES",
                        subtitle = "Complete seasons & episode guide",
                        badge = "SERIES",
                        theme = theme,
                        onClick = onNavigateSeries,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Row 2
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HomeMenuTile(
                        title = "CHANNEL GUIDE",
                        subtitle = "Full-screen interactive grid guide",
                        badge = "EPG",
                        theme = theme,
                        onClick = onNavigateGuide,
                        modifier = Modifier.weight(1f),
                    )

                    HomeMenuTile(
                        title = "FAVORITES",
                        subtitle = if (favoriteChannelCount > 0) "$favoriteChannelCount starred channels" else "Curated channel & group favorites",
                        badge = "★",
                        theme = theme,
                        onClick = onNavigateFavorites,
                        modifier = Modifier.weight(1f),
                    )

                    HomeMenuTile(
                        title = "SETTINGS",
                        subtitle = "Filters, market rules & accounts",
                        badge = "CONFIG",
                        theme = theme,
                        onClick = onNavigateSettings,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Footer info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "▲▼◄► USE REMOTE ARROWS TO NAVIGATE  •  SELECT TO OPEN",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )

                Text(
                    text = "RETROGUIDE v1.0 (FIRE TV / ANDROID TV)",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }
        }
    }
}

@Composable
private fun HomeMenuTile(
    title: String,
    subtitle: String,
    badge: String,
    theme: GuideTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var hasFocus by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(if (hasFocus) theme.panelSelected else theme.panel)
            .border(
                width = if (hasFocus) 3.dp else 1.dp,
                color = if (hasFocus) theme.highlight else theme.panelEdge,
                shape = RoundedCornerShape(8.dp),
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { hasFocus = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                    onClick()
                    true
                } else false
            }
            .clickable { onClick() }
            .padding(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = title,
                    color = if (hasFocus) theme.cellText else theme.infoTitle,
                    fontSize = theme.titleSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = theme.titleWeight,
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (hasFocus) theme.highlight else theme.tabFillCurrent)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = badge,
                        color = if (hasFocus) theme.background else theme.cellText,
                        fontSize = theme.sectionSize,
                        fontFamily = theme.fontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text(
                text = subtitle,
                color = if (hasFocus) theme.highlight else theme.infoDetail,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
            )
        }
    }
}

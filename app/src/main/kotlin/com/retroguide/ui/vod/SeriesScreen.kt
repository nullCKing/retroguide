package com.retroguide.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.retroguide.core.model.RawCategory
import com.retroguide.core.model.RawEpisode
import com.retroguide.core.model.RawSeries
import com.retroguide.core.model.RawSeriesInfo
import com.retroguide.ui.theme.GuideTheme

/**
 * On-Demand TV Shows / Series browser screen.
 * Displays series categories, poster grid, and season/episode details.
 */
@Composable
fun SeriesScreen(
    categories: List<RawCategory>,
    seriesList: List<RawSeries>,
    selectedCategory: RawCategory?,
    isLoadingSeries: Boolean,
    selectedSeriesInfo: RawSeriesInfo?,
    isLoadingInfo: Boolean,
    theme: GuideTheme,
    onSelectCategory: (RawCategory) -> Unit,
    onSelectSeries: (RawSeries) -> Unit,
    onPlayEpisode: (episodeId: Long, extension: String?) -> Unit,
    onDismissDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstCategoryFocus = remember { FocusRequester() }
    LaunchedEffect(categories.isNotEmpty()) {
        if (categories.isNotEmpty()) {
            runCatching { firstCategoryFocus.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(horizontal = 40.dp, vertical = 24.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "TV SHOWS & SERIES • " + (selectedCategory?.categoryName ?: "ALL"),
                    color = theme.infoTitle,
                    fontSize = theme.titleSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = theme.titleWeight,
                )
                Text(
                    text = "${seriesList.size} shows",
                    color = theme.highlight,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxSize()) {
                // Category sidebar (Left 260dp)
                Column(
                    modifier = Modifier
                        .width(260.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.panel)
                        .border(1.dp, theme.panelEdge, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    Text(
                        text = "CATEGORIES",
                        color = theme.infoDetail,
                        fontSize = theme.sectionSize,
                        fontFamily = theme.fontFamily,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(categories) { index, cat ->
                            var hasFocus by remember { mutableStateOf(false) }
                            val isSelected = cat.categoryId == selectedCategory?.categoryId

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .then(if (index == 0) Modifier.focusRequester(firstCategoryFocus) else Modifier)
                                    .background(
                                        if (hasFocus) theme.panelSelected
                                        else if (isSelected) theme.tabFillCurrent
                                        else theme.panel
                                    )
                                    .border(
                                        width = if (hasFocus) 2.dp else 0.dp,
                                        color = if (hasFocus) theme.highlight else androidx.compose.ui.graphics.Color.Transparent,
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .onFocusChanged { hasFocus = it.isFocused }
                                    .focusable()
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                            onSelectCategory(cat)
                                            true
                                        } else false
                                    }
                                    .clickable { onSelectCategory(cat) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = cat.categoryName,
                                    color = if (hasFocus) theme.cellText else theme.infoTitle,
                                    fontSize = theme.sectionSize,
                                    fontFamily = theme.fontFamily,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                // Series Grid
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoadingSeries) {
                        CircularProgressIndicator(color = theme.highlight)
                    } else if (seriesList.isEmpty()) {
                        Text(
                            text = "No TV shows available in this category.",
                            color = theme.infoDetail,
                            fontSize = theme.detailSize,
                            fontFamily = theme.fontFamily,
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 140.dp),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(seriesList) { series ->
                                SeriesPosterCard(
                                    series = series,
                                    theme = theme,
                                    onClick = { onSelectSeries(series) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Series Details Modal Dialog
        if (selectedSeriesInfo != null || isLoadingInfo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoadingInfo) {
                    CircularProgressIndicator(color = theme.highlight)
                } else if (selectedSeriesInfo != null) {
                    SeriesDetailsDialog(
                        info = selectedSeriesInfo,
                        theme = theme,
                        onPlayEpisode = onPlayEpisode,
                        onDismiss = onDismissDialog,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesPosterCard(
    series: RawSeries,
    theme: GuideTheme,
    onClick: () -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (hasFocus) theme.panelSelected else theme.panel)
            .border(
                width = if (hasFocus) 2.dp else 1.dp,
                color = if (hasFocus) theme.highlight else theme.panelEdge,
                shape = RoundedCornerShape(6.dp),
            )
            .onFocusChanged { hasFocus = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                    onClick()
                    true
                } else false
            }
            .clickable { onClick() }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.67f)
                .clip(RoundedCornerShape(4.dp))
                .background(theme.background),
            contentAlignment = Alignment.Center,
        ) {
            if (!series.cover.isNullOrBlank()) {
                AsyncImage(
                    model = series.cover,
                    contentDescription = series.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "NO POSTER",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = series.name,
            color = if (hasFocus) theme.cellText else theme.infoTitle,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SeriesDetailsDialog(
    info: RawSeriesInfo,
    theme: GuideTheme,
    onPlayEpisode: (episodeId: Long, extension: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var activeSeason by remember(info) { mutableStateOf(info.seasons.firstOrNull() ?: 1) }

    Box(
        modifier = Modifier
            .width(760.dp)
            .height(500.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.panel)
            .border(2.dp, theme.highlight, RoundedCornerShape(8.dp))
            .padding(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Cover
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .aspectRatio(0.67f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(theme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!info.cover.isNullOrBlank()) {
                        AsyncImage(
                            model = info.cover,
                            contentDescription = info.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text("POSTER", color = theme.infoDetail, fontSize = theme.sectionSize)
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info.name,
                        color = theme.infoTitle,
                        fontSize = theme.titleSize,
                        fontFamily = theme.fontFamily,
                        fontWeight = theme.titleWeight,
                    )
                    Spacer(Modifier.height(4.dp))
                    val plot = info.plot
                    if (!plot.isNullOrBlank()) {
                        Text(
                            text = plot,
                            color = theme.cellText,
                            fontSize = theme.detailSize,
                            fontFamily = theme.fontFamily,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Season Tabs
            if (info.seasons.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(info.seasons) { seasonNum ->
                        var isFocused by remember { mutableStateOf(false) }
                        val isSelected = seasonNum == activeSeason

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isFocused) theme.highlight
                                    else if (isSelected) theme.tabFillCurrent
                                    else theme.panelSelected
                                )
                                .border(1.dp, theme.panelEdge, RoundedCornerShape(4.dp))
                                .onFocusChanged { isFocused = it.isFocused }
                                .focusable()
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                        activeSeason = seasonNum
                                        true
                                    } else false
                                }
                                .clickable { activeSeason = seasonNum }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "Season $seasonNum",
                                color = if (isFocused) theme.background else theme.cellText,
                                fontSize = theme.sectionSize,
                                fontFamily = theme.fontFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // Episode list for active season
            val currentEpisodes = info.episodes[activeSeason].orEmpty()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(theme.background)
                    .padding(8.dp),
            ) {
                if (currentEpisodes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No episodes found for this season.", color = theme.infoDetail)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(currentEpisodes) { ep ->
                            EpisodeRow(
                                episode = ep,
                                theme = theme,
                                onClick = { onPlayEpisode(ep.id, ep.containerExtension) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Footer / Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "Press BACK to close",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: RawEpisode,
    theme: GuideTheme,
    onClick: () -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (hasFocus) theme.panelSelected else theme.panel)
            .border(
                width = if (hasFocus) 2.dp else 0.dp,
                color = if (hasFocus) theme.highlight else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .onFocusChanged { hasFocus = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                    onClick()
                    true
                } else false
            }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "E${episode.episodeNum}",
                color = theme.highlight,
                fontSize = theme.sectionSize,
                fontFamily = theme.fontFamily,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = episode.title.ifBlank { "Episode ${episode.episodeNum}" },
                color = if (hasFocus) theme.cellText else theme.infoTitle,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = "▶ PLAY",
            color = if (hasFocus) theme.highlight else theme.infoDetail,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

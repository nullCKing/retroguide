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
import com.retroguide.core.model.RawVodInfo
import com.retroguide.core.model.RawVodStream
import com.retroguide.ui.theme.GuideTheme

/**
 * On-Demand Movies browser screen.
 * Displays VOD categories, a poster grid for the active category, and a details dialog.
 */
@Composable
fun VodScreen(
    categories: List<RawCategory>,
    movies: List<RawVodStream>,
    selectedCategory: RawCategory?,
    isLoadingMovies: Boolean,
    selectedMovieInfo: RawVodInfo?,
    isLoadingInfo: Boolean,
    theme: GuideTheme,
    onSelectCategory: (RawCategory) -> Unit,
    onSelectMovie: (RawVodStream) -> Unit,
    onPlayMovie: (streamId: Long, extension: String?) -> Unit,
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
                    text = "ON DEMAND MOVIES • " + (selectedCategory?.categoryName ?: "ALL"),
                    color = theme.infoTitle,
                    fontSize = theme.titleSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = theme.titleWeight,
                )
                Text(
                    text = "${movies.size} titles",
                    color = theme.highlight,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxSize()) {
                // Category sidebar (Left 280dp)
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

                // Movies Grid
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoadingMovies) {
                        CircularProgressIndicator(color = theme.highlight)
                    } else if (movies.isEmpty()) {
                        Text(
                            text = "No movies available in this category.",
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
                            items(movies) { movie ->
                                MoviePosterCard(
                                    movie = movie,
                                    theme = theme,
                                    onClick = { onSelectMovie(movie) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Movie Details Modal Dialog
        if (selectedMovieInfo != null || isLoadingInfo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoadingInfo) {
                    CircularProgressIndicator(color = theme.highlight)
                } else if (selectedMovieInfo != null) {
                    MovieDetailsDialog(
                        info = selectedMovieInfo,
                        theme = theme,
                        onPlay = { onPlayMovie(selectedMovieInfo.streamId, selectedMovieInfo.containerExtension) },
                        onDismiss = onDismissDialog,
                    )
                }
            }
        }
    }
}

@Composable
private fun MoviePosterCard(
    movie: RawVodStream,
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
            if (!movie.streamIcon.isNullOrBlank()) {
                AsyncImage(
                    model = movie.streamIcon,
                    contentDescription = movie.name,
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
            text = movie.name,
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
private fun MovieDetailsDialog(
    info: RawVodInfo,
    theme: GuideTheme,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val playButtonFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { playButtonFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .width(680.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.panel)
            .border(2.dp, theme.highlight, RoundedCornerShape(8.dp))
            .padding(24.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Poster
            Box(
                modifier = Modifier
                    .width(170.dp)
                    .aspectRatio(0.67f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(theme.background),
                contentAlignment = Alignment.Center,
            ) {
                if (!info.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = info.coverUrl,
                        contentDescription = info.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(text = "NO POSTER", color = theme.infoDetail)
                }
            }

            Spacer(Modifier.width(20.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.name,
                    color = theme.infoTitle,
                    fontSize = theme.titleSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = theme.titleWeight,
                )

                Spacer(Modifier.height(4.dp))

                val meta = listOfNotNull(
                    info.releaseDate?.take(4),
                    info.duration?.let { "$it mins" },
                    info.rating?.takeIf { it.isNotBlank() }?.let { "Rating: $it" },
                ).joinToString("  •  ")

                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        color = theme.highlight,
                        fontSize = theme.detailSize,
                        fontFamily = theme.fontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                val description = info.description
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        color = theme.cellText,
                        fontSize = theme.detailSize,
                        fontFamily = theme.fontFamily,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (!info.cast.isNullOrBlank()) {
                    Text(
                        text = "Cast: ${info.cast}",
                        color = theme.infoDetail,
                        fontSize = theme.sectionSize,
                        fontFamily = theme.fontFamily,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(14.dp))
                }

                // Action buttons
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    var playFocus by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .focusRequester(playButtonFocus)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (playFocus) theme.highlight else theme.tabFillCurrent)
                            .border(1.dp, theme.highlight, RoundedCornerShape(4.dp))
                            .onFocusChanged { playFocus = it.isFocused }
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                    onPlay()
                                    true
                                } else false
                            }
                            .clickable { onPlay() }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "▶ PLAY MOVIE",
                            color = if (playFocus) theme.background else theme.cellText,
                            fontSize = theme.detailSize,
                            fontFamily = theme.fontFamily,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    var closeFocus by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (closeFocus) theme.panelSelected else theme.panel)
                            .border(1.dp, theme.panelEdge, RoundedCornerShape(4.dp))
                            .onFocusChanged { closeFocus = it.isFocused }
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                    onDismiss()
                                    true
                                } else false
                            }
                            .clickable { onDismiss() }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "CLOSE",
                            color = theme.infoDetail,
                            fontSize = theme.detailSize,
                            fontFamily = theme.fontFamily,
                        )
                    }
                }
            }
        }
    }
}

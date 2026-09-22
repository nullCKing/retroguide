package com.retroguide.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retroguide.data.db.CategoryEntity
import com.retroguide.ui.theme.GuideTheme

data class CategoryItem(
    val id: String?,
    val name: String,
    val isFavorite: Boolean = false,
    val isSpecial: Boolean = false,
    val count: Int? = null,
)

const val ALL_CHANNELS_ID = "__all__"
const val FAVORITE_CHANNELS_ID = "__favorites__"

/**
 * Screen presenting live TV categories before entering the channel guide.
 * Allows filtering by category and favoriting channel groups.
 */
@Composable
fun LiveCategoriesScreen(
    categories: List<CategoryEntity>,
    favoriteCategoryIds: Set<String>,
    totalChannelCount: Int,
    favoriteChannelCount: Int,
    categoryChannelCounts: Map<String, Int> = emptyMap(),
    theme: GuideTheme,
    onSelectCategory: (categoryId: String?, categoryName: String) -> Unit,
    onToggleFavoriteCategory: (categoryId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Build combined list: All Channels, Favorite Channels, Favorite Groups, and All Categories
    val items = remember(categories, favoriteCategoryIds, totalChannelCount, favoriteChannelCount, categoryChannelCounts) {
        val list = ArrayList<CategoryItem>()

        // 1. All Channels
        list.add(
            CategoryItem(
                id = ALL_CHANNELS_ID,
                name = "ALL CHANNELS",
                isSpecial = true,
                count = totalChannelCount,
            )
        )

        // 2. Favorite Channels
        if (favoriteChannelCount > 0) {
            list.add(
                CategoryItem(
                    id = FAVORITE_CHANNELS_ID,
                    name = "★ FAVORITE CHANNELS",
                    isSpecial = true,
                    count = favoriteChannelCount,
                )
            )
        }

        // 3. Pinned Favorite Categories
        val favCategories = categories.filter { it.categoryId in favoriteCategoryIds }
        if (favCategories.isNotEmpty()) {
            for (cat in favCategories) {
                list.add(
                    CategoryItem(
                        id = cat.categoryId,
                        name = "★ ${cat.name}",
                        isFavorite = true,
                        count = categoryChannelCounts[cat.categoryId],
                    )
                )
            }
        }

        // 4. All Categories
        for (cat in categories) {
            val isFav = cat.categoryId in favoriteCategoryIds
            list.add(
                CategoryItem(
                    id = cat.categoryId,
                    name = cat.name,
                    isFavorite = isFav,
                    count = categoryChannelCounts[cat.categoryId],
                )
            )
        }
        list
    }

    val listState = rememberLazyListState()
    var selectedIndex by remember { mutableStateOf(0) }
    val focusRequesters = remember(items.size) { List(items.size) { FocusRequester() } }

    LaunchedEffect(Unit) {
        if (focusRequesters.isNotEmpty()) {
            focusRequesters[0].requestFocus()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(horizontal = 48.dp, vertical = 28.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "LIVE TV CATEGORIES",
                        color = theme.infoTitle,
                        fontSize = theme.titleSize,
                        fontFamily = theme.fontFamily,
                        fontWeight = theme.titleWeight,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Select a category to view the guide  •  Press PLAY/PAUSE to favorite a group",
                        color = theme.infoDetail,
                        fontSize = theme.detailSize,
                        fontFamily = theme.fontFamily,
                    )
                }

                Text(
                    text = "${categories.size} Categories",
                    color = theme.highlight,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(20.dp))

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items) { index, item ->
                    val requester = focusRequesters.getOrNull(index) ?: remember { FocusRequester() }
                    var hasFocus by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (hasFocus) theme.panelSelected else theme.panel)
                            .border(
                                width = if (hasFocus) 2.dp else 1.dp,
                                color = if (hasFocus) theme.highlight else theme.panelEdge,
                                shape = RoundedCornerShape(6.dp),
                            )
                            .focusRequester(requester)
                            .onFocusChanged {
                                hasFocus = it.isFocused
                                if (it.isFocused) selectedIndex = index
                            }
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionCenter, Key.Enter -> {
                                            when (item.id) {
                                                ALL_CHANNELS_ID -> onSelectCategory(null, "ALL CHANNELS")
                                                FAVORITE_CHANNELS_ID -> onSelectCategory(FAVORITE_CHANNELS_ID, "FAVORITE CHANNELS")
                                                else -> onSelectCategory(item.id, item.name.removePrefix("★ "))
                                            }
                                            true
                                        }
                                        Key.MediaPlayPause, Key.MediaPlay -> {
                                            if (item.id != null && !item.isSpecial) {
                                                onToggleFavoriteCategory(item.id)
                                                true
                                            } else false
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item.isFavorite) {
                                Text(
                                    text = "★ ",
                                    color = theme.highlight,
                                    fontSize = theme.titleSize,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                text = item.name,
                                color = if (hasFocus) theme.cellText else theme.infoTitle,
                                fontSize = theme.detailSize,
                                fontFamily = theme.fontFamily,
                                fontWeight = if (item.isSpecial || item.isFavorite) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (item.count != null) {
                            Text(
                                text = "${item.count} channels",
                                color = theme.infoDetail,
                                fontSize = theme.sectionSize,
                                fontFamily = theme.fontFamily,
                            )
                        } else if (!item.isSpecial) {
                            Text(
                                text = if (item.isFavorite) "[ Favorited Group ]" else "Press PLAY to favorite",
                                color = if (item.isFavorite) theme.highlight else theme.infoDetail,
                                fontSize = theme.sectionSize,
                                fontFamily = theme.fontFamily,
                            )
                        }
                    }
                }
            }
        }
    }
}

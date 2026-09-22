package com.retroguide.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.retroguide.core.guide.ProgramSlot
import com.retroguide.domain.GuideChannel
import com.retroguide.ui.GuideState
import com.retroguide.ui.theme.GuideTheme

/**
 * The whole guide screen: info panel and preview across the top, the drawn grid below.
 *
 * The 40/60 split, the info panel's layout and the preview window's placement follow the DirecTV
 * references. Everything inside [GuideTheme.safeMarginFraction] of the edges, so nothing is lost
 * to overscan on an older set.
 */
@Composable
fun GuideScreen(
    state: GuideState,
    theme: GuideTheme,
    previewContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = (960.dp * theme.safeMarginFraction),
                    vertical = (540.dp * theme.safeMarginFraction),
                )
        ) {
            InfoPanel(
                state = state,
                theme = theme,
                previewContent = previewContent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(theme.infoPanelHeight),
            )

            Spacer(Modifier.height(10.dp))

            if (state.channels.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No channels match the current filter.\nPress MENU to change it.",
                        color = theme.infoDetail,
                        fontSize = theme.detailSize,
                        fontFamily = theme.fontFamily,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                GuideGrid(
                    channels = state.channels,
                    programsByKey = state.programsByKey,
                    window = state.cursor.window,
                    firstVisibleRow = state.cursor.firstVisibleRow,
                    selectedRow = state.cursor.channelIndex,
                    selectedProgramId = state.selected?.id,
                    nowMs = state.nowMs,
                    theme = theme,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        state.details?.let { slot ->
            ProgramDetailsDialog(slot = slot, theme = theme, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The upper section: the highlighted programme's details on the left, the live preview on the
 * right, and the date and clock in the top corner.
 */
@Composable
private fun InfoPanel(
    state: GuideState,
    theme: GuideTheme,
    previewContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slot = state.selected
    val channel = state.selectedChannel
    val isFuture = slot != null && slot.startMs > state.nowMs

    Row(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(end = 16.dp)
        ) {
            Text(
                text = "PROGRAM GUIDE",
                color = theme.infoDetail,
                fontSize = theme.sectionSize,
                fontFamily = theme.fontFamily,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = state.categoryName.uppercase(),
                color = theme.infoDetail,
                fontSize = theme.sectionSize,
                fontFamily = theme.fontFamily,
            )
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!channel?.logoUrl.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = channel?.logoUrl,
                        contentDescription = channel?.name,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .padding(end = 8.dp),
                    )
                }
                Text(
                    text = slot?.title ?: "—",
                    color = theme.infoTitle,
                    fontSize = theme.titleSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = theme.titleWeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))

            // Rating • start-end • "description", wrapping over several lines, as in the reference.
            Text(
                text = buildDetailLine(slot, channel),
                color = theme.infoDetail,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = headerClock(state.nowMs),
                color = theme.clock,
                fontSize = theme.clockSize,
                fontFamily = theme.fontFamily,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .size(width = theme.previewWidth, height = theme.previewHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(theme.panel)
                    .border(1.dp, theme.panelEdge, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (isFuture) {
                    // The reference replaces the video with this message box when the highlight
                    // is on something that has not started yet.
                    Text(
                        text = "Future program.\nPress SELECT for options.",
                        color = theme.cellText,
                        fontSize = theme.detailSize,
                        fontFamily = theme.fontFamily,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    previewContent(Modifier.fillMaxSize())
                }
            }
        }
    }
}

private fun buildDetailLine(slot: ProgramSlot?, channel: GuideChannel?): String {
    if (slot == null) return ""
    val parts = ArrayList<String>(4)
    channel?.let {
        val fav = if (it.isFavorite) " ★" else ""
        parts.add("${it.number} ${it.shortName}$fav")
    }
    slot.rating?.takeIf { it.isNotBlank() }?.let(parts::add)
    parts.add(timeRange(slot.startMs, slot.endMs))
    val head = parts.joinToString("  •  ")
    val description = slot.description.takeIf { it.isNotBlank() }
    return if (description != null) "$head\n\"$description\"" else head
}

/** Shown when Select lands on a programme that has not started yet. */
@Composable
private fun ProgramDetailsDialog(
    slot: ProgramSlot,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(androidx.compose.ui.graphics.Color(0xAA000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(theme.panel)
                .border(2.dp, theme.highlight, RoundedCornerShape(6.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = slot.title,
                color = theme.infoTitle,
                fontSize = theme.titleSize,
                fontFamily = theme.fontFamily,
                fontWeight = theme.titleWeight,
            )
            Text(
                text = timeRange(slot.startMs, slot.endMs) +
                    (slot.rating?.let { "  •  $it" } ?: ""),
                color = theme.infoDetail,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
            )
            if (slot.description.isNotBlank()) {
                Text(
                    text = slot.description,
                    color = theme.cellText,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                )
            }
            Text(
                text = "Press BACK to close",
                color = theme.infoDetail,
                fontSize = theme.sectionSize,
                fontFamily = theme.fontFamily,
            )
        }
    }
}
